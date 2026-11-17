import XCTest
@testable import VelaCore

final class LoadCommitTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 3_000_000)

    private func page(_ names: [String]) -> CatalogPage {
        let rooms = names.map { Room(username: $0, viewers: 5, gender: .female, tags: ["x"], show: .publicShow) }
        return CatalogPage(rooms: rooms, total: rooms.count, nextOffset: rooms.count)
    }

    // Q2：被作废的旧响应不改目录快照，也不写推荐理由。
    func testStaleResponseLeavesNoTrace() {
        var catalog = KnownCatalog()
        catalog.record(page(["keep"]).rooms, firstPage: true, at: now)
        var reasons = ["keep": "旧理由"]
        let applied = LoadCommit.apply(
            raw: page(["old"]), arranged: page(["old"]), offset: 0, source: .guess,
            token: 3, current: 4, replacing: true, now: now.addingTimeInterval(500),
            catalog: &catalog, reasons: &reasons, reason: { _ in "新理由" }
        )
        XCTAssertFalse(applied)
        XCTAssertEqual(catalog.rooms.map(\.username), ["keep"])
        XCTAssertEqual(catalog.entries.first?.at, now, "旧房间时效也不能被续期")
        XCTAssertEqual(reasons, ["keep": "旧理由"])
    }

    func testFreshResponseRecordsCatalogAndReasons() {
        var catalog = KnownCatalog()
        var reasons = ["gone": "x"]
        let applied = LoadCommit.apply(
            raw: page(["a", "b"]), arranged: page(["b"]), offset: 0, source: .guess,
            token: 4, current: 4, replacing: true, now: now,
            catalog: &catalog, reasons: &reasons, reason: { "r-\($0.username)" }
        )
        XCTAssertTrue(applied)
        XCTAssertEqual(catalog.rooms.map(\.username), ["a", "b"], "目录快照按原始页记")
        XCTAssertEqual(reasons, ["b": "r-b"])
    }

    func testFavoritesSourceDoesNotRecordCatalogOrReasons() {
        var catalog = KnownCatalog()
        var reasons: [String: String] = [:]
        LoadCommit.apply(
            raw: page(["a"]), arranged: page(["a"]), offset: 0, source: .favorites,
            token: 1, current: 1, replacing: true, now: now,
            catalog: &catalog, reasons: &reasons, reason: { _ in "r" }
        )
        XCTAssertTrue(catalog.rooms.isEmpty)
        XCTAssertTrue(reasons.isEmpty)
    }

    // Q1：列表整体替换时归属新来源，否则仍是已加载的来源（不跟着用户刚选的来源走）。
    func testDwellOriginFollowsLoadedListNotSelection() {
        XCTAssertEqual(ListOrigin.dwell(loaded: .popular, installing: nil), .popular)
        XCTAssertEqual(ListOrigin.dwell(loaded: .popular, installing: .guess), .guess)
    }
}
