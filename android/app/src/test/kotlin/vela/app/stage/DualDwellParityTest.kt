package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.core.base.lane.LaneEndReason
import vela.core.base.lane.LaneID
import vela.core.base.lane.PlaybackLaneRole
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage
import vela.core.selection.LaneSelection
import vela.core.selection.LaneSettlement
import vela.core.taste.Feedback
import vela.core.taste.Taste

/**
 * 双窗停留与焦点秒 parity 测试（对应 DwellFocusTests 与 DualDwellTests）。
 * 不访问真实站点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DualDwellParityTest {
    private val harness = StageHarness()
    private lateinit var settings: Settings
    private lateinit var directory: java.nio.file.Path
    private val clock = ManualClock(Instant.ofEpochSecond(1_800_000_000))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context)
        directory = Files.createTempDirectory("vela-dual-dwell-parity")
    }

    @After
    fun tearDown() {
        deleteTree(directory)
        harness.close()
    }

    private fun at(seconds: Long): Instant = clock.instant.plusSeconds(seconds)

    private fun settleSpans(spans: List<LaneSettlement.Span>): LaneSettlement =
        LaneSettlement.settle(
            lane = PlaybackLaneRole.PRIMARY,
            username = "a",
            source = FeedSource.guess,
            spans = spans,
            endReason = LaneEndReason.SWIPE,
            userInitiated = true,
        )

    // MARK: DwellFocusTests (7)

    @Test
    fun testFocusedByDefaultSoFocusedSecondsEqualDwell() {
        val log = FocusSegments()
        log.align(at(0), active = true, focused = true)
        val settled = settleSpans(log.take(at(30)))
        assertEquals(30.0, settled.rawSeconds, 0.001)
        assertEquals(30.0, settled.focusedSeconds, 0.001)
    }

    @Test
    fun testUnfocusedFromTheStartHasNoFocusedSeconds() {
        val log = FocusSegments()
        log.align(at(0), active = true, focused = false)
        val settled = settleSpans(log.take(at(10)))
        assertEquals(10.0, settled.rawSeconds, 0.001)
        assertEquals(0.0, settled.focusedSeconds, 0.001)
    }

    @Test
    fun testFocusTogglingDuringPlaybackSplitsSeconds() {
        val log = FocusSegments()
        log.align(at(0), active = true, focused = true)
        log.align(at(10), active = true, focused = false)
        log.align(at(16), active = true, focused = true)
        val settled = settleSpans(log.take(at(20)))
        assertEquals(20.0, settled.rawSeconds, 0.001)
        assertEquals(14.0, settled.focusedSeconds, 0.001)
    }

    @Test
    fun testFocusChangeWhileInactiveOnlyChangesFlag() {
        val log = FocusSegments()
        log.align(at(5), active = false, focused = false)
        log.align(at(8), active = true, focused = false)
        log.align(at(12), active = true, focused = true)
        log.align(at(20), active = false, focused = true)
        val settled = settleSpans(log.take(at(30)))
        assertEquals(12.0, settled.rawSeconds, 0.001)
        assertEquals(8.0, settled.focusedSeconds, 0.001)
    }

    @Test
    fun testPausedSecondsAreNotCountedAsFocused() {
        val log = FocusSegments()
        log.align(at(0), active = true, focused = true)
        log.align(at(5), active = false, focused = true)
        log.align(at(60), active = true, focused = true)
        val settled = settleSpans(log.take(at(65)))
        assertEquals(10.0, settled.rawSeconds, 0.001)
        assertEquals(10.0, settled.focusedSeconds, 0.001)
    }

    @Test
    fun testSwitchRoomResetsSecondsButKeepsFocusFlag() {
        val log = FocusSegments()
        log.align(at(0), active = true, focused = false)
        val first = settleSpans(log.take(at(10)))
        assertEquals(10.0, first.rawSeconds, 0.001)
        assertEquals(0.0, first.focusedSeconds, 0.001)
        // 切房后继续播放仍沿用非焦点，直到 20 秒切到焦点。
        log.align(at(10), active = true, focused = false)
        log.align(at(20), active = true, focused = true)
        val second = settleSpans(log.take(at(25)))
        assertEquals(15.0, second.rawSeconds, 0.001)
        assertEquals(5.0, second.focusedSeconds, 0.001)
    }

    @Test
    fun testRepeatedSameFocusIsNoOp() {
        val log = FocusSegments()
        log.align(at(0), active = true, focused = true)
        log.align(at(5), active = true, focused = true)
        log.align(at(9), active = true, focused = true)
        val settled = settleSpans(log.take(at(10)))
        assertEquals(10.0, settled.rawSeconds, 0.001)
        assertEquals(10.0, settled.focusedSeconds, 0.001)
    }

    // MARK: DualDwellTests (12)

    @Test
    fun testEachLaneKeepsItsOwnSource() = harness.onStage {
        val stage = makeStage(dual = true)
        try {
            stage.dwellSource = FeedSource.guess
            stage.secondarySource = FeedSource.popular
            stage.notePlaying("alice")
            stage.noteLanePlaying(LaneID.SECONDARY, "bob")
            clock.instant = clock.instant.plusSeconds(40)
            // 主路猜你喜欢、副路热门：各自按来源结算，不串来源。
            stage.move(1, userInitiated = true)
            val guessAlpha = stage.taste.posterior(Taste.tagKey("t_alice"), clock.instant).alpha - 1.0
            assertEquals(Feedback.grade(40.0).hit, guessAlpha, 0.001)
            assertEquals(1, dailyHits(stage))

            stage.setFocus(LaneID.SECONDARY)
            stage.moveLane(LaneID.SECONDARY, 1, userInitiated = true)
            // 热门来源权重折半（且不进猜你喜欢每日命中率）。
            val popularAlpha = stage.taste.posterior(Taste.tagKey("t_bob"), clock.instant).alpha - 1.0
            // 副路 40s 非焦点 -> 加权 20s；popular 来源再折半。
            assertEquals(Feedback.grade(20.0).hit / 2.0, popularAlpha, 0.001)
            assertEquals(1, dailyHits(stage))
        } finally {
            stage.close()
        }
    }

    @Test
    fun testBackgroundAndDualOffShortStaysAreNotMisses() = harness.onStage {
        for (reason in listOf(LaneEndReason.BACKGROUND, LaneEndReason.DUAL_OFF, LaneEndReason.SOURCE_CHANGE)) {
            val stage = makeStage(dual = true)
            try {
                stage.notePlaying("alice")
                clock.instant = clock.instant.plusSeconds(1)
                val settled = stage.dwell.finish(clock.instant)!!
                stage.settlementRole = PlaybackLaneRole.PRIMARY
                stage.settlementFocused = true
                stage.settlementSourceOverride = null
                stage.recordSettlement(settled, reason, userInitiated = false)
                assertTrue("短停留 $reason 不应产生落空", stage.taste.beliefs.isEmpty())
            } finally {
                stage.close()
            }
        }
        val swipeStage = makeStage(dual = true)
        try {
            swipeStage.notePlaying("alice")
            clock.instant = clock.instant.plusSeconds(1)
            val settled = swipeStage.dwell.finish(clock.instant)!!
            swipeStage.settlementRole = PlaybackLaneRole.PRIMARY
            swipeStage.settlementFocused = true
            swipeStage.settlementSourceOverride = null
            swipeStage.recordSettlement(settled, LaneEndReason.SWIPE, userInitiated = true)
            assertTrue("划走 1 秒应记弱落空", swipeStage.taste.beliefs.isNotEmpty())
        } finally {
            swipeStage.close()
        }
    }

    @Test
    fun testDailyHitRateUsesFocusedSecondsOnly() = harness.onStage {
        val stage = makeStage(dual = true)
        try {
            stage.notePlaying("alice")
            stage.noteLanePlaying(LaneID.SECONDARY, "bob")
            clock.instant = clock.instant.plusSeconds(60)
            // 副路一直没焦点，60 秒不进每日命中率。
            stage.moveLane(LaneID.SECONDARY, 1, userInitiated = true)
            assertEquals(0, dailyHits(stage))
            assertEquals(0, dailyMisses(stage))
            // 主路先焦点 40 秒，再非焦点 20 秒 -> 焦点 40 秒计一次每日命中。
            val stage2 = makeStage(dual = true)
            try {
                stage2.notePlaying("alice")
                clock.instant = clock.instant.plusSeconds(40)
                stage2.setFocus(LaneID.SECONDARY)
                clock.instant = clock.instant.plusSeconds(20)
                stage2.move(1, userInitiated = true)
                assertEquals(1, dailyHits(stage2))
            } finally {
                stage2.close()
            }
        } finally {
            stage.close()
        }
    }

    @Test
    fun testLedgerRecordsWeightedSeconds() = harness.onStage {
        val stage = makeStage(dual = true)
        try {
            stage.setFocus(LaneID.SECONDARY)
            stage.noteLanePlaying(LaneID.SECONDARY, "bob")
            clock.instant = clock.instant.plusSeconds(10)
            stage.setFocus(LaneID.PRIMARY)
            clock.instant = clock.instant.plusSeconds(10)
            stage.moveLane(LaneID.SECONDARY, 1, userInitiated = true)
            assertEquals(15.0, stage.watchLedger.entries["bob"]?.totalSeconds ?: Double.NaN, 0.001)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSingleWindowLedgerEqualsRawSeconds() = harness.onStage {
        val stage = makeStage(dual = false)
        try {
            stage.notePlaying("alice")
            clock.instant = clock.instant.plusSeconds(20)
            stage.move(1, userInitiated = true)
            assertEquals(20.0, stage.watchLedger.entries["alice"]?.totalSeconds ?: Double.NaN, 0.001)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testFocusedLaneHitCountsDailyAndNeverFocusedDoesNot() = harness.onStage {
        val stage = makeStage(dual = true)
        try {
            stage.notePlaying("alice")
            stage.noteLanePlaying(LaneID.SECONDARY, "bob")
            clock.instant = clock.instant.plusSeconds(30)
            stage.move(1, userInitiated = true)
            assertEquals(1, dailyHits(stage))
            assertEquals(0, dailyMisses(stage))
            stage.moveLane(LaneID.SECONDARY, 1, userInitiated = true)
            assertEquals(1, dailyHits(stage))
            assertEquals(0, dailyMisses(stage))
        } finally {
            stage.close()
        }
    }

    @Test
    fun testQuickSwipeOnFocusLaneStillCountsDailyMiss() = harness.onStage {
        val stage = makeStage(dual = true)
        try {
            // 焦点路还没出画面（dwell == 0）就划走：仍记一次每日落空。
            assertEquals(0, dailyMisses(stage))
            val moved = stage.move(1, userInitiated = true)
            assertTrue(moved)
            assertEquals(1, dailyMisses(stage))
        } finally {
            stage.close()
        }
    }

    @Test
    fun testBackgroundShortStayRecordsNoMiss() = harness.onStage {
        val stage = makeStage(dual = true)
        try {
            stage.notePlaying("alice")
            clock.instant = clock.instant.plusSeconds(1)
            val settled = stage.dwell.finish(clock.instant)!!
            stage.settlementRole = PlaybackLaneRole.PRIMARY
            stage.settlementFocused = true
            stage.settlementSourceOverride = null
            stage.recordSettlement(settled, LaneEndReason.BACKGROUND, userInitiated = false)
            assertEquals(0, dailyMisses(stage))
        } finally {
            stage.close()
        }
    }

    @Test
    fun testDwellFocusFlagsFollowFocusLane() = harness.onStage {
        val stage = makeStage(dual = true)
        try {
            stage.setFocus(LaneID.SECONDARY)
            stage.notePlaying("alice")
            stage.noteLanePlaying(LaneID.SECONDARY, "bob")
            clock.instant = clock.instant.plusSeconds(10)
            val primarySettled = settleSpans(stage.primaryFocusLog.take(clock.instant))
            val secondarySettled = settleSpans(stage.secondaryFocusLog.take(clock.instant))
            assertEquals(0.0, primarySettled.focusedSeconds, 0.001)
            assertEquals(10.0, secondarySettled.focusedSeconds, 0.001)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testRelatedPromotionOnlyOnFocusLane() = harness.onStage {
        val stage = makeStage(dual = true)
        try {
            assertFalse(LaneSettlement.tracksHotRelated(isFocusLane = false))
            assertTrue(LaneSettlement.tracksHotRelated(isFocusLane = true))
            stage.notePlaying("alice")
            assertNotNull("主路有焦点且在播时应排热门关联", stage.relatedJob)
            stage.setFocus(LaneID.SECONDARY)
            stage.scheduleRelated()
            assertNull("焦点切走后主路不应排热门关联", stage.relatedJob)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSettleDwellOnBackgroundPassesReasonAndSkipsMiss() = harness.onStage {
        val stage = makeStage(dual = true)
        try {
            stage.notePlaying("alice")
            clock.instant = clock.instant.plusSeconds(1)
            stage.settleAllLanes(LaneEndReason.BACKGROUND)
            assertEquals(0, dailyMisses(stage))
        } finally {
            stage.close()
        }
    }

    @Test
    fun testFavoriteSuggestFollowsFocusLane() = harness.onStage {
        val stage = makeStage(dual = true)
        try {
            assertEquals("alice", stage.state.value.focusRoom?.username)
            stage.setFocus(LaneID.SECONDARY)
            assertEquals("bob", stage.state.value.focusRoom?.username)
            assertFalse(stage.state.value.currentIsFavorite)
            stage.commitCuration(stage.curation.favoriting("bob"), null)
            assertTrue("收藏状态应跟焦点路房间", stage.state.value.currentIsFavorite)
            // 副路有焦点时弹出常看提示并接受：应收藏副路房间而不是主路。
            stage.commitCuration(stage.curation.unfavoriting("bob"), null)
            assertFalse(stage.curation.isFavorite("bob"))
            stage.presentFavoriteSuggest(room("bob"))
            stage.acceptFavoriteSuggest()
            assertTrue("副路焦点下接受常看提示应收藏副路房间", stage.curation.isFavorite("bob"))
            assertFalse(stage.curation.isFavorite("alice"))
        } finally {
            stage.close()
        }
    }

    private fun makeStage(dual: Boolean): StageViewModel {
        val stage = StageViewModel(
            settings = settings,
            http = FakeCatalogHttp(emptyMap()),
            directory = directory,
            clock = clock,
            dispatcher = harness.dispatcher,
        )
        stage.updateFeed(installing = FeedSource.guess, intent = LaneSelection.Intent.Reload) { store ->
            store.replace(
                CatalogPage(
                    rooms = listOf("alice", "bob", "carol", "dave").map { room(it) },
                    total = 4,
                    nextOffset = 4,
                ),
            )
            store.setQuery(stage.currentQuery())
        }
        if (dual) stage.setDual(true)
        return stage
    }

    private fun room(name: String): Room = Room(
        username = name,
        viewers = 10,
        gender = Gender.female,
        tags = listOf("t_$name"),
        show = ShowState.publicShow,
    )

    private fun dailyHits(stage: StageViewModel): Int =
        stage.taste.daily.values.sumOf { it.hit }

    private fun dailyMisses(stage: StageViewModel): Int =
        stage.taste.daily.values.sumOf { it.miss }
}
