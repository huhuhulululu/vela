import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// 一位主播的上次开播时间；`at` 为 nil 表示接口没给出（查过，但不知道）。
public struct LastBroadcast: Equatable, Sendable {
    public var at: Date?

    public init(at: Date?) {
        self.at = at
    }
}

/// `biocontext` 接口：按用户名取主播资料，只用其中的 `last_broadcast`（缺失时退到 `time_since_last_broadcast`）。
/// 字段格式未在真机核对：`last_broadcast` 接受 ISO 8601 字符串、`yyyy-MM-dd HH:mm:ss`（按 UTC）或秒级 / 毫秒级 Unix 时间；
/// `time_since_last_broadcast` 只接受秒数。
public enum BioContext {
    public static func url(username: String) -> URL {
        let slug = username.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? username
        return URL(string: "https://chaturbate.com/api/biocontext/\(slug)/")!
    }

    public static func request(username: String) -> URLRequest {
        var request = URLRequest(url: url(username: username))
        request.httpMethod = "GET"
        HTTPHeaders.applyBrowser(&request, referer: "https://chaturbate.com/\(username)/")
        request.timeoutInterval = 8
        return request
    }

    /// 200 解析时间；404、401 与 403 带 JSON 说明算「没有可用时间」；站点质询抛 `challenge`。
    public static func decode(status: Int, data: Data, now: Date) throws -> LastBroadcast {
        if looksLikeChallenge(data) { throw CatalogFailure.challenge }
        let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        switch status {
        case 200:
            guard let object else { throw CatalogFailure.malformed }
            return LastBroadcast(at: lastBroadcast(in: object, now: now))
        case 401, 404:
            return LastBroadcast(at: nil)
        case 403:
            if object?["detail"] is String { return LastBroadcast(at: nil) }
            throw CatalogFailure.challenge
        default:
            throw CatalogFailure.status(status)
        }
    }

    static func lastBroadcast(in object: [String: Any], now: Date) -> Date? {
        if let date = parseDate(object["last_broadcast"]) { return min(date, now) }
        if let seconds = number(object["time_since_last_broadcast"]), seconds >= 0 {
            return now.addingTimeInterval(-seconds)
        }
        return nil
    }

    static func parseDate(_ value: Any?) -> Date? {
        if let seconds = number(value), seconds > 0 {
            return Date(timeIntervalSince1970: seconds > 100_000_000_000 ? seconds / 1000 : seconds)
        }
        guard let text = (value as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
        let iso = ISO8601DateFormatter()
        if let date = iso.date(from: text) { return date }
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = iso.date(from: text) { return date }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        for pattern in ["yyyy-MM-dd'T'HH:mm:ss.SSSSSSZZZZZ", "yyyy-MM-dd'T'HH:mm:ss.SSSSSS", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss"] {
            formatter.dateFormat = pattern
            if let date = formatter.date(from: text) { return date }
        }
        return nil
    }

    /// 数字或纯数字字符串。
    static func number(_ value: Any?) -> Double? {
        if let value = value as? NSNumber, !(value is Bool) { return value.doubleValue }
        if let text = value as? String, let double = Double(text.trimmingCharacters(in: .whitespaces)) { return double }
        return nil
    }
}

/// 「上次开播 3 小时前」一类中文相对时间。
public enum BroadcastAgo {
    public static func string(since date: Date, now: Date) -> String {
        let seconds = max(0, Int(now.timeIntervalSince(date)))
        let minute = 60, hour = 3_600, day = 86_400
        let phrase: String
        switch seconds {
        case ..<minute: phrase = "刚刚"
        case ..<hour: phrase = "\(seconds / minute) 分钟前"
        case ..<day: phrase = "\(seconds / hour) 小时前"
        case ..<(30 * day): phrase = "\(seconds / day) 天前"
        case ..<(365 * day): phrase = "\(seconds / (30 * day)) 个月前"
        default: phrase = "\(seconds / (365 * day)) 年前"
        }
        return "上次开播 \(phrase)"
    }
}

/// 上次开播时间的缓存：按用户名（小写）记结果与查询时间，30 分钟内不重查。不落盘。
public struct LastBroadcastCache: Equatable, Sendable {
    public static let ttl: TimeInterval = 1_800

    private struct Entry: Equatable, Sendable {
        var value: LastBroadcast
        var checkedAt: Date
    }

    private var entries: [String: Entry] = [:]

    public init() {}

    /// 有时间的条目（不论新旧），键为小写用户名：收藏夹显示用。
    public var dates: [String: Date] {
        entries.compactMapValues { $0.value.at }
    }

    /// 这些名字里还没有新鲜结果的（保持原顺序，按小写去重）。
    public func pending(_ names: [String], now: Date) -> [String] {
        var seen = Set<String>()
        return names.filter { name in
            let key = Curation.key(name)
            guard seen.insert(key).inserted else { return false }
            guard let entry = entries[key] else { return true }
            return now.timeIntervalSince(entry.checkedAt) > Self.ttl
        }
    }

    public func storing(_ values: [String: LastBroadcast], at date: Date) -> LastBroadcastCache {
        var next = self
        for (key, value) in values { next.entries[Curation.key(key)] = Entry(value: value, checkedAt: date) }
        return next
    }

    public func pruned(keeping names: [String]) -> LastBroadcastCache {
        let keep = Set(names.map(Curation.key))
        var next = self
        next.entries = entries.filter { keep.contains($0.key) }
        return next
    }
}

public struct LastBroadcastFetchResult: Equatable, Sendable {
    /// 键为小写用户名；网络失败、超时的不在里面（不写缓存）。
    public var found: [String: LastBroadcast]
    public var challenged: Bool
}

/// 逐个查上次开播时间，并发上限沿用收藏查询（4）；遇到站点拦截立刻停止发起新请求。
@available(macOS 10.15, *)
public struct LastBroadcastFetcher: Sendable {
    private enum Outcome: Sendable {
        case value(LastBroadcast)
        case failed
        case challenge
    }

    private let client: any HTTPClient
    private let concurrency: Int
    private let clock: @Sendable () -> Date
    private let backoff: EndpointBackoffBox?

    public init(
        client: any HTTPClient, concurrency: Int = FavoriteFetcher.maxConcurrent,
        now: @escaping @Sendable () -> Date = { Date() }, backoff: EndpointBackoffBox? = .shared
    ) {
        self.client = client
        self.concurrency = max(1, min(concurrency, FavoriteFetcher.maxConcurrent))
        self.clock = now
        self.backoff = backoff
    }

    public func fetch(_ names: [String]) async -> LastBroadcastFetchResult {
        var found: [String: LastBroadcast] = [:]
        var challenged = false
        var index = 0
        await withTaskGroup(of: (String, Outcome).self) { group in
            func startNext(_ group: inout TaskGroup<(String, Outcome)>) {
                guard index < names.count else { return }
                let name = names[index]
                index += 1
                group.addTask { (Curation.key(name), await self.lookup(name)) }
            }
            for _ in 0..<min(concurrency, names.count) { startNext(&group) }
            while let (key, outcome) = await group.next() {
                switch outcome {
                case .value(let value): found[key] = value
                case .challenge: challenged = true
                case .failed: break
                }
                if !challenged, !Task.isCancelled { startNext(&group) }
            }
        }
        return LastBroadcastFetchResult(found: found, challenged: challenged)
    }

    /// 退避窗口内不发请求；正文不是 JSON 也不是质询页（404、401 除外）登记退避，不写缓存。
    private func lookup(_ name: String) async -> Outcome {
        if let backoff, !backoff.allows(.bioContext) { return .failed }
        do {
            let result = try await client.send(BioContext.request(username: name))
            if backoff?.note(.bioContext, status: result.status, data: result.data, definitive: [401, 404]) == true {
                return .failed
            }
            return .value(try BioContext.decode(status: result.status, data: result.data, now: clock()))
        } catch CatalogFailure.challenge {
            return .challenge
        } catch {
            return .failed
        }
    }
}
