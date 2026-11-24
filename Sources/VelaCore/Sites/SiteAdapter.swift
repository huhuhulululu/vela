import Foundation

/// 一个站点列表 / 播放接口实际具备的能力。没有的筛选项不向该站发请求。
public struct SiteCapabilities: OptionSet, Sendable {
    public let rawValue: Int

    public init(rawValue: Int) { self.rawValue = rawValue }

    /// 列表接口能按性别筛。
    public static let serverGender = SiteCapabilities(rawValue: 1 << 0)
    /// 能按地区筛。
    public static let serverRegion = SiteCapabilities(rawValue: 1 << 1)
    /// 能按标签筛。
    public static let serverTag = SiteCapabilities(rawValue: 1 << 2)
    /// 有「新开播」筛选。
    public static let freshCams = SiteCapabilities(rawValue: 1 << 3)
    /// 有全站搜索。
    public static let keywordSearch = SiteCapabilities(rawValue: 1 << 4)
    /// 有热门标签接口。
    public static let hotTags = SiteCapabilities(rawValue: 1 << 5)
    /// 能查离线收藏的上次开播。
    public static let lastBroadcast = SiteCapabilities(rawValue: 1 << 6)
    /// 会出质询页，需要侧车确认（只有 cb）。
    public static let challengePage = SiteCapabilities(rawValue: 1 << 7)
    /// 主列表取不到（5xx / 403 / 404）即判这间不可播（只有 c4）。
    public static let strictMaster = SiteCapabilities(rawValue: 1 << 8)
}

public protocol SiteAdapter: Sendable {
    var site: SiteID { get }
    var capabilities: SiteCapabilities { get }
    /// 质询确认页的入口（侧车与确认页打开它）；没有质询能力为 nil。
    var challengeHome: URL? { get }

    /// 目录分页。复用 `CatalogQuery`。能力里没有、又不能本地过滤的筛选项：返回空页且 `nextOffset == total`，不发请求。
    func fetchDirectory(_ query: CatalogQuery) async throws -> CatalogPage

    /// 房间 → 可播放流。质询抛 `StreamDecodeError.challenge`；普通不可用抛 `.unavailable(status:)`；
    /// 退避中抛 `EndpointBackedOff`；网络错误与取消原样抛出。
    func resolveStream(_ room: Room) async throws -> SourcedTicket

    /// 在线状态。查不到的键不放进结果（界面显示「未查询」）。
    func status(of keys: [RoomKey], now: Date) async -> [RoomKey: FavoriteStatus]

    /// 此刻可用的缩略图。距 `thumbnailExpires` 不足 60 秒返回 nil，不为它单独发请求。
    func thumbnail(for room: Room, now: Date) -> URL?

    /// 中继取主列表 / 子列表、AVURLAsset 直连时要带的头（Referer、Origin）。
    func mediaHeaders(for key: RoomKey) -> [String: String]

    /// 主列表状态码是否已能定论为不可播。仅 `strictMaster` 站点返回非 nil。
    func masterFailure(status: Int) -> StreamFailure?

    /// 该站点能否处理本次目录查询。默认按 `capabilities` 判定地区、标签、新开播三项服务端专属条件。
    func supportsDirectory(_ query: CatalogQuery) -> Bool

    /// 令缓存中的房间流地址失效，下次解析强制刷新（无流缓存的站点忽略）。
    func invalidateStream(for key: RoomKey)
}

public extension SiteAdapter {
    func supportsDirectory(_ query: CatalogQuery) -> Bool {
        if query.region != nil, !capabilities.contains(.serverRegion) { return false }
        if CatalogQuery.normalizedHashtag(query.tag) != nil, !capabilities.contains(.serverTag) { return false }
        if query.newCams, !capabilities.contains(.freshCams) { return false }
        return true
    }

    func invalidateStream(for key: RoomKey) {}
}

public enum SiteError: Error, Equatable, Sendable {
    /// 键里的站点没有注册适配器。
    case unknownSite(String)
    /// 调了该站没有的能力（hotTags、lastBroadcast 等）。
    case unsupported(SiteID)
}

public struct SiteRegistry: Sendable {
    private let bySite: [SiteID: any SiteAdapter]

    public init(adapters: [any SiteAdapter]) {
        var map: [SiteID: any SiteAdapter] = [:]
        for adapter in adapters { map[adapter.site] = adapter }
        self.bySite = map
    }

    public func adapter(for site: SiteID) -> (any SiteAdapter)? { bySite[site] }

    public static func live(
        client: any HTTPClient,
        backoff: EndpointBackoffBox? = .shared,
        viewerRegions: Set<String>
    ) -> SiteRegistry {
        SiteRegistry(adapters: [
            ChaturbateAdapter(client: client, backoff: backoff),
            Cam4Adapter(client: client, backoff: backoff, viewerRegions: viewerRegions)
        ])
    }
}
