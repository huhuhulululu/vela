import Foundation
import os

/// 多路（§4，ios-triple 计划）：路数的增减与压紧、焦点、横屏与画中画的临时释放。
/// macOS 的 `supportsDual` 恒为假，这里的入口都直接返回。
@MainActor
extension StageModel {
    /// 焦点路：声音、收藏提示、热门关联、画中画都跟随它。
    var focusPlaybackLane: PlaybackLane { findLane(focusLane) ?? primary }

    /// 焦点路此刻的房间。收藏提示、心形、收藏夹的「当前房间」都看它；单窗就是 `feed.current`。
    var focusRoom: Room? { room(of: focusPlaybackLane) }

    /// 点下去的目标，并要求这一路此刻仍停在界面上看到的那一间；换了就放弃（菜单展开期间可能被自动跳过）。
    func target(_ id: LaneID, showing username: String) -> RoomTarget? {
        guard let target = target(id), target.username.lowercased() == username.lowercased() else { return nil }
        return target
    }

    /// 这一路的房间在可见列表里的位置（分页用）。主路就是 `feed.index`。
    func index(of lane: PlaybackLane) -> Int {
        if lane.id == .primary { return feed.index }
        guard let name = lane.username?.lowercased() else { return 0 }
        return feed.rooms.firstIndex { $0.username.lowercased() == name } ?? 0
    }

    /// 翻页要不要加载下一页：以各路里排得最靠后的房间为锚（单窗就是主路）。
    var furthestLaneUsername: String? {
        lanes.compactMap { name(of: $0) }.max { lhs, rhs in
            let left = feed.rooms.firstIndex { $0.username == lhs } ?? 0
            let right = feed.rooms.firstIndex { $0.username == rhs } ?? 0
            return left < right
        }
    }

    /// 各路房间是否两两不同（空路算不同）。日志只记结论，不记房间名。
    var lanesDistinct: Bool {
        let taken = lanes.compactMap { $0.username?.lowercased() }
        return Set(taken).count == taken.count
    }

    func logLaneRooms() {
        guard lanes.count > 1 else { return }
        Self.lanesLog.info("lane-rooms distinct=\(self.lanesDistinct, privacy: .public) lanes=\(self.lanes.count)")
    }

    // MARK: 路数

    /// 此设备竖屏最多开几路（修订 v2 M12：每格高度不足 160 点的机型最多 2 路）。
    var maxLaneCount: Int { platform.supportsDual ? (threeLanesFit ? LaneID.maxCount : 2) : 1 }

    /// 选择路数并记住（`remember` 为 false 时是临时调整，不覆盖用户选择）。同值不动；画中画开着（或在启动）时不改：
    /// 小窗里放的就是焦点路，改路数会拆掉它或让新的一路在画中画期间起播。
    func setLaneCount(_ requested: Int, remember: Bool = true) {
        guard platform.supportsDual, !platform.focusLocked else { return }
        let next = min(max(requested, 1), maxLaneCount)
        guard next != laneCount else {
            // 逻辑路数不变（例如屏幕变矮临时降到 2 路后用户明确选 2 路），用户选择仍要记住；同值不写。
            if remember, requested == next, settings.laneCount != next { settings.laneCount = next }
            return
        }
        if next > laneCount { openLanes(upTo: next) } else { closeLanes(keeping: next) }
        syncLaneCaps()
        if remember { settings.laneCount = next }
        Self.lanesLog.info("lanes \(next)")
    }

    /// 旧的双窗开关入口：开 = 至少 2 路（已是 3 路不降），关 = 单窗。
    func setDual(_ on: Bool, remember: Bool = true) {
        if on {
            guard laneCount < 2 else { return }
            setLaneCount(2, remember: remember)
        } else {
            setLaneCount(1, remember: remember)
        }
    }

    /// 界面量到舞台尺寸（点，横屏时按竖屏方向理解：宽取短边、高取长边）。三格每格不足 160 点就最多 2 路（修订 v2 M12）：
    /// 已开 3 路的临时降到 2 路，不改用户选择；又放得下时按用户选择恢复。同值不动。
    func notePortraitStageSize(_ size: CGSize) {
        let portrait = CGSize(width: min(size.width, size.height), height: max(size.width, size.height))
        guard portrait != portraitStageSize else { return }
        portraitStageSize = portrait
        let fits = LaneLayout.fitsThreeLanes(stageHeight: Double(portrait.height))
        guard fits != threeLanesFit else { return }
        threeLanesFit = fits
        if fits {
            restoreLanesIfRemembered()
        } else if laneCount > maxLaneCount {
            setLaneCount(maxLaneCount, remember: false)
        }
    }

    /// 启动时恢复上次的路数：不写设置，列表还没到时新路先空着，列表到达后由 `LaneSelection` 选房。
    func restoreLanesIfRemembered() {
        let remembered = min(settings.laneCount, maxLaneCount)
        guard platform.supportsDual, remembered > laneCount else { return }
        openLanes(upTo: remembered)
    }

    /// 按 P → S → T 补开到 `count` 路；新路沿前一路往后补位（`.fill`），已有的路不动。
    private func openLanes(upTo count: Int) {
        let now = Date()
        var opened: [PlaybackLane] = []
        for id in LaneID.active(count: count) where findLane(id) == nil {
            let lane = PlaybackLane(id: id)
            lane.dwell.setFocused(false, now: now)
            lane.visible = !(landscape || pipSolo)
            install(lane)
            attach(lane)
            opened.append(lane)
        }
        laneCount = lanes.count
        let outcome = LaneSelection.resolve(
            before: laneSnapshot(of: feed), afterRooms: feed.rooms.map(\.username),
            proposedPrimary: feed.currentUsername, intent: .fill
        )
        // `.fill` 给所有空路分房：新开的路与原本就空着的非主路一并提交（有房间的路它不动）。
        for lane in lanes where lane.id != .primary && (lane.username == nil || opened.contains { $0 === lane }) {
            settleLane(lane, outcome: outcome)
        }
        syncPlaybackFace()
        applyPower()
        logLaneRooms()
    }

    /// 用户减少路数（修订 v2 H1）：保留焦点路，再按 P → S → T 补足名额；被移除的路结算停留（布局变更）后丢弃。
    /// 留下的路按 P → S → T 压紧：主路的名额由焦点房间搬过去（先切声音再迁房间）；第三路要顶到副路时整体迁移状态。
    private func closeLanes(keeping count: Int) {
        var moveToPrimary: String?
        if count == 1 {
            // 只留一路：主路总在，焦点房间搬到主路（先切声音再迁房间），其余各路移除。
            if focusLane != .primary {
                moveToPrimary = focusPlaybackLane.username
                setFocus(.primary)
            }
            for lane in lanes where lane.id != .primary { remove(lane) }
        } else {
            var kept: Set<LaneID> = [focusLane]
            for id in LaneID.allCases where kept.count < count && findLane(id) != nil { kept.insert(id) }
            for lane in lanes where !kept.contains(lane.id) { remove(lane) }
            if let lone = tertiary, secondary == nil { compact(lone, into: .secondary) }
        }
        laneCount = lanes.count
        if laneCount == 1 {
            focusLane = .primary
            primary.dwell.setFocused(true, now: Date())
            if !primary.visible { primary.visible = true }
        }
        if let keep = moveToPrimary, keep.lowercased() != feed.currentUsername?.lowercased() {
            updateFeed(intent: .reveal(keep), endReason: .layoutChange) { $0.select(keep) }
            primary.player.forgetSuspendedResume()
            requestPlay()
        }
        // 路数减少，解码负载降了：被资源降级扣住的路各重试一次（H3）。
        for lane in lanes { retryDegraded(lane, reason: .laneReduced) }
        applyLaneVisibility()
        applyPower()
        for lane in lanes { scheduleRelated(on: lane) }
        logLaneRooms()
    }

    private func install(_ lane: PlaybackLane) {
        switch lane.id {
        case .primary: break
        case .secondary: secondary = lane
        case .tertiary: tertiary = lane
        }
    }

    /// 移除一路：结算停留（布局变更，不记弱落空），取消它的工作，释放播放项与中继源。
    private func remove(_ lane: PlaybackLane) {
        record(lane.dwell.finish(now: Date()), from: lane, reason: .layoutChange)
        lane.relatedTask?.cancel()
        lane.degradedRetryTask?.cancel()
        lane.degradedRetryTask = nil
        release(lane)
        laneForwarders[lane.id] = nil
        switch lane.id {
        case .primary: break
        case .secondary: secondary = nil
        case .tertiary: tertiary = nil
        }
    }

    /// 第三路整体迁成副路：状态、播放器、在途任务都不动，焦点随迁（一次提交内完成）。
    private func compact(_ lane: PlaybackLane, into slot: LaneID) {
        let wasFocus = focusLane == lane.id
        laneForwarders[lane.id] = nil
        tertiary = nil
        lane.reassign(to: slot)
        install(lane)
        laneForwarders[slot] = lane.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        if wasFocus { focusLane = slot }
        Self.lanesLog.info("lane compact tertiary -> \(slot.label, privacy: .public)")
    }

    // MARK: 焦点

    /// 切焦点：出声时先静音旧的出声路、确认会话、再让新焦点路出声（§3.3），然后才改焦点与停留口径。
    func setFocus(_ id: LaneID) {
        // 画中画里的那一路就是焦点路，小窗开着时不能换掉它。焦点不能落在没开的路上。
        guard lanes.count > 1, let target = findLane(id), id != focusLane, !platform.focusLocked else { return }
        let outcome = audioFocus?.switchFocus(to: id.role)
        focusLane = id
        let now = Date()
        for lane in lanes { lane.dwell.setFocused(lane.id == id, now: now) }
        if let outcome { applyAudioOutcome(outcome) }
        for lane in lanes { pushVolume(to: lane) }
        applyLaneVisibility()
        // 焦点切到被资源降级扣住的路：它优先，重试一次（H3）。
        retryDegraded(target, reason: .focusGained)
        for lane in lanes { scheduleRelated(on: lane) }
        dismissFavoriteSuggestIfMismatch()
        scheduleFavoriteSuggestWake()
        Self.lanesLog.info("focus \(id.label, privacy: .public)")
    }

    // MARK: 只留焦点路（横屏、画中画）

    /// 横屏只显示焦点路：非焦点路立刻释放；回竖屏用 `requestPlay(on:)` 恢复（§4.2）。
    func setLandscape(_ on: Bool) {
        guard on != landscape else { return }
        landscape = on
        applyLaneVisibility()
        syncLaneCaps()
    }

    /// 画中画进入时只留焦点路，退出（或启动失败）后按原路数恢复。单窗不动。
    func setPipSolo(_ on: Bool) {
        guard on != pipSolo else { return }
        pipSolo = on
        applyLaneVisibility()
        syncLaneCaps()
    }

    /// 按「横屏或画中画 ∧ 非焦点」重算每一路的可见性（临时释放：只放播放资源，逻辑路状态全部保留）。隐藏的路释放播放项与中继源；
    /// 显示回来的路按生命周期的恢复判定重新起播（挂起期间只改可见性，回前台由生命周期恢复）。
    func applyLaneVisibility() {
        guard dualWindow else { return }
        let solo = landscape || pipSolo
        for lane in lanes {
            let shown = !solo || lane.id == focusLane
            guard lane.visible != shown else { continue }
            lane.visible = shown
            if shown {
                lane.player.forgetSuspendedResume()
                applyPower()
                if !lifecycleSuspended, PhoneLifecycle.shouldPlay(phoneSnapshot(of: lane)) {
                    resumePlay(lane, activity: .idle)
                }
            } else {
                release(lane)
                applyPower()
            }
        }
    }

    static let lanesLog = Logger(subsystem: "com.vela.stage", category: "lanes")
}

extension StageModel {
    /// 界面「当前房间」对应的目标：焦点路（收藏夹弹窗与重试菜单用；单窗恒为主路）。
    func focusTarget() -> RoomTarget? { target(focusLane) }
    /// 全站搜索直达：房间插进列表并播放。按 `.reveal` 走联合选房：目标正在别的路播放时只切焦点，
    /// 不会把它挤成两路同房；其余情况主路改到它。
    func applyDirectSearchHit(_ room: Room) {
        let before = feed.currentUsername
        updateFeed(intent: .reveal(room.username)) { $0.insertDirect(room) }
        showFilters = false
        if secondary == nil || feed.currentUsername != before { requestPlay() }
    }
}
