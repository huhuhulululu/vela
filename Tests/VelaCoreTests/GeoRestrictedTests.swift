import XCTest
@testable import VelaCore

/// 地区受限：公开但拿不到可播地址，归为「当前地区不可观看」，不算离线也不算在线。
final class GeoRestrictedTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 10_000)

    private func context(_ fields: String) -> Data {
        Data(#"{"room_status":"public","num_viewers":3,"broadcaster_username":"geo_room"\#(fields)}"#.utf8)
    }

    func testPublicWithEmptyHLSSourceIsRestricted() throws {
        XCTAssertEqual(try ChatContext.decode(status: 200, data: context(#","hls_source":"""#), now: now), .restricted)
        XCTAssertEqual(try ChatContext.decode(status: 200, data: context(#","hls_source":"  ""#), now: now), .restricted)
    }

    func testPublicWithSourceOrWithoutFieldStaysOnline() throws {
        let withSource = context(#","hls_source":"https://edge.example/v1/edge/streams/a/llhls.m3u8?token=t""#)
        XCTAssertNotNil(try ChatContext.decode(status: 200, data: withSource, now: now).room)
        // 字段缺失或为 null：不下结论，照旧算在线。
        XCTAssertNotNil(try ChatContext.decode(status: 200, data: context(""), now: now).room)
        XCTAssertNotNil(try ChatContext.decode(status: 200, data: context(#","hls_source":null"#), now: now).room)
    }

    func testNonPublicWithEmptySourceIsOfflineNotRestricted() throws {
        let away = Data(#"{"room_status":"away","broadcaster_username":"x","hls_source":""}"#.utf8)
        XCTAssertEqual(try ChatContext.decode(status: 200, data: away, now: now), .offline)
    }

    func testRestrictedIsNotOnlineAndHasNoRoom() {
        XCTAssertNil(FavoriteStatus.restricted.room)
        let rooms = FavoriteResolver.onlineRooms(favorites: ["geo_room"], statuses: ["geo_room": .restricted])
        XCTAssertTrue(rooms.isEmpty)
        XCTAssertFalse(FavoriteResolver.hasUnresolved(pending: ["geo_room"], statuses: ["geo_room": .restricted]))
    }

    func testBoardShowsRestrictedBetweenOfflineAndUnknown() {
        let board = FavoritesBoard.make(
            favorites: ["gone", "geo", "later", "off"],
            statuses: ["gone": .notFound, "geo": .restricted, "off": .offline],
            catalog: []
        )
        XCTAssertTrue(board.online.isEmpty)
        XCTAssertEqual(board.others.map(\.username), ["off", "geo", "later", "gone"])
        let geo = board.others[1]
        XCTAssertEqual(geo.state, .restricted)
        XCTAssertEqual(geo.detail, "当前地区不可观看")
        XCTAssertNil(geo.room)
    }

    func testRestrictedIsCachedLikeOtherAnswers() {
        let cache = FavoriteCache().storing(["Geo": .restricted], at: now)
        XCTAssertEqual(cache.fresh(for: "geo", now: now.addingTimeInterval(60)), .restricted)
        XCTAssertNil(cache.fresh(for: "geo", now: now.addingTimeInterval(FavoriteCache.ttl + 1)))
    }

    func testEdgeTicketPublicWithoutURLIsGeo() throws {
        let empty = Data(#"{"success":true,"url":"","room_status":"public"}"#.utf8)
        let ticket = try StreamDecoder.decode(status: 200, data: empty)
        XCTAssertFalse(ticket.isPlayable)
        XCTAssertEqual(ticket.failure, .geo)
        let offline = Data(#"{"success":true,"url":"","room_status":"offline"}"#.utf8)
        XCTAssertEqual(try StreamDecoder.decode(status: 200, data: offline).failure, .offline)
    }
}
