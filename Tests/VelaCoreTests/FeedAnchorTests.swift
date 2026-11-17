import XCTest
@testable import VelaCore

/// FeedStore 的选择、步进、翻页和前置可以指定锚点；不传锚点时与原来的主路行为一致。
final class FeedAnchorTests: XCTestCase {
    private func room(_ name: String) -> Room {
        Room(username: name, viewers: 10, gender: .female, tags: [], show: .publicShow)
    }

    private func store(_ names: [String], current: String? = nil) -> FeedStore {
        var feed = FeedStore()
        feed.replace(with: CatalogPage(rooms: names.map { room($0) }, total: names.count, nextOffset: names.count))
        if let current {
            XCTAssertTrue(feed.focus(current))
        }
        return feed
    }

    func testSelectWritesAVisibleRoomAndClearsWhenNil() {
        var feed = store(["a", "b", "c"], current: "a")
        feed.select("C")
        XCTAssertEqual(feed.currentUsername, "c")
        feed.select(nil)
        XCTAssertNil(feed.currentUsername)
        feed.select("missing")
        XCTAssertNil(feed.currentUsername, "不在可见列表里的名字不改当前房间")
        feed.select("b")
        XCTAssertEqual(feed.currentUsername, "b")
        XCTAssertFalse(feed.focus("nope"))
        XCTAssertEqual(feed.currentUsername, "b")
    }

    func testStepSkippingEmptyMatchesPlainStep() {
        let names = ["a", "b", "c", "d", "e"]
        for delta in [-4, -1, 0, 1, 3, 9] {
            var plain = store(names, current: "c")
            var skipping = plain
            let left = plain.step(delta)
            let right = skipping.step(delta, skipping: [])
            XCTAssertEqual(skipping.currentUsername, plain.currentUsername, "delta \(delta)")
            XCTAssertEqual(right?.username, left?.username, "delta \(delta)")
        }
    }

    func testStepSkippingJumpsOverNamedRoomsAndRefusesToLandOnThem() {
        var feed = store(["a", "b", "c", "d"], current: "b")
        _ = feed.step(1, skipping: ["c"])
        XCTAssertEqual(feed.currentUsername, "d")

        var stuck = store(["a", "b"], current: "a")
        let stayed = stuck.step(1, skipping: ["b"])
        XCTAssertEqual(stuck.currentUsername, "a")
        XCTAssertEqual(stayed?.username, "a")

        var tail = store(["a", "b", "c"], current: "b")
        _ = tail.step(1, skipping: ["C"])
        XCTAssertEqual(tail.currentUsername, "b", "落点被跳过且前方没有房间时停在原地")
    }

    func testShouldLoadMoreAtAnchorMatchesCurrentAndBlankPages() {
        var feed = store((0..<20).map { "r\($0)" }, current: "r0")
        feed.reachedEnd = false
        XCTAssertFalse(feed.shouldLoadMore())
        XCTAssertEqual(
            feed.shouldLoadMore(),
            feed.shouldLoadMore(at: feed.currentUsername, threshold: 8, blankPages: 0)
        )
        XCTAssertFalse(feed.shouldLoadMore(at: "r0", threshold: 8, blankPages: 0))
        XCTAssertTrue(feed.shouldLoadMore(at: "r12", threshold: 8, blankPages: 0))
        XCTAssertTrue(feed.shouldLoadMore(at: "r19", threshold: 8, blankPages: 0))
        XCTAssertFalse(feed.shouldLoadMore(at: "missing", threshold: 8, blankPages: 0), "找不到锚点时与 index 回落到 0 相同")
        XCTAssertTrue(feed.focus("r19"))
        XCTAssertEqual(
            feed.shouldLoadMore(threshold: 8, blankPages: 0),
            feed.shouldLoadMore(at: feed.currentUsername, threshold: 8, blankPages: 0)
        )

        var blank = FeedStore(query: RoomQuery(blocked: ["a", "b"]))
        blank.replace(with: CatalogPage(rooms: [room("a"), room("b")], total: 10, nextOffset: 2))
        XCTAssertTrue(blank.rooms.isEmpty)
        XCTAssertEqual(
            blank.shouldLoadMore(blankPages: FeedStore.maxBlankPages - 1),
            blank.shouldLoadMore(at: "a", threshold: 8, blankPages: FeedStore.maxBlankPages - 1)
        )
        XCTAssertFalse(blank.shouldLoadMore(at: nil, threshold: 1, blankPages: FeedStore.maxBlankPages))
        blank.reachedEnd = true
        XCTAssertFalse(blank.shouldLoadMore(at: nil, threshold: 8, blankPages: 0))
    }

    func testPromoteAfterAnchorMatchesCurrentAndCanUseAnotherAnchor() {
        let names = (0..<8).map { "r\($0)" }
        var current = store(names, current: "r2")
        var anchored = current
        current.promoteAfterCurrent(["R6", "r4", "r1", "r2", "ghost", "r6"])
        anchored.promoteAfter(anchor: "r2", ["R6", "r4", "r1", "r2", "ghost", "r6"])
        XCTAssertEqual(anchored.catalog.map(\.username), current.catalog.map(\.username))
        XCTAssertEqual(anchored.currentUsername, "r2")

        var other = store(names, current: "r2")
        other.promoteAfter(anchor: "r0", ["r3"])
        XCTAssertEqual(other.catalog.map(\.username), ["r0", "r3", "r1", "r2", "r4", "r5", "r6", "r7"])
        XCTAssertEqual(other.currentUsername, "r2", "前置别的锚点不改当前房间")

        var missing = store(names, current: "r2")
        missing.promoteAfter(anchor: nil, ["r5"])
        XCTAssertEqual(missing.catalog.map(\.username), names)
        missing.promoteAfter(anchor: "nobody", ["r5"])
        XCTAssertEqual(missing.catalog.map(\.username), names)
    }
}
