import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import VelaCore

/// 线程安全的假网络层：记录同时在途数，按路径里的用户名回复。
private final class BioClient: HTTPClient, @unchecked Sendable {
    private let lock = NSLock()
    private var inFlight = 0
    private var peakValue = 0
    private var names: [String] = []
    private let respond: @Sendable (String) -> HTTPResult

    init(respond: @escaping @Sendable (String) -> HTTPResult) { self.respond = respond }

    var peak: Int { locked { peakValue } }
    var requested: [String] { locked { names } }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    private func enter(_ name: String) {
        locked {
            inFlight += 1
            peakValue = max(peakValue, inFlight)
            names.append(name)
        }
    }

    private func leave() { locked { inFlight -= 1 } }

    func send(_ request: URLRequest) async throws -> HTTPResult {
        let name = request.url?.pathComponents[3] ?? ""
        enter(name)
        try? await Task.sleep(nanoseconds: 10_000_000)
        leave()
        return respond(name)
    }
}

private func json(_ text: String, status: Int = 200) -> HTTPResult { HTTPResult(status: status, data: Data(text.utf8)) }

final class LastBroadcastTests: XCTestCase {
    /// 默认共用的非 JSON 退避记录跨用例清空，免得前一个用例的退避挡住后一个。
    override func setUp() {
        super.setUp()
        EndpointBackoffBox.shared.reset()
    }

    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func decode(_ result: HTTPResult) throws -> Date? {
        try BioContext.decode(status: result.status, data: result.data, now: now).at
    }

    func testRequestShape() {
        let request = BioContext.request(username: "alpha_1")
        XCTAssertEqual(request.url?.absoluteString, "https://chaturbate.com/api/biocontext/alpha_1/")
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.timeoutInterval, 8)
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Requested-With"), "XMLHttpRequest")
    }

    func testDecodesISOLastBroadcast() throws {
        let date = try decode(json(#"{"last_broadcast":"2027-01-15T07:00:00Z","follower_count":3}"#))
        XCTAssertEqual(date, ISO8601DateFormatter().date(from: "2027-01-15T07:00:00Z"))
        let fractional = try decode(json(#"{"last_broadcast":"2027-01-15T07:00:00.250Z"}"#))
        let base = ISO8601DateFormatter().date(from: "2027-01-15T07:00:00Z")?.timeIntervalSince1970 ?? 0
        XCTAssertEqual(fractional?.timeIntervalSince1970 ?? 0, base + 0.25, accuracy: 0.001)
    }

    func testDecodesPlainAndNumericForms() throws {
        let plain = try decode(json(#"{"last_broadcast":"2027-01-15 07:00:00"}"#))
        XCTAssertEqual(plain, ISO8601DateFormatter().date(from: "2027-01-15T07:00:00Z"))
        XCTAssertEqual(try decode(json(#"{"last_broadcast":1799990000}"#)), Date(timeIntervalSince1970: 1_799_990_000))
        XCTAssertEqual(try decode(json(#"{"last_broadcast":1799990000000}"#)), Date(timeIntervalSince1970: 1_799_990_000))
    }

    func testFallsBackToSecondsSinceLastBroadcast() throws {
        XCTAssertEqual(try decode(json(#"{"last_broadcast":null,"time_since_last_broadcast":"3600"}"#)), now.addingTimeInterval(-3600))
        XCTAssertNil(try decode(json(#"{"time_since_last_broadcast":"3 hours ago"}"#)))
    }

    func testFutureTimeIsClampedToNow() throws {
        XCTAssertEqual(try decode(json(#"{"last_broadcast":1900000000}"#)), now)
    }

    func testMissingAndDeletedAreAnswersWithoutTime() throws {
        XCTAssertNil(try decode(json("{}")))
        XCTAssertNil(try decode(json("", status: 404)))
        XCTAssertNil(try decode(json(#"{"detail":"gone"}"#, status: 403)))
    }

    func testChallengeAndBadPayloadThrow() {
        XCTAssertThrowsError(try decode(json("<html>Just a moment...</html>"))) { XCTAssertEqual($0 as? CatalogFailure, .challenge) }
        XCTAssertThrowsError(try decode(json("<p>nope</p>", status: 403))) { XCTAssertEqual($0 as? CatalogFailure, .challenge) }
        XCTAssertThrowsError(try decode(json("not json"))) { XCTAssertEqual($0 as? CatalogFailure, .malformed) }
        XCTAssertThrowsError(try decode(json("{}", status: 500))) { XCTAssertEqual($0 as? CatalogFailure, .status(500)) }
    }

    func testRelativeCopy() {
        func ago(_ seconds: TimeInterval) -> String { BroadcastAgo.string(since: now.addingTimeInterval(-seconds), now: now) }
        XCTAssertEqual(ago(20), "上次开播 刚刚")
        XCTAssertEqual(ago(-50), "上次开播 刚刚")
        XCTAssertEqual(ago(5 * 60), "上次开播 5 分钟前")
        XCTAssertEqual(ago(3 * 3600 + 59), "上次开播 3 小时前")
        XCTAssertEqual(ago(2 * 86_400), "上次开播 2 天前")
        XCTAssertEqual(ago(65 * 86_400), "上次开播 2 个月前")
        XCTAssertEqual(ago(800 * 86_400), "上次开播 2 年前")
    }

    func testCacheExpiresAfterThirtyMinutes() {
        let cache = LastBroadcastCache().storing(["Alpha": LastBroadcast(at: now), "beta": LastBroadcast(at: nil)], at: now)
        XCTAssertEqual(cache.pending(["alpha", "BETA", "gamma", "Gamma"], now: now.addingTimeInterval(60)), ["gamma"])
        XCTAssertEqual(cache.pending(["alpha", "gamma"], now: now.addingTimeInterval(LastBroadcastCache.ttl + 1)), ["alpha", "gamma"])
        XCTAssertEqual(cache.dates, ["alpha": now])
        XCTAssertEqual(cache.pruned(keeping: ["beta"]).dates, [:])
    }

    func testFetcherRespectsConcurrencyLimit() async {
        let client = BioClient { _ in json(#"{"last_broadcast":1799990000}"#) }
        let names = (0..<10).map { "user\($0)" }
        let result = await LastBroadcastFetcher(client: client, now: { Date(timeIntervalSince1970: 1_800_000_000) }).fetch(names)
        XCTAssertEqual(result.found.count, 10)
        XCTAssertFalse(result.challenged)
        XCTAssertLessThanOrEqual(client.peak, FavoriteFetcher.maxConcurrent)
        XCTAssertEqual(result.found["user3"], LastBroadcast(at: Date(timeIntervalSince1970: 1_799_990_000)))
    }

    func testFetcherStopsOnChallengeAndSkipsFailures() async {
        let client = BioClient { name in
            if name == "b" { return json("<html>just a moment</html>") }
            if name == "a" { return json("{}", status: 500) }
            return json("{}")
        }
        let names = ["a", "b"] + (0..<20).map { "n\($0)" }
        let result = await LastBroadcastFetcher(client: client, concurrency: 1).fetch(names)
        XCTAssertTrue(result.challenged)
        XCTAssertNil(result.found["a"])
        XCTAssertEqual(client.requested, ["a", "b"])
    }

    func testBoardShowsLastBroadcastAndSortsOfflineByRecency() {
        let statuses: [String: FavoriteStatus] = [
            "old": .offline, "new": .offline, "nodate": .offline, "gone": .notFound, "live": .online(Room(username: "live"))
        ]
        let dates = ["old": now.addingTimeInterval(-3 * 86_400), "new": now.addingTimeInterval(-2 * 3600)]
        let board = FavoritesBoard.make(
            favorites: ["nodate", "old", "gone", "live", "new", "unseen"], statuses: statuses, catalog: [],
            lastBroadcast: dates, now: now
        )
        XCTAssertEqual(board.online.map(\.username), ["live"])
        XCTAssertEqual(board.others.map(\.username), ["new", "old", "nodate", "unseen", "gone"])
        XCTAssertEqual(board.others.map(\.detail), ["上次开播 2 小时前", "上次开播 3 天前", "离线", "未查询", "账号不存在"])
    }
}
