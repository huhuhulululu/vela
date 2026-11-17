import XCTest
@testable import VelaCore

final class WatchLedgerTests: XCTestCase {
    private let t0: TimeInterval = 1_700_000_000

    func testTotalTenMinutesTriggersSuggest() {
        var ledger = WatchLedger()
        let start = t0
        ledger = ledger.recording(username: "alice", seconds: 400, start: start, now: t0 + 400)
        XCTAssertFalse(ledger.shouldSuggest(username: "alice", favorited: false, blocked: false))
        ledger = ledger.recording(username: "alice", seconds: 250, start: t0 + 5000, now: t0 + 5250)
        XCTAssertTrue(ledger.shouldSuggest(username: "alice", favorited: false, blocked: false))
    }

    func testLiveSecondsReachTenMinutes() {
        var ledger = WatchLedger()
        ledger = ledger.recording(username: "bob", seconds: 500, start: t0, now: t0 + 500)
        XCTAssertTrue(
            ledger.shouldSuggest(username: "bob", favorited: false, blocked: false, liveSeconds: 100, liveStart: t0 + 600)
        )
    }

    func testThreeVisitsWithGapAndDuration() {
        var ledger = WatchLedger()
        ledger = ledger.recording(username: "c", seconds: 70, start: t0, now: t0 + 70)
        XCTAssertFalse(ledger.shouldSuggest(username: "c", favorited: false, blocked: false))
        ledger = ledger.recording(username: "c", seconds: 80, start: t0 + 2000, now: t0 + 2080)
        XCTAssertFalse(ledger.shouldSuggest(username: "c", favorited: false, blocked: false))
        ledger = ledger.recording(username: "c", seconds: 90, start: t0 + 4000, now: t0 + 4090)
        XCTAssertTrue(ledger.shouldSuggest(username: "c", favorited: false, blocked: false))
    }

    func testVisitGapBelowThirtyMinutesDoesNotIncrement() {
        var ledger = WatchLedger()
        ledger = ledger.recording(username: "d", seconds: 90, start: t0, now: t0 + 90)
        XCTAssertEqual(ledger.entries["d"]?.visitCount, 1)
        ledger = ledger.recording(username: "d", seconds: 90, start: t0 + 600, now: t0 + 690)
        XCTAssertEqual(ledger.entries["d"]?.visitCount, 1)
        ledger = ledger.recording(username: "d", seconds: 90, start: t0 + 1900, now: t0 + 1990)
        XCTAssertEqual(ledger.entries["d"]?.visitCount, 2)
    }

    func testVisitUnderSixtySecondsDoesNotCount() {
        var ledger = WatchLedger()
        ledger = ledger.recording(username: "e", seconds: 59, start: t0, now: t0 + 59)
        XCTAssertEqual(ledger.entries["e"]?.visitCount, 0)
        XCTAssertEqual(ledger.entries["e"]?.totalSeconds, 59)
    }

    func testFavoritedBlockedOrSuggestedDoNotTrigger() {
        var ledger = WatchLedger()
        ledger = ledger.recording(username: "f", seconds: 700, start: t0, now: t0 + 700)
        XCTAssertFalse(ledger.shouldSuggest(username: "f", favorited: true, blocked: false))
        XCTAssertFalse(ledger.shouldSuggest(username: "f", favorited: false, blocked: true))
        ledger = ledger.markingSuggested("f", now: t0 + 800)
        XCTAssertFalse(ledger.shouldSuggest(username: "f", favorited: false, blocked: false))
    }

    func testMarkingSuggested() {
        var ledger = WatchLedger()
        ledger = ledger.markingSuggested("g", now: t0)
        XCTAssertTrue(ledger.entries["g"]?.suggested == true)
    }

    func testCapsAtFiveHundredEntries() {
        var ledger = WatchLedger()
        for index in 0..<520 {
            let name = String(format: "u%04d", index)
            ledger = ledger.recording(username: name, seconds: 1, start: t0 + Double(index), now: t0 + Double(index))
        }
        XCTAssertLessThanOrEqual(ledger.entries.count, WatchLedger.maxEntries)
        XCTAssertNil(ledger.entries["u0000"])
        XCTAssertNotNil(ledger.entries["u0519"])
    }

    func testRoundTripJSON() throws {
        var ledger = WatchLedger()
        ledger = ledger.recording(username: "h", seconds: 120, start: t0, now: t0 + 120)
        let data = try JSONEncoder().encode(ledger)
        let decoded = try JSONDecoder().decode(WatchLedger.self, from: data)
        XCTAssertEqual(decoded, ledger)
    }

    func testOversizeFileTreatedAsEmpty() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("watch-ledger.json")
        let blob = Data(repeating: 0x20, count: Preferences.maxFileBytes + 1)
        try blob.write(to: url)
        let store = WatchLedgerStore(url: url)
        XCTAssertEqual(store.load(), WatchLedger())
    }

    func testSecondsUntilTotalSuggest() {
        var ledger = WatchLedger()
        ledger = ledger.recording(username: "i", seconds: 100, start: t0, now: t0 + 100)
        XCTAssertEqual(ledger.secondsUntilTotalSuggest(username: "i", favorited: false, blocked: false, liveSeconds: 0, liveStart: nil), 500)
        XCTAssertNil(
            ledger.secondsUntilTotalSuggest(
                username: "i", favorited: false, blocked: false, liveSeconds: 500, liveStart: t0 + 200
            )
        )
    }
}