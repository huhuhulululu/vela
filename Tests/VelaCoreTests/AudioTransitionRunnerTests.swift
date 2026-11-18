import XCTest
@testable import VelaCore

final class AudioTransitionRunnerTests: XCTestCase {
    /// 驱动与静音入口共用一份调用记录，用来断言跨两者的先后顺序。
    final class Recorder: AudioSessionDriving, AudioLaneControlling {
        struct Failure: Error {}
        var calls: [String] = []
        var failCategory = false
        var failActive = false
        var muted: [PlaybackLaneRole: Bool] = [:]

        func setCategory(_ category: AudioSessionCategory, mode: AudioSessionMode?, options: AudioSessionOptions) throws {
            calls.append("category \(category) mix=\(options.contains(.mixWithOthers))")
            if failCategory { throw Failure() }
        }

        func setActive(_ active: Bool, notifyOthers: Bool) throws {
            calls.append("active \(active) notify=\(notifyOthers)")
            if failActive { throw Failure() }
        }

        func setMuted(_ isMuted: Bool, lane: PlaybackLaneRole) {
            calls.append("\(isMuted ? "mute" : "unmute") \(lane)")
            muted[lane] = isMuted
        }
    }

    private let lanes: [PlaybackLaneRole] = [.primary, .secondary]

    private func run(
        _ intent: AudioIntent, audible: PlaybackLaneRole? = nil, pip: Bool = false, _ rec: Recorder
    ) -> AudioOutcome {
        let plan = AudioTransition.plan(
            intent: intent, focus: .primary, currentlyAudible: audible, lanes: lanes, pictureInPictureActive: pip
        )
        return AudioTransitionRunner.run(plan, lanes: lanes, driver: rec, sink: rec)
    }

    func testUnmuteRunsInOrderAndUnmutesOnlyAfterActivation() {
        let rec = Recorder()
        let outcome = run(.unmute(.primary), audible: .secondary, rec)
        XCTAssertEqual(rec.calls, [
            "mute secondary",          // detach 非焦点路
            "mute secondary",          // 步骤 1：静音旧路
            "category playback mix=false",
            "active true notify=false",
            "unmute primary"
        ])
        XCTAssertEqual(outcome.mutedAfter, false)
        XCTAssertEqual(outcome.audibleLane, .primary)
        XCTAssertTrue(outcome.writesSettings)
        XCTAssertFalse(outcome.failed)
    }

    func testCategoryFailureKeepsEverythingMutedAndSkipsActivation() {
        let rec = Recorder()
        rec.failCategory = true
        let outcome = run(.unmute(.primary), audible: nil, rec)
        XCTAssertFalse(rec.calls.contains { $0.hasPrefix("active") }, "类别失败不再激活")
        XCTAssertFalse(rec.calls.contains("unmute primary"))
        XCTAssertEqual(rec.muted[.primary], true)
        XCTAssertEqual(outcome.mutedAfter, true)
        XCTAssertFalse(outcome.writesSettings, "失败不写设置")
        XCTAssertEqual(outcome.notice, AudioSessionPolicy.failureNotice)
        XCTAssertTrue(outcome.failed)
    }

    func testActivationFailureNeverUnmutes() {
        let rec = Recorder()
        rec.failActive = true
        let outcome = run(.unmute(.primary), audible: .primary, rec)
        XCTAssertEqual(rec.calls.suffix(2), ["mute primary", "mute secondary"], "失败后全部压回静音")
        XCTAssertEqual(rec.muted[.primary], true)
        XCTAssertFalse(rec.calls.contains("unmute primary"))
        XCTAssertEqual(outcome.mutedAfter, true)
        XCTAssertFalse(outcome.writesSettings)
        XCTAssertEqual(outcome.notice, "暂时无法出声")
    }

    func testMuteMergesCategoryAndOptionsThenDeactivatesNotifyingOthers() {
        let rec = Recorder()
        let outcome = run(.deactivate(.mute), audible: .primary, rec)
        XCTAssertEqual(rec.calls.filter { $0.hasPrefix("category") }, ["category playback mix=true"], "选项并进同一次 setCategory")
        XCTAssertEqual(rec.calls.last, "active false notify=true")
        XCTAssertEqual(outcome.mutedAfter, true)
        XCTAssertTrue(outcome.writesSettings)
        XCTAssertFalse(outcome.failed)
    }

    func testMuteWithPictureInPictureKeepsSessionActive() {
        let rec = Recorder()
        _ = run(.deactivate(.mute), audible: .primary, pip: true, rec)
        XCTAssertTrue(rec.calls.contains("category playback mix=true"))
        XCTAssertFalse(rec.calls.contains { $0.hasPrefix("active") }, "画中画仍在播：只切选项")
    }

    func testMuteFailureStillReportsMuted() {
        let rec = Recorder()
        rec.failActive = true
        let outcome = run(.deactivate(.mute), audible: .primary, rec)
        XCTAssertEqual(outcome.mutedAfter, true)
        XCTAssertFalse(outcome.failed, "静音路径上的会话失败不是「无法出声」")
        XCTAssertNil(outcome.notice)
    }

    func testInterruptionBeganMutesAllWithoutWritingSettings() {
        let rec = Recorder()
        let outcome = run(.interruptionBegan(wasSuspended: false), audible: .primary, rec)
        XCTAssertEqual(rec.muted[.primary], true)
        XCTAssertEqual(rec.muted[.secondary], true)
        XCTAssertFalse(rec.calls.contains { $0.hasPrefix("category") || $0.hasPrefix("active") })
        XCTAssertEqual(outcome.mutedAfter, true)
        XCTAssertFalse(outcome.writesSettings)
    }

    func testSuspendedInterruptionAndInterruptionEndedLeaveStateAlone() {
        let ignored = Recorder()
        let a = run(.interruptionBegan(wasSuspended: true), audible: .primary, ignored)
        XCTAssertTrue(a.ignored)
        XCTAssertNil(a.mutedAfter)
        XCTAssertFalse(ignored.calls.contains { !$0.hasPrefix("mute secondary") })

        let ended = Recorder()
        let b = run(.interruptionEnded, audible: nil, ended)
        XCTAssertNil(b.mutedAfter, "打断结束不自动出声")
        XCTAssertFalse(ended.calls.contains { $0.hasPrefix("unmute") })
    }

    func testOldDeviceUnavailableMutesWithoutPause() {
        let rec = Recorder()
        let outcome = run(.route(.oldDeviceUnavailable), audible: .primary, rec)
        XCTAssertEqual(outcome.mutedAfter, true)
        XCTAssertFalse(outcome.pausesPlayback)
        XCTAssertEqual(rec.calls.last, "active false notify=true")
    }

    func testOldDeviceUnavailableKeepsSessionWhenPictureInPictureActive() {
        let rec = Recorder()
        let outcome = run(.route(.oldDeviceUnavailable), audible: .primary, pip: true, rec)
        XCTAssertEqual(outcome.mutedAfter, true)
        XCTAssertFalse(rec.calls.contains { $0.hasPrefix("active") })
    }

    func testOtherRouteChangeDoesNothing() {
        let rec = Recorder()
        let outcome = run(.route(.other), audible: .primary, rec)
        XCTAssertNil(outcome.mutedAfter)
        XCTAssertFalse(rec.calls.contains { $0.hasPrefix("category") || $0.hasPrefix("active") })
    }

    func testPrepareMutedSetsMixedCategoryThenActivates() {
        let rec = Recorder()
        let outcome = run(.prepare(muted: true), rec)
        XCTAssertEqual(rec.calls, [
            "mute secondary",
            "mute primary", "mute secondary",
            "category playback mix=true",
            "active true notify=false"
        ])
        XCTAssertEqual(outcome.mutedAfter, true)
        XCTAssertFalse(outcome.writesSettings)
        XCTAssertFalse(outcome.failed)
    }

    func testPrepareMutedActivationFailureStaysMutedWithoutNotice() {
        let rec = Recorder()
        rec.failActive = true
        let outcome = run(.prepare(muted: true), rec)
        XCTAssertEqual(outcome.mutedAfter, true)
        XCTAssertFalse(outcome.failed)
        XCTAssertNil(outcome.notice, "静音本来就不出声，激活失败不打扰用户")
    }
    func testBackgroundDeactivationLeavesRuntimeMuteUnchanged() {
        let rec = Recorder()
        let outcome = run(.deactivate(.background), audible: .primary, rec)
        XCTAssertNil(outcome.mutedAfter, "进后台停用会话不把出声改成静音")
        XCTAssertEqual(rec.calls.last, "active false notify=true")
    }

    /// 停用失败要让调用方知道（重试、补做），否则他人的音乐收不到恢复通知。
    func testDeactivationFailureIsReported() {
        let rec = Recorder()
        rec.failActive = true
        let outcome = run(.deactivate(.mute), audible: .primary, rec)
        XCTAssertTrue(outcome.deactivationFailed)
        XCTAssertEqual(outcome.mutedAfter, true, "静音本身照旧生效")
        let fine = Recorder()
        XCTAssertFalse(run(.deactivate(.mute), audible: .primary, fine).deactivationFailed)
        let kept = Recorder()
        kept.failActive = true
        XCTAssertFalse(run(.deactivate(.mute), audible: .primary, pip: true, kept).deactivationFailed, "画中画保持会话：没有停用这一步")
    }
}
