import Foundation

@MainActor
extension StageModel {
    /// 这个房间的推荐理由；只在列表确实来自「猜你喜欢」时有。
    func reason(for room: Room) -> String? {
        loadedSource == .guess && siteSearch == nil ? reasons[room.identity] : nil
    }

    /// 排序的附加信号：当前候选的标签稀有度、观看账本里的主播亲和度，屏蔽的主播为 0。
    var rankSignals: RankSignals {
        RankSignals(rarity: tagRarity, ledger: watchLedger, blocked: curation.blockedSet)
    }

    var tasteSummary: TasteSummary { taste.summary(now: Date()) }

    /// 冷启动整理的候选：全站热门标签里画像还没有的；取不到时退到当前已加载房间里最常见的。
    var tasteSuggestions: [String] { taste.suggestedTags(hot: hotTags.fresh(now: Date()), fallback: feed.catalog) }

    /// 筛选面板标签输入下方的提示：全站热门标签，取不到时退到当前已加载房间里最常见的。
    var tagHints: [String] { HotTags.filterHints(hot: hotTags.fresh(now: Date()), rooms: feed.catalog) }

    /// 缓存过期（且不在失败冷却里）才取一次全站热门标签；失败或被站点拦截只记下，界面照用回退，不弹确认页。
    /// 在可取消的子任务里跑，挂起时整段取消（§3.1）。
    func loadHotTagsIfStale() async {
        await trackShared { [weak self] in await self?.loadHotTagsTracked() }
    }

    private func loadHotTagsTracked() async {
        guard !hotTagsLoading, hotTags.needsFetch(now: Date()), let life = sharedToken() else { return }
        hotTagsLoading = true
        defer { hotTagsLoading = false }
        do {
            let result = try await http.send(HotTags.request())
            guard lifecycleAllows(.beforeCommit, life) else { return }
            let tags = try HotTags.decode(status: result.status, data: result.data)
            hotTags = hotTags.storing(tags, at: Date())
        } catch {
            // 挂起时被取消的请求不算失败，不进失败冷却。
            guard lifecycleAllows(.beforeCommit, life) else { return }
            log.error("hot tags failed")
            hotTags = hotTags.recordingFailure(at: Date())
        }
    }

    private func commitTaste(_ next: Taste) {
        let previous = taste
        taste = next
        tasteSaver.schedule(next)
        // 暂停与恢复学习对每一路的停留都生效，副路的账本排除段也要同步。
        for lane in lanes { lane.dwell.setLedgerExcluded(next.paused, now: Date()) }
        syncHiddenFilter(previous: previous)
        refreshReasons()
    }

    /// 「不想看」集合变了：只改列表的可见性过滤（房间仍留在目录里，恢复后再排回来），不从目录删房间。
    private func syncHiddenFilter(previous: Taste) {
        guard previous.vetoedKeys != taste.vetoedKeys, source == .guess, loadedSource == .guess else { return }
        let before = feed.current?.ref
        // 当前房间因被过滤而换走：不记正向停留信号。
        for lane in lanes {
            if let room = room(of: lane), taste.isVetoed(room) { lane.dwell.discard() }
        }
        let blocked = curation.blockedSet
        let only = allowedNames(for: curation)
        let hidden = taste.vetoedKeys
        updateFeed { $0.setFilters(blocked: blocked, only: only, hidden: hidden) }
        if feed.current?.ref != before { requestPlay() }
        Task { await loadMoreIfNeeded() }
    }

    /// 当前及其后的房间按最新画像与收藏重算理由（正在看的这个也随之更新）。
    func refreshReasons() {
        guard loadedSource == .guess else { return }
        let now = Date()
        let favorites = curation.favoriteSet
        let current = taste
        for room in feed.rooms.dropFirst(feed.index) {
            reasons[room.identity] = Recommender.reason(for: room, taste: current, favorites: favorites, now: now)
        }
    }

    /// 面板上改了某个标签的强弱：已加载的列表在当前位置之后立即按新画像重排（当前及之前不动）。
    private func commitTasteAndRearrange(_ next: Taste) {
        commitTaste(next)
        // 全站搜索的临时列表保持接口顺序，不按画像重排。
        guard loadedSource == .guess, source == .guess, siteSearch == nil else { return }
        let now = Date()
        let favorites = curation.favoriteSet
        let seed = UInt64.random(in: .min ... .max)
        let current = taste
        let signals = rankSignals
        updateFeed(intent: .merge) {
            $0.reorderAfterCurrent {
                Recommender.arrange($0, source: .guess, taste: current, now: now, seed: seed, favorites: favorites, signals: signals)
            }
        }
        Task { await loadMoreIfNeeded() }
    }

    func boostTaste(_ key: String) { commitTasteAndRearrange(taste.boosting(key, now: Date())) }

    func vetoTaste(_ key: String) { commitTasteAndRearrange(taste.vetoing(key, now: Date())) }

    func restoreTaste(_ key: String) { commitTasteAndRearrange(taste.restoring(key, now: Date())) }

    func removeTaste(_ key: String) { commitTasteAndRearrange(taste.removing(key)) }

    func setLearningPaused(_ paused: Bool) {
        commitTaste(taste.settingPaused(paused))
        if paused {
            favoriteSuggest = nil
            favoriteSuggestTask?.cancel()
            favoriteSuggestWakeTask?.cancel()
        } else {
            scheduleFavoriteSuggestWake()
        }
        showToast(paused ? "已暂停学习，排序仍按现有口味" : "已恢复学习")
    }

    func resetTasteProfile() {
        commitTaste(taste.resettingProfile())
        showToast("口味画像已重置")
    }

    /// 清掉画像、最近看过、旧版偏好文件，并作废每一路当前房间的停留（否则它离开时又会写回一条）。
    func clearAllPreferences() {
        for lane in lanes { lane.dwell.discard() }
        commitTaste(taste.erased())
        tasteSaver.flush()
        tasteStore.eraseLegacy()
        watchLedger = watchLedger.cleared()
        watchLedgerSaver.flush()
        watchLedgerStore.erase()
        // 迁移时留下的旧格式备份里也有口味与观看记录，一并删掉。
        SiteKeyMigration.removeBackups(
            curation: CurationStore.defaultURL(), taste: tasteStore.url, ledger: watchLedgerStore.url
        )
        favoriteSuggest = nil
        favoriteSuggestTask?.cancel()
        favoriteSuggestWakeTask?.cancel()
        showToast("已清除口味与观看记录，收藏与屏蔽保留")
    }

    /// 对当前房间不感兴趣：记一次强落空，作废它的停留，立刻切到下一个。
    func markNotInterested(_ target: RoomTarget?) {
        guard let lane = matchedLane(target), let room = room(of: lane) else { return }
        commitTaste(taste.rejecting(room: room, source: lane.dwellSource, now: Date()))
        lane.dwell.discard()
        showToast(taste.paused ? "已跳过，学习已暂停所以没有记口味" : "已记下，会少推荐类似的")
        step(1, on: lane)
    }
}
