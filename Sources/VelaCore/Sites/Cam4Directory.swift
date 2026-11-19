import Foundation

/// Cam4 访客目录。不登录、不带推广参数。缩略图用 `snapshotImageLink`，不用 `defaultImageLink`。
public enum Cam4Directory {
    public static let maxPageSize = 90
    /// 截图没有 `validto` 时的固定有效期，与目录索引的新鲜窗口一致。
    public static let snapshotTTL: TimeInterval = 10 * 60

    /// `page` 从 1 开始。`resultsPerPage` 夹到 1...90。
    public static func url(page: Int, resultsPerPage: Int) -> URL {
        let size = min(max(resultsPerPage, 1), maxPageSize)
        let index = max(page, 1)
        var components = URLComponents()
        components.scheme = "https"
        components.host = "www.cam4.com"
        components.path = "/directoryCams"
        components.queryItems = [
            URLQueryItem(name: "directoryJson", value: "true"),
            URLQueryItem(name: "online", value: "true"),
            URLQueryItem(name: "url", value: "true"),
            URLQueryItem(name: "page", value: String(index)),
            URLQueryItem(name: "resultsPerPage", value: String(size))
        ]
        return components.url!
    }

    /// `page = offset / k + 1`，`k` 为夹紧后的页大小。
    public static func url(offset: Int, limit: Int) -> URL {
        let size = min(max(limit, 1), maxPageSize)
        let page = max(offset, 0) / size + 1
        return url(page: page, resultsPerPage: size)
    }

    public static func decode(
        _ data: Data,
        requestLimit: Int,
        requestOffset: Int,
        now: Date,
        viewerRegions: Set<String>
    ) throws -> CatalogPage {
        guard ResponseShape.looksLikeJSON(data) else { throw CatalogFailure.malformed }
        let parsed = try parse(data)
        let limit = min(max(requestLimit, 1), maxPageSize)
        let rooms = parsed.users.compactMap { $0.room(now: now, viewerRegions: viewerRegions) }
        let total = parsed.total ?? rooms.count
        let step = min(rooms.count, limit)
        return CatalogPage(rooms: rooms, total: total, nextOffset: requestOffset + step)
    }

    private struct Parsed {
        var users: [Cam4User]
        var total: Int?
    }

    private static func parse(_ data: Data) throws -> Parsed {
        let decoder = JSONDecoder()
        if let object = try? decoder.decode(Cam4Object.self, from: data), object.list != nil || object.total != nil {
            return Parsed(users: object.list ?? [], total: object.total)
        }
        if let list = try? decoder.decode([Cam4User].self, from: data) {
            return Parsed(users: list, total: list.count)
        }
        throw CatalogFailure.malformed
    }
}

/// 逗号分隔字符串或字符串数组。解析结果是大写 alpha-2。
public struct Cam4RegionField: Decodable, Equatable, Sendable {
    public var parts: [String]

    public init(parts: [String]) { self.parts = parts }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            parts = []
        } else if let list = try? container.decode([String].self) {
            parts = list
        } else if let text = try? container.decode(String.self) {
            parts = [text]
        } else if let numbers = try? container.decode([Int].self) {
            parts = numbers.map(String.init)
        } else {
            parts = []
        }
    }
}

public enum Cam4Regions {
    /// 接受逗号分隔字符串或字符串数组。数字码（`"643"`、`"4"`、`"004"`）映射成 alpha-2；
    /// 本身已是两位字母的也收下。空白丢掉，认不出的码丢掉。
    public static func parse(_ raw: Cam4RegionField?) -> Set<String> {
        guard let raw else { return [] }
        var out = Set<String>()
        for part in raw.parts {
            for piece in part.split(separator: ",") {
                if let alpha = canonical(String(piece)) { out.insert(alpha) }
            }
        }
        return out
    }

    /// 观看地区为空不过滤。两边都先归一成 alpha-2 再看交集（设备侧是 alpha-2，房间侧可能还是数字码）。
    public static func isBlocked(_ blocked: Set<String>, viewer: Set<String>) -> Bool {
        guard !viewer.isEmpty, !blocked.isEmpty else { return false }
        let left = Set(blocked.compactMap(canonical))
        let right = Set(viewer.compactMap(canonical))
        return !left.isDisjoint(with: right)
    }

    static func canonical(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if trimmed.count == 2, trimmed.unicodeScalars.allSatisfy(CharacterSet.letters.contains) {
            let upper = trimmed.uppercased()
            return ISO3166.alpha2ToNumeric[upper] == nil ? nil : upper
        }
        return ISO3166.alpha2(numeric: trimmed)
    }
}

struct Cam4Object: Decodable {
    var users: [Cam4User]?
    var rooms: [Cam4User]?
    var totalCount: Int?
    var userCount: Int?

    var list: [Cam4User]? { users ?? rooms }
    var total: Int? { totalCount ?? userCount }
}

struct Cam4User: Decodable {
    var username: String?
    var gender: String?
    var age: FlexInt?
    var countryCode: String?
    var privateRoom: Bool?
    var hlsPreviewUrl: String?
    var snapshotImageLink: String?
    var profileImageLink: String?
    var defaultImageLink: String?
    var blockedRegions: Cam4RegionField?
    var viewers: FlexInt?
    var showTags: [String]?
    var broadcastType: String?
    var languages: [String]?

    func room(now: Date, viewerRegions: Set<String>) -> Room? {
        guard let rawName = username?.trimmingCharacters(in: .whitespacesAndNewlines),
              let key = RoomKey(site: .cam4, name: rawName) else { return nil }
        let blocked = Cam4Regions.parse(blockedRegions)
        let geo = Cam4Regions.isBlocked(blocked, viewer: viewerRegions)
        let isPrivate = privateRoom == true
        let stream = geo || isPrivate ? nil : Cam4Links.https(hlsPreviewUrl)
        let snapshot = Cam4Links.https(snapshotImageLink)
        var show = ShowState.publicShow
        if isPrivate && !geo { show = .privateShow }
        let expires = Cam4Links.expiry(profile: profileImageLink, hasSnapshot: snapshot != nil, now: now)
        _ = (defaultImageLink, broadcastType, languages)
        return Room(
            username: key.name,
            displayName: key.name,
            viewers: viewers?.value ?? 0,
            gender: Cam4Links.gender(gender),
            tags: showTags ?? [],
            show: show,
            location: Cam4Links.location(countryCode),
            thumbnailURL: snapshot,
            age: age?.value,
            site: .cam4,
            stream: stream,
            thumbnailExpires: expires,
            blockedRegions: blocked
        )
    }
}

enum Cam4Links {
    static func https(_ raw: String?) -> URL? {
        guard var raw, !raw.isEmpty else { return nil }
        raw = raw.replacingOccurrences(of: "\\/", with: "/")
        guard let components = URLComponents(string: raw), components.scheme == "https" else { return nil }
        return components.url
    }

    static func gender(_ raw: String?) -> Gender? {
        switch raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "female": return .female
        case "male": return .male
        case "couple": return .couple
        case "shemale", "trans", "transgender": return .trans
        default: return nil
        }
    }

    static func location(_ raw: String?) -> String {
        let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard trimmed.count == 2, trimmed.unicodeScalars.allSatisfy(CharacterSet.letters.contains) else { return "" }
        return trimmed.uppercased()
    }

    /// `profileImageLink` 的 `validto` 是秒（10 位左右）。没有则用截图的固定 TTL。
    static func expiry(profile: String?, hasSnapshot: Bool, now: Date) -> Date? {
        if let date = validto(profile) { return date }
        return hasSnapshot ? now.addingTimeInterval(Cam4Directory.snapshotTTL) : nil
    }

    static func validto(_ raw: String?) -> Date? {
        guard var raw, !raw.isEmpty else { return nil }
        raw = raw.replacingOccurrences(of: "\\/", with: "/")
        guard let components = URLComponents(string: raw),
              let value = components.queryItems?.first(where: { $0.name.lowercased() == "validto" })?.value,
              let seconds = TimeInterval(value) else { return nil }
        // 只认秒。毫秒（>1e11）不是这个字段的单位，交给截图 TTL。
        guard seconds >= 1_000_000_000, seconds < 100_000_000_000 else { return nil }
        return Date(timeIntervalSince1970: seconds)
    }
}
