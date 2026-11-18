package vela.core.selection

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import vela.core.base.lane.LaneEndReason
import vela.core.base.lane.PlaybackLaneRole
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.ledger.WatchLedger
import vela.core.taste.Feedback
import vela.core.taste.Taste

class LaneSettlementTests {
    private val now: Instant = Instant.ofEpochSecond(1_800_000_000)

    private fun span(seconds: Double, focused: Boolean): LaneSettlement.Span =
        LaneSettlement.Span(seconds = seconds, focused = focused)

    private fun settle(
        lane: PlaybackLaneRole = PlaybackLaneRole.PRIMARY,
        username: String = "a",
        source: FeedSource = FeedSource.guess,
        spans: List<LaneSettlement.Span>,
        end: LaneEndReason,
        userInitiated: Boolean = false,
    ): LaneSettlement =
        LaneSettlement.settle(
            lane = lane,
            username = username,
            source = source,
            spans = spans,
            endReason = end,
            userInitiated = userInitiated,
        )

    private fun room(name: String, tags: List<String>): Room =
        Room(username = name, viewers = 10, gender = null, tags = tags, show = ShowState.publicShow)

    private fun hit(taste: Taste, tag: String): Double =
        taste.posterior(Taste.tagKey(tag), now).alpha - 1

    private fun miss(taste: Taste, tag: String): Double =
        taste.posterior(Taste.tagKey(tag), now).beta - 1

    private fun dailyHits(taste: Taste): Int = taste.daily.values.sumOf { it.hit }

    private fun dailyMisses(taste: Taste): Int = taste.daily.values.sumOf { it.miss }

    /** 后台结束的 5 秒不算落空；账本仍记加权秒。较长的后台停留仍可记命中。 */
    @Test
    fun testBackgroundFiveSecondsIsNotAMiss() {
        val record = settle(spans = listOf(span(5.0, focused = true)), end = LaneEndReason.BACKGROUND, userInitiated = false)
        assertEquals(5.0, record.rawSeconds)
        assertEquals(5.0, record.weightedSeconds)
        assertEquals(5.0, record.focusedSeconds)
        assertEquals(LaneEndReason.BACKGROUND, record.endReason)
        assertEquals(PlaybackLaneRole.PRIMARY, record.lane)
        assertFalse(record.userInitiated)
        assertTrue(record.resumesAsNewDwell)
        val taste = record.recording(taste = Taste(), room = room("a", listOf("x")), now = now)
        assertEquals(0.0, hit(taste, "x"), 1e-9)
        assertEquals(0.0, miss(taste, "x"), 1e-9)
        assertEquals(0, dailyHits(taste))
        assertEquals(0, dailyMisses(taste))
        val ledger = record.recording(ledger = WatchLedger(), start = 0.0, now = 5.0)
        assertEquals(5.0, ledger.entries[WatchLedger.key("a")]?.totalSeconds ?: -1.0, 1e-9)

        val long = settle(spans = listOf(span(60.0, focused = true)), end = LaneEndReason.BACKGROUND)
        val learned = long.recording(taste = Taste(), room = room("a", listOf("x")), now = now)
        assertEquals(Feedback.grade(60.0).hit, hit(learned, "x"), 1e-9)
        assertEquals(0.0, miss(learned, "x"), 1e-9)
        assertEquals(1, dailyHits(learned))
        assertEquals(0, dailyMisses(learned))
        assertTrue(long.resumesAsNewDwell)
    }

    /** 非焦点 40 秒：加权 20 秒用于学习，不进每日统计；焦点段 1.0、非焦点段 0.5。 */
    @Test
    fun testUnfocusedFortySecondsStayOutOfDailyStats() {
        val record = settle(spans = listOf(span(40.0, focused = false)), end = LaneEndReason.SWIPE, userInitiated = true)
        assertEquals(40.0, record.rawSeconds)
        assertEquals(0.0, record.focusedSeconds)
        assertEquals(20.0, record.weightedSeconds)
        assertTrue(record.userInitiated)
        val taste = record.recording(taste = Taste(), room = room("a", listOf("x")), now = now)
        assertEquals(0, dailyHits(taste))
        assertEquals(0, dailyMisses(taste))
        assertEquals(Feedback.grade(20.0).hit, hit(taste, "x"), 1e-9)
        val ledger = record.recording(ledger = WatchLedger(), start = 1.0, now = 41.0)
        assertEquals(20.0, ledger.entries[WatchLedger.key("a")]?.totalSeconds ?: -1.0, 1e-9)

        val mixed = settle(
            spans = listOf(span(20.0, focused = true), span(20.0, focused = false)),
            end = LaneEndReason.SWIPE,
            userInitiated = true,
        )
        assertEquals(40.0, mixed.rawSeconds)
        assertEquals(20.0, mixed.focusedSeconds)
        assertEquals(30.0, mixed.weightedSeconds)
        val mixedTaste = mixed.recording(taste = Taste(), room = room("a", listOf("x")), now = now)
        assertEquals(Feedback.grade(30.0).hit, hit(mixedTaste, "x"), 1e-9)
        assertEquals(1, dailyHits(mixedTaste))
    }

    /** 两路各记各的来源，学习权重和每日统计都不串。 */
    @Test
    fun testLaneSourcesDoNotCross() {
        val primary = settle(
            lane = PlaybackLaneRole.PRIMARY,
            username = "ann",
            source = FeedSource.guess,
            spans = listOf(span(40.0, focused = true)),
            end = LaneEndReason.SWIPE,
            userInitiated = true,
        )
        val secondary = settle(
            lane = PlaybackLaneRole.SECONDARY,
            username = "bee",
            source = FeedSource.popular,
            spans = listOf(span(40.0, focused = true)),
            end = LaneEndReason.SWIPE,
            userInitiated = true,
        )
        assertEquals(FeedSource.guess, primary.source)
        assertEquals(FeedSource.popular, secondary.source)
        assertEquals("ann", primary.username)
        assertEquals("bee", secondary.username)
        var taste = Taste()
        taste = secondary.recording(taste = taste, room = room("bee", listOf("beta")), now = now)
        taste = primary.recording(taste = taste, room = room("ann", listOf("alpha")), now = now)
        assertEquals(Feedback.grade(40.0).hit, hit(taste, "alpha"), 1e-9)
        assertEquals(Feedback.grade(40.0).hit / 2, hit(taste, "beta"), 1e-9)
        assertEquals(1, dailyHits(taste))
        assertEquals(0, dailyMisses(taste))
        assertFalse(LaneSettlement.tracksHotRelated(isFocusLane = false))
        assertTrue(LaneSettlement.tracksHotRelated(isFocusLane = true))
    }

    /** 显式不记账：background / dualOff 的落空段不学习、不进每日统计，只留「已看过」标记。 */
    @Test
    fun testBackgroundAndDualOffDoNotLearnExplicitly() {
        for (end in listOf(LaneEndReason.BACKGROUND, LaneEndReason.DUAL_OFF)) {
            val short = settle(spans = listOf(span(2.0, focused = true)), end = end)
            assertFalse(short.learnsTaste, "$end")
            assertFalse(short.countsDaily, "$end")
            val taste = short.recording(taste = Taste(), room = room("a", listOf("x")), now = now)
            assertTrue(taste.beliefs.isEmpty(), "$end")
            assertTrue(taste.daily.isEmpty(), "$end")
            assertNotNull(taste.lastSeen("a"), "$end")
        }
        val swipe = settle(spans = listOf(span(2.0, focused = true)), end = LaneEndReason.SWIPE, userInitiated = true)
        assertTrue(swipe.learnsTaste)
        assertTrue(swipe.countsDaily)
        val long = settle(spans = listOf(span(40.0, focused = true)), end = LaneEndReason.BACKGROUND)
        assertTrue(long.learnsTaste)
        assertTrue(long.countsDaily)
    }

    /** 弱落空只在 rawSeconds < 3 且结束原因是 swipe 时记。后台和关掉双窗不产生落空。 */
    @Test
    fun testWeakMissOnlyWhenUnderThreeSecondsAndSwipe() {
        val swipe = settle(spans = listOf(span(2.0, focused = true)), end = LaneEndReason.SWIPE, userInitiated = true)
        val background = settle(spans = listOf(span(2.0, focused = true)), end = LaneEndReason.BACKGROUND)
        val autoSkip = settle(spans = listOf(span(2.0, focused = true)), end = LaneEndReason.AUTO_SKIP)
        val dualOff = settle(spans = listOf(span(2.0, focused = true)), end = LaneEndReason.DUAL_OFF)
        val blocked = settle(spans = listOf(span(2.0, focused = true)), end = LaneEndReason.BLOCK, userInitiated = true)
        val atThree = settle(spans = listOf(span(3.0, focused = true)), end = LaneEndReason.SWIPE, userInitiated = true)

        val swipeTaste = swipe.recording(taste = Taste(), room = room("a", listOf("x")), now = now)
        assertEquals(Feedback.skipMiss, miss(swipeTaste, "x"), 1e-9)
        assertEquals(1, dailyMisses(swipeTaste))
        assertEquals(2.0, swipe.learnSeconds, 1e-9)

        val backgroundTaste = background.recording(taste = Taste(), room = room("a", listOf("x")), now = now)
        assertEquals(0.0, miss(backgroundTaste, "x"), 1e-9)
        assertEquals(0, dailyMisses(backgroundTaste))
        assertTrue(background.resumesAsNewDwell)

        val autoTaste = autoSkip.recording(taste = Taste(), room = room("a", listOf("x")), now = now)
        assertEquals(0.0, miss(autoTaste, "x"), 1e-9)
        assertEquals(1, dailyMisses(autoTaste))

        val dualTaste = dualOff.recording(taste = Taste(), room = room("a", listOf("x")), now = now)
        assertEquals(0.0, miss(dualTaste, "x"), 1e-9)
        assertEquals(0, dailyMisses(dualTaste))
        assertTrue(dualOff.resumesAsNewDwell)

        val blockedTaste = blocked.recording(taste = Taste(), room = room("a", listOf("x")), now = now)
        assertEquals(0.0, miss(blockedTaste, "x"), 1e-9)
        assertEquals(1, dailyMisses(blockedTaste))

        val boundary = atThree.recording(taste = Taste(), room = room("a", listOf("x")), now = now)
        assertEquals(0.0, miss(boundary, "x"), 1e-9)
        assertEquals(0.0, hit(boundary, "x"), 1e-9)
        assertEquals(0, dailyHits(boundary))
        assertEquals(0, dailyMisses(boundary))
        assertFalse(atThree.resumesAsNewDwell)
    }
}
