import Foundation

/// 实际观看累计：为温和的收藏提示记账（不自动收藏）。
public struct WatchLedger: Codable, Equatable, Sendable {
    public static let totalThreshold: TimeInterval = 600
    public static let visitMinSeconds: TimeInterval = 60
    public static let visitGapSeconds: TimeInterval = 30 * 60
    public static let visitCountThreshold = 3
    public static let maxEntries = 500

    public struct Entry: Codable, Equatable, Sendable {
        public var totalSeconds: TimeInterval
        public var visitCount: Int
        public var lastVisitStart: TimeInterval?
        public var suggested: Bool
        public var lastUsed: TimeInterval

        static func empty(now: TimeInterval) -> Entry {
            Entry(totalSeconds: 0, visitCount: 0, lastVisitStart: nil, suggested: false, lastUsed: now)
        }
    }

    public private(set) var entries: [String: Entry]

    public init(entries: [String: Entry] = [:]) {
        self.entries = entries
    }

    public static func key(_ username: String) -> String {
        let trimmed = String(username.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Preferences.maxNameLength))
        return trimmed.lowercased()
    }

    /// 结算一段有效停留：累计秒数，并按规则计「有效次数」。
    public func recording(username: String, seconds: TimeInterval, start: TimeInterval, now: TimeInterval = Date().timeIntervalSince1970) -> WatchLedger {
        let key = Self.key(username)
        guard !key.isEmpty, seconds > 0 else { return capped(now: now) }
        var entry = entries[key] ?? Entry.empty(now: now)
        entry.totalSeconds += max(0, seconds)
        entry.lastUsed = now
        if seconds >= Self.visitMinSeconds {
            if entry.lastVisitStart == nil || start - entry.lastVisitStart! >= Self.visitGapSeconds {
                entry.visitCount += 1
                entry.lastVisitStart = start
            }
        }
        var next = self
        next.entries[key] = entry
        return next.capped(now: now)
    }

    /// 是否应提示收藏（可选带上当前在播、尚未结算的观看）。
    public func shouldSuggest(
        username: String,
        favorited: Bool,
        blocked: Bool,
        liveSeconds: TimeInterval = 0,
        liveStart: TimeInterval? = nil
    ) -> Bool {
        guard !favorited, !blocked else { return false }
        let key = Self.key(username)
        guard !key.isEmpty else { return false }
        let entry = entries[key]
        if entry?.suggested == true { return false }
        let metrics = Self.metrics(entry: entry, liveSeconds: liveSeconds, liveStart: liveStart)
        return metrics.total >= Self.totalThreshold || metrics.visits >= Self.visitCountThreshold
    }

    /// 距离累计满 10 分钟还差多少（含当前在播段）；已达标或已提示时返回 nil。
    public func secondsUntilTotalSuggest(
        username: String,
        favorited: Bool,
        blocked: Bool,
        liveSeconds: TimeInterval,
        liveStart: TimeInterval?
    ) -> TimeInterval? {
        guard !favorited, !blocked else { return nil }
        let key = Self.key(username)
        guard !key.isEmpty, entries[key]?.suggested != true else { return nil }
        if shouldSuggest(
            username: username, favorited: false, blocked: false,
            liveSeconds: liveSeconds, liveStart: liveStart
        ) {
            return nil
        }
        let metrics = Self.metrics(entry: entries[key], liveSeconds: liveSeconds, liveStart: liveStart)
        guard metrics.total < Self.totalThreshold else { return nil }
        return max(0, Self.totalThreshold - metrics.total)
    }

    /// 下一个需要重新判定的时间点：累计满 10 分钟，或本次观看满 60 秒（若那时会凑够第三次有效观看）。
    /// 没有后续检查点（已达标、已提示、已收藏或屏蔽）时返回 nil。
    public func secondsUntilNextSuggestCheck(
        username: String,
        favorited: Bool,
        blocked: Bool,
        liveSeconds: TimeInterval,
        liveStart: TimeInterval?
    ) -> TimeInterval? {
        let total = secondsUntilTotalSuggest(
            username: username, favorited: favorited, blocked: blocked, liveSeconds: liveSeconds, liveStart: liveStart
        )
        guard let total, liveSeconds < Self.visitMinSeconds, let start = liveStart else { return total }
        let entry = entries[Self.key(username)]
        let counts = entry?.lastVisitStart.map { start - $0 >= Self.visitGapSeconds } ?? true
        guard counts, (entry?.visitCount ?? 0) + 1 >= Self.visitCountThreshold else { return total }
        return min(total, Self.visitMinSeconds - max(0, liveSeconds))
    }

    public func markingSuggested(_ username: String, now: TimeInterval = Date().timeIntervalSince1970) -> WatchLedger {
        let key = Self.key(username)
        guard !key.isEmpty else { return self }
        var entry = entries[key] ?? Entry.empty(now: now)
        entry.suggested = true
        entry.lastUsed = now
        var next = self
        next.entries[key] = entry
        return next.capped(now: now)
    }

    public func cleared() -> WatchLedger { WatchLedger() }

    public func capped(now: TimeInterval) -> WatchLedger {
        guard entries.count > Self.maxEntries else {
            return WatchLedger(entries: entries.filter { $0.key.count <= Preferences.maxNameLength })
        }
        let kept = entries
            .filter { $0.key.count <= Preferences.maxNameLength }
            .sorted { $0.value.lastUsed > $1.value.lastUsed }
            .prefix(Self.maxEntries)
        return WatchLedger(entries: Dictionary(uniqueKeysWithValues: kept.map { ($0.key, $0.value) }))
    }

    private static func metrics(entry: Entry?, liveSeconds: TimeInterval, liveStart: TimeInterval?) -> (total: TimeInterval, visits: Int) {
        let baseTotal = entry?.totalSeconds ?? 0
        let total = baseTotal + max(0, liveSeconds)
        var visits = entry?.visitCount ?? 0
        if liveSeconds >= visitMinSeconds, let start = liveStart {
            if entry?.lastVisitStart == nil || start - entry!.lastVisitStart! >= visitGapSeconds {
                visits += 1
            }
        }
        return (total, visits)
    }
}

/// 观看账本 JSON 存储。读写失败不抛出：读退回空，写返回 false。
/// 读失败时坏文件改名为 `.bad-<时间戳>` 备份（`PreferenceQuarantine`）；备份失败则只读，保存跳过。
public final class WatchLedgerStore: @unchecked Sendable {
    public let url: URL
    private let lock = NSLock()
    private var readOnly = false
    /// 坏文件改名用的操作（测试注入失败用）。
    var moveItem: PreferenceQuarantine.Mover = PreferenceQuarantine.fileMover

    public init(url: URL) { self.url = url }

    public static func defaultURL() -> URL {
        PreferenceStore.defaultURL().deletingLastPathComponent().appendingPathComponent("watch-ledger.json")
    }

    public func load(now: Date = Date()) -> WatchLedger {
        lock.lock()
        defer { lock.unlock() }
        let result = PreferenceQuarantine.read(WatchLedger.self, from: url, limit: Preferences.maxFileBytes, now: now, move: moveItem)
        readOnly = false
        switch result {
        case .loaded(let decoded): return decoded.capped(now: now.timeIntervalSince1970)
        case .missing, .quarantined: return WatchLedger()
        case .readOnly:
            readOnly = true
            return WatchLedger()
        }
    }

    /// 上次读取失败且坏文件没能改名备份：此时保存会被跳过（返回 false），不让空数据覆盖原文件。
    public var isReadOnly: Bool {
        lock.lock()
        defer { lock.unlock() }
        return readOnly
    }


    @discardableResult
    public func save(_ ledger: WatchLedger) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !readOnly else { return false }
        let capped = ledger.capped(now: Date().timeIntervalSince1970)
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(capped).write(to: url, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    @discardableResult
    public func erase() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        do {
            if FileManager.default.fileExists(atPath: url.path) {
                try FileManager.default.removeItem(at: url)
            }
            readOnly = false
            return true
        } catch {
            return false
        }
    }
}