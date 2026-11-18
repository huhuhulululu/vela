import XCTest
@testable import VelaCore

final class AudioTransitionTests: XCTestCase {
    func plan(_ intent: AudioIntent, audible: PlaybackLaneRole? = .secondary, lanes: [PlaybackLaneRole] = [.primary, .secondary], focus: PlaybackLaneRole = .primary) -> AudioTransitionPlan {
        AudioTransition.plan(intent: intent, focus: focus, currentlyAudible: audible, lanes: lanes)
    }

    func unmutes(_ steps: [AudioTransitionStep]) -> Bool {
        steps.contains { if case .unmuteLane = $0 { return true }; return false }
    }

    /// 出声转换固定四步：先静音旧路，再设类别、激活，成功后才取消静音。
    func testUnmuteMutesOldLaneBeforeCategoryActivationAndUnmute() {
        let planned = plan(.unmute(.primary), audible: .secondary)
        XCTAssertEqual(planned.steps, [
            .muteLane(.secondary),
            .setCategory(.playback, mode: .moviePlayback),
            .setActive(true, notifyOthersOnDeactivation: false),
            .unmuteLane(.primary)
        ])
        XCTAssertEqual(planned.audibleLane, .primary)
        XCTAssertEqual(planned.detachAudio, [.secondary])
        XCTAssertTrue(planned.writesSettings)
        XCTAssertNil(planned.notice)
        XCTAssertFalse(planned.pausesPlayback)
        XCTAssertTrue(planned.attemptsAudible)
        XCTAssertEqual(AudioTransition.finished(planned, categorySucceeded: true, activationSucceeded: true), planned)
        XCTAssertEqual(
            AudioSessionPolicy.configuration(audible: true),
            AudioSessionPolicy.Configuration(category: .playback, mode: .moviePlayback)
        )
    }

    /// 第 2 或第 3 步失败：全部保持静音，不写设置，提示暂时无法出声。
    func testSessionFailureStaysMutedWithoutWritingSettings() {
        let planned = plan(.unmute(.primary), audible: .secondary)
        for (categoryOK, activationOK) in [(false, true), (true, false), (false, false)] {
            let failed = AudioTransition.finished(
                planned, categorySucceeded: categoryOK, activationSucceeded: activationOK
            )
            XCTAssertNil(failed.audibleLane, "category \(categoryOK) activation \(activationOK)")
            XCTAssertFalse(failed.writesSettings)
            XCTAssertEqual(failed.notice, "暂时无法出声")
            XCTAssertEqual(failed.notice, AudioSessionPolicy.failureNotice)
            XCTAssertFalse(unmutes(failed.steps))
            XCTAssertTrue(failed.steps.contains(.muteLane(.secondary)))
            XCTAssertEqual(failed.detachAudio, [.secondary])
            XCTAssertFalse(failed.pausesPlayback)
            XCTAssertFalse(failed.attemptsAudible)
        }
    }

    /// wasSuspended 的打断开始忽略；普通打断开始只改运行时静音。
    func testIgnoresInterruptionBeganWhenWasSuspended() {
        let ignored = plan(.interruptionBegan(wasSuspended: true), audible: .primary)
        XCTAssertTrue(ignored.ignored)
        XCTAssertTrue(ignored.preservesAudibleLane)
        XCTAssertTrue(ignored.steps.isEmpty)
        XCTAssertFalse(ignored.writesSettings)
        XCTAssertTrue(AudioSessionPolicy.ignoresInterruptionBegan(wasSuspended: true))
        XCTAssertFalse(AudioSessionPolicy.ignoresInterruptionBegan(wasSuspended: false))

        let began = plan(.interruptionBegan(wasSuspended: false), audible: .primary)
        XCTAssertFalse(began.ignored)
        XCTAssertEqual(began.steps, [.muteAllLanes])
        XCTAssertNil(began.audibleLane)
        XCTAssertFalse(began.preservesAudibleLane)
        XCTAssertFalse(began.writesSettings)
        XCTAssertFalse(began.pausesPlayback)
        XCTAssertNil(began.notice)
    }

    /// 打断结束不自动出声。
    func testInterruptionEndedDoesNotResumeAudio() {
        let ended = plan(.interruptionEnded, audible: .primary, lanes: [.primary])
        XCTAssertFalse(ended.resumesAudible)
        XCTAssertFalse(AudioSessionPolicy.resumesAudibleAfterInterruption)
        XCTAssertTrue(ended.preservesAudibleLane)
        XCTAssertTrue(ended.steps.isEmpty)
        XCTAssertFalse(unmutes(ended.steps))
        XCTAssertFalse(ended.writesSettings)
        XCTAssertNil(ended.notice)
        XCTAssertFalse(ended.pausesPlayback)
    }

    /// 拔耳机转为静音，不暂停。
    func testHeadphoneUnplugMutesWithoutPausing() {
        let unplug = plan(.route(.oldDeviceUnavailable), audible: .primary)
        XCTAssertNil(unplug.audibleLane)
        XCTAssertFalse(unplug.pausesPlayback)
        XCTAssertFalse(unplug.preservesAudibleLane)
        XCTAssertEqual(unplug.steps, [
            .muteAllLanes,
            .setCategory(.playback, mode: .moviePlayback),
            .setSessionOptions(.mixWithOthers),
            .setActive(false, notifyOthersOnDeactivation: true)
        ])
        XCTAssertTrue(unplug.writesSettings)
        XCTAssertEqual(
            AudioTransition.finished(unplug, categorySucceeded: false, activationSucceeded: false),
            unplug
        )
        XCTAssertEqual(
            AudioSessionPolicy.routeDecision(.oldDeviceUnavailable),
            AudioSessionPolicy.RouteDecision(mutes: true, pauses: false)
        )
        XCTAssertEqual(
            AudioSessionPolicy.routeDecision(.other),
            AudioSessionPolicy.RouteDecision(mutes: false, pauses: false)
        )
        let other = plan(.route(.other), audible: .primary, lanes: [.primary])
        XCTAssertTrue(other.preservesAudibleLane)
        XCTAssertTrue(other.steps.isEmpty)
        XCTAssertFalse(other.pausesPlayback)
    }

    /// 画中画活跃时拔耳机：同样静音所有路、切混音配置，但保持会话激活（不 setActive(false)）。
    func testHeadphoneUnplugKeepsSessionActiveDuringPictureInPicture() {
        let unplug = AudioTransition.plan(
            intent: .route(.oldDeviceUnavailable), focus: .primary, currentlyAudible: .primary,
            lanes: [.primary, .secondary], pictureInPictureActive: true
        )
        XCTAssertEqual(unplug.steps, [
            .muteAllLanes,
            .setCategory(.playback, mode: .moviePlayback),
            .setSessionOptions(.mixWithOthers)
        ])
        XCTAssertTrue(unplug.keepsSessionActive)
        XCTAssertNil(unplug.audibleLane)
        XCTAssertFalse(unplug.pausesPlayback)
        XCTAssertTrue(unplug.writesSettings)
        XCTAssertEqual(unplug.detachAudio, [.secondary])

        let withoutPiP = plan(.route(.oldDeviceUnavailable), audible: .primary)
        XCTAssertFalse(withoutPiP.keepsSessionActive)
        XCTAssertEqual(withoutPiP.steps.last, .setActive(false, notifyOthersOnDeactivation: true))
    }

    /// 再静音、进后台、最后一路停播都通知其他 App 恢复。非焦点路拿掉音轨。
    func testDeactivationNotifiesOthersAndNonFocusStaysDetached() {
        for reason in [AudioSessionPolicy.DeactivationReason.mute, .background, .lastLaneStopped] {
            XCTAssertTrue(AudioSessionPolicy.deactivatesNotifyingOthers(on: reason))
            let planned = plan(.deactivate(reason), audible: .primary)
            XCTAssertEqual(planned.steps, [
                .muteAllLanes,
                .setCategory(.playback, mode: .moviePlayback),
                .setSessionOptions(.mixWithOthers),
                .setActive(false, notifyOthersOnDeactivation: true)
            ])
            XCTAssertNil(planned.audibleLane)
            XCTAssertFalse(planned.pausesPlayback)
            XCTAssertEqual(planned.writesSettings, reason == .mute)
        }
        XCTAssertEqual(
            AudioSessionPolicy.configuration(audible: false),
            AudioSessionPolicy.Configuration(category: .playback, mode: .moviePlayback, options: .mixWithOthers)
        )
        let nonFocus = plan(.unmute(.secondary), audible: .primary, focus: .primary)
        XCTAssertEqual(nonFocus.detachAudio, [.secondary])
        XCTAssertEqual(nonFocus.audibleLane, .primary)
        XCTAssertFalse(unmutes(nonFocus.steps.filter { step in
            if case .unmuteLane(.secondary) = step { return true }
            return false
        }))
        XCTAssertFalse(nonFocus.attemptsAudible)
    }

    /// 首次起播前的初始转换（静音）：播放类别 + 混音并激活，不取消任何路的静音，不写设置。
    func testPrepareMutedActivatesMixedPlaybackSession() {
        let planned = plan(.prepare(muted: true), audible: nil)
        XCTAssertEqual(planned.steps, [
            .muteAllLanes,
            .setCategory(.playback, mode: .moviePlayback),
            .setSessionOptions(.mixWithOthers),
            .setActive(true, notifyOthersOnDeactivation: false)
        ])
        XCTAssertNil(planned.audibleLane)
        XCTAssertFalse(planned.preservesAudibleLane)
        XCTAssertFalse(planned.writesSettings, "初始转换只落实已有设置，不改写")
        XCTAssertFalse(planned.attemptsAudible)
        XCTAssertNil(planned.notice)
    }

    /// 首次起播前的初始转换（出声）：与取消静音同样的步骤（不混音），但不写设置；失败照样保持静音并提示。
    func testPrepareAudibleUsesUnmixedPlaybackWithoutWritingSettings() {
        let planned = plan(.prepare(muted: false), audible: nil)
        XCTAssertEqual(planned.steps, [
            .setCategory(.playback, mode: .moviePlayback),
            .setActive(true, notifyOthersOnDeactivation: false),
            .unmuteLane(.primary)
        ])
        XCTAssertEqual(planned.audibleLane, .primary)
        XCTAssertFalse(planned.writesSettings)
        XCTAssertTrue(planned.attemptsAudible)
        let failed = AudioTransition.finished(planned, categorySucceeded: true, activationSucceeded: false)
        XCTAssertNil(failed.audibleLane)
        XCTAssertEqual(failed.notice, AudioSessionPolicy.failureNotice)
    }
}
