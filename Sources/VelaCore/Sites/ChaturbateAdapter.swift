import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Chaturbate 走现有目录与起播路径，行为不变。侧车（冷却期直走侧车、质询或网络故障改走侧车）留在 Shared。
public struct ChaturbateAdapter: SiteAdapter {
    private let client: any HTTPClient
    private let backoff: EndpointBackoffBox?

    public init(client: any HTTPClient, backoff: EndpointBackoffBox? = .shared) {
        self.client = client
        self.backoff = backoff
    }

    public var site: SiteID { .chaturbate }

    public var capabilities: SiteCapabilities {
        [
            .serverGender, .serverRegion, .serverTag, .freshCams, .keywordSearch,
            .hotTags, .lastBroadcast, .challengePage
        ]
    }

    public var challengeHome: URL? { URL(string: "https://chaturbate.com/") }

    public func fetchDirectory(_ query: CatalogQuery) async throws -> CatalogPage {
        try await CatalogAPI(client: client, backoff: backoff).fetch(query)
    }

    /// `chatvideocontext` → `StreamSourcing.first` →（需要时）`StreamDecoder.fetch` → `StreamSourcing.settle`。
    public func resolveStream(_ room: Room) async throws -> SourcedTicket {
        let name = room.username
        let probe = try await ChatContext.probe(username: name, client: client, backoff: backoff)
        if case .use(let ticket) = StreamSourcing.first(probe) {
            return SourcedTicket(ticket: ticket, origin: .chatContext)
        }
        let fallback = try await StreamDecoder.fetch(username: name, client: client, backoff: backoff)
        return SourcedTicket(ticket: StreamSourcing.settle(probe: probe, fallback: fallback), origin: .edge)
    }

    public func status(of keys: [RoomKey], now: Date) async -> [RoomKey: FavoriteStatus] {
        let mine = keys.filter { $0.site == site }
        guard !mine.isEmpty else { return [:] }
        let fetched = await FavoriteFetcher(client: client, now: { now }, backoff: backoff).fetch(mine.map(\.name))
        var out: [RoomKey: FavoriteStatus] = [:]
        for key in mine {
            if let status = fetched.statuses[Curation.key(key.name)] ?? fetched.statuses[key.normalized] {
                out[key] = status
            }
        }
        return out
    }

    public func thumbnail(for room: Room, now: Date) -> URL? { room.thumbnailURL }

    public func mediaHeaders(for key: RoomKey) -> [String: String] {
        ["Referer": "https://chaturbate.com/", "Origin": "https://chaturbate.com"]
    }

    public func masterFailure(status: Int) -> StreamFailure? { nil }
}
