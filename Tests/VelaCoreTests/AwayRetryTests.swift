import XCTest
@testable import VelaCore

final class AwayRetryTests: XCTestCase {
    func testFirstAwayRetriesOnceThenHolds() {
        var away = AwayRetry()
        away.enter("alpha")
        XCTAssertEqual(away.onAway("alpha"), .retryLater(after: AwayRetry.delay))
        XCTAssertEqual(AwayRetry.delay, 60)
        // 延迟到点真正开始重试（记一次），重新起播同一房间：仍离开就保持提示，不再排第二次。
        away.beginRetry()
        away.enter("alpha")
        XCTAssertEqual(away.onAway("alpha"), .hold)
        away.enter("Alpha")
        XCTAssertEqual(away.onAway("ALPHA"), .hold)
    }

    func testNotes() {
        XCTAssertEqual(AwayRetry.Decision.retryLater(after: 60).note, "暂时离开，稍后自动重试")
        XCTAssertEqual(AwayRetry.Decision.hold.note, "暂时离开，可稍后手动重试或换个房间")
    }

    func testSwitchingRoomsResets() {
        var away = AwayRetry()
        away.enter("alpha")
        _ = away.onAway("alpha")
        away.enter("beta")
        XCTAssertNil(away.room)
        XCTAssertEqual(away.onAway("beta"), .retryLater(after: AwayRetry.delay))
        // 回到原房间也是新的一次：可以再自动重试一次。
        away.enter("alpha")
        XCTAssertEqual(away.onAway("alpha"), .retryLater(after: AwayRetry.delay))
    }

    func testAwayForDifferentRoomWithoutEnterStillCountsSeparately() {
        var away = AwayRetry()
        _ = away.onAway("alpha")
        XCTAssertEqual(away.onAway("beta"), .retryLater(after: AwayRetry.delay))
        away.beginRetry()
        XCTAssertEqual(away.onAway("beta"), .hold)
    }

    func testSuccessfulPlaybackClears() {
        var away = AwayRetry()
        _ = away.onAway("alpha")
        away.clear()
        away.enter("alpha")
        XCTAssertFalse(away.retried)
        XCTAssertEqual(away.onAway("alpha"), .retryLater(after: AwayRetry.delay))
    }

    func testShouldFireOnlyForSameRoomGenerationAndAllowed() {
        func fire(room: String? = "alpha", gen: Int = 3, allowed: Bool = true, cancelled: Bool = false) -> Bool {
            AwayRetry.shouldFire(
                scheduledRoom: "alpha", currentRoom: room, scheduledGeneration: 3, currentGeneration: gen,
                playbackAllowed: allowed, cancelled: cancelled
            )
        }
        XCTAssertTrue(fire())
        XCTAssertTrue(fire(room: "ALPHA"))
        XCTAssertFalse(fire(room: "beta"), "切房后不再重试")
        XCTAssertFalse(fire(room: nil))
        XCTAssertFalse(fire(gen: 4), "期间有新的起播")
        XCTAssertFalse(fire(allowed: false), "暂停或隐藏时不重试")
        XCTAssertFalse(fire(cancelled: true))
    }

    func testPauseCancelsPendingRetryEvenIfResumedBeforeDeadline() {
        var timer = AwayRetryTimer()
        timer.schedule()
        XCTAssertTrue(timer.pending)
        XCTAssertFalse(timer.gateChanged(allowed: true), "仍允许播放：不动")
        XCTAssertTrue(timer.gateChanged(allowed: false), "暂停：作废排程")
        XCTAssertFalse(timer.pending)
        XCTAssertFalse(timer.gateChanged(allowed: true), "到期前恢复不重新排程")
        XCTAssertFalse(
            AwayRetry.shouldFire(
                scheduledRoom: "a", currentRoom: "a", scheduledGeneration: 1, currentGeneration: 1,
                playbackAllowed: true, cancelled: !timer.pending
            ), "到点也不触发"
        )
    }

    func testPauseWithoutPendingIsNoop() {
        var timer = AwayRetryTimer()
        XCTAssertFalse(timer.gateChanged(allowed: false))
    }

    func testClearDropsPending() {
        var timer = AwayRetryTimer()
        timer.schedule()
        timer.clear()
        XCTAssertFalse(timer.pending)
    }

    func testCancelledRetryDoesNotConsumeTheAutoRetry() {
        var away = AwayRetry()
        away.enter("alpha")
        XCTAssertEqual(away.onAway("alpha"), .retryLater(after: AwayRetry.delay))
        // 排程后被暂停取消，没有 beginRetry；手动重试仍离开时还有一次自动重试。
        away.enter("alpha")
        XCTAssertEqual(away.onAway("alpha"), .retryLater(after: AwayRetry.delay))
        away.beginRetry()
        away.enter("alpha")
        XCTAssertEqual(away.onAway("alpha"), .hold)
    }

    /// 暂停发生在解析返回之前：away 结果回来时门控已关，只给手动重试提示、不建自动重试任务。
    func testAwayResultAfterPauseGivesManualNoteWithoutScheduling() {
        var away = AwayRetry()
        away.enter("alpha")
        let decision = away.onAway("alpha")
        XCTAssertEqual(decision, .retryLater(after: AwayRetry.delay))
        XCTAssertEqual(AwayRetry.gated(decision, playbackAllowed: false), .hold)
        XCTAssertEqual(AwayRetry.gated(decision, playbackAllowed: false).note, AwayRetry.heldNote)
        XCTAssertEqual(AwayRetry.gated(decision, playbackAllowed: true), decision)
        XCTAssertEqual(AwayRetry.gated(.hold, playbackAllowed: true), .hold)
        // 被门控挡掉的那次没有消耗自动重试：恢复后手动重试仍 away 时还有一次。
        away.enter("alpha")
        XCTAssertEqual(away.onAway("alpha"), .retryLater(after: AwayRetry.delay))
    }

    func testBackoffNoteFollowsGate() {
        XCTAssertEqual(EndpointBackoff.pendingNote(playbackAllowed: true), EndpointBackoff.waitingNote)
        XCTAssertEqual(EndpointBackoff.pendingNote(playbackAllowed: false), EndpointBackoff.heldNote)
    }
}
