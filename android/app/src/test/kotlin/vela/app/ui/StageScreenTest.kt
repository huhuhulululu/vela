package vela.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
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
import vela.app.stage.LanePlayback
import vela.app.stage.StageIntent
import vela.app.stage.StageSurface
import vela.app.stage.StageUiState
import vela.core.base.model.FeedSource
import vela.core.base.model.Room

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class StageScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun testAgeGateDispatchesConfirmAndQuitWithoutAttachingPlayer() {
        val stage = testStage(StageUiState())
        val attached = mutableStateListOf<String>()
        show(stage, attached)
        compose.onNodeWithText("已满 18 岁").assertIsDisplayed()
        compose.onNodeWithText("退出").performClick()
        assertEquals(listOf(vela.app.stage.StageEffect.Quit), stage.effects)
        assertTrue(!stage.state.value.ageConfirmed)
        compose.onNodeWithText("已满 18 岁").performClick()
        assertTrue(stage.state.value.ageConfirmed)
        assertEquals(emptyList<String>(), attached)
        compose.requireNoTag("player-slot")
        compose.requireNoTag("pager")
    }

    @Test
    fun testPagerRendersOnlyCurrentAndNeighbors() {
        val stage = testStage(pagedState(listOf("a", "b", "c", "d", "e"), index = 2))
        show(stage)
        compose.requireTag("room:b")
        compose.requireTag("room:c")
        compose.requireTag("room:d")
        compose.requireNoTag("room:a")
        compose.requireNoTag("room:e")
        assertEquals(1, compose.onAllNodesWithTag("player-slot").fetchSemanticsNodes().size)
    }

    @Test
    fun testEndsOfTheListDoNotInventPages() {
        val stage = testStage(pagedState(listOf("a", "b", "c"), index = 0))
        show(stage)
        compose.requireTag("room:a")
        compose.requireTag("room:b")
        compose.requireNoTag("room:c")
    }

    @Test
    fun testPlayerAttachesOnlyToTheCurrentPage() {
        val stage = testStage(pagedState(listOf("a", "b", "c", "d", "e"), index = 2))
        val attached = mutableStateListOf<String>()
        show(stage, attached)
        compose.waitForIdle()
        assertEquals(setOf("c"), attached.toSet())
        compose.onNodeWithText("attached:c").assertIsDisplayed()
        compose.requireNoText("attached:b")
        compose.requireNoText("attached:d")
    }

    @Test
    fun testSwipeUpStepsForwardAndMovesThePlayer() {
        val stage = testStage(pagedState(listOf("a", "b", "c", "d", "e"), index = 2))
        val attached = mutableStateListOf<String>()
        show(stage, attached)
        compose.onNodeWithTag("pager").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals("d", stage.state.value.currentUsername)
        compose.requireTag("room:e")
        compose.requireNoTag("room:b")
        compose.onNodeWithText("attached:d").assertIsDisplayed()
        compose.requireNoText("attached:c")
        compose.requireNoText("attached:b")
        compose.requireNoText("attached:e")
        assertEquals(setOf("c", "d"), attached.toSet())
        assertEquals(1, compose.onAllNodesWithTag("player-slot").fetchSemanticsNodes().size)
    }

    @Test
    fun testSwipeDownStepsBackward() {
        val stage = testStage(pagedState(listOf("a", "b", "c", "d", "e"), index = 2))
        show(stage)
        compose.onNodeWithTag("pager").performTouchInput {
            swipeDown(startY = centerY, endY = bottom - 1f)
        }
        compose.waitForIdle()
        assertEquals("b", stage.state.value.currentUsername)
        compose.requireTag("room:a")
        compose.requireNoTag("room:d")
    }

    @Test
    fun testSwipeAtTheEndDoesNotLeaveTheList() {
        val stage = testStage(pagedState(listOf("a", "b"), index = 1))
        show(stage)
        compose.onNodeWithTag("pager").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals("b", stage.state.value.currentUsername)
        compose.requireTag("room:a")
    }

    @Test
    fun testTapOnThePageBringsChromeBack() {
        val stage = testStage(pagedState(listOf("a", "b", "c"), index = 1, chromeVisible = false))
        show(stage)
        compose.requireNoTag("source:guess")
        compose.onNodeWithTag("pager").performTouchInput { click() }
        compose.waitForIdle()
        assertTrue(stage.state.value.chromeVisible)
        compose.onNodeWithTag("source:guess").assertIsDisplayed()
    }

    @Test
    fun testCaptionHidesOnTheCurrentPageWhenChromeIsHidden() {
        val stage = testStage(pagedState(listOf("a", "b", "c"), index = 1, chromeVisible = false))
        show(stage)
        compose.requireNoTag("caption:b")
        compose.requireTag("caption:a")
        compose.requireTag("caption:c")
    }

    @Test
    fun testCaptionStaysWhenInfoIsAlwaysOn() {
        val stage = testStage(
            pagedState(listOf("a", "b", "c"), index = 1, chromeVisible = false) {
                copy(infoAlwaysOn = true)
            },
        )
        show(stage)
        compose.requireTag("caption:b")
    }

    @Test
    fun testEmptyListShowsTheMessageAndRetry() {
        val stage = testStage(
            StageUiState(
                ageConfirmed = true,
                catalog = emptyList(),
                rooms = emptyList(),
                loading = false,
                chromeVisible = true,
            ),
        )
        show(stage)
        compose.onNodeWithText("目录里现在没有房间，可以稍后重试").assertIsDisplayed()
        compose.onNodeWithTag("empty-retry").performClick()
        assertTrue(stage.effects.any { it == vela.app.stage.StageEffect.CatalogReload })
        compose.requireNoTag("pager")
        compose.requireNoTag("player-slot")
    }

    @Test
    fun testLoadingEmptyListDoesNotOfferRetry() {
        val stage = testStage(StageUiState(ageConfirmed = true, loading = true, chromeVisible = true))
        show(stage)
        compose.onNodeWithText("正在整理在线房间").assertIsDisplayed()
        compose.requireNoTag("empty-retry")
    }

    @Test
    fun testFavoriteSourceWithNoFavoritesOpensTheSheet() {
        val stage = testStage(
            StageUiState(
                ageConfirmed = true,
                source = FeedSource.favorites,
                loadedSource = FeedSource.favorites,
                chromeVisible = true,
            ),
        )
        show(stage)
        compose.onNodeWithText("还没有收藏，在房间里按 ⌘D 收藏").assertIsDisplayed()
        compose.onNodeWithTag("empty-open-curation").performClick()
        assertEquals(StageSurface.Curation, stage.state.value.surface)
        compose.onNodeWithText("收藏夹").assertIsDisplayed()
    }

    @Test
    fun testFailureOffersRetryAndPauseCoversIt() {
        val stage = testStage(
            pagedState(listOf("a", "b", "c"), index = 1) {
                copy(
                    lane = LaneFace(
                        username = "b",
                        generation = 3,
                        playback = LanePlayback.Failed,
                        playbackNote = "画面中断了",
                    ),
                )
            },
        )
        show(stage)
        compose.onNodeWithText("画面中断了").assertIsDisplayed()
        compose.onNodeWithTag("retry-playback").performClick()
        assertEquals(LanePlayback.Idle, stage.state.value.lane.playback)
        stage.dispatch(StageIntent.TogglePause(stage.target()))
        compose.waitForIdle()
        compose.onNodeWithTag("paused-mark").assertIsDisplayed()
        compose.requireNoText("画面中断了")
    }

    @Test
    fun testHintToastAndFavoriteSuggest() {
        val stage = testStage(
            pagedState(listOf("a", "b"), index = 0) {
                copy(hintVisible = true, toast = "已记下，会少推荐类似的")
            },
        )
        show(stage)
        compose.onNodeWithText("上下滑动切换房间").assertIsDisplayed()
        compose.onNodeWithTag("close-hint").performClick()
        assertTrue(stage.state.value.hintSeen)
        compose.requireNoText("上下滑动切换房间")
        compose.onNodeWithText("已记下，会少推荐类似的").assertIsDisplayed()
        stage.presentFavoriteSuggest("nora", "Nora")
        compose.waitForIdle()
        compose.onNodeWithText("常看 Nora，收藏吗？").assertIsDisplayed()
        compose.onNodeWithTag("accept-suggest").performClick()
        assertTrue(stage.state.value.curation.isFavorite("nora"))
    }

    @Test
    fun testUndoToastRestoresTheFavorite() {
        val stage = testStage(
            pagedState(listOf("a"), index = 0) {
                copy(curation = withFavorite("a"))
            },
        )
        show(stage)
        compose.onNodeWithTag("heart").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("toast-undo").assertIsDisplayed().performClick()
        assertTrue(stage.state.value.curation.isFavorite("a"))
    }

    @Test
    fun testScrimDismissesFilters() {
        val filters = testStage(pagedState(listOf("a"), index = 0) { copy(surface = StageSurface.Filters) })
        show(filters)
        compose.onNodeWithTag("sheet-scrim").performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, 4f)) }
        assertEquals(StageSurface.None, filters.state.value.surface)
    }

    @Test
    fun testScrimDoesNotDismissTheChallenge() {
        val challenge = testStage(pagedState(listOf("a"), index = 0) { copy(surface = StageSurface.Challenge) })
        show(challenge)
        compose.onNodeWithTag("sheet-scrim").performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, 4f)) }
        assertEquals(StageSurface.Challenge, challenge.state.value.surface)
        compose.requireNoTag("challenge-web")
    }

    @Test
    fun testReasonIsShownOnlyForTheGuessSource() {
        val rooms = listOf(testRoom("a"))
        val stage = testStage(
            StageUiState(
                ageConfirmed = true,
                catalog = rooms,
                rooms = rooms,
                currentUsername = "a",
                source = FeedSource.guess,
                chromeVisible = true,
                reasons = mapOf("a" to "你常看这类"),
                lane = LaneFace(username = "a", generation = 1),
            ),
        )
        show(stage)
        compose.onNodeWithText("你常看这类").assertIsDisplayed()
    }

    private fun show(
        stage: vela.app.stage.FakeStage,
        attached: MutableList<String>? = null,
        challengeUrl: String? = null,
        onImport: (() -> Unit)? = null,
    ) {
        compose.setContent {
            val seen = attached ?: remember { mutableStateListOf() }
            Box(Modifier.requiredSize(360.dp, 800.dp)) {
                StageScreen(
                    stage = stage,
                    challengeUrl = challengeUrl,
                    onImportFavorites = onImport,
                    player = { room: Room ->
                        seen.add(room.username)
                        BasicText("attached:${room.username}")
                    },
                )
            }
        }
    }
}
