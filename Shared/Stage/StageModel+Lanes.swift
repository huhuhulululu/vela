import Foundation
import os

/// 双窗（§4）：副路的开关、焦点、横屏释放。macOS 的 `supportsDual` 恒为假，这里的入口都直接返回。
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

    /// 翻页要不要加载下一页：以两路里排得最靠后的房间为锚（单窗就是主路）。
    var furthestLaneUsername: String? {
        lanes.compactMap { name(of: $0) }.max { lhs, rhs in
            let left = feed.rooms.firstIndex { $0.username == lhs } ?? 0
            let right = feed.rooms.firstIndex { $0.username == rhs } ?? 0
            return left < right
        }
    }

    /// 两路房间是否互不相同（空路算不同）。日志只记结论，不记房间名。
    var lanesDistinct: Bool {
        guard let secondary, let first = primary.username?.lowercased(), let second = secondary.username?.lowercased() else { return true }
        return first != second
    }

    func logLaneRooms() {
        guard secondary != nil else { return }
        Self.lanesLog.info("lane-rooms distinct=\(self.lanesDistinct, privacy: .public)")
    }

    // MARK: 开关

    /// 开关双窗并记住（裁决 1）。同值不动。
    func setDual(_ on: Bool, remember: Bool = true) {
        // 画中画小窗开着（或在启动）时不切双窗：小窗里放的就是焦点路，关副路会拆掉它，
        // 开副路会让新的一路在画中画期间起播。等小窗收掉后再切。
        guard platform.supportsDual, on != dualWindow, !platform.focusLocked else { return }
        if on { openSecondary() } else { closeSecondary() }
        if remember { settings.phoneDual = on }
        Self.lanesLog.info("dual \(on ? "on" : "off", privacy: .public)")
    }

    /// 启动时恢复上次的开关：不写设置，列表还没到时副路先空着，列表到达后由 `LaneSelection` 选房。
    func restoreDualIfRemembered() {
        guard platform.supportsDual, settings.phoneDual, !dualWindow else { return }
        openSecondary()
    }

    private func openSecondary() {
        let lane = PlaybackLane(id: .secondary)
        lane.dwell.setFocused(false, now: Date())
        lane.visible = !(landscape || pipSolo)
        secondary = lane
        attach(lane)
        dualWindow = true
        // 副路取列表里主路之后的第一间；只剩一间或列表为空时留空（§4.1）。
        let outcome = LaneSelection.resolve(
            before: laneSnapshot(of: feed), afterRooms: feed.rooms.map(\.username),
            proposedPrimary: feed.currentUsername, intent: .reload
        )
        settleSecondary(lane, outcome: outcome)
        syncPlaybackFace()
        applyPower()
        logLaneRooms()
    }

    private func closeSecondary() {
        guard let lane = secondary else { return }
        // 焦点在副路时，留下的是焦点路的房间：先走一次有序的声音转换，再让主路换到那一间。
        let keep = focusLane == .secondary ? lane.username : nil
        if focusLane == .secondary { setFocus(.primary) }
        record(lane.dwell.finish(now: Date()), from: lane, reason: .dualOff)
        lane.relatedTask?.cancel()
        release(lane)
        laneForwarders[.secondary] = nil
        secondary = nil
        dualWindow = false
        focusLane = .primary
        primary.dwell.setFocused(true, now: Date())
        if !primary.visible { primary.visible = true }
        if let keep, keep.lowercased() != feed.currentUsername?.lowercased() {
            updateFeed(intent: .reveal(keep), endReason: .dualOff) { $0.select(keep) }
            primary.player.forgetSuspendedResume()
            requestPlay()
        }
        applyPower()
        scheduleRelated(on: primary)
    }

    // MARK: 焦点

    /// 切焦点：出声时先静音旧的出声路、确认会话、再让新焦点路出声（§3.3），然后才改焦点与停留口径。
    func setFocus(_ id: LaneID) {
        // 画中画里的那一路就是焦点路，小窗开着时不能换掉它。
        guard dualWindow, secondary != nil, id != focusLane, !platform.focusLocked else { return }
        let outcome = audioFocus?.switchFocus(to: id.role)
        focusLane = id
        let now = Date()
        for lane in lanes { lane.dwell.setFocused(lane.id == id, now: now) }
        if let outcome { applyAudioOutcome(outcome) }
        for lane in lanes { pushVolume(to: lane) }
        applyLaneVisibility()
        for lane in lanes { scheduleRelated(on: lane) }
        dismissFavoriteSuggestIfMismatch()
        scheduleFavoriteSuggestWake()
        Self.lanesLog.info("focus \(Self.label(id.role), privacy: .public)")
    }

    // MARK: 只留焦点路（横屏、画中画）

    /// 横屏只显示焦点路：非焦点路立刻释放；回竖屏用 `requestPlay(on:)` 恢复（§4.2）。
    func setLandscape(_ on: Bool) {
        guard on != landscape else { return }
        landscape = on
        applyLaneVisibility()
    }

    /// 画中画进入时只留焦点路，退出（或启动失败）后恢复双窗。单窗不动。
    func setPipSolo(_ on: Bool) {
        guard on != pipSolo else { return }
        pipSolo = on
        applyLaneVisibility()
    }

    /// 按「横屏或画中画 ∧ 非焦点」重算每一路的可见性。隐藏的路释放播放项与中继源；
    /// 显示回来的路按生命周期的恢复判定重新起播（挂起期间只改可见性，回前台由生命周期恢复）。
    private func applyLaneVisibility() {
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
    func applyDirectSearchHit(_ room: Room) {}
}
