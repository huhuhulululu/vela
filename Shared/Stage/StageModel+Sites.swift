import Foundation

/// 站点接线（docs/plans/sites.md Phase B）：站点模式、按房间所属站点取适配器、请求头与主列表判定、直达输入、启动迁移。
/// `cb` 的目录、起播、侧车路径都不经这里改动，只有非 `cb` 房间走适配器。
@MainActor
extension StageModel {
    /// 切换站点模式：记住选择，按新模式重建目录入口并重新加载（等同切来源）。
    @_optimize(size)
    func chooseSite(_ mode: SiteMode) {
        guard siteMode != mode else { return }
        siteMode = mode
        settings.siteMode = mode
        directory = SiteDirectory(mode: mode, registry: sites, chaturbate: catalogAPI)
        Task { await reload() }
    }

    @_optimize(size)
    func siteAdapter(forRef ref: String) -> (any SiteAdapter)? {
        sites.adapter(for: RoomKey.site(ofRef: ref))
    }

    /// 中继与播放器取列表、分片时带的头。没注册的站点退回 cb 的头（与站点抽象之前相同）。
    @_optimize(size)
    func mediaHeaders(forRef ref: String) -> [String: String] {
        guard let key = RoomKey(parsing: ref), key.site != .chaturbate,
              let adapter = sites.adapter(for: key.site) else { return PlaylistRelay.defaultHeaders }
        return adapter.mediaHeaders(for: key)
    }

    /// 主列表状态码能否定论这间不可播（只有 strictMaster 站点会给出原因；cb 恒 nil）。
    @_optimize(size)
    func masterFailure(forRef ref: String, status: Int) -> StreamFailure? {
        guard RoomKey.site(ofRef: ref) != .chaturbate else { return nil }
        return siteAdapter(forRef: ref)?.masterFailure(status: status)
    }

    /// 非 cb 房间的起播解析：用列表里的房间（带目录给的流地址提示），不在列表里就按键直达。
    @_optimize(size)
    func resolveOnSite(_ ref: String) async throws -> SourcedTicket {
        guard let key = RoomKey(parsing: ref), let adapter = sites.adapter(for: key.site) else {
            throw SiteError.unknownSite(ref)
        }
        let room = feed.catalog.first { $0.identity == key.normalized } ?? Room.direct(key: key)
        return try await adapter.resolveStream(room)
    }

    /// 搜索框回车直达：带站点前缀按前缀；裸名在 Cam4 模式按 c4，其余按 cb。名字不合法返回 nil。
    @_optimize(size)
    func directRoom(_ text: String) -> Room? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.contains(":") { return RoomKey(parsing: trimmed).map(Room.direct(key:)) }
        let site: SiteID = siteMode == .cam4 ? .cam4 : .chaturbate
        return RoomKey(site: site, name: trimmed).map(Room.direct(key:))
    }

    /// 收藏里属于当前站点模式的那部分（收藏来源只列这些）。
    @_optimize(size)
    func favoritesInMode(_ names: [String]) -> [String] {
        let sites = Set(siteMode.sites)
        return names.filter { sites.contains(RoomKey.site(ofRef: $0)) }
    }

    /// 非 cb 收藏的在线状态：只问各站适配器（Cam4 只查目录索引，不逐个请求）。键为小写 ref。
    @_optimize(size)
    func otherSiteStatuses(_ names: [String]) async -> [String: FavoriteStatus] {
        let keys = names.compactMap { RoomKey(parsing: $0) }.filter { $0.site != .chaturbate }
        var out: [String: FavoriteStatus] = [:]
        for site in SiteID.allCases where site != .chaturbate {
            guard let adapter = sites.adapter(for: site) else { continue }
            for (key, status) in await adapter.status(of: keys, now: Date()) { out[key.normalized] = status }
        }
        return out
    }

    /// 启动时把偏好文件的房间键一次落成带前缀格式；全部成功才记版本，失败的下次启动再试（原文件不动）。
    @_optimize(size)
    static func migrateSiteKeys(_ settings: SettingsStore) {
        guard settings.siteKeyVersion < SiteKeyMigration.version else { return }
        let report = SiteKeyMigration.run(
            curation: CurationStore.defaultURL(), taste: TasteStore.defaultURL(), ledger: WatchLedgerStore.defaultURL()
        )
        if report.failed.isEmpty { settings.siteKeyVersion = SiteKeyMigration.version }
    }

    /// 观看地区：设备所在地区（遵守主播设的地区屏蔽，不按代理出口判断）。取不到为空集，不过滤。
    @_optimize(size)
    static func viewerRegions() -> Set<String> {
        guard let code = Locale.current.region?.identifier.uppercased(), code.count == 2 else { return [] }
        return [code]
    }
}
