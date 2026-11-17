import Foundation

public enum Gender: String, Codable, CaseIterable, Equatable, Sendable {
    case female = "f"
    case male = "m"
    case trans = "t"
    case couple = "c"

    public init?(apiValue: String?) {
        switch apiValue?.lowercased() {
        case "f": self = .female
        case "m": self = .male
        case "t", "s": self = .trans
        case "c": self = .couple
        default: return nil
        }
    }
}

public enum Region: String, CaseIterable, Codable, Equatable, Sendable {
    case asia
    case europeRussia = "europe_russia"
    case northAmerica = "northamerica"
    case southAmerica = "southamerica"
    case other

    /// 站点列表接口的 regions 参数值（实测：AS / ER / NA / SA / O；单值，逗号拼多个会 400）。
    public var siteCode: String {
        switch self {
        case .asia: return "AS"
        case .europeRussia: return "ER"
        case .northAmerica: return "NA"
        case .southAmerica: return "SA"
        case .other: return "O"
        }
    }
}

public enum ShowState: Equatable, Sendable {
    case publicShow
    case privateShow
    case group
    case away
    case offline
    case hidden
    case password
    case unknown

    public init(apiValue: String?) {
        switch apiValue?.lowercased() {
        case "public": self = .publicShow
        case "private": self = .privateShow
        case "group": self = .group
        case "away": self = .away
        case "offline": self = .offline
        case "hidden": self = .hidden
        case "password", "password protected": self = .password
        default: self = .unknown
        }
    }

    public var isPlayable: Bool { self == .publicShow }
}

public struct Room: Identifiable, Equatable, Sendable {
    public var id: String { username }
    public var username: String
    public var displayName: String
    public var subject: String
    public var viewers: Int
    public var followers: Int
    public var gender: Gender?
    public var tags: [String]
    public var isHD: Bool
    public var show: ShowState
    public var location: String
    public var thumbnailURL: URL?
    public var age: Int?
    public var secondsOnline: Int?

    public init(
        username: String,
        displayName: String? = nil,
        subject: String = "",
        viewers: Int = 0,
        followers: Int = 0,
        gender: Gender? = nil,
        tags: [String] = [],
        isHD: Bool = false,
        show: ShowState = .unknown,
        location: String = "",
        thumbnailURL: URL? = nil,
        age: Int? = nil,
        secondsOnline: Int? = nil
    ) {
        self.username = username
        let trimmedName = displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        self.displayName = trimmedName.isEmpty ? username : trimmedName
        self.subject = subject
        self.viewers = viewers
        self.followers = followers
        self.gender = gender
        self.tags = tags
        self.isHD = isHD
        self.show = show
        self.location = location
        self.thumbnailURL = thumbnailURL
        self.age = Self.displayableAge(age)
        self.secondsOnline = secondsOnline
    }

    public static func displayableAge(_ age: Int?) -> Int? {
        guard let age, (18...80).contains(age) else { return nil }
        return age
    }

    /// 用户名格式：2 到 30 位，只含字母、数字、下划线（已去掉首尾空白）。
    public static func isValidUsername(_ name: String) -> Bool {
        guard (2...30).contains(name.count) else { return false }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_")
        return name.unicodeScalars.allSatisfy { allowed.contains($0) }
    }

    public static func direct(username raw: String) -> Room? {
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isValidUsername(name) else { return nil }
        return Room(
            username: name,
            displayName: name,
            subject: "",
            show: .publicShow,
            thumbnailURL: URL(string: "https://thumb.live.mmcdn.com/riw/\(name).jpg")
        )
    }
}

public enum ViewerCount {
    public static func string(_ count: Int) -> String {
        let n = max(count, 0)
        if n >= 10_000 {
            let value = Double(n) / 10_000
            if abs(value.rounded() - value) < 0.05 || value >= 10 {
                return "\(Int(value.rounded()))万"
            }
            return String(format: "%.1f万", value)
        }
        return "\(n)"
    }
}

public enum BroadcastClock {
    public static func string(seconds: Int?) -> String? {
        guard let seconds, seconds >= 60 else { return nil }
        let hours = seconds / 3600
        let minutes = (seconds % 3600) / 60
        if hours > 0 {
            return "已开播 \(hours) 小时 \(minutes) 分"
        }
        return "已开播 \(minutes) 分钟"
    }
}

public struct RoomQuery: Equatable, Sendable {
    public var text: String
    public var publicOnly: Bool
    /// 已屏蔽的用户名（小写）。在所有来源里一律不出现。
    public var blocked: Set<String>
    /// 只允许这些用户名（小写）出现；nil 表示不限。收藏来源用它把列表限定在当前收藏名单内。
    public var only: Set<String>?
    /// 「不想看」的口味键（标签键 / 性别键）：带其中任一键的房间不显示，但仍留在目录里，恢复后重新出现。
    public var hidden: Set<String>

    public init(
        text: String = "", publicOnly: Bool = true, blocked: Set<String> = [], only: Set<String>? = nil,
        hidden: Set<String> = []
    ) {
        self.text = text
        self.publicOnly = publicOnly
        self.blocked = blocked
        self.only = only
        self.hidden = hidden
    }

    static func isHidden(_ room: Room, by keys: Set<String>) -> Bool {
        room.tags.prefix(Preferences.maxTagsPerRoom).contains { keys.contains(Taste.tagKey($0)) }
            || (room.gender.map { keys.contains(Taste.genderKey($0)) } ?? false)
    }

    public func matches(_ room: Room) -> Bool {
        if blocked.contains(room.username.lowercased()) { return false }
        if let only, !only.contains(room.username.lowercased()) { return false }
        if publicOnly && room.show != .publicShow { return false }
        if !hidden.isEmpty, Self.isHidden(room, by: hidden) { return false }
        let needle = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if needle.isEmpty { return true }
        if room.username.lowercased().contains(needle) { return true }
        if room.displayName.lowercased().contains(needle) { return true }
        if room.subject.lowercased().contains(needle) { return true }
        return room.tags.contains { $0.lowercased().contains(needle) }
    }
}

func secureImageURL(_ raw: String?) -> URL? {
    guard var raw, !raw.isEmpty else { return nil }
    raw = raw.replacingOccurrences(of: "\\/", with: "/")
    guard var components = URLComponents(string: raw) else { return nil }
    if components.scheme == "http" {
        components.scheme = "https"
    }
    guard components.scheme == "https" else { return nil }
    return components.url
}

struct FlexInt: Decodable {
    var value: Int?

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            value = nil
            return
        }
        if let int = try? container.decode(Int.self) {
            value = int
            return
        }
        if let double = try? container.decode(Double.self) {
            value = Int(double)
            return
        }
        if let string = try? container.decode(String.self) {
            value = Int(string)
            return
        }
        value = nil
    }
}

func dedupeRooms(_ rooms: [Room]) -> [Room] {
    var seen = Set<String>()
    return rooms.filter { seen.insert($0.username.lowercased()).inserted }
}
