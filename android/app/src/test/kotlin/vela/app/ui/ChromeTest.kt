package vela.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.stage.StageEffect
import vela.app.stage.StageSurface
import vela.app.stage.StageUiState
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Region

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class ChromeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun testSourceHeartAndLongPressOpenTheRightSurfaces() {
        val stage = testStage(pagedState(listOf("a", "b", "c"), index = 1))
        show(stage)
        compose.onNodeWithTag("source:popular").performScrollTo().performClick()
        assertEquals(FeedSource.popular, stage.state.value.source)
        compose.onNodeWithTag("source:fresh").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("heart").performScrollTo().performClick()
        assertTrue(stage.state.value.curation.isFavorite("b"))
        compose.onNodeWithTag("heart").performScrollTo().performTouchInput { longClick() }
        assertEquals(StageSurface.Curation, stage.state.value.surface)
    }

    @Test
    fun testFilterSubmitsASiteSearchAndPicksGender() {
        val stage = testStage(pagedState(listOf("a", "b"), index = 0))
        show(stage)
        compose.onNodeWithTag("open-filters").performScrollTo().performClick()
        assertEquals(StageSurface.Filters, stage.state.value.surface)
        compose.onNodeWithTag("search-field").performScrollTo().performTextInput("nomatch")
        compose.onNodeWithTag("search-field").performImeAction()
        val search = stage.effects.filterIsInstance<StageEffect.SiteSearch>().single()
        assertEquals("nomatch", search.keyword)
        compose.onNodeWithTag("gender:f").performScrollTo().performClick()
        assertEquals(Gender.female, stage.state.value.gender)
        compose.onNodeWithTag("region:asia").performScrollTo().performClick()
        assertEquals(Region.asia, stage.state.value.region)
        compose.onNodeWithTag("public-only").performScrollTo().performClick()
        assertFalse(stage.state.value.publicOnly)
        compose.onNodeWithTag("tag-field").performScrollTo().performTextInput("jazz")
        compose.onNodeWithTag("apply-tag").performScrollTo().performClick()
        assertTrue(stage.effects.any { it == StageEffect.CatalogReload })
    }

    @Test
    fun testVolumePanelMutesAndSteps() {
        val stage = testStage(pagedState(listOf("a"), index = 0) { copy(muted = false, volume = 0.4) })
        show(stage)
        compose.onNodeWithTag("open-volume").performScrollTo().performClick()
        compose.onNodeWithText("静音").assertIsDisplayed().performClick()
        assertTrue(stage.state.value.muted)
        compose.onNodeWithTag("volume-up").performClick()
        assertFalse(stage.state.value.muted)
        assertTrue(stage.state.value.volume > 0.0)
    }

    @Test
    fun testRoomActionsPauseSkipAndBlock() {
        val stage = testStage(pagedState(listOf("a", "b", "c"), index = 1))
        show(stage)
        compose.onNodeWithTag("pause-room").performClick()
        assertTrue(stage.state.value.lane.manuallyPaused)
        compose.onNodeWithTag("pause-room").performClick()
        assertFalse(stage.state.value.lane.manuallyPaused)
        compose.onNodeWithTag("not-interested").performClick()
        assertEquals("c", stage.state.value.currentUsername)
        compose.onNodeWithText("已记下，会少推荐类似的").assertIsDisplayed()
        compose.onNodeWithTag("block-room").performClick()
        assertTrue(stage.state.value.curation.isBlocked("c"))
    }

    @Test
    fun testInfoToggleAndSiteSearchExit() {
        val stage = testStage(
            pagedState(listOf("a", "b"), index = 0) {
                copy(siteSearch = "jazz", searchText = "jazz")
            },
        )
        show(stage)
        compose.onNodeWithText("全站：jazz").assertIsDisplayed()
        compose.onNodeWithTag("exit-site-search").performClick()
        assertEquals(null, stage.state.value.siteSearch)
        compose.onNodeWithTag("toggle-info").performScrollTo().performClick()
        assertTrue(stage.state.value.infoAlwaysOn)
    }

    @Test
    fun testTasteButtonOpensTheSheet() {
        val stage = testStage(pagedState(listOf("a"), index = 0))
        show(stage)
        compose.onNodeWithTag("open-taste").performScrollTo().performClick()
        assertEquals(StageSurface.Taste, stage.state.value.surface)
        compose.onNodeWithText("我的口味").assertIsDisplayed()
    }

    private fun show(stage: vela.app.stage.FakeStage) {
        compose.setContent {
            Box(Modifier.requiredSize(360.dp, 800.dp)) {
                StageScreen(stage = stage)
            }
        }
    }
}
