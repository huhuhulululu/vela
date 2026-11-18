import Foundation
import os

/// 设备热状态下的画质（§4.4，裁决 3）。两路平时都用最高档、不封顶；热状态到 `.serious` 及以上时，
/// 每一路按自己画面实际显示的高度（`displaySize`，不是窗格高度）降档；回到 `.fair` 及以下并持续 60 秒后回升。
/// macOS 不订阅热状态，`laneQuality` 恒为未降档，所有路的上限恒为 `.infinity`。
@MainActor
extension StageModel {
    static let thermalLog = Logger(subsystem: "com.vela.stage", category: "thermal")

    /// 这一路的热状态上限。未降档为 `.infinity`；降档时按画面实际显示高度乘显示倍率查档，还没有画面尺寸时落到下限。
    func thermalCap(for lane: PlaybackLane) -> Double {
        laneQuality.peakBitrate(displayHeight: Double(lane.displaySize.height), screenScale: screenScale)
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

    /// 播放层回报画面实际显示尺寸。降档期间尺寸变化会改变档位（旋转、副路先落到下限再量到真实高度），
    /// 下一个主线程回合再补换档，不在布局回调里直接动播放器。
    func noteDisplaySize(_ size: CGSize, on lane: PlaybackLane) {
        guard lane.displaySize != size else { return }
        lane.displaySize = size
        guard laneQuality.isLimited, needsCapSync(lane) else { return }
        DispatchQueue.main.async { [weak self, weak lane] in
            guard let self, let lane, self.needsCapSync(lane) else { return }
            self.retier(lane)
        }
    }
}
