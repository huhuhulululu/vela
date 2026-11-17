import XCTest
@testable import VelaCore

final class FeedReorderTests: XCTestCase {
    func room(_ name: String, secs: Int? = nil) -> Room {
        Room(username: name, viewers: 10, gender: .female, tags: ["x"], show: .publicShow, secondsOnline: secs)
    }

    func testReorderAfterCurrentLeavesCurrentAndBeforeUntouched() {
        var feed = FeedStore()
        feed.replace(with: CatalogPage(
            rooms: [room("a", secs: 900), room("b", secs: 800), room("c", secs: 700), room("d", secs: 600)],
            total: 8, nextOffset: 4
        ))
        feed.step(1)
        feed.append(CatalogPage(rooms: [room("e", secs: 10), room("f", secs: 5), room("g", secs: nil)], total: 8, nextOffset: 7))
        feed.reorderAfterCurrent(Recommender.newest)
        XCTAssertEqual(feed.catalog.map(\.username), ["a", "b", "f", "e", "d", "c", "g"])
        XCTAssertEqual(feed.currentUsername, "b")
    }
}

final class FeedBlockTests: XCTestCase {
    func room(_ name: String) -> Room {
        Room(username: name, viewers: 10, gender: .female, tags: [], show: .publicShow)
    }

    // N1：屏蔽最后一个可见房间后当前为 nil，取消屏蔽后当前恢复成该房间，两次都是「当前房间变了」。
    func testBlockingLastRoomThenUnblockingChangesCurrentBothWays() {
        var feed = FeedStore()
        feed.replace(with: CatalogPage(rooms: [room("solo")], total: 1, nextOffset: 1))
        XCTAssertEqual(feed.current?.username, "solo")
        feed.setBlocked(["solo"])
        XCTAssertNil(feed.current)
        feed.setBlocked([])
        XCTAssertEqual(feed.current?.username, "solo")
    }

    // N2
    func testBlankVisibleListKeepsPagingUntilCapOrEnd() {
        var feed = FeedStore(query: RoomQuery(blocked: ["a", "b"]))
        feed.replace(with: CatalogPage(rooms: [room("a"), room("b")], total: 10, nextOffset: 2))
        XCTAssertTrue(feed.rooms.isEmpty)
        XCTAssertTrue(feed.shouldLoadMore())
        XCTAssertTrue(feed.shouldLoadMore(blankPages: FeedStore.maxBlankPages - 1))
        XCTAssertFalse(feed.shouldLoadMore(blankPages: FeedStore.maxBlankPages))
        feed.append(CatalogPage(rooms: [room("c")], total: 10, nextOffset: 3))
        XCTAssertEqual(feed.current?.username, "c")
    }

    func testBlankVisibleListStopsAtRealEnd() {
        var feed = FeedStore(query: RoomQuery(blocked: ["a"]))
        feed.replace(with: CatalogPage(rooms: [room("a")], total: 1, nextOffset: 1))
        XCTAssertTrue(feed.rooms.isEmpty)
        XCTAssertFalse(feed.shouldLoadMore())
    }
}
