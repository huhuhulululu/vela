import XCTest
@testable import VelaCore

final class FileLimitTests: XCTestCase {
    func tempURL() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("vela-limit-\(UUID().uuidString).json")
    }

    func testOversizedFilesLoadAsEmpty() throws {
        let prefURL = tempURL(), curURL = tempURL()
        defer { try? FileManager.default.removeItem(at: prefURL); try? FileManager.default.removeItem(at: curURL) }
        let pad = String(repeating: " ", count: Preferences.maxFileBytes + 10)
        try Data(("{\"weights\":{},\"seen\":{}}" + pad).utf8).write(to: prefURL)
        try Data(("{\"favorites\":[\"a\"],\"blocked\":[]}" + pad).utf8).write(to: curURL)
        XCTAssertEqual(PreferenceStore(url: prefURL).load(), Preferences())
        XCTAssertEqual(CurationStore(url: curURL).load(), Curation())
    }

    func testNormalFilesStillLoad() {
        let url = tempURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let store = CurationStore(url: url)
        XCTAssertTrue(store.save(Curation(favorites: ["a"])))
        XCTAssertEqual(store.load().favorites, ["a"])
    }

    func testLongNamesAreClipped() {
        let long = String(repeating: "x", count: 500)
        let r = Room(username: long, viewers: 1, tags: [long], show: .publicShow)
        let taste = Taste().recording(room: r, watched: 100, source: .guess, now: Date())
        XCTAssertTrue(taste.beliefs.keys.allSatisfy { $0.count <= Preferences.maxNameLength + 2 })
        XCTAssertTrue(taste.seen.keys.allSatisfy { $0.count <= Preferences.maxNameLength })
        XCTAssertNotNil(taste.lastSeen(long))
        let cur = Curation().favoriting(long)
        XCTAssertEqual(cur.favorites.first?.count, Preferences.maxNameLength)
    }

    func testOversizedKeysDroppedOnLoad() {
        let big = Preferences(weights: [String(repeating: "k", count: 300): Affinity(value: 1, at: 0)],
                              seen: [String(repeating: "s", count: 300): 1e12])
        XCTAssertTrue(big.capped(now: Date(timeIntervalSince1970: 1e12)).weights.isEmpty)
        XCTAssertTrue(big.capped(now: Date(timeIntervalSince1970: 1e12)).seen.isEmpty)
    }
}
