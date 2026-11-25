import Foundation

/// 把收藏、口味、观看账本三个文件的用户名键一次性写成 raw（`cb:alice`）。
/// 幂等；先备份 `<名>.pre-sites`（已有不覆盖）；任何一步失败都不改原文件。
public enum SiteKeyMigration {
    public static let version = 2

    public struct Report: Equatable, Sendable {
        public var migrated: [URL]
        public var skipped: [URL]
        public var failed: [URL]

        public init(migrated: [URL] = [], skipped: [URL] = [], failed: [URL] = []) {
            self.migrated = migrated
            self.skipped = skipped
            self.failed = failed
        }
    }

    public typealias Writer = (Data, URL) throws -> Void

    /// 依次处理 curation、taste、watch-ledger。`write` 仅测试注入失败用。
    public static func run(
        curation: URL,
        taste: URL,
        ledger: URL,
        fileManager: FileManager = .default,
        write: Writer? = nil,
        copy: ((URL, URL) throws -> Void)? = nil
    ) -> Report {
        let writer = write ?? { try $0.write(to: $1, options: .atomic) }
        let copier = copy ?? { try fileManager.copyItem(at: $0, to: $1) }
        var report = Report()
        for (url, kind) in [(curation, Kind.curation), (taste, .taste), (ledger, .ledger)] {
            switch migrate(url, kind: kind, fileManager: fileManager, write: writer, copy: copier) {
            case .migrated: report.migrated.append(url)
            case .skipped: report.skipped.append(url)
            case .failed: report.failed.append(url)
            }
        }
        return report
    }

    /// 用 `.pre-sites` 备份覆盖回原文件。没有备份的记入 `failed`。`read` 与 `write` 仅测试注入失败用。
    public static func rollback(
        curation: URL,
        taste: URL,
        ledger: URL,
        fileManager: FileManager = .default,
        read: ((URL) throws -> Data)? = nil,
        write: Writer? = nil
    ) -> Report {
        let reader = read ?? { try Data(contentsOf: $0) }
        let writer = write ?? { try $0.write(to: $1, options: .atomic) }
        var report = Report()
        for url in [curation, taste, ledger] {
            let backup = backupURL(for: url)
            guard fileManager.fileExists(atPath: backup.path) else {
                report.failed.append(url)
                continue
            }
            do {
                let data = try reader(backup)
                try writer(data, url)
                report.migrated.append(url)
            } catch {
                report.failed.append(url)
            }
        }
        return report
    }

    public static func backupURL(for file: URL) -> URL {
        file.deletingLastPathComponent().appendingPathComponent(file.lastPathComponent + ".pre-sites")
    }

    /// 「清除全部偏好数据」时一并删掉三份备份。文件不在也算成功。
    @discardableResult
    public static func removeBackups(
        curation: URL, taste: URL, ledger: URL, fileManager: FileManager = .default
    ) -> Bool {
        var ok = true
        for url in [curation, taste, ledger] {
            let backup = backupURL(for: url)
            guard fileManager.fileExists(atPath: backup.path) else { continue }
            do { try fileManager.removeItem(at: backup) } catch { ok = false }
        }
        return ok
    }

    private enum Kind { case curation, taste, ledger }
    private enum Outcome { case migrated, skipped, failed }

    private static func migrate(
        _ url: URL, kind: Kind, fileManager: FileManager, write: Writer, copy: (URL, URL) throws -> Void
    ) -> Outcome {
        guard fileManager.fileExists(atPath: url.path) else { return .skipped }
        if let size = fileSize(url, fileManager: fileManager), size > Preferences.maxFileBytes {
            return .skipped
        }
        guard let data = try? Data(contentsOf: url), data.count <= Preferences.maxFileBytes else { return .skipped }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return .skipped }
        if let version = intValue(object["keyVersion"]), version >= self.version { return .skipped }
        guard hasPayload(object, kind: kind) else { return .skipped }
        guard let encoded = encode(data, kind: kind) else { return .skipped }

        let backup = backupURL(for: url)
        if !fileManager.fileExists(atPath: backup.path) {
            do { try copy(url, backup) } catch { return .failed }
        }
        do {
            try write(encoded, url)
            return .migrated
        } catch {
            return .failed
        }
    }

    private static func hasPayload(_ object: [String: Any], kind: Kind) -> Bool {
        let keys: Set<String>
        switch kind {
        case .curation: keys = ["favorites", "blocked"]
        case .taste: keys = ["beliefs", "seen", "daily", "paused"]
        case .ledger: keys = ["entries"]
        }
        return object.keys.contains { keys.contains($0) }
    }

    private static func encode(_ data: Data, kind: Kind) -> Data? {
        let decoder = JSONDecoder()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        switch kind {
        case .curation:
            guard let value = try? decoder.decode(Curation.self, from: data) else { return nil }
            return try? encoder.encode(value)
        case .taste:
            guard let value = try? decoder.decode(Taste.self, from: data) else { return nil }
            return try? encoder.encode(value)
        case .ledger:
            guard let value = try? decoder.decode(WatchLedger.self, from: data) else { return nil }
            return try? encoder.encode(value)
        }
    }

    private static func intValue(_ raw: Any?) -> Int? {
        if let value = raw as? Int { return value }
        if let value = raw as? Double { return Int(value) }
        if let value = raw as? NSNumber { return value.intValue }
        return nil
    }

    private static func fileSize(_ url: URL, fileManager: FileManager) -> Int? {
        guard let raw = try? fileManager.attributesOfItem(atPath: url.path)[.size] else { return nil }
        if let value = raw as? Int { return value }
        if let value = raw as? UInt64, value <= UInt64(Int.max) { return Int(value) }
        if let value = raw as? NSNumber { return value.intValue }
        return nil
    }
}
