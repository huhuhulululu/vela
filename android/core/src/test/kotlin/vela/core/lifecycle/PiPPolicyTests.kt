package vela.core.lifecycle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.lane.LaneEndReason
import vela.core.base.lane.PlaybackLaneRole

/** 修订 A2：画中画策略、静音会话和进后台分支。音频会话改断言 [PipAudioOutput]。 */
class PiPPolicyTests {
    private fun lane(
        role: PlaybackLaneRole = PlaybackLaneRole.PRIMARY,
        username: String? = "room",
        manuallyPaused: Boolean = false,
        visible: Boolean = true,
        failed: Boolean = false,
        activity: PhoneLaneActivity = PhoneLaneActivity.PLAYING,
    ): PhoneLaneSnapshot = PhoneLaneSnapshot(
        role = role,
        username = username,
        manuallyPaused = manuallyPaused,
        visible = visible,
        failed = failed,
        activity = activity,
    )

    private fun input(
        platform: PiPPolicy.Platform = PiPPolicy.Platform.PHONE,
        scene: PhoneScenePhase = PhoneScenePhase.BACKGROUND,
        pictureInPicture: PiPPolicy.State = PiPPolicy.State.NONE,
        autoEnterEnabled: Boolean = true,
        muted: Boolean = true,
        focus: PlaybackLaneRole = PlaybackLaneRole.PRIMARY,
        lanes: List<PlaybackLaneRole> = listOf(PlaybackLaneRole.PRIMARY, PlaybackLaneRole.SECONDARY),
        focusPlaying: Boolean = true,
        bossKey: Boolean = false,
        lockScreenOrSystemSleep: Boolean = false,
        windowMinimized: Boolean = false,
        windowOccluded: Boolean = false,
    ): PiPPolicy.Input = PiPPolicy.Input(
        platform = platform,
        scene = scene,
        pictureInPicture = pictureInPicture,
        autoEnterEnabled = autoEnterEnabled,
        muted = muted,
        focus = focus,
        lanes = lanes,
        focusPlaying = focusPlaying,
        bossKey = bossKey,
        lockScreenOrSystemSleep = lockScreenOrSystemSleep,
        windowMinimized = windowMinimized,
        windowOccluded = windowOccluded,
    )

    private fun fullStop(resume: PhoneResumeIntent?): List<PhoneLifecycleAction> = listOf(
        PhoneLifecycleAction.GateOff,
        PhoneLifecycleAction.EpochSuspend,
        PhoneLifecycleAction.SettleDwell(LaneEndReason.BACKGROUND),
        PhoneLifecycleAction.FlushSavers,
        PhoneLifecycleAction.SuspendLanes,
        PhoneLifecycleAction.AudioDeactivate,
        PhoneLifecycleAction.StopListener,
        PhoneLifecycleAction.NoteResumeIntent(resume),
    )

    private fun pictureInPictureBackground(
        focus: PlaybackLaneRole,
        releasing: List<PlaybackLaneRole>,
        resume: PhoneResumeIntent?,
    ): List<PhoneLifecycleAction> {
        val actions = mutableListOf(
            PhoneLifecycleAction.SuspendSharedTasks,
            PhoneLifecycleAction.AllowLaneRequests(focus),
            PhoneLifecycleAction.SettleDwell(LaneEndReason.BACKGROUND),
            PhoneLifecycleAction.FlushSavers,
        )
        actions.addAll(releasing.map { PhoneLifecycleAction.ReleaseLane(it) })
        actions.add(PhoneLifecycleAction.ContinueLane(focus))
        actions.add(PhoneLifecycleAction.RetainRelaySource(focus))
        actions.add(PhoneLifecycleAction.NoteResumeIntent(resume))
        return actions
    }

    /** 回桌面且自动进入开着：焦点路继续，另一路释放，中继只留焦点源。 */
    @Test
    fun testAutoEnterOnHomeContinuesFocusAndReleasesOtherLane() {
        val pip = input(
            scene = PhoneScenePhase.BACKGROUND,
            pictureInPicture = PiPPolicy.State.NONE,
            autoEnterEnabled = true,
            focusPlaying = true,
        )
        val decision = PiPPolicy.decide(pip)
        assertTrue(decision.playbackAllowed(PlaybackLaneRole.PRIMARY))
        assertFalse(decision.playbackAllowed(PlaybackLaneRole.SECONDARY))
        assertTrue(decision.keepsRelayListening)
        assertEquals(PlaybackLaneRole.PRIMARY, decision.retainedRelaySource)
        assertFalse(decision.shouldClosePictureInPicture)
        assertTrue(decision.keepsSessionActiveOnMute)
        assertEquals(mutedSessionHeld(), decision.audio)

        val lanes = listOf(lane(username = "focus"), lane(role = PlaybackLaneRole.SECONDARY, username = "other"))
        assertEquals(
            pictureInPictureBackground(
                focus = PlaybackLaneRole.PRIMARY,
                releasing = listOf(PlaybackLaneRole.SECONDARY),
                resume = PhoneResumeIntent.SEARCH,
            ),
            PhoneLifecycle.actions(
                from = PhoneScenePhase.ACTIVE,
                to = PhoneScenePhase.BACKGROUND,
                wasSuspended = false,
                lanes = lanes,
                resumeIntent = PhoneResumeIntent.SEARCH,
                pip = pip,
            ),
        )
        assertEquals(
            pictureInPictureBackground(
                focus = PlaybackLaneRole.PRIMARY,
                releasing = listOf(PlaybackLaneRole.SECONDARY),
                resume = null,
            ),
            PhoneLifecycle.actions(
                from = PhoneScenePhase.INACTIVE,
                to = PhoneScenePhase.BACKGROUND,
                wasSuspended = false,
                lanes = lanes,
                resumeIntent = null,
                pip = pip,
            ),
        )

        val secondaryFocus = input(focus = PlaybackLaneRole.SECONDARY, focusPlaying = true)
        assertTrue(PiPPolicy.decide(secondaryFocus).playbackAllowed(PlaybackLaneRole.SECONDARY))
        assertFalse(PiPPolicy.decide(secondaryFocus).playbackAllowed(PlaybackLaneRole.PRIMARY))
        assertEquals(PlaybackLaneRole.SECONDARY, PiPPolicy.decide(secondaryFocus).retainedRelaySource)
        assertEquals(
            pictureInPictureBackground(
                focus = PlaybackLaneRole.SECONDARY,
                releasing = listOf(PlaybackLaneRole.PRIMARY),
                resume = null,
            ),
            PhoneLifecycle.actions(
                from = PhoneScenePhase.ACTIVE,
                to = PhoneScenePhase.BACKGROUND,
                wasSuspended = false,
                lanes = lanes,
                resumeIntent = null,
                pip = secondaryFocus,
            ),
        )
    }

    /** 焦点路已经在画中画（含 starting）：进后台继续，自动进入开关关掉也一样。 */
    @Test
    fun testPictureInPictureAlreadyActiveContinuesFocusOnBackground() {
        for (state in listOf(PiPPolicy.State.ACTIVE, PiPPolicy.State.STARTING)) {
            val pip = input(
                scene = PhoneScenePhase.BACKGROUND,
                pictureInPicture = state,
                autoEnterEnabled = false,
                focusPlaying = false,
            )
            val decision = PiPPolicy.decide(pip)
            assertTrue(decision.playbackAllowed(PlaybackLaneRole.PRIMARY), "$state")
            assertFalse(decision.playbackAllowed(PlaybackLaneRole.SECONDARY), "$state")
            assertTrue(decision.keepsRelayListening, "$state")
            assertEquals(PlaybackLaneRole.PRIMARY, decision.retainedRelaySource, "$state")
            assertFalse(decision.shouldClosePictureInPicture, "$state")
            assertEquals(
                pictureInPictureBackground(
                    focus = PlaybackLaneRole.PRIMARY,
                    releasing = listOf(PlaybackLaneRole.SECONDARY),
                    resume = PhoneResumeIntent.RELOAD,
                ),
                PhoneLifecycle.actions(
                    from = PhoneScenePhase.ACTIVE,
                    to = PhoneScenePhase.BACKGROUND,
                    wasSuspended = false,
                    lanes = listOf(lane(), lane(role = PlaybackLaneRole.SECONDARY)),
                    resumeIntent = PhoneResumeIntent.RELOAD,
                    pip = pip,
                ),
                "$state",
            )
        }
    }

    /**
     * 焦点路还在首次解析、切房解析或尚未播放的刷新：画中画输入说焦点路没在播，回桌面走完整停播序列。
     * `shouldPlay` 只能收紧 `pip.focusPlaying`，不能把「没在播」改写成「在播」。
     */
    @Test
    fun testNotYetPlayingFocusStopsEveryLaneOnHome() {
        for (activity in listOf(
            PhoneLaneActivity.FIRST_RESOLVE,
            PhoneLaneActivity.ROOM_SWITCH,
            PhoneLaneActivity.REFRESHING,
        )) {
            val pip = input(
                scene = PhoneScenePhase.BACKGROUND,
                pictureInPicture = PiPPolicy.State.NONE,
                autoEnterEnabled = true,
                focusPlaying = false,
            )
            assertEquals(
                fullStop(PhoneResumeIntent.RELOAD),
                PhoneLifecycle.actions(
                    from = PhoneScenePhase.ACTIVE,
                    to = PhoneScenePhase.BACKGROUND,
                    wasSuspended = false,
                    lanes = listOf(lane(activity = activity), lane(role = PlaybackLaneRole.SECONDARY, username = "other")),
                    resumeIntent = PhoneResumeIntent.RELOAD,
                    pip = pip,
                ),
                "$activity",
            )
        }
    }

    /** 画中画说焦点路在播，但焦点路已暂停：取交集，仍是全停。 */
    @Test
    fun testFocusPlayingIsIntersectedWithShouldPlay() {
        val pip = input(
            scene = PhoneScenePhase.BACKGROUND,
            pictureInPicture = PiPPolicy.State.NONE,
            autoEnterEnabled = true,
            focusPlaying = true,
        )
        assertEquals(
            fullStop(null),
            PhoneLifecycle.actions(
                from = PhoneScenePhase.ACTIVE,
                to = PhoneScenePhase.BACKGROUND,
                wasSuspended = false,
                lanes = listOf(lane(manuallyPaused = true), lane(role = PlaybackLaneRole.SECONDARY, username = "other")),
                resumeIntent = null,
                pip = pip,
            ),
        )
    }

    /** 没有正在进行的画中画、也不会自动进入时，进后台仍是原来的全停。 */
    @Test
    fun testBackgroundWithoutPictureInPictureStopsEveryLane() {
        val paused = lane(manuallyPaused = true)
        val other = lane(role = PlaybackLaneRole.SECONDARY, username = "other")
        val notPlaying = input(
            pictureInPicture = PiPPolicy.State.NONE,
            autoEnterEnabled = true,
            focusPlaying = true,
        )
        assertEquals(
            fullStop(PhoneResumeIntent.RELOAD),
            PhoneLifecycle.actions(
                from = PhoneScenePhase.ACTIVE,
                to = PhoneScenePhase.BACKGROUND,
                wasSuspended = false,
                lanes = listOf(paused, other),
                resumeIntent = PhoneResumeIntent.RELOAD,
                pip = notPlaying,
            ),
        )
        val stopped = PiPPolicy.decide(
            input(
                scene = PhoneScenePhase.BACKGROUND,
                pictureInPicture = PiPPolicy.State.NONE,
                autoEnterEnabled = true,
                focusPlaying = false,
            ),
        )
        assertFalse(stopped.playbackAllowed(PlaybackLaneRole.PRIMARY))
        assertFalse(stopped.playbackAllowed(PlaybackLaneRole.SECONDARY))
        assertFalse(stopped.keepsRelayListening)
        assertNull(stopped.retainedRelaySource)
        assertFalse(stopped.shouldClosePictureInPicture)

        val stopping = input(
            pictureInPicture = PiPPolicy.State.STOPPING,
            autoEnterEnabled = true,
            focusPlaying = true,
        )
        assertFalse(PiPPolicy.decide(stopping).playbackAllowed(PlaybackLaneRole.PRIMARY))
        assertEquals(
            fullStop(PhoneResumeIntent.SEARCH),
            PhoneLifecycle.actions(
                from = PhoneScenePhase.ACTIVE,
                to = PhoneScenePhase.BACKGROUND,
                wasSuspended = false,
                lanes = listOf(lane(), lane(role = PlaybackLaneRole.SECONDARY)),
                resumeIntent = PhoneResumeIntent.SEARCH,
                pip = stopping,
            ),
        )
    }

    /** 自动进入关掉、又没在画中画：回桌面照旧全停。 */
    @Test
    fun testAutoEnterDisabledStopsOnHome() {
        val pip = input(
            scene = PhoneScenePhase.BACKGROUND,
            pictureInPicture = PiPPolicy.State.NONE,
            autoEnterEnabled = false,
            focusPlaying = true,
        )
        val decision = PiPPolicy.decide(pip)
        assertFalse(decision.playbackAllowed(PlaybackLaneRole.PRIMARY))
        assertFalse(decision.playbackAllowed(PlaybackLaneRole.SECONDARY))
        assertFalse(decision.keepsRelayListening)
        assertNull(decision.retainedRelaySource)
        assertFalse(decision.shouldClosePictureInPicture)
        assertFalse(decision.keepsSessionActiveOnMute)
        assertEquals(
            fullStop(PhoneResumeIntent.RELOAD),
            PhoneLifecycle.actions(
                from = PhoneScenePhase.ACTIVE,
                to = PhoneScenePhase.BACKGROUND,
                wasSuspended = false,
                lanes = listOf(lane(), lane(role = PlaybackLaneRole.SECONDARY)),
                resumeIntent = PhoneResumeIntent.RELOAD,
                pip = pip,
            ),
        )
    }

    /** 已经在后台时用户关掉画中画：补做原来的全停。仍开着则不重复发动作。 */
    @Test
    fun testClosingPictureInPictureWhileAlreadyBackgroundStopsEveryLane() {
        val stopping = input(
            pictureInPicture = PiPPolicy.State.STOPPING,
            autoEnterEnabled = true,
            focusPlaying = true,
        )
        assertEquals(
            fullStop(PhoneResumeIntent.RELOAD),
            PhoneLifecycle.actions(
                from = PhoneScenePhase.BACKGROUND,
                to = PhoneScenePhase.BACKGROUND,
                wasSuspended = true,
                lanes = listOf(lane(), lane(role = PlaybackLaneRole.SECONDARY)),
                resumeIntent = PhoneResumeIntent.RELOAD,
                pip = stopping,
            ),
        )
        val stillOpen = input(
            pictureInPicture = PiPPolicy.State.ACTIVE,
            autoEnterEnabled = true,
            focusPlaying = true,
        )
        assertEquals(
            emptyList(),
            PhoneLifecycle.actions(
                from = PhoneScenePhase.BACKGROUND,
                to = PhoneScenePhase.BACKGROUND,
                wasSuspended = true,
                lanes = listOf(lane(), lane(role = PlaybackLaneRole.SECONDARY)),
                resumeIntent = PhoneResumeIntent.RELOAD,
                pip = stillOpen,
            ),
        )
        val none = input(
            pictureInPicture = PiPPolicy.State.NONE,
            autoEnterEnabled = true,
            focusPlaying = true,
        )
        assertEquals(
            emptyList(),
            PhoneLifecycle.actions(
                from = PhoneScenePhase.BACKGROUND,
                to = PhoneScenePhase.BACKGROUND,
                wasSuspended = true,
                lanes = listOf(lane()),
                resumeIntent = PhoneResumeIntent.RELOAD,
                pip = none,
            ),
        )
    }

    /** mac 画中画中老板键、锁屏或系统休眠：关闭画中画并停播。遮挡不能压过老板键。 */
    @Test
    fun testMacBossKeyAndLockScreenClosePictureInPictureAndStopPlayback() {
        val boss = PiPPolicy.decide(
            input(
                platform = PiPPolicy.Platform.MAC,
                scene = PhoneScenePhase.ACTIVE,
                pictureInPicture = PiPPolicy.State.ACTIVE,
                bossKey = true,
                windowMinimized = true,
            ),
        )
        assertClosedAndStopped(boss)

        val locked = PiPPolicy.decide(
            input(
                platform = PiPPolicy.Platform.MAC,
                scene = PhoneScenePhase.ACTIVE,
                pictureInPicture = PiPPolicy.State.ACTIVE,
                lockScreenOrSystemSleep = true,
            ),
        )
        assertClosedAndStopped(locked)

        val starting = PiPPolicy.decide(
            input(
                platform = PiPPolicy.Platform.MAC,
                scene = PhoneScenePhase.ACTIVE,
                pictureInPicture = PiPPolicy.State.STARTING,
                bossKey = true,
            ),
        )
        assertClosedAndStopped(starting)

        val phone = PiPPolicy.decide(
            input(
                platform = PiPPolicy.Platform.PHONE,
                scene = PhoneScenePhase.BACKGROUND,
                pictureInPicture = PiPPolicy.State.ACTIVE,
                bossKey = true,
                lockScreenOrSystemSleep = true,
            ),
        )
        assertFalse(phone.shouldClosePictureInPicture)
        assertTrue(phone.playbackAllowed(PlaybackLaneRole.PRIMARY))
        assertTrue(phone.keepsRelayListening)
    }

    /** mac 最小化或主窗口被遮挡：画中画继续，不关闭。 */
    @Test
    fun testMacMinimizeAndOcclusionKeepPictureInPicture() {
        val minimized = PiPPolicy.decide(
            input(
                platform = PiPPolicy.Platform.MAC,
                scene = PhoneScenePhase.ACTIVE,
                pictureInPicture = PiPPolicy.State.ACTIVE,
                windowMinimized = true,
            ),
        )
        assertPictureInPictureContinues(minimized)

        val occluded = PiPPolicy.decide(
            input(
                platform = PiPPolicy.Platform.MAC,
                scene = PhoneScenePhase.ACTIVE,
                pictureInPicture = PiPPolicy.State.ACTIVE,
                windowOccluded = true,
            ),
        )
        assertPictureInPictureContinues(occluded)

        val both = PiPPolicy.decide(
            input(
                platform = PiPPolicy.Platform.MAC,
                scene = PhoneScenePhase.ACTIVE,
                pictureInPicture = PiPPolicy.State.ACTIVE,
                windowMinimized = true,
                windowOccluded = true,
            ),
        )
        assertPictureInPictureContinues(both)
    }

    /**
     * 静音画中画：与别人混音、不拿独占焦点，会话保持，放弃时不通知对方。
     * iOS 的 `.playback` + `.mixWithOthers` 落成 [PipAudioOutput]；步骤序列归 A2。
     */
    @Test
    fun testMutedPictureInPictureUsesMixWithOthers() {
        val quiet = PipAudioOutput.of(muted = true, sessionActive = true)
        assertEquals(mutedSessionHeld(), quiet)
        assertTrue(PipAudioOutput.keepsSessionWhenMuting(pictureInPicturePlaying = true))

        val decision = PiPPolicy.decide(
            input(
                platform = PiPPolicy.Platform.PHONE,
                scene = PhoneScenePhase.BACKGROUND,
                pictureInPicture = PiPPolicy.State.ACTIVE,
                muted = true,
            ),
        )
        assertEquals(quiet, decision.audio)
        assertTrue(decision.keepsSessionActiveOnMute)
        assertTrue(decision.playbackAllowed(PlaybackLaneRole.PRIMARY))
        assertEquals(PiPPolicy.SessionActivation.KEEP_ACTIVE, decision.sessionActivation)
    }

    /**
     * 取消静音：拿焦点、不混音。
     * `AudioTransition.plan` 的步骤（先关旧路再激活再开焦点路、不出混音选项）归 A2。
     */
    @Test
    fun testUnmuteDoesNotMixWithOthers() {
        val loud = PipAudioOutput.of(muted = false, sessionActive = true)
        assertEquals(audibleSessionHeld(), loud)
        assertTrue(loud.holdsFocus)
        assertFalse(loud.mixWithOthers)

        val decision = PiPPolicy.decide(
            input(
                platform = PiPPolicy.Platform.PHONE,
                scene = PhoneScenePhase.ACTIVE,
                pictureInPicture = PiPPolicy.State.ACTIVE,
                muted = false,
            ),
        )
        assertEquals(loud, decision.audio)
        assertFalse(decision.audio.mixWithOthers)
        assertTrue(decision.audio.holdsFocus)
        assertFalse(decision.audio.releaseNotifyingOthers)
        assertEquals(PiPPolicy.SessionActivation.KEEP_ACTIVE, decision.sessionActivation)
    }

    /**
     * 再静音：没在画中画则放弃焦点并通知对方恢复；画中画仍在播则只改成混音，不放弃。
     * `AudioTransition.plan` 的逐步列表归 A2，这里锁决策矩阵。
     */
    @Test
    fun testRemuteNotifiesOthersAndKeepsPictureInPictureSession() {
        assertTrue(PipAudioOutput.of(muted = true, sessionActive = false).releaseNotifyingOthers)
        assertFalse(PipAudioOutput.keepsSessionWhenMuting(pictureInPicturePlaying = false))
        assertTrue(PipAudioOutput.keepsSessionWhenMuting(pictureInPicturePlaying = true))

        val released = PipAudioOutput.of(muted = true, sessionActive = false)
        assertEquals(mutedSessionReleased(), released)
        val held = PipAudioOutput.of(muted = true, sessionActive = true)
        assertEquals(mutedSessionHeld(), held)
        assertFalse(held.releaseNotifyingOthers)

        val playing = PiPPolicy.decide(
            input(
                scene = PhoneScenePhase.ACTIVE,
                pictureInPicture = PiPPolicy.State.ACTIVE,
                muted = true,
                focusPlaying = true,
            ),
        )
        assertEquals(mutedSessionHeld(), playing.audio)
        assertTrue(playing.keepsSessionActiveOnMute)
        assertEquals(PiPPolicy.SessionActivation.KEEP_ACTIVE, playing.sessionActivation)
        assertTrue(playing.playbackAllowed(PlaybackLaneRole.PRIMARY))

        val inline = PiPPolicy.decide(
            input(
                scene = PhoneScenePhase.ACTIVE,
                pictureInPicture = PiPPolicy.State.NONE,
                muted = true,
                focusPlaying = true,
            ),
        )
        assertEquals(mutedSessionReleased(), inline.audio)
        assertFalse(inline.keepsSessionActiveOnMute)
        assertEquals(PiPPolicy.SessionActivation.DEACTIVATE_NOTIFYING_OTHERS, inline.sessionActivation)
        assertTrue(inline.playbackAllowed(PlaybackLaneRole.PRIMARY))
        assertTrue(inline.playbackAllowed(PlaybackLaneRole.SECONDARY))
    }

    private fun mutedSessionHeld(): PipAudioOutput = PipAudioOutput(
        holdsFocus = false,
        mixWithOthers = true,
        releaseNotifyingOthers = false,
    )

    private fun mutedSessionReleased(): PipAudioOutput = PipAudioOutput(
        holdsFocus = false,
        mixWithOthers = true,
        releaseNotifyingOthers = true,
    )

    private fun audibleSessionHeld(): PipAudioOutput = PipAudioOutput(
        holdsFocus = true,
        mixWithOthers = false,
        releaseNotifyingOthers = false,
    )

    private fun assertClosedAndStopped(decision: PiPPolicy.Decision) {
        assertTrue(decision.shouldClosePictureInPicture)
        assertFalse(decision.playbackAllowed(PlaybackLaneRole.PRIMARY))
        assertFalse(decision.playbackAllowed(PlaybackLaneRole.SECONDARY))
        assertFalse(decision.keepsRelayListening)
        assertNull(decision.retainedRelaySource)
        assertFalse(decision.keepsSessionActiveOnMute)
        assertEquals(PiPPolicy.SessionActivation.DEACTIVATE_NOTIFYING_OTHERS, decision.sessionActivation)
        assertEquals(mutedSessionReleased(), decision.audio)
    }

    private fun assertPictureInPictureContinues(decision: PiPPolicy.Decision) {
        assertFalse(decision.shouldClosePictureInPicture)
        assertTrue(decision.playbackAllowed(PlaybackLaneRole.PRIMARY))
        assertFalse(decision.playbackAllowed(PlaybackLaneRole.SECONDARY))
        assertTrue(decision.keepsRelayListening)
        assertEquals(PlaybackLaneRole.PRIMARY, decision.retainedRelaySource)
        assertTrue(decision.keepsSessionActiveOnMute)
        assertEquals(PiPPolicy.SessionActivation.KEEP_ACTIVE, decision.sessionActivation)
        assertEquals(mutedSessionHeld(), decision.audio)
    }
}
