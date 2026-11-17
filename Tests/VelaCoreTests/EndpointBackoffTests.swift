import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import VelaCore

/// 可调的时钟。
private final class Clock: @unchecked Sendable {
    private let lock = NSLock()
    private var value = Date(timeIntervalSince1970: 50_000)

    var now: Date {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func advance(_ seconds: TimeInterval) {
        lock.lock()
        value = value.addingTimeInterval(seconds)
        lock.unlock()
    }
}

/// 按顺序回放响应并记请求数的假网络层。
private final class Replay: HTTPClient, @unchecked Sendable {
    private let lock = NSLock()
    private var queue: [HTTPResult]
    private(set) var count = 0

    init(_ queue: [HTTPResult]) { self.queue = queue }

    func send(_ request: URLRequest) async throws -> HTTPResult { take() }

    private func take() -> HTTPResult {
        lock.lock()
        defer { lock.unlock() }
        count += 1
        return queue.isEmpty ? HTTPResult(status: 200, data: Data("{}".utf8)) : queue.removeFirst()
    }
}

private func body(_ text: String, status: Int = 200) -> HTTPResult { HTTPResult(status: status, data: Data(text.utf8)) }

private let rateLimited = body("<html><body>Too many requests</body></html>", status: 429)

final class EndpointBackoffTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_000)

    func testWindowIsPerEndpointAndThirtySeconds() {
        var backoff = EndpointBackoff()
        XCTAssertTrue(backoff.allows(.streamURL, now: t0))
        backoff.record(.streamURL, now: t0)
        XCTAssertFalse(backoff.allows(.streamURL, now: t0.addingTimeInterval(29.9)))
        XCTAssertTrue(backoff.allows(.streamURL, now: t0.addingTimeInterval(EndpointBackoff.window)))
        XCTAssertEqual(EndpointBackoff.window, 30)
        for other in SiteEndpoint.allCases where other != .streamURL {
            XCTAssertTrue(backoff.allows(other, now: t0), "\(other) 不受影响")
        }
        backoff.reset()
        XCTAssertTrue(backoff.allows(.streamURL, now: t0))
    }

    func testShapeClassification() {
        XCTAssertTrue(ResponseShape.looksLikeJSON(Data(" \n{\"a\":1}".utf8)))
        XCTAssertTrue(ResponseShape.looksLikeJSON(Data("[1]".utf8)))
        XCTAssertTrue(ResponseShape.looksLikeJSON(Data([0xEF, 0xBB, 0xBF]) + Data("{}".utf8)))
        XCTAssertFalse(ResponseShape.looksLikeJSON(Data()))
        XCTAssertFalse(ResponseShape.looksLikeJSON(Data("<html>".utf8)))

        XCTAssertTrue(ResponseShape.shouldBackOff(status: 200, data: Data("<html>busy</html>".utf8)))
        XCTAssertTrue(ResponseShape.shouldBackOff(status: 502, data: Data()))
        XCTAssertFalse(ResponseShape.shouldBackOff(status: 200, data: Data("{}".utf8)))
        XCTAssertFalse(ResponseShape.shouldBackOff(status: 403, data: Data("<title>Just a moment...</title>".utf8)), "质询页走确认流程")
        XCTAssertFalse(ResponseShape.shouldBackOff(status: 404, data: Data("<html>404</html>".utf8), definitive: [404]))
    }

    func testChallengeMarkers() {
        XCTAssertTrue(looksLikeChallenge(Data("<html><title>Just a moment...</title></html>".utf8)))
        XCTAssertTrue(looksLikeChallenge(Data("<html><h1>Verify your age</h1></html>".utf8)))
        // 年龄页标记出现在较长的页头之后也能认出。
        let padded = "<html><head>" + String(repeating: "<meta name=\"x\" content=\"y\">", count: 40) + "</head><body>VERIFY YOUR AGE</body>"
        XCTAssertTrue(looksLikeChallenge(Data(padded.utf8)))
        // JSON 正文从不算质询，即使房间标题里恰好有这些字。
        XCTAssertFalse(looksLikeChallenge(Data(#"{"room_title":"just a moment, verify your age"}"#.utf8)))
        XCTAssertFalse(looksLikeChallenge(Data("<html>busy</html>".utf8)))
    }

    func testAgePageIsChallengeForStreamAndCatalog() {
        let page = Data("<html><title>Verify your age</title></html>".utf8)
        XCTAssertThrowsError(try StreamDecoder.decode(status: 200, data: page)) {
            XCTAssertEqual($0 as? StreamDecodeError, .challenge)
        }
        XCTAssertThrowsError(try ChatContext.decode(status: 200, data: page, now: t0)) {
            XCTAssertEqual($0 as? CatalogFailure, .challenge)
        }
    }

    func testStreamFetchBacksOffAfterNonJSON() async throws {
        let clock = Clock()
        let box = EndpointBackoffBox(now: { clock.now })
        let client = Replay([rateLimited, body(#"{"success":true,"url":"https://e.example/a.m3u8","room_status":"public"}"#)])
        do {
            _ = try await StreamDecoder.fetch(username: "a", client: client, backoff: box)
            XCTFail("expected unavailable")
        } catch {
            XCTAssertEqual(error as? StreamDecodeError, .unavailable(status: 429))
            XCTAssertEqual((error as? StreamDecodeError)?.failure, .unavailable)
        }
        // 窗口内不再发请求。
        clock.advance(10)
        do {
            _ = try await StreamDecoder.fetch(username: "b", client: client, backoff: box)
            XCTFail("expected backoff")
        } catch {
            XCTAssertEqual(error as? EndpointBackedOff, EndpointBackedOff(.streamURL), "退避中未发请求：与真实 403/503 分开")
            XCTAssertNil(error as? StreamDecodeError)
        }
        XCTAssertEqual(client.count, 1)
        clock.advance(20)
        let ticket = try await StreamDecoder.fetch(username: "c", client: client, backoff: box)
        XCTAssertTrue(ticket.isPlayable)
        XCTAssertEqual(client.count, 2)
    }

    func testStreamFetchChallengeDoesNotBackOff() async {
        let box = EndpointBackoffBox()
        let client = Replay([body("<title>Just a moment...</title>", status: 403)])
        do {
            _ = try await StreamDecoder.fetch(username: "a", client: client, backoff: box)
            XCTFail("expected challenge")
        } catch {
            XCTAssertEqual(error as? StreamDecodeError, .challenge)
        }
        XCTAssertTrue(box.allows(.streamURL))
    }

    func testCatalogBacksOffAndKeepsFailureKind() async {
        let box = EndpointBackoffBox()
        let api = CatalogAPI(client: Replay([body("<html>busy</html>")]), backoff: box)
        do {
            _ = try await api.fetch(CatalogQuery())
            XCTFail("expected malformed")
        } catch {
            XCTAssertEqual(error as? CatalogFailure, .malformed)
        }
        do {
            _ = try await api.fetch(CatalogQuery())
            XCTFail("expected backoff")
        } catch {
            XCTAssertEqual(error as? EndpointBackedOff, EndpointBackedOff(.catalog))
        }
        XCTAssertFalse(box.allows(.catalog))
        XCTAssertTrue(box.allows(.chatContext))
    }

    func testFavoriteFetcherBacksOffWithoutCachingOrChallenge() async {
        let box = EndpointBackoffBox()
        let client = Replay([body("denied", status: 403)] + Array(repeating: body("<html>x</html>"), count: 5))
        let result = await FavoriteFetcher(client: client, concurrency: 1, backoff: box).fetch(["a", "b", "c"])
        XCTAssertTrue(result.statuses.isEmpty, "状态未知，不写缓存")
        XCTAssertFalse(result.challenged, "普通 403 文本按暂时不可用，不弹确认页")
        XCTAssertEqual(client.count, 1, "退避后同一接口不再发请求")
        XCTAssertFalse(box.allows(.chatContext))
    }

    func testFavoriteFetcherDefinitiveStatusesIgnoreBody() async {
        let box = EndpointBackoffBox()
        let client = Replay([body("<html>Not Found</html>", status: 404)])
        let result = await FavoriteFetcher(client: client, backoff: box).fetch(["gone"])
        XCTAssertEqual(result.statuses["gone"], .notFound)
        XCTAssertTrue(box.allows(.chatContext))
    }

    func testLastBroadcastFetcherBacksOff() async {
        let box = EndpointBackoffBox()
        let client = Replay([rateLimited, body(#"{"last_broadcast":"2026-01-01T00:00:00Z"}"#)])
        let result = await LastBroadcastFetcher(client: client, concurrency: 1, backoff: box).fetch(["a", "b"])
        XCTAssertTrue(result.found.isEmpty)
        XCTAssertFalse(result.challenged)
        XCTAssertEqual(client.count, 1)
        XCTAssertFalse(box.allows(.bioContext))
        XCTAssertTrue(box.allows(.chatContext))
    }

    func testNoteAfterCancelSwitchesWaitingNotes() {
        XCTAssertEqual(AwayRetry.noteAfterCancel(AwayRetry.waitingNote), AwayRetry.heldNote)
        XCTAssertEqual(AwayRetry.noteAfterCancel(EndpointBackoff.waitingNote), EndpointBackoff.heldNote)
        XCTAssertEqual(AwayRetry.noteAfterCancel("别的提示"), "别的提示")
        XCTAssertNil(AwayRetry.noteAfterCancel(nil))
    }
}
