#if canImport(Darwin)
import Foundation
#if SWIFT_PACKAGE
import VelaCore
#endif

/// 给同步状态文件加「不进备份」属性（Apple `isExcludedFromBackup`）。原子替换会换掉文件，所以每次保存后重设。
/// 另把「状态损坏」持久化（第 23 节第 14 条，H4）：底层读到坏文件会把它改名隔离，下次再读就成了「没有状态」，
/// 钥匙串里的 `K` 会被当成孤儿删掉。所以第一次读到损坏时在旁边写一个标记文件，之后每次读都报损坏、保存一律拒绝，
/// 直到用户明确关闭（`delete`）才清掉。
final class SyncBackupExcludedStore: SyncStateStore {
    let inner: SyncStateFileStore
    private let fm = FileManager.default

    init(_ inner: SyncStateFileStore) { self.inner = inner }

    /// `sync-state.json.corrupt`（与隔离备份 `sync-state.json.bad-*` 不同名）。
    var corruptMarker: URL { inner.url.appendingPathExtension("corrupt") }
    private var markedCorrupt: Bool { fm.fileExists(atPath: corruptMarker.path) }

    func load() -> SyncStateRead {
        if markedCorrupt { return .corrupted }
        let read = inner.load()
        if read == .corrupted {
            // 标记写不成也照样报损坏；坏文件改名失败（只读）时原文件还在，下次仍会读到损坏。
            _ = fm.createFile(atPath: corruptMarker.path, contents: Data(), attributes: [.posixPermissions: 0o600])
        }
        return read
    }

    func save(_ state: SyncState) -> Bool {
        guard !markedCorrupt, inner.save(state) else { return false }
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
