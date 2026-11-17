import Foundation

/// 偏好文件读失败（解码失败、读不出、超过大小上限）时的现场保留：把原文件改名为
/// `<文件名>.bad-<秒级时间戳>`，内存里继续用空值。改名成功后原路径已空，之后保存不会覆盖现场；
/// 改名失败时调用方应进入只读，不让空数据覆盖原文件。每个文件最多保留 `maxBackups` 份，删最旧的。
/// 不抛出：所有文件操作失败都折成返回值。调用方负责加锁。
public enum PreferenceQuarantine {
    public static let maxBackups = 3

    /// 改名操作；测试可注入失败。
    public typealias Mover = @Sendable (URL, URL) throws -> Void
    public static let fileMover: Mover = { try FileManager.default.moveItem(at: $0, to: $1) }

    public enum ReadResult<Value> {
        /// 文件不存在：可正常保存。
        case missing
        /// 读取并解码成功。
        case loaded(Value)
        /// 读失败，已改名备份：内存用空值，可正常保存。
        case quarantined
        /// 读失败且改名失败：内存用空值，但不得覆盖原路径。
        case readOnly
    }

    /// 读取并解码 `url`；失败时尝试备份。`limit` 为文件大小上限（字节）。
    public static func read<Value: Decodable>(
        _ type: Value.Type, from url: URL, limit: Int, now: Date = Date(), move: Mover = fileMover
    ) -> ReadResult<Value> {
        let manager = FileManager.default
        guard manager.fileExists(atPath: url.path) else { return .missing }
        if PreferenceStore.fitsLimit(url, limit),
           let data = try? Data(contentsOf: url),
           let decoded = try? JSONDecoder().decode(Value.self, from: data) {
            return .loaded(decoded)
        }
        return quarantine(url, now: now, move: move) ? .quarantined : .readOnly
    }

    /// 把坏文件改名为带时间戳的 `.bad` 并清理多余备份；改名成功返回 true。
    @discardableResult
    public static func quarantine(_ url: URL, now: Date = Date(), move: Mover = fileMover) -> Bool {
        let target = backupURL(for: url, now: now)
        do {
            try move(url, target)
        } catch {
            return false
        }
        prune(url)
        return true
    }

    /// `<文件名>.bad-<秒>`；同一秒已有备份时追加 `-1`、`-2`……
    static func backupURL(for url: URL, now: Date) -> URL {
        let base = "\(url.lastPathComponent).bad-\(Int(now.timeIntervalSince1970))"
        let directory = url.deletingLastPathComponent()
        var candidate = directory.appendingPathComponent(base)
        var suffix = 1
        while FileManager.default.fileExists(atPath: candidate.path) {
            candidate = directory.appendingPathComponent("\(base)-\(suffix)")
            suffix += 1
        }
        return candidate
    }

    /// 现有备份（按时间从旧到新）。
    public static func backups(of url: URL) -> [URL] {
        let directory = url.deletingLastPathComponent()
        let prefix = "\(url.lastPathComponent).bad-"
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: directory.path) else { return [] }
        return names
            .filter { $0.hasPrefix(prefix) }
            .map { (name: $0, key: sortKey(String($0.dropFirst(prefix.count)))) }
            .sorted { $0.key == $1.key ? $0.name < $1.name : $0.key.lexicographicallyPrecedes($1.key) }
            .map { directory.appendingPathComponent($0.name) }
    }

    /// 只保留最新的 `maxBackups` 份；删除失败忽略。
    static func prune(_ url: URL) {
        let all = backups(of: url)
        guard all.count > maxBackups else { return }
        for stale in all.prefix(all.count - maxBackups) {
            try? FileManager.default.removeItem(at: stale)
        }
    }

    /// "<秒>" 或 "<秒>-<序号>" → [秒, 序号]；认不出的排最前（最先被删）。
    private static func sortKey(_ tail: String) -> [Int] {
        let parts = tail.split(separator: "-").map { Int($0) ?? -1 }
        return parts.count == 1 ? [parts[0], 0] : parts
    }
}
