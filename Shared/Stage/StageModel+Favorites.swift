import Foundation

/// 一次收藏查询的结果。
struct FavoriteResolution: Sendable {
    var statuses: [String: FavoriteStatus]
    var challenged: Bool
    /// 本批计划查询的名字里仍有没拿到状态的（只看本批计划内的名字）。
    var incomplete = false
}

/// 收藏来源：不登录，按收藏名单逐个查在线状态。先比对已加载的目录与缓存，只对没有答案的发请求。
@MainActor
extension StageModel {
    /// 解析全部收藏的状态并写进缓存。键为小写用户名；`challenged` 表示被站点拦截，已停止查询。
    /// 同一时间只有一批查询：新请求取消在途批次，等它收尾后再开新批（并发不叠加）；
    /// 被取代的调用方拿到最新一批的结果，旧批次不写回缓存，`favoritesChecking` 只在最后一批结束时关闭。
    func resolveFavorites() async -> FavoriteResolution {
        // 挂起期间（iOS 后台）不查，也不动补查冷却。
        guard sharedToken() != nil else { return FavoriteResolution(statuses: [:], challenged: false) }
        favoritesChecking = true
        let result = await favoriteRunner.run { [self] id in await runFavoriteBatch(id) }
        if !favoriteRunner.isRunning { favoritesChecking = false }
        guard sharedToken() != nil else { return result }
        noteFavoriteOutcome(result)
        return result
    }

    /// 被拦截或仍有收藏没拿到状态：记一次失败，进入自动补查冷却；全部拿到则清掉。
    private func noteFavoriteOutcome(_ result: FavoriteResolution) {
        let now = Date()
        if result.challenged || result.incomplete {
            favoriteRetry.recordFailure(at: now)
        } else {
            favoriteRetry.reset()
        }
    }

    private func runFavoriteBatch(_ id: Int) async -> FavoriteResolution {
        let life = sharedToken()
        let plan = FavoriteResolver.plan(
            favorites: curation.favorites, catalog: knownCatalog, cache: favoriteStatus, now: Date()
        )
        var statuses = plan.resolved
        var fetched: [String: FavoriteStatus] = [:]
        var challenged = false
        if !plan.pending.isEmpty, lifecycleAllows(.beforeRequest, life) {
            let result = await fetchFavoriteStatuses(plan.pending)
            fetched = result.statuses
            statuses.merge(fetched) { _, new in new }
            challenged = result.challenged
        }
        let incomplete = FavoriteResolver.hasUnresolved(pending: plan.pending, statuses: fetched)
        guard favoriteRunner.isCurrent(id), !Task.isCancelled, lifecycleAllows(.beforeCommit, life) else {
            return FavoriteResolution(statuses: statuses, challenged: challenged, incomplete: incomplete)
        }
        // 写回时基于此刻的缓存（不是批次开始时的快照），免得盖掉期间被重置或更新的结果。
        favoriteStatus = favoriteStatus
            .storing(stamped: plan.fromCatalog)
            .storing(fetched, at: Date())
            .pruned(keeping: curation.favorites)
        return FavoriteResolution(statuses: statuses, challenged: challenged, incomplete: incomplete)
    }

    /// 先走普通网络；被站点拦截后改经侧车（浏览器里已通过确认的会话）重查没答案的那部分。
    /// 侧车也被拦截才算 `challenged`，交给确认页；确认完成后由 `retryAfterChallenge` 重跑。
    private func fetchFavoriteStatuses(_ names: [String]) async -> FavoriteFetchResult {
        let viaSidecar = Date() < sidecarUntil
        let first = await FavoriteFetcher(client: viaSidecar ? SidecarHTTPClient(sidecar: sidecar) : http).fetch(names)
        guard first.challenged, !viaSidecar else { return first }
        sidecarUntil = Date().addingTimeInterval(600)
        let rest = names.filter { first.statuses[Curation.key($0)] == nil }
        let second = await FavoriteFetcher(client: SidecarHTTPClient(sidecar: sidecar)).fetch(rest)
        return FavoriteFetchResult(statuses: first.statuses.merging(second.statuses) { _, new in new }, challenged: second.challenged)
    }

    func fetchFavoritesPage() async throws -> CatalogPage {
        let names = curation.favorites
        let result = await resolveFavorites()
        if result.challenged { throw CatalogFailure.challenge }
        let rooms = FavoriteResolver.onlineRooms(favorites: names, statuses: result.statuses)
        return CatalogPage(rooms: rooms, total: rooms.count, nextOffset: rooms.count)
    }

    /// 重试：收藏来源要忽略缓存重查，其他来源照常重新加载。
    func retry() async {
        if source == .favorites { await refreshFavorites() } else { await reload() }
    }

    /// 忽略缓存与目录，把全部收藏重查一遍。收藏来源下同时刷新列表。
    func refreshFavorites() async {
        guard !favoritesChecking else { return }
        favoriteRetry.reset()
        favoriteStatus = FavoriteCache()
        knownCatalog = KnownCatalog()
        if source == .favorites {
            await reload()
        } else {
            await checkFavorites()
        }
    }

    /// 只补查没有新鲜结果的收藏（添加、导入之后用），被拦截时走确认流程。
    func checkFavorites() async {
        let result = await resolveFavorites()
        guard result.challenged, sharedToken() != nil else { return }
        guard allowChallengePrompt() else {
            showToast("站点连续要求确认，收藏状态稍后再查")
            return
        }
        challengeResume = challengeResume?.merging(.favorites) ?? .favorites
        dismissSurfaces()
        presentCatalogChallenge()
    }

    /// 打开收藏夹时用：缓存里有收藏已过期（或从未查过）就补查一次；被站点拦截时不自动弹确认页，留给「刷新状态」。
    /// 上次查询失败或被拦截后 60 秒内不再自动补查。
    func refreshFavoritesIfStale() async {
        let now = Date()
        guard !favoritesChecking, favoriteRetry.allowsAutoCheck(now: now), curation.favorites.contains(where: { favoriteStatus.fresh(for: $0, now: now) == nil }) else { return }
        _ = await resolveFavorites()
    }

    /// 打开收藏夹时用：离线的收藏没有 30 分钟内的上次开播时间才查（并发 4，同一时间一批）。
    /// 被站点拦截时不弹确认页；失败或被拦截后 60 秒内不再自动补查。
    /// 在可取消的子任务里跑，挂起时整段取消（§3.1）。
    func refreshLastBroadcastsIfStale() async {
        await trackShared { [weak self] in await self?.refreshLastBroadcastsTracked() }
    }

    private func refreshLastBroadcastsTracked() async {
        let now = Date()
        let offline = curation.favorites.filter { favoriteStatus.status(for: $0) == .offline }
        let pending = lastBroadcasts.pending(offline, now: now)
        guard !lastBroadcastChecking, !pending.isEmpty, lastBroadcastRetry.allowsAutoCheck(now: now),
              let life = sharedToken() else { return }
        lastBroadcastChecking = true
        let client: any HTTPClient = now < sidecarUntil ? SidecarHTTPClient(sidecar: sidecar) : http
        let result = await LastBroadcastFetcher(client: client).fetch(pending)
        lastBroadcastChecking = false
        guard lifecycleAllows(.beforeCommit, life), !Task.isCancelled else { return }
        lastBroadcasts = lastBroadcasts.storing(result.found, at: Date()).pruned(keeping: curation.favorites)
        if result.challenged || result.found.count < pending.count {
            lastBroadcastRetry.recordFailure(at: Date())
        } else {
            lastBroadcastRetry.reset()
        }
    }

    /// 在收藏夹里点一个在线的收藏：已在列表里就切过去，否则插到当前房间之后再播。
    func playFavorite(_ name: String) {
        // 状态缓存里没有时退到当前目录（收藏夹的在线判断也用同样的回退）。
        let key = Curation.key(name)
        let room = favoriteStatus.status(for: name)?.room
            ?? feed.catalog.first { Curation.key($0.username) == key && $0.show.isPlayable }
        guard let room else { return }
        cancelSiteSearch()
        dismissSurfaces()
        // 搜索条件会挡住目标时清掉它，确保播放的就是点击的房间。
        updateFeed(intent: .reveal(room.username)) { _ = $0.reveal(room) }
        if siteSearch == nil, feed.query.text != searchText { searchText = feed.query.text }
        requestPlay()
    }

    /// 收藏夹弹窗与菜单栏共用的数据：收藏名单、状态缓存、当前目录整理成的看板。
    var favoritesBoard: FavoritesBoard {
        var statuses: [String: FavoriteStatus] = [:]
        for name in curation.favorites {
            if let status = favoriteStatus.status(for: name) { statuses[name] = status }
        }
        return FavoritesBoard.make(favorites: curation.favorites, statuses: statuses, catalog: feed.catalog)
    }

    /// 按用户名手动添加。返回错误文案，成功返回 nil。
    func addFavorite(_ raw: String) -> String? {
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Room.isValidUsername(name) else { return "用户名只含字母、数字、下划线，2 到 30 位" }
        if curation.isFavorite(name) { return "已经在收藏里了" }
        commit(curation.favoriting(name), nil)
        if source != .favorites { Task { await checkFavorites() } }
        return nil
    }

    /// 导出文件的内容（JSON）。文件面板（macOS）或 `.fileExporter`（iOS）负责写到哪里。
    func exportFavoritesData() throws -> Data {
        try FavoriteExchange.encode(curation.favorites)
    }

    /// 从用户选中的文件导入并合并收藏。文件面板（macOS）或 `.fileImporter`（iOS）负责选文件。
    func importFavorites(_ url: URL) {
        do {
            let size = (try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
            guard size <= Preferences.maxFileBytes else { throw FavoriteExchange.Failure.tooLarge }
            let imported = try FavoriteExchange.decode(Data(contentsOf: url))
            let merged = curation.importing(imported.names)
            let skipped = imported.rejected > 0 ? "，忽略 \(imported.rejected) 条格式不对的" : ""
            commit(merged.curation, "已导入 \(merged.added) 个收藏\(skipped)")
            if source != .favorites { Task { await checkFavorites() } }
        } catch FavoriteExchange.Failure.tooLarge {
            showToast("文件太大，不是收藏夹导出文件")
        } catch {
            showToast("无法识别这个文件")
        }
    }
}
