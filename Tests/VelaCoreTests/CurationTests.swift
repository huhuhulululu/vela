import XCTest
@testable import VelaCore

final class CurationTests: XCTestCase {
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    func room(_ name: String, viewers: Int = 100, show: ShowState = .publicShow) -> Room {
        Room(username: name, viewers: viewers, gender: .female, show: show)
    }

    func names(_ rooms: [Room]) -> [String] { rooms.map(\.username) }

    // MARK: 收藏与屏蔽

    func testFavoriteAndBlockAreMutuallyExclusiveAndCaseInsensitive() {
        var c = Curation().favoriting("Alice")
        XCTAssertTrue(c.isFavorite("alice"))
        c = c.blocking("ALICE")
        XCTAssertFalse(c.isFavorite("Alice"))
        XCTAssertTrue(c.isBlocked("alice"))
        c = c.favoriting("alice")
        XCTAssertTrue(c.isFavorite("Alice"))
        XCTAssertFalse(c.isBlocked("alice"))
        XCTAssertEqual(c.unfavoriting("ALICE").favorites, [])
        XCTAssertEqual(c.blocking("bob").unblocking("BOB").blocked, [])
    }

    func testNewestFirstDedupeAndIgnoreBlank() {
        let c = Curation().favoriting("a").favoriting("b").favoriting("A").favoriting("  ")
        XCTAssertEqual(c.favorites, ["A", "b"])
    }

    func testCapsKeepNewest() {
        var c = Curation()
        for i in 0..<(Curation.maxEntries + 20) { c = c.blocking("u\(i)") }
        XCTAssertEqual(c.blocked.count, Curation.maxEntries)
        XCTAssertEqual(c.blocked.first, "u\(Curation.maxEntries + 19)")
        XCTAssertFalse(c.isBlocked("u0"))
        let big = Curation(favorites: (0..<1_000).map { "f\($0)" }, blocked: [])
        XCTAssertEqual(big.favorites.count, Curation.maxEntries)
    }

    // MARK: 持久化

    func tempURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("vela-curation-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent("feed-curation.json")
    }

    func testStoreRoundTripAndCapOnLoad() throws {
        let url = tempURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = CurationStore(url: url)
        XCTAssertEqual(store.load(), Curation())
        let saved = Curation().favoriting("x").blocking("y")
        XCTAssertTrue(store.save(saved))
        XCTAssertEqual(store.load(), saved)

        let oversize = (0..<1_000).map { "n\($0)" }
        let json = try JSONSerialization.data(withJSONObject: ["favorites": oversize, "blocked": ["n1"]])
        try json.write(to: url)
        let loaded = store.load()
        XCTAssertLessThanOrEqual(loaded.favorites.count, Curation.maxEntries)
        XCTAssertFalse(loaded.isFavorite("n1"), "屏蔽优先，互斥在读取时也成立")
    }

    func testStoreSurvivesCorruptFileAndUnwritablePath() throws {
        let url = tempURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("not json".utf8).write(to: url)
        XCTAssertEqual(CurationStore(url: url).load(), Curation())

        let blocker = url.deletingLastPathComponent().appendingPathComponent("file")
        try Data().write(to: blocker)
        let bad = CurationStore(url: blocker.appendingPathComponent("sub/c.json"))
        XCTAssertFalse(bad.save(Curation().blocking("z")))
    }

    // MARK: 过滤

    func testBlockedRoomsAreHiddenFromEverySourceAndFutureAppends() {
        var store = FeedStore(query: RoomQuery(blocked: ["bad"]))
        store.replace(with: CatalogPage(rooms: [room("a"), room("Bad"), room("c")], total: 3, nextOffset: 3))
        XCTAssertEqual(names(store.rooms), ["a", "c"])
        store.append(CatalogPage(rooms: [room("BAD"), room("d")], total: 5, nextOffset: 5))
        XCTAssertEqual(names(store.rooms), ["a", "c", "d"])
        store.setBlocked([])
        XCTAssertEqual(names(store.rooms), ["a", "Bad", "c", "d"])
    }

    func testBlockingCurrentMovesToNextThenPrevious() {
        var store = FeedStore(query: RoomQuery())
        store.replace(with: CatalogPage(rooms: [room("a"), room("b"), room("c")], total: 3, nextOffset: 3))
        store.focus("b")
        store.setBlocked(["b"])
        XCTAssertEqual(store.currentUsername, "c")
        store.setBlocked(["b", "c"])
        XCTAssertEqual(store.currentUsername, "a")
        store.setBlocked(["a", "b", "c"])
        XCTAssertNil(store.currentUsername)
        XCTAssertNil(store.current)
    }

    func testBlockingOtherRoomKeepsCurrent() {
        var store = FeedStore(query: RoomQuery())
        store.replace(with: CatalogPage(rooms: [room("a"), room("b"), room("c")], total: 3, nextOffset: 3))
        store.focus("b")
        store.setBlocked(["c"])
        XCTAssertEqual(store.currentUsername, "b")
    }

    // MARK: 加权

    func testFavoriteOutranksHotterRoomsAndSurvivesSeenPenalty() {
        let hot = room("hot", viewers: 20_000)
        let fav = room("Fav", viewers: 10)
        let favorites: Set<String> = ["fav"]
        let seen = Taste().marking(seen: "fav", now: now.addingTimeInterval(-60)).marking(seen: "hot", now: now.addingTimeInterval(-60))
        // 没有口味证据时两者样本同分布：收藏加分应在绝大多数种子下排第一，刚看过也一样。
        for taste in [Taste(), seen] {
            let wins = (1...100).filter {
                Recommender.arrange([hot, fav], source: .guess, taste: taste, now: now, seed: UInt64($0), favorites: favorites).first?.username == "Fav"
            }.count
            XCTAssertGreaterThan(wins, 95)
        }
        let popular = Recommender.arrange([hot, fav], source: .popular, taste: Taste(), now: now, seed: 1, favorites: favorites)
        XCTAssertEqual(names(popular), ["hot", "Fav"])
    }
}

final class RestoreFavoriteTests: XCTestCase {
    /// C1：撤销移除只恢复收藏；之后被屏蔽的名字不能被撤销解除屏蔽。
    func testRestoreDoesNotUnblock() {
        let start = Curation(favorites: ["A", "B"])
        let removed = start.unfavoriting("A")
        let blocked = removed.blocking("A")
        XCTAssertFalse(blocked.canRestoreFavorite("A"))
        XCTAssertEqual(blocked.restoringFavorite("A"), blocked)
        XCTAssertTrue(blocked.isBlocked("A"))
    }

    func testRestoreBringsBackFavoriteAtFront() {
        let removed = Curation(favorites: ["A", "B"]).unfavoriting("A")
        XCTAssertTrue(removed.canRestoreFavorite("a"))
        XCTAssertEqual(removed.restoringFavorite("A").favorites, ["A", "B"])
    }

    func testRestoreVoidWhenReFavorited() {
        let again = Curation(favorites: ["B"]).favoriting("A")
        XCTAssertFalse(again.canRestoreFavorite("A"))
        XCTAssertEqual(again.restoringFavorite("A"), again)
    }
}

final class FavoriteRetryGateTests: XCTestCase {
    /// C2：失败后 60 秒内不自动补查；到点、成功或手动刷新（reset）后放行。
    func testCooldownAfterFailure() {
        var gate = FavoriteRetryGate()
        let t0 = Date(timeIntervalSince1970: 1000)
        XCTAssertTrue(gate.allowsAutoCheck(now: t0))
        gate.recordFailure(at: t0)
        XCTAssertFalse(gate.allowsAutoCheck(now: t0.addingTimeInterval(59)))
        XCTAssertTrue(gate.allowsAutoCheck(now: t0.addingTimeInterval(60)))
        gate.reset()
        XCTAssertTrue(gate.allowsAutoCheck(now: t0.addingTimeInterval(1)))
    }

    /// G2：失败判定只看本批计划内的名字；批次进行中新加的收藏不算缺失，不触发冷却。
    func testUnresolvedOnlyConsidersPlannedNames() {
        let online = FavoriteStatus.offline
        XCTAssertFalse(FavoriteResolver.hasUnresolved(pending: ["Alice"], statuses: ["alice": online]))
        XCTAssertTrue(FavoriteResolver.hasUnresolved(pending: ["Alice", "Bob"], statuses: ["alice": online]))
        // 新加的 carol 不在计划里：本批全部拿到就不算未完成。
        XCTAssertFalse(FavoriteResolver.hasUnresolved(pending: ["Alice"], statuses: ["alice": online]))
        XCTAssertFalse(FavoriteResolver.hasUnresolved(pending: [], statuses: [:]))
    }

    /// G2：不记失败时，新加名字在下次打开收藏夹时允许自动补查。
    func testNoFailureRecordedKeepsAutoCheckOpen() {
        var gate = FavoriteRetryGate()
        let t0 = Date(timeIntervalSince1970: 1000)
        let incomplete = FavoriteResolver.hasUnresolved(pending: ["a"], statuses: ["a": .offline])
        if incomplete { gate.recordFailure(at: t0) } else { gate.reset() }
        XCTAssertTrue(gate.allowsAutoCheck(now: t0.addingTimeInterval(1)))
    }
}
