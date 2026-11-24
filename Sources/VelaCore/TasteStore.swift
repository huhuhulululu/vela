import Foundation

/// 把口味画像存成一个小 JSON 文件。读写失败不抛出：读退回空画像，写返回 false。
/// 新文件不存在时尝试从旧版偏好文件迁移（旧文件不改动，直到用户清除全部偏好数据）。
/// 读失败时坏文件改名为 `.bad-<时间戳>` 备份（`PreferenceQuarantine`）；备份失败则只读，保存跳过。
public final class TasteStore: @unchecked Sendable {
    public let url: URL
    public let legacyURL: URL?
    private let lock = NSLock()
    private var readOnly = false
    private var firstOutcome: PreferenceLoadOutcome?
    /// 坏文件改名用的操作（测试注入失败用）。
    var moveItem: PreferenceQuarantine.Mover = PreferenceQuarantine.fileMover

    public init(url: URL, legacyURL: URL? = nil) {
        self.url = url
        self.legacyURL = legacyURL
    }

    public static func defaultURL() -> URL {
        PreferenceStore.defaultURL().deletingLastPathComponent().appendingPathComponent("feed-taste.json")
    }

    /// 第一次 `load` 的结果，之后一直保留（docs/plans/sync.md 第 14.1 节）；尚未读取时为 nil。
    public var lastLoadOutcome: PreferenceLoadOutcome? {
        lock.lock()
        defer { lock.unlock() }
        return firstOutcome
    }

    public func load(now: Date = Date()) -> Taste {
        lock.lock()
        defer { lock.unlock() }
        let result = PreferenceQuarantine.read(Taste.self, from: url, limit: Preferences.maxFileBytes, now: now, move: moveItem)
        readOnly = false
        if firstOutcome == nil { firstOutcome = result.outcome }
        switch result {
        case .loaded(let decoded): return decoded.capped(now: now)
        case .quarantined: return Taste()
        case .readOnly:
            readOnly = true
            return Taste()
        case .missing:
            guard let legacyURL else { return Taste() }
            return Taste(migrating: PreferenceStore(url: legacyURL).load(now: now)).capped(now: now)
        }
    }

    /// 上次读取失败且坏文件没能改名备份：此时保存会被跳过（返回 false），不让空数据覆盖原文件。
    public var isReadOnly: Bool {
        lock.lock()
        defer { lock.unlock() }
        return readOnly
    }


    @discardableResult
    public func save(_ taste: Taste, now: Date = Date()) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !readOnly else { return false }
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(taste.capped(now: now)).write(to: url, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    /// 删除旧版偏好文件（「清除全部偏好数据」用）；文件不存在也算成功。
    @discardableResult
    public func eraseLegacy() -> Bool {
        guard let legacyURL else { return true }
        lock.lock()
        defer { lock.unlock() }
        do {
            if FileManager.default.fileExists(atPath: legacyURL.path) { try FileManager.default.removeItem(at: legacyURL) }
            return true
        } catch {
            return false
        }
    }

    /// 持同一把文件锁执行外部文件操作（如站点键迁移），避免与后台保存竞态覆写。
    public func withFileLock<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }
}
