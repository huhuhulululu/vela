#if canImport(Darwin)
import Foundation
#if SWIFT_PACKAGE
import VelaCore
#endif

/// 给同步状态文件加「不进备份」属性（Apple `isExcludedFromBackup`）。原子替换会换掉文件，所以每次保存后重设。
final class SyncBackupExcludedStore: SyncStateStore {
    let inner: SyncStateFileStore

    init(_ inner: SyncStateFileStore) { self.inner = inner }

    func load() -> SyncStateRead { inner.load() }

    func save(_ state: SyncState) -> Bool {
        guard inner.save(state) else { return false }
        var url = inner.url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        // 设属性失败不回滚已写的状态：文件本身是对的，下次保存再试。
        try? url.setResourceValues(values)
        return true
    }

    func delete() -> Bool { inner.delete() }
}
#endif
