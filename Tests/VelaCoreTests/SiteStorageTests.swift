import XCTest
@testable import VelaCore

final class SiteStorageTests: XCTestCase {
    func testCurationAcceptsThreeSpellingsAndEncodesRaw() throws {
        let curation = Curation(favorites: ["Alice"], blocked: []).favoriting("c4:Bob")
        XCTAssertTrue(curation.isFavorite("alice"))
        XCTAssertTrue(curation.isFavorite("cb:Alice"))
        XCTAssertTrue(curation.isFavorite("CB:alice"))
        XCTAssertTrue(curation.isFavorite("c4:bob"))
        XCTAssertFalse(curation.isFavorite("c4:alice"))
        let data = try JSONEncoder().encode(curation)
        let text = String(decoding: data, as: UTF8.self)
        XCTAssertTrue(text.contains("\"keyVersion\":2"))
        XCTAssertTrue(text.contains("cb:Alice"))
        XCTAssertTrue(text.contains("c4:bob"))
        let decoded = try JSONDecoder().decode(Curation.self, from: data)
        XCTAssertEqual(decoded.favorites, curation.favorites)
        XCTAssertTrue(decoded.isFavorite("alice"))

        let legacy = Data(#"{"favorites":["Alice","cb:Carol","c4:Bob"],"blocked":["dave"]}"#.utf8)
        let old = try JSONDecoder().decode(Curation.self, from: legacy)
        XCTAssertTrue(old.isFavorite("alice"))
        XCTAssertTrue(old.isFavorite("carol"))
        XCTAssertTrue(old.isFavorite("c4:bob"))
        XCTAssertTrue(old.isBlocked("dave"))
        XCTAssertFalse(old.isBlocked("c4:dave"))
    }

    func testFavoriteExchangeWritesRawAndReadsBoth() throws {
        let data = try FavoriteExchange.encode(["alpha", "c4:sample_one"])
        let text = String(decoding: data, as: UTF8.self)
        XCTAssertTrue(text.contains("cb:alpha"))
        XCTAssertTrue(text.contains("c4:sample_one"))
        XCTAssertEqual(try FavoriteExchange.decode(data).names, ["alpha", "c4:sample_one"])
        let mixed = Data(#"["ok_1"," cb:padded ","c4:sample_two","bad name","x"]"#.utf8)
        XCTAssertEqual(try FavoriteExchange.decode(mixed), .init(names: ["ok_1", "padded", "c4:sample_two"], rejected: 2))
    }

    func testTasteAndLedgerRoundTripOldAndNewKeys() throws {
        var taste = Taste().marking(seen: "Alice", now: Date(timeIntervalSince1970: 50))
        taste = taste.marking(seen: "c4:Bob", now: Date(timeIntervalSince1970: 80))
        let encoded = try JSONEncoder().encode(taste)
        XCTAssertTrue(String(decoding: encoded, as: UTF8.self).contains("cb:alice"))
        XCTAssertTrue(String(decoding: encoded, as: UTF8.self).contains("c4:bob"))
        let back = try JSONDecoder().decode(Taste.self, from: encoded)
        XCTAssertEqual(back.lastSeen("cb:Alice"), taste.lastSeen("alice"))
        XCTAssertEqual(back.lastSeen("c4:bob"), taste.lastSeen("C4:Bob"))
        let legacy = Data(#"{"seen":{"alice":50,"c4:bob":80},"beliefs":{"tag:talk":{"hit":1,"miss":0,"at":1,"hits":1,"misses":0,"vetoed":false}}}"#.utf8)
        let old = try JSONDecoder().decode(Taste.self, from: legacy)
        XCTAssertEqual(old.lastSeen("CB:alice"), Date(timeIntervalSince1970: 50))
        XCTAssertEqual(old.beliefs["tag:talk"]?.hit, 1)

        var ledger = WatchLedger().recording(username: "alice", seconds: 120, start: 10, now: 130)
        ledger = ledger.recording(username: "c4:bob", seconds: 30, start: 200, now: 230)
        let ledgerData = try JSONEncoder().encode(ledger)
        XCTAssertEqual(try JSONDecoder().decode(WatchLedger.self, from: ledgerData), ledger)
        XCTAssertTrue(String(decoding: ledgerData, as: UTF8.self).contains("cb:alice"))
        let oldLedger = Data(#"{"entries":{"alice":{"totalSeconds":120,"visitCount":1,"lastVisitStart":10,"suggested":false,"lastUsed":130},"cb:alice":{"totalSeconds":5,"visitCount":0,"suggested":false,"lastUsed":1}}}"#.utf8)
        let merged = try JSONDecoder().decode(WatchLedger.self, from: oldLedger)
        XCTAssertEqual(merged.entries.count, 1)
        XCTAssertEqual(merged.entries["alice"]?.totalSeconds, 120)
    }

    func testFeedAndLanesKeepCam4BesideTheSameChaturbateName() {
        let cb = Room(username: "alice", viewers: 1, show: .publicShow)
        let c4 = Room(username: "alice", viewers: 9, show: .publicShow, site: .cam4)
        let bob = Room(username: "bob", viewers: 3, show: .publicShow)
        var store = FeedStore()
        store.replace(with: CatalogPage(rooms: [cb, c4, bob], total: 3, nextOffset: 3))
        XCTAssertEqual(store.rooms.map(\.id), ["alice", "c4:alice", "bob"])
        store.setBlocked(["alice"])
        XCTAssertEqual(store.rooms.map(\.id), ["c4:alice", "bob"])
        XCTAssertFalse(RoomQuery(blocked: ["alice"]).matches(cb))
        XCTAssertTrue(RoomQuery(blocked: ["alice"]).matches(c4))
        XCTAssertFalse(RoomQuery(blocked: ["c4:alice"]).matches(c4))
        XCTAssertTrue(RoomQuery(blocked: ["cb:alice"]).matches(c4))
        store.setBlocked([])
        store.setQuery(RoomQuery(text: "c4:"))
        XCTAssertEqual(store.rooms.map(\.id), ["c4:alice"])
        store.setQuery(RoomQuery())
        XCTAssertTrue(store.focus("alice"))
        XCTAssertEqual(store.step(1)?.id, "c4:alice")
        XCTAssertTrue(store.reveal(bob))
        XCTAssertEqual(store.current?.id, "bob")
        XCTAssertEqual(Set(store.rooms.map(\.id)), ["alice", "c4:alice", "bob"])

        let names = ["alice", "c4:alice", "bob", "cara"]
        let before = LaneSelection.Snapshot(
            rooms: names,
            primary: LaneCursor(username: "alice"),
            secondary: LaneCursor(username: "c4:alice"),
            tertiary: LaneCursor(username: "bob"),
            focus: .primary
        )
        let moved = LaneSelection.resolve(before: before, afterRooms: names, proposedPrimary: nil, intent: .move(.primary, 1))
        XCTAssertEqual(
            [moved.primary.username, moved.secondary?.username, moved.tertiary?.username],
            ["cara", "c4:alice", "bob"]
        )
        let blocked = LaneSelection.resolve(
            before: before, afterRooms: ["c4:alice", "bob", "cara"], proposedPrimary: "c4:alice", intent: .filter
        )
        XCTAssertEqual(
            [blocked.primary.username, blocked.secondary?.username, blocked.tertiary?.username],
            ["cara", "c4:alice", "bob"]
        )
        let searched = LaneSelection.resolve(
            before: before, afterRooms: ["c4:alice", "cara"], proposedPrimary: "c4:alice", intent: .searchInstall
        )
        XCTAssertEqual(searched.primary.username, "c4:alice")
        XCTAssertEqual(searched.secondary?.username, "cara")
        XCTAssertNil(searched.tertiary?.username)
        XCTAssertNotEqual(searched.primary.username, searched.secondary?.username)
        let revealed = LaneSelection.resolve(
            before: before, afterRooms: names, proposedPrimary: "c4:alice", intent: .reveal("c4:alice")
        )
        XCTAssertEqual(revealed.focus, .secondary)
        XCTAssertEqual(
            [revealed.primary.username, revealed.secondary?.username, revealed.tertiary?.username],
            ["alice", "c4:alice", "bob"]
        )
    }
}

final class SiteKeyMigrationTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testMissingFilesAreSkipped() {
        let report = SiteKeyMigration.run(curation: file("feed-curation.json"), taste: file("feed-taste.json"), ledger: file("watch-ledger.json"))
        XCTAssertTrue(report.migrated.isEmpty)
        XCTAssertEqual(report.skipped.count, 3)
        XCTAssertTrue(report.failed.isEmpty)
    }

    func testOldFileMigratesOnceAndSecondRunDoesNotTouchBytes() throws {
        let curation = file("feed-curation.json")
        let taste = file("feed-taste.json")
        let ledger = file("watch-ledger.json")
        try Data(#"{"favorites":["Alice","c4:Bob"],"blocked":["dave"]}"#.utf8).write(to: curation)
        try Data(#"{"seen":{"alice":50},"paused":false}"#.utf8).write(to: taste)
        try Data(#"{"entries":{"alice":{"totalSeconds":12,"visitCount":1,"lastVisitStart":1,"suggested":false,"lastUsed":12}}}"#.utf8).write(to: ledger)
        let original = try Data(contentsOf: curation)
        let first = SiteKeyMigration.run(curation: curation, taste: taste, ledger: ledger)
        XCTAssertEqual(Set(first.migrated.map(\.lastPathComponent)), ["feed-curation.json", "feed-taste.json", "watch-ledger.json"])
        let migrated = try Data(contentsOf: curation)
        let object = try JSONSerialization.jsonObject(with: migrated) as? [String: Any]
        XCTAssertEqual(object?["keyVersion"] as? Int ?? (object?["keyVersion"] as? NSNumber)?.intValue, 2)
        let loaded = try JSONDecoder().decode(Curation.self, from: migrated)
        XCTAssertTrue(loaded.isFavorite("alice"))
        XCTAssertTrue(loaded.isFavorite("c4:bob"))
        XCTAssertTrue(loaded.isBlocked("dave"))
        XCTAssertEqual(try JSONDecoder().decode(Taste.self, from: Data(contentsOf: taste)).lastSeen("cb:alice"), Date(timeIntervalSince1970: 50))
        XCTAssertEqual(try JSONDecoder().decode(WatchLedger.self, from: Data(contentsOf: ledger)).entries["alice"]?.totalSeconds, 12)
        let bytes = try Data(contentsOf: curation)
        let second = SiteKeyMigration.run(curation: curation, taste: taste, ledger: ledger)
        XCTAssertTrue(second.migrated.isEmpty)
        XCTAssertEqual(second.skipped.count, 3)
        XCTAssertEqual(try Data(contentsOf: curation), bytes)
        let backup = SiteKeyMigration.backupURL(for: curation)
        XCTAssertEqual(try Data(contentsOf: backup), original)
        try Data("changed".utf8).write(to: curation)
        let third = SiteKeyMigration.run(curation: curation, taste: taste, ledger: ledger)
        XCTAssertTrue(third.skipped.contains(curation), "已有 keyVersion 不再迁移；这里文件已不是 JSON，同样跳过")
        // 备份在第一次之后就不再覆盖。上面把原文件改坏之前备份仍是最初字节。
        XCTAssertEqual(try Data(contentsOf: backup), original)
    }

    func testBackupIsCreatedOnce() throws {
        let curation = file("feed-curation.json")
        try Data(#"{"favorites":["alice"],"blocked":[]}"#.utf8).write(to: curation)
        _ = SiteKeyMigration.run(curation: curation, taste: file("missing-taste.json"), ledger: file("missing-ledger.json"))
        let backup = SiteKeyMigration.backupURL(for: curation)
        let firstBackup = try Data(contentsOf: backup)
        _ = SiteKeyMigration.run(curation: curation, taste: file("missing-taste.json"), ledger: file("missing-ledger.json"))
        XCTAssertEqual(try Data(contentsOf: backup), firstBackup)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path).filter { $0.contains("pre-sites") }.count, 1)
    }

    func testCopyFailureLeavesTheOriginalAlone() throws {
        let curation = file("feed-curation.json")
        let original = Data(#"{"favorites":["alice"],"blocked":[]}"#.utf8)
        try original.write(to: curation)
        let report = SiteKeyMigration.run(
            curation: curation, taste: file("no-taste.json"), ledger: file("no-ledger.json"),
            copy: { _, _ in throw CocoaError(.fileWriteNoPermission) }
        )
        XCTAssertEqual(report.failed, [curation])
        XCTAssertEqual(try Data(contentsOf: curation), original)
        XCTAssertFalse(FileManager.default.fileExists(atPath: SiteKeyMigration.backupURL(for: curation).path))
    }

    func testWriteFailureKeepsOriginalAndBackup() throws {
        let curation = file("feed-curation.json")
        let original = Data(#"{"favorites":["alice"],"blocked":[]}"#.utf8)
        try original.write(to: curation)
        let report = SiteKeyMigration.run(
            curation: curation, taste: file("no-taste.json"), ledger: file("no-ledger.json"),
            write: { _, _ in throw CocoaError(.fileWriteNoPermission) }
        )
        XCTAssertEqual(report.failed, [curation])
        XCTAssertEqual(try Data(contentsOf: curation), original)
        XCTAssertEqual(try Data(contentsOf: SiteKeyMigration.backupURL(for: curation)), original)
    }

    func testBrokenAndOversizeFilesAreLeftForQuarantine() throws {
        let broken = file("feed-curation.json")
        try Data("broken".utf8).write(to: broken)
        let report = SiteKeyMigration.run(curation: broken, taste: file("no-taste.json"), ledger: file("no-ledger.json"))
        XCTAssertEqual(report.skipped.first, broken)
        XCTAssertEqual(try Data(contentsOf: broken), Data("broken".utf8))
        XCTAssertFalse(FileManager.default.fileExists(atPath: SiteKeyMigration.backupURL(for: broken).path))
        let quarantined = PreferenceQuarantine.read(Curation.self, from: broken, limit: Preferences.maxFileBytes, now: Date(timeIntervalSince1970: 10))
        if case .quarantined = quarantined {} else { XCTFail("坏文件应走隔离") }
        XCTAssertFalse(FileManager.default.fileExists(atPath: broken.path))

        let huge = file("feed-taste.json")
        try Data(repeating: 0x20, count: Preferences.maxFileBytes + 1).write(to: huge)
        let skipped = SiteKeyMigration.run(curation: file("absent.json"), taste: huge, ledger: file("absent-ledger.json"))
        XCTAssertTrue(skipped.skipped.contains(huge))
        XCTAssertEqual(try Data(contentsOf: huge).count, Preferences.maxFileBytes + 1)
    }

    func testRollbackRestoresOriginalBytes() throws {
        let curation = file("feed-curation.json")
        let taste = file("feed-taste.json")
        let ledger = file("watch-ledger.json")
        let original = Data(#"{"favorites":["Alice"],"blocked":[]}"#.utf8)
        try original.write(to: curation)
        try Data(#"{"seen":{"alice":1}}"#.utf8).write(to: taste)
        try Data(#"{"entries":{}}"#.utf8).write(to: ledger)
        _ = SiteKeyMigration.run(curation: curation, taste: taste, ledger: ledger)
        XCTAssertNotEqual(try Data(contentsOf: curation), original)
        let rolled = SiteKeyMigration.rollback(curation: curation, taste: taste, ledger: ledger)
        XCTAssertEqual(rolled.migrated.count, 3)
        XCTAssertEqual(try Data(contentsOf: curation), original)
        XCTAssertTrue(SiteKeyMigration.removeBackups(curation: curation, taste: taste, ledger: ledger))
        XCTAssertFalse(FileManager.default.fileExists(atPath: SiteKeyMigration.backupURL(for: curation).path))
    }

    func testMixedOldAndNewKeys() throws {
        let curation = file("feed-curation.json")
        try Data(#"{"favorites":["Alice","cb:Carol","c4:Bob","c4:bob"],"blocked":["cb:dave"]}"#.utf8).write(to: curation)
        let report = SiteKeyMigration.run(curation: curation, taste: file("no-taste.json"), ledger: file("no-ledger.json"))
        XCTAssertEqual(report.migrated, [curation])
        let loaded = try JSONDecoder().decode(Curation.self, from: Data(contentsOf: curation))
        XCTAssertTrue(loaded.isFavorite("alice"))
        XCTAssertTrue(loaded.isFavorite("carol"))
        XCTAssertTrue(loaded.isFavorite("c4:bob"))
        XCTAssertEqual(loaded.favorites.filter { RoomKey.normalizeRef($0) == "c4:bob" }.count, 1)
        XCTAssertTrue(loaded.isBlocked("dave"))
        XCTAssertFalse(loaded.isBlocked("c4:dave"))
    }

    private func file(_ name: String) -> URL { directory.appendingPathComponent(name) }
}
