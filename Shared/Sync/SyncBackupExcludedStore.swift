#if canImport(Darwin)
import Foundation
#if SWIFT_PACKAGE
import VelaCore
#endif

/// 给同步状态文件加「不进备份」属性（Apple `isExcludedFromBackup`）。原子替换会换掉文件，所以每次保存后重设。
/// 另把「状态损坏」持久化（第 23 节第 14、15 条，H4）：底层读到坏文件会把它改名隔离，下次再读就成了「没有状态」，
/// 钥匙串里的 `K` 会被当成孤儿删掉。所以只要状态文件存在，读之前先写损坏标记（先于任何改名落盘），读成功再删掉；
/// 标记写不成就不读（坏文件留在原处，下次仍是损坏）。标记在、状态文件不在 = 损坏，保存一律拒绝，
/// 直到用户明确关闭（`delete`）才清掉。之后任何时刻进程退出，磁盘上都不会出现「坏文件已隔离、却没有标记」。
final class SyncBackupExcludedStore: SyncStateStore {
    let inner: SyncStateFileStore
    private let fm = FileManager.default

    init(_ inner: SyncStateFileStore) { self.inner = inner }

    /// 写损坏标记；测试可注入失败（磁盘满）或观察写入时机。
    var writeMarker: (URL) -> Bool = { url in
        FileManager.default.createFile(atPath: url.path, contents: Data(), attributes: [.posixPermissions: 0o600])
    }

    /// `sync-state.json.corrupt`（与隔离备份 `sync-state.json.bad-*` 不同名）。
    var corruptMarker: URL { inner.url.appendingPathExtension("corrupt") }
    private var markedCorrupt: Bool { fm.fileExists(atPath: corruptMarker.path) }
    private var stateFileExists: Bool { fm.fileExists(atPath: inner.url.path) }

    func load() -> SyncStateRead {
        guard stateFileExists else { return markedCorrupt ? .corrupted : .none }
        // 读之前先把标记落盘：底层若判坏会立刻改名隔离，那之后才写标记就可能来不及（磁盘满、进程被杀）。
        guard markedCorrupt || writeMarker(corruptMarker) else { return .corrupted }
        let read = inner.load()
        // 读成功（或文件恰好不见了）：标记是预写的，删掉；删不掉也无妨，下次读到好文件会再删。
        if read != .corrupted { try? fm.removeItem(at: corruptMarker) }
        return read
    }

    func save(_ state: SyncState) -> Bool {
        // 标记在而状态文件已被隔离：仍处于损坏，拒绝覆盖。标记在、文件也在（预写标记没删掉）不算损坏。
        guard !(markedCorrupt && !stateFileExists), inner.save(state) else { return false }
        var url = inner.url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        // 设属性失败不回滚已写的状态：文件本身是对的，下次保存再试。
        try? url.setResourceValues(values)
        return true
    }

    func delete() -> Bool {
        guard inner.delete() else { return false }
        guard markedCorrupt else { return true }
        return (try? fm.removeItem(at: corruptMarker)) != nil
    }
}
#endif
