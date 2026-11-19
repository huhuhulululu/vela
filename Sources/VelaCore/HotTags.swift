import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// 全站热门标签的一项：标签名（小写、不带 #）与站点给出的房间数。
public struct HotTag: Equatable, Sendable {
    public var name: String
    public var rooms: Int

    @_optimize(size)
    public init(name: String, rooms: Int) {
        self.name = name
        self.rooms = rooms
    }
}

/// `tag-table-data` 接口：全站热门标签（按房间数降序）。
/// 元素字段按 `hashtag` / `room_count` 解码，未在真机核对；没有 `hashtag` 的项跳过，数量缺失按 0。
public enum HotTags {
    public static let limit = 20

    @_optimize(size)
    public static func url(limit: Int = limit) -> URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "chaturbate.com"
        components.path = "/api/ts/hashtags/tag-table-data/"
        components.queryItems = [
            URLQueryItem(name: "sort", value: "-rc"),
            URLQueryItem(name: "page", value: "1"),
            URLQueryItem(name: "g", value: ""),
            URLQueryItem(name: "limit", value: String(max(1, limit)))
        ]
        return components.url!
    }

    @_optimize(size)
    public static func request() -> URLRequest {
        var request = URLRequest(url: url())
        request.httpMethod = "GET"
        HTTPHeaders.applyBrowser(&request, referer: "https://chaturbate.com/tags/")
        return request
    }

    @_optimize(size)
    public static func decode(status: Int, data: Data) throws -> [HotTag] {
        if status == 403 || status == 503 || looksLikeChallenge(data) { throw CatalogFailure.challenge }
        guard (200..<300).contains(status) else { throw CatalogFailure.status(status) }
        let payload: Payload
        do {
            payload = try JSONDecoder().decode(Payload.self, from: data)
        } catch {
            throw CatalogFailure.malformed
        }
        var seen = Set<String>()
        return payload.hashtags.compactMap { item in
            guard let name = normalized(item.hashtag), seen.insert(name).inserted else { return nil }
            return HotTag(name: name, rooms: max(0, item.roomCount?.value ?? 0))
        }
    }

    /// 去掉 #、首尾空白，转小写；空的或超过名字长度上限的丢弃。
    @_optimize(size)
    static func normalized(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let name = String(raw.trimmingCharacters(in: .whitespacesAndNewlines).drop { $0 == "#" }).lowercased()
        guard !name.isEmpty, name.count <= Preferences.maxNameLength else { return nil }
        return name
    }

    /// 筛选面板的标签提示：有全站热门标签用它，否则退到当前在线房间里最常见的标签。
    @_optimize(size)
    public static func filterHints(hot: [HotTag]?, rooms: [Room], limit: Int = 8) -> [String] {
        if let hot, !hot.isEmpty { return Array(hot.prefix(limit).map(\.name)) }
        return Taste().suggestedTags(from: rooms, limit: limit)
    }

    private struct Payload: Decodable {
        var hashtags: [Item]
    }

    private struct Item: Decodable {
        var hashtag: String?
        var roomCount: FlexInt?

        enum CodingKeys: String, CodingKey {
            case hashtag
            case roomCount = "room_count"
        }
    }
}

/// 热门标签缓存：成功的结果用 6 小时；失败后 60 秒内不再自动重试（期间回退到在线房间的常见标签）。不落盘。
public struct HotTagCache: Equatable, Sendable {
    public static let ttl: TimeInterval = 6 * 3_600
    public static let retryAfter: TimeInterval = 60

    private var tags: [HotTag] = []
    private var fetchedAt: Date?
    private var failedAt: Date?

    @_optimize(size)
    public init() {}

    /// 6 小时内取回的热门标签；没有或已过期返回 nil（调用方回退）。
    @_optimize(size)
    public func fresh(now: Date) -> [HotTag]? {
        guard let fetchedAt, now.timeIntervalSince(fetchedAt) <= Self.ttl, !tags.isEmpty else { return nil }
        return tags
    }

    /// 没有新鲜结果，且不在失败冷却期内，才需要发请求。
    @_optimize(size)
    public func needsFetch(now: Date) -> Bool {
        guard fresh(now: now) == nil else { return false }
        guard let failedAt else { return true }
        return now.timeIntervalSince(failedAt) >= Self.retryAfter
    }

    /// 空列表按失败处理（不覆盖已有的结果）。
    @_optimize(size)
    public func storing(_ tags: [HotTag], at date: Date) -> HotTagCache {
        guard !tags.isEmpty else { return recordingFailure(at: date) }
        var next = self
        next.tags = tags
        next.fetchedAt = date
        next.failedAt = nil
        return next
    }

    @_optimize(size)
    public func recordingFailure(at date: Date) -> HotTagCache {
        var next = self
        next.failedAt = date
        return next
    }
}

extension Taste {
    /// 冷启动整理的候选：有全站热门标签时取其中画像里还没有的；没有或全都已在画像里时，
    /// 退到当前在线房间里最常见、画像里还没有的标签。
    @_optimize(size)
    public func suggestedTags(hot: [HotTag]?, fallback rooms: [Room], limit: Int = 12) -> [String] {
        let fromHot = (hot ?? []).map(\.name).filter { beliefs[Self.tagKey($0)] == nil }
        if !fromHot.isEmpty { return Array(fromHot.prefix(limit)) }
        return suggestedTags(from: rooms, limit: limit)
    }
}
