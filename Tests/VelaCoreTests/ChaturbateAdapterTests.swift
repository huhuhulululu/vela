import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import VelaCore

/// ChaturbateAdapter 必须与 CatalogAPI / StreamSourcing 的现路径一致（不含侧车）。
final class ChaturbateAdapterTests: XCTestCase {
    private let source = "https://edge.example/v1/origin.m3u8?token=t"
    private let edge = "https://edge.example/v1/ajax.m3u8?token=u"

    func testDirectoryMatchesCatalogAPI() async throws {
        let body = catalog([("sample_one", "public", 4)])
        let query = CatalogQuery(gender: .female, limit: 50, offset: 0)
        let apiBox = EndpointBackoffBox()
        let adapterBox = EndpointBackoffBox()
        let api = try await CatalogAPI(client: QueueClient([HTTPResult(status: 200, data: body)]), backoff: apiBox).fetch(query)
        let adapter = try await ChaturbateAdapter(client: QueueClient([HTTPResult(status: 200, data: body)]), backoff: adapterBox)
            .fetchDirectory(query)
        XCTAssertEqual(adapter, api)
        XCTAssertEqual(adapter.rooms.map(\.username), ["sample_one"])
        XCTAssertEqual(adapter.rooms.map(\.site), [.chaturbate])
    }

    func testDirectoryChallengeStatusAndBackoffMatchCatalogAPI() async throws {
        let query = CatalogQuery()
        let challenge = Data("<html>Just a moment</html>".utf8)
        try await assertSameFailure(query: query, result: HTTPResult(status: 403, data: challenge)) { error in
            XCTAssertEqual(error as? CatalogFailure, .challenge)
        }
        try await assertSameFailure(query: query, result: HTTPResult(status: 400, data: Data("{}".utf8))) { error in
            XCTAssertEqual(error as? CatalogFailure, .status(400))
        }
        let html = HTTPResult(status: 200, data: Data("<html>busy</html>".utf8))
        let apiBox = EndpointBackoffBox()
        let adapterBox = EndpointBackoffBox()
        await assertCatalogError({ try await CatalogAPI(client: QueueClient([html]), backoff: apiBox).fetch(query) }) { error in
            XCTAssertEqual(error as? CatalogFailure, .malformed)
        }
        await assertCatalogError({ try await ChaturbateAdapter(client: QueueClient([html]), backoff: adapterBox).fetchDirectory(query) }) { error in
            XCTAssertEqual(error as? CatalogFailure, .malformed)
        }
        XCTAssertFalse(apiBox.allows(.catalog))
        XCTAssertFalse(adapterBox.allows(.catalog))
        let held = EndpointBackoffBox()
        held.record(.catalog)
        let client = QueueClient([html])
        await assertCatalogError({ try await ChaturbateAdapter(client: client, backoff: held).fetchDirectory(query) }) { error in
            XCTAssertEqual(error as? EndpointBackedOff, EndpointBackedOff(.catalog))
        }
        XCTAssertTrue(client.requests.isEmpty)
    }

    func testPublicSourceMatchesStreamSourcingAndSkipsAjax() async throws {
        let client = QueueClient([HTTPResult(status: 200, data: context("public", source: source))])
        let room = Room(username: "sample_one", show: .publicShow)
        let ticket = try await ChaturbateAdapter(client: client, backoff: EndpointBackoffBox()).resolveStream(room)
        let probe = ChatContext.probe(status: 200, data: context("public", source: source))
        guard case .use(let expected) = StreamSourcing.first(probe) else { return XCTFail("应直接采用") }
        XCTAssertEqual(ticket, SourcedTicket(ticket: expected, origin: .chatContext))
        XCTAssertEqual(client.requests.count, 1)
        XCTAssertEqual(client.requests[0].url, ChatContext.url(username: "sample_one"))
    }

    func testChallenge403503BackoffAndGeoMatchExistingPath() async throws {
        let challenge = Data("<title>Just a moment...</title>".utf8)
        let client = QueueClient([
            HTTPResult(status: 403, data: challenge),
            HTTPResult(status: 403, data: challenge)
        ])
        do {
            _ = try await ChaturbateAdapter(client: client, backoff: EndpointBackoffBox())
                .resolveStream(Room(username: "sample_one"))
            XCTFail("质询应抛出")
        } catch let error as StreamDecodeError {
            XCTAssertEqual(error, .challenge)
        }
        XCTAssertEqual(client.requests.count, 2)

        let denied = QueueClient([
            HTTPResult(status: 200, data: context("public")),
            HTTPResult(status: 403, data: Data(#"{"detail":"no"}"#.utf8))
        ])
        do {
            _ = try await ChaturbateAdapter(client: denied, backoff: EndpointBackoffBox()).resolveStream(Room(username: "sample_one"))
            XCTFail("403")
        } catch let error as StreamDecodeError {
            XCTAssertEqual(error, .unavailable(status: 403))
        }

        let busy = QueueClient([
            HTTPResult(status: 200, data: context("public")),
            HTTPResult(status: 503, data: Data(#"{"ok":false}"#.utf8))
        ])
        do {
            _ = try await ChaturbateAdapter(client: busy, backoff: EndpointBackoffBox()).resolveStream(Room(username: "sample_one"))
            XCTFail("503")
        } catch let error as StreamDecodeError {
            XCTAssertEqual(error, .unavailable(status: 503))
        }

        let box = EndpointBackoffBox()
        let html = HTTPResult(status: 200, data: Data("<html>busy</html>".utf8))
        let nonJSON = QueueClient([
            HTTPResult(status: 200, data: context("public")),
            html
        ])
        do {
            _ = try await ChaturbateAdapter(client: nonJSON, backoff: box).resolveStream(Room(username: "sample_one"))
            XCTFail("非 JSON")
        } catch let error as StreamDecodeError {
            XCTAssertEqual(error, .unavailable(status: 200))
        }
        XCTAssertFalse(box.allows(.streamURL))
        let held = QueueClient([HTTPResult(status: 200, data: context("public")), html])
        let before = held.requests.count
        do {
            _ = try await ChaturbateAdapter(client: held, backoff: box).resolveStream(Room(username: "sample_one"))
            XCTFail("退避中")
        } catch let error as EndpointBackedOff {
            XCTAssertEqual(error, EndpointBackedOff(.streamURL))
        }
        XCTAssertEqual(held.requests.count, before + 1, "chatvideocontext 仍可问，ajax 不发")

        let geoClient = QueueClient([
            HTTPResult(status: 200, data: context("public")),
            HTTPResult(status: 200, data: Data(#"{"success":1,"url":"","room_status":"public"}"#.utf8))
        ])
        let geo = try await ChaturbateAdapter(client: geoClient, backoff: EndpointBackoffBox()).resolveStream(Room(username: "sample_one"))
        let probe = ChatContext.probe(status: 200, data: context("public"))
        let ajax = try StreamDecoder.decode(status: 200, data: Data(#"{"success":1,"url":"","room_status":"public"}"#.utf8))
        XCTAssertEqual(geo.ticket, StreamSourcing.settle(probe: probe, fallback: ajax))
        XCTAssertEqual(geo.ticket.failure, .geo)
        XCTAssertEqual(geo.origin, .edge)
    }

    private func assertSameFailure(
        query: CatalogQuery, result: HTTPResult, check: (Error) -> Void
    ) async throws {
        let api = CatalogAPI(client: QueueClient([result]), backoff: EndpointBackoffBox())
        let adapter = ChaturbateAdapter(client: QueueClient([result]), backoff: EndpointBackoffBox())
        var apiError: Error?
        var adapterError: Error?
        do { _ = try await api.fetch(query) } catch { apiError = error }
        do { _ = try await adapter.fetchDirectory(query) } catch { adapterError = error }
        let left = try XCTUnwrap(apiError)
        let right = try XCTUnwrap(adapterError)
        check(left)
        check(right)
        XCTAssertEqual(left as? CatalogFailure, right as? CatalogFailure)
    }

    private func assertCatalogError(_ body: () async throws -> CatalogPage, check: (Error) -> Void) async {
        do {
            _ = try await body()
            XCTFail("应失败")
        } catch {
            check(error)
        }
    }

    private func catalog(_ rooms: [(String, String, Int)]) -> Data {
        let rows = rooms.map { name, show, viewers in
            #"{"username":"\#(name)","current_show":"\#(show)","num_users":\#(viewers)}"#
        }.joined(separator: ",")
        return Data(#"{"rooms":[\#(rows)],"total_count":\#(rooms.count)}"#.utf8)
    }

    private func context(_ status: String, source: String? = nil) -> Data {
        let field = source.map { #","hls_source":"\#($0)""# } ?? ""
        return Data(#"{"room_status":"\#(status)","broadcaster_username":"sample_one"\#(field)}"#.utf8)
    }
}

private final class QueueClient: HTTPClient, @unchecked Sendable {
    var results: [HTTPResult]
    var requests: [URLRequest] = []
    init(_ results: [HTTPResult]) { self.results = results }
    func send(_ request: URLRequest) async throws -> HTTPResult {
        requests.append(request)
        return results.isEmpty ? HTTPResult(status: 500, data: Data()) : results.removeFirst()
    }
}
