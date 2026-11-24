import XCTest
#if SWIFT_PACKAGE
@testable import VelaCore
#else
@testable import VelaPhone
#endif

/// 修订 A2：画中画策略、静音会话和进后台分支。
final class PiPPolicyTests: XCTestCase {
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

    func input(
        platform: PiPPolicy.Platform = .phone,
        scene: PhoneScenePhase = .background,
        pictureInPicture: PiPPolicy.State = .none,
        autoEnterEnabled: Bool = true,
        muted: Bool = true,
        focus: PlaybackLaneRole = .primary,
        lanes: [PlaybackLaneRole] = [.primary, .secondary],
        focusPlaying: Bool = true,
        bossKey: Bool = false,
        lockScreenOrSystemSleep: Bool = false,
        windowMinimized: Bool = false,
        windowOccluded: Bool = false
    ) -> PiPPolicy.Input {
        PiPPolicy.Input(
            platform: platform,
            scene: scene,
            pictureInPicture: pictureInPicture,
            autoEnterEnabled: autoEnterEnabled,
            muted: muted,
            focus: focus,
            lanes: lanes,
            focusPlaying: focusPlaying,
            bossKey: bossKey,
            lockScreenOrSystemSleep: lockScreenOrSystemSleep,
            windowMinimized: windowMinimized,
            windowOccluded: windowOccluded
        )
    }

    func fullStop(_ resume: PhoneResumeIntent?) -> [PhoneLifecycleAction] {
        [
            .gateOff,
            .epochSuspend,
            .settleDwell(.background),
            .flushSavers,
            .suspendLanes,
            .audioDeactivate,
            .stopListener,
            .noteResumeIntent(resume)
        ]
    }

    func pictureInPictureBackground(
        focus: PlaybackLaneRole,
        releasing: [PlaybackLaneRole],
        resume: PhoneResumeIntent?
    ) -> [PhoneLifecycleAction] {
        var actions: [PhoneLifecycleAction] = [
            .suspendSharedTasks,
            .allowLaneRequests(focus),
            .settleDwell(.background),
            .flushSavers
        ]
        actions.append(contentsOf: releasing.map { .releaseLane($0) })
        actions.append(.continueLane(focus))
        actions.append(.retainRelaySource(focus))
        actions.append(.noteResumeIntent(resume))
        return actions
    }

    /// 回桌面且自动进入开着：焦点路继续，另一路释放，中继只留焦点源。
    func testAutoEnterOnHomeContinuesFocusAndReleasesOtherLane() {
        let pip = input(scene: .background, pictureInPicture: .none, autoEnterEnabled: true, focusPlaying: true)
        let decision = PiPPolicy.decide(pip)
        XCTAssertTrue(decision.playbackAllowed(.primary))
        XCTAssertFalse(decision.playbackAllowed(.secondary))
        XCTAssertTrue(decision.keepsRelayListening)
        XCTAssertEqual(decision.retainedRelaySource, .primary)
        XCTAssertFalse(decision.shouldClosePictureInPicture)
        XCTAssertTrue(decision.keepsSessionActiveOnMute)
        XCTAssertEqual(decision.session.options, .mixWithOthers)

        let lanes = [lane(username: "focus"), lane(.secondary, username: "other")]
        XCTAssertEqual(
            PhoneLifecycle.actions(
                from: .active, to: .background, wasSuspended: false,
                lanes: lanes, resumeIntent: .search, pip: pip
            ),
            pictureInPictureBackground(focus: .primary, releasing: [.secondary], resume: .search)
        )
        XCTAssertEqual(
            PhoneLifecycle.actions(
                from: .inactive, to: .background, wasSuspended: false,
                lanes: lanes, resumeIntent: nil, pip: pip
            ),
            pictureInPictureBackground(focus: .primary, releasing: [.secondary], resume: nil)
        )

        let secondaryFocus = input(focus: .secondary, focusPlaying: true)
        XCTAssertTrue(PiPPolicy.decide(secondaryFocus).playbackAllowed(.secondary))
        XCTAssertFalse(PiPPolicy.decide(secondaryFocus).playbackAllowed(.primary))
        XCTAssertEqual(PiPPolicy.decide(secondaryFocus).retainedRelaySource, .secondary)
        XCTAssertEqual(
            PhoneLifecycle.actions(
                from: .active, to: .background, wasSuspended: false,
                lanes: lanes, resumeIntent: nil, pip: secondaryFocus
            ),
            pictureInPictureBackground(focus: .secondary, releasing: [.primary], resume: nil)
        )
    }

    /// 焦点路已经在画中画（含 starting）：进后台继续，自动进入开关关掉也一样。
    func testPictureInPictureAlreadyActiveContinuesFocusOnBackground() {
        for state in [PiPPolicy.State.active, .starting] {
            let pip = input(
                scene: .background, pictureInPicture: state,
                autoEnterEnabled: false, focusPlaying: false
            )
            let decision = PiPPolicy.decide(pip)
            XCTAssertTrue(decision.playbackAllowed(.primary), "\(state)")
            XCTAssertFalse(decision.playbackAllowed(.secondary), "\(state)")
            XCTAssertTrue(decision.keepsRelayListening, "\(state)")
            XCTAssertEqual(decision.retainedRelaySource, .primary, "\(state)")
            XCTAssertFalse(decision.shouldClosePictureInPicture, "\(state)")
            XCTAssertEqual(
                PhoneLifecycle.actions(
                    from: .active, to: .background, wasSuspended: false,
                    lanes: [lane(), lane(.secondary)], resumeIntent: .reload, pip: pip
                ),
                pictureInPictureBackground(focus: .primary, releasing: [.secondary], resume: .reload),
                "\(state)"
            )
        }
    }

    /// 焦点路还在首次解析、切房解析或尚未播放的刷新：画中画输入说焦点路没在播，回桌面走完整停播序列。
    /// `shouldPlay` 只能收紧 `pip.focusPlaying`，不能把「没在播」改写成「在播」。
    func testNotYetPlayingFocusStopsEveryLaneOnHome() {
        for activity in [PhoneLaneActivity.firstResolve, .roomSwitch, .refreshing] {
            let pip = input(scene: .background, pictureInPicture: .none, autoEnterEnabled: true, focusPlaying: false)
            XCTAssertEqual(
                PhoneLifecycle.actions(
                    from: .active, to: .background, wasSuspended: false,
                    lanes: [lane(activity: activity), lane(.secondary, username: "other")],
                    resumeIntent: .reload, pip: pip
                ),
                fullStop(.reload),
                "\(activity)"
            )
        }
    }

    /// 画中画说焦点路在播，但焦点路已暂停：取交集，仍是全停。
    func testFocusPlayingIsIntersectedWithShouldPlay() {
        let pip = input(scene: .background, pictureInPicture: .none, autoEnterEnabled: true, focusPlaying: true)
        XCTAssertEqual(
            PhoneLifecycle.actions(
                from: .active, to: .background, wasSuspended: false,
                lanes: [lane(manuallyPaused: true), lane(.secondary, username: "other")],
                resumeIntent: nil, pip: pip
            ),
            fullStop(nil)
        )
    }

    /// 没有正在进行的画中画、也不会自动进入时，进后台仍是原来的全停。
    func testBackgroundWithoutPictureInPictureStopsEveryLane() {
        let paused = lane(manuallyPaused: true)
        let other = lane(.secondary, username: "other")
        let notPlaying = input(pictureInPicture: .none, autoEnterEnabled: true, focusPlaying: true)
        XCTAssertEqual(
            PhoneLifecycle.actions(
                from: .active, to: .background, wasSuspended: false,
                lanes: [paused, other], resumeIntent: .reload, pip: notPlaying
            ),
            fullStop(.reload)
        )
        let stopped = PiPPolicy.decide(input(
            scene: .background, pictureInPicture: .none, autoEnterEnabled: true, focusPlaying: false
        ))
        XCTAssertFalse(stopped.playbackAllowed(.primary))
        XCTAssertFalse(stopped.playbackAllowed(.secondary))
        XCTAssertFalse(stopped.keepsRelayListening)
        XCTAssertNil(stopped.retainedRelaySource)
        XCTAssertFalse(stopped.shouldClosePictureInPicture)

        let stopping = input(pictureInPicture: .stopping, autoEnterEnabled: true, focusPlaying: true)
        XCTAssertFalse(PiPPolicy.decide(stopping).playbackAllowed(.primary))
        XCTAssertEqual(
            PhoneLifecycle.actions(
                from: .active, to: .background, wasSuspended: false,
                lanes: [lane(), lane(.secondary)], resumeIntent: .search, pip: stopping
            ),
            fullStop(.search)
        )
    }

    /// 自动进入关掉、又没在画中画：回桌面照旧全停。
    func testAutoEnterDisabledStopsOnHome() {
        let pip = input(
            scene: .background, pictureInPicture: .none,
            autoEnterEnabled: false, focusPlaying: true
        )
        let decision = PiPPolicy.decide(pip)
        XCTAssertFalse(decision.playbackAllowed(.primary))
        XCTAssertFalse(decision.playbackAllowed(.secondary))
        XCTAssertFalse(decision.keepsRelayListening)
        XCTAssertNil(decision.retainedRelaySource)
        XCTAssertFalse(decision.shouldClosePictureInPicture)
        XCTAssertFalse(decision.keepsSessionActiveOnMute)
        XCTAssertEqual(
            PhoneLifecycle.actions(
                from: .active, to: .background, wasSuspended: false,
                lanes: [lane(), lane(.secondary)], resumeIntent: .reload, pip: pip
            ),
            fullStop(.reload)
        )
    }

    /// 已经在后台时用户关掉画中画：补做原来的全停。仍开着则不重复发动作。
    func testClosingPictureInPictureWhileAlreadyBackgroundStopsEveryLane() {
        let stopping = input(pictureInPicture: .stopping, autoEnterEnabled: true, focusPlaying: true)
        XCTAssertEqual(
            PhoneLifecycle.actions(
                from: .background, to: .background, wasSuspended: true,
                lanes: [lane(), lane(.secondary)], resumeIntent: .reload, pip: stopping
            ),
            fullStop(.reload)
        )
        let stillOpen = input(pictureInPicture: .active, autoEnterEnabled: true, focusPlaying: true)
        XCTAssertEqual(
            PhoneLifecycle.actions(
                from: .background, to: .background, wasSuspended: true,
                lanes: [lane(), lane(.secondary)], resumeIntent: .reload, pip: stillOpen
            ),
            []
        )
        let none = input(pictureInPicture: .none, autoEnterEnabled: true, focusPlaying: true)
        XCTAssertEqual(
            PhoneLifecycle.actions(
                from: .background, to: .background, wasSuspended: true,
                lanes: [lane()], resumeIntent: .reload, pip: none
            ),
            []
        )
    }

    /// mac 画中画中老板键、锁屏或系统休眠：关闭画中画并停播。遮挡不能压过老板键。
    func testMacBossKeyAndLockScreenClosePictureInPictureAndStopPlayback() {
        let boss = PiPPolicy.decide(input(
            platform: .mac, scene: .active, pictureInPicture: .active,
            bossKey: true, windowMinimized: true
        ))
        assertClosedAndStopped(boss)

        let locked = PiPPolicy.decide(input(
            platform: .mac, scene: .active, pictureInPicture: .active,
            lockScreenOrSystemSleep: true
        ))
        assertClosedAndStopped(locked)

        let starting = PiPPolicy.decide(input(
            platform: .mac, scene: .active, pictureInPicture: .starting, bossKey: true
        ))
        assertClosedAndStopped(starting)

        let phone = PiPPolicy.decide(input(
            platform: .phone, scene: .background, pictureInPicture: .active,
            bossKey: true, lockScreenOrSystemSleep: true
        ))
        XCTAssertFalse(phone.shouldClosePictureInPicture)
        XCTAssertTrue(phone.playbackAllowed(.primary))
        XCTAssertTrue(phone.keepsRelayListening)
    }

    /// mac 最小化或主窗口被遮挡：画中画继续，不关闭。
    func testMacMinimizeAndOcclusionKeepPictureInPicture() {
        let minimized = PiPPolicy.decide(input(
            platform: .mac, scene: .active, pictureInPicture: .active, windowMinimized: true
        ))
        assertPictureInPictureContinues(minimized)

        let occluded = PiPPolicy.decide(input(
            platform: .mac, scene: .active, pictureInPicture: .active, windowOccluded: true
        ))
        assertPictureInPictureContinues(occluded)

        let both = PiPPolicy.decide(input(
            platform: .mac, scene: .active, pictureInPicture: .active,
            windowMinimized: true, windowOccluded: true
        ))
        assertPictureInPictureContinues(both)
    }

    /// 静音画中画用 `.playback` + `.mixWithOthers`，会话保持活跃。
    func testMutedPictureInPictureUsesMixWithOthers() {
        let quiet = AudioSessionPolicy.configuration(audible: false)
        XCTAssertEqual(quiet, AudioSessionPolicy.Configuration(
            category: .playback, mode: .moviePlayback, options: .mixWithOthers
        ))
        XCTAssertTrue(AudioSessionPolicy.keepsSessionActiveWhenMuting(pictureInPicturePlaying: true))

        let decision = PiPPolicy.decide(input(
            platform: .phone, scene: .background, pictureInPicture: .active, muted: true
        ))
        XCTAssertEqual(decision.session, quiet)
        XCTAssertTrue(decision.keepsSessionActiveOnMute)
        XCTAssertTrue(decision.playbackAllowed(.primary))
        XCTAssertEqual(decision.sessionActivation, .keepActive)
    }

    /// 取消静音：`.playback` 不混音。
    func testUnmuteDoesNotMixWithOthers() {
        let loud = AudioSessionPolicy.configuration(audible: true)
        XCTAssertEqual(loud.category, .playback)
        XCTAssertEqual(loud.mode, .moviePlayback)
        XCTAssertEqual(loud.options, [])
        XCTAssertFalse(loud.options.contains(.mixWithOthers))

        let planned = AudioTransition.plan(
            intent: .unmute(.primary), focus: .primary, currentlyAudible: .secondary,
            lanes: [.primary, .secondary]
        )
        XCTAssertEqual(planned.steps, [
            .muteLane(.secondary),
            .setCategory(.playback, mode: .moviePlayback),
            .setActive(true, notifyOthersOnDeactivation: false),
            .unmuteLane(.primary)
        ])
        XCTAssertFalse(planned.steps.contains { step in
            if case .setSessionOptions = step { return true }
            return false
        })

        let decision = PiPPolicy.decide(input(
            platform: .phone, scene: .active, pictureInPicture: .active, muted: false
        ))
        XCTAssertEqual(decision.session.category, .playback)
        XCTAssertEqual(decision.session.options, [])
        XCTAssertFalse(decision.session.options.contains(.mixWithOthers))
        XCTAssertEqual(decision.sessionActivation, .keepActive)
    }

    /// 再静音：没在画中画则停用并通知他人恢复；画中画仍在播则只切换选项，会话保持。
    func testRemuteNotifiesOthersAndKeepsPictureInPictureSession() {
        XCTAssertTrue(AudioSessionPolicy.deactivatesNotifyingOthers(on: .mute))
        XCTAssertFalse(AudioSessionPolicy.keepsSessionActiveWhenMuting(pictureInPicturePlaying: false))
        XCTAssertTrue(AudioSessionPolicy.keepsSessionActiveWhenMuting(pictureInPicturePlaying: true))

        let released = AudioTransition.plan(
            intent: .deactivate(.mute), focus: .primary, currentlyAudible: .primary,
            lanes: [.primary, .secondary], pictureInPictureActive: false
        )
        XCTAssertEqual(released.steps, [
            .muteAllLanes,
            .setCategory(.playback, mode: .moviePlayback),
            .setSessionOptions(.mixWithOthers),
            .setActive(false, notifyOthersOnDeactivation: true)
        ])
        XCTAssertFalse(released.keepsSessionActive)
        XCTAssertTrue(released.writesSettings)
        XCTAssertNil(released.audibleLane)

        let held = AudioTransition.plan(
            intent: .deactivate(.mute), focus: .primary, currentlyAudible: .primary,
            lanes: [.primary, .secondary], pictureInPictureActive: true
        )
        XCTAssertEqual(held.steps, [
            .muteAllLanes,
            .setCategory(.playback, mode: .moviePlayback),
            .setSessionOptions(.mixWithOthers)
        ])
        XCTAssertTrue(held.keepsSessionActive)
        XCTAssertFalse(held.steps.contains { step in
            if case .setActive(false, _) = step { return true }
            return false
        })

        let playing = PiPPolicy.decide(input(
            scene: .active, pictureInPicture: .active, muted: true, focusPlaying: true
        ))
        XCTAssertEqual(playing.session.options, .mixWithOthers)
        XCTAssertEqual(playing.session.category, .playback)
        XCTAssertTrue(playing.keepsSessionActiveOnMute)
        XCTAssertEqual(playing.sessionActivation, .keepActive)
        XCTAssertTrue(playing.playbackAllowed(.primary))

        let inline = PiPPolicy.decide(input(
            scene: .active, pictureInPicture: .none, muted: true, focusPlaying: true
        ))
        XCTAssertEqual(inline.session.options, .mixWithOthers)
        XCTAssertFalse(inline.keepsSessionActiveOnMute)
        XCTAssertEqual(inline.sessionActivation, .deactivateNotifyingOthers)
        XCTAssertTrue(inline.playbackAllowed(.primary))
        XCTAssertTrue(inline.playbackAllowed(.secondary))
    }

    private func assertClosedAndStopped(_ decision: PiPPolicy.Decision) {
        XCTAssertTrue(decision.shouldClosePictureInPicture)
        XCTAssertFalse(decision.playbackAllowed(.primary))
        XCTAssertFalse(decision.playbackAllowed(.secondary))
        XCTAssertFalse(decision.keepsRelayListening)
        XCTAssertNil(decision.retainedRelaySource)
        XCTAssertFalse(decision.keepsSessionActiveOnMute)
        XCTAssertEqual(decision.sessionActivation, .deactivateNotifyingOthers)
    }

    private func assertPictureInPictureContinues(_ decision: PiPPolicy.Decision) {
        XCTAssertFalse(decision.shouldClosePictureInPicture)
        XCTAssertTrue(decision.playbackAllowed(.primary))
        XCTAssertFalse(decision.playbackAllowed(.secondary))
        XCTAssertTrue(decision.keepsRelayListening)
        XCTAssertEqual(decision.retainedRelaySource, .primary)
        XCTAssertTrue(decision.keepsSessionActiveOnMute)
        XCTAssertEqual(decision.sessionActivation, .keepActive)
    }

    func testPictureInPictureStateLogTextPin() {
        let cases: [(PiPPolicy.State, String)] = [
            (.none, "none"),
            (.starting, "starting"),
            (.active, "active"),
            (.stopping, "stopping"),
        ]
        for (state, expected) in cases {
            XCTAssertEqual(String(describing: state), expected)
        }
    }
}
