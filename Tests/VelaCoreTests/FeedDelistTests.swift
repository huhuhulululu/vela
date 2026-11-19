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

    private func ticket(_ status: ShowState, success: Bool = false, url: URL? = nil) -> StreamTicket {
        StreamTicket(success: success, url: url, status: status)
    }

    func testTicketDelistsOnlyConfirmedStatuses() {
        XCTAssertEqual(ticket(.privateShow).delistedShow, .privateShow)
        XCTAssertEqual(ticket(.group).delistedShow, .group)
        XCTAssertEqual(ticket(.hidden).delistedShow, .hidden)
        XCTAssertEqual(ticket(.password).delistedShow, .password)
        XCTAssertEqual(ticket(.offline).delistedShow, .offline)
    }

    // 接口失败（退避、success:false 缺 room_status）时 status 是 unknown：提示仍是「已离线」，但不能据此筛除。
    func testUnknownTicketIsNotDelistedButKeepsOfflineNotice() {
        let unknown = ticket(.unknown)
        XCTAssertNil(unknown.delistedShow)
        XCTAssertEqual(unknown.failure, .offline)
    }

    func testAwayGeoAndPlayableTicketsAreNotDelisted() {
        XCTAssertNil(ticket(.away).delistedShow)
        XCTAssertNil(ticket(.publicShow).delistedShow)
        XCTAssertNil(ticket(.publicShow, success: true, url: URL(string: "https://x.test/a.m3u8")).delistedShow)
    }

    // MARK: 站点（docs/plans/sites.md）：同名的 Chaturbate 与 Cam4 房间各算各的

    private func mixed() -> FeedStore {
        var feed = FeedStore(query: RoomQuery(publicOnly: true))
        let rooms = [room("a"), room("alice"), Room(username: "alice", show: .publicShow, site: .cam4), room("c")]
        feed.replace(with: CatalogPage(rooms: rooms, total: 4, nextOffset: 4))
        return feed
    }

    func testDelistCam4RoomLeavesChaturbateNamesake() {
        var feed = mixed()
        XCTAssertTrue(feed.canDelist("c4:alice"))
        XCTAssertTrue(feed.delist("c4:alice", as: .privateShow))
        XCTAssertEqual(feed.rooms.map(\.ref), ["a", "alice", "c"])
    }

    func testDelistChaturbateRoomLeavesCam4Namesake() {
        var feed = mixed()
        XCTAssertTrue(feed.delist("ALICE", as: .offline))
        XCTAssertEqual(feed.rooms.map(\.ref), ["a", "c4:alice", "c"])
    }

    func testProtectingUsesSiteKeys() {
        var feed = mixed()
        XCTAssertFalse(feed.delist("c4:alice", as: .privateShow, protecting: ["C4:Alice"]), "在播的 Cam4 房间不筛")
        XCTAssertTrue(feed.delist("alice", as: .privateShow, protecting: ["c4:alice"]), "保护 Cam4 同名房间不挡 cb 房间")
        XCTAssertEqual(feed.rooms.map(\.ref), ["a", "c4:alice", "c"])
    }
}
