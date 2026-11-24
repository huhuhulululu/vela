package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import kotlinx.coroutines.launch
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

    @Test
    fun testSetVolumeWhileSecondaryFocusedKeepsPrimaryMutedAndUpdatesSecondary() = harness.onStage {
        val stage = stage()
        try {
            stage.ageConfirmed = true
            install(stage, "alice", "bob")
            stage.setDual(true)
            val events = ArrayList<Triple<String, Double, Boolean>>()
            stage.attachPlayback(recordingVolumeSink("primary", events))
            stage.secondarySink = recordingVolumeSink("secondary", events)
            stage.bindLifecycleHooks(ensurePlayback = {}, onMuteChanged = { next -> stage.applyMute(next, true) })
            stage.applyMute(nextMuted = false, writeSettings = true)
            stage.setFocus(LaneID.SECONDARY)
            events.clear()

            // 双窗未静音且焦点在副路，调一个非零音量不会触发 onMuteChanged；不得把主路解除静音，同时副路要拿到新音量。
            stage.dispatch(StageIntent.SetVolume(0.35))
            val primaryLast = events.lastOrNull { it.first == "primary" }
            val secondaryLast = events.lastOrNull { it.first == "secondary" }
            assertTrue("主路不得被解除静音: $events", primaryLast == null || primaryLast.third)
            assertTrue("主路音量应保持 0: $events", primaryLast == null || primaryLast.second == 0.0)
            assertTrue("焦点副路应收到新音量 0.35 且未静音: $events", secondaryLast != null && !secondaryLast.third && secondaryLast.second == 0.35)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testLatePrimaryStartPlaybackWhileSecondaryFocusedKeepsPrimaryMuted() = harness.onStage {
        val http = streamHttp()
        val stage = stage(http)
        try {
            stage.ageConfirmed = true
            stage.prefetchEnabled = false
            install(stage, "alice", "bob")
            stage.setDual(true)
            val events = ArrayList<Triple<String, Double, Boolean>>()
            var primaryPlayed = 0
            stage.bindPlayback(
                recordingVolumeSink("primary", events, onPlay = { primaryPlayed += 1 }),
            )
            stage.secondarySink = recordingVolumeSink("secondary", events)
            stage.applyMute(nextMuted = false, writeSettings = true)
            stage.setFocus(LaneID.SECONDARY)
            events.clear()

            // 焦点在副路时主路异步起播完成，startPlayback 不得给非焦点主路解除静音。
            stage.requestPlay()
            stage.awaitUntil { primaryPlayed > 0 }
            val primaryEvents = events.filter { it.first == "primary" }
            assertTrue("主路起播后应有音量同步记录", primaryEvents.isNotEmpty())
            assertTrue("非焦点主路起播完成仍必须保持静音: $primaryEvents", primaryEvents.all { it.third })
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSecondaryRenewalUsesSecondaryRoomAndGeneration() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Main.immediate) {
        val requested = ArrayList<String>()
        val http = streamHttp(onRequest = { requested.add(it.url) })
        val stage = StageViewModel(
            settings = settings,
            http = http,
            directory = directory,
            clock = clock,
            dispatcher = kotlinx.coroutines.Dispatchers.Main.immediate,
        )
        val secondaryLane = vela.app.lane.PlaybackLane(
            id = LaneID.SECONDARY,
            hub = vela.app.playback.LaneHub(),
            player = vela.app.lane.FakeLanePlayer(),
        )
        try {
            stage.ageConfirmed = true
            install(stage, "alice", "bob", "claire")
            stage.setDual(true)
            stage.bindLaneRouted(secondaryLane)
            assertEquals("alice", stage.state.value.currentUsername)
            assertEquals("bob", stage.secondaryCursor?.username)

            // 副路调用 renewRendition 应使用副路房间与 generation，成功续签 bob 而不是拿主路 alice 判过期。
            val renewed = secondaryLane.renewRendition?.invoke("bob", Double.POSITIVE_INFINITY)
            assertTrue("副路续签应成功返回新 StreamRendition", renewed != null && !renewed.isUnselected)
            assertTrue("应请求副路 bob 的流地址: $requested", requested.any { it.contains("bob") })

            // 副路切房到 claire 后，对旧房 bob 的续签应被判过期返回 null。
            requested.clear()
            assertTrue(stage.moveLane(LaneID.SECONDARY, 1, userInitiated = true))
            assertEquals("claire", stage.secondaryCursor?.username)
            val stale = secondaryLane.renewRendition?.invoke("bob", Double.POSITIVE_INFINITY)
            assertNull("副路切房后旧房续签应返回 null", stale)
            assertTrue("过期续签不应再发网络请求: $requested", requested.isEmpty())
        } finally {
            secondaryLane.release()
            stage.close()
        }
    }

    @Test
    fun testSecondaryBecomingEmptyStopsSinkAndCancelsInflightPlay() = harness.onStage {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val probeStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val http = streamHttp(
            beforeSend = { request ->
                if (request.url.contains("bob")) {
                    probeStarted.complete(Unit)
                    gate.await()
                }
            },
        )
        val stage = stage(http)
        try {
            stage.ageConfirmed = true
            install(stage, "alice", "bob")
            stage.setDual(true)
            var stops = 0
            val played = ArrayList<String>()
            stage.secondarySink = object : PlaybackSink {
                override val lastError: String? = null
                override val failed: Boolean = false
                override fun play(
                    rendition: StreamRendition,
                    username: String,
                    peakBitrate: Double,
                    allowed: Boolean,
                    isRetry: Boolean,
                ) {
                    played.add(username)
                }
                override fun stop() {
                    stops += 1
                }
                override fun setVolume(volume: Double, muted: Boolean) = Unit
                override fun setAllowed(allowed: Boolean) = Unit
                override fun refreshFinished(reason: String?, report: Boolean) = Unit
                override fun setRefreshHandler(handler: ((String, Boolean) -> Unit)?) = Unit
                override fun setRenewHandler(handler: (suspend (String, Double) -> StreamRendition?)?) = Unit
            }

            // 启动副路解析（停在网络 gate 上），然后搜索缩窄到只剩主路 alice，副路变空。
            stage.requestLane(LaneID.SECONDARY)
            kotlinx.coroutines.withTimeout(5_000) { probeStarted.await() }
            val jobBefore = stage.secondaryPlayJob
            assertTrue("应有在途副路解析任务", jobBefore != null && jobBefore.isActive)

            stage.dispatch(StageIntent.SetSearchText("alice"))
            assertNull("搜索只剩一间时副路应为空", stage.secondaryCursor?.username)
            assertEquals(LanePlayback.Empty, stage.secondaryFace?.playback)
            assertTrue("副路变空时必须取消旧解析任务", jobBefore!!.isCancelled)
            assertTrue("副路变空时必须停止旧播放源", stops >= 1)

            gate.complete(Unit)
            stage.awaitLoads()
            assertTrue("变空后不得再对旧房起播: $played", played.isEmpty())
        } finally {
            gate.complete(Unit)
            stage.close()
        }
    }

    @Test
    fun testHidingSecondaryCancelsInflightPlay() = harness.onStage {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val probeStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val http = streamHttp(
            beforeSend = { request ->
                if (request.url.contains("bob")) {
                    probeStarted.complete(Unit)
                    gate.await()
                }
            },
        )
        val stage = stage(http)
        try {
            stage.ageConfirmed = true
            install(stage, "alice", "bob")
            stage.setDual(true)
            var stops = 0
            val played = ArrayList<String>()
            stage.secondarySink = object : PlaybackSink {
                override val lastError: String? = null
                override val failed: Boolean = false
                override fun play(
                    rendition: StreamRendition,
                    username: String,
                    peakBitrate: Double,
                    allowed: Boolean,
                    isRetry: Boolean,
                ) {
                    played.add(username)
                }
                override fun stop() {
                    stops += 1
                }
                override fun setVolume(volume: Double, muted: Boolean) = Unit
                override fun setAllowed(allowed: Boolean) = Unit
                override fun refreshFinished(reason: String?, report: Boolean) = Unit
                override fun setRefreshHandler(handler: ((String, Boolean) -> Unit)?) = Unit
                override fun setRenewHandler(handler: (suspend (String, Double) -> StreamRendition?)?) = Unit
            }

            stage.requestLane(LaneID.SECONDARY)
            kotlinx.coroutines.withTimeout(5_000) { probeStarted.await() }
            val jobBefore = stage.secondaryPlayJob
            assertTrue(jobBefore != null && jobBefore.isActive)

            // 横屏隐藏非焦点副路，应取消在途解析并停播，放行网络后不得再 play。
            stage.setLandscape(true)
            assertTrue("隐藏副路时必须取消在途解析任务", jobBefore!!.isCancelled)
            assertTrue(stops >= 1)

            gate.complete(Unit)
            stage.awaitLoads()
            assertTrue("副路隐藏后不得再起播: $played", played.isEmpty())
        } finally {
            gate.complete(Unit)
            stage.close()
        }
    }

    @Test
    fun testHidingPrimaryCancelsPrefetchAwayAndSkipAndDisallowsLane() = harness.onStage {
        val http = streamHttp()
        val stage = stage(http)
        try {
            stage.ageConfirmed = true
            install(stage, "alice", "bob", "claire")
            stage.setDual(true)
            stage.setFocus(LaneID.SECONDARY)

            // 模拟主路上挂着预取、离开重试与失败自动跳过任务。
            stage.awayDelayOverrideMillis = 60_000L
            stage.skipDelayMillis = 60_000L
            val token = stage.laneFace.generation
            stage.scheduleAwayRetry("alice", token, afterSeconds = 60.0)
            stage.scheduleSkip(token)
            val fakePrefetch = stage.scope.launch { kotlinx.coroutines.delay(60_000L) }
            stage.prefetchJob = fakePrefetch
            val awayBefore = stage.awayJob
            val skipBefore = stage.skipJob
            assertTrue(awayBefore != null && awayBefore.isActive)
            assertTrue(skipBefore != null && skipBefore.isActive)
            assertTrue(fakePrefetch.isActive)

            // 进入画中画仅焦点路（焦点在副路）：隐藏主路必须取消主路的预取、离开重试与跳过任务。
            stage.setPipSolo(true)
            assertFalse("主路应被隐藏", stage.laneFace.visible)
            assertTrue("隐藏主路必须取消 prefetchJob", fakePrefetch.isCancelled)
            assertTrue("隐藏主路必须取消 awayJob", awayBefore!!.isCancelled)
            assertTrue("隐藏主路必须取消 skipJob", skipBefore!!.isCancelled)

            // 画中画期间即使主路触发 startPlayback 也不得启动 prefetchJob。
            stage.setPipSolo(false)
            stage.setFocus(LaneID.PRIMARY)
            stage.pictureInPicture = true
            stage.prefetchEnabled = true
            var played = 0
            stage.bindPlayback(recordingVolumeSink("primary", ArrayList(), onPlay = { played += 1 }))
            stage.requestPlay()
            stage.awaitUntil { played > 0 }
            assertNull("画中画期间主路起播不得启动预取任务", stage.prefetchJob)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSecondaryResolutionExceptionStopsPreviousPlayback() = harness.onStage {
        val http = object : vela.core.base.net.HTTPClient {
            override suspend fun send(request: vela.core.base.net.HTTPRequest): vela.core.base.net.HTTPResult {
                if (request.url.contains("claire")) {
                    throw java.io.IOException("network-boom")
                }
                val url = request.url
                return when {
                    url.contains("chatvideocontext") -> vela.core.base.net.HTTPResult(
                        status = 200,
                        data = """{"room_status":"public","hls_source":"https://cdn.example/live/master.m3u8"}""".toByteArray(),
                    )
                    url.contains("master.m3u8") -> vela.core.base.net.HTTPResult(
                        status = 200,
                        data = (
                            "#EXTM3U\n" +
                                "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\n" +
                                "chunklist_720.m3u8\n"
                            ).toByteArray(),
                    )
                    else -> vela.core.base.net.HTTPResult(status = 200, data = """{"rooms":[],"total_count":0}""".toByteArray())
                }
            }
        }
        val stage = stage(http)
        try {
            stage.ageConfirmed = true
            install(stage, "alice", "bob", "claire")
            stage.setDual(true)
            var stops = 0
            val played = ArrayList<String>()
            stage.secondarySink = object : PlaybackSink {
                override val lastError: String? = null
                override val failed: Boolean = false
                override fun play(
                    rendition: StreamRendition,
                    username: String,
                    peakBitrate: Double,
                    allowed: Boolean,
                    isRetry: Boolean,
                ) {
                    played.add(username)
                }
                override fun stop() {
                    stops += 1
                }
                override fun setVolume(volume: Double, muted: Boolean) = Unit
                override fun setAllowed(allowed: Boolean) = Unit
                override fun refreshFinished(reason: String?, report: Boolean) = Unit
                override fun setRefreshHandler(handler: ((String, Boolean) -> Unit)?) = Unit
                override fun setRenewHandler(handler: (suspend (String, Double) -> StreamRendition?)?) = Unit
            }

            // 副路先成功起播 bob。
            stage.requestLane(LaneID.SECONDARY)
            stage.awaitUntil { played.contains("bob") }
            stops = 0

            // 切换到 claire，解析抛出 IOException；必须停止旧房 bob 的播放源再标失败。
            assertTrue(stage.moveLane(LaneID.SECONDARY, 1, userInitiated = true))
            stage.awaitUntil { stage.secondaryFace?.playback == LanePlayback.Failed }
            assertEquals("claire", stage.secondaryCursor?.username)
            assertTrue("副路切房解析抛异常后必须停止旧播放源，实际 stops=$stops", stops >= 1)
        } finally {
            stage.close()
        }
    }

    private fun stage(http: vela.core.base.net.HTTPClient = FakeCatalogHttp(emptyMap())): StageViewModel = StageViewModel(
        settings = settings,
        http = http,
        directory = directory,
        clock = clock,
        dispatcher = harness.dispatcher,
    )

    private fun streamHttp(
        onRequest: (vela.core.base.net.HTTPRequest) -> Unit = {},
        beforeSend: suspend (vela.core.base.net.HTTPRequest) -> Unit = {},
    ): vela.core.base.net.HTTPClient = object : vela.core.base.net.HTTPClient {
        override suspend fun send(request: vela.core.base.net.HTTPRequest): vela.core.base.net.HTTPResult {
            onRequest(request)
            beforeSend(request)
            val url = request.url
            return when {
                url.contains("chatvideocontext") -> vela.core.base.net.HTTPResult(
                    status = 200,
                    data = """{"room_status":"public","hls_source":"https://cdn.example/live/master.m3u8"}""".toByteArray(),
                )
                url.contains("master.m3u8") -> vela.core.base.net.HTTPResult(
                    status = 200,
                    data = (
                        "#EXTM3U\n" +
                            "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\n" +
                            "chunklist_720.m3u8\n"
                        ).toByteArray(),
                )
                else -> vela.core.base.net.HTTPResult(status = 200, data = """{"rooms":[],"total_count":0}""".toByteArray())
            }
        }
    }

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

    private fun recordingVolumeSink(
        label: String,
        events: MutableList<Triple<String, Double, Boolean>>,
        onPlay: () -> Unit = {},
    ): PlaybackSink = object : PlaybackSink {
        override val lastError: String? = null
        override val failed: Boolean = false
        override fun play(
            rendition: StreamRendition,
            username: String,
            peakBitrate: Double,
            allowed: Boolean,
            isRetry: Boolean,
        ) {
            onPlay()
        }
        override fun stop() = Unit
        override fun setVolume(volume: Double, muted: Boolean) {
            events.add(Triple(label, volume, muted))
        }
        override fun setAllowed(allowed: Boolean) = Unit
        override fun refreshFinished(reason: String?, report: Boolean) = Unit
        override fun setRefreshHandler(handler: ((String, Boolean) -> Unit)?) = Unit
        override fun setRenewHandler(handler: (suspend (String, Double) -> StreamRendition?)?) = Unit
    }
}
