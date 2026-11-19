import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import VelaCore

final class RoomKeyTests: XCTestCase {
    func testRawRefBareNameAndCaseInsensitiveEquality() {
        let bare = RoomKey(parsing: "Alice")
        let prefixed = RoomKey(parsing: "cb:Alice")
        let lower = RoomKey(parsing: "cb:alice")
        XCTAssertEqual(bare, prefixed)
        XCTAssertEqual(prefixed, lower)
        XCTAssertEqual(bare?.raw, "cb:Alice")
        XCTAssertEqual(bare?.ref, "Alice")
        XCTAssertEqual(bare?.normalized, "alice")
        XCTAssertEqual(lower?.raw, "cb:alice")
        XCTAssertEqual(RoomKey(parsing: "c4:Bob")?.raw, "c4:bob")
        XCTAssertEqual(RoomKey(parsing: "c4:Bob")?.ref, "c4:bob")
        XCTAssertNotEqual(RoomKey(parsing: "alice"), RoomKey(parsing: "c4:alice"))
    }

    func testSiteIDPattern() {
        XCTAssertTrue(SiteID.isValidID("a1"))
        XCTAssertTrue(SiteID.isValidID("abcdefgh"))
        XCTAssertFalse(SiteID.isValidID("1a"))
        XCTAssertFalse(SiteID.isValidID("A1"))
        XCTAssertFalse(SiteID.isValidID("a"))
        XCTAssertFalse(SiteID.isValidID("abcdefghi"))
        XCTAssertEqual(SiteID.chaturbate.displayName, "Chaturbate")
        XCTAssertEqual(SiteID.cam4.displayName, "Cam4")
    }

    func testRejectsUnknownSiteEmptyAndIllegalNames() {
        XCTAssertNil(RoomKey(parsing: ""))
        XCTAssertNil(RoomKey(parsing: "a"))
        XCTAssertNil(RoomKey(parsing: "bad name"))
        XCTAssertNil(RoomKey(parsing: "c4:bad-name"))
        XCTAssertNil(RoomKey(parsing: "c4:has.dot"))
        XCTAssertNil(RoomKey(site: .cam4, name: String(repeating: "a", count: 31)))
        XCTAssertNil(RoomKey(parsing: "zz:alice"))
        XCTAssertNil(RoomKey(parsing: "1a:alice"))
        XCTAssertNil(RoomKey(parsing: "A1:alice"))
        XCTAssertNotNil(RoomKey(site: .cam4, name: "ab"))
        XCTAssertNotNil(RoomKey(site: .chaturbate, name: "ab"))
    }

    func testCodableRoundTripAndNormalizeRef() throws {
        let key = try XCTUnwrap(RoomKey(parsing: "cb:Alice"))
        let data = try JSONEncoder().encode(key)
        XCTAssertEqual(String(data: data, encoding: .utf8), "\"cb:Alice\"")
        XCTAssertEqual(try JSONDecoder().decode(RoomKey.self, from: data), key)
        XCTAssertEqual(try JSONDecoder().decode(RoomKey.self, from: Data("\"alice\"".utf8)), RoomKey(parsing: "alice"))
        XCTAssertEqual(RoomKey.normalizeRef("Alice"), "alice")
        XCTAssertEqual(RoomKey.normalizeRef("cb:Alice"), "alice")
        XCTAssertEqual(RoomKey.normalizeRef("CB:Alice"), "alice")
        XCTAssertEqual(RoomKey.normalizeRef("c4:Bob"), "c4:bob")
        XCTAssertEqual(RoomKey.normalizeRef("  not a key  "), "not a key")
    }

    func testRoomIdentityKeepsChaturbateIDAndSeparatesCam4() {
        let cb = Room(username: "Alice")
        XCTAssertEqual(cb.site, .chaturbate)
        XCTAssertEqual(cb.id, cb.username)
        XCTAssertNil(cb.stream)
        XCTAssertNil(cb.thumbnailExpires)
        XCTAssertTrue(cb.blockedRegions.isEmpty)
        let c4 = Room(username: "Alice", site: .cam4)
        XCTAssertEqual(c4.id, "c4:alice")
        XCTAssertEqual(c4.ref, "c4:alice")
        let merged = dedupeRooms([
            Room(username: "alice", show: .publicShow),
            Room(username: "Alice", show: .publicShow),
            Room(username: "alice", show: .publicShow, site: .cam4)
        ])
        XCTAssertEqual(merged.map(\.id), ["alice", "c4:alice"])
        XCTAssertEqual(Room.direct(username: "sample_one")?.id, "sample_one")
        XCTAssertEqual(Room.direct(key: RoomKey(parsing: "c4:sample_one")!).id, "c4:sample_one")
        XCTAssertNil(Room.direct(username: "x"))
    }

    func testMasterFailureOnlyOnCam4() {
        let cb = ChaturbateAdapter(client: IdleClient(), backoff: nil)
        let c4 = Cam4Adapter(client: IdleClient(), backoff: nil, viewerRegions: [])
        for status in [200, 301, 302, 400, 401] {
            XCTAssertNil(c4.masterFailure(status: status), "\(status)")
            XCTAssertNil(cb.masterFailure(status: status), "\(status)")
        }
        for status in [403, 404, 500, 504, 599] {
            XCTAssertEqual(c4.masterFailure(status: status), .unavailable, "\(status)")
            XCTAssertNil(cb.masterFailure(status: status), "\(status)")
        }
        XCTAssertEqual(cb.mediaHeaders(for: RoomKey(parsing: "alice")!)["Origin"], "https://chaturbate.com")
        XCTAssertEqual(c4.mediaHeaders(for: RoomKey(parsing: "c4:alice")!)["Referer"], "https://www.cam4.com/")
        XCTAssertEqual(c4.mediaHeaders(for: RoomKey(parsing: "c4:alice")!)["Origin"], "https://www.cam4.com")
        XCTAssertNil(c4.challengeHome)
        XCTAssertEqual(cb.challengeHome?.absoluteString, "https://chaturbate.com/")
        XCTAssertTrue(cb.capabilities.contains(.challengePage))
        XCTAssertEqual(c4.capabilities, .strictMaster)
    }

    func testSiteModeRestore() {
        XCTAssertEqual(SiteMode.restore(nil), .chaturbate)
        XCTAssertEqual(SiteMode.restore(""), .chaturbate)
        XCTAssertEqual(SiteMode.restore("nope"), .chaturbate)
        XCTAssertEqual(SiteMode.restore("chaturbate"), .chaturbate)
        XCTAssertEqual(SiteMode.restore("cam4"), .cam4)
        XCTAssertEqual(SiteMode.restore("mixed"), .mixed)
        XCTAssertEqual(SiteMode.mixed.sites, [.chaturbate, .cam4])
        XCTAssertEqual(SiteMode.cam4.sites, [.cam4])
    }

    func testRelativePlaylistResolutionDropsMasterQuery() throws {
        let master = try XCTUnwrap(URL(string: "https://cdn.example/live/master.m3u8?token=abc"))
        let cases: [(String, String)] = [
            ("media/720.m3u8", "https://cdn.example/live/media/720.m3u8"),
            ("/hls/720.m3u8", "https://cdn.example/hls/720.m3u8"),
            ("../other/720.m3u8", "https://cdn.example/other/720.m3u8"),
            ("720.m3u8?rendition=1", "https://cdn.example/live/720.m3u8?rendition=1"),
            ("https://other.example/a.m3u8?x=1", "https://other.example/a.m3u8?x=1")
        ]
        for (reference, expected) in cases {
            let playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            \(reference)
            """
            let selected = try XCTUnwrap(StreamRendition.select(in: playlist, master: master, maxBandwidth: .infinity))
            XCTAssertEqual(selected.video.absoluteString, expected, reference)
            XCTAssertFalse(selected.isUnselected)
            let ladder = try XCTUnwrap(PlaylistLadder.mediaURL(in: playlist, master: master, maxBandwidth: .infinity))
            XCTAssertEqual(ladder.absoluteString, expected, reference)
        }
        let single = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
        720/index.m3u8
        """
        let only = try XCTUnwrap(StreamRendition.select(in: single, master: master, maxBandwidth: 1))
        XCTAssertEqual(only.video.absoluteString, "https://cdn.example/live/720/index.m3u8")
        XCTAssertFalse(only.video.absoluteString.contains("token"))
    }
}

private struct IdleClient: HTTPClient {
    func send(_ request: URLRequest) async throws -> HTTPResult {
        HTTPResult(status: 500, data: Data())
    }
}
