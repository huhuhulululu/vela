import Foundation
import os

/// 翻一页的结果：区分「暂时忙」「失败」「不需要或已过期」，补拉据此决定等待还是放弃。
enum PageOutcome { case loaded, busy, skipped, failed }

/// 常规目录安装后还要并发补拉的定向候选：沿用的常规查询，与常规候选算出的标签稀有度。
struct TargetedPlan {
    let base: CatalogQuery
    let rarity: TagRarity
}

/// 一次拉取的结果：`page` 与 `regular` 都只含常规页（稀有度的统计口径）；
/// `targeted` 非 nil 时，常规目录装好、起播之后再并发补拉按口味定向的候选并入当前位置之后。
/// `rest` 非 nil 时，首页已经够用，其余页在后台续拉，到了再并入，不另起播。
struct FetchedPage {
    let page: CatalogPage
    let regular: [Room]
    var targeted: TargetedPlan?
    var rest: CatalogRest?
}

/// 首页之后还要续拉的页：沿用同一次查询，从已取回的首页接着数。
struct CatalogRest: Sendable {
    var query: CatalogQuery
    var pages: Int
    var fetched: Int
    var last: CatalogPage
}

@MainActor
extension StageModel {
    /// `keepingSearchTask`：全站搜索落空后恢复原来源时用，本次重载不取消调用它的搜索任务。
    func reload(keepingSearchTask: Bool = false) async {
        guard ageConfirmed else { return }
        endSiteSearch(cancelTask: !keepingSearchTask)
        loadGeneration += 1
        let token = loadGeneration
        loading = true
        catalogError = nil
        let previousPaging = paging.restart()
        targetedTask?.cancel()
        targetedTask = nil
        invalidateCatalogRest()
        idleStart.clear()
        skips.reset()
        do {
            let fetched = try await fetchOpening()
            guard token == loadGeneration else { return }
            challengeGate.reset()
            let raw = fetched.page
            updateCandidates(fetched.regular, replacing: true)
            let page = arranged(raw)
            commitFetched(raw: raw, arranged: page, offset: 0, token: token, replacing: true)
            updateFeed(installing: source, intent: .reload) { store in
                store.replace(with: page)
                store.setQuery(self.currentQuery)
            }
            loadedOffset = page.nextOffset
            loading = false
            // 首页先装好并起播。还有后续页时，定向候选等这些页并入后再发（稀有度按全部常规页算）；
            // 没有后续页就马上发。都不挡起播。
            launchCatalogRest(fetched.rest, token: token)
            if catalogRestTask == nil {
                launchTargeted(fetched.targeted, token: token)
            }
            // 首页一个可见房间都没有时，先等后台页并入，再按原逻辑继续翻，避免和后台页抢同一个游标。
            if feed.rooms.isEmpty, let restTask = catalogRestTask {
                await restTask.value
                guard token == loadGeneration else { return }
            }
            await loadUntilVisible(token: token)
            guard token == loadGeneration else { return }
            await requestPlayAndWait()
        } catch CatalogFailure.challenge, SidecarError.challenge {
            // 只有正文是质询页才弹确认页；普通 403 / 503 走下面的失败分支。
            guard token == loadGeneration else { return }
            paging.restore(previousPaging)
            if allowChallengePrompt() {
                challengeResume = .reload
                presentCatalogChallenge()
            } else {
                failReload("站点连续要求确认，稍后再试")
            }
        } catch {
            guard token == loadGeneration else { return }
            // 列表没被替换：游标回到最后一次成功加载的位置，不留在清零后的 0。
            paging.restore(previousPaging)
            log.error("catalog failed")
            failReload(Self.catalogMessage(error))
        }
    }

    /// 目录没取回：切来源失败就回滚来源，否则显示原因（列表非空时再弹一条提示）。
    private func failReload(_ message: String) {
        loading = false
        if source != loadedSource, !feed.rooms.isEmpty {
            rollbackSource(message)
            return
        }
        catalogError = message
        if !feed.rooms.isEmpty { showToast(message) }
    }

    /// 发起全站搜索：先作废并取消旧搜索，再用新的操作代次起任务。
    func startSiteSearch(_ keyword: String, fallback: Room?) {
        siteSearchTask?.cancel()
        invalidateCatalogRest()
        let epoch = searchEpoch.begin()
        siteSearchTask = Task { await searchSite(keyword, fallback: fallback, epoch: epoch) }
    }

    /// 作废并取消在途全站搜索（本地提交、收藏选择、退出搜索时用）；在途时一并结束加载中状态。
    func cancelSiteSearch() {
        searchEpoch.invalidate()
        siteSearchTask?.cancel()
        challengeResume = challengeResume?.droppingSiteSearch()
        if searchEpoch.releaseLoading() { loading = false }
        siteSearchTask = nil
    }

    /// 全站搜索：按搜索词取一页，作为临时列表浏览（接口原顺序，不写推荐理由，没有后续页）。
    /// 没有结果或请求失败时，搜索词是合法用户名就照旧直达；被站点质询时沿用目录的确认流程。
    /// 成功、失败、质询三个分支都先核对操作代次，被作废的旧搜索不改任何状态。
    private func searchSite(_ keyword: String, fallback: Room?, epoch: Int) async {
        defer { if searchEpoch.isCurrent(epoch) { siteSearchTask = nil } }
        guard searchEpoch.canStart(epoch, cancelled: Task.isCancelled), ageConfirmed else { return }
        loadGeneration += 1
        let token = loadGeneration
        invalidateCatalogRest()
        loading = true
        searchEpoch.claimLoading(epoch)
        do {
            let raw = try await catalogAPI.fetch(SiteSearch.query(keyword))
            guard token == loadGeneration, searchEpoch.isCurrent(epoch) else { return }
            if searchEpoch.releaseLoading(token: epoch) { loading = false }
            challengeGate.reset()
            let page = SiteSearch.page(raw.rooms, blocked: curation.blockedSet)
            guard !page.rooms.isEmpty else {
                await siteSearchMissed(keyword, fallback: fallback, failed: false, epoch: epoch)
                return
            }
            siteSearch = keyword
            updateFeed(intent: .searchInstall) { store in
                store.replace(with: page)
                store.setQuery(self.currentQuery)
            }
            requestPlay()
        } catch CatalogFailure.challenge {
            guard token == loadGeneration, searchEpoch.isCurrent(epoch) else { return }
            guard allowChallengePrompt() else {
                if searchEpoch.releaseLoading(token: epoch) { loading = false }
                showToast("站点连续要求确认，全站搜索稍后再试")
                return
            }
            challengeResume = challengeResume?.merging(.siteSearch(keyword)) ?? .siteSearch(keyword)
            _ = searchEpoch.releaseLoading(token: epoch) // presentCatalogChallenge 自己会结束加载中
            presentCatalogChallenge()
        } catch {
            guard token == loadGeneration, searchEpoch.isCurrent(epoch) else { return }
            if searchEpoch.releaseLoading(token: epoch) { loading = false }
            log.error("site search failed")
            await siteSearchMissed(keyword, fallback: fallback, failed: true, epoch: epoch)
        }
    }

    /// 全站搜索没找到：能直达用户名就直达（原来的回车行为），否则提示。
    /// 已有上一次全站搜索时先结束它、重载回原来源，再直达或提示，不把直达房间塞进旧结果列表。
    private func siteSearchMissed(_ keyword: String, fallback: Room?, failed: Bool, epoch: Int) async {
        if SiteSearch.missRecovery(active: siteSearch) == .restoreSource {
            // 保留任务所有权：重载期间用户的新操作仍能经 searchEpoch 作废本次，返回后重新校验。
            await reload(keepingSearchTask: true)
        }
        let outcome = SiteSearch.missOutcome(
            keyword: keyword, fallback: fallback, fallbackBlocked: fallback.map { curation.isBlocked($0.username) } ?? false,
            failed: failed, stillCurrent: searchEpoch.isCurrent(epoch) && !Task.isCancelled
        )
        switch outcome {
        case .abandon:
            return
        case .direct(let room):
            updateFeed { $0.insertDirect(room) }
            showFilters = false
            requestPlay()
        case .notify(let message):
            showToast(message)
        }
    }

    /// 离开全站搜索：清掉搜索词与搜索框，随后的重新加载装回原来源。
    private func endSiteSearch(cancelTask: Bool = true) {
        if cancelTask { cancelSiteSearch() }
        guard siteSearch != nil else { return }
        siteSearch = nil
        if !searchText.isEmpty { searchText = "" }
    }

    /// 切换来源后目录取不回来：列表还是原来源的，来源标记也回到原来源，别让界面说一套、显示另一套。
    private func rollbackSource(_ message: String) {
        source = loadedSource
        settings.source = loadedSource
        showToast("\(message)，仍停在\(Copy.source(loadedSource))")
    }

    /// 可见列表为空（整页被屏蔽或过滤）时继续翻页，直到出现可见房间、到底，或达到空页上限。
    /// 别的请求占着位（暂时忙）时直接返回：那次请求完成时，`loadPage` 会统一检查并起播，不靠忙等。
    func loadUntilVisible(token: Int) async {
        var pages = 0
        while token == loadGeneration, feed.rooms.isEmpty, feed.shouldLoadMore(blankPages: pages) {
            let before = loadedOffset
            switch await loadPage() {
            case .loaded:
                pages += 1
                if loadedOffset == before { return }
            case .busy, .skipped, .failed:
                return
            }
        }
    }

    func loadMoreIfNeeded() async {
        let token = loadGeneration
        await loadPage()
        // 翻页期间可见房间被屏蔽光、或整页被屏蔽：继续翻到可看的房间；起播由 loadPage 完成时统一触发。
        guard LoadFollowUp.needsFill(tokenUnchanged: token == loadGeneration, visibleRooms: feed.rooms.count) else { return }
        await loadUntilVisible(token: token)
    }

    @discardableResult
    func loadPage() async -> PageOutcome {
        guard ageConfirmed, feed.shouldLoadMore(), !loading else { return .skipped }
        let token = loadGeneration
        // 按列表代次占位：旧来源残留的请求不会挡住新来源的补拉。
        guard loadSlot.acquire(generation: token) else { return .busy }
        defer { loadSlot.release(generation: token) }
        let offset = loadedOffset
        do {
            let fetched = try await fetchPage(offset: offset)
            guard token == loadGeneration else { return .skipped }
            challengeGate.reset()
            let raw = fetched.page
            updateCandidates(fetched.regular, replacing: false)
            let page = arranged(raw)
            commitFetched(raw: raw, arranged: page, offset: offset, token: token, replacing: false)
            // 新开播：每页各自排序后追加会出现断层，追加后对当前位置之后整体重排。
            let reorder = source == .fresh
            updateFeed(intent: .merge) {
                $0.append(page, from: offset)
                if reorder { $0.reorderAfterCurrent(Recommender.newest) }
            }
            loadedOffset = page.nextOffset
            // 无论谁发起：播放器因没房间而空闲、现在有了可见房间，就起播。
            if idleStart.takeStart(hasCurrent: feed.current != nil) {
                requestPlay()
            }
            return .loaded
        } catch {
            // 被作废的旧请求失败不留痕迹：不记日志、不弹提示。
            guard token == loadGeneration else { return .skipped }
            log.error("page failed")
            showToast("没能加载更多房间")
            return .failed
        }
    }

    func catalogQuery(offset: Int) -> CatalogQuery {
        CatalogQuery(
            gender: gender,
            region: region,
            tag: normalizedTag,
            limit: source.pageLimit,
            offset: offset,
            newCams: source.usesNewCams
        )
    }

    func fetchPage(offset: Int) async throws -> FetchedPage {
        if source == .favorites {
            let page = try await fetchFavoritesPage()
            return FetchedPage(page: page, regular: page.rooms)
        }
        let query = catalogQuery(offset: offset)
        let regular = try await catalogAPI.fetchPages(query, pages: source.pageCount)
        // 只有「猜你喜欢」的首次拉取按口味定向补候选；定向页都是 offset 0，翻页时不重复拉。
        let plan = source == .guess && offset == 0 ? TargetedPlan(base: query, rarity: TagRarity(rooms: regular.rooms)) : nil
        return FetchedPage(page: regular, regular: regular.rooms, targeted: plan)
    }

    /// 冷启动只等首页。推荐和新开播还有后续页时附上续拉计划；收藏和单页来源与原来一样一次取完。
    func fetchOpening() async throws -> FetchedPage {
        if source == .favorites || source.pageCount <= 1 {
            return try await fetchPage(offset: 0)
        }
        let query = catalogQuery(offset: 0)
        let first = try await catalogAPI.fetch(query)
        let plan = source == .guess ? TargetedPlan(base: query, rarity: TagRarity(rooms: first.rooms)) : nil
        return FetchedPage(
            page: first, regular: first.rooms, targeted: plan,
            rest: CatalogRest(query: query, pages: source.pageCount, fetched: 1, last: first)
        )
    }

    func invalidateCatalogRest() {
        catalogRestTask?.cancel()
        catalogRestTask = nil
    }

    /// 首页之后的页在后台顺序续拉。短页或到底不发。失败只记日志，不弹错、不换房间、不起播。
    func launchCatalogRest(_ rest: CatalogRest?, token: Int) {
        invalidateCatalogRest()
        guard let rest else { return }
        let pageSize = min(max(rest.query.limit, 1), CatalogQuery.maxPageSize)
        guard CatalogWindow.shouldContinue(
            fetched: rest.fetched, pages: rest.pages, lastCount: rest.last.rooms.count,
            pageSize: pageSize, nextOffset: rest.last.nextOffset, total: rest.last.total
        ) else { return }
        catalogRestTask = Task { [weak self] in
            await self?.followCatalogRest(rest, pageSize: pageSize, token: token)
        }
    }

    /// 续页并入当前位置之后。代次变了或任务被取消就整段丢掉，不写列表。
    /// 占住这一代的翻页位，避免用户滑到末尾时和续页抢同一个偏移。
    /// 猜你喜欢的定向候选等续页结束后再发，稀有度按已经并入的常规页算；中途被作废则不发。
    private func followCatalogRest(_ rest: CatalogRest, pageSize: Int, token: Int) async {
        guard loadSlot.acquire(generation: token) else {
            guard !Task.isCancelled, token == loadGeneration, source == .guess else { return }
            launchTargeted(TargetedPlan(base: rest.query, rarity: tagRarity), token: token)
            return
        }
        defer { loadSlot.release(generation: token) }
        var query = rest.query
        var last = rest.last
        var fetched = rest.fetched
        while CatalogWindow.shouldContinue(
            fetched: fetched, pages: rest.pages, lastCount: last.rooms.count,
            pageSize: pageSize, nextOffset: last.nextOffset, total: last.total
        ) {
            guard !Task.isCancelled, token == loadGeneration else { return }
            query.offset = last.nextOffset
            let page: CatalogPage
            do {
                page = try await catalogAPI.fetch(query)
            } catch {
                guard !Task.isCancelled, token == loadGeneration else { return }
                log.error("catalog rest page failed")
                break
            }
            guard !Task.isCancelled, token == loadGeneration else { return }
            let offset = query.offset
            if page.rooms.isEmpty {
                updateFeed(intent: .merge) { $0.mergePageAfterCurrent(page, from: offset) { $0 } }
                loadedOffset = page.nextOffset
                break
            }
            updateCandidates(page.rooms, replacing: false)
            let ordered = arranged(page)
            guard commitFetched(raw: page, arranged: ordered, offset: offset, token: token, replacing: false) else { return }
            updateFeed(intent: .merge) { store in
                store.mergePageAfterCurrent(page, from: offset) { rooms in
                    self.arranged(CatalogPage(rooms: rooms, total: 0, nextOffset: 0)).rooms
                }
            }
            loadedOffset = page.nextOffset
            // 首页起播后用户把可见房间屏蔽光、播放器因空列表停下时，续页带来房间就起播（与 loadPage 一致）。
            if idleStart.takeStart(hasCurrent: feed.current != nil) { requestPlay() }
            last = page
            fetched += 1
        }
        guard !Task.isCancelled, token == loadGeneration, source == .guess else { return }
        launchTargeted(TargetedPlan(base: rest.query, rarity: tagRarity), token: token)
    }

    /// 定向请求各自的超时：慢的只丢掉自己，不拖住其余，也不阻塞起播。
    static let targetedTimeout: TimeInterval = 6

    func launchTargeted(_ plan: TargetedPlan?, token: Int) {
        targetedTask?.cancel()
        targetedTask = nil
        guard let plan else { return }
        targetedTask = Task { [weak self] in await self?.mergeTargeted(plan, token: token) }
    }

    /// 最喜欢的 2 到 3 个标签各拉一页，并发执行、各自带超时；失败只记日志。
    /// 完成时列表代次与来源仍是原来的才并入：当前位置之后整体重排，当前及之前不动。
    private func mergeTargeted(_ plan: TargetedPlan, token: Int) async {
        let tags = TasteTargeting.favoriteTags(taste: taste, now: Date(), rarity: plan.rarity)
        let queries = TasteTargeting.queries(for: tags, base: plan.base)
        guard !queries.isEmpty else { return }
        let api = catalogAPI
        let pages = await TimedFanOut.run(queries, timeout: Self.targetedTimeout) { try await api.fetchPages($0, pages: 1).rooms }
        guard !Task.isCancelled, token == loadGeneration, source == .guess else { return }
        for page in pages where page == nil { log.error("targeted page failed") }
        let rooms = TasteTargeting.merging([], extra: pages.compactMap { $0 })
        guard !rooms.isEmpty else { return }
        let now = Date()
        let favorites = curation.favoriteSet
        let current = taste
        knownCatalog.record(rooms, firstPage: false, at: now)
        for room in rooms {
            reasons[room.username.lowercased()] = Recommender.reason(for: room, taste: current, favorites: favorites, now: now)
        }
        updateFeed(intent: .merge) { store in
            store.mergeAfterCurrent(rooms) { self.arranged(CatalogPage(rooms: $0, total: 0, nextOffset: 0)).rooms }
        }
        if idleStart.takeStart(hasCurrent: feed.current != nil) {
            requestPlay()
        }
    }

    /// 记下常规候选并重算标签稀有度（按当前候选集合；最多保留最近 600 个房间）。
    func updateCandidates(_ rooms: [Room], replacing: Bool) {
        let all = replacing ? rooms : TasteTargeting.merging(regularCandidates, extra: [rooms])
        regularCandidates = Array(all.suffix(600))
        tagRarity = TagRarity(rooms: regularCandidates)
    }

    /// 目录快照与推荐理由只在代次检查通过后写；被作废的旧响应不改任何状态。
    @discardableResult
    private func commitFetched(raw: CatalogPage, arranged page: CatalogPage, offset: Int, token: Int, replacing: Bool) -> Bool {
        let favorites = curation.favoriteSet
        let current = taste
        let now = Date()
        return LoadCommit.apply(
            raw: raw, arranged: page, offset: offset, source: source, token: token, current: loadGeneration,
            replacing: replacing, now: now, catalog: &knownCatalog, reasons: &reasons
        ) { Recommender.reason(for: $0, taste: current, favorites: favorites, now: now) }
    }

    var normalizedTag: String? {
        let trimmed = tagText.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    func webSheetDismissed() {
        sidecar.resumeIfNeeded()
    }

    func presentCatalogChallenge() {
        loading = false
        catalogError = "目录需要先通过站点确认"
        sidecar.suspend()
        chromeVisible = true
        Task { @MainActor in
            await WebKitWarmup.prepare()
            self.showChallenge = true
        }
    }

    static func catalogMessage(_ error: Error) -> String {
        if let error = error as? CatalogFailure {
            switch error {
            case .challenge: return "站点暂时拦下了目录"
            case .malformed: return "目录数据无法识别"
            case .status(let code): return "目录请求失败（\(code)）"
            }
        }
        if error is EndpointBackedOff { return EndpointBackoff.catalogNote }
        if error is SidecarError { return "目录需要先通过站点确认" }
        return NetworkFailure.message
    }
}
