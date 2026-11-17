import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import VelaCore

final class VelaCoreTests: XCTestCase {
    /// 默认共用的非 JSON 退避记录跨用例清空，免得前一个用例的退避挡住后一个。
    override func setUp() {
        super.setUp()
        EndpointBackoffBox.shared.reset()
    }

    func testCatalogFetchRejectsChallenge() async {
        let client = FixtureClient(status: 403, data: Data("<html>Just a moment</html>".utf8))
        let api = CatalogAPI(client: client)
        do {
            _ = try await api.fetch(CatalogQuery())
            XCTFail("expected challenge")
        } catch let error as CatalogFailure {
            XCTAssertEqual(error, .challenge)
        } catch {
            XCTFail("unexpected \(error)")
        }
    }

    func testStreamTickets() throws {
        let playing = try StreamDecoder.decode(status: 200, data: Fixtures.streamPublic)
        XCTAssertTrue(playing.isPlayable)
        XCTAssertEqual(playing.url?.host, "edge.example")

        let legacy = try StreamDecoder.decode(status: 200, data: Fixtures.streamLegacySuccess)
        XCTAssertTrue(legacy.isPlayable)

        let priv = try StreamDecoder.decode(status: 200, data: Fixtures.streamPrivate)
        XCTAssertEqual(priv.failure, .privateShow)

        let offline = try StreamDecoder.decode(status: 200, data: Fixtures.streamOffline)
        XCTAssertEqual(offline.failure, .offline)

        let geo = try StreamDecoder.decode(status: 200, data: Fixtures.streamGeo)
        XCTAssertEqual(geo.failure, .geo)

        XCTAssertThrowsError(try StreamDecoder.decode(status: 403, data: Data("<html>Just a moment</html>".utf8))) { error in
            XCTAssertEqual(error as? StreamDecodeError, .challenge)
        }
    }

    func testStreamRequestDoesNotAskForHighestBandwidth() {
        let request = StreamRequest.make(username: "alpha")
        let body = String(data: request.httpBody ?? Data(), encoding: .utf8)
        XCTAssertEqual(body, "room_slug=alpha")
        XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Requested-With"), "XMLHttpRequest")
    }

    func testPlaylistLadderPicksRestingRung() {
        let variants = PlaylistLadder.variants(in: Fixtures.playlist)
        XCTAssertEqual(variants.map(\.bandwidth), [896_000, 1_696_000, 3_096_000, 5_128_000])
        XCTAssertEqual(variants.map(\.height), [360, 540, 720, 1080])
        XCTAssertEqual(PlaylistLadder.peakBitrate(pixelHeight: 800, variants: variants, resting: false), 3_096_000)
        XCTAssertEqual(PlaylistLadder.peakBitrate(pixelHeight: 500, variants: variants, resting: false), 1_696_000)
        XCTAssertEqual(PlaylistLadder.peakBitrate(pixelHeight: 1400, variants: variants, resting: true), 896_000)
        let master = URL(string: "https://edge.example/live/llhls.m3u8")!
        let media = PlaylistLadder.mediaURL(in: Fixtures.playlist, master: master, maxBandwidth: 1_700_000)
        XCTAssertEqual(media?.absoluteString, "https://edge.example/live/mid.m3u8")
        let highest = PlaylistLadder.mediaURL(in: Fixtures.playlist, master: master, maxBandwidth: .infinity)
        XCTAssertEqual(highest?.absoluteString, "https://edge.example/live/source.m3u8")
        let steady = PlaylistLadder.steadyPlaylist(Fixtures.lowLatencyMedia, base: URL(string: "https://edge.example/live/source.m3u8?token=abc")!)
        XCTAssertEqual(steady, Fixtures.steadyMedia + "\n")
        XCTAssertNil(PlaylistLadder.steadyPlaylist(Fixtures.playlist, base: master))
        XCTAssertEqual(PowerPolicy.peakBitrate(pixelHeight: 500, resting: false), 1_696_000)
    }

    func testWheelCommitsOneRoom() {
        var wheel = WheelInterpreter()
        let start = Date(timeIntervalSince1970: 1_000)
        let first = wheel.consume(WheelSample(deltaY: -8, phaseNone: true), now: start)
        XCTAssertEqual(first, 1)
        let ignored = wheel.consume(WheelSample(deltaY: -8, phaseNone: true), now: start.addingTimeInterval(0.1))
        XCTAssertNil(ignored)
        let trackpad = wheel.consume(
            WheelSample(deltaY: -50, began: true, ended: true, precise: true),
            now: start.addingTimeInterval(1)
        )
        XCTAssertEqual(trackpad, 1)
        let momentum = wheel.consume(
            WheelSample(deltaY: -80, momentum: true, precise: true),
            now: start.addingTimeInterval(2)
        )
        XCTAssertNil(momentum)
    }

    func testPresenceIsConditional() {
        var presence = Presence(pointerInside: false, interacting: false, restingOpacity: 0.4, engagedOpacity: 1)
        XCTAssertEqual(presence.opacity, 0.4)
        XCTAssertTrue(presence.ignoresMouseEvents)
        presence.pointerInside = true
        XCTAssertEqual(presence.opacity, 1)
        XCTAssertFalse(presence.ignoresMouseEvents)
        presence.pointerInside = false
        presence.interacting = true
        XCTAssertTrue(presence.engaged)
        presence.conditionalOpacity = false
        presence.interacting = false
        XCTAssertEqual(presence.opacity, 1)
        XCTAssertFalse(presence.ignoresMouseEvents)
    }

    func testFeedKeepsPlaceFiltersAndPrefetches() {
        var feed = FeedStore()
        feed.replace(with: CatalogPage(rooms: [room("a"), room("b"), room("c", show: .privateShow)], total: 5, nextOffset: 3))
        XCTAssertEqual(feed.current?.username, "a")
        XCTAssertEqual(feed.rooms.map(\.username), ["a", "b"])
        XCTAssertFalse(feed.reachedEnd)
        feed.step(1)
        XCTAssertEqual(feed.current?.username, "b")
        feed.setQuery(RoomQuery(text: "zzz", publicOnly: true))
        XCTAssertNil(feed.current?.username)
        feed.setQuery(RoomQuery(publicOnly: true))
        XCTAssertEqual(feed.current?.username, "a")
        feed.focus("b")
        feed.insertDirect(Room.direct(username: "new_room")!)
        XCTAssertEqual(feed.current?.username, "new_room")
        XCTAssertEqual(feed.prefetchUsernames(), [])
        feed.append(CatalogPage(rooms: [room("b"), room("d", show: .publicShow)], total: 5, nextOffset: 5))
        XCTAssertTrue(feed.catalog.contains { $0.username == "d" })
        XCTAssertEqual(feed.catalog.filter { $0.username == "b" }.count, 1)
        XCTAssertTrue(feed.reachedEnd)
    }

    func testFormattingAndTokens() {
        XCTAssertEqual(ViewerCount.string(419), "419")
        XCTAssertEqual(ViewerCount.string(12_000), "1.2万")
        XCTAssertEqual(ViewerCount.string(20_000), "2万")
        XCTAssertEqual(BroadcastClock.string(seconds: 59), nil)
        XCTAssertEqual(BroadcastClock.string(seconds: 120), "已开播 2 分钟")
        XCTAssertEqual(BroadcastClock.string(seconds: 3660), "已开播 1 小时 1 分")
        XCTAssertNil(Room.direct(username: "nope name"))
        XCTAssertEqual(Room.direct(username: "alpha_1")?.username, "alpha_1")
        XCTAssertFalse(RoomQuery(text: "alpha", publicOnly: true).matches(room("beta")))
        XCTAssertTrue(RoomQuery(text: "hello", publicOnly: true).matches(room("alpha", subject: "say hello")))
        XCTAssertFalse(RoomQuery(publicOnly: true).matches(room("c", show: .privateShow)))
    }

    private func room(_ name: String, show: ShowState = .publicShow, subject: String = "") -> Room {
        Room(username: name, subject: subject, show: show)
    }
}

private struct FixtureClient: HTTPClient {
    var status: Int
    var data: Data

    func send(_ request: URLRequest) async throws -> HTTPResult {
        HTTPResult(status: status, data: data)
    }
}

private enum Fixtures {
    static let streamPublic = Data(
        """
        {"success":true,"url":"https://edge.example/live/llhls.m3u8?token=abc","room_status":"public","hidden_message":""}
        """.utf8
    )

    static let streamLegacySuccess = Data(
        """
        {"success":1,"url":"https://edge.example/legacy.m3u8","room_status":"public","hidden_message":""}
        """.utf8
    )

    static let streamPrivate = Data(
        """
        {"success":true,"url":"","room_status":"private","hidden_message":""}
        """.utf8
    )

    static let streamOffline = Data(
        """
        {"success":false,"url":"","room_status":"offline","hidden_message":""}
        """.utf8
    )

    static let streamGeo = Data(
        """
        {"success":true,"url":"","room_status":"public","hidden_message":""}
        """.utf8
    )

    static let lowLatencyMedia = """
    #EXTM3U
    #EXT-X-VERSION:9
    #EXT-X-TARGETDURATION:2
    #EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK=1.000
    #EXT-X-PART-INF:PART-TARGET=0.500
    #EXT-X-MEDIA-SEQUENCE:10
    #EXT-X-MAP:URI="init.mp4"
    #EXTINF:2.000,
    seg10.m4s
    #EXT-X-PART:DURATION=0.500,URI="seg11.1.m4s",INDEPENDENT=YES
    #EXT-X-PRELOAD-HINT:TYPE=PART,URI="seg11.2.m4s"
    """

    static let steadyMedia = """
    #EXTM3U
    #EXT-X-VERSION:7
    #EXT-X-TARGETDURATION:2
    #EXT-X-MEDIA-SEQUENCE:10
    #EXT-X-MAP:URI="https://edge.example/live/init.mp4"
    #EXTINF:2.000,
    https://edge.example/live/seg10.m4s
    """

    static let playlist = """
    #EXTM3U
    #EXT-X-STREAM-INF:BANDWIDTH=896000,RESOLUTION=640x360,CODECS="avc1.42c01e,mp4a.40.2"
    low.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=1696000,RESOLUTION=960x540
    mid.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=3096000,RESOLUTION=1280x720
    high.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=5128000,RESOLUTION=1920x1080
    source.m3u8
    """
}
