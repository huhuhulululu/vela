#if canImport(Darwin)
import Foundation
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// `sync-state.json` 排除出备份（docs/plans/sync.md 第 11.1 节）：原子替换会换掉文件，每次保存后都要重设。
final class SyncStateBackupTests: XCTestCase {
    func testEverySaveMarksFileExcludedFromBackup() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sync-backup-\(UUID().uuidString)")
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("sync-state.json")
        let store = SyncBackupExcludedStore(SyncStateFileStore(url: url))
        let state = SyncState(phase: .active, origin: "https://sync.example", blobID: String(repeating: "a", count: 64), clock: SyncClock(dev: 1))
        for _ in 0..<2 {
            XCTAssertTrue(store.save(state))
            let values = try url.resourceValues(forKeys: [.isExcludedFromBackupKey])
            XCTAssertEqual(values.isExcludedFromBackup, true)
        }
        XCTAssertEqual(store.load(), .present(state))
        XCTAssertTrue(store.delete())
        XCTAssertEqual(store.load(), .none)
    }
}
#endif
