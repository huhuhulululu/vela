import XCTest
@testable import VelaCore

final class PhoneLifecycleTests: XCTestCase {
    func lane(
        _ role: PlaybackLaneRole = .primary,
        username: String? = "room",
        manuallyPaused: Bool = false,
        visible: Bool = true,
        failed: Bool = false,
        activity: PhoneLaneActivity = .playing
    ) -> PhoneLaneSnapshot {
        PhoneLaneSnapshot(
            role: role, username: username, manuallyPaused: manuallyPaused,
            visible: visible, failed: failed, activity: activity
        )
    }

    func actions(
        from: PhoneScenePhase, to: PhoneScenePhase, wasSuspended: Bool,
        lanes: [PhoneLaneSnapshot], resumeIntent: PhoneResumeIntent? = nil
    ) -> [PhoneLifecycleAction] {
        PhoneLifecycle.actions(
            from: from, to: to, wasSuspended: wasSuspended, lanes: lanes, resumeIntent: resumeIntent
        )
    }

    /// 进后台：gateOff 在 suspendLanes 前，audio.deactivate 在 stopListener 前。
    func testEnteringBackgroundOrdersGateOffBeforeSuspendLanesAndDeactivateBeforeStopListener() {
        let lanes = [lane()]
        let entered = actions(from: .active, to: .background, wasSuspended: false, lanes: lanes, resumeIntent: .reload)
        XCTAssertEqual(entered, [
            .gateOff,
            .epochSuspend,
            .settleDwell(.background),
            .flushSavers,
            .suspendLanes,
            .audioDeactivate,
            .stopListener,
            .noteResumeIntent(.reload)
        ])
        XCTAssertLessThan(entered.firstIndex(of: .gateOff)!, entered.firstIndex(of: .suspendLanes)!)
        XCTAssertLessThan(entered.firstIndex(of: .audioDeactivate)!, entered.firstIndex(of: .stopListener)!)

        let fromInactive = actions(from: .inactive, to: .background, wasSuspended: false, lanes: lanes, resumeIntent: .search)
        XCTAssertEqual(fromInactive, [
            .gateOff, .epochSuspend, .settleDwell(.background), .flushSavers,
            .suspendLanes, .audioDeactivate, .stopListener, .noteResumeIntent(.search)
        ])
        XCTAssertEqual(
            actions(from: .background, to: .background, wasSuspended: true, lanes: lanes, resumeIntent: .reload),
            []
        )
    }

    /// 回前台：首次解析、切房解析、刷新中都重新起播。
    func testForegroundRestartsFirstResolveRoomSwitchAndRefresh() {
        let first = lane(username: "a", activity: .firstResolve)
        let switching = lane(.secondary, username: "b", activity: .roomSwitch)
        let refreshing = lane(username: "c", activity: .refreshing)
        XCTAssertEqual(actions(from: .background, to: .active, wasSuspended: true, lanes: [first]), [
            .epochResume, .requestPlay(.primary, .firstResolve)
        ])
        XCTAssertEqual(actions(from: .background, to: .active, wasSuspended: true, lanes: [switching]), [
            .epochResume, .requestPlay(.secondary, .roomSwitch)
        ])
        XCTAssertEqual(actions(from: .background, to: .active, wasSuspended: true, lanes: [refreshing]), [
            .epochResume, .requestPlay(.primary, .refreshing)
        ])
        XCTAssertTrue(PhoneLifecycle.shouldPlay(first))
        XCTAssertTrue(PhoneLifecycle.shouldPlay(switching))
        XCTAssertTrue(PhoneLifecycle.shouldPlay(refreshing))
    }

    /// 失败态不自动重播，手动暂停保持暂停，隐藏路保持释放。
    func testForegroundKeepsFailurePausedAndHiddenLanes() {
        let failed = lane(username: "a", failed: true)
        let paused = lane(.secondary, username: "b", manuallyPaused: true)
        let hidden = lane(username: "c", visible: false)
        XCTAssertEqual(actions(from: .background, to: .active, wasSuspended: true, lanes: [failed]), [
            .epochResume, .keepFailure(.primary)
        ])
        XCTAssertEqual(actions(from: .background, to: .active, wasSuspended: true, lanes: [paused]), [
            .epochResume, .stayPaused(.secondary)
        ])
        XCTAssertEqual(actions(from: .background, to: .active, wasSuspended: true, lanes: [hidden]), [
            .epochResume, .stayReleased(.primary)
        ])
        XCTAssertEqual(
            actions(from: .background, to: .active, wasSuspended: true, lanes: [failed, paused, hidden]),
            [.epochResume, .keepFailure(.primary), .stayPaused(.secondary), .stayReleased(.primary)]
        )
        XCTAssertFalse(PhoneLifecycle.shouldPlay(failed))
        XCTAssertFalse(PhoneLifecycle.shouldPlay(paused))
        XCTAssertFalse(PhoneLifecycle.shouldPlay(hidden))
    }

    /// 从后台经 inactive 回 active 仍按 wasSuspended 恢复；没挂起过则不重播。
    func testBackgroundThroughInactiveToActiveResumes() {
        let playing = lane(activity: .playing)
        XCTAssertEqual(
            actions(from: .background, to: .inactive, wasSuspended: true, lanes: [playing], resumeIntent: .search),
            []
        )
        XCTAssertEqual(
            actions(from: .inactive, to: .active, wasSuspended: true, lanes: [playing], resumeIntent: .search),
            [.epochResume, .redoResumeIntent(.search), .requestPlay(.primary, .playing)]
        )
        XCTAssertEqual(
            actions(from: .background, to: .active, wasSuspended: true, lanes: [playing]),
            [.epochResume, .requestPlay(.primary, .playing)]
        )
        XCTAssertEqual(
            actions(from: .inactive, to: .active, wasSuspended: false, lanes: [playing], resumeIntent: .search),
            []
        )
        XCTAssertEqual(
            actions(from: .background, to: .active, wasSuspended: false, lanes: [playing], resumeIntent: .reload),
            []
        )
    }

    func testPointVisibleAndAttentiveFollowScenePhase() {
        XCTAssertTrue(PhoneLifecycle.pointVisible(.active))
        XCTAssertTrue(PhoneLifecycle.pointVisible(.inactive))
        XCTAssertFalse(PhoneLifecycle.pointVisible(.background))
        XCTAssertTrue(PhoneLifecycle.attentive(.active))
        XCTAssertFalse(PhoneLifecycle.attentive(.inactive))
        XCTAssertFalse(PhoneLifecycle.attentive(.background))
    }

    func testLaneActivityLogTextPin() {
        let cases: [(PhoneLaneActivity, String)] = [
            (.idle, "idle"),
            (.firstResolve, "firstResolve"),
            (.roomSwitch, "roomSwitch"),
            (.refreshing, "refreshing"),
            (.playing, "playing"),
        ]
        for (activity, expected) in cases {
            XCTAssertEqual(String(describing: activity), expected)
        }
    }
}
