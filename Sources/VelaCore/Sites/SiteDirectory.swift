import Foundation

/// 舞台取目录的唯一入口：`cb` 模式原样走 `CatalogAPI`（行为不变）；`c4` 模式只问 Cam4；
/// 混合模式经 `SiteCatalog` 并发取两站，`CatalogPage.nextOffset` 是各站游标之和，
/// 这里记住「游标之和 → 各站游标」，下一页按它接着取。记不住的偏移从头取（重复的房间由去重吸收）。
public final class SiteDirectory: @unchecked Sendable {
    private struct QueryScope: Hashable {
        var gender: Gender?
        var region: Region?
        var tag: String?
        var limit: Int
        var newCams: Bool
        var keywords: String?

        init(_ query: CatalogQuery) {
            gender = query.gender
            region = query.region
            tag = CatalogQuery.normalizedHashtag(query.tag)
            limit = min(max(query.limit, 1), CatalogQuery.maxPageSize)
            newCams = query.newCams
            keywords = SiteSearch.normalized(query.keywords)
        }
    }

    public let mode: SiteMode
    private let registry: SiteRegistry
    private let chaturbate: CatalogAPI
    private let lock = NSLock()
    private var cursors: [QueryScope: [Int: MixedCursor]] = [:]
    private var scopeOrder: [QueryScope] = []
    private var failed: [SiteID] = []

    public init(mode: SiteMode, registry: SiteRegistry, chaturbate: CatalogAPI) {
        self.mode = mode
        self.registry = registry
        self.chaturbate = chaturbate
    }

    /// 混合模式最近一次取页时失败的站（只记日志用）。
    public var lastFailedSites: [SiteID] { lock.withLock { failed } }

    public func fetch(_ query: CatalogQuery) async throws -> CatalogPage {
        switch mode {
        case .chaturbate:
            return try await chaturbate.fetch(query)
        case .cam4:
            guard let adapter = registry.adapter(for: .cam4) else { throw SiteError.unknownSite(SiteID.cam4.rawValue) }
            return try await adapter.fetchDirectory(query)
        case .mixed:
            return try await fetchMixed(query)
        }
    }

    /// 与 `CatalogAPI.fetchPages` 同一循环：没取满、上一页是满页、游标没到底就接着取。
    public func fetchPages(_ query: CatalogQuery, pages: Int) async throws -> CatalogPage {
        if mode == .chaturbate { return try await chaturbate.fetchPages(query, pages: pages) }
        return try await CatalogWindow.collect(query, pages: pages, fetch: fetch)
    }

    private func fetchMixed(_ query: CatalogQuery) async throws -> CatalogPage {
        let sites = mode.sites
        let scope = QueryScope(query)
        let cursor: MixedCursor = lock.withLock {
            if query.offset <= 0 { return MixedCursor(sites: sites) }
            touchScope(scope)
            return cursors[scope]?[query.offset] ?? MixedCursor(sites: sites)
        }
        let result = try await SiteCatalog.fetch(mode: mode, query: query, cursor: cursor, registry: registry)
        lock.withLock {
            failed = sites.filter { result.failures[$0] != nil }
            var bucket = query.offset <= 0 ? [:] : (cursors[scope] ?? [:])
            if bucket.count >= 64 { bucket = [:] }
            bucket[result.page.nextOffset] = result.cursor
            cursors[scope] = bucket
            touchScope(scope)
            while scopeOrder.count > 16 {
                let oldest = scopeOrder.removeFirst()
                cursors.removeValue(forKey: oldest)
            }
        }
        return result.page
    }

    private func touchScope(_ scope: QueryScope) {
        scopeOrder.removeAll { $0 == scope }
        scopeOrder.append(scope)
    }
}

extension RoomKey {
    /// 内存 ref 属于哪个站：带已知站点前缀的按前缀，其余（裸名、测试房间）一律是 cb。
    public static func site(ofRef ref: String) -> SiteID {
        let trimmed = ref.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let colon = trimmed.firstIndex(of: ":"),
              let site = SiteID(rawValue: SyncKeys.asciiLowercased(String(trimmed[..<colon]))) else { return .chaturbate }
        return site
    }
}

extension Room {
    /// 此刻能用的缩略图：带过期时刻且离过期不足 `Cam4Adapter.thumbnailMargin` 时为 nil（界面用模糊占位，不为它单独请求）。
    public func usableThumbnail(now: Date = Date()) -> URL? {
        guard let expires = thumbnailExpires else { return thumbnailURL }
        return expires.timeIntervalSince(now) < Cam4Adapter.thumbnailMargin ? nil : thumbnailURL
    }
}
