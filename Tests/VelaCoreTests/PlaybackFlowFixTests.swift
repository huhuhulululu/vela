import XCTest
@testable import VelaCore

/// A9 卡住看门狗、A10 中继采纳新列表、A12 列表尽头的自动跳过。
final class PlaybackFlowFixTests: XCTestCase {
    // MARK: A9 卡住看门狗

    private func stuck(bufferAdvancing: Bool = false, renewing: Bool = false, retry: Bool = true,
                       playing: Bool = false, refreshing: Bool = false) -> PlaybackPolicy.StuckAction {
        PlaybackPolicy.stuckAction(
            sameItem: true, allowed: true, itemPlayed: true, isPlaying: playing, refreshing: refreshing,
            retryAvailable: retry, bufferAdvancing: bufferAdvancing, renewing: renewing
        )
    }

    func testStuckWithoutProgressStillRecoversOrGivesUp() {
        XCTAssertEqual(stuck(), .recover)
        XCTAssertEqual(stuck(retry: false), .giveUp)
        XCTAssertEqual(PlaybackPolicy.stuckAfter, 10, "10 秒阈值保留")
    }

    func testStuckRearmsWhileBufferGrowsOrRenewing() {
        XCTAssertEqual(stuck(bufferAdvancing: true), .rearm)
        XCTAssertEqual(stuck(renewing: true), .rearm)
        XCTAssertEqual(stuck(bufferAdvancing: true, retry: false), .rearm, "有进展时不放弃")
    }

    func testStuckIgnoreTakesPriorityOverProgress() {
        XCTAssertEqual(stuck(bufferAdvancing: true, playing: true), .ignore)
        XCTAssertEqual(stuck(renewing: true, refreshing: true), .ignore)
    }

    func testBufferAdvanced() {
        XCTAssertTrue(PlaybackPolicy.bufferAdvanced(previousEnd: 10, currentEnd: 10.5))
        XCTAssertTrue(PlaybackPolicy.bufferAdvanced(previousEnd: 0, currentEnd: PlaybackPolicy.bufferAdvanceEpsilon))
        XCTAssertFalse(PlaybackPolicy.bufferAdvanced(previousEnd: 10, currentEnd: 10.05), "浮点抖动不算")
        XCTAssertFalse(PlaybackPolicy.bufferAdvanced(previousEnd: 10, currentEnd: 9))
        XCTAssertFalse(PlaybackPolicy.bufferAdvanced(previousEnd: nil, currentEnd: 12))
        XCTAssertFalse(PlaybackPolicy.bufferAdvanced(previousEnd: 10, currentEnd: nil))
    }

    // MARK: A10 中继

    private let t0 = Date(timeIntervalSince1970: 1_000_000)
    private let oldBody = Data("old".utf8)
    private let newBody = Data("new".utf8)

    private func playlist(sequence: Int, segments: Int = 3, discontinuity: Bool = false, name: String = "seg") -> String {
        var lines = ["#EXTM3U", "#EXT-X-TARGETDURATION:2", "#EXT-X-MEDIA-SEQUENCE:\(sequence)"]
        if discontinuity { lines.append("#EXT-X-DISCONTINUITY") }
        for index in 0..<segments { lines += ["#EXTINF:2.000,", "\(name)\(sequence + index).ts"] }
        return lines.joined(separator: "\n")
    }

    private func info(_ text: String) -> MediaPlaylistInfo { MediaPlaylistInfo.parse(text)! }

    private func apply(_ feed: inout RelayFeed, _ body: Data, _ text: String, at offset: TimeInterval,
                       addressChanged: Bool = false) -> RelayFeed.Applied {
        let ticket = feed.begin()!
        return feed.finish(ticket, outcome: .success(body: body, info: info(text)),
                           now: t0.addingTimeInterval(offset), addressChanged: addressChanged)
    }

    private func gap(_ applied: RelayFeed.Applied) -> TimeInterval? {
        if case .advanced(let gap) = applied { return gap }
        return nil
    }

    private func servedBody(_ feed: inout RelayFeed, at offset: TimeInterval) -> (Data, Bool)? {
        guard case .body(let data, _, let replay) = feed.serve(now: t0.addingTimeInterval(offset)) else { return nil }
        return (data, replay)
    }

    func testParseReadsDiscontinuityAndLastURI() {
        let parsed = info(playlist(sequence: 5, discontinuity: true))
        XCTAssertTrue(parsed.hasDiscontinuity)
        XCTAssertEqual(parsed.lastSegmentURI, "seg7.ts")
        XCTAssertEqual(parsed.lastSequence, 7)
        let sequenceOnly = "#EXTM3U\n#EXT-X-DISCONTINUITY-SEQUENCE:3\n#EXTINF:2,\na.ts"
        XCTAssertFalse(info(sequenceOnly).hasDiscontinuity, "DISCONTINUITY-SEQUENCE 不是不连续标记")
    }

    func testRewindWithDiscontinuityAdoptsNewBody() {
        var feed = RelayFeed()
        _ = apply(&feed, oldBody, playlist(sequence: 1000), at: 0)
        _ = servedBody(&feed, at: 0.5)
        XCTAssertEqual(gap(apply(&feed, newBody, playlist(sequence: 0, discontinuity: true), at: 1.5)) ?? -1, 1.5, accuracy: 0.001)
        XCTAssertEqual(feed.info?.lastSequence, 2)
        let served = servedBody(&feed, at: 3.4)
        XCTAssertEqual(served?.0, newBody, "进展计时已重置，宽限内给新内容")
        XCTAssertEqual(served?.1, false)
    }

    func testRewindWithoutDiscontinuityStaysOlder() {
        var feed = RelayFeed()
        _ = apply(&feed, oldBody, playlist(sequence: 1000), at: 0)
        XCTAssertEqual(apply(&feed, newBody, playlist(sequence: 0), at: 1), .older)
        XCTAssertEqual(servedBody(&feed, at: 1.5)?.0, oldBody)
    }

    func testAddressChangedAdoptsEvenWhenSequenceDoesNotAdvance() {
        var feed = RelayFeed()
        _ = apply(&feed, oldBody, playlist(sequence: 1000), at: 0)
        XCTAssertEqual(gap(apply(&feed, newBody, playlist(sequence: 10), at: 1.8, addressChanged: true)) ?? -1, 1.8, accuracy: 0.001)
        XCTAssertEqual(servedBody(&feed, at: 3.5)?.0, newBody)
        XCTAssertEqual(gap(apply(&feed, oldBody, playlist(sequence: 10, name: "x"), at: 4, addressChanged: true)) ?? -1, 2.2, accuracy: 0.001)
    }

    func testSameSequenceNewURIAdoptsAndClearsReplay() {
        var feed = RelayFeed()
        _ = apply(&feed, oldBody, playlist(sequence: 50), at: 0)
        XCTAssertEqual(servedBody(&feed, at: 0.2)?.1, false)
        XCTAssertEqual(gap(apply(&feed, newBody, playlist(sequence: 50, name: "signed"), at: 1.9)) ?? -1, 1.9, accuracy: 0.001)
        let served = servedBody(&feed, at: 2.0)
        XCTAssertEqual(served?.0, newBody)
        XCTAssertEqual(served?.1, false, "换了内容不算回放")
    }

    func testSameSequenceSameURIStaysUnchanged() {
        var feed = RelayFeed()
        _ = apply(&feed, oldBody, playlist(sequence: 50), at: 0)
        XCTAssertEqual(apply(&feed, newBody, playlist(sequence: 50), at: 1), .unchanged)
        XCTAssertEqual(servedBody(&feed, at: 1.5)?.0, oldBody)
        guard case .expired = feed.serve(now: t0.addingTimeInterval(2.1)) else { return XCTFail("旧逻辑：超过宽限仍失败") }
    }

    func testSameSequenceWithoutURIsStaysUnchanged() {
        var feed = RelayFeed()
        let bare = MediaPlaylistInfo(targetDuration: 2, lastSequence: 9)
        let ticket = feed.begin()!
        _ = feed.finish(ticket, outcome: .success(body: oldBody, info: bare), now: t0)
        let again = feed.begin()!
        XCTAssertEqual(feed.finish(again, outcome: .success(body: newBody, info: bare), now: t0.addingTimeInterval(1)), .unchanged)
    }

    // MARK: A12 列表尽头

    func testEndOfListKeepsReasonAndDoesNotCount() {
        var tracker = SkipTracker()
        let outcome = tracker.attemptAuto { false }
        XCTAssertEqual(outcome, .endOfList)
        XCTAssertEqual(tracker.count, 0, "没跳成不计数")
        XCTAssertEqual(outcome.notice(keeping: "主播已离线"), "主播已离线，已到列表尽头")
        XCTAssertEqual(outcome.notice(keeping: nil), "已到列表尽头")
        XCTAssertEqual(outcome.notice(keeping: "  "), "已到列表尽头")
    }

    func testMovedHasNoNotice() {
        var tracker = SkipTracker()
        XCTAssertEqual(tracker.attemptAuto { true }, .moved)
        XCTAssertEqual(tracker.count, 1)
        XCTAssertNil(SkipTracker.AutoOutcome.moved.notice(keeping: "x"))
    }

    func testLimitOnlyAfterRealConsecutiveSkips() {
        var tracker = SkipTracker()
        for _ in 0..<SkipTracker.limit { XCTAssertEqual(tracker.attemptAuto { true }, .moved) }
        var called = false
        let outcome = tracker.attemptAuto { called = true; return true }
        XCTAssertEqual(outcome, .limitReached)
        XCTAssertFalse(called, "到上限不再移动")
        XCTAssertEqual(outcome.notice(keeping: "主播已离线"), "连续几个房间都无法播放")
    }

    func testEndOfListAfterSomeSkipsIsStillEndOfList() {
        var tracker = SkipTracker()
        XCTAssertEqual(tracker.attemptAuto { true }, .moved)
        XCTAssertEqual(tracker.attemptAuto { false }, .endOfList)
        tracker.reset()
        XCTAssertEqual(tracker.count, 0)
    }
}
