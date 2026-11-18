import XCTest
@testable import VelaCore

/// 第 6.5、6.6、14.1 节：`settingSyncedVeto`、`trimmed` 平局规则、`lastLoadOutcome`。
final class SyncTasteTests: XCTestCase {
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    // MARK: settingSyncedVeto

    func testSettingSyncedVetoCreatesBareBelief() {
        let t = Taste().settingSyncedVeto("t:asian", true)
        XCTAssertEqual(t.beliefs["t:asian"], Belief(vetoed: true))
    }

    func testSettingSyncedVetoKeepsEvidence() {
        let base = Taste().recording(room: Room(username: "a", tags: ["asian"]), watched: 200, source: .guess, now: now)
        let before = base.beliefs["t:asian"]!
        let vetoed = base.settingSyncedVeto("t:asian", true)
        var expected = before
        expected.vetoed = true
        XCTAssertEqual(vetoed.beliefs["t:asian"], expected)
        let back = vetoed.settingSyncedVeto("t:asian", false)
        XCTAssertEqual(back.beliefs["t:asian"], before, "撤销只改 vetoed，不清证据")
        XCTAssertEqual(back.seen, base.seen)
        XCTAssertEqual(back.daily, base.daily)
    }

    func testSettingSyncedVetoFalseDropsEmptyEntry() {
        let t = Taste().settingSyncedVeto("g:m", true).settingSyncedVeto("g:m", false)
        XCTAssertNil(t.beliefs["g:m"])
        XCTAssertEqual(Taste().settingSyncedVeto("g:m", false), Taste(), "不存在时撤销是空操作")
    }

    func testSettingSyncedVetoIsIdempotent() {
        let once = Taste().settingSyncedVeto("t:x", true)
        XCTAssertEqual(once.settingSyncedVeto("t:x", true), once)
    }

    // MARK: trimmed 平局规则

    func testTrimmedTieBreaksByKeyBytesAscending() {
        var beliefs: [String: Belief] = [:]
        for i in 0..<600 { beliefs[String(format: "t:k%03d", i)] = Belief(hit: 1, at: now.timeIntervalSince1970) }
        let capped = Taste(beliefs: beliefs).capped(now: now)
        let expected = Set((0..<500).map { String(format: "t:k%03d", $0) })
        XCTAssertEqual(Set(capped.beliefs.keys), expected)
    }

    func testTrimmedVetoedTieBreaksByKeyBytes() {
        var beliefs: [String: Belief] = [:]
        for i in 0..<501 { beliefs[String(format: "t:v%03d", i)] = Belief(vetoed: true) }
        let capped = Taste(beliefs: beliefs).capped(now: now)
        XCTAssertNil(capped.beliefs["t:v500"])
        XCTAssertEqual(capped.beliefs.count, 500)
    }

    // MARK: lastLoadOutcome

    func tempDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sync-taste-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        return dir
    }

    func testCurationStoreOutcomeMissingThenLoaded() throws {
        let dir = try tempDir()
        let store = CurationStore(url: dir.appendingPathComponent("c.json"))
        XCTAssertNil(store.lastLoadOutcome)
        _ = store.load(now: now)
        XCTAssertEqual(store.lastLoadOutcome, .missing)
        let other = CurationStore(url: store.url)
        XCTAssertTrue(store.save(Curation(favorites: ["alice"])))
        _ = other.load(now: now)
        XCTAssertEqual(other.lastLoadOutcome, .loaded)
    }

    func testCurationStoreQuarantineOutcomeIsSticky() throws {
        let dir = try tempDir()
        let url = dir.appendingPathComponent("c.json")
        try Data("not json".utf8).write(to: url)
        let store = CurationStore(url: url)
        _ = store.load(now: now)
        XCTAssertEqual(store.lastLoadOutcome, .quarantined)
        _ = store.load(now: now)
        XCTAssertEqual(store.lastLoadOutcome, .quarantined, "之后读到文件不存在也不覆盖")
    }

    func testCurationStoreReadOnlyOutcome() throws {
        let dir = try tempDir()
        let url = dir.appendingPathComponent("c.json")
        try Data("not json".utf8).write(to: url)
        let store = CurationStore(url: url)
        store.moveItem = { _, _ in throw CocoaError(.fileWriteNoPermission) }
        _ = store.load(now: now)
        XCTAssertEqual(store.lastLoadOutcome, .readOnly)
    }

    func testTasteStoreOutcomes() throws {
        let dir = try tempDir()
        let url = dir.appendingPathComponent("t.json")
        let missing = TasteStore(url: url)
        _ = missing.load(now: now)
        XCTAssertEqual(missing.lastLoadOutcome, .missing)
        try Data("{".utf8).write(to: url)
        let bad = TasteStore(url: url)
        _ = bad.load(now: now)
        XCTAssertEqual(bad.lastLoadOutcome, .quarantined)
        _ = bad.load(now: now)
        XCTAssertEqual(bad.lastLoadOutcome, .quarantined)
        XCTAssertTrue(bad.save(Taste()))
        let good = TasteStore(url: url)
        _ = good.load(now: now)
        XCTAssertEqual(good.lastLoadOutcome, .loaded)
    }

    func testTasteStoreReadOnlyOutcome() throws {
        let dir = try tempDir()
        let url = dir.appendingPathComponent("t.json")
        try Data("{".utf8).write(to: url)
        let store = TasteStore(url: url)
        store.moveItem = { _, _ in throw CocoaError(.fileWriteNoPermission) }
        _ = store.load(now: now)
        XCTAssertEqual(store.lastLoadOutcome, .readOnly)
    }
}
