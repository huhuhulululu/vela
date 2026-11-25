package vela.app.ui

import android.view.View
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.stage.FakeStage
import vela.app.stage.StageSurface
import vela.app.stage.StageUiState
import vela.core.base.model.FeedSource

/**
 * 系统栏避让。targetSdk 35 起，安卓 15 及以上一律全屏绘制，状态栏、导航条、挖孔的内边距由应用自己让。
 * 这里给 Compose 根视图派发一组内边距，断言所有可点控件都落在安全区内；
 * 视频画面与画中画按设计铺满整屏（与 iOS 的 ignoresSafeArea 一致），不跟着缩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
class SystemBarInsetsTest {
    @get:Rule
    val compose = createComposeRule()

    private val portrait = Bars(statusTop = 48, navBottom = 32)

    @Test
    fun testAgeGateClearsSystemBars() {
        showWithBars(testStage(StageUiState()), portrait)
        assertClickablesInsideSafeArea(portrait)
        val brand = compose.onNodeWithText("VELA").fetchSemanticsNode().boundsInRoot
        assertTrue("age gate brand ${brand.top} under status bar", brand.top >= portrait.statusTop)
    }

    @Test
    fun testEmptyStatusPaneClearsSystemBars() {
        showWithBars(testStage(StageUiState(ageConfirmed = true, loading = false, source = FeedSource.popular)), portrait)
        assertClickablesInsideSafeArea(portrait)
        compose.requireTag("empty-retry")
    }

    @Test
    fun testChromeRailCaptionAndNoticesClearSystemBars() {
        val stage = testStage(
            pagedState(listOf("a", "b", "c"), index = 1) {
                copy(hintVisible = true, toast = "提示", infoAlwaysOn = true)
            },
        )
        showWithBars(stage, portrait)
        assertClickablesInsideSafeArea(portrait)
        assertTop("source:guess", atLeast = portrait.statusTop)
        assertTop("lane-count", atLeast = portrait.statusTop)
        assertBottom("caption:b", atMost = SCREEN_H - portrait.navBottom)
        assertBottom("close-hint", atMost = SCREEN_H - portrait.navBottom)
    }

    @Test
    fun testVideoStaysFullBleedUnderSystemBars() {
        showWithBars(testStage(pagedState(listOf("a", "b", "c"), index = 1)), portrait)
        val slot = bounds("player-slot")
        assertEquals(0f, slot.top, 0.5f)
        assertEquals(SCREEN_H.toFloat(), slot.bottom, 0.5f)
    }

    @Test
    fun testEverySheetClearsSystemBars() {
        listOf(StageSurface.Filters, StageSurface.Curation, StageSurface.Taste, StageSurface.Challenge).forEach { surface ->
            val stage = testStage(pagedState(listOf("a", "b"), index = 0) { copy(surface = surface) })
            showWithBars(stage, portrait)
            assertClickablesInsideSafeArea(portrait, label = surface.name)
        }
    }

    @Test
    fun testDualStagePanesClearSystemBars() {
        val stage = testStage(dualState(listOf("a", "b", "c"), primary = "a", secondary = "b"))
        showWithBars(stage, portrait)
        assertClickablesInsideSafeArea(portrait)
        assertTop("pane:primary", atLeast = portrait.statusTop)
        assertBottom("pane:secondary", atMost = SCREEN_H - portrait.navBottom)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-mdpi")
    fun testLandscapeCutoutAndSideNavigationAreAvoided() {
        val bars = Bars(statusTop = 24, cutoutLeft = 40, navRight = 48)
        val stage = testStage(
            pagedState(listOf("a", "b", "c"), index = 1) { copy(infoAlwaysOn = true, surface = StageSurface.Filters) },
        )
        showWithBars(stage, bars, width = 800, height = 360)
        assertClickablesInsideSafeArea(bars, width = 800, height = 360)
    }

    @Test
    fun testPictureInPictureKeepsFullWindow() {
        val stage = testStage(pagedState(listOf("a"), index = 0) { copy(pictureInPicture = true) })
        showWithBars(stage, portrait)
        val pip = bounds("pip-stage")
        assertEquals(Rect(0f, 0f, SCREEN_W.toFloat(), SCREEN_H.toFloat()), pip)
    }

    private data class Bars(
        val statusTop: Int = 0,
        val navBottom: Int = 0,
        val cutoutLeft: Int = 0,
        val navRight: Int = 0,
    )

    private var root: View? = null

    private fun showWithBars(stage: FakeStage, bars: Bars, width: Int = SCREEN_W, height: Int = SCREEN_H) {
        if (root == null) {
            compose.setContent {
                root = LocalView.current
                current.value?.let { shown ->
                    StageScreen(stage = shown, player = { _, room -> BasicText("attached:${room.username}") })
                }
            }
        }
        compose.runOnIdle { current.value = stage }
        compose.waitForIdle()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, bars.statusTop, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, bars.navRight, bars.navBottom))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(bars.cutoutLeft, 0, 0, 0))
            .build()
        compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(root!!, insets) }
        compose.waitForIdle()
        val size = compose.onRoot().fetchSemanticsNode().size
        assertEquals("root width", width, size.width)
        assertEquals("root height", height, size.height)
    }

    private val current = androidx.compose.runtime.mutableStateOf<FakeStage?>(null)

    private fun assertClickablesInsideSafeArea(
        bars: Bars,
        width: Int = SCREEN_W,
        height: Int = SCREEN_H,
        label: String = "",
    ) {
        val safe = Rect(
            left = bars.cutoutLeft.toFloat(),
            top = bars.statusTop.toFloat(),
            right = (width - bars.navRight).toFloat(),
            bottom = (height - bars.navBottom).toFloat(),
        )
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("$label: no clickable found", nodes.isNotEmpty())
        val offenders = nodes.filter { node ->
            val tag = node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }
            if (tag == "sheet-scrim") return@filter false
            val b = node.boundsInRoot
            if (b.width <= 0f || b.height <= 0f) return@filter false
            b.left < safe.left - 0.5f || b.top < safe.top - 0.5f ||
                b.right > safe.right + 0.5f || b.bottom > safe.bottom + 0.5f
        }.map { node ->
            val tag = node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }
            "${tag ?: "node#${node.id}"}@${node.boundsInRoot}"
        }
        assertTrue("$label: clickables overlap system bars $safe: $offenders", offenders.isEmpty())
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun assertTop(tag: String, atLeast: Int) {
        val all = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("missing $tag", all.isNotEmpty())
        all.forEach { assertTrue("$tag top ${it.boundsInRoot.top} < $atLeast", it.boundsInRoot.top >= atLeast - 0.5f) }
    }

    private fun assertBottom(tag: String, atMost: Int) {
        val all = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("missing $tag", all.isNotEmpty())
        all.forEach { assertTrue("$tag bottom ${it.boundsInRoot.bottom} > $atMost", it.boundsInRoot.bottom <= atMost + 0.5f) }
    }

    private companion object {
        const val SCREEN_W = 360
        const val SCREEN_H = 800
    }
}
