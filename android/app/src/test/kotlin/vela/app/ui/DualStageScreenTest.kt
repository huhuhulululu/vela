package vela.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.stage.LaneFace
import vela.app.stage.LaneWindow
import vela.app.stage.StageIntent
import vela.app.stage.StageRules
import vela.core.base.lane.LaneID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class DualStageScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun testDualPanesRenderDistinctPlayersOnlyForOccupiedLanes() {
        val stage = testStage(dualRooms("alice", "bob"))
        compose.setContent {
            Box(Modifier.requiredSize(360.dp, 874.dp)) {
                DualStageScreen(
                    state = stage.state.value,
                    dispatch = stage::dispatch,
                    player = { room, _, active ->
                        if (active) BasicText("attached:${room.username}")
                    },
                )
            }
        }
        compose.requireTag("dual-pane:primary")
        compose.requireTag("dual-pane:secondary")
        compose.onNodeWithText("attached:alice").assertIsDisplayed()
        compose.onNodeWithText("attached:bob").assertIsDisplayed()
        assertEquals(
            2,
            compose.onAllNodesWithTag("player-slot", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun testFocusTapSwitchesFocusLane() {
        val stage = testStage(dualRooms("alice", "bob"))
        compose.setContent {
            Box(Modifier.requiredSize(360.dp, 874.dp)) {
                DualStageScreen(stage.state.value, stage::dispatch) { room, _, active ->
                    if (active) BasicText("attached:${room.username}")
                }
            }
        }
        compose.onNodeWithTag("dual-pane:secondary", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(LaneID.SECONDARY, stage.state.value.focusLane)
    }

    @Test
    fun testSecondarySwipeDispatchesStepWithLane() {
        val stage = testStage(dualRooms("alice", "bob", "cara"))
        val sent = ArrayList<StageIntent>()
        val dispatch: (StageIntent) -> Unit = {
            stage.dispatch(it)
            sent.add(it)
        }
        compose.setContent {
            Box(Modifier.requiredSize(360.dp, 874.dp)) {
                DualStageScreen(stage.state.value, dispatch) { room, _, active ->
                    if (active) BasicText("attached:${room.username}")
                }
            }
        }
        compose.onNodeWithTag("pager:secondary", useUnmergedTree = true).performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(sent.any { it is StageIntent.Step && it.lane == LaneID.SECONDARY })
    }

    @Test
    fun testEmptySecondaryShowsNoMoreRooms() {
        val opened = LaneWindow.setDual(pagedState(listOf("alice"), index = 0), on = true, remember = false)
        val stage = testStage(opened)
        compose.setContent {
            Box(Modifier.requiredSize(360.dp, 874.dp)) {
                DualStageScreen(stage.state.value, stage::dispatch) { _, _, _ -> }
            }
        }
        compose.onNodeWithText(StageRules.NO_MORE_ROOMS).assertIsDisplayed()
        compose.requireNoTag("pager:secondary")
    }

    @Test
    fun testLanePickerOpensDual() {
        val stage = testStage(pagedState(listOf("alice", "bob"), index = 0))
        compose.setContent {
            Box(Modifier.requiredSize(360.dp, 800.dp)) {
                StageScreen(
                    stage = stage,
                    player = { room, _, active ->
                        if (active) BasicText("attached:${room.username}")
                    },
                )
            }
        }
        compose.onNodeWithTag("lane-picker").performClick()
        compose.onNodeWithTag("lane-count:2").performClick()
        compose.waitForIdle()
        assertEquals(2, stage.state.value.laneCount)
    }

    private fun dualRooms(primary: String, secondary: String, extra: String? = null): vela.app.stage.StageUiState {
        val names = buildList {
            add(primary)
            add(secondary)
            if (extra != null) add(extra)
        }
        var state = pagedState(names, index = 0)
        state = LaneWindow.setDual(state, on = true, remember = false)
        return state.copy(
            laneCount = 2,
            rememberedLaneCount = 2,
            secondary = LaneFace(
                id = LaneID.SECONDARY,
                username = secondary,
                generation = 2,
            ),
        )
    }
}