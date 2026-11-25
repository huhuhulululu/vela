package vela.app.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vela.core.base.lane.LaneID
import vela.core.base.lane.RoomTarget
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.hls.PhoneThermalState

/**
 * 双窗 / 三窗契约。只断言路数、焦点、可见性和降级占位，不选房、不放播放器。
 * 类名带 StageContract，好让 `--tests '*StageContract*'` 挑中。
 */
class StageContractDualTest {
    @Test
    fun testSingleWindowDefaultsIgnoreExtraLanes() {
        val state = StageUiState(
            ageConfirmed = true,
            rooms = listOf(room("Ann")),
            currentUsername = "Ann",
            lane = LaneFace(generation = 3),
        )
        assertEquals(1, state.laneCount)
        assertEquals(1, state.rememberedLaneCount)
        assertEquals(LaneID.PRIMARY, state.focusLane)
        assertNull(state.secondary)
        assertNull(state.tertiary)
        assertFalse(state.dualWindow)
        assertTrue(state.playbackAllowed)
        assertTrue(state.accepts(RoomTarget.primary("ann", 3)))
        assertFalse(state.accepts(RoomTarget(LaneID.SECONDARY, "ann", 3)))
        assertFalse(state.accepts(null))
        assertEquals(state.current, state.focusRoom)
        val focused = LaneWindow.setFocus(state, LaneID.SECONDARY)
        assertEquals(state, focused)
    }

    @Test
    fun testSetDualOpensSecondLaneAndIsIdempotent() {
        val opened = LaneWindow.setDual(aged(), true)
        assertEquals(2, opened.laneCount)
        assertEquals(2, opened.rememberedLaneCount)
        assertTrue(opened.dualWindow)
        assertEquals(LaneID.PRIMARY, opened.focusLane)
        assertEquals(LaneID.SECONDARY, opened.secondary?.id)
        assertNull(opened.secondary?.username)
        assertEquals(LanePlayback.Empty, opened.secondary?.playback)
        assertEquals(StageRules.NO_MORE_ROOMS, opened.emptyLaneMessage(LaneID.SECONDARY))
        assertNull(opened.tertiary)
        assertEquals(opened, LaneWindow.setDual(opened, true))
    }

    @Test
    fun testSetDualOffFromThreeKeepsFocusRoomOnPrimary() {
        val triple = threeLanes(focus = LaneID.TERTIARY)
        val single = LaneWindow.setDual(triple, false)
        assertEquals(1, single.laneCount)
        assertEquals(1, single.rememberedLaneCount)
        assertEquals(LaneID.PRIMARY, single.focusLane)
        assertNull(single.secondary)
        assertNull(single.tertiary)
        assertEquals("cara", single.lane.username)
        assertEquals("cara", single.currentUsername)
        assertTrue(single.lane.visible)
        assertTrue(single.lane.generation > triple.lane.generation)
    }

    @Test
    fun testSetLaneCountClampsAndLocks() {
        val opened = LaneWindow.setLaneCount(aged(), 9)
        assertEquals(3, opened.laneCount)
        assertEquals(3, opened.rememberedLaneCount)
        assertEquals(LaneID.TERTIARY, opened.tertiary?.id)
        val floored = LaneWindow.setLaneCount(opened, 0)
        assertEquals(1, floored.laneCount)
        val locked = opened.copy(laneCountLocked = true)
        assertEquals(locked, LaneWindow.setLaneCount(locked, 1))
        assertEquals(locked, LaneWindow.setDual(locked, false))
    }

    @Test
    fun testShortScreenNeverOpensThirdAndExplicitChoiceIsRemembered() {
        val remembered = aged().copy(rememberedLaneCount = 3)
        val launched = LaneWindow.restoreRemembered(remembered)
        assertEquals(2, launched.laneCount)
        assertEquals(3, launched.rememberedLaneCount)
        assertNull(launched.tertiary)
        val short = LaneWindow.notePortraitStageSize(launched, width = 300.0, height = 400.0)
        assertFalse(short.threeLanesFit)
        assertEquals(2, short.laneCount)
        assertEquals(3, short.rememberedLaneCount)
        assertNull(short.tertiary)
        val chosen = LaneWindow.setLaneCount(short, 2)
        assertEquals(2, chosen.rememberedLaneCount)
        val tall = LaneWindow.notePortraitStageSize(chosen, width = 402.0, height = 874.0)
        assertTrue(tall.threeLanesFit)
        assertEquals("恢复高度后按用户新的选择，不再开第三路", 2, tall.laneCount)
        assertNull(tall.tertiary)
    }

    @Test
    fun testTallScreenRestoresRememberedThirdLane() {
        val short = LaneWindow.notePortraitStageSize(
            LaneWindow.restoreRemembered(aged().copy(rememberedLaneCount = 3)),
            width = 300.0,
            height = 400.0,
        )
        val tall = LaneWindow.notePortraitStageSize(short, width = 402.0, height = 874.0)
        assertTrue(tall.threeLanesFit)
        assertEquals(3, tall.laneCount)
        assertEquals(3, tall.rememberedLaneCount)
        assertEquals(LaneID.TERTIARY, tall.tertiary?.id)
    }

    @Test
    fun testLandscapeMeasurementDoesNotFlipFit() {
        val fitted = LaneWindow.notePortraitStageSize(threeLanes(), width = 300.0, height = 486.0)
        assertTrue(fitted.threeLanesFit)
        val ignored = LaneWindow.notePortraitStageSize(fitted, width = 486.0, height = 296.0)
        assertTrue(ignored.threeLanesFit)
        assertEquals(300.0, ignored.portraitStageWidth, 0.0)
        assertEquals(486.0, ignored.portraitStageHeight, 0.0)
        assertEquals(3, ignored.laneCount)
        assertEquals(fitted, ignored)
    }

    @Test
    fun testFocusMovesOnlyAmongOpenLanes() {
        val single = aged()
        assertEquals(single, LaneWindow.setFocus(single, LaneID.SECONDARY))
        val dual = LaneWindow.setDual(single, true)
        assertEquals(dual, LaneWindow.setFocus(dual, LaneID.TERTIARY))
        val focused = LaneWindow.setFocus(dual, LaneID.SECONDARY)
        assertEquals(LaneID.SECONDARY, focused.focusLane)
        assertEquals(focused, LaneWindow.setFocus(focused, LaneID.SECONDARY))
        val locked = dual.copy(focusLocked = true)
        assertEquals(locked, LaneWindow.setFocus(locked, LaneID.SECONDARY))
    }

    @Test
    fun testLandscapeReleasesNonFocusAndClosingRestoresPrimary() {
        val dual = LaneWindow.setDual(aged(), true).let { state ->
            state.copy(
                secondary = state.secondary?.copy(username = "bob", playback = LanePlayback.Playing),
            )
        }
        val landscape = LaneWindow.setLandscape(dual, true)
        assertTrue(landscape.landscape)
        assertTrue(landscape.lane.visible)
        assertFalse(landscape.secondary?.visible ?: true)
        assertEquals(landscape, LaneWindow.setLandscape(landscape, true))
        val focusSecondary = LaneWindow.setFocus(landscape, LaneID.SECONDARY)
        assertFalse(focusSecondary.lane.visible)
        assertTrue(focusSecondary.secondary?.visible ?: false)
        val closed = LaneWindow.setDual(focusSecondary, false)
        assertFalse(closed.landscape && !closed.lane.visible)
        assertTrue(closed.lane.visible)
        assertEquals("bob", closed.lane.username)
        assertNull(closed.secondary)
        val single = LaneWindow.setLandscape(aged(), true)
        assertTrue(single.lane.visible)
        assertNull(single.secondary)
    }

    @Test
    fun testThreeToTwoCompactsTertiaryFocusAndDropsItOtherwise() {
        val onTertiary = LaneWindow.setLaneCount(threeLanes(focus = LaneID.TERTIARY), 2, remember = false)
        assertEquals(2, onTertiary.laneCount)
        assertEquals(3, onTertiary.rememberedLaneCount)
        assertEquals(LaneID.SECONDARY, onTertiary.focusLane)
        assertEquals("cara", onTertiary.secondary?.username)
        assertNull(onTertiary.tertiary)
        val onPrimary = LaneWindow.setLaneCount(threeLanes(focus = LaneID.PRIMARY), 2, remember = false)
        assertEquals(LaneID.PRIMARY, onPrimary.focusLane)
        assertEquals("ann", onPrimary.lane.username)
        assertEquals("bob", onPrimary.secondary?.username)
        assertNull(onPrimary.tertiary)
        assertEquals(LaneID.PRIMARY, LaneWindow.setLaneCount(threeLanes(), 1).focusLane)
    }

    @Test
    fun testAcceptsTargetOnTheLaneThatShowsIt() {
        val dual = LaneWindow.setDual(aged(), true).let { state ->
            state.copy(
                secondary = state.secondary?.copy(username = "Bob", generation = 4, playback = LanePlayback.Playing),
            )
        }
        assertTrue(dual.accepts(RoomTarget(LaneID.SECONDARY, "bob", 4)))
        assertFalse(dual.accepts(RoomTarget(LaneID.SECONDARY, "bob", 5)))
        assertFalse(dual.accepts(RoomTarget(LaneID.TERTIARY, "bob", 4)))
        assertTrue(dual.accepts(RoomTarget.primary("ann", 0)))
        assertEquals(1, dual.indexOfLane(LaneID.SECONDARY))
        assertEquals(0, dual.indexOfLane(LaneID.PRIMARY))
        assertTrue(dual.lanesDistinct())
        assertFalse(
            dual.copy(
                lane = dual.lane.copy(username = "bob"),
                secondary = dual.secondary?.copy(username = "BOB"),
            ).lanesDistinct(),
        )
    }

    @Test
    fun testDegradeHoldsOnlyTheNonFocusLane() {
        val dual = LaneWindow.setDual(aged(), true).let { state ->
            state.copy(
                lane = state.lane.copy(username = "ann", playback = LanePlayback.Playing),
                secondary = state.secondary?.copy(username = "bob", playback = LanePlayback.Playing),
            )
        }
        assertEquals(dual, LaneWindow.degrade(dual, LaneID.PRIMARY))
        val degraded = LaneWindow.degrade(dual, LaneID.SECONDARY)
        assertEquals(LanePlayback.Playing, degraded.lane.playback)
        assertEquals("ann", degraded.lane.username)
        assertEquals(LanePlayback.Degraded, degraded.secondary?.playback)
        assertEquals(StageRules.DUAL_UNAVAILABLE, degraded.secondary?.playbackNote)
        assertEquals("bob", degraded.secondary?.username)
        assertFalse(degraded.lanePlaybackAllowed(LaneID.SECONDARY))
        assertTrue(degraded.lanePlaybackAllowed(LaneID.PRIMARY))
        val hidden = degraded.copy(lane = degraded.lane.copy(visible = false))
        assertFalse(hidden.lanePlaybackAllowed(LaneID.PRIMARY))
        val regained = LaneWindow.setFocus(degraded, LaneID.SECONDARY)
        assertEquals(LanePlayback.Idle, regained.secondary?.playback)
        assertNull(regained.secondary?.playbackNote)
        assertEquals(aged(), LaneWindow.degrade(aged(), LaneID.PRIMARY))
    }

    @Test
    fun testThermalSlotDefaultsNominal() {
        assertEquals(PhoneThermalState.NOMINAL, aged().thermal)
        val hot = LaneWindow.noteThermal(aged(), PhoneThermalState.SERIOUS)
        assertEquals(PhoneThermalState.SERIOUS, hot.thermal)
        assertEquals(hot, LaneWindow.noteThermal(hot, PhoneThermalState.SERIOUS))
    }

    @Test
    fun testFakeStageDispatchesLaneIntentsOnlyAfterAge() {
        val stage = FakeStage(cataloged())
        stage.dispatch(StageIntent.SetDual(true))
        assertEquals(1, stage.state.value.laneCount)
        stage.dispatch(StageIntent.ConfirmAge)
        stage.dispatch(StageIntent.SetLaneCount(3))
        assertEquals(3, stage.state.value.laneCount)
        stage.dispatch(StageIntent.SetFocus(LaneID.TERTIARY))
        assertEquals(LaneID.TERTIARY, stage.state.value.focusLane)
        stage.dispatch(StageIntent.SetLandscape(true))
        assertTrue(stage.state.value.landscape)
        assertTrue(stage.state.value.face(LaneID.TERTIARY)?.visible ?: false)
        assertFalse(stage.state.value.lane.visible)
    }

    private fun aged(): StageUiState = StageUiState(
        ageConfirmed = true,
        catalog = listOf(room("ann"), room("bob"), room("cara")),
        rooms = listOf(room("ann"), room("bob"), room("cara")),
        currentUsername = "ann",
        lane = LaneFace(username = "ann", playback = LanePlayback.Playing),
    )

    private fun cataloged(): StageUiState = StageUiState(
        catalog = listOf(room("ann"), room("bob"), room("cara")),
        currentUsername = "ann",
    )

    private fun threeLanes(focus: LaneID = LaneID.PRIMARY): StageUiState {
        val opened = LaneWindow.setLaneCount(aged(), 3)
        return opened.copy(
            focusLane = focus,
            lane = opened.lane.copy(username = "ann", playback = LanePlayback.Playing),
            secondary = opened.secondary?.copy(username = "bob", playback = LanePlayback.Playing),
            tertiary = opened.tertiary?.copy(username = "cara", playback = LanePlayback.Playing),
        )
    }
}

private fun room(name: String): Room = Room(username = name, show = ShowState.publicShow)
