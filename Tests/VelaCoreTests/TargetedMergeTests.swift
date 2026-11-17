import XCTest
@testable import VelaCore

final class TargetedMergeTests: XCTestCase {
    private func room(_ name: String, secs: Int? = nil) -> Room {
        Room(username: name, viewers: 10, gender: .female, tags: ["x"], show: .publicShow, secondsOnline: secs)
    }

    // M2：常规分页整页都是已有房间（定向候选提前进了目录），只要游标仍在前进就不算到底。
    func testFullyDuplicatePageDoesNotEndWhileCursorAdvances() {
        var feed = FeedStore()
        feed.replace(with: CatalogPage(rooms: [room("a"), room("b"), room("t1"), room("t2")], total: 10, nextOffset: 2))
        feed.append(CatalogPage(rooms: [room("t1"), room("t2")], total: 10, nextOffset: 4), from: 2)
        XCTAssertFalse(feed.reachedEnd)
        XCTAssertEqual(feed.catalog.map(\.username), ["a", "b", "t1", "t2"])
        XCTAssertTrue(feed.shouldLoadMore(threshold: 8))
    }

    func testEndsOnlyByCursorTotalEmptyOrStall() {
        var atTotal = FeedStore()
        atTotal.replace(with: CatalogPage(rooms: [room("a")], total: 4, nextOffset: 1))
        atTotal.append(CatalogPage(rooms: [room("b")], total: 4, nextOffset: 4), from: 1)
        XCTAssertTrue(atTotal.reachedEnd)

        var empty = FeedStore()
        empty.replace(with: CatalogPage(rooms: [room("a")], total: 9, nextOffset: 1))
        empty.append(CatalogPage(rooms: [], total: 9, nextOffset: 1), from: 1)
        XCTAssertTrue(empty.reachedEnd)

        var stalled = FeedStore()
        stalled.replace(with: CatalogPage(rooms: [room("a")], total: 9, nextOffset: 1))
        stalled.append(CatalogPage(rooms: [room("a")], total: 9, nextOffset: 1), from: 1)
        XCTAssertTrue(stalled.reachedEnd, "游标没有前进：兜底停止，免得原地重复请求")
    }

    // M3：后到的定向候选并入当前位置之后，当前及之前不动，已有的不重复。
    func testMergeAfterCurrentKeepsHeadAndDedupes() {
        var feed = FeedStore()
        feed.replace(with: CatalogPage(rooms: [room("a"), room("b"), room("c"), room("d")], total: 9, nextOffset: 4))
        feed.step(1)
        let added = feed.mergeAfterCurrent([room("D"), room("x"), room("y")]) { $0.sorted { $0.username > $1.username } }
        XCTAssertEqual(added, 2, "D 与已有的 d 重名（不分大小写）不重复加")
        XCTAssertEqual(feed.catalog.map(\.username), ["a", "b", "y", "x", "d", "c"])
        XCTAssertEqual(feed.currentUsername, "b")
        XCTAssertEqual(feed.total, 9)
        XCTAssertFalse(feed.reachedEnd)
    }

    func testMergeNothingNewIsNoOp() {
        var feed = FeedStore()
        feed.replace(with: CatalogPage(rooms: [room("a"), room("b")], total: 2, nextOffset: 2))
        let before = feed
        XCTAssertEqual(feed.mergeAfterCurrent([room("A")]) { $0.reversed() }, 0)
        XCTAssertEqual(feed, before)
    }

    func testMergeIntoEmptyFeedSelectsFirst() {
        var feed = FeedStore()
        feed.mergeAfterCurrent([room("a"), room("b")]) { $0 }
        XCTAssertEqual(feed.currentUsername, "a")
    }

    @available(macOS 10.15, *)
    // M3：并发请求各自带超时；慢的与失败的只丢掉自己，不拖住其余。
    func testFanOutDropsSlowAndFailedKeepsOrder() async {
        struct Boom: Error {}
        let started = Date()
        let results = await TimedFanOut.run([0, 1, 2, 3], timeout: 0.3) { (n: Int) async throws -> Int in
            switch n {
            case 1: try await Task.sleep(nanoseconds: 5_000_000_000); return n
            case 2: throw Boom()
            default: return n * 10
            }
        }
        XCTAssertEqual(results, [0, nil, nil, 30])
        XCTAssertLessThan(Date().timeIntervalSince(started), 2, "慢请求被超时截断")
    }

    @available(macOS 10.15, *)
    func testFanOutRunsConcurrently() async {
        let started = Date()
        let results = await TimedFanOut.run([1, 2, 3], timeout: 2) { (n: Int) async throws -> Int in
            try await Task.sleep(nanoseconds: 300_000_000)
            return n
        }
        XCTAssertEqual(results, [1, 2, 3])
        XCTAssertLessThan(Date().timeIntervalSince(started), 0.8, "三个 0.3 秒的请求并发，不串行")
    }

    @available(macOS 10.15, *)
    func testFanOutEmpty() async {
        let results = await TimedFanOut.run([Int](), timeout: 1) { $0 }
        XCTAssertTrue(results.isEmpty)
    }
}
