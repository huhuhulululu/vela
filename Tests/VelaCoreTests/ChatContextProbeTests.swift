import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import VelaCore

private struct Fixed: HTTPClient {
    let result: HTTPResult
    func send(_ request: URLRequest) async throws -> HTTPResult { result }
}

/// 起播探测：401 / 404 是确定答案，空正文或 HTML 正文不登记全局退避。
final class ChatContextProbeTests: XCTestCase {
    func testDefinitiveStatusesWithEmptyOrHTMLBodyAreFailedNotNotJSON() {
        for status in [401, 404] {
            XCTAssertEqual(ChatContext.probe(status: status, data: Data()), .failed, "\(status) 空正文")
            XCTAssertEqual(ChatContext.probe(status: status, data: Data("<html>Not Found</html>".utf8)), .failed, "\(status) HTML")
        }
    }

    func testNonDefinitiveEmptyBodyStillNotJSON() {
        XCTAssertEqual(ChatContext.probe(status: 502, data: Data()), .notJSON)
        XCTAssertEqual(ChatContext.probe(status: 200, data: Data("<html>busy</html>".utf8)), .notJSON)
    }

    func testDefinitiveStatusDoesNotRegisterBackoff() async throws {
        for (status, text) in [(401, ""), (404, "<html>Not Found</html>")] {
            let box = EndpointBackoffBox()
            let client = Fixed(result: HTTPResult(status: status, data: Data(text.utf8)))
            let outcome = try await ChatContext.probe(username: "gone", client: client, backoff: box)
            XCTAssertEqual(outcome, .failed)
            XCTAssertTrue(box.allows(.chatContext), "\(status) 不应退避")
        }
    }

    func testRateLimitPageStillBacksOff() async throws {
        let box = EndpointBackoffBox()
        let client = Fixed(result: HTTPResult(status: 429, data: Data("<html>Too many requests</html>".utf8)))
        let outcome = try await ChatContext.probe(username: "x", client: client, backoff: box)
        XCTAssertEqual(outcome, .notJSON)
        XCTAssertFalse(box.allows(.chatContext))
    }
}
