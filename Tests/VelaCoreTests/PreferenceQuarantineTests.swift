import XCTest
@testable import VelaCore

/// A11：读失败的偏好文件先改名备份；备份失败则只读，不让空数据覆盖原文件。
final class PreferenceQuarantineTests: XCTestCase {
    private var directory: URL!
    private let now = Date(timeIntervalSince1970: 1_700_000_000)

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("vela-quarantine-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
    }

    private func file(_ name: String, _ contents: Data) throws -> URL {
        let url = directory.appendingPathComponent(name)
        try contents.write(to: url)
        return url
    }

    private func backupNames(_ url: URL) -> [String] {
        PreferenceQuarantine.backups(of: url).map(\.lastPathComponent)
    }

    private let failingMove: PreferenceQuarantine.Mover = { _, _ in
        throw CocoaError(.fileWriteNoPermission)
    }

    // MARK: 三个存储

    func testCorruptTasteIsBackedUpThenSavable() throws {
        let url = try file("feed-taste.json", Data("not json".utf8))
        let store = TasteStore(url: url)
        XCTAssertEqual(store.load(now: now), Taste())
        XCTAssertFalse(store.isReadOnly)
        XCTAssertEqual(backupNames(url), ["feed-taste.json.bad-1700000000"])
        let backup = directory.appendingPathComponent("feed-taste.json.bad-1700000000")
        XCTAssertEqual(try Data(contentsOf: backup), Data("not json".utf8), "现场原样保留")
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        XCTAssertTrue(store.save(Taste(), now: now))
    }

    func testCorruptTasteDoesNotFallBackToLegacy() throws {
        let url = try file("feed-taste.json", Data("{".utf8))
        let legacy = try file("legacy.json", Data("{}".utf8))
        XCTAssertEqual(TasteStore(url: url, legacyURL: legacy).load(now: now), Taste())
        XCTAssertEqual(backupNames(url).count, 1)
    }

    func testOversizedCurationIsBackedUp() throws {
        let url = try file("feed-curation.json", Data(repeating: 0x20, count: Preferences.maxFileBytes + 1))
        let store = CurationStore(url: url)
        XCTAssertEqual(store.load(now: now), Curation())
        XCTAssertEqual(backupNames(url), ["feed-curation.json.bad-1700000000"])
        XCTAssertTrue(store.save(Curation().favoriting("a")))
        XCTAssertEqual(store.load(now: now).favorites, ["a"])
    }

    func testCorruptLedgerIsBackedUp() throws {
        let url = try file("watch-ledger.json", Data("[]".utf8))
        let store = WatchLedgerStore(url: url)
        XCTAssertEqual(store.load(now: now), WatchLedger())
        XCTAssertEqual(backupNames(url).count, 1)
        XCTAssertTrue(store.save(WatchLedger()))
    }

    func testRenameFailureMakesStoresReadOnly() throws {
        let bad = Data("broken".utf8)
        let taste = TasteStore(url: try file("feed-taste.json", bad))
        let curation = CurationStore(url: try file("feed-curation.json", bad))
        let ledger = WatchLedgerStore(url: try file("watch-ledger.json", bad))
        taste.moveItem = failingMove
        curation.moveItem = failingMove
        ledger.moveItem = failingMove

        XCTAssertEqual(taste.load(now: now), Taste())
        XCTAssertEqual(curation.load(now: now), Curation())
        XCTAssertEqual(ledger.load(now: now), WatchLedger())
        XCTAssertTrue(taste.isReadOnly && curation.isReadOnly && ledger.isReadOnly)
        XCTAssertFalse(taste.save(Taste(), now: now))
        XCTAssertFalse(curation.save(Curation().blocking("x")))
        XCTAssertFalse(ledger.save(WatchLedger()))
        for name in ["feed-taste.json", "feed-curation.json", "watch-ledger.json"] {
            XCTAssertEqual(try Data(contentsOf: directory.appendingPathComponent(name)), bad, "\(name) 未被覆盖")
        }
    }

    func testReadOnlyClearsAfterGoodLoadOrErase() throws {
        let url = try file("watch-ledger.json", Data("broken".utf8))
        let ledger = WatchLedgerStore(url: url)
        ledger.moveItem = failingMove
        _ = ledger.load(now: now)
        XCTAssertTrue(ledger.isReadOnly)
        XCTAssertTrue(ledger.erase(), "用户主动清除仍可执行")
        XCTAssertFalse(ledger.isReadOnly)
        XCTAssertTrue(ledger.save(WatchLedger()))

        let curationURL = try file("feed-curation.json", Data("broken".utf8))
        let curation = CurationStore(url: curationURL)
        curation.moveItem = failingMove
        _ = curation.load(now: now)
        try JSONEncoder().encode(Curation().favoriting("ok")).write(to: curationURL)
        XCTAssertEqual(curation.load(now: now).favorites, ["ok"])
        XCTAssertFalse(curation.isReadOnly)
    }

    func testMissingFileIsNotBackedUpAndSavable() {
        let url = directory.appendingPathComponent("feed-curation.json")
        let store = CurationStore(url: url)
        XCTAssertEqual(store.load(now: now), Curation())
        XCTAssertFalse(store.isReadOnly)
        XCTAssertTrue(backupNames(url).isEmpty)
        XCTAssertTrue(store.save(Curation()))
    }

    // MARK: 备份数量与命名

    func testKeepsAtMostThreeNewestBackups() throws {
        let url = directory.appendingPathComponent("feed-taste.json")
        for epoch in [900, 1_000, 800] {
            try Data("old".utf8).write(to: directory.appendingPathComponent("feed-taste.json.bad-\(epoch)"))
        }
        try Data("x".utf8).write(to: url)
        XCTAssertTrue(PreferenceQuarantine.quarantine(url, now: now))
        XCTAssertEqual(backupNames(url), [
            "feed-taste.json.bad-900", "feed-taste.json.bad-1000", "feed-taste.json.bad-1700000000"
        ], "删最旧的 800，按时间而不是字典序")
    }

    func testSameSecondGetsSuffixAndOtherFilesUntouched() throws {
        let url = directory.appendingPathComponent("feed-taste.json")
        let other = try file("feed-curation.json.bad-1", Data("keep".utf8))
        for _ in 0..<2 {
            try Data("x".utf8).write(to: url)
            XCTAssertTrue(PreferenceQuarantine.quarantine(url, now: now))
        }
        XCTAssertEqual(backupNames(url), ["feed-taste.json.bad-1700000000", "feed-taste.json.bad-1700000000-1"])
        XCTAssertTrue(FileManager.default.fileExists(atPath: other.path), "别的文件的备份不受影响")
    }

    func testQuarantineFailureLeavesFileInPlace() throws {
        let url = try file("feed-taste.json", Data("x".utf8))
        XCTAssertFalse(PreferenceQuarantine.quarantine(url, now: now, move: failingMove))
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        XCTAssertTrue(backupNames(url).isEmpty)
    }
}
