import Foundation

/// 本机收藏与屏蔽：按用户名（不分大小写）记录，各有数量上限，超出丢最旧的。
/// 同一个用户名只会在其中一边。纯值类型，所有更新返回新值。
public struct Curation: Codable, Equatable, Sendable {
    public static let maxEntries = 300

    /// 最新的在前，保留输入时的大小写用于显示。
    public private(set) var favorites: [String]
    public private(set) var blocked: [String]

    public init(favorites: [String] = [], blocked: [String] = []) {
        let kept = Self.clean(blocked)
        let blockedKeys = Set(kept.map(Self.key))
        self.blocked = kept
        self.favorites = Self.clean(favorites).filter { !blockedKeys.contains(Self.key($0)) }
    }

    public static func key(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    public var favoriteSet: Set<String> { Set(favorites.map(Self.key)) }
    public var blockedSet: Set<String> { Set(blocked.map(Self.key)) }

    public func isFavorite(_ name: String) -> Bool { favoriteSet.contains(Self.key(name)) }
    public func isBlocked(_ name: String) -> Bool { blockedSet.contains(Self.key(name)) }

    public func favoriting(_ name: String) -> Curation {
        guard !Self.key(name).isEmpty else { return self }
        return Curation(favorites: [name] + favorites, blocked: removing(name, from: blocked))
    }

    /// 撤销移除时用：这个名字此刻还能放回收藏（没被屏蔽、也没被重新收藏）。
    public func canRestoreFavorite(_ name: String) -> Bool {
        !Self.key(name).isEmpty && !isBlocked(name) && !isFavorite(name)
    }

    /// 撤销移除：只放回收藏，不碰屏蔽名单；不能放回时（已屏蔽、已重新收藏）原样返回，不覆盖用户之后的操作。
    public func restoringFavorite(_ name: String) -> Curation {
        canRestoreFavorite(name) ? Curation(favorites: [name] + favorites, blocked: blocked) : self
    }

    /// 导入：已有的收藏保持原先后，新名字接在后面；去重、遵守数量上限，屏蔽名单里的不收。
    public func importing(_ names: [String]) -> (curation: Curation, added: Int) {
        let next = Curation(favorites: favorites + names, blocked: blocked)
        let known = favoriteSet
        return (next, next.favorites.filter { !known.contains(Self.key($0)) }.count)
    }

    public func unfavoriting(_ name: String) -> Curation {
        Curation(favorites: removing(name, from: favorites), blocked: blocked)
    }

    public func blocking(_ name: String) -> Curation {
        guard !Self.key(name).isEmpty else { return self }
        return Curation(favorites: removing(name, from: favorites), blocked: [name] + blocked)
    }

    public func unblocking(_ name: String) -> Curation {
        Curation(favorites: favorites, blocked: removing(name, from: blocked))
    }

    private func removing(_ name: String, from list: [String]) -> [String] {
        let target = Self.key(name)
        return list.filter { Self.key($0) != target }
    }

    /// 去重（保留先出现的）、去空，超出上限只留最新的。
    private static func clean(_ names: [String]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for name in names {
            let trimmed = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Preferences.maxNameLength))
            guard !trimmed.isEmpty, seen.insert(trimmed.lowercased()).inserted else { continue }
            out.append(trimmed)
            if out.count == maxEntries { break }
        }
        return out
    }
}

/// 启动时那次读取的结果（docs/plans/sync.md 第 14.1 节）：开启或加入同步的前置条件。
public enum PreferenceLoadOutcome: String, Equatable, Sendable {
    case loaded, missing, quarantined, readOnly
}

extension PreferenceQuarantine.ReadResult {
    var outcome: PreferenceLoadOutcome {
        switch self {
        case .loaded: return .loaded
        case .missing: return .missing
        case .quarantined: return .quarantined
        case .readOnly: return .readOnly
        }
    }
}

/// 把收藏与屏蔽存成一个小 JSON 文件。读写失败不抛出：读退回空，写返回 false。
/// 读失败时坏文件改名为 `.bad-<时间戳>` 备份（`PreferenceQuarantine`）；备份失败则只读，保存跳过。
public final class CurationStore: @unchecked Sendable {
    public let url: URL
    private let lock = NSLock()
    private var readOnly = false
    private var firstOutcome: PreferenceLoadOutcome?
    /// 坏文件改名用的操作（测试注入失败用）。
    var moveItem: PreferenceQuarantine.Mover = PreferenceQuarantine.fileMover

    public init(url: URL) { self.url = url }

    /// 第一次 `load` 的结果，之后一直保留（之后再读到「文件不存在」也不覆盖）；尚未读取时为 nil。
    public var lastLoadOutcome: PreferenceLoadOutcome? {
        lock.lock()
        defer { lock.unlock() }
        return firstOutcome
    }

    public static func defaultURL() -> URL {
        PreferenceStore.defaultURL().deletingLastPathComponent().appendingPathComponent("feed-curation.json")
    }

    public func load(now: Date = Date()) -> Curation {
        lock.lock()
        defer { lock.unlock() }
        let result = PreferenceQuarantine.read(Curation.self, from: url, limit: Preferences.maxFileBytes, now: now, move: moveItem)
        readOnly = false
        if firstOutcome == nil { firstOutcome = result.outcome }
        switch result {
        case .loaded(let decoded): return Curation(favorites: decoded.favorites, blocked: decoded.blocked)
        case .missing, .quarantined: return Curation()
        case .readOnly:
            readOnly = true
            return Curation()
        }
    }

    /// 上次读取失败且坏文件没能改名备份：此时保存会被跳过（返回 false），不让空数据覆盖原文件。
    public var isReadOnly: Bool {
        lock.lock()
        defer { lock.unlock() }
        return readOnly
    }


    @discardableResult
    public func save(_ curation: Curation) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !readOnly else { return false }
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(curation).write(to: url, options: .atomic)
            return true
        } catch {
            return false
        }
    }
}
