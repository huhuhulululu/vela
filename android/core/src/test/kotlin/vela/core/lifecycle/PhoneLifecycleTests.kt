package vela.core.lifecycle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vela.core.base.lane.LaneEndReason
import vela.core.base.lane.PlaybackLaneRole

class PhoneLifecycleTests {
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

    private fun actions(
        from: PhoneScenePhase,
        to: PhoneScenePhase,
        wasSuspended: Boolean,
        lanes: List<PhoneLaneSnapshot>,
        resumeIntent: PhoneResumeIntent? = null,
    ): List<PhoneLifecycleAction> = PhoneLifecycle.actions(
        from = from,
        to = to,
        wasSuspended = wasSuspended,
        lanes = lanes,
        resumeIntent = resumeIntent,
    )

    /** 进后台：gateOff 在 suspendLanes 前，audio.deactivate 在 stopListener 前。 */
    @Test
    fun testEnteringBackgroundOrdersGateOffBeforeSuspendLanesAndDeactivateBeforeStopListener() {
        val lanes = listOf(lane())
        val entered = actions(
            from = PhoneScenePhase.ACTIVE,
            to = PhoneScenePhase.BACKGROUND,
            wasSuspended = false,
            lanes = lanes,
            resumeIntent = PhoneResumeIntent.RELOAD,
        )
        assertEquals(
            listOf(
                PhoneLifecycleAction.GateOff,
                PhoneLifecycleAction.EpochSuspend,
                PhoneLifecycleAction.SettleDwell(LaneEndReason.BACKGROUND),
                PhoneLifecycleAction.FlushSavers,
                PhoneLifecycleAction.SuspendLanes,
                PhoneLifecycleAction.AudioDeactivate,
                PhoneLifecycleAction.StopListener,
                PhoneLifecycleAction.NoteResumeIntent(PhoneResumeIntent.RELOAD),
            ),
            entered,
        )
        assertTrue(entered.indexOf(PhoneLifecycleAction.GateOff) < entered.indexOf(PhoneLifecycleAction.SuspendLanes))
        assertTrue(
            entered.indexOf(PhoneLifecycleAction.AudioDeactivate) < entered.indexOf(PhoneLifecycleAction.StopListener),
        )

        val fromInactive = actions(
            from = PhoneScenePhase.INACTIVE,
            to = PhoneScenePhase.BACKGROUND,
            wasSuspended = false,
            lanes = lanes,
            resumeIntent = PhoneResumeIntent.SEARCH,
        )
        assertEquals(
            listOf(
                PhoneLifecycleAction.GateOff,
                PhoneLifecycleAction.EpochSuspend,
                PhoneLifecycleAction.SettleDwell(LaneEndReason.BACKGROUND),
                PhoneLifecycleAction.FlushSavers,
                PhoneLifecycleAction.SuspendLanes,
                PhoneLifecycleAction.AudioDeactivate,
                PhoneLifecycleAction.StopListener,
                PhoneLifecycleAction.NoteResumeIntent(PhoneResumeIntent.SEARCH),
            ),
            fromInactive,
        )
        assertEquals(
            emptyList(),
            actions(
                from = PhoneScenePhase.BACKGROUND,
                to = PhoneScenePhase.BACKGROUND,
                wasSuspended = true,
                lanes = lanes,
                resumeIntent = PhoneResumeIntent.RELOAD,
            ),
        )
    }

    /** 回前台：首次解析、切房解析、刷新中都重新起播。 */
    @Test
    fun testForegroundRestartsFirstResolveRoomSwitchAndRefresh() {
        val first = lane(username = "a", activity = PhoneLaneActivity.FIRST_RESOLVE)
        val switching = lane(
            role = PlaybackLaneRole.SECONDARY,
            username = "b",
            activity = PhoneLaneActivity.ROOM_SWITCH,
        )
        val refreshing = lane(username = "c", activity = PhoneLaneActivity.REFRESHING)
        assertEquals(
            listOf(
                PhoneLifecycleAction.EpochResume,
                PhoneLifecycleAction.RequestPlay(PlaybackLaneRole.PRIMARY, PhoneLaneActivity.FIRST_RESOLVE),
            ),
            actions(
                from = PhoneScenePhase.BACKGROUND,
                to = PhoneScenePhase.ACTIVE,
                wasSuspended = true,
                lanes = listOf(first),
            ),
        )
        assertEquals(
            listOf(
                PhoneLifecycleAction.EpochResume,
                PhoneLifecycleAction.RequestPlay(PlaybackLaneRole.SECONDARY, PhoneLaneActivity.ROOM_SWITCH),
            ),
            actions(
                from = PhoneScenePhase.BACKGROUND,
                to = PhoneScenePhase.ACTIVE,
                wasSuspended = true,
                lanes = listOf(switching),
            ),
        )
        assertEquals(
            listOf(
                PhoneLifecycleAction.EpochResume,
                PhoneLifecycleAction.RequestPlay(PlaybackLaneRole.PRIMARY, PhoneLaneActivity.REFRESHING),
            ),
            actions(
                from = PhoneScenePhase.BACKGROUND,
                to = PhoneScenePhase.ACTIVE,
                wasSuspended = true,
                lanes = listOf(refreshing),
            ),
        )
        assertTrue(PhoneLifecycle.shouldPlay(first))
        assertTrue(PhoneLifecycle.shouldPlay(switching))
        assertTrue(PhoneLifecycle.shouldPlay(refreshing))
    }

    /** 失败态不自动重播，手动暂停保持暂停，隐藏路保持释放。 */
    @Test
    fun testForegroundKeepsFailurePausedAndHiddenLanes() {
        val failed = lane(username = "a", failed = true)
        val paused = lane(role = PlaybackLaneRole.SECONDARY, username = "b", manuallyPaused = true)
        val hidden = lane(username = "c", visible = false)
        assertEquals(
            listOf(
                PhoneLifecycleAction.EpochResume,
                PhoneLifecycleAction.KeepFailure(PlaybackLaneRole.PRIMARY),
            ),
            actions(
                from = PhoneScenePhase.BACKGROUND,
                to = PhoneScenePhase.ACTIVE,
                wasSuspended = true,
                lanes = listOf(failed),
            ),
        )
        assertEquals(
            listOf(
                PhoneLifecycleAction.EpochResume,
                PhoneLifecycleAction.StayPaused(PlaybackLaneRole.SECONDARY),
            ),
            actions(
                from = PhoneScenePhase.BACKGROUND,
                to = PhoneScenePhase.ACTIVE,
                wasSuspended = true,
                lanes = listOf(paused),
            ),
        )
        assertEquals(
            listOf(
                PhoneLifecycleAction.EpochResume,
                PhoneLifecycleAction.StayReleased(PlaybackLaneRole.PRIMARY),
            ),
            actions(
                from = PhoneScenePhase.BACKGROUND,
                to = PhoneScenePhase.ACTIVE,
                wasSuspended = true,
                lanes = listOf(hidden),
            ),
        )
        assertEquals(
            listOf(
                PhoneLifecycleAction.EpochResume,
                PhoneLifecycleAction.KeepFailure(PlaybackLaneRole.PRIMARY),
                PhoneLifecycleAction.StayPaused(PlaybackLaneRole.SECONDARY),
                PhoneLifecycleAction.StayReleased(PlaybackLaneRole.PRIMARY),
            ),
            actions(
                from = PhoneScenePhase.BACKGROUND,
                to = PhoneScenePhase.ACTIVE,
                wasSuspended = true,
                lanes = listOf(failed, paused, hidden),
            ),
        )
        assertFalse(PhoneLifecycle.shouldPlay(failed))
        assertFalse(PhoneLifecycle.shouldPlay(paused))
        assertFalse(PhoneLifecycle.shouldPlay(hidden))
    }

    /** 从后台经 inactive 回 active 仍按 wasSuspended 恢复；没挂起过则不重播。 */
    @Test
    fun testBackgroundThroughInactiveToActiveResumes() {
        val playing = lane(activity = PhoneLaneActivity.PLAYING)
        assertEquals(
            emptyList(),
            actions(
                from = PhoneScenePhase.BACKGROUND,
                to = PhoneScenePhase.INACTIVE,
                wasSuspended = true,
                lanes = listOf(playing),
                resumeIntent = PhoneResumeIntent.SEARCH,
            ),
        )
        assertEquals(
            listOf(
                PhoneLifecycleAction.EpochResume,
                PhoneLifecycleAction.RedoResumeIntent(PhoneResumeIntent.SEARCH),
                PhoneLifecycleAction.RequestPlay(PlaybackLaneRole.PRIMARY, PhoneLaneActivity.PLAYING),
            ),
            actions(
                from = PhoneScenePhase.INACTIVE,
                to = PhoneScenePhase.ACTIVE,
                wasSuspended = true,
                lanes = listOf(playing),
                resumeIntent = PhoneResumeIntent.SEARCH,
            ),
        )
        assertEquals(
            listOf(
                PhoneLifecycleAction.EpochResume,
                PhoneLifecycleAction.RequestPlay(PlaybackLaneRole.PRIMARY, PhoneLaneActivity.PLAYING),
            ),
            actions(
                from = PhoneScenePhase.BACKGROUND,
                to = PhoneScenePhase.ACTIVE,
                wasSuspended = true,
                lanes = listOf(playing),
            ),
        )
        assertEquals(
            emptyList(),
            actions(
                from = PhoneScenePhase.INACTIVE,
                to = PhoneScenePhase.ACTIVE,
                wasSuspended = false,
                lanes = listOf(playing),
                resumeIntent = PhoneResumeIntent.SEARCH,
            ),
        )
        assertEquals(
            emptyList(),
            actions(
                from = PhoneScenePhase.BACKGROUND,
                to = PhoneScenePhase.ACTIVE,
                wasSuspended = false,
                lanes = listOf(playing),
                resumeIntent = PhoneResumeIntent.RELOAD,
            ),
        )
    }

    @Test
    fun testPointVisibleAndAttentiveFollowScenePhase() {
        assertTrue(PhoneLifecycle.pointVisible(PhoneScenePhase.ACTIVE))
        assertTrue(PhoneLifecycle.pointVisible(PhoneScenePhase.INACTIVE))
        assertFalse(PhoneLifecycle.pointVisible(PhoneScenePhase.BACKGROUND))
        assertTrue(PhoneLifecycle.attentive(PhoneScenePhase.ACTIVE))
        assertFalse(PhoneLifecycle.attentive(PhoneScenePhase.INACTIVE))
        assertFalse(PhoneLifecycle.attentive(PhoneScenePhase.BACKGROUND))
    }
}
