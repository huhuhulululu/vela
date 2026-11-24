import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Cam4 访客适配器。目录索引只在内存，上限 2000 条，超出丢最旧。不轮询。
public final class Cam4Adapter: SiteAdapter, @unchecked Sendable {
    public static let streamTTL: TimeInterval = 10 * 60
    public static let statusTTL: TimeInterval = 3 * 60
    public static let indexLimit = 2_000
    /// 缩略图在过期前这么久就不再用，等下次目录刷新。
    public static let thumbnailMargin: TimeInterval = 60

    /// 本地过滤把原页滤短或滤空时，单次目录请求最多顺延扫描的原页数。
    public static let maxFilterScans = 5

    private struct Entry {
        var room: Room
        var offset: Int
        var limit: Int
        var fetchedAt: Date
        var geo: Bool
    }

    private let client: any HTTPClient
    private let backoff: EndpointBackoffBox?
    private let viewerRegions: Set<String>
    private let clock: @Sendable () -> Date
    private let lock = NSLock()
    private var index: [String: Entry] = [:]
    private var order: [String] = []

    public init(
        client: any HTTPClient,
        backoff: EndpointBackoffBox? = .shared,
        viewerRegions: Set<String>,
        clock: @escaping @Sendable () -> Date = Date.init
    ) {
        self.client = client
        self.backoff = backoff
        self.viewerRegions = viewerRegions
        self.clock = clock
    }

    public var site: SiteID { .cam4 }
    public var capabilities: SiteCapabilities { .strictMaster }
    public var challengeHome: URL? { nil }

    public func fetchDirectory(_ query: CatalogQuery) async throws -> CatalogPage {
        if unsupported(query) {
            return CatalogPage(rooms: [], total: 0, nextOffset: 0)
        }
        let limit = min(max(query.limit, 1), Cam4Directory.maxPageSize)
        let needsFilter = !viewerRegions.isEmpty || query.gender != nil || SiteSearch.normalized(query.keywords) != nil
        var cursor = max(query.offset, 0)
        var collected: [Room] = []
        var seen = Set<String>()
        var total = 0
        var scans = 0
        while true {
            let raw = try await load(offset: cursor, limit: limit)
            scans += 1
            total = max(total, raw.total)
            for room in filtered(raw, query: query).rooms where seen.insert(room.identity).inserted {
                collected.append(room)
            }
            let upstreamStep = raw.nextOffset - cursor
            cursor = raw.nextOffset
            if !needsFilter || collected.count >= limit || upstreamStep < limit || upstreamStep <= 0 || cursor >= total || scans >= Self.maxFilterScans {
                break
            }
        }
        return CatalogPage(rooms: collected, total: total, nextOffset: cursor)
    }

    public func resolveStream(_ room: Room) async throws -> SourcedTicket {
        let name = room.key.name.lowercased()
        let cached = lookup(name)
        if let cached, fresh(cached, ttl: Self.streamTTL, now: clock()) {
            return ticket(cached, url: cached.room.stream)
        }
        let offset = cached?.offset ?? 0
        let limit = cached?.limit ?? Cam4Directory.maxPageSize
        // 这一页没再出现的人要从索引拿掉，否则旧的播放地址会被当成还在播。
        let stamp = clock()
        _ = try await load(offset: offset, limit: limit)
        if let refreshed = lookup(name), refreshed.fetchedAt >= stamp {
            return ticket(refreshed, url: refreshed.room.stream)
        }
        drop(name)
        return SourcedTicket(
            ticket: StreamTicket(success: true, url: nil, status: .offline),
            origin: .cam4Directory
        )
    }

    public func status(of keys: [RoomKey], now: Date) async -> [RoomKey: FavoriteStatus] {
        var out: [RoomKey: FavoriteStatus] = [:]
        lock.lock()
        defer { lock.unlock() }
        for key in keys where key.site == site {
            guard let entry = index[key.name.lowercased()],
                  now.timeIntervalSince(entry.fetchedAt) <= Self.statusTTL else { continue }
            if entry.geo {
                out[key] = .restricted
            } else if entry.room.show == .privateShow || entry.room.stream == nil {
                out[key] = .offline
            } else {
                out[key] = .online(entry.room)
            }
        }
        return out
    }

    public func thumbnail(for room: Room, now: Date) -> URL? {
        guard let url = room.thumbnailURL else { return nil }
        if let expires = room.thumbnailExpires, expires.timeIntervalSince(now) < Self.thumbnailMargin {
            return nil
        }
        return url
    }

    public func mediaHeaders(for key: RoomKey) -> [String: String] {
        ["Referer": "https://www.cam4.com/", "Origin": "https://www.cam4.com"]
    }

    public func masterFailure(status: Int) -> StreamFailure? {
        if (500...599).contains(status) || status == 403 || status == 404 { return .unavailable }
        return nil
    }

    // MARK: 目录

    private func unsupported(_ query: CatalogQuery) -> Bool {
        query.region != nil || CatalogQuery.normalizedHashtag(query.tag) != nil || query.newCams
    }

    private func load(offset: Int, limit: Int) async throws -> CatalogPage {
        if let backoff, !backoff.allows(.cam4Directory) { throw EndpointBackedOff(.cam4Directory) }
        let pageSize = min(max(limit, 1), Cam4Directory.maxPageSize)
        var request = URLRequest(url: Cam4Directory.url(offset: offset, limit: pageSize))
        request.httpMethod = "GET"
        request.setValue(UserAgent.safari, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
        request.setValue("https://www.cam4.com/", forHTTPHeaderField: "Referer")
        request.setValue("https://www.cam4.com", forHTTPHeaderField: "Origin")
        request.timeoutInterval = 12
        let result = try await client.send(request)
        let nonJSON = ResponseShape.shouldBackOff(status: result.status, data: result.data)
        if !(200..<300).contains(result.status) {
            if nonJSON { backoff?.record(.cam4Directory) }
            throw CatalogFailure.status(result.status)
        }
        if nonJSON || !ResponseShape.looksLikeJSON(result.data) {
            backoff?.record(.cam4Directory)
            throw CatalogFailure.malformed
        }
        let now = clock()
        let page = try Cam4Directory.decode(
            result.data, requestLimit: pageSize, requestOffset: offset, now: now, viewerRegions: viewerRegions
        )
        remember(page.rooms, offset: offset, limit: pageSize, at: now)
        return page
    }

    /// 观看地区被主播屏蔽的房间不进目录（只留在索引里供状态与直达起播判 geo）；总数与游标仍按原页。
    private func filtered(_ page: CatalogPage, query: CatalogQuery) -> CatalogPage {
        var rooms = page.rooms.filter { !geoBlocked($0) }
        if let gender = query.gender {
            rooms = rooms.filter { $0.gender == gender }
        }
        if let keywords = SiteSearch.normalized(query.keywords) {
            let needle = keywords.lowercased()
            rooms = rooms.filter { $0.username.lowercased().contains(needle) || $0.identity.contains(needle) }
        }
        return CatalogPage(rooms: rooms, total: page.total, nextOffset: page.nextOffset)
    }

    private func remember(_ rooms: [Room], offset: Int, limit: Int, at date: Date) {
        lock.lock()
        defer { lock.unlock() }
        for room in rooms {
            let name = room.key.name.lowercased()
            index[name] = Entry(room: room, offset: offset, limit: limit, fetchedAt: date, geo: geoBlocked(room))
            order.removeAll { $0 == name }
            order.append(name)
        }
        while order.count > Self.indexLimit {
            let oldest = order.removeFirst()
            index.removeValue(forKey: oldest)
        }
    }

    private func geoBlocked(_ room: Room) -> Bool {
        room.show == .publicShow && room.stream == nil && !room.blockedRegions.isEmpty
            && Cam4Regions.isBlocked(room.blockedRegions, viewer: viewerRegions)
    }

    private func lookup(_ name: String) -> Entry? {
        lock.lock()
        defer { lock.unlock() }
        return index[name]
    }

    private func drop(_ name: String) {
        lock.lock()
        defer { lock.unlock() }
        index.removeValue(forKey: name)
        order.removeAll { $0 == name }
    }

    private func fresh(_ entry: Entry, ttl: TimeInterval, now: Date) -> Bool {
        now.timeIntervalSince(entry.fetchedAt) <= ttl
    }

    private func ticket(_ entry: Entry, url: URL?) -> SourcedTicket {
        if entry.geo {
            return SourcedTicket(ticket: StreamTicket(success: true, url: nil, status: .publicShow), origin: .cam4Directory)
        }
        if entry.room.show == .privateShow {
            return SourcedTicket(ticket: StreamTicket(success: true, url: nil, status: .privateShow), origin: .cam4Directory)
        }
        guard let url else {
            return SourcedTicket(ticket: StreamTicket(success: true, url: nil, status: .offline), origin: .cam4Directory)
        }
        return SourcedTicket(ticket: StreamTicket(success: true, url: url, status: .publicShow), origin: .cam4Directory)
    }
}
