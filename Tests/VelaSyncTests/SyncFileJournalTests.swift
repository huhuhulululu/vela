import Foundation
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 文件快照：同目录、0600，换回用原子 rename；原本不存在的文件换回后删除；设置按快照恢复（未设过的键恢复成未设置）。
final class SyncFileJournalTests: XCTestCase {
    func testSnapshotAndRestoreRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("journal-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        let curation = dir.appendingPathComponent("feed-curation.json"), taste = dir.appendingPathComponent("feed-taste.json")
        try Data("old-curation".utf8).write(to: curation)
        var settings: [String: Bool] = ["publicOnly": false]
        let journal = FileJoinJournal(dir: dir.appendingPathComponent("sync-join-snapshot"), files: [curation, taste],
                                      settingKeys: ["publicOnly", "infoAlwaysOn"],
                                      readSettings: { settings },
                                      writeSettings: { values in for (k, v) in values { settings[k] = v } })
        XCTAssertFalse(journal.exists)
        XCTAssertTrue(journal.snapshot())
        XCTAssertTrue(journal.exists)
        let attrs = try FileManager.default.attributesOfItem(atPath: journal.dir.appendingPathComponent("feed-curation.json").path)
        XCTAssertEqual((attrs[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        try Data("projected".utf8).write(to: curation)
        try Data("new-taste".utf8).write(to: taste)
        settings = ["publicOnly": true, "infoAlwaysOn": true]
        XCTAssertTrue(journal.restore())
        XCTAssertEqual(try String(contentsOf: curation, encoding: .utf8), "old-curation")
        XCTAssertFalse(FileManager.default.fileExists(atPath: taste.path), "原本没有的文件换回后删除")
        XCTAssertEqual(settings, ["publicOnly": false])
        XCTAssertTrue(journal.restore(), "换回可重复执行")
        XCTAssertEqual(try String(contentsOf: curation, encoding: .utf8), "old-curation")
        XCTAssertEqual(journal.status, .pending, "换回不删快照：收尾完成后由调用方删除")
        XCTAssertTrue(journal.discard())
        XCTAssertFalse(journal.exists)
        XCTAssertFalse(journal.restore(), "没有快照时拒绝换回")

        // 已提交的快照只能删除，不能换回。
        XCTAssertTrue(journal.snapshot())
        XCTAssertTrue(journal.markCommitted())
        try Data("projected".utf8).write(to: curation)
        XCTAssertFalse(journal.restore())
        XCTAssertEqual(try String(contentsOf: curation, encoding: .utf8), "projected")
        XCTAssertTrue(journal.discard())
    }
}
