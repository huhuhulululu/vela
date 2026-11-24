import Foundation

/// 站点接线（docs/plans/sites.md Phase B）：站点模式、按房间所属站点取适配器、请求头与主列表判定、直达输入、启动迁移。
/// `cb` 的目录、起播、侧车路径都不经这里改动，只有非 `cb` 房间走适配器。
@MainActor
extension StageModel {
    /// 切换站点模式：记住选择，按新模式重建目录入口并重新加载（等同切来源）。
    func chooseSite(_ mode: SiteMode) {
        guard siteMode != mode else { return }
        siteMode = mode
        settings.siteMode = mode
        directory = SiteDirectory(mode: mode, registry: sites, chaturbate: catalogAPI)
        Task { await reload() }
    }

    func siteAdapter(forRef ref: String) -> (any SiteAdapter)? {
        sites.adapter(for: RoomKey.site(ofRef: ref))
    }

    /// 中继与播放器取列表、分片时带的头。没注册的站点退回 cb 的头（与站点抽象之前相同）。
    func mediaHeaders(forRef ref: String) -> [String: String] {
        guard let key = RoomKey(parsing: ref), key.site != .chaturbate,
              let adapter = sites.adapter(for: key.site) else { return PlaylistRelay.defaultHeaders }
        return adapter.mediaHeaders(for: key)
    }

    /// 主列表状态码能否定论这间不可播（只有 strictMaster 站点会给出原因；cb 恒 nil）。
    func masterFailure(forRef ref: String, status: Int) -> StreamFailure? {
        guard RoomKey.site(ofRef: ref) != .chaturbate else { return nil }
        return siteAdapter(forRef: ref)?.masterFailure(status: status)
    }

    /// 非 cb 房间的起播解析：用列表里的房间（带目录给的流地址提示），不在列表里就按键直达。
    func resolveOnSite(_ ref: String) async throws -> SourcedTicket {
        guard let key = RoomKey(parsing: ref), let adapter = sites.adapter(for: key.site) else {
            throw SiteError.unknownSite(ref)
        }
        let room = feed.catalog.first { $0.identity == key.normalized } ?? Room.direct(key: key)
        return try await adapter.resolveStream(room)
    }

    /// 令该房间的站点流缓存失效（403 续期、主列表失败、自动/手动重试前调用）。
    func invalidateSiteStream(_ ref: String) {
        guard let key = RoomKey(parsing: ref), key.site != .chaturbate else { return }
        sites.adapter(for: key.site)?.invalidateStream(for: key)
    }

    /// 不可播时能否据此把房间从列表筛掉：只认接口实测确认的非公开状态（`StreamTicket.delistedShow`）。
    /// Cam4 只有目录明确标了私密才算确认；它的「离线」是重取那一页没找到推断出来的（可能只是换了页），不筛只跳过。
    func delistState(_ ticket: StreamTicket, forRef ref: String) -> ShowState? {
        guard let state = ticket.delistedShow else { return nil }
        guard RoomKey.site(ofRef: ref) != .chaturbate else { return state }
        return state == .privateShow ? state : nil
    }

    /// 搜索框回车直达：带站点前缀按前缀；裸名在 Cam4 模式按 c4，其余按 cb。名字不合法返回 nil。
    func directRoom(_ text: String) -> Room? {
        siteMode.directRoom(text)
    }

    /// 收藏里属于当前站点模式的那部分（收藏来源只列这些）。
    func favoritesInMode(_ names: [String]) -> [String] {
        siteMode.filterFavorites(names)
    }

    /// 非 cb 收藏的在线状态：只问各站适配器（Cam4 只查目录索引，不逐个请求）。键为小写 ref。
    func otherSiteStatuses(_ names: [String]) async -> [String: FavoriteStatus] {
        let keys = names.compactMap { RoomKey(parsing: $0) }.filter { $0.site != .chaturbate }
        var out: [String: FavoriteStatus] = [:]
        for site in SiteID.allCases where site != .chaturbate {
            guard let adapter = sites.adapter(for: site) else { continue }
            for (key, status) in await adapter.status(of: keys, now: Date()) { out[key.normalized] = status }
        }
        return out
    }

    /// 站点键迁移能不能现在跑：还没迁移过、没有未清理的同步加入快照（`pending` 要先换回或删除，
    /// `committed` / `incomplete` 要先删除，都由同步启动恢复处理）、本机写入未被提交锁或待修复拒绝。
    nonisolated static func shouldMigrateSiteKeys(version: Int, journal: SyncJoinJournalStatus, localWriteAllowed: Bool) -> Bool {
        version < SiteKeyMigration.version && journal == .none && localWriteAllowed
    }

    /// 启动时（同步关着，或没有残留快照）与同步启动恢复结束后各调一次：条件不满足就推迟、不记版本。
    static func migrateSiteKeysIfAllowed(
        _ settings: SettingsStore,
        journal: SyncJoinJournalStatus,
        localWriteAllowed: Bool,
        curation: URL = CurationStore.defaultURL(),
        taste: URL = TasteStore.defaultURL(),
        ledger: URL = WatchLedgerStore.defaultURL()
    ) {
        guard shouldMigrateSiteKeys(version: settings.siteKeyVersion, journal: journal, localWriteAllowed: localWriteAllowed) else { return }
        migrateSiteKeys(settings, curation: curation, taste: taste, ledger: ledger)
    }

    /// 把偏好文件的房间键一次落成带前缀格式；全部成功才记版本，失败的下次再试（原文件不动）。
    private static func migrateSiteKeys(_ settings: SettingsStore, curation: URL, taste: URL, ledger: URL) {
        let report = SiteKeyMigration.run(curation: curation, taste: taste, ledger: ledger)
        if report.failed.isEmpty { settings.siteKeyVersion = SiteKeyMigration.version }
    }

    /// 观看地区：设备所在地区（遵守主播设的地区屏蔽，不按代理出口判断）。取不到为空集，不过滤。
    static func viewerRegions() -> Set<String> {
        guard let code = Locale.current.region?.identifier.uppercased(), code.count == 2 else { return [] }
        return [code]
    }
}
