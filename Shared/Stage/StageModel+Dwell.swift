import Foundation
import os

@MainActor
extension StageModel {
    /// 当前房间变了：结算上一个房间的停留，新房间等播放真正起来再计时，满阈值后做热门关联。
    func currentRoomChanged(on given: PlaybackLane? = nil, reason: LaneSettlement.EndReason = .swipe) {
        let lane = given ?? primary
        lane.relatedTask?.cancel()
        record(lane.dwell.switchTo(room(of: lane), now: Date()), from: lane, reason: reason)
        lane.dwellSource = SiteSearch.dwellSource(loaded: loadedSource, searching: siteSearch != nil)
        suggestSnoozed.removeAll()
        lane.dwell.setLedgerExcluded(taste.paused, now: Date())
        dismissFavoriteSuggestIfMismatch()
        updateDwellActive(on: lane)
        scheduleRelated(on: lane)
    }

    func endView(reason: LaneSettlement.EndReason = .swipe) {
        for lane in lanes {
            lane.relatedTask?.cancel()
            record(lane.dwell.finish(now: Date()), from: lane, reason: reason)
        }
    }

    /// 停留只在「允许播放、用户在看（iOS 只有 `.active`，§2 `attentive`），且这一路当前房间的播放项此刻真在播」时计时；
    /// 状态变化时顺带重排关联任务。
    func updateDwellActive(on given: PlaybackLane? = nil) {
        let lane = given ?? primary
        let active = platform.attentive && PlaybackPolicy.dwellActive(
            allowed: laneAllowed(lane), playing: lane.playingName, current: name(of: lane)
        )
        guard active != lane.dwell.isActive else {
            if active {
                checkFavoriteSuggest(now: Date())
                scheduleFavoriteSuggestWake()
            }
            return
        }
        lane.dwell.setActive(active, now: Date())
        scheduleRelated(on: lane)
        checkFavoriteSuggest(now: Date())
        scheduleFavoriteSuggestWake()
    }

    /// 暂停、缓冲、隐藏时不排唤醒（`.idle`），恢复计时后由 `updateDwellActive` 重排。
    /// 热门关联只看焦点路（§4.3）。
    func scheduleRelated(on given: PlaybackLane? = nil) {
        let lane = given ?? primary
        lane.relatedTask?.cancel()
        lane.relatedTask = nil
        guard lane.id == focusLane, let username = name(of: lane) else { return }
        switch lane.dwell.relatedPlan(for: username, now: Date()) {
        case .none, .idle:
            return
        case .due:
            lane.dwell.markRelatedDone()
            promoteRelated(to: username)
        case .wait(let seconds):
            lane.relatedTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64((seconds + 0.05) * 1_000_000_000))
                guard !Task.isCancelled else { return }
                self?.scheduleRelated(on: lane)
            }
        }
    }

    /// 一段停留的结算（§4.3）：原始秒、加权秒（焦点段 1.0、非焦点段 0.5）、焦点秒，连同本路自己的来源与结束原因。
    /// 单窗焦点恒在这一路，三个秒数相等，与原来按总秒数记账的结果一致。
    func settlement(
        for settled: DwellTracker.Settled, lane: PlaybackLane, reason: LaneSettlement.EndReason
    ) -> LaneSettlement {
        let focused = min(settled.focusedDwell, settled.dwell)
        return LaneSettlement.settle(
            lane: lane.id.role, username: settled.room.ref, source: lane.dwellSource,
            spans: [
                .init(seconds: focused, focused: true),
                .init(seconds: settled.dwell - focused, focused: false)
            ],
            endReason: reason, userInitiated: reason == .swipe || reason == .notInterested || reason == .block
        )
    }

    func record(_ settled: DwellTracker.Settled?, from lane: PlaybackLane, reason: LaneSettlement.EndReason = .swipe) {
        guard let settled else { return }
        let now = Date()
        let instant = now.timeIntervalSince1970
        let result = settlement(for: settled, lane: lane, reason: reason)
        // 焦点路上还没出画面就被划走（0 秒）仍是每日命中率里的一次落空：与单窗原来的口径一致。
        var daily: TimeInterval? = result.countsDaily ? result.dailySeconds : nil
        if daily == nil, lane.id == focusLane, settled.dwell <= 0, !result.resumesAsNewDwell { daily = 0 }
        taste = taste.recording(
            room: settled.room, learnSeconds: result.learnsTaste ? result.learnSeconds : nil, daily: daily,
            source: result.source, now: now, rarity: tagRarity
        )
        tasteSaver.schedule(taste)
        // 账本只记真实开始时刻起、且去掉暂停学习时段的秒数；与实时提示判定同一口径。非焦点时间折半。
        guard settled.ledgerSeconds > 0, let start = settled.start else { return }
        let ratio = settled.dwell > 0 ? result.weightedSeconds / settled.dwell : 1
        watchLedger = watchLedger.recording(
            username: settled.room.ref, seconds: settled.ledgerSeconds * ratio,
            start: start.timeIntervalSince1970, now: instant
        )
        watchLedgerSaver.schedule(watchLedger)
    }

    // MARK: 收藏提示

    /// 学习未暂停、且当前停留没被作废（清除偏好、不感兴趣、屏蔽）时，才允许记账提示。
    private var suggestAllowed: Bool { !taste.paused && !focusPlaybackLane.dwell.isDiscarded }

    func checkFavoriteSuggest(now: Date) {
        let dwell = focusPlaybackLane.dwell
        guard suggestAllowed, favoriteSuggest == nil, let room = focusRoom, dwell.isActive else { return }
        // 点击穿透期间按钮点不到：不显示也不开始计时，等指针回到窗口（`notePointer`）再显示；
        // 本次停留里已超时消失过的不重复弹。
        guard SuggestRules.canPresent(
            pointerCanClick: !resolvedPresence().ignoresMouseEvents,
            snoozed: suggestSnoozed.contains(WatchLedger.key(room.ref))
        ) else { return }
        let live = dwell.ledgerDwell(now: now)
        let start = dwell.startedAt?.timeIntervalSince1970
        let favorited = curation.isFavorite(room.ref)
        let blocked = curation.isBlocked(room.ref)
        guard watchLedger.shouldSuggest(
            username: room.ref,
            favorited: favorited,
            blocked: blocked,
            liveSeconds: live,
            liveStart: start
        ) else { return }
        presentFavoriteSuggest(room: room, now: now)
    }

    func scheduleFavoriteSuggestWake() {
        favoriteSuggestWakeTask?.cancel()
        favoriteSuggestWakeTask = nil
        let dwell = focusPlaybackLane.dwell
        guard suggestAllowed, favoriteSuggest == nil, let room = focusRoom, dwell.isActive,
              let spell = dwell.startedAt else { return }
        let now = Date()
        let live = dwell.ledgerDwell(now: now)
        let favorited = curation.isFavorite(room.ref)
        let blocked = curation.isBlocked(room.ref)
        if watchLedger.shouldSuggest(
            username: room.ref,
            favorited: favorited,
            blocked: blocked,
            liveSeconds: live,
            liveStart: spell.timeIntervalSince1970
        ) {
            return
        }
        guard let wait = watchLedger.secondsUntilNextSuggestCheck(
            username: room.ref,
            favorited: favorited,
            blocked: blocked,
            liveSeconds: live,
            liveStart: spell.timeIntervalSince1970
        ), wait > 0.05 else { return }
        let name = WatchLedger.key(room.ref)
        favoriteSuggestWakeTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64((wait + 0.05) * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self, WatchLedger.key(self.focusRoom?.ref ?? "") == name else { return }
                self.checkFavoriteSuggest(now: Date())
                self.scheduleFavoriteSuggestWake()
            }
        }
    }

    func presentFavoriteSuggest(room: Room, now: Date) {
        favoriteSuggestWakeTask?.cancel()
        favoriteSuggestWakeTask = nil
        toastTask?.cancel()
        toast = nil
        toastUndoName = nil
        // 这里不记账：只有点了「收藏」或关闭才算问过，超时消失不算（`endFavoriteSuggest`）。
        let key = WatchLedger.key(room.ref)
        let display = room.displayName.isEmpty ? room.username : room.displayName
        favoriteSuggest = FavoriteSuggestPrompt(username: key, displayName: display)
        favoriteSuggestTask?.cancel()
        favoriteSuggestTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Theme.favoriteSuggestSeconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await MainActor.run {
                if self?.favoriteSuggest?.username == key {
                    self?.endFavoriteSuggest(.timedOut)
                }
            }
        }
    }

    func dismissFavoriteSuggestIfMismatch() {
        guard let prompt = favoriteSuggest, let current = focusRoom?.ref else {
            favoriteSuggest = nil
            favoriteSuggestTask?.cancel()
            return
        }
        if WatchLedger.key(current) != prompt.username {
            favoriteSuggest = nil
            favoriteSuggestTask?.cancel()
        }
    }

    func acceptFavoriteSuggest() {
        guard let prompt = favoriteSuggest, let room = focusRoom,
              WatchLedger.key(room.ref) == prompt.username else { return }
        endFavoriteSuggest(.accepted)
        guard !curation.isFavorite(room.ref) else { return }
        taste = taste.following(room: room, now: Date(), rarity: tagRarity)
        tasteSaver.schedule(taste)
        commit(curation.favoriting(room.ref), nil)
    }

    /// 点了提示上的关闭：明确的「不用」，记为问过。
    func closeFavoriteSuggest() {
        endFavoriteSuggest(.closed)
    }

    /// 撤下当前的常看提示并按 `SuggestRules` 决定是否记账：点收藏或关闭记为问过；
    /// 超时消失、被别的横幅替换都不算，本次停留里不再重复弹，之后条件满足仍会提示。
    func endFavoriteSuggest(_ end: SuggestEnd) {
        guard let prompt = favoriteSuggest else { return }
        favoriteSuggest = nil
        favoriteSuggestTask?.cancel()
        if SuggestRules.countsAsAsked(end) {
            watchLedger = watchLedger.markingSuggested(prompt.username, now: Date().timeIntervalSince1970)
            watchLedgerSaver.schedule(watchLedger)
        } else if end != .withdrawn {
            suggestSnoozed.insert(prompt.username)
        }
    }

    /// 只在猜你喜欢模式、且焦点路仍停留在原房间时，把相近的未看房间挪到紧随这一间之后；已划过的位置不动。
    /// 双窗里按焦点路的房间与位置来，不是主路的。
    func promoteRelated(to username: String) {
        guard source == .guess, let room = focusRoom, room.ref == username,
              let at = feed.rooms.firstIndex(where: { $0.ref == username }) else { return }
        let ahead = Array(feed.rooms.dropFirst(at + 1))
        let names = Recommender.related(to: room, among: ahead, taste: taste)
        guard !names.isEmpty else { return }
        updateFeed(intent: .merge) { $0.promoteAfter(anchor: username, names) }
    }
}