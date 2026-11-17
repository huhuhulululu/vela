import XCTest
@testable import VelaCore

final class RelayFeedTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_000_000)
    private let body = Data("body".utf8)

    private func playlist(target: Int = 2, sequence: Int, segments: Int, parts: Bool = false) -> String {
        var lines = ["#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-TARGETDURATION:\(target)", "#EXT-X-MEDIA-SEQUENCE:\(sequence)"]
        for _ in 0..<segments { lines += ["#EXTINF:1.600,", "seg.ts"] }
        if parts { lines += ["#EXT-X-PART:DURATION=0.5,URI=\"p.ts\""] }
        return lines.joined(separator: "\n")
    }

    private func info(_ seq: Int, target: TimeInterval = 2) -> MediaPlaylistInfo {
        MediaPlaylistInfo(targetDuration: target, lastSequence: seq, lastSegmentDuration: 1.6)
    }

    private func apply(_ feed: inout RelayFeed, _ outcome: RelayFeed.Outcome, at offset: TimeInterval) -> RelayFeed.Applied {
        let ticket = feed.begin()!
        return feed.finish(ticket, outcome: outcome, now: t0.addingTimeInterval(offset))
    }

    private func served(_ feed: inout RelayFeed, at offset: TimeInterval) -> (body: Data, replay: Bool)? {
        guard case .body(let data, _, let replay) = feed.serve(now: t0.addingTimeInterval(offset)) else { return nil }
        return (data, replay)
    }

    // MARK: 解析

    func testParseCountsFullSegmentsOnly() {
        let parsed = MediaPlaylistInfo.parse(playlist(sequence: 100, segments: 4, parts: true))
        XCTAssertEqual(parsed?.lastSequence, 103)
        XCTAssertEqual(parsed?.targetDuration, 2)
        XCTAssertEqual(parsed?.lastSegmentDuration ?? 0, 1.6, accuracy: 0.001)
        XCTAssertNil(MediaPlaylistInfo.parse("#EXTM3U\n#EXT-X-TARGETDURATION:2\n"))
        XCTAssertEqual(MediaPlaylistInfo.parse("#EXTM3U\n#EXTINF:2,\na.ts")?.targetDuration, RelayPolicy.defaultTargetDuration)
    }

    // MARK: 新鲜度

    func testStaysFreshWhileSegmentsAdvance() {
        var feed = RelayFeed()
        _ = apply(&feed, .success(body: body, info: info(10)), at: 0)
        _ = apply(&feed, .success(body: body, info: info(11)), at: 1.6)
        guard case .body = feed.serve(now: t0.addingTimeInterval(3.0)) else { return XCTFail("分片前进，不该失败") }
        guard case .body = feed.serve(now: t0.addingTimeInterval(3.6)) else { return XCTFail("宽限边界内") }
    }

    func testUpstream200WithoutNewSegmentExpiresAfterGrace() {
        var feed = RelayFeed()
        _ = apply(&feed, .success(body: body, info: info(10)), at: 0)
        // 上游一直 200，但完整分片序号不动（可能只有 partial 在动）。
        for second in stride(from: 0.5, through: 6.0, by: 0.5) {
            let applied = apply(&feed, .success(body: body, info: info(10)), at: second)
            XCTAssertEqual(applied, .unchanged)
        }
        guard case .body = feed.serve(now: t0.addingTimeInterval(1.9)) else { return XCTFail("宽限内可回放") }
        guard case .expired(let age) = feed.serve(now: t0.addingTimeInterval(2.1)) else { return XCTFail("超过宽限必须失败") }
        XCTAssertEqual(age, 2.1, accuracy: 0.001)
    }

    func testUpstreamFailuresUseSameGrace() {
        var feed = RelayFeed()
        _ = apply(&feed, .success(body: body, info: info(10)), at: 0)
        XCTAssertEqual(apply(&feed, .failure(.http(403)), at: 1), .failed(.http(403)))
        XCTAssertEqual(feed.lastFailure, .http(403))
        guard case .body = feed.serve(now: t0.addingTimeInterval(1.5)) else { return XCTFail() }
        guard case .expired = feed.serve(now: t0.addingTimeInterval(2.5)) else { return XCTFail() }
    }

    func testGraceDerivedFromTargetDuration() {
        XCTAssertEqual(RelayPolicy.grace(targetDuration: 2), 2)
        XCTAssertEqual(RelayPolicy.grace(targetDuration: 6), 6)
        var feed = RelayFeed()
        _ = apply(&feed, .success(body: body, info: info(1, target: 6)), at: 0)
        guard case .body = feed.serve(now: t0.addingTimeInterval(5.9)) else { return XCTFail("T=6 宽限 6 秒") }
        guard case .expired = feed.serve(now: t0.addingTimeInterval(6.1)) else { return XCTFail() }
    }

    func testReplayFlagAndEmpty() {
        var feed = RelayFeed()
        XCTAssertEqual(feed.serve(now: t0), .empty)
        _ = apply(&feed, .success(body: body, info: info(5)), at: 0)
        XCTAssertEqual(served(&feed, at: 0.1)?.replay, false)
        XCTAssertEqual(served(&feed, at: 0.2)?.replay, true)
    }

    // MARK: 单次在途 / 取消 / 旧结果

    func testSingleFlightAndCancelDiscardsLateResult() {
        var feed = RelayFeed()
        let first = feed.begin()
        XCTAssertNotNil(first)
        XCTAssertNil(feed.begin(), "同一个源只能有一个在途请求")
        feed.cancel()
        XCTAssertFalse(feed.isInFlight)
        let second = feed.begin()
        XCTAssertNotNil(second)
        // 取消前发出的请求晚到：丢弃，且不能清掉新请求的在途标记。
        XCTAssertEqual(feed.finish(first!, outcome: .success(body: body, info: info(1)), now: t0), .discarded)
        XCTAssertTrue(feed.isInFlight)
        XCTAssertEqual(feed.serve(now: t0), .empty)
        XCTAssertEqual(feed.finish(second!, outcome: .success(body: body, info: info(2)), now: t0), .advanced(gap: 0))
        XCTAssertFalse(feed.isInFlight)
    }

    func testOlderResultNeverOverwritesNewerProgress() {
        var feed = RelayFeed()
        let newer = Data("new".utf8)
        _ = apply(&feed, .success(body: newer, info: info(20)), at: 0)
        XCTAssertEqual(apply(&feed, .success(body: Data("old".utf8), info: info(18)), at: 0.5), .older)
        XCTAssertEqual(feed.info?.lastSequence, 20)
        XCTAssertEqual(served(&feed, at: 0.6)?.body, newer)
        XCTAssertEqual(apply(&feed, .success(body: body, info: info(21)), at: 2), .advanced(gap: 2))
    }

    func testRefreshCadence() {
        let i = info(5)
        XCTAssertEqual(RelayPolicy.refreshDelay(info: i, productive: true), 1.6, accuracy: 0.001)
        XCTAssertEqual(RelayPolicy.refreshDelay(info: i, productive: false), 1.0, accuracy: 0.001)
        XCTAssertEqual(RelayPolicy.refreshDelay(info: nil, productive: true), 2)
        XCTAssertEqual(RelayPolicy.refreshDelay(info: info(1, target: 6), productive: false), 3)
    }

    func testFailureLabelsCarryNoURL() {
        XCTAssertEqual(RelayFailure.classify(RelayFailure.http(410)).label, "http-410")
        XCTAssertEqual(RelayFailure.classify(URLError(.timedOut)).label, "NSURLErrorDomain/-1001")
    }
}
