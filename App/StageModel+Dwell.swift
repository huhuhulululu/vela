import AppKit
import Foundation
import os

@MainActor
extension StageModel {
    /// 当前房间变了：结算上一个房间的停留，新房间等播放真正起来再计时，满阈值后做热门关联。
    func currentRoomChanged() {
        relatedTask?.cancel()
        record(dwell.switchTo(feed.current, now: Date()))
        dwellSource = SiteSearch.dwellSource(loaded: loadedSource, searching: siteSearch != nil)
        suggestSnoozed.removeAll()
        dwell.setLedgerExcluded(taste.paused, now: Date())
        dismissFavoriteSuggestIfMismatch()
        updateDwellActive()
        scheduleRelated()
    }

    func endView() {
        relatedTask?.cancel()
        record(dwell.finish(now: Date()))
    }

    /// 停留只在「允许播放，且当前房间的播放项此刻真在播」时计时；状态变化时顺带重排关联任务。
    func updateDwellActive() {
        let active = PlaybackPolicy.dwellActive(
            allowed: playbackAllowed, playing: playingName, current: feed.currentUsername
        )
        guard active != dwell.isActive else {
            if active {
                checkFavoriteSuggest(now: Date())
                scheduleFavoriteSuggestWake()
            }
            return
        }
        dwell.setActive(active, now: Date())
        scheduleRelated()
        checkFavoriteSuggest(now: Date())
        scheduleFavoriteSuggestWake()
    }

    /// 暂停、缓冲、隐藏时不排唤醒（`.idle`），恢复计时后由 `updateDwellActive` 重排。
    func scheduleRelated() {
        relatedTask?.cancel()
        relatedTask = nil
        guard let username = feed.currentUsername else { return }
        switch dwell.relatedPlan(for: username, now: Date()) {
        case .none, .idle:
            return
        case .due:
            dwell.markRelatedDone()
            promoteRelated(to: username)
        case .wait(let seconds):
            relatedTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64((seconds + 0.05) * 1_000_000_000))
                guard !Task.isCancelled else { return }
                self?.scheduleRelated()
            }
        }
    }

    func record(_ settled: DwellTracker.Settled?) {
        guard let settled else { return }
        let now = Date()
        let instant = now.timeIntervalSince1970
        taste = taste.recording(room: settled.room, watched: settled.dwell, source: dwellSource, now: now, rarity: tagRarity)
        tasteSaver.schedule(taste)
        // 账本只记真实开始时刻起、且去掉暂停学习时段的秒数；与实时提示判定同一口径。
        guard settled.ledgerSeconds > 0, let start = settled.start else { return }
        watchLedger = watchLedger.recording(
            username: settled.room.username, seconds: settled.ledgerSeconds,
            start: start.timeIntervalSince1970, now: instant
        )
        watchLedgerSaver.schedule(watchLedger)
    }

    // MARK: 收藏提示

    /// 学习未暂停、且当前停留没被作废（清除偏好、不感兴趣、屏蔽）时，才允许记账提示。
    private var suggestAllowed: Bool { !taste.paused && !dwell.isDiscarded }

    func checkFavoriteSuggest(now: Date) {
        guard suggestAllowed, favoriteSuggest == nil, let room = feed.current, dwell.isActive else { return }
        // 点击穿透期间按钮点不到：不显示也不开始计时，等指针回到窗口（`notePointer`）再显示；
        // 本次停留里已超时消失过的不重复弹。
        guard SuggestRules.canPresent(
            pointerCanClick: !resolvedPresence().ignoresMouseEvents,
            snoozed: suggestSnoozed.contains(WatchLedger.key(room.username))
        ) else { return }
        let live = dwell.ledgerDwell(now: now)
        let start = dwell.startedAt?.timeIntervalSince1970
        let favorited = curation.isFavorite(room.username)
        let blocked = curation.isBlocked(room.username)
        guard watchLedger.shouldSuggest(
            username: room.username,
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
        guard suggestAllowed, favoriteSuggest == nil, let room = feed.current, dwell.isActive,
              let spell = dwell.startedAt else { return }
        let now = Date()
        let live = dwell.ledgerDwell(now: now)
        let favorited = curation.isFavorite(room.username)
        let blocked = curation.isBlocked(room.username)
        if watchLedger.shouldSuggest(
            username: room.username,
            favorited: favorited,
            blocked: blocked,
            liveSeconds: live,
            liveStart: spell.timeIntervalSince1970
        ) {
            return
        }
        guard let wait = watchLedger.secondsUntilNextSuggestCheck(
            username: room.username,
            favorited: favorited,
            blocked: blocked,
            liveSeconds: live,
            liveStart: spell.timeIntervalSince1970
        ), wait > 0.05 else { return }
        let name = WatchLedger.key(room.username)
        favoriteSuggestWakeTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64((wait + 0.05) * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self, WatchLedger.key(self.feed.currentUsername ?? "") == name else { return }
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
        let key = WatchLedger.key(room.username)
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
        guard let prompt = favoriteSuggest, let current = feed.currentUsername else {
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
        guard let prompt = favoriteSuggest, let room = feed.current,
              WatchLedger.key(room.username) == prompt.username else { return }
        endFavoriteSuggest(.accepted)
        guard !curation.isFavorite(room.username) else { return }
        taste = taste.following(room: room, now: Date(), rarity: tagRarity)
        tasteSaver.schedule(taste)
        commit(curation.favoriting(room.username), nil)
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

    /// 只在猜你喜欢模式、且仍停留在原房间时，把相近的未看房间挪到紧随其后；已划过的位置不动。
    func promoteRelated(to username: String) {
        guard source == .guess, let room = feed.current, room.username == username else { return }
        let ahead = Array(feed.rooms.dropFirst(feed.index + 1))
        let names = Recommender.related(to: room, among: ahead, taste: taste)
        guard !names.isEmpty else { return }
        updateFeed { $0.promoteAfterCurrent(names) }
    }
}