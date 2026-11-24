import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct CatalogQuery: Equatable, Sendable {
    /// 站点列表接口单页上限（limit=500 会 400，实测 90 可用）。
    public static let maxPageSize = 90

    public var gender: Gender?
    public var region: Region?
    public var tag: String?
    public var limit: Int
    public var offset: Int
    /// 站点自己的「新开播」筛选（`new_cams=true`），可与性别、地区、标签叠加。
    public var newCams: Bool
    /// 全站搜索词（`keywords`）；nil 或空白不带。
    public var keywords: String?

    public init(
        gender: Gender? = nil,
        region: Region? = nil,
        tag: String? = nil,
        limit: Int = 50,
        offset: Int = 0,
        newCams: Bool = false,
        keywords: String? = nil
    ) {
        self.gender = gender
        self.region = region
        self.tag = tag
        self.limit = limit
        self.offset = offset
        self.newCams = newCams
        self.keywords = keywords
    }

    public func url() -> URL {
        var items = [
            URLQueryItem(name: "limit", value: String(min(max(limit, 1), Self.maxPageSize))),
            URLQueryItem(name: "offset", value: String(max(offset, 0)))
        ]
        if let gender {
            items.append(URLQueryItem(name: "genders", value: gender.rawValue))
        }
        if let region {
            items.append(URLQueryItem(name: "regions", value: region.siteCode))
        }
        if let tag = Self.normalizedHashtag(tag) {
            items.append(URLQueryItem(name: "hashtags", value: tag))
        }
        if newCams {
            items.append(URLQueryItem(name: "new_cams", value: "true"))
        }
        if let keywords = SiteSearch.normalized(keywords) {
            items.append(URLQueryItem(name: "keywords", value: keywords))
        }
        var components = URLComponents()
        components.scheme = "https"
        components.host = "chaturbate.com"
        components.path = "/api/ts/roomlist/room-list/"
        components.queryItems = items
        return components.url!
    }

    /// 站点的 hashtags 参数不带 # 且只收单个标签：去掉前导 #，取第一个词。
    static func normalizedHashtag(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines).drop { $0 == "#" }
        let word = trimmed.split(whereSeparator: { $0.isWhitespace || $0 == "," }).first.map(String.init) ?? ""
        return word.isEmpty ? nil : word.lowercased()
    }
}

public struct CatalogPage: Equatable, Sendable {
    public var rooms: [Room]
    public var total: Int
    public var nextOffset: Int

    public init(rooms: [Room], total: Int, nextOffset: Int) {
        self.rooms = rooms
        self.total = total
        self.nextOffset = nextOffset
    }
}

public enum CatalogFailure: Error, Equatable {
    /// 正文命中站点质询页，需要用户在确认页通关。
    case challenge
    /// 非 2xx（含正文不是质询页的 403 / 503）：普通失败，不弹确认页。
    case status(Int)
    case malformed

    /// 是否需要弹确认页；只有 `.challenge` 为 true。
    public var isChallenge: Bool { self == .challenge }
}

public enum RoomListDecoder {
    public static func decode(
        _ data: Data, requestLimit: Int, requestOffset: Int, now: Date = Date()
    ) throws -> CatalogPage {
        if looksLikeChallenge(data) { throw CatalogFailure.challenge }
        let payload: RoomListPayload
        do {
            payload = try JSONDecoder().decode(RoomListPayload.self, from: data)
        } catch {
            throw CatalogFailure.malformed
        }
        let rooms = payload.rooms.map { $0.room(now: now) }
        let total = payload.totalCount ?? rooms.count
        // 站点偶尔多返回一条：下一页起点按请求的页大小算，重叠的一条由去重吸收。
        let step = min(rooms.count, min(max(requestLimit, 1), CatalogQuery.maxPageSize))
        return CatalogPage(rooms: rooms, total: total, nextOffset: requestOffset + step)
    }
}

public struct CatalogAPI: Sendable {
    public var client: any HTTPClient
    /// 非 JSON 退避记录；nil 表示不退避。
    public var backoff: EndpointBackoffBox?

    public init(client: any HTTPClient, backoff: EndpointBackoffBox? = .shared) {
        self.client = client
        self.backoff = backoff
    }

    /// 退避窗口内不发请求，抛 `EndpointBackedOff`；正文不是 JSON 也不是质询页时登记退避，再照旧按状态码或格式失败。
    public func fetch(_ query: CatalogQuery) async throws -> CatalogPage {
        if let backoff, !backoff.allows(.catalog) { throw EndpointBackedOff(.catalog) }
        var request = URLRequest(url: query.url())
        request.httpMethod = "GET"
        HTTPHeaders.applyBrowser(&request, referer: "https://chaturbate.com/")
        let result = try await client.send(request)
        // 只有正文像质询页才算质询；其余 403 / 503 按状态码失败。
        if looksLikeChallenge(result.data) {
            throw CatalogFailure.challenge
        }
        backoff?.note(.catalog, status: result.status, data: result.data)
        guard (200..<300).contains(result.status) else {
            throw CatalogFailure.status(result.status)
        }
        return try RoomListDecoder.decode(result.data, requestLimit: query.limit, requestOffset: query.offset)
    }

    /// 连续取最多 `pages` 页并合并（每页不超过接口上限）：遇到短页或到底即停，去重后返回。
    /// 任何一页失败都整体失败，不返回残缺候选池。冷启动不走这里等满页，只取首页后在后台续页。
    public func fetchPages(_ query: CatalogQuery, pages: Int) async throws -> CatalogPage {
        try await CatalogWindow.collect(query, pages: pages, fetch: fetch)
    }
}

/// 还要不要拉目录的下一页。与 `CatalogAPI.fetchPages` 的循环条件相同：
/// 没取满 `pages`、上一页是满页、游标没到底。页大小按接口上限截断。
public enum CatalogWindow {
    /// 连续取页的循环（`CatalogAPI.fetchPages` 与 `SiteDirectory.fetchPages` 共用）：遇到短页、空页或到底即停，去重合并。
    /// 本地过滤顺延扫描触顶时，过滤后房间数可能小于页大小甚至为空，只要游标仍按整页推进且未到底，仍继续取下一页（上限仍受 `pages` 约束）。
    public static func collect(
        _ query: CatalogQuery, pages: Int, fetch: (CatalogQuery) async throws -> CatalogPage
    ) async throws -> CatalogPage {
        let size = min(max(query.limit, 1), CatalogQuery.maxPageSize)
        var next = query
        var lastOffset = max(next.offset, 0)
        var last = try await fetch(next)
        var merged = last
        var fetched = 1
        while shouldContinue(
            fetched: fetched, pages: pages, lastCount: last.rooms.count,
            pageSize: size, nextOffset: last.nextOffset, total: last.total,
            advanced: last.nextOffset - lastOffset
        ) {
            next.offset = last.nextOffset
            lastOffset = next.offset
            last = try await fetch(next)
            let advanced = last.nextOffset - lastOffset
            if last.rooms.isEmpty {
                if advanced > 0 { merged = PageMerge.appended(merged, last) }
                if advanced <= 0 || last.nextOffset >= last.total { break }
                fetched += 1
                continue
            }
            merged = PageMerge.appended(merged, last)
            fetched += 1
        }
        return merged
    }

    public static func shouldContinue(
        fetched: Int, pages: Int, lastCount: Int, pageSize: Int, nextOffset: Int, total: Int,
        advanced: Int? = nil
    ) -> Bool {
        let size = min(max(pageSize, 1), CatalogQuery.maxPageSize)
        if let advanced, advanced <= 0 { return false }
        let progress = max(lastCount, advanced ?? 0)
        return fetched < pages && progress >= size && nextOffset < total
    }
}

/// 首页先入列表，后页再并入的纯合并。
public struct PageMergeResult: Equatable, Sendable {
    public var rooms: [Room]
    public var added: Int

    public init(rooms: [Room], added: Int) {
        self.rooms = rooms
        self.added = added
    }
}

public enum PageMerge {
    /// 后页并入已展示的目录。`current` 还在列表里时，它和它之前的房间保持原位与原顺序；
    /// 新房间按用户名去重（不分大小写）后，跟当前项之后的房间一起交给 `order`。
    /// 没有新增时原样返回，不调用 `order`（避免把已经在播的列表重排一遍）。
    public static func keepingCurrent(
        existing: [Room], incoming: [Room], current: String?, order: ([Room]) -> [Room]
    ) -> PageMergeResult {
        var seen = Set(existing.map(\.identity))
        let fresh = incoming.filter { seen.insert($0.identity).inserted }
        guard !fresh.isEmpty else { return PageMergeResult(rooms: existing, added: 0) }
        let combined = existing + fresh
        guard let current, let at = combined.firstIndex(where: { $0.ref == current }) else {
            return PageMergeResult(rooms: order(combined), added: fresh.count)
        }
        let head = Array(combined[...at])
        let tail = Array(combined[(at + 1)...])
        return PageMergeResult(rooms: head + order(tail), added: fresh.count)
    }

    /// 顺序翻页的目录合并：去重、总量取较大值、游标用后一页的。
    public static func appended(_ base: CatalogPage, _ next: CatalogPage) -> CatalogPage {
        CatalogPage(
            rooms: dedupeRooms(base.rooms + next.rooms),
            total: max(base.total, next.total),
            nextOffset: next.nextOffset
        )
    }
}

struct RoomListPayload: Decodable {
    var rooms: [RoomListRoom]
    var totalCount: Int?

    enum CodingKeys: String, CodingKey {
        case rooms
        case totalCount = "total_count"
    }
}

struct RoomListRoom: Decodable {
    var gender: String?
    var location: String?
    var currentShow: String?
    var username: String
    var roomSubject: String?
    var tags: [String]?
    var numUsers: Int?
    var numFollowers: Int?
    var hasPassword: Bool?
    var displayAge: FlexInt?
    var startTimestamp: FlexInt?
    var img: String?

    enum CodingKeys: String, CodingKey {
        case gender, location, username, tags, img
        case currentShow = "current_show"
        case roomSubject = "room_subject"
        case numUsers = "num_users"
        case numFollowers = "num_followers"
        case hasPassword = "has_password"
        case displayAge = "display_age"
        case startTimestamp = "start_timestamp"
    }

    func room(now: Date) -> Room {
        var show = ShowState(apiValue: currentShow)
        if hasPassword == true, show == .publicShow { show = .password }
        return Room(
            username: username,
            subject: roomSubject ?? "",
            viewers: numUsers ?? 0,
            followers: numFollowers ?? 0,
            gender: Gender(apiValue: gender),
            tags: tags ?? [],
            show: show,
            location: location ?? "",
            thumbnailURL: secureImageURL(img),
            age: displayAge?.value,
            secondsOnline: Self.secondsOnline(startTimestamp: startTimestamp?.value, now: now)
        )
    }

    /// 开播时刻（秒级 Unix 时间）换算成已开播秒数；缺失、非正或在未来的按缺失处理。
    static func secondsOnline(startTimestamp: Int?, now: Date) -> Int? {
        guard let startTimestamp, startTimestamp > 0 else { return nil }
        let seconds = Int(now.timeIntervalSince1970) - startTimestamp
        return seconds >= 0 ? seconds : nil
    }
}

/// 正文像站点质询页：Cloudflare 的 `Just a moment` 页，或站点的年龄确认页（`Verify your age`）。
/// 两者都要在侧车网页里由用户确认一次，所以归为同一种质询，走确认页流程。
/// JSON 正文从不算质询（房间标题里可能恰好有这些字）。
func looksLikeChallenge(_ data: Data) -> Bool {
    if ResponseShape.looksLikeJSON(data) { return false }
    let text = String(decoding: data.prefix(4096), as: UTF8.self).lowercased()
    if text.contains("just a moment") { return true }
    if text.contains("cf-mitigated") || text.contains("challenges.cloudflare.com") { return true }
    if text.contains("verify your age") { return true }
    return false
}
