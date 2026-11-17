import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// 一个收藏房间此刻的状态。在线只指「公开、可播放」；私密、群组、离开、需密码都算不在线。
/// 公开但站点没给可播地址（`hls_source` 为空）算地区受限：不是离线，也不当作可播放。
public enum FavoriteStatus: Equatable, Sendable {
    case online(Room)
    case offline
    case notFound
    /// 当前地区不可观看：公开但拿不到可播地址。
    case restricted

    public var room: Room? {
        if case .online(let room) = self { return room }
        return nil
    }
}

/// `chatvideocontext` 接口：按用户名查单个房间，不需要登录。
/// 实测：存在的房间 200 + JSON（`room_status` 在线公开为 `public`）；不存在 404；
/// 已删除 403 + JSON（带 `detail`）；需密码 401；站点质询是 HTML（含 "Just a moment"）。
public enum ChatContext {
    /// 状态码本身就是答案（不存在、需密码），正文是什么都不退避。
    public static let definitiveStatuses: Set<Int> = [401, 404]

    public static func url(username: String) -> URL {
        let slug = username.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? username
        return URL(string: "https://chaturbate.com/api/chatvideocontext/\(slug)/")!
    }

    public static func request(username: String) -> URLRequest {
        var request = URLRequest(url: url(username: username))
        request.httpMethod = "GET"
        HTTPHeaders.applyBrowser(&request, referer: "https://chaturbate.com/\(username)/")
        request.timeoutInterval = 8
        return request
    }

    public static func decode(status: Int, data: Data, now: Date) throws -> FavoriteStatus {
        if looksLikeChallenge(data) { throw CatalogFailure.challenge }
        switch status {
        case 200:
            return try decodeRoom(data, now: now)
        case 404:
            return .notFound
        case 401:
            return .offline
        case 403:
            // 403 带 JSON 说明（房间已删除）是确定的答案；其他 403 当作站点拦截。
            if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any], object["detail"] is String {
                return .notFound
            }
            throw CatalogFailure.challenge
        default:
            throw CatalogFailure.status(status)
        }
    }

    private static func decodeRoom(_ data: Data, now: Date) throws -> FavoriteStatus {
        let payload: Payload
        do {
            payload = try JSONDecoder().decode(Payload.self, from: data)
        } catch {
            throw CatalogFailure.malformed
        }
        let show = ShowState(apiValue: payload.roomStatus)
        guard show.isPlayable, let name = payload.username, !name.isEmpty else { return .offline }
        // 字段在但为空：公开却没有可播地址，按地区受限处理；字段缺失时不下结论，照旧算在线。
        if let source = payload.hlsSource, source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return .restricted
        }
        var seconds: Int?
        if let start = payload.startTimestamp, start > 0 {
            seconds = max(0, Int(now.timeIntervalSince1970 - start))
        }
        return .online(Room(
            username: name,
            subject: payload.roomTitle ?? "",
            viewers: payload.viewers ?? 0,
            gender: Gender(apiValue: payload.gender.map { String($0.prefix(1)) }),
            show: show,
            thumbnailURL: URL(string: "https://thumb.live.mmcdn.com/riw/\(name).jpg"),
            age: payload.age?.value,
            secondsOnline: seconds
        ))
    }

    private struct Payload: Decodable {
        var roomStatus: String?
        var viewers: Int?
        var roomTitle: String?
        var username: String?
        var gender: String?
        var age: FlexInt?
        var startTimestamp: Double?
        var hlsSource: String?

        enum CodingKeys: String, CodingKey {
            case roomStatus = "room_status"
            case hlsSource = "hls_source"
            case viewers = "num_viewers"
            case roomTitle = "room_title"
            case username = "broadcaster_username"
            case gender = "broadcaster_gender"
            case age
            case startTimestamp = "start_timestamp"
        }
    }
}

/// 状态缓存：按用户名（小写）记结果与查询时间，超过 `ttl` 视为过期。纯值类型。
public struct FavoriteCache: Equatable, Sendable {
    public static let ttl: TimeInterval = 180

    private struct Entry: Equatable, Sendable {
        var status: FavoriteStatus
        var checkedAt: Date
    }

    private var entries: [String: Entry] = [:]

    public init() {}

    /// 不论新旧：界面显示用。
    public func status(for name: String) -> FavoriteStatus? { entries[Curation.key(name)]?.status }

    public func fresh(for name: String, now: Date) -> FavoriteStatus? {
        guard let entry = entries[Curation.key(name)], now.timeIntervalSince(entry.checkedAt) <= Self.ttl else { return nil }
        return entry.status
    }

    public func storing(_ statuses: [String: FavoriteStatus], at date: Date) -> FavoriteCache {
        var next = self
        for (key, status) in statuses { next.entries[Curation.key(key)] = Entry(status: status, checkedAt: date) }
        return next
    }

    /// 写入带各自时间的结果（来自目录的）。
    public func storing(stamped: [String: FavoritePlan.Stamped]) -> FavoriteCache {
        var next = self
        for (key, item) in stamped {
            let k = Curation.key(key)
            if let existing = next.entries[k], existing.checkedAt > item.at { continue }
            next.entries[k] = Entry(status: item.status, checkedAt: item.at)
        }
        return next
    }

    /// 只留仍在收藏名单里的条目，免得缓存无限增长。
    public func pruned(keeping names: [String]) -> FavoriteCache {
        let keep = Set(names.map(Curation.key))
        var next = self
        next.entries = entries.filter { keep.contains($0.key) }
        return next
    }
}

/// 最近一次从推荐、热门、新开播取回的房间，供收藏来源比对。每个房间记着自己被取回的时间，
/// 追加页不会续期早先页面里房间的在线状态；超过缓存时效的房间不再采信。
public struct KnownCatalog: Equatable, Sendable {
    public struct Entry: Equatable, Sendable {
        public var room: Room
        public var at: Date
    }

    public private(set) var entries: [Entry] = []

    public init() {}

    public var rooms: [Room] { entries.map(\.room) }

    /// 第一页替换，后续页追加；新进来的房间记 `date`，已有的保持原时间。
    public mutating func record(_ page: [Room], firstPage: Bool, at date: Date) {
        let added = page.map { Entry(room: $0, at: date) }
        entries = firstPage ? added : entries + added
    }

    public func usable(now: Date) -> [Entry] {
        entries.filter { now.timeIntervalSince($0.at) <= FavoriteCache.ttl }
    }
}

/// 一次查询计划：哪些已有答案（缓存或目录命中），哪些还要发请求。
public struct FavoritePlan: Equatable, Sendable {
    public struct Stamped: Equatable, Sendable {
        public var status: FavoriteStatus
        public var at: Date
    }

    /// 键为小写用户名。
    public var resolved: [String: FavoriteStatus]
    /// 其中来自目录（缓存里没有新鲜结果）的部分，带各自被取回的时间，调用方按它写回缓存。
    public var fromCatalog: [String: Stamped]
    public var pending: [String]
}

/// 同一时间只跑一批：新请求到来时取消在途批次，等它收尾后再开新批（所以网络并发不会叠加）；
/// 旧批次的调用方改拿最新一批的结果，旧批次不应再写回（用 `isCurrent` 判断）。
@available(macOS 10.15, *)
@MainActor
public final class SupersedingRunner<Value: Sendable> {
    private var seq = 0
    private var latest: (id: Int, task: Task<Value, Never>)?
    private var latestDone = true

    public init() {}

    /// 是否有批次在途（最新一批还没跑完）。
    public var isRunning: Bool { !latestDone }

    public func isCurrent(_ id: Int) -> Bool { latest?.id == id }

    public func run(_ work: @escaping @MainActor (_ id: Int) async -> Value) async -> Value {
        seq += 1
        let id = seq
        let previous = latest?.task
        previous?.cancel()
        latestDone = false
        let task = Task { @MainActor in
            _ = await previous?.value
            let value = await work(id)
            if self.latest?.id == id { self.latestDone = true }
            return value
        }
        latest = (id, task)
        var waiting = (id: id, task: task)
        while true {
            let value = await waiting.task.value
            guard let newer = latest, newer.id > waiting.id else { return value }
            waiting = newer
        }
    }
}

public enum FavoriteResolver {
    public static func plan(favorites: [String], catalog: KnownCatalog, cache: FavoriteCache, now: Date) -> FavoritePlan {
        let known = Dictionary(
            catalog.usable(now: now).map { (Curation.key($0.room.username), $0) },
            uniquingKeysWith: { first, second in first.at >= second.at ? first : second }
        )
        var plan = FavoritePlan(resolved: [:], fromCatalog: [:], pending: [])
        for name in favorites {
            let key = Curation.key(name)
            guard plan.resolved[key] == nil, !plan.pending.contains(where: { Curation.key($0) == key }) else { continue }
            if let cached = cache.fresh(for: name, now: now) {
                plan.resolved[key] = cached
            } else if let entry = known[key] {
                let status: FavoriteStatus = entry.room.show.isPlayable ? .online(entry.room) : .offline
                plan.resolved[key] = status
                plan.fromCatalog[key] = FavoritePlan.Stamped(status: status, at: entry.at)
            } else {
                plan.pending.append(name)
            }
        }
        return plan
    }

    /// 本批计划查询的名字里，是否有没拿到状态的（键为小写用户名）。只看本批计划内的名字：
    /// 批次进行中新加的收藏不在计划里，不算缺失，免得它让自动补查进入冷却。
    public static func hasUnresolved(pending: [String], statuses: [String: FavoriteStatus]) -> Bool {
        pending.contains { statuses[Curation.key($0)] == nil }
    }

    /// 在线的收藏房间：在线人数多的在前，同人数保持收藏名单里的先后。
    public static func onlineRooms(favorites: [String], statuses: [String: FavoriteStatus]) -> [Room] {
        var seen = Set<String>()
        let rooms: [Room] = favorites.compactMap { name in
            let key = Curation.key(name)
            guard seen.insert(key).inserted else { return nil }
            return statuses[key]?.room
        }
        return rooms.enumerated()
            .sorted { lhs, rhs in
                lhs.element.viewers != rhs.element.viewers ? lhs.element.viewers > rhs.element.viewers : lhs.offset < rhs.offset
            }
            .map(\.element)
    }
}

public struct FavoriteFetchResult: Equatable, Sendable {
    /// 键为小写用户名。网络失败、超时的不在里面（状态未知，不写缓存）。
    public var statuses: [String: FavoriteStatus]
    /// 遇到站点拦截：已停止发起新请求。
    public var challenged: Bool
}

/// 逐个查询收藏房间，并发有上限；遇到站点拦截立刻停止发起新请求。
@available(macOS 10.15, *)
public struct FavoriteFetcher: Sendable {
    public static let maxConcurrent = 4

    private enum Outcome: Sendable {
        case status(FavoriteStatus)
        case failed
        case challenge
    }

    private let client: any HTTPClient
    private let concurrency: Int
    private let clock: @Sendable () -> Date
    private let backoff: EndpointBackoffBox?

    public init(
        client: any HTTPClient, concurrency: Int = maxConcurrent,
        now: @escaping @Sendable () -> Date = { Date() }, backoff: EndpointBackoffBox? = .shared
    ) {
        self.client = client
        self.concurrency = max(1, min(concurrency, Self.maxConcurrent))
        self.clock = now
        self.backoff = backoff
    }

    public func fetch(_ names: [String]) async -> FavoriteFetchResult {
        var statuses: [String: FavoriteStatus] = [:]
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
                case .status(let status): statuses[key] = status
                case .challenge: challenged = true
                case .failed: break
                }
                if !challenged, !Task.isCancelled { startNext(&group) }
            }
        }
        return FavoriteFetchResult(statuses: statuses, challenged: challenged)
    }

    /// 退避窗口内不发请求；正文不是 JSON 也不是质询页（404、401 除外）按暂时不可用登记退避，状态未知。
    private func lookup(_ name: String) async -> Outcome {
        if let backoff, !backoff.allows(.chatContext) { return .failed }
        do {
            let result = try await client.send(ChatContext.request(username: name))
            if backoff?.note(.chatContext, status: result.status, data: result.data, definitive: ChatContext.definitiveStatuses) == true {
                return .failed
            }
            return .status(try ChatContext.decode(status: result.status, data: result.data, now: clock()))
        } catch CatalogFailure.challenge {
            return .challenge
        } catch {
            return .failed
        }
    }
}

/// 收藏夹导出与导入：一个 JSON 文件，只含用户名。
public enum FavoriteExchange {
    public struct Imported: Equatable, Sendable {
        public var names: [String]
        /// 格式不合格而丢弃的条数。
        public var rejected: Int
    }

    public enum Failure: Error, Equatable, Sendable { case tooLarge, malformed }

    public static func encode(_ names: [String]) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(File(app: "Vela", version: 1, favorites: names))
    }

    /// 接受 `{"favorites": [...]}`，也接受直接的字符串数组。
    public static func decode(_ data: Data) throws -> Imported {
        guard data.count <= Preferences.maxFileBytes else { throw Failure.tooLarge }
        let raw: [String]
        if let file = try? JSONDecoder().decode(File.self, from: data) {
            raw = file.favorites
        } else if let list = try? JSONDecoder().decode([String].self, from: data) {
            raw = list
        } else {
            throw Failure.malformed
        }
        var names: [String] = []
        var rejected = 0
        for item in raw {
            let name = item.trimmingCharacters(in: .whitespacesAndNewlines)
            if Room.isValidUsername(name) { names.append(name) } else { rejected += 1 }
        }
        return Imported(names: names, rejected: rejected)
    }

    private struct File: Codable {
        var app: String
        var version: Int
        var favorites: [String]
    }
}

/// 目录翻页结果落地：所有副作用（收藏用的目录快照、推荐理由）都在代次检查通过之后才写，
/// 被作废的旧响应只丢弃，不留任何痕迹。
public enum LoadCommit {
    /// `token` 与 `current` 不等（期间发生了重载或换来源）返回 false 且什么都不改。
    /// `raw` 是站点原始页（目录快照按它记），`arranged` 是排序后的页（理由按它算）。
    @discardableResult
    public static func apply(
        raw: CatalogPage, arranged: CatalogPage, offset: Int, source: FeedSource,
        token: Int, current: Int, replacing: Bool, now: Date,
        catalog: inout KnownCatalog, reasons: inout [String: String], reason: (Room) -> String
    ) -> Bool {
        guard token == current else { return false }
        if source != .favorites { catalog.record(raw.rooms, firstPage: offset == 0, at: now) }
        if replacing { reasons = [:] }
        if source == .guess {
            for room in arranged.rooms { reasons[room.username.lowercased()] = reason(room) }
        }
        return true
    }
}

/// 打开收藏夹时的自动补查冷却：查询失败或被拦截后，冷却期内再打开不再自动批量查询；
/// 手动「刷新状态」不受限，成功或手动刷新会清掉冷却。
public struct FavoriteRetryGate: Equatable, Sendable {
    public static let cooldown: TimeInterval = 60

    private var failedAt: Date?

    public init() {}

    public func allowsAutoCheck(now: Date) -> Bool {
        guard let failedAt else { return true }
        return now.timeIntervalSince(failedAt) >= Self.cooldown
    }

    public mutating func recordFailure(at date: Date) { failedAt = date }

    public mutating func reset() { failedAt = nil }
}
