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
}
#endif
