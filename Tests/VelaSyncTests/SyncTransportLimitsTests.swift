#if canImport(CryptoKit)
import Foundation
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 安全审查 M1、M2：服务端（或中间人）给的 Retry-After 与响应体不能让客户端崩溃或无限读。
final class SyncTransportLimitsTests: XCTestCase {
    func testRetryAfterIsClampedToOneDay() {
        XCTAssertEqual(SyncHTTPTransport.parse(status: 429, body: Data(), retryAfter: Int.max, kind: .get), .rateLimited(retryAfter: 86_400))
        XCTAssertEqual(SyncHTTPTransport.parse(status: 429, body: Data(), retryAfter: -5, kind: .get), .rateLimited(retryAfter: 1))
    }

    func testSchedulerSurvivesHugeRetryAfter() {
        var s = SyncScheduler()
        s.record(.rateLimited(retryAfter: Int.max), now: UInt64.max - 10)
        XCTAssertFalse(s.allows(.manual, now: UInt64.max - 1))
        var t = SyncScheduler()
        t.record(.quotaFull, now: UInt64.max - 10)
        for _ in 0..<70 { t.record(.transient, now: UInt64.max - 10) }
        XCTAssertFalse(t.allows(.manual, now: UInt64.max - 1))
    }

    /// M2：响应体超过请求体上限（802,048 字节）就中止读取，按暂时故障处理；Content-Length 先查。
    func testOversizedResponseBodyIsTransient() async throws {
        let keys = try SyncCrypto.derive([UInt8](repeating: 2, count: 32))
        // 合法 JSON，只是太大：不能指望解析失败来兜底。
        let big = Data("{\"epoch\":\"e\",\"now\":1,\"pad\":\"".utf8) + Data(repeating: 0x61, count: 900_000) + Data("\"}".utf8)
        StubProtocol.respond = { _ in (404, ["Content-Type": "application/json"], big) }
        var r = await stubTransport(keys).get()
        XCTAssertEqual(r, .transient)
        StubProtocol.respond = { _ in (404, ["Content-Length": "900000"], Data("{\"epoch\":\"e\",\"now\":1}".utf8)) }
        r = await stubTransport(keys).get()
        XCTAssertEqual(r, .transient, "声明的长度超限，不读体")
        StubProtocol.respond = { _ in (404, [:], Data("{\"epoch\":\"e\",\"now\":1}".utf8)) }
        r = await stubTransport(keys).get()
        XCTAssertEqual(r, .notFound(epoch: "e", now: 1))
    }

    func stubTransport(_ keys: SyncKeyMaterial) -> SyncHTTPTransport {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return SyncHTTPTransport(base: URL(string: "https://sync.example")!, keys: keys, session: URLSession(configuration: config))
    }
}

final class StubProtocol: URLProtocol {
    nonisolated(unsafe) static var respond: (URLRequest) -> (Int, [String: String], Data) = { _ in (500, [:], Data()) }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let (status, headers, body) = Self.respond(request)
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        var offset = 0
        while offset < body.count {
            let end = min(body.count, offset + 65_536)
            client?.urlProtocol(self, didLoad: body.subdata(in: offset..<end))
            offset = end
        }
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
#endif
