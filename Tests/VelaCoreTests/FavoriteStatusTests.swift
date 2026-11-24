import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import VelaCore

private func json(_ text: String, status: Int = 200) -> HTTPResult { HTTPResult(status: status, data: Data(text.utf8)) }

private func publicContext(_ name: String, viewers: Int = 10, start: Int = 1_000) -> HTTPResult {
    json("""
    {"room_status":"public","num_viewers":\(viewers),"room_title":"hi","broadcaster_username":"\(name)",
     "broadcaster_gender":"female","age":24,"start_timestamp":\(start)}
    """)
}

private func catalogRoom(_ name: String, viewers: Int = 5, show: ShowState = .publicShow) -> Room {
    Room(username: name, viewers: viewers, show: show)
}

/// 记录请求次数与同时在途数的假网络层。
private final class RecordingClient: HTTPClient, @unchecked Sendable {
    private let lock = NSLock()
    private var inFlight = 0
    private(set) var peak = 0
    private(set) var requested: [String] = []
    private let respond: @Sendable (String) -> HTTPResult

    init(respond: @escaping @Sendable (String) -> HTTPResult) { self.respond = respond }

    func send(_ request: URLRequest) async throws -> HTTPResult {
        let name = request.url?.pathComponents[3] ?? ""
        lock.lock()
        inFlight += 1
        peak = max(peak, inFlight)
        requested.append(name)
        lock.unlock()
        try? await Task.sleep(nanoseconds: 15_000_000)
        lock.lock()
        inFlight -= 1
        lock.unlock()
        return respond(name)
    }
}

final class FavoriteStatusTests: XCTestCase {
    /// 默认共用的非 JSON 退避记录跨用例清空，免得前一个用例的退避挡住后一个。
    override func setUp() {
        super.setUp()
        EndpointBackoffBox.shared.reset()
    }

    private let now = Date(timeIntervalSince1970: 10_000)

    func testDecodeOnlinePublicRoom() throws {
        let result = publicContext("Alpha", viewers: 321)
        let status = try ChatContext.decode(status: result.status, data: result.data, now: now)
        let room = try XCTUnwrap(status.room)
        XCTAssertEqual(room.username, "Alpha")
        XCTAssertEqual(room.viewers, 321)
        XCTAssertEqual(room.subject, "hi")
        XCTAssertEqual(room.gender, .female)
        XCTAssertEqual(room.age, 24)
        XCTAssertEqual(room.show, .publicShow)
        XCTAssertEqual(room.secondsOnline, 9_000)
        XCTAssertEqual(room.thumbnailURL?.host, "thumb.live.mmcdn.com")
    }

    func testDecodeNotPlayableAndMissing() throws {
        for state in ["offline", "private", "group", "away", "hidden"] {
            let data = Data(#"{"room_status":"\#(state)","num_viewers":0,"broadcaster_username":"x"}"#.utf8)
            XCTAssertEqual(try ChatContext.decode(status: 200, data: data, now: now), .offline, state)
        }
        XCTAssertEqual(try ChatContext.decode(status: 404, data: Data(), now: now), .notFound)
        let deleted = Data(#"{"status":403,"detail":"Room is deleted","code":"access-denied"}"#.utf8)
        XCTAssertEqual(try ChatContext.decode(status: 403, data: deleted, now: now), .notFound)
        let password = Data(#"{"status":401,"detail":"This room requires a password."}"#.utf8)
        XCTAssertEqual(try ChatContext.decode(status: 401, data: password, now: now), .offline)
    }

    func testDecodeChallengeAndFailures() {
        let page = Data("<html><title>Just a moment...</title></html>".utf8)
        for status in [200, 403, 503] {
            XCTAssertThrowsError(try ChatContext.decode(status: status, data: page, now: now)) {
                XCTAssertEqual($0 as? CatalogFailure, .challenge)
            }
        }
        XCTAssertThrowsError(try ChatContext.decode(status: 403, data: Data("denied".utf8), now: now)) {
            XCTAssertEqual($0 as? CatalogFailure, .challenge)
        }
        XCTAssertThrowsError(try ChatContext.decode(status: 500, data: Data(), now: now)) {
            XCTAssertEqual($0 as? CatalogFailure, .status(500))
        }
        XCTAssertThrowsError(try ChatContext.decode(status: 200, data: Data("[1]".utf8), now: now)) {
            XCTAssertEqual($0 as? CatalogFailure, .malformed)
        }
    }

    func testRequestShape() {
        let request = ChatContext.request(username: "alpha_1")
        XCTAssertEqual(request.url?.absoluteString, "https://chaturbate.com/api/chatvideocontext/alpha_1/")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Requested-With"), "XMLHttpRequest")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Referer"), "https://chaturbate.com/alpha_1/")
        XCTAssertNotNil(request.value(forHTTPHeaderField: "User-Agent"))
        XCTAssertLessThanOrEqual(request.timeoutInterval, 10)
        XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
    }

    func testCacheExpiryAndCaseInsensitivity() {
        let cache = FavoriteCache().storing(["alpha": .offline], at: now)
        XCTAssertEqual(cache.fresh(for: "ALPHA", now: now.addingTimeInterval(FavoriteCache.ttl)), .offline)
        XCTAssertNil(cache.fresh(for: "alpha", now: now.addingTimeInterval(FavoriteCache.ttl + 1)))
        XCTAssertEqual(cache.status(for: "Alpha"), .offline)
        XCTAssertNil(cache.status(for: "beta"))
        let kept = cache.storing(["beta": .notFound], at: now).pruned(keeping: ["Beta"])
        XCTAssertNil(kept.status(for: "alpha"))
        XCTAssertEqual(kept.status(for: "beta"), .notFound)
    }

    func testPlanPrefersFreshCacheThenCatalogThenAsksNetwork() {
        var catalog = KnownCatalog()
        catalog.record([catalogRoom("Alpha"), catalogRoom("beta", show: .privateShow), catalogRoom("cache")], firstPage: true, at: now)
        let cache = FavoriteCache().storing(["cache": .notFound], at: now)
        let plan = FavoriteResolver.plan(
            favorites: ["alpha", "Beta", "cache", "gamma", "ALPHA", "gamma"], catalog: catalog, cache: cache, now: now.addingTimeInterval(60)
        )
        XCTAssertEqual(plan.resolved["alpha"]?.room?.username, "Alpha")
        XCTAssertEqual(plan.resolved["beta"], .offline)
        XCTAssertEqual(plan.resolved["cache"], .notFound)
        XCTAssertEqual(Set(plan.fromCatalog.keys), ["alpha", "beta"])
        XCTAssertEqual(plan.pending, ["gamma"])
    }

    func testPlanIgnoresStaleCatalogAndCache() {
        var catalog = KnownCatalog()
        catalog.record([catalogRoom("alpha")], firstPage: true, at: now)
        let cache = FavoriteCache().storing(["beta": .offline], at: now)
        let later = now.addingTimeInterval(FavoriteCache.ttl + 1)
        let plan = FavoriteResolver.plan(favorites: ["alpha", "beta"], catalog: catalog, cache: cache, now: later)
        XCTAssertTrue(plan.resolved.isEmpty)
        XCTAssertEqual(plan.pending, ["alpha", "beta"])
    }

    func testKnownCatalogAppendsAfterFirstPage() {
        var catalog = KnownCatalog()
        catalog.record([catalogRoom("a")], firstPage: true, at: now)
        catalog.record([catalogRoom("b")], firstPage: false, at: now)
        XCTAssertEqual(catalog.rooms.map(\.username), ["a", "b"])
        catalog.record([catalogRoom("c")], firstPage: true, at: now)
        XCTAssertEqual(catalog.rooms.map(\.username), ["c"])
    }

    func testOnlineRoomsSortByViewersThenFavoriteOrder() {
        let statuses: [String: FavoriteStatus] = [
            "a": .online(catalogRoom("a", viewers: 5)), "b": .online(catalogRoom("b", viewers: 50)),
            "c": .online(catalogRoom("c", viewers: 5)), "d": .offline, "e": .notFound
        ]
        let rooms = FavoriteResolver.onlineRooms(favorites: ["a", "d", "b", "e", "c", "A"], statuses: statuses)
        XCTAssertEqual(rooms.map(\.username), ["b", "a", "c"])
    }

    func testFetcherNeverExceedsConcurrencyLimit() async {
        let names = (0..<12).map { "user\($0)" }
        let client = RecordingClient { publicContext($0) }
        let result = await FavoriteFetcher(client: client).fetch(names)
        XCTAssertEqual(result.statuses.count, 12)
        XCTAssertFalse(result.challenged)
        XCTAssertEqual(client.requested.count, 12)
        XCTAssertLessThanOrEqual(client.peak, FavoriteFetcher.maxConcurrent)
        XCTAssertEqual(client.peak, FavoriteFetcher.maxConcurrent)
    }

    func testFetcherSkipsFailuresAndMarksMissing() async {
        let client = RecordingClient { name in
            switch name {
            case "gone": return json("", status: 404)
            case "broken": return json("", status: 500)
            case "away": return json(#"{"room_status":"away","broadcaster_username":"away"}"#)
            default: return publicContext(name)
            }
        }
        let result = await FavoriteFetcher(client: client, backoff: nil).fetch(["live", "gone", "broken", "away"])
        XCTAssertEqual(result.statuses["gone"], .notFound)
        XCTAssertEqual(result.statuses["away"], .offline)
        XCTAssertNotNil(result.statuses["live"]?.room)
        XCTAssertNil(result.statuses["broken"])
        XCTAssertFalse(result.challenged)
    }

    func testFetcherStopsOnChallenge() async {
        let page = HTTPResult(status: 403, data: Data("<html>Just a moment</html>".utf8))
        let client = RecordingClient { _ in page }
        let names = (0..<20).map { "user\($0)" }
        let result = await FavoriteFetcher(client: client).fetch(names)
        XCTAssertTrue(result.challenged)
        XCTAssertLessThanOrEqual(client.requested.count, FavoriteFetcher.maxConcurrent)
        XCTAssertTrue(result.statuses.isEmpty)
    }

    func testFetcherWithNoNamesDoesNothing() async {
        let client = RecordingClient { publicContext($0) }
        let result = await FavoriteFetcher(client: client).fetch([])
        XCTAssertTrue(result.statuses.isEmpty)
        XCTAssertTrue(client.requested.isEmpty)
    }

    func testExchangeRoundTripAndValidation() throws {
        let data = try FavoriteExchange.encode(["alpha", "beta_2"])
        XCTAssertEqual(try FavoriteExchange.decode(data), .init(names: ["alpha", "beta_2"], rejected: 0))
        let bare = Data(#"["ok_1"," padded ","bad name","x",""]"#.utf8)
        XCTAssertEqual(try FavoriteExchange.decode(bare), .init(names: ["ok_1", "padded"], rejected: 3))
        XCTAssertThrowsError(try FavoriteExchange.decode(Data("{".utf8))) { XCTAssertEqual($0 as? FavoriteExchange.Failure, .malformed) }
        let huge = Data(repeating: 0x20, count: Preferences.maxFileBytes + 1)
        XCTAssertThrowsError(try FavoriteExchange.decode(huge)) { XCTAssertEqual($0 as? FavoriteExchange.Failure, .tooLarge) }
    }

    func testImportMergesDedupesAndHonorsLimits() {
        let base = Curation(favorites: ["alpha", "beta"], blocked: ["bad"])
        let merged = base.importing(["BETA", "gamma", "bad", "gamma", "delta"])
        XCTAssertEqual(merged.curation.favorites, ["alpha", "beta", "gamma", "delta"])
        XCTAssertEqual(merged.added, 2)

        let full = Curation(favorites: (0..<Curation.maxEntries).map { "u\($0)" })
        let capped = full.importing(["extra1", "extra2"])
        XCTAssertEqual(capped.curation.favorites.count, Curation.maxEntries)
        XCTAssertEqual(capped.added, 0)
        XCTAssertFalse(capped.curation.isFavorite("extra1"))
    }

    func testUsernameValidation() {
        XCTAssertTrue(Room.isValidUsername("Alpha_9"))
        XCTAssertFalse(Room.isValidUsername("a"))
        XCTAssertFalse(Room.isValidUsername("has space"))
        XCTAssertFalse(Room.isValidUsername(String(repeating: "a", count: 31)))
        XCTAssertFalse(Room.isValidUsername("名字很长"))
    }

    /// 审计发现 4：Cam4 收藏来源只解析当前站点模式的收藏，过期 Chaturbate 收藏的质询不能阻断 Cam4 收藏页。
    func testCam4ModeFavoritePlanSkipsChaturbateChallenge() async {
        let cam4Room = Room(username: "sample_one", viewers: 12, show: .publicShow, site: .cam4)
        let cache = FavoriteCache().storing(["c4:sample_one": .online(cam4Room)], at: now)
        let allFavorites = ["alice", "c4:sample_one"]

        let cam4Plan = FavoriteResolver.plan(
            favorites: allFavorites, mode: .cam4, catalog: KnownCatalog(), cache: cache, now: now
        )
        XCTAssertEqual(cam4Plan.pending, [], "Cam4 模式不把过期 Chaturbate 收藏放进待查名单")

        let challenge = HTTPResult(status: 403, data: Data("<html>Just a moment</html>".utf8))
        let cbClient = RecordingClient { _ in challenge }
        let cbPending = cam4Plan.pending.filter { RoomKey.site(ofRef: $0) == .chaturbate }
        let fetched = await FavoriteFetcher(client: cbClient).fetch(cbPending)
        XCTAssertFalse(fetched.challenged, "未请求 Chaturbate，不应触发质询")
        XCTAssertTrue(cbClient.requested.isEmpty)

        let rooms = FavoriteResolver.onlineRooms(favorites: ["c4:sample_one"], statuses: cam4Plan.resolved)
        XCTAssertEqual(rooms.map(\.ref), ["c4:sample_one"])

        let panelPlan = FavoriteResolver.plan(
            favorites: allFavorites, mode: nil, catalog: KnownCatalog(), cache: cache, now: now
        )
        XCTAssertEqual(panelPlan.pending, ["alice"], "收藏面板未限定站点时仍查询全部收藏")
    }

    /// 盲审 6 / 复审 2：Cam4 公开房没有流地址时，命中目录快照也应判为 offline 而非 online。
    func testCam4CatalogRoomWithoutStreamIsOffline() {
        var catalog = KnownCatalog()
        let streamless = Room(username: "no_stream", viewers: 15, show: .publicShow, site: .cam4, stream: nil)
        let playable = Room(
            username: "has_stream", viewers: 10, show: .publicShow, site: .cam4,
            stream: URL(string: "https://preview.example/has_stream/master.m3u8")
        )
        let cbRoom = Room(username: "cb_pub", viewers: 8, show: .publicShow, site: .chaturbate, stream: nil)
        catalog.record([streamless, playable, cbRoom], firstPage: true, at: now)

        let plan = FavoriteResolver.plan(
            favorites: ["c4:no_stream", "c4:has_stream", "cb_pub"], catalog: catalog, cache: FavoriteCache(), now: now
        )
        XCTAssertEqual(plan.resolved["c4:no_stream"], .offline, "无流 Cam4 房不能按在线缓存")
        XCTAssertEqual(plan.resolved["c4:has_stream"], .online(playable))
        XCTAssertEqual(plan.resolved["cb_pub"], .online(cbRoom), "Chaturbate 目录本就无 stream，行为不变")
    }
}
