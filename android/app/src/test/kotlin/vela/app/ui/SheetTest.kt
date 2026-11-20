package vela.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.stage.StageEffect
import vela.app.stage.StageSurface
import vela.app.stage.StageUiState
import vela.core.base.model.Room
import vela.core.base.model.TasteKeys
import vela.core.curation.Curation
import vela.core.curation.FavoritesBoard

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class SheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun testFavoritesSheetPlaysRemovesExportsAndImports() {
        var imports = 0
        val rooms = listOf(testRoom("a"), testRoom("alice", viewers = 40), testRoom("bob"))
        val curation = Curation(favorites = listOf("alice", "bob"), blocked = listOf("carol"))
        val stage = testStage(
            StageUiState(
                ageConfirmed = true,
                catalog = rooms,
                rooms = rooms,
                currentUsername = "a",
                chromeVisible = true,
                surface = StageSurface.Curation,
                curation = curation,
                favoritesBoard = FavoritesBoard.make(curation.favorites, emptyMap(), rooms),
                lane = vela.app.stage.LaneFace(username = "a", generation = 2),
            ),
        )
        show(stage) { imports += 1 }
        compose.onNodeWithText("收藏夹").assertIsDisplayed()
        compose.onNodeWithTag("favorite-play:alice").performClick()
        assertEquals("alice", stage.state.value.currentUsername)
        assertEquals(StageSurface.None, stage.state.value.surface)
        stage.dispatch(vela.app.stage.StageIntent.ShowSurface(StageSurface.Curation))
        compose.waitForIdle()
        compose.onNodeWithTag("favorite-remove:alice").performClick()
        assertFalse(stage.state.value.curation.isFavorite("alice"))
        compose.onNodeWithTag("favorite-undo").assertIsDisplayed().performClick()
        assertTrue(stage.state.value.curation.isFavorite("alice"))
        compose.onNodeWithTag("export-favorites").performClick()
        assertTrue(stage.effects.any { it is StageEffect.ExportedFavorites })
        compose.onNodeWithTag("import-favorites").performClick()
        assertEquals(1, imports)
    }

    @Test
    fun testFavoritesAddAndBlockedPage() {
        val stage = testStage(
            pagedState(listOf("a"), index = 0) {
                copy(
                    surface = StageSurface.Curation,
                    curation = Curation(favorites = listOf("a"), blocked = listOf("carol")),
                )
            },
        )
        show(stage)
        compose.onNodeWithTag("add-favorite").performTextInput("!!")
        compose.onNodeWithTag("add-favorite-submit").performClick()
        compose.onNodeWithText("用户名只含字母、数字、下划线，2 到 30 位").assertIsDisplayed()
        compose.onNodeWithTag("add-favorite").performTextReplacement("zoe")
        compose.onNodeWithTag("add-favorite-submit").performClick()
        assertTrue(stage.state.value.curation.isFavorite("zoe"))
        compose.requireNoText("用户名只含字母、数字、下划线，2 到 30 位")
        compose.onNodeWithTag("open-blocked").performClick()
        compose.onNodeWithText("carol").assertIsDisplayed()
        compose.onNodeWithTag("unblock:carol").performClick()
        assertFalse(stage.state.value.curation.isBlocked("carol"))
        compose.onNodeWithTag("back-blocked").performClick()
        compose.onNodeWithTag("block-current").performClick()
        assertTrue(stage.state.value.curation.isBlocked("a"))
    }

    @Test
    fun testRefreshFavoritesWhileCheckingIsDisabled() {
        val stage = testStage(
            pagedState(listOf("a"), index = 0) {
                copy(surface = StageSurface.Curation, favoritesChecking = true, curation = Curation(favorites = listOf("a")))
            },
        )
        show(stage)
        compose.onNodeWithText("查询中").assertIsDisplayed()
        val before = stage.effects.size
        compose.onNodeWithTag("refresh-favorites").performClick()
        assertEquals(before, stage.effects.size)
    }

    @Test
    fun testTasteBoostVetoExpandAndReset() {
        val liked = (0 until 9).map { tasteRow("t:tag$it", "tag$it") }
        val stage = testStage(
            pagedState(listOf("a"), index = 0, block = {
                copy(
                    surface = StageSurface.Taste,
                    taste = withTaste(liked, suggestions = listOf("blonde")).copy(
                        disliked = listOf(tasteRow("t:nope", "nope", mean = 0.2, hits = 0, vetoed = true)),
                        genders = listOf(tasteRow("g:f", "f", mean = 0.8, hits = 3)),
                        tagCount = 1,
                    ),
                )
            }),
        )
        show(stage)
        compose.onNodeWithText("我的口味").assertIsDisplayed()
        compose.onNodeWithText("偏好只保存在这台设备上，不上传。").assertIsDisplayed()
        compose.requireTag("taste-boost:t:tag0")
        compose.requireNoTag("taste-boost:t:tag8")
        compose.onNodeWithTag("expand-liked").performScrollTo().performClick()
        compose.requireTag("taste-boost:t:tag8")
        compose.onNodeWithTag("suggest-like:blonde").performScrollTo().performClick()
        assertTrue(stage.state.value.taste.liked.any { it.key == TasteKeys.tagKey("blonde") })
        compose.onNodeWithTag("taste-veto:t:tag0").performScrollTo().performClick()
        assertTrue(stage.state.value.taste.disliked.any { it.key == "t:tag0" })
        compose.onNodeWithTag("taste-restore:t:nope").performScrollTo().performClick()
        assertFalse(stage.state.value.taste.disliked.any { it.key == "t:nope" && it.vetoed })
        compose.onNodeWithTag("taste-row:g:f").performScrollTo()
        compose.onNodeWithText("女").assertIsDisplayed()
        compose.onNodeWithTag("pause-learning").performClick()
        assertTrue(stage.state.value.taste.paused)
        compose.onNodeWithTag("reset-taste").performClick()
        compose.onNodeWithTag("confirm-danger").performClick()
        assertTrue(stage.state.value.taste.liked.isEmpty())
        compose.onNodeWithText("口味画像已重置").assertIsDisplayed()
    }

    @Test
    fun testClearPreferencesAsksFirst() {
        val stage = testStage(
            pagedState(listOf("a"), index = 0) {
                copy(surface = StageSurface.Taste, taste = withTaste(listOf(tasteRow("t:a", "a"))))
            },
        )
        show(stage)
        compose.onNodeWithTag("clear-preferences").performClick()
        compose.onNodeWithTag("cancel-danger").performClick()
        assertEquals(1, stage.state.value.taste.liked.size)
        compose.onNodeWithTag("clear-preferences").performClick()
        compose.onNodeWithTag("confirm-danger").performClick()
        assertTrue(stage.state.value.taste.liked.isEmpty())
    }

    @Test
    fun testChallengeSheetConfirmsWithoutOpeningTheNetwork() {
        val stage = testStage(pagedState(listOf("a"), index = 0) { copy(surface = StageSurface.Challenge) })
        show(stage)
        compose.onNodeWithText("确认访问").assertIsDisplayed()
        compose.onNodeWithText("站点要求确认这次访问。完成后回到播放器即可，画面不会在这个窗口里播放。").assertIsDisplayed()
        compose.requireNoTag("challenge-web")
        compose.onNodeWithTag("challenge-done").performClick()
        assertEquals(StageSurface.None, stage.state.value.surface)
        stage.dispatch(vela.app.stage.StageIntent.ShowSurface(StageSurface.Challenge))
        compose.waitForIdle()
        compose.onNodeWithTag("challenge-close").performClick()
        assertEquals(StageSurface.None, stage.state.value.surface)
        assertNull(stage.state.value.challengeResume)
    }

    @Test
    fun testChallengeSheetCanHostAWebViewWhenAUrlIsGiven() {
        val stage = testStage(pagedState(listOf("a"), index = 0) { copy(surface = StageSurface.Challenge) })
        show(stage, challengeUrl = "about:blank")
        compose.requireTag("challenge-web")
    }

    private fun show(
        stage: vela.app.stage.FakeStage,
        challengeUrl: String? = null,
        onImport: () -> Unit = {},
    ) {
        compose.setContent {
            Box(Modifier.requiredSize(360.dp, 800.dp)) {
                StageScreen(
                    stage = stage,
                    challengeUrl = challengeUrl,
                    onImportFavorites = onImport,
                    player = { _, _, _ -> },
                )
            }
        }
    }
}
