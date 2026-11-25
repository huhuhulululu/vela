import Foundation
import os

@MainActor
extension StageModel {
    var currentIsFavorite: Bool {
        focusRoom.map { curation.isFavorite($0.ref) } ?? false
    }

    /// 收藏或取消收藏当前房间。收藏时再给它的标签与性别一次「关注」级加权。
    func toggleFavorite(_ target: RoomTarget?) {
        guard let lane = matchedLane(target), let room = room(of: lane) else { return }
        log.info("favorite-toggle \(self.curation.isFavorite(room.ref) ? "off" : "on", privacy: .public)")
        if curation.isFavorite(room.ref) {
            guard let next = syncedCuration(.unfavorite(room.ref), local: curation.unfavoriting(room.ref)) else { return }
            commit(
                next,
                "已取消收藏 \(room.displayName)",
                toastSeconds: Theme.unfavoriteToastSeconds,
                undoFavorite: room.ref
            )
        } else {
            guard let next = syncedCuration(.favorite(room.ref), local: curation.favoriting(room.ref)) else { return }
            taste = taste.following(room: room, now: Date(), rarity: tagRarity)
            tasteSaver.schedule(taste)
            commit(next, nil)
        }
    }

    /// 屏蔽当前房间：从所有来源里去掉，并立刻切到下一个。
    func blockCurrent(_ target: RoomTarget?) {
        guard let lane = matchedLane(target), let room = room(of: lane) else { return }
        guard let next = syncedCuration(.block(room.ref), local: curation.blocking(room.ref)) else { return }
        lane.manuallyPaused = false
        commit(next, "已屏蔽 \(room.displayName)，可在收藏与屏蔽列表里取消")
    }

    func removeFavorite(_ name: String) {
        guard let next = syncedCuration(.unfavorite(name), local: curation.unfavoriting(name)) else { return }
        commit(next, nil)
    }

    /// 撤销移除：把名字放回收藏（收藏夹里「撤销」用，不加口味权重、不弹提示）。
    func restoreFavorite(_ name: String) {
        guard let next = syncedCuration(.restoreFavorite(name), local: curation.restoringFavorite(name)) else { return }
        commit(next, nil)
    }

    func unblock(_ name: String) {
        guard let next = syncedCuration(.unblock(name), local: curation.unblocking(name)) else { return }
        commit(next, nil)
    }

    func commit(_ next: Curation, _ message: String?, toastSeconds: Double = 3.5, undoFavorite: String? = nil) {
        let previous = curation
        curation = next
        curationSaver.schedule(next)
        if next.favorites != previous.favorites { refreshReasons() }
        let before = feed.current?.ref
        // 屏蔽当前房间导致的换房不记正向信号：停留作废，免得给它的标签加分。双窗时两路各查各的。
        for lane in lanes {
            if let username = name(of: lane), next.isBlocked(username) { lane.dwell.discard() }
        }
        updateFeed { $0.setFilters(blocked: next.blockedSet, only: self.allowedNames(for: next)) }
        if feed.current?.ref != before {
            // 名单变化换了（或清空了、或恢复了）当前房间：同步播放，playCurrent 会让旧的跳过任务与请求失效。
            requestPlay()
            Task { await loadMoreIfNeeded() }
        }
        if let prompt = favoriteSuggest, next.isFavorite(prompt.username) {
            favoriteSuggest = nil
            favoriteSuggestTask?.cancel()
        }
        if let message {
            let undo = undoFavorite.flatMap { next.canRestoreFavorite($0) ? $0 : nil }
            showToast(message, seconds: toastSeconds, undoFavorite: undo)
        }
        // 收藏来源里增删收藏：列表按新名单重取（命中缓存不发请求）。屏蔽当前房间已由上面处理。
        if source == .favorites, next.blocked == previous.blocked, next.favorites != previous.favorites {
            Task { await reload() }
        }
    }
}
