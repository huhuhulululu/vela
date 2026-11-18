import Foundation
import os

/// 多路画质上限（§4.4，裁决 3，ios-triple 修订 v2 H2）：最终上限 = min(尺寸上限, 热上限)。
/// 显示 1、2 路时尺寸上限不限；3 路时按每格画面实际显示的高度（`displaySize`，还没出画面时按格子里 16:9 画面估算）。
/// 热状态 `.serious` 降一档、`.critical` 降两档，以尺寸上限为基准；回到 `.fair` 及以下并持续 60 秒后回升到尺寸上限。
/// macOS 不订阅热状态、只有一路，所有路的上限恒为 `.infinity`。
@MainActor
extension StageModel {
    static let thermalLog = Logger(subsystem: "com.vela.stage", category: "thermal")

    /// 这一路的画质上限：min(尺寸上限, 热上限)。
    func laneCap(for lane: PlaybackLane) -> Double {
        laneQuality.cap(displayHeight: estimatedDisplayHeight(lane), screenScale: screenScale, shownLanes: shownLaneCount)
    }

    /// 此刻竖屏同时显示几格：横屏与画中画只显示焦点路。
    var shownLaneCount: Int { landscape || pipSolo ? 1 : lanes.count }

    /// 画面显示高度（点）：已出画面用 `videoRect`；还没有就按格子尺寸里 16:9 画面的高度估算；都没有为 0。
    func estimatedDisplayHeight(_ lane: PlaybackLane) -> Double {
        if lane.displaySize.height > 0 { return Double(lane.displaySize.height) }
        let pane = lane.paneSize
        guard pane.width > 0, pane.height > 0 else { return 0 }
        return Double(min(pane.height, pane.width * 9 / 16))
    }

    /// 布局变了（路数、旋转、画中画进出、格子尺寸）：上限变了的在播路原地换档，没有播放项的路下次起播带新上限。
    func syncLaneCaps() {
        for lane in lanes where needsCapSync(lane) { retier(lane) }
    }

    /// 开始订阅系统热状态（只有支持双窗的平台）。启动时已经过热也要按当前状态起算。
    func startThermalObserving() {
        guard platform.supportsDual, thermalObserver == nil else { return }
        thermalObserver = NotificationCenter.default.addObserver(
            forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.noteThermal(PhoneThermalState(ProcessInfo.processInfo.thermalState)) }
        }
        noteThermal(PhoneThermalState(ProcessInfo.processInfo.thermalState))
    }

    /// 收到一次热状态。降档与否变了就让各路按新上限换档；降档后回到 fair 只排一次性的回升检查，不留空转定时器。
    func noteThermal(_ state: PhoneThermalState, now: Date = Date()) {
        let wasLimited = laneQuality.isLimited
        laneQuality.observe(state, now: now)
        thermalState = state
        thermalRecoveryTask?.cancel()
        thermalRecoveryTask = nil
        if let wait = laneQuality.recoveryRemaining(now: now) {
            thermalRecoveryTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64((wait + 0.05) * 1_000_000_000))
                guard !Task.isCancelled, let self else { return }
                self.thermalRecoveryTask = nil
                self.noteThermal(self.thermalState)
            }
        }
        guard laneQuality.isLimited != wasLimited else { return }
        let word = laneQuality.isLimited ? "limit" : "recover"
        Self.thermalLog.info("thermal \(word, privacy: .public) state=\(state.rawValue)")
        for lane in lanes {
            let cap = playbackCap(on: lane)
            Self.thermalLog.info("thermal-cap lane=\(Self.label(lane.id.role), privacy: .public) cap=\(cap.isFinite ? Int(cap) : -1)")
            retier(lane)
        }
    }

    /// 这一路要换到的上限；与现在用的相同就不用换。
    func retargetCap(for lane: PlaybackLane) -> Double? {
        let cap = playbackCap(on: lane)
        return cap == lane.currentCap ? nil : cap
    }

    /// 在播的一路按新上限换档：现取主列表、选档、原地换（不重新起播）。取不到或这一路已换房就放弃，
    /// 下次起播会带着新上限。挂起期间不做，回前台重新起播时自然取新上限。
    func retier(_ lane: PlaybackLane) {
        lane.capSyncRequests += 1
        lane.capTask?.cancel()
        lane.capTask = nil
        guard lane.player.hasItem, laneAllowed(lane), let username = name(of: lane), retargetCap(for: lane) != nil else { return }
        let token = lane.playGeneration
        lane.capTask = Task { [weak self, weak lane] in
            guard let self, let lane, let life = self.laneToken(lane) else { return }
            // 固定测试流（Perf）不访问真实站点：直接沿用起播时的回环主列表。
            var fixed = false
            #if (os(macOS) && (DEBUG || PERF_PROBE)) || (os(iOS) && PERF_PROBE)
            fixed = username.hasPrefix(PerfStream.username)
            #endif
            let fresh = fixed ? nil : try? await self.resolve(username)
            guard let master = (fresh?.isPlayable == true ? fresh?.url : nil) ?? lane.currentMaster else { return }
            guard token == lane.playGeneration, !Task.isCancelled, self.name(of: lane) == username else { return }
            let cap = self.playbackCap(on: lane)
            let playable = await self.selectRendition(master: master, cap: cap)
            guard token == lane.playGeneration, !Task.isCancelled, self.name(of: lane) == username,
                  self.lifecycleAllows(.beforeCommit, life) else { return }
            if !playable.isUnselected { self.prepared.set(playable, cap: cap, for: username, now: Date()) }
            guard lane.player.retier(rendition: playable, username: username, peakBitrate: cap) else { return }
            lane.currentMaster = master
            lane.currentPlayable = playable
            lane.currentCap = cap
            let label = Self.label(lane.id.role)
            Self.thermalLog.info("thermal-retier lane=\(label, privacy: .public) cap=\(cap.isFinite ? Int(cap) : -1)")
        }
    }
}

extension PhoneThermalState {
    /// 系统热状态。没见过的新档位按 `.serious` 处理：宁可降档。
    init(_ system: ProcessInfo.ThermalState) {
        switch system {
        case .nominal: self = .nominal
        case .fair: self = .fair
        case .serious: self = .serious
        case .critical: self = .critical
        @unknown default: self = .serious
        }
    }
}

extension StageModel {
    /// 这一路正在用的上限与此刻应有的上限不一致，需要补换档（起播期间热状态变了、画面尺寸量到真实值后上限变了）。
    func needsCapSync(_ lane: PlaybackLane) -> Bool {
        retargetCap(for: lane) != nil
    }

    /// 播放层回报画面实际显示尺寸。三格或降档期间尺寸变化会改变档位（旋转、新路先按估算再量到真实高度），
    /// 下一个主线程回合再补换档，不在布局回调里直接动播放器。上限没变什么都不做。
    func noteDisplaySize(_ size: CGSize, on lane: PlaybackLane) {
        guard lane.displaySize != size else { return }
        lane.displaySize = size
        scheduleCapSync(lane)
    }

    /// 界面回报这一格的尺寸（点）。同值不动，不发布。
    func notePaneSize(_ size: CGSize, on lane: PlaybackLane) {
        guard lane.paneSize != size else { return }
        lane.paneSize = size
        scheduleCapSync(lane)
    }

    private func scheduleCapSync(_ lane: PlaybackLane) {
        guard needsCapSync(lane) else { return }
        DispatchQueue.main.async { [weak self, weak lane] in
            guard let self, let lane, self.needsCapSync(lane) else { return }
            self.retier(lane)
        }
    }
}
