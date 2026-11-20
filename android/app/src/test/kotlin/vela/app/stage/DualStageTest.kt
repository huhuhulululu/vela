package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.app.lane.ThermalMonitor
import vela.core.base.lane.LaneID
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.play.StreamRendition
import vela.core.base.site.CatalogPage
import vela.core.hls.FailureCode
import vela.core.hls.LaneQuality
import vela.core.hls.PhoneThermalState
import vela.core.hls.PlaybackFailureClassifier
import vela.core.selection.LaneSelection

/**
 * 双路舞台：两路不同房、焦点操作、热降档、副路资源降级、双路结算。
 * 不访问真实站点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DualStageTest {
    private val harness = StageHarness()
    private lateinit var settings: Settings
    private lateinit var directory: java.nio.file.Path
    private val clock = ManualClock()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context)
        directory = Files.createTempDirectory("vela-dual-stage")
    }

    @After
    fun tearDown() {
        deleteTree(directory)
        harness.close()
    }

    @Test
    fun testSetDualOnPlacesSecondaryAfterPrimaryAndRemembers() = harness.onStage {
        val stage = stage()
        try {
            install(stage, "alice", "bob", "carol", "dave")
            assertEquals(1, stage.laneCount)
            assertNull(stage.secondaryCursor)
            stage.setDual(true)
            assertEquals(2, stage.laneCount)
            assertEquals(LaneID.PRIMARY, stage.focusLane)
            assertEquals("alice", stage.feed.currentUsername)
            assertEquals("bob", stage.secondaryCursor?.username)
            assertTrue(stage.settings.dualWindow)
            assertTrue(stage.state.value.lanesDistinct())
            assertTrue(stage.secondaryPlayRequests > 0)
            val cursor = stage.secondaryCursor
            stage.setDual(true)
            assertTrue(cursor === stage.secondaryCursor)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testRememberedDualFillsSecondaryWhenTheListArrives() = harness.onStage {
        settings.dualWindow = true
        val stage = stage()
        try {
            assertEquals(2, stage.laneCount)
            assertEquals(LaneID.SECONDARY, stage.state.value.secondary?.id)
            install(stage, "alice", "bob", "carol")
            assertEquals("alice", stage.feed.currentUsername)
            assertEquals("bob", stage.secondaryCursor?.username)
            assertTrue(stage.state.value.lanesDistinct())
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSingleRoomLeavesSecondaryEmpty() = harness.onStage {
        val stage = stage()
        try {
            install(stage, "alice")
            stage.setDual(true)
            assertEquals("alice", stage.feed.currentUsername)
            assertNull(stage.secondaryCursor?.username)
            assertEquals(StageRules.NO_MORE_ROOMS, stage.state.value.emptyLaneMessage(LaneID.SECONDARY))
        } finally {
            stage.close()
        }
    }

    @Test
    fun testBlockPrimaryKeepsSecondaryDistinct() = harness.onStage {
        val stage = stage()
        try {
            install(stage, "alice", "bob", "carol", "dave")
            stage.setDual(true)
            stage.blockCurrent(stage.target(LaneID.PRIMARY))
            assertEquals("bob", stage.secondaryCursor?.username)
            assertEquals("carol", stage.feed.currentUsername)
            assertTrue(stage.state.value.lanesDistinct())
        } finally {
            stage.close()
        }
    }

    @Test
    fun testBlockSecondaryFindsTheNextRoom() = harness.onStage {
        val stage = stage()
        try {
            install(stage, "alice", "bob", "carol", "dave")
            stage.setDual(true)
            val primary = stage.feed.currentUsername
            stage.blockCurrent(stage.target(LaneID.SECONDARY))
            assertEquals(primary, stage.feed.currentUsername)
            assertEquals("carol", stage.secondaryCursor?.username)
            assertTrue(stage.state.value.lanesDistinct())
        } finally {
            stage.close()
        }
    }

    @Test
    fun testRevealOnTheOtherLaneOnlySwitchesFocus() = harness.onStage {
        val stage = stage()
        try {
            install(stage, "alice", "bob", "carol")
            stage.setDual(true)
            stage.updateFeed(intent = LaneSelection.Intent.Reveal("bob")) { store ->
                store.reveal(room("bob"))
            }
            assertEquals(LaneID.SECONDARY, stage.focusLane)
            assertEquals("alice", stage.feed.currentUsername)
            assertEquals("bob", stage.secondaryCursor?.username)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testClosingDualKeepsTheFocusedSecondaryRoom() = harness.onStage {
        val stage = stage()
        try {
            install(stage, "alice", "bob", "carol")
            stage.setDual(true)
            stage.setFocus(LaneID.SECONDARY)
            stage.setDual(false)
            assertEquals(1, stage.laneCount)
            assertEquals(LaneID.PRIMARY, stage.focusLane)
            assertNull(stage.secondaryCursor)
            assertEquals("bob", stage.feed.currentUsername)
            assertFalse(stage.settings.dualWindow)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSecondarySwipeSkipsThePrimaryRoom() = harness.onStage {
        val stage = stage()
        try {
            install(stage, "alice", "bob", "carol")
            stage.setDual(true)
            val moved = stage.moveLane(LaneID.SECONDARY, 1, userInitiated = true)
            assertTrue(moved)
            assertEquals("alice", stage.feed.currentUsername)
            assertEquals("carol", stage.secondaryCursor?.username)
            assertTrue(stage.state.value.lanesDistinct())
        } finally {
            stage.close()
        }
    }

    @Test
    fun testPauseOnSecondaryDoesNotPausePrimary() = harness.onStage {
        val stage = stage()
        try {
            install(stage, "alice", "bob", "carol")
            stage.setDual(true)
            stage.notePlaying("alice")
            stage.noteLanePlaying(LaneID.SECONDARY, "bob")
            stage.ageConfirmed = true
            stage.dispatch(StageIntent.TogglePause(stage.target(LaneID.SECONDARY)))
            assertFalse(stage.laneFace.manuallyPaused)
            assertTrue(stage.secondaryFace?.manuallyPaused == true)
            assertEquals("alice", stage.feed.currentUsername)
            assertEquals("bob", stage.secondaryCursor?.username)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testLandscapeReleasesTheNonFocusLaneButKeepsItsRoom() = harness.onStage {
        val stage = stage()
        try {
            install(stage, "alice", "bob", "carol")
            stage.setDual(true)
            stage.setLandscape(true)
            assertTrue(stage.state.value.lane.visible)
            assertFalse(stage.state.value.secondary?.visible == true)
            assertEquals("bob", stage.secondaryCursor?.username)
            stage.setLandscape(false)
            assertTrue(stage.state.value.secondary?.visible == true)
            assertEquals("bob", stage.secondaryCursor?.username)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testThermalCapFollowsLaneQualityAndRecoversAtSixtySeconds() = harness.onStage {
        val stage = stage()
        try {
            install(stage, "alice", "bob", "carol")
            stage.setDual(true)
            stage.noteLaneDisplay(LaneID.PRIMARY, height = 221.0, scale = 3.0)
            stage.noteLaneDisplay(LaneID.SECONDARY, height = 221.0, scale = 3.0)
            assertEquals(Double.POSITIVE_INFINITY, stage.laneCap(LaneID.PRIMARY), 0.0)
            assertEquals(Double.POSITIVE_INFINITY, stage.laneCap(LaneID.SECONDARY), 0.0)
            assertNull(stage.retargetCap(LaneID.PRIMARY))
            val hot = LaneQuality()
            hot.observe(PhoneThermalState.SERIOUS, clock.instant)
            val expected = hot.peakBitrate(221.0, 3.0)
            stage.noteThermal(PhoneThermalState.SERIOUS, clock.instant, scheduleRecovery = false)
            assertEquals(expected, stage.laneCap(LaneID.PRIMARY), 0.0)
            assertEquals(expected, stage.laneCap(LaneID.SECONDARY), 0.0)
            assertTrue(expected >= LaneQuality.minimumBitrate)
            assertEquals(expected, stage.retargetCap(LaneID.PRIMARY))
            stage.noteAppliedCap(LaneID.PRIMARY, expected)
            assertNull(stage.retargetCap(LaneID.PRIMARY))
            clock.instant = clock.instant.plusSeconds(59)
            stage.noteThermal(PhoneThermalState.FAIR, clock.instant, scheduleRecovery = false)
            assertEquals(expected, stage.laneCap(LaneID.PRIMARY), 0.0)
            // 60 秒从进入 fair 起算，必须连续，不能把发热的那段算进去。
            clock.instant = clock.instant.plusSeconds(60)
            stage.noteThermal(PhoneThermalState.NOMINAL, clock.instant, scheduleRecovery = false)
            assertEquals(Double.POSITIVE_INFINITY, stage.laneCap(LaneID.PRIMARY), 0.0)
            assertEquals(Double.POSITIVE_INFINITY, stage.laneCap(LaneID.SECONDARY), 0.0)
            assertEquals(Double.POSITIVE_INFINITY, stage.retargetCap(LaneID.PRIMARY))
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSecondaryDecoderResourceDegradesWithoutTouchingPrimary() = harness.onStage {
        val stage = stage()
        try {
            install(stage, "alice", "bob", "carol")
            stage.setDual(true)
            val chain = listOf(
                FailureCode(
                    PlaybackFailureClassifier.media3ErrorDomain,
                    PlaybackFailureClassifier.decoderInitFailedCode,
                ),
            )
            assertEquals(
                PlaybackFailureClassifier.Outcome.RESOURCE_DEGRADE,
                stage.outcomeFor(LaneID.SECONDARY, chain),
            )
            assertEquals(
                PlaybackFailureClassifier.Outcome.ROOM_RETRY,
                stage.outcomeFor(LaneID.PRIMARY, chain),
            )
            val corrupt = listOf(
                FailureCode(
                    PlaybackFailureClassifier.media3ErrorDomain,
                    PlaybackFailureClassifier.decodingFailedCode,
                ),
            )
            assertEquals(
                PlaybackFailureClassifier.Outcome.ROOM_RETRY,
                stage.outcomeFor(LaneID.SECONDARY, corrupt),
            )
            stage.degradeLane(LaneID.SECONDARY)
            assertEquals(LanePlayback.Degraded, stage.state.value.secondary?.playback)
            assertEquals(StageRules.DUAL_UNAVAILABLE, stage.state.value.secondary?.playbackNote)
            assertEquals("bob", stage.secondaryCursor?.username)
            assertEquals("alice", stage.feed.currentUsername)
            assertEquals(LanePlayback.Idle, stage.state.value.lane.playback)
            stage.degradeLane(LaneID.PRIMARY)
            assertEquals(LanePlayback.Idle, stage.state.value.lane.playback)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testUnfocusedLaneSettlesAtHalfWeight() = harness.onStage {
        val stage = stage()
        try {
            install(stage, "alice", "bob", "carol")
            stage.setDual(true)
            stage.notePlaying("alice")
            stage.noteLanePlaying(LaneID.SECONDARY, "bob")
            clock.instant = clock.instant.plusSeconds(10)
            val moved = stage.moveLane(LaneID.SECONDARY, 1, userInitiated = true)
            assertTrue(moved)
            val entry = stage.watchLedger.entries["bob"]
            assertEquals(5.0, entry?.totalSeconds ?: Double.NaN, 0.001)
            assertNull(stage.watchLedger.entries["alice"])
        } finally {
            stage.close()
        }
    }

    @Test
    fun testEmptyListThenResumeReselectsBothLanes() = harness.onStage {
        val stage = stage()
        try {
            install(stage, "alice", "bob", "carol")
            stage.setDual(true)
            stage.updateFeed(intent = LaneSelection.Intent.Reload) { store ->
                store.replace(pageOf())
            }
            assertNull(stage.feed.currentUsername)
            assertNull(stage.secondaryCursor?.username)
            stage.updateFeed(intent = LaneSelection.Intent.Merge) { store ->
                store.mergeAfterCurrent(listOf(room("p1"), room("p2"), room("p3"))) { page -> page }
            }
            assertEquals("p1", stage.feed.currentUsername)
            assertEquals("p2", stage.secondaryCursor?.username)
            assertNotEquals(
                stage.feed.currentUsername?.lowercase(),
                stage.secondaryCursor?.username?.lowercase(),
            )
        } finally {
            stage.close()
        }
    }

    @Test
    fun testThermalMonitorMapsSystemStatus() {
        assertEquals(PhoneThermalState.NOMINAL, ThermalMonitor.map(ThermalMonitor.THERMAL_NONE))
        assertEquals(PhoneThermalState.FAIR, ThermalMonitor.map(ThermalMonitor.THERMAL_LIGHT))
        assertEquals(PhoneThermalState.SERIOUS, ThermalMonitor.map(ThermalMonitor.THERMAL_MODERATE))
        assertEquals(PhoneThermalState.CRITICAL, ThermalMonitor.map(ThermalMonitor.THERMAL_SEVERE))
        assertEquals(PhoneThermalState.CRITICAL, ThermalMonitor.map(ThermalMonitor.THERMAL_CRITICAL))
        assertEquals(PhoneThermalState.CRITICAL, ThermalMonitor.map(ThermalMonitor.THERMAL_EMERGENCY))
        assertEquals(PhoneThermalState.CRITICAL, ThermalMonitor.map(99))
    }

    @Test
    fun testOnlyTheFocusLaneIsAudible() = harness.onStage {
        val stage = stage()
        try {
            install(stage, "alice", "bob")
            stage.setDual(true)
            val volumes = ArrayList<Pair<String, Boolean>>()
            stage.attachPlayback(recordingSink("primary", volumes))
            stage.secondarySink = recordingSink("secondary", volumes)
            stage.muted = false
            stage.pushVolumes()
            assertEquals(listOf("primary" to false, "secondary" to true), volumes.toList())
            volumes.clear()
            stage.setFocus(LaneID.SECONDARY)
            assertEquals(LaneID.SECONDARY, stage.focusLane)
            val muted = volumes.map { it.first to it.second }
            assertTrue(muted.contains("primary" to true))
            assertTrue(muted.contains("secondary" to false))
        } finally {
            stage.close()
        }
    }

    private fun stage(): StageViewModel = StageViewModel(
        settings = settings,
        http = FakeCatalogHttp(emptyMap()),
        directory = directory,
        clock = clock,
        dispatcher = harness.dispatcher,
    )

    private fun install(stage: StageViewModel, vararg names: String) {
        stage.updateFeed(installing = FeedSource.guess, intent = LaneSelection.Intent.Reload) { store ->
            store.replace(pageOf(*names))
            store.setQuery(stage.currentQuery())
        }
    }

    private fun pageOf(vararg names: String): CatalogPage {
        val rooms = names.map { room(it) }
        return CatalogPage(rooms = rooms, total = rooms.size, nextOffset = rooms.size)
    }

    private fun room(name: String): Room = Room(username = name, viewers = 2, show = ShowState.publicShow)

    private fun recordingSink(label: String, volumes: MutableList<Pair<String, Boolean>>): PlaybackSink =
        object : PlaybackSink {
            override val lastError: String? = null
            override val failed: Boolean = false
            override fun play(
                rendition: StreamRendition,
                username: String,
                peakBitrate: Double,
                allowed: Boolean,
                isRetry: Boolean,
            ) = Unit
            override fun stop() = Unit
            override fun setVolume(volume: Double, muted: Boolean) {
                volumes.add(label to muted)
            }
            override fun setAllowed(allowed: Boolean) = Unit
            override fun refreshFinished(reason: String?, report: Boolean) = Unit
            override fun setRefreshHandler(handler: ((String, Boolean) -> Unit)?) = Unit
            override fun setRenewHandler(handler: (suspend (String, Double) -> StreamRendition?)?) = Unit
        }
}
