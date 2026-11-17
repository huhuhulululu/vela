import Foundation

/// 带时间戳的偏好权重，读取时按半衰期衰减。
public struct Affinity: Codable, Equatable, Sendable {
    public var value: Double
    public var at: TimeInterval
}

/// 旧版本机偏好（标签 / 性别权重与最近看过）。只剩迁移用途：读取旧文件，交给 `Taste(migrating:)`；不再写入。
public struct Preferences: Codable, Equatable, Sendable {
    public static let halfLife: TimeInterval = 3 * 86_400
    public static let maxWeights = 500
    public static let maxSeen = 1_000
    public static let seenRetention: TimeInterval = 24 * 3_600
    public static let likeAfter: TimeInterval = 20
    public static let skipBelow: TimeInterval = 3
    public static let relatedAfter: TimeInterval = 30
    static let maxTagsPerRoom = 8
    /// 单个标签 / 用户名的最长字符数；超出的截断（写入时）或丢弃（读取已有文件时）。
    public static let maxNameLength = 64
    /// 偏好文件读取上限：超过就当作空偏好，不解析。
    public static let maxFileBytes = 1_000_000

    public private(set) var weights: [String: Affinity]
    public private(set) var seen: [String: TimeInterval]

    public init(weights: [String: Affinity] = [:], seen: [String: TimeInterval] = [:]) {
        self.weights = weights
        self.seen = seen
    }

    public static func tagKey(_ tag: String) -> String { "t:" + tag.lowercased() }
    public static func genderKey(_ gender: Gender) -> String { "g:" + gender.rawValue }

    /// 超出上限时保留最强的权重与最新的看过记录，过期的看过记录一并丢弃。
    public func capped(now: Date) -> Preferences {
        let keyLimit = Self.maxNameLength + 2
        return Preferences(
            weights: Self.trimmed(weights.filter { $0.key.count <= keyLimit }, now: now),
            seen: Self.trimmed(seen.filter { $0.key.count <= Self.maxNameLength }, now: now)
        )
    }

    private static func trimmed(_ weights: [String: Affinity], now: Date) -> [String: Affinity] {
        guard weights.count > maxWeights else { return weights }
        func strength(_ entry: Affinity) -> Double {
            abs(entry.value) * pow(0.5, max(0, now.timeIntervalSince1970 - entry.at) / halfLife)
        }
        let kept = weights.sorted { strength($0.value) > strength($1.value) }.prefix(maxWeights)
        return Dictionary(uniqueKeysWithValues: kept.map { ($0.key, $0.value) })
    }

    private static func trimmed(_ seen: [String: TimeInterval], now: Date) -> [String: TimeInterval] {
        let cutoff = now.timeIntervalSince1970 - seenRetention
        var live = seen.filter { $0.value >= cutoff }
        if live.count > maxSeen {
            let kept = live.sorted { $0.value > $1.value }.prefix(maxSeen)
            live = Dictionary(uniqueKeysWithValues: kept.map { ($0.key, $0.value) })
        }
        return live
    }
}

/// 读旧版偏好文件（只读，迁移用）。读失败退回空偏好。
public final class PreferenceStore: @unchecked Sendable {
    public let url: URL
    private let lock = NSLock()

    public init(url: URL) { self.url = url }

    public static func defaultURL() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("Vela", isDirectory: true).appendingPathComponent("feed-prefs.json")
    }

    public func load(now: Date = Date()) -> Preferences {
        lock.lock()
        defer { lock.unlock() }
        guard Self.fitsLimit(url, Preferences.maxFileBytes),
              let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode(Preferences.self, from: data) else {
            return Preferences()
        }
        return decoded.capped(now: now)
    }

    /// 文件不存在或大小在上限内才返回 true（不存在时由后面的读取失败退回空值）。
    static func fitsLimit(_ url: URL, _ limit: Int) -> Bool {
        guard let size = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber else { return true }
        return size.intValue <= limit
    }
}
