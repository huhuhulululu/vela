import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import VelaCore

/// A8：只有正文像质询页才算质询；普通 403 / 503 是普通失败。连续质询有上限。
final class ChallengeGateTests: XCTestCase {
    /// 默认共用的非 JSON 退避记录跨用例清空，免得前一个用例的退避挡住后一个。
    override func setUp() {
        super.setUp()
        EndpointBackoffBox.shared.reset()
    }

    private let page = Data("<html><title>Just a moment...</title></html>".utf8)
    private let plain = Data(#"{"detail":"rate limited"}"#.utf8)

    // MARK: 流地址

    func testStreamChallengeOnlyWhenBodyLooksLikeChallenge() {
        for status in [200, 403, 503] {
            XCTAssertThrowsError(try StreamDecoder.decode(status: status, data: page)) {
                XCTAssertEqual($0 as? StreamDecodeError, .challenge)
                XCTAssertTrue(($0 as? StreamDecodeError)?.isChallenge ?? false)
            }
        }
    }

    func testStreamPlain403And503AreUnavailable() {
        for status in [403, 503] {
            XCTAssertThrowsError(try StreamDecoder.decode(status: status, data: plain)) {
                let error = $0 as? StreamDecodeError
                XCTAssertEqual(error, .unavailable(status: status))
                XCTAssertEqual(error?.isChallenge, false)
                XCTAssertEqual(error?.failure, .unavailable)
            }
        }
        XCTAssertThrowsError(try StreamDecoder.decode(status: 403, data: Data())) {
            XCTAssertEqual($0 as? StreamDecodeError, .unavailable(status: 403), "空正文也不是质询")
        }
    }

    func testStreamOtherStatusesUnchanged() {
        XCTAssertThrowsError(try StreamDecoder.decode(status: 500, data: plain)) {
            XCTAssertEqual($0 as? StreamDecodeError, .malformed)
            XCTAssertNil(($0 as? StreamDecodeError)?.failure)
        }
        let ok = Data(#"{"success":true,"url":"https://edge.example/a.m3u8","room_status":"public"}"#.utf8)
        XCTAssertEqual(try StreamDecoder.decode(status: 200, data: ok).isPlayable, true)
    }

    // MARK: 目录

    func testCatalogPlain403And503AreStatusFailures() async {
        for status in [403, 503] {
            do {
                _ = try await CatalogAPI(client: Fixed(status: status, data: plain)).fetch(CatalogQuery())
                XCTFail("expected failure")
            } catch let error as CatalogFailure {
                XCTAssertEqual(error, .status(status))
                XCTAssertFalse(error.isChallenge)
            } catch {
                XCTFail("unexpected \(error)")
            }
        }
    }

    func testCatalogChallengeBodyIsChallengeAtAnyStatus() async {
        for status in [200, 403, 503] {
            do {
                _ = try await CatalogAPI(client: Fixed(status: status, data: page)).fetch(CatalogQuery())
                XCTFail("expected challenge")
            } catch let error as CatalogFailure {
                XCTAssertEqual(error, .challenge)
                XCTAssertTrue(error.isChallenge)
            } catch {
                XCTFail("unexpected \(error)")
            }
        }
    }

    // MARK: 连续质询闸门

    private let t0 = Date(timeIntervalSince1970: 2_000_000)

    func testFirstChallengePresents() {
        var gate = ChallengeGate()
        XCTAssertEqual(gate.register(now: t0), .present)
        XCTAssertEqual(gate.consecutive, 1)
    }

    func testQuickRepeatIsSuppressed() {
        var gate = ChallengeGate()
        _ = gate.register(now: t0)
        XCTAssertEqual(gate.register(now: t0.addingTimeInterval(ChallengeGate.repeatWindow - 1)), .suppress)
    }

    func testRepeatAfterWindowPresentsUntilLimit() {
        var gate = ChallengeGate()
        let step = ChallengeGate.repeatWindow + 1
        for index in 0..<ChallengeGate.limit {
            XCTAssertEqual(gate.register(now: t0.addingTimeInterval(step * Double(index))), .present, "第 \(index + 1) 次")
        }
        let over = t0.addingTimeInterval(step * Double(ChallengeGate.limit))
        XCTAssertEqual(gate.register(now: over), .suppress, "超过上限不再弹")
        XCTAssertEqual(gate.register(now: over.addingTimeInterval(step)), .suppress)
    }

    func testRepeatWindowBoundaryPresents() {
        var gate = ChallengeGate()
        _ = gate.register(now: t0)
        XCTAssertEqual(gate.register(now: t0.addingTimeInterval(ChallengeGate.repeatWindow)), .present)
    }

    func testResetAndLongGapStartOver() {
        var gate = ChallengeGate()
        _ = gate.register(now: t0)
        _ = gate.register(now: t0.addingTimeInterval(1))
        gate.reset()
        XCTAssertEqual(gate.consecutive, 0)
        XCTAssertNil(gate.lastAt)
        XCTAssertEqual(gate.register(now: t0.addingTimeInterval(2)), .present, "成功后重新计数")

        var stale = ChallengeGate()
        for index in 0...ChallengeGate.limit { _ = stale.register(now: t0.addingTimeInterval(Double(index))) }
        let later = t0.addingTimeInterval(Double(ChallengeGate.limit) + ChallengeGate.resetAfter)
        XCTAssertEqual(stale.register(now: later), .present, "很久以前的质询不算连续")
        XCTAssertEqual(stale.consecutive, 1)
    }
}

private struct Fixed: HTTPClient {
    var status: Int
    var data: Data

    func send(_ request: URLRequest) async throws -> HTTPResult {
        HTTPResult(status: status, data: data)
    }
}
