import Foundation
#if SWIFT_PACKAGE
import VelaCore
#endif

/// 快照的状态（docs/plans/sync.md 第 12.4 节、第 23 节第 14 条）。
enum SyncJoinJournalStatus: Equatable {
    case none
    /// 完整快照，提交还没定局：失败或启动时没有同步状态就换回。
    case pending
    /// 「已开启」已写，提交已定局：只能删除，不能换回。
    case committed
    /// 发布目录没有完整性标记（例如旧版本构建到一半留下的）：快照是写本机的第一步，没写过别的，直接删除，不换回。
    case incomplete
}

/// 加入提交的本机文件快照（docs/plans/sync.md 第 12.4 节、第 23 节第 13、14 条）。写本机之前整份复制收藏、口味文件与
/// 两个同步设置；任一步失败、或启动时发现未定局的快照而没有同步状态，就用它换回提交前的本机数据。
protocol SyncJoinJournal: AnyObject {
    var status: SyncJoinJournalStatus { get }
    /// 在临时目录完整构建后原子发布；任何失败都不留目录。
    func snapshot() -> Bool
    /// 「已开启」写入之后：持久标记已提交，此后只能删除。
    func markCommitted() -> Bool
    /// 换回快照里的文件与设置；可重复执行，不删除快照（收尾完成后由调用方 `discard`）。只接受未定局的完整快照。
    func restore() -> Bool
    /// 删除快照（连同残留的临时目录）。
    func discard() -> Bool
}

extension SyncJoinJournal {
    /// 有任何快照残留（包括已提交、不完整的）。
    var exists: Bool { status != .none }
}

/// 不做快照（只用于不涉及本机文件的测试与工具）。
final class NoJournal: SyncJoinJournal {
    var status: SyncJoinJournalStatus { .none }
    func snapshot() -> Bool { true }
    func markCommitted() -> Bool { true }
    func restore() -> Bool { true }
    func discard() -> Bool { true }
}

/// 文件实现：快照目录与原文件同卷（同一目录下），文件 0600，换回用原子 rename。
/// 构建在同目录的 `<dir>.tmp-<随机>` 里进行，写完全部副本与完整性标记后整目录 rename 成 `dir` 发布，
/// 所以 `dir` 要么不存在、要么带完整性标记；已提交另写一个标记文件。
final class FileJoinJournal: SyncJoinJournal {
    static let completeMarker = "complete"
    static let committedMarker = "committed"
    static let settingsFile = "settings.json"

    let dir: URL
    let files: [URL]
    let readSettings: () -> [String: Bool]
    let writeSettings: ([String: Bool?]) -> Void
    let settingKeys: [String]
    /// 写一个小文件；测试可注入失败。
    var writeFile: (URL, Data) -> Bool = { url, data in
        FileManager.default.createFile(atPath: url.path, contents: data, attributes: [.posixPermissions: 0o600])
    }
    /// 原子换回单个文件（同卷 rename(2)）；测试可注入中断。
    var renameItem: (URL, URL) -> Bool = { from, to in
        rename(from.path, to.path) == 0
    }
    /// 删除单个文件或目录；测试可注入中断。
    var removeItem: (URL) -> Bool = { url in
        (try? FileManager.default.removeItem(at: url)) != nil
    }
    /// 读取快照里的设置文件；测试可注入中断。
    var readFile: (URL) -> Data? = { url in
        FileManager.default.contents(atPath: url.path)
    }
    private let fm = FileManager.default

    init(dir: URL, files: [URL], settingKeys: [String], readSettings: @escaping () -> [String: Bool], writeSettings: @escaping ([String: Bool?]) -> Void) {
        self.dir = dir
        self.files = files
        self.settingKeys = settingKeys
        self.readSettings = readSettings
        self.writeSettings = writeSettings
    }

    private func copyURL(_ f: URL, in d: URL) -> URL { d.appendingPathComponent(f.lastPathComponent) }
    private func absentURL(_ f: URL, in d: URL) -> URL { d.appendingPathComponent("absent-" + f.lastPathComponent) }
    private func settingsURL(in d: URL) -> URL { d.appendingPathComponent(Self.settingsFile) }
    private func has(_ name: String) -> Bool { fm.fileExists(atPath: dir.appendingPathComponent(name).path) }

    /// 同目录下残留的临时构建目录。
    private var temporaries: [URL] {
        let parent = dir.deletingLastPathComponent()
        let prefix = dir.lastPathComponent + ".tmp-"
        let names = (try? fm.contentsOfDirectory(atPath: parent.path)) ?? []
        return names.filter { $0.hasPrefix(prefix) }.map { parent.appendingPathComponent($0) }
    }

    /// 只看发布目录（写操作闸门每次都会问，不扫目录）。崩溃留下的临时目录从未发布、不会被换回，下次快照或删除时清掉。
    /// 完整性要求 `complete` 与 `settings.json` 同时存在：构建时二者一并原子发布，换回不删 `settings.json`，
    /// 只有删快照中途中断才可能缺 `settings.json`，按 `.incomplete` 直接清理，不再永久卡在「需要修复」。
    var status: SyncJoinJournalStatus {
        guard fm.fileExists(atPath: dir.path) else { return .none }
        if has(Self.committedMarker) { return .committed }
        return has(Self.completeMarker) && has(Self.settingsFile) ? .pending : .incomplete
    }

    func snapshot() -> Bool {
        guard discard() else { return false }
        let tmp = dir.deletingLastPathComponent().appendingPathComponent(dir.lastPathComponent + ".tmp-" + UUID().uuidString)
        guard build(tmp), rename(tmp.path, dir.path) == 0 else {
            try? fm.removeItem(at: tmp)
            return false
        }
        return true
    }

    private func build(_ tmp: URL) -> Bool {
        do {
            try fm.createDirectory(at: tmp, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            for f in files {
                if fm.fileExists(atPath: f.path) {
                    try fm.copyItem(at: f, to: copyURL(f, in: tmp))
                    try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: copyURL(f, in: tmp).path)
                } else {
                    guard writeFile(absentURL(f, in: tmp), Data()) else { return false }
                }
            }
            // 设置只记明确设过的键；没设过的键换回时删除，恢复成「未设置」。
            let data = try JSONEncoder().encode(readSettings())
            guard writeFile(settingsURL(in: tmp), data) else { return false }
            // 完整性标记最后写：发布出去的目录一定是完整的。
            return writeFile(tmp.appendingPathComponent(Self.completeMarker), Data())
        } catch {
            return false
        }
    }

    func markCommitted() -> Bool {
        switch status {
        case .committed: return true
        case .pending: return writeFile(dir.appendingPathComponent(Self.committedMarker), Data())
        case .none, .incomplete: return false
        }
    }

    func restore() -> Bool {
        guard status == .pending else { return false }
        for f in files {
            let copy = copyURL(f, in: dir)
            let absent = absentURL(f, in: dir)
            if fm.fileExists(atPath: copy.path) {
                // 同卷原子替换：rename(2) 覆盖目标。
                guard renameItem(copy, f) else { return false }
            } else if fm.fileExists(atPath: absent.path) {
                if fm.fileExists(atPath: f.path) {
                    guard removeItem(f) else { return false }
                }
                guard removeItem(absent) else { return false }
            }
        }
        guard let data = readFile(settingsURL(in: dir)),
              let saved = try? JSONDecoder().decode([String: Bool].self, from: data) else { return false }
        var values: [String: Bool?] = [:]
        for key in settingKeys { values[key] = saved[key] }
        writeSettings(values)
        return true
    }

    func discard() -> Bool {
        var ok = true
        if fm.fileExists(atPath: dir.path), !removeSnapshotDirectory(dir) { ok = false }
        for d in temporaries where fm.fileExists(atPath: d.path) {
            if !removeItem(d) { ok = false }
        }
        return ok
    }

    /// 删发布快照目录：先删完整性标记，再逐个删其余条目，最后删空目录。
    /// 删标记前失败：快照完整保留，重启可再次换回；删标记后中断：目录转为 `.incomplete`（或保持 `.committed`），
    /// 重启直接删掉，不会留下「缺 `settings.json` 的未提交快照」。
    private func removeSnapshotDirectory(_ d: URL) -> Bool {
        let complete = d.appendingPathComponent(Self.completeMarker)
        if fm.fileExists(atPath: complete.path) {
            guard removeItem(complete) else { return false }
        }
        if let names = try? fm.contentsOfDirectory(atPath: d.path) {
            for name in names.sorted() {
                guard removeItem(d.appendingPathComponent(name)) else { return false }
            }
        }
        return removeItem(d)
    }
}
