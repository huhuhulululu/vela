package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.core.base.lane.LaneID
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.play.StreamRendition
import vela.core.base.site.CatalogPage
import vela.core.curation.Curation
import vela.core.hls.LaneQuality
import vela.core.hls.PhoneThermalState
import vela.core.selection.LaneSelection

/**
 * 双窗选房、目标路操作、横屏可见性、画质上限与持久化往返 parity 测试
 * （对应 DualWindowTests 与 DualQualityTests）。不访问真实站点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DualWindowParityTest {
    private val harness = StageHarness()
    private lateinit var context: Context
    private lateinit var settings: Settings
    private lateinit var directory: java.nio.file.Path
    private val clock = ManualClock(Instant.ofEpochSecond(1_800_000_000))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context)
        directory = Files.createTempDirectory("vela-dual-window-parity")
    }

    @After
    fun tearDown() {
        deleteTree(directory)
        harness.close()
    }

    // MARK: DualWindowTests (20)

    @Test
    fun testSetDualOffReleasesSecondary() = harness.onStage {
        val stage = dual()
        try {
            var released = 0
            stage.releaseSecondaryLane = { released += 1 }
            stage.setDual(false)
            assertNull(stage.secondaryCursor)
            assertNull(stage.secondaryFace)
            assertNull(stage.secondaryPlayJob)
            assertFalse(stage.state.value.dualWindow)
            assertEquals(1, stage.laneCount)
            assertFalse(stage.settings.dualWindow)
            assertEquals(LaneID.PRIMARY, stage.focusLane)
            assertEquals(1, released)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testUnfavoriteSecondaryRoomInFavoritesSource() = harness.onStage {
        val stage = makeStage()
        try {
            stage.source = FeedSource.favorites
            stage.commitCuration(Curation().favoriting("alice").favoriting("bob").favoriting("carol"), null)
            install(stage, listOf("alice", "bob", "carol"), source = FeedSource.favorites)
            stage.setDual(true)
            assertEquals("bob", stage.secondaryCursor?.username)
            stage.commitCuration(stage.curation.unfavoriting("bob"), null)
            assertEquals("alice", stage.feed.currentUsername)
            assertEquals("carol", stage.secondaryCursor?.username)
            assertDistinct(stage)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testUnfavoritePrimaryRoomInFavoritesSource() = harness.onStage {
        val stage = makeStage()
        try {
            stage.source = FeedSource.favorites
            stage.commitCuration(Curation().favoriting("alice").favoriting("bob").favoriting("carol"), null)
            install(stage, listOf("alice", "bob", "carol"), source = FeedSource.favorites)
            stage.setDual(true)
            stage.commitCuration(stage.curation.unfavoriting("alice"), null)
            assertDistinct(stage)
            assertEquals("bob", stage.secondaryCursor?.username)
            assertEquals("carol", stage.feed.currentUsername)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testLocalSearchNarrowingStaysDistinct() = harness.onStage {
        val stage = dual(listOf("alice", "alex", "bob", "alina"))
        try {
            assertEquals("alex", stage.secondaryCursor?.username)
            stage.searchText = "al"
            stage.applySearch()
            assertDistinct(stage)
            assertNotNull(stage.feed.currentUsername)
            assertNotNull(stage.secondaryCursor?.username)

            stage.searchText = "bob"
            stage.applySearch()
            assertEquals("bob", stage.feed.currentUsername)
            assertNull("只剩一间时焦点路保留它，另一路空着", stage.secondaryCursor?.username)

            stage.searchText = ""
            stage.applySearch()
            assertDistinct(stage)
            assertNotNull("清空搜索放宽回多间时应补回副路", stage.secondaryCursor?.username)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testPrimaryForwardSkipsRoomsTheSecondaryJustLeft() = harness.onStage {
        val stage = dual(listOf("alice", "bob", "carol", "dave", "erin"))
        try {
            stage.moveLane(LaneID.SECONDARY, 1, userInitiated = true)
            stage.moveLane(LaneID.SECONDARY, 1, userInitiated = true)
            assertEquals("dave", stage.secondaryCursor?.username)
            stage.move(1, userInitiated = true)
            assertEquals("erin", stage.feed.currentUsername)
            assertDistinct(stage)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSecondaryForwardSkipsRoomsThePrimaryJustLeft() = harness.onStage {
        val stage = dual(listOf("alice", "bob", "carol", "dave", "erin"))
        try {
            stage.move(1, userInitiated = true)
            stage.move(1, userInitiated = true)
            assertEquals("dave", stage.feed.currentUsername)
            stage.moveLane(LaneID.SECONDARY, 1, userInitiated = true)
            assertEquals("erin", stage.secondaryCursor?.username)
            assertDistinct(stage)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testBackSwipeToOwnPreviousRoomIsAllowed() = harness.onStage {
        val stage = dual(listOf("alice", "bob", "carol", "dave", "erin"))
        try {
            stage.move(1, userInitiated = true)
            assertEquals("carol", stage.feed.currentUsername)
            stage.move(-1, userInitiated = true)
            assertEquals("alice", stage.feed.currentUsername)
            assertDistinct(stage)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testRecentSkipExpiresAndDirectRevealIgnoresIt() = harness.onStage {
        val stage = dual(listOf("alice", "bob", "carol", "dave", "erin"))
        try {
            stage.recentSkips.record("carol", clock.instant.minusSeconds(31 * 60))
            stage.recentSkips.record("dave", clock.instant)
            stage.move(1, userInitiated = true)
            assertEquals("carol 已过 30 分钟应恢复可选", "carol", stage.feed.currentUsername)
            stage.updateFeed(intent = LaneSelection.Intent.Reveal("dave")) { store ->
                store.select("dave")
            }
            assertEquals("直达不受最近划走限制", "dave", stage.feed.currentUsername)
            assertDistinct(stage)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSingleWindowSwipesAreNotRecorded() = harness.onStage {
        val stage = makeStage(listOf("alice", "bob", "carol", "dave", "erin"))
        try {
            stage.move(1, userInitiated = true)
            stage.move(1, userInitiated = true)
            assertEquals(0, stage.recentSkips.count)
            assertEquals("carol", stage.feed.currentUsername)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testRecentSkipsSurviveListReload() = harness.onStage {
        val five = listOf("alice", "bob", "carol", "dave", "erin")
        val stage = dual(five)
        try {
            stage.moveLane(LaneID.SECONDARY, 1, userInitiated = true)
            install(stage, five)
            assertEquals("重载列表不应清空最近划走记录", 1, stage.recentSkips.count)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSiteSearchInstallAndExitStayDistinct() = harness.onStage {
        val stage = dual()
        try {
            stage.updateFeed(intent = LaneSelection.Intent.SearchInstall) { store ->
                store.replace(pageOf("zed", "yan", "xi"))
                store.setQuery(stage.currentQuery())
            }
            assertEquals("zed", stage.feed.currentUsername)
            assertEquals("yan", stage.secondaryCursor?.username)
            assertDistinct(stage)
            stage.updateFeed(intent = LaneSelection.Intent.SearchExit) { store ->
                store.replace(pageOf("alice", "bob"))
                store.setQuery(stage.currentQuery())
            }
            assertDistinct(stage)
            assertNotNull(stage.secondaryCursor?.username)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSwitchingSourceStaysDistinct() = harness.onStage {
        val stage = dual()
        try {
            install(stage, listOf("m1", "m2", "m3"), source = FeedSource.popular)
            assertEquals("m1", stage.feed.currentUsername)
            assertEquals("m2", stage.secondaryCursor?.username)
            assertDistinct(stage)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testRevealOtherRoomMovesPrimaryAndFocusesIt() = harness.onStage {
        val stage = dual()
        try {
            stage.setFocus(LaneID.SECONDARY)
            stage.updateFeed(intent = LaneSelection.Intent.Reveal("dave")) { store ->
                store.reveal(room("dave"))
            }
            assertEquals(LaneID.PRIMARY, stage.focusLane)
            assertEquals("dave", stage.feed.currentUsername)
            assertDistinct(stage)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testFavoriteOnSecondaryTargetsSecondaryRoomOnly() = harness.onStage {
        val stage = dual()
        try {
            stage.setFocus(LaneID.SECONDARY)
            stage.toggleFavorite(stage.target(LaneID.SECONDARY))
            assertTrue(stage.curation.isFavorite("bob"))
            assertFalse(stage.curation.isFavorite("alice"))
            assertEquals("alice", stage.feed.currentUsername)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testBlockOnSecondaryLeavesPrimaryUntouched() = harness.onStage {
        val stage = dual()
        try {
            val primaryGenBefore = stage.laneFace.generation
            stage.setFocus(LaneID.SECONDARY)
            stage.blockCurrent(stage.target(LaneID.SECONDARY))
            assertTrue(stage.curation.isBlocked("bob"))
            assertFalse(stage.curation.isBlocked("alice"))
            assertEquals("alice", stage.feed.currentUsername)
            assertEquals("主路不应被重新起播", primaryGenBefore, stage.laneFace.generation)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testNotInterestedOnSecondaryMovesOnlySecondary() = harness.onStage {
        val stage = dual()
        try {
            stage.setFocus(LaneID.SECONDARY)
            stage.markNotInterested(stage.target(LaneID.SECONDARY))
            assertEquals("alice", stage.feed.currentUsername)
            assertEquals("carol", stage.secondaryCursor?.username)
            assertFalse(stage.secondaryFace?.manuallyPaused == true)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testRetryOnSecondaryRestartsOnlySecondary() = harness.onStage {
        val stage = dual()
        try {
            stage.ageConfirmed = true
            stage.setFocus(LaneID.SECONDARY)
            val primaryGenBefore = stage.laneFace.generation
            val secondaryReqBefore = stage.secondaryPlayRequests
            stage.retryPlayback(stage.target(LaneID.SECONDARY))
            assertEquals("主路不应重播", primaryGenBefore, stage.laneFace.generation)
            assertEquals(secondaryReqBefore + 1, stage.secondaryPlayRequests)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testStaleTargetIsIgnoredAfterRoomChange() = harness.onStage {
        val stage = dual()
        try {
            val staleTarget = stage.target(LaneID.SECONDARY)
            stage.moveLane(LaneID.SECONDARY, 1, userInitiated = true)
            stage.toggleFavorite(staleTarget)
            assertFalse("换房后的过期目标必须放弃", stage.curation.isFavorite("bob"))
            assertFalse(stage.curation.isFavorite("carol"))
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSetFocusUpdatesFocusAndDwellFlags() = harness.onStage {
        val stage = dual()
        try {
            assertEquals(LaneID.PRIMARY, stage.focusLane)
            assertEquals("alice", stage.state.value.focusRoom?.username)
            stage.setFocus(LaneID.SECONDARY)
            assertEquals(LaneID.SECONDARY, stage.focusLane)
            assertEquals("bob", stage.state.value.focusRoom?.username)
            stage.setFocus(LaneID.PRIMARY)
            assertEquals(LaneID.PRIMARY, stage.focusLane)
            assertEquals("alice", stage.state.value.focusRoom?.username)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSetFocusInSingleWindowIsIgnored() = harness.onStage {
        val stage = makeStage()
        try {
            stage.setFocus(LaneID.SECONDARY)
            assertEquals(LaneID.PRIMARY, stage.focusLane)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSwipingNonFocusPaneTakesFocusViaMove() = harness.onStage {
        val stage = dual()
        try {
            stage.ageConfirmed = true
            stage.setFocus(LaneID.SECONDARY)
            stage.dispatch(StageIntent.SetFocus(LaneID.PRIMARY))
            stage.dispatch(StageIntent.Step(1, LaneID.PRIMARY))
            assertEquals(LaneID.PRIMARY, stage.focusLane)
            assertEquals("carol", stage.feed.currentUsername)
            assertDistinct(stage)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testIndexOfLaneFollowsItsRoom() = harness.onStage {
        val stage = dual()
        try {
            assertEquals(0, stage.state.value.indexOfLane(LaneID.PRIMARY))
            assertEquals(1, stage.state.value.indexOfLane(LaneID.SECONDARY))
        } finally {
            stage.close()
        }
    }

    @Test
    fun testLandscapeReleasesPrimaryWhenSecondaryHasFocus() = harness.onStage {
        val stage = dual()
        try {
            stage.setFocus(LaneID.SECONDARY)
            stage.setLandscape(true)
            assertFalse(stage.state.value.lane.visible)
            assertTrue(stage.state.value.secondary?.visible == true)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testBackToPortraitRequestsPlayForReleasedLane() = harness.onStage {
        val stage = dual()
        try {
            stage.setLandscape(true)
            val before = stage.secondaryPlayRequests
            stage.setLandscape(false)
            assertFalse(stage.landscape)
            assertTrue(stage.state.value.secondary?.visible == true)
            assertEquals("回竖屏应重新请求被隐藏的副路起播", before + 1, stage.secondaryPlayRequests)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testRoomChangeWhileHiddenDoesNotStartPlayback() = harness.onStage {
        val stage = dual()
        try {
            stage.setLandscape(true)
            val before = stage.secondaryPlayRequests
            stage.updateFeed { store -> store.setFilters(blocked = setOf("bob"), only = null) }
            assertEquals("carol", stage.secondaryCursor?.username)
            assertEquals("横屏隐藏态换房不应起播", before, stage.secondaryPlayRequests)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSetLandscapeIsIdempotentAndIgnoredInSingleWindow() = harness.onStage {
        val stage = makeStage()
        try {
            stage.setLandscape(true)
            stage.setLandscape(true)
            assertTrue(stage.state.value.lane.visible)
            assertNull(stage.secondaryCursor)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testClosingDualInLandscapeRestoresVisibility() = harness.onStage {
        val stage = dual()
        try {
            stage.setFocus(LaneID.SECONDARY)
            stage.setLandscape(true)
            assertFalse(stage.state.value.lane.visible)
            stage.setDual(false)
            assertTrue(stage.state.value.lane.visible)
            assertEquals("bob", stage.feed.currentUsername)
        } finally {
            stage.close()
        }
    }

    // MARK: DualQualityTests (5)

    @Test
    fun testCriticalLimitsEvenWithoutPicture() = harness.onStage {
        val stage = dual()
        try {
            stage.noteLaneDisplay(LaneID.SECONDARY, height = 0.0, scale = 3.0)
            stage.noteThermal(PhoneThermalState.CRITICAL, clock.instant, scheduleRecovery = false)
            assertTrue(stage.laneQuality.isLimited)
            assertEquals(LaneQuality.minimumBitrate, stage.laneCap(LaneID.SECONDARY), 0.0)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testReheatRestartsTheSixtySeconds() = harness.onStage {
        val stage = dual()
        try {
            stage.noteLaneDisplay(LaneID.PRIMARY, height = 221.0, scale = 3.0)
            stage.noteThermal(PhoneThermalState.SERIOUS, clock.instant, scheduleRecovery = true)
            val limitedCap = stage.laneCap(LaneID.PRIMARY)
            assertTrue(limitedCap.isFinite())

            clock.instant = clock.instant.plusSeconds(10)
            stage.noteThermal(PhoneThermalState.FAIR, clock.instant, scheduleRecovery = true)
            assertNotNull("进入 fair 后应排一次性回升检查", stage.thermalJob)

            clock.instant = clock.instant.plusSeconds(40)
            stage.noteThermal(PhoneThermalState.SERIOUS, clock.instant, scheduleRecovery = true)
            assertNull("再次发热应取消回升检查", stage.thermalJob)

            clock.instant = clock.instant.plusSeconds(10)
            stage.noteThermal(PhoneThermalState.FAIR, clock.instant, scheduleRecovery = false)
            clock.instant = clock.instant.plusSeconds(59)
            stage.noteThermal(PhoneThermalState.FAIR, clock.instant, scheduleRecovery = false)
            assertEquals(limitedCap, stage.laneCap(LaneID.PRIMARY), 0.0)

            clock.instant = clock.instant.plusSeconds(1)
            stage.noteThermal(PhoneThermalState.FAIR, clock.instant, scheduleRecovery = false)
            assertEquals(Double.POSITIVE_INFINITY, stage.laneCap(LaneID.PRIMARY), 0.0)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testRetargetCapTellsLanesToChangeTierOnBothTransitions() = harness.onStage {
        val stage = dual()
        try {
            stage.noteLaneDisplay(LaneID.PRIMARY, height = 221.0, scale = 3.0)
            assertNull(stage.retargetCap(LaneID.PRIMARY))
            stage.noteThermal(PhoneThermalState.SERIOUS, clock.instant, scheduleRecovery = false)
            val cap = stage.retargetCap(LaneID.PRIMARY)
            assertNotNull(cap)
            assertTrue(cap!!.isFinite())
            stage.noteAppliedCap(LaneID.PRIMARY, cap)
            assertNull(stage.retargetCap(LaneID.PRIMARY))

            clock.instant = clock.instant.plusSeconds(1)
            stage.noteThermal(PhoneThermalState.NOMINAL, clock.instant, scheduleRecovery = false)
            clock.instant = clock.instant.plusSeconds(61)
            stage.noteThermal(PhoneThermalState.NOMINAL, clock.instant, scheduleRecovery = false)
            assertEquals(Double.POSITIVE_INFINITY, stage.retargetCap(LaneID.PRIMARY))
        } finally {
            stage.close()
        }
    }

    @Test
    fun testStartPlaybackCarriesThermalCap() = harness.onStage {
        val stage = dual()
        try {
            stage.noteLaneDisplay(LaneID.PRIMARY, height = 221.0, scale = 3.0)
            val peaks = ArrayList<Double>()
            stage.attachPlayback(peakRecordingSink(peaks))
            stage.noteThermal(PhoneThermalState.SERIOUS, clock.instant, scheduleRecovery = false)
            val expectedCap = stage.laneCap(LaneID.PRIMARY)
            assertTrue(expectedCap.isFinite())
            val rendition = StreamRendition(video = java.net.URI("https://example.invalid/live.m3u8"))
            stage.playbackSink!!.play(rendition, "alice", Double.POSITIVE_INFINITY, allowed = true, isRetry = false)
            assertEquals(listOf(expectedCap), peaks)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testRetierSkipsLanesWithoutPlaybackItem() = harness.onStage {
        val stage = dual()
        try {
            assertNull(stage.primaryLane)
            assertNull(stage.secondaryLane)
            stage.noteLaneDisplay(LaneID.PRIMARY, height = 221.0, scale = 3.0)
            stage.noteLaneDisplay(LaneID.SECONDARY, height = 221.0, scale = 3.0)
            stage.noteThermal(PhoneThermalState.SERIOUS, clock.instant, scheduleRecovery = false)
            assertNull("没有播放项时不应写入 appliedCap，留待下次起播带新上限", stage.appliedCap[LaneID.PRIMARY])
            assertNull(stage.appliedCap[LaneID.SECONDARY])
            assertNotNull(stage.retargetCap(LaneID.PRIMARY))
            assertNotNull(stage.retargetCap(LaneID.SECONDARY))
        } finally {
            stage.close()
        }
    }

    // MARK: 双窗状态持久化保存与恢复往返

    @Test
    fun testDualWindowSettingSaveAndRestoreRoundTrip() = harness.onStage {
        val first = makeStage()
        try {
            first.setDual(true, remember = true)
            assertTrue(first.settings.dualWindow)
            assertEquals(2, first.rememberedLaneCount)
            // 临时降路不覆盖已保存的双窗设置。
            first.setDual(false, remember = false)
            assertTrue(first.settings.dualWindow)
        } finally {
            first.close()
        }

        val restored = StageViewModel(
            settings = Settings(context),
            http = FakeCatalogHttp(emptyMap()),
            directory = directory,
            clock = clock,
            dispatcher = harness.dispatcher,
        )
        try {
            assertTrue(restored.state.value.dualWindow)
            assertEquals(2, restored.state.value.laneCount)
            assertEquals(2, restored.state.value.rememberedLaneCount)
            restored.setDual(false, remember = true)
            assertFalse(restored.settings.dualWindow)
        } finally {
            restored.close()
        }

        val single = StageViewModel(
            settings = Settings(context),
            http = FakeCatalogHttp(emptyMap()),
            directory = directory,
            clock = clock,
            dispatcher = harness.dispatcher,
        )
        try {
            assertFalse(single.state.value.dualWindow)
            assertEquals(1, single.state.value.laneCount)
            assertEquals(1, single.state.value.rememberedLaneCount)
        } finally {
            single.close()
        }
    }

    @Test
    fun testLanesDistinctFollowsRooms() = harness.onStage {
        val stage = dual(listOf("alice", "bob", "carol"))
        try {
            assertTrue("主副不同房时应为 distinct", stage.state.value.lanesDistinct())

            val emptySecondary = stage.state.value.copy(
                secondary = stage.state.value.secondary?.copy(username = null),
            )
            assertTrue("副路为空时应保持 distinct", emptySecondary.lanesDistinct())

            val duplicate = stage.state.value.copy(
                secondary = stage.state.value.secondary?.copy(username = "ALICE"),
            )
            assertFalse("两路同房时 lanesDistinct 应返回 false", duplicate.lanesDistinct())
        } finally {
            stage.close()
        }
    }

    private fun makeStage(names: List<String> = listOf("alice", "bob", "carol", "dave")): StageViewModel {
        val stage = StageViewModel(
            settings = settings,
            http = FakeCatalogHttp(emptyMap()),
            directory = directory,
            clock = clock,
            dispatcher = harness.dispatcher,
        )
        install(stage, names)
        return stage
    }

    private fun dual(names: List<String> = listOf("alice", "bob", "carol", "dave")): StageViewModel {
        val stage = makeStage(names)
        stage.setDual(true)
        return stage
    }

    private fun install(stage: StageViewModel, names: List<String>, source: FeedSource? = FeedSource.guess) {
        stage.updateFeed(installing = source, intent = LaneSelection.Intent.Reload) { store ->
            store.replace(pageOf(*names.toTypedArray()))
            store.setQuery(stage.currentQuery())
        }
    }

    private fun pageOf(vararg names: String): CatalogPage {
        val rooms = names.map { room(it) }
        return CatalogPage(rooms = rooms, total = rooms.size, nextOffset = rooms.size)
    }

    private fun room(name: String): Room = Room(username = name, viewers = 4, show = ShowState.publicShow)

    private fun assertDistinct(stage: StageViewModel) {
        val primary = stage.feed.currentUsername
        val secondary = stage.secondaryCursor?.username
        if (primary != null && secondary != null) {
            assertNotEquals(primary.lowercase(), secondary.lowercase())
        }
    }

    private fun peakRecordingSink(peaks: MutableList<Double>): PlaybackSink = object : PlaybackSink {
        override val lastError: String? = null
        override val failed: Boolean = false
        override fun play(
            rendition: StreamRendition,
            username: String,
            peakBitrate: Double,
            allowed: Boolean,
            isRetry: Boolean,
        ) {
            peaks.add(peakBitrate)
        }
        override fun stop() = Unit
        override fun setVolume(volume: Double, muted: Boolean) = Unit
        override fun setAllowed(allowed: Boolean) = Unit
        override fun refreshFinished(reason: String?, report: Boolean) = Unit
        override fun setRefreshHandler(handler: ((String, Boolean) -> Unit)?) = Unit
        override fun setRenewHandler(handler: (suspend (String, Double) -> StreamRendition?)?) = Unit
    }
}
