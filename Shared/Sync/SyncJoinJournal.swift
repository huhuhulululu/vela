import Foundation
#if SWIFT_PACKAGE
import VelaCore
#endif

/// 加入提交的本机文件快照（docs/plans/sync.md 第 12.4 节、第 23 节第 13 条）。写本机之前整份复制收藏、口味文件与
/// 两个同步设置；任一步失败、或启动时发现快照存在而同步状态不是「已开启」，就用它换回提交前的本机数据。
protocol SyncJoinJournal: AnyObject {
    /// 快照存在：上一次提交还没收尾。
    var exists: Bool { get }
    func snapshot() -> Bool
    /// 换回快照并删除它；可重复执行（每一步换回后就删掉它的来源）。
    func restore() -> Bool
    /// 提交已完成：删除快照。
    func discard() -> Bool
}

/// 不做快照（只用于不涉及本机文件的测试与工具）。
final class NoJournal: SyncJoinJournal {
    var exists: Bool { false }
    func snapshot() -> Bool { true }
    func restore() -> Bool { true }
    func discard() -> Bool { true }
}

/// 文件实现：快照目录与原文件同卷（同一目录下），文件 0600，换回用原子 rename。
final class FileJoinJournal: SyncJoinJournal {
    let dir: URL
    let files: [URL]
    let readSettings: () -> [String: Bool]
    let writeSettings: ([String: Bool?]) -> Void
    let settingKeys: [String]
    private let fm = FileManager.default

    init(dir: URL, files: [URL], settingKeys: [String], readSettings: @escaping () -> [String: Bool], writeSettings: @escaping ([String: Bool?]) -> Void) {
        self.dir = dir
        self.files = files
        self.settingKeys = settingKeys
        self.readSettings = readSettings
        self.writeSettings = writeSettings
    }

    var exists: Bool { fm.fileExists(atPath: dir.path) }
    private func copyURL(_ f: URL) -> URL { dir.appendingPathComponent(f.lastPathComponent) }
    private func absentURL(_ f: URL) -> URL { dir.appendingPathComponent("absent-" + f.lastPathComponent) }
    private var settingsURL: URL { dir.appendingPathComponent("settings.json") }

    func snapshot() -> Bool {
        do {
            if exists { try fm.removeItem(at: dir) }
            try fm.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            for f in files {
                if fm.fileExists(atPath: f.path) {
                    try fm.copyItem(at: f, to: copyURL(f))
                    try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: copyURL(f).path)
                } else {
                    guard fm.createFile(atPath: absentURL(f).path, contents: Data(), attributes: [.posixPermissions: 0o600]) else { return false }
                }
            }
            // 设置只记明确设过的键；没设过的键换回时删除，恢复成「未设置」。
            let data = try JSONEncoder().encode(readSettings())
            guard fm.createFile(atPath: settingsURL.path, contents: data, attributes: [.posixPermissions: 0o600]) else { return false }
            return true
        } catch {
            try? fm.removeItem(at: dir)
            return false
        }
    }

    func restore() -> Bool {
        guard exists else { return true }
        for f in files {
            if fm.fileExists(atPath: copyURL(f).path) {
                // 同卷原子替换：rename(2) 覆盖目标。
                guard rename(copyURL(f).path, f.path) == 0 else { return false }
            } else if fm.fileExists(atPath: absentURL(f).path) {
                if fm.fileExists(atPath: f.path) {
                    guard (try? fm.removeItem(at: f)) != nil else { return false }
                }
                guard (try? fm.removeItem(at: absentURL(f))) != nil else { return false }
            }
        }
        if let data = fm.contents(atPath: settingsURL.path) {
            guard let saved = try? JSONDecoder().decode([String: Bool].self, from: data) else { return false }
            var values: [String: Bool?] = [:]
            for key in settingKeys { values[key] = saved[key] }
            writeSettings(values)
        }
        return discard()
    }

    func discard() -> Bool {
        guard exists else { return true }
        return (try? fm.removeItem(at: dir)) != nil
    }
}
