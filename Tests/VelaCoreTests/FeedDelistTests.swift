import XCTest
@testable import VelaCore

final class FeedDelistTests: XCTestCase {
    private func room(_ name: String, _ show: ShowState = .publicShow) -> Room {
        Room(username: name, viewers: 10, gender: .female, tags: [], show: show)
    }

    private func feed(publicOnly: Bool = true) -> FeedStore {
        var feed = FeedStore(query: RoomQuery(publicOnly: publicOnly))
        feed.replace(with: CatalogPage(rooms: ["a", "b", "c"].map { room($0) }, total: 3, nextOffset: 3))
        return feed
    }

    func testDelistRemovesRoomFromVisibleListAndPrefetch() {
        var feed = feed()
        XCTAssertEqual(feed.prefetchUsernames(), ["b"])
        XCTAssertTrue(feed.delist("b", as: .privateShow))
        XCTAssertEqual(feed.rooms.map(\.username), ["a", "c"])
        XCTAssertEqual(feed.prefetchUsernames(), ["c"])
        XCTAssertEqual(feed.currentUsername, "a")
    }

    func testDelistIsCaseInsensitive() {
        var feed = feed()
        XCTAssertTrue(feed.delist("B", as: .offline))
        XCTAssertEqual(feed.rooms.map(\.username), ["a", "c"])
    }

    func testDelistSkippedWhenPublicOnlyIsOff() {
        var feed = feed(publicOnly: false)
        XCTAssertFalse(feed.delist("b", as: .privateShow))
        XCTAssertEqual(feed.rooms.map(\.username), ["a", "b", "c"])
    }

    func testDelistNeverTouchesCurrentOrProtectedRooms() {
        var feed = feed()
        XCTAssertFalse(feed.delist("a", as: .group))
        XCTAssertFalse(feed.delist("c", as: .group, protecting: ["C"]))
        XCTAssertEqual(feed.rooms.map(\.username), ["a", "b", "c"])
    }

    func testDelistIgnoresAwayAndPublicStates() {
        var feed = feed()
        XCTAssertFalse(feed.delist("b", as: .away))
        XCTAssertFalse(feed.delist("b", as: .publicShow))
        XCTAssertEqual(feed.rooms.count, 3)
    }

    func testDelistOnlyAppliesToRoomsListedAsPublic() {
        var feed = FeedStore(query: RoomQuery(publicOnly: true))
        feed.replace(with: CatalogPage(rooms: [room("a"), room("x", .unknown)], total: 2, nextOffset: 2))
        XCTAssertFalse(feed.canDelist("x"))
        XCTAssertFalse(feed.delist("x", as: .privateShow))
        XCTAssertFalse(feed.canDelist("nobody"))
    }

    func testRefreshBringsDelistedRoomBackWhenPublic() {
        var feed = feed()
        feed.delist("b", as: .privateShow)
        feed.replace(with: CatalogPage(rooms: ["a", "b", "c"].map { room($0) }, total: 3, nextOffset: 3))
        XCTAssertEqual(feed.rooms.map(\.username), ["a", "b", "c"])
    }

    func testStreamFailureDelistMapping() {
        XCTAssertEqual(StreamFailure.privateShow.delistedShow, .privateShow)
        XCTAssertEqual(StreamFailure.group.delistedShow, .group)
        XCTAssertEqual(StreamFailure.hidden.delistedShow, .hidden)
        XCTAssertEqual(StreamFailure.password.delistedShow, .password)
        XCTAssertEqual(StreamFailure.offline.delistedShow, .offline)
        XCTAssertNil(StreamFailure.away.delistedShow)
        XCTAssertNil(StreamFailure.geo.delistedShow)
        XCTAssertNil(StreamFailure.unavailable.delistedShow)
    }
}
