package vela.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.stage.LaneFace
import vela.app.stage.LanePlayback
import vela.app.stage.LaneWindow
import vela.app.stage.StageContract
import vela.app.stage.StageIntent
import vela.app.stage.StageRules
import vela.app.stage.StageUiState
import vela.core.base.lane.LaneID
import vela.core.base.lane.RoomTarget
import vela.core.base.model.FeedSource
import vela.core.base.model.Room

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class DualStageScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun testPortraitDualShowsTwoPanesAndFocusBorder() {
        val stage = testStage(dualState(listOf("a", "b", "c", "d"), primary = "a", secondary = "b"))
        show(stage)
        compose.requireTag("dual-stage")
        compose.requireTag("pane:primary")
        compose.requireTag("pane:secondary")
        compose.requireTag("focus-border:primary")
        compose.requireNoTag("focus-border:secondary")
        compose.requireTag("pager:primary")
        compose.requireTag("pager:secondary")
        assertEquals(2, compose.onAllNodesWithTag("player-slot", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun testEmptySecondaryShowsNoMoreRoomsAndTakesFocusOnTap() {
        val stage = testStage(
            dualState(listOf("a"), primary = "a", secondary = null).copy(
                secondary = LaneFace(id = LaneID.SECONDARY, username = null, playback = LanePlayback.Empty),
            ),
        )
        show(stage)
        compose.onNodeWithText(StageRules.NO_MORE_ROOMS).assertIsDisplayed()
        compose.onNodeWithTag("pane:secondary").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(LaneID.SECONDARY, stage.state.value.focusLane)
        compose.requireTag("focus-border:secondary")
    }

    @Test
    fun testSwipeOnSecondaryTakesFocusAndStepsThatLane() {
        val stage = IntentProbe(dualState(listOf("a", "b", "c", "d"), primary = "a", secondary = "b"))
        show(stage)
        compose.onNodeWithTag("pager:secondary").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(stage.intents.any { it is StageIntent.SetFocus && it.lane == LaneID.SECONDARY })
        assertTrue(
            stage.intents.any {
                it is StageIntent.Step && it.lane == LaneID.SECONDARY && it.delta == 1
            },
        )
    }

    @Test
    fun testLandscapeFallsBackToSingleFocusPane() {
        val stage = testStage(
            dualState(listOf("a", "b", "c"), primary = "a", secondary = "b").copy(
                landscape = true,
                focusLane = LaneID.SECONDARY,
                secondary = LaneFace(
                    id = LaneID.SECONDARY,
                    username = "b",
                    generation = 2,
                    visible = true,
                ),
                lane = LaneFace(username = "a", generation = 1, visible = false),
            ),
        )
        // 横屏尺寸，避免 onSizeChanged 把 landscape 写回竖屏。
        show(stage, sizeWidth = 800.dp, sizeHeight = 360.dp)
        compose.requireNoTag("dual-stage")
        compose.requireTag("pager")
        compose.requireTag("room:b")
        compose.requireNoTag("pane:primary")
    }

    @Test
    fun testLaneCountPickerOpensDualAndReportsPortraitSize() {
        val stage = testStage(pagedState(listOf("a", "b", "c"), index = 0))
        show(stage, sizeWidth = 402.dp, sizeHeight = 874.dp)
        compose.waitForIdle()
        assertTrue(stage.state.value.portraitStageHeight > 0.0)
        assertFalse(stage.state.value.landscape)
        compose.onNodeWithTag("lane-count").assertIsDisplayed().performClick()
        compose.onNodeWithTag("lane-count:2").performClick()
        compose.waitForIdle()
        assertEquals(2, stage.state.value.laneCount)
        assertTrue(stage.state.value.dualWindow)
        compose.requireTag("dual-stage")
    }

    @Test
    fun testPaneActionsTargetSecondaryOnly() {
        val stage = IntentProbe(
            dualState(listOf("a", "b", "c"), primary = "a", secondary = "b") {
                copy(focusLane = LaneID.SECONDARY, chromeVisible = true)
            },
        )
        show(stage)
        compose.onNodeWithTag("pane-heart:secondary").performClick()
        compose.onNodeWithTag("pane-more:secondary").performClick()
        compose.onNodeWithTag("pane-pause:secondary").performClick()
        compose.waitForIdle()
        val favorite = stage.intents.filterIsInstance<StageIntent.ToggleFavorite>().single()
        assertEquals(LaneID.SECONDARY, favorite.target?.laneID)
        assertEquals("b", favorite.target?.username)
        val pause = stage.intents.filterIsInstance<StageIntent.TogglePause>().single()
        assertEquals(LaneID.SECONDARY, pause.target?.laneID)
        assertEquals("b", pause.target?.username)
        assertTrue(stage.intents.none { intent ->
            intent is StageIntent.ToggleFavorite && intent.target?.laneID == LaneID.PRIMARY
        })
    }

    @Test
    fun testDegradedSecondaryShowsDualUnavailable() {
        val stage = testStage(
            dualState(listOf("a", "b"), primary = "a", secondary = "b") {
                copy(
                    secondary = LaneFace(
                        id = LaneID.SECONDARY,
                        username = "b",
                        generation = 2,
                        playback = LanePlayback.Degraded,
                        playbackNote = StageRules.DUAL_UNAVAILABLE,
                    ),
                )
            },
        )
        show(stage)
        compose.onNodeWithText(StageRules.DUAL_UNAVAILABLE).assertIsDisplayed()
    }

    @Test
    fun testThreeLaneLayoutKeepsThreePanesWhenOpen() {
        val stage = testStage(
            dualState(listOf("a", "b", "c", "d"), primary = "a", secondary = "b").copy(
                laneCount = 3,
                rememberedLaneCount = 3,
                tertiary = LaneFace(id = LaneID.TERTIARY, username = "c", generation = 3),
                threeLanesFit = true,
                portraitStageHeight = 874.0,
                portraitStageWidth = 402.0,
            ),
        )
        show(stage, sizeWidth = 402.dp, sizeHeight = 874.dp)
        compose.requireTag("pane:primary")
        compose.requireTag("pane:secondary")
        compose.requireTag("pane:tertiary")
    }

    @Test
    fun testPictureInPictureHidesDualLayoutControlsAndSkipsSizeReporting() {
        val stage = IntentProbe(
            dualState(listOf("a", "b", "c"), primary = "a", secondary = "b") {
                copy(
                    focusLane = LaneID.SECONDARY,
                    chromeVisible = true,
                    pictureInPicture = true,
                    surface = vela.app.stage.StageSurface.Curation,
                    toast = "should-hide",
                    portraitStageWidth = 402.0,
                    portraitStageHeight = 874.0,
                    threeLanesFit = true,
                )
            },
        )
        // 模拟竖屏画中画小窗尺寸（width < height）：绝不能按双窗渲染，也不能污染竖屏尺寸与 threeLanesFit。
        show(stage, sizeWidth = 140.dp, sizeHeight = 240.dp)
        compose.waitForIdle()

        compose.requireTag("pip-stage")
        compose.onNodeWithText("attached:secondary:b").assertIsDisplayed()
        compose.requireNoTag("dual-stage")
        compose.requireNoTag("pane:primary")
        compose.requireNoTag("pane:secondary")
        compose.requireNoTag("heart")
        compose.requireNoTag("lane-count")
        compose.requireNoTag("block-current")
        assertTrue(
            "画中画期间 onSizeChanged 不得派发 SetLandscape 或 NotePortraitStageSize: ${stage.intents}",
            stage.intents.none { it is StageIntent.SetLandscape || it is StageIntent.NotePortraitStageSize },
        )
        assertEquals(874.0, stage.state.value.portraitStageHeight, 0.001)
        assertTrue(stage.state.value.threeLanesFit)
    }

    @Test
    fun testTopHeartTargetsFocusedSecondaryLane() {
        val stage = IntentProbe(
            dualState(listOf("a", "b", "c"), primary = "a", secondary = "b") {
                copy(
                    focusLane = LaneID.SECONDARY,
                    chromeVisible = true,
                    landscape = true,
                    secondary = LaneFace(id = LaneID.SECONDARY, username = "b", generation = 7, visible = true),
                    lane = LaneFace(username = "a", generation = 1, visible = false),
                )
            },
        )
        show(stage, sizeWidth = 800.dp, sizeHeight = 360.dp)
        compose.onNodeWithTag("heart").performClick()
        compose.waitForIdle()
        val topFav = stage.intents.filterIsInstance<StageIntent.ToggleFavorite>().single()
        assertEquals("顶栏心形应作用于焦点副路", LaneID.SECONDARY, topFav.target?.laneID)
        assertEquals("b", topFav.target?.username)
        assertEquals(7, topFav.target?.generation)
    }

    @Test
    fun testFavoritesSheetTargetsFocusedSecondaryLane() {
        val sheetStage = IntentProbe(
            dualState(listOf("a", "b", "c"), primary = "a", secondary = "b") {
                copy(
                    focusLane = LaneID.SECONDARY,
                    chromeVisible = true,
                    landscape = true,
                    surface = vela.app.stage.StageSurface.Curation,
                    secondary = LaneFace(id = LaneID.SECONDARY, username = "b", generation = 7, visible = true),
                    lane = LaneFace(username = "a", generation = 1, visible = false),
                )
            },
        )
        compose.setContent {
            Box(Modifier.requiredSize(360.dp, 800.dp)) {
                FavoritesSheet(state = sheetStage.state.value, dispatch = sheetStage::dispatch, onDismiss = {})
            }
        }
        compose.onNodeWithTag("favorite-current").performClick()
        compose.onNodeWithTag("block-current").performClick()
        compose.waitForIdle()
        val sheetFav = sheetStage.intents.filterIsInstance<StageIntent.ToggleFavorite>().single()
        assertEquals("收藏面板应收藏焦点副路", LaneID.SECONDARY, sheetFav.target?.laneID)
        assertEquals("b", sheetFav.target?.username)
        val sheetBlock = sheetStage.intents.filterIsInstance<StageIntent.BlockCurrent>().single()
        assertEquals("收藏面板应屏蔽焦点副路", LaneID.SECONDARY, sheetBlock.target?.laneID)
        assertEquals("b", sheetBlock.target?.username)
    }

    @Test
    fun testDualLandscapeShowsActionRailTargetingFocusedSecondaryLane() {
        val stage = IntentProbe(
            dualState(listOf("a", "b", "c"), primary = "a", secondary = "b") {
                copy(
                    focusLane = LaneID.SECONDARY,
                    chromeVisible = true,
                    landscape = true,
                    secondary = LaneFace(id = LaneID.SECONDARY, username = "b", generation = 7, visible = true),
                    lane = LaneFace(username = "a", generation = 1, visible = false),
                )
            },
        )
        show(stage, sizeWidth = 320.dp, sizeHeight = 260.dp)
        compose.onNodeWithTag("pause-room").assertIsDisplayed().performClick()
        compose.onNodeWithTag("not-interested").assertIsDisplayed().performClick()
        compose.onNodeWithTag("block-room").assertIsDisplayed().performClick()
        compose.waitForIdle()

        val pause = stage.intents.filterIsInstance<StageIntent.TogglePause>().single()
        assertEquals(LaneID.SECONDARY, pause.target?.laneID)
        assertEquals("b", pause.target?.username)
        assertEquals(7, pause.target?.generation)

        val notInterested = stage.intents.filterIsInstance<StageIntent.MarkNotInterested>().single()
        assertEquals(LaneID.SECONDARY, notInterested.target?.laneID)
        assertEquals("b", notInterested.target?.username)

        val block = stage.intents.filterIsInstance<StageIntent.BlockCurrent>().single()
        assertEquals(LaneID.SECONDARY, block.target?.laneID)
        assertEquals("b", block.target?.username)
    }

    @Test
    fun testLaneCountPickerOnTallPortraitDoesNotExposeThreeLanes() {
        val stage = IntentProbe(
            dualState(listOf("a", "b", "c"), primary = "a", secondary = "b") {
                copy(
                    chromeVisible = true,
                    threeLanesFit = true,
                    portraitStageWidth = 402.0,
                    portraitStageHeight = 874.0,
                )
            },
        )
        show(stage, sizeWidth = 402.dp, sizeHeight = 874.dp)
        compose.onNodeWithTag("lane-count").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.requireTag("lane-count:1")
        compose.requireTag("lane-count:2")
        compose.requireNoTag("lane-count:3")
    }

    private fun show(
        stage: StageContract,
        attached: MutableList<String>? = null,
        sizeWidth: androidx.compose.ui.unit.Dp = 360.dp,
        sizeHeight: androidx.compose.ui.unit.Dp = 800.dp,
    ) {
        compose.setContent {
            val seen = attached ?: remember { mutableStateListOf() }
            Box(Modifier.requiredSize(sizeWidth, sizeHeight)) {
                StageScreen(stage = stage) { lane, room: Room ->
                    seen.add("${lane.label}:${room.username}")
                    BasicText("attached:${lane.label}:${room.username}")
                }
            }
        }
    }
}

/** 只记录意图，并处理路数/焦点/尺寸，方便对照副路动作带的 [RoomTarget]。 */
private class IntentProbe(initial: StageUiState) : StageContract {
    private val mutable = MutableStateFlow(initial)
    override val state: StateFlow<StageUiState> = mutable
    val intents = mutableListOf<StageIntent>()

    override fun dispatch(intent: StageIntent) {
        intents += intent
        when (intent) {
            is StageIntent.SetFocus -> mutable.value = LaneWindow.setFocus(mutable.value, intent.lane)
            is StageIntent.SetLaneCount ->
                mutable.value = LaneWindow.setLaneCount(mutable.value, intent.count, intent.remember)
            is StageIntent.SetDual -> mutable.value = LaneWindow.setDual(mutable.value, intent.on, intent.remember)
            is StageIntent.SetLandscape -> mutable.value = LaneWindow.setLandscape(mutable.value, intent.on)
            is StageIntent.NotePortraitStageSize ->
                mutable.value = LaneWindow.notePortraitStageSize(mutable.value, intent.width, intent.height)
            StageIntent.NoteActivity -> mutable.value = mutable.value.copy(chromeVisible = true)
            is StageIntent.Step -> {
                if (intent.lane != mutable.value.focusLane) {
                    mutable.value = LaneWindow.setFocus(mutable.value, intent.lane)
                }
            }
            else -> Unit
        }
    }

    override fun noteReady(username: String?) = Unit

    override fun notePlaying(username: String?) = Unit

    override fun noteFailed(failed: Boolean, note: String) = Unit
}

internal fun dualState(
    names: List<String>,
    primary: String,
    secondary: String?,
    block: StageUiState.() -> StageUiState = { this },
): StageUiState {
    val rooms = names.map { testRoom(it) }
    return StageUiState(
        ageConfirmed = true,
        catalog = rooms,
        rooms = rooms,
        currentUsername = primary,
        source = FeedSource.guess,
        loadedSource = FeedSource.guess,
        chromeVisible = true,
        laneCount = 2,
        rememberedLaneCount = 2,
        focusLane = LaneID.PRIMARY,
        lane = LaneFace(username = primary, generation = 1),
        secondary = LaneFace(
            id = LaneID.SECONDARY,
            username = secondary,
            generation = 2,
            playback = if (secondary == null) LanePlayback.Empty else LanePlayback.Idle,
        ),
        portraitStageWidth = 360.0,
        portraitStageHeight = 800.0,
        threeLanesFit = true,
    ).block()
}
