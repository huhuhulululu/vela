package vela.app.stage

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
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
import org.robolectric.shadows.ShadowLooper
import vela.app.infra.Settings
import vela.app.infra.ThumbnailCache
import vela.app.pip.PiPCoordinator
import vela.app.pip.PiPEvent
import vela.app.pip.PictureInPictureControlling
import vela.core.base.lane.LaneID
import vela.core.base.lane.PlaybackLaneRole
import vela.core.base.lane.RoomTarget
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.play.StreamRendition
import vela.core.base.site.CatalogPage
import vela.core.lifecycle.LifecycleEpoch
import vela.core.lifecycle.PhoneScenePhase
import vela.core.selection.LaneSelection

/**
 * A-PARITY-7 回归测试：
 * 1) 副路焦点画中画期间同步屏蔽隐藏主路不得重建主路或联网；
 * 2) 主路释放同步清 playbackSink/playbackBound，副路焦点静音不触碰已释放主路；
 * 3) 缩略图下载在共享任务挂起/画中画时取消在途任务并阻断新请求；
 * 4) 副路重试只清副路缓存，保留主路缓存与跳过计数；
 * 5) 主路预取跳过且保护副路正在观看的房间；
 * 6) 安卓舞台路数上限为 2；
 * 7) 双窗收藏与本地搜索命中副路时聚焦副路而不重启主路；
 * 8) 手动进入画中画按钮校验焦点路正在播放。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParitySevenRegressionTest {
    private lateinit var directory: java.nio.file.Path
    private val opened = ArrayList<StageViewModel>()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("vela-parity-7")
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun testSecondaryFocusPipSyncBlockOnHiddenPrimaryDoesNotRecreateOrFetchPrimary() {
        val requests = CopyOnWriteArrayList<String>()
        val ensurePrimaryCalls = AtomicInteger(0)
        val stage = newStage(
            http = object : HTTPClient {
                override suspend fun send(request: HTTPRequest): HTTPResult {
                    requests.add(request.url)
                    return HTTPResult(status = 200, data = """{"room_status":"public","hls_source":"https://cdn.example/live.m3u8"}""".toByteArray())
                }
            },
        )
        installRooms(stage, listOf("alice", "bob", "carol"))
        stage.dispatch(StageIntent.SetDual(true))
        stage.dispatch(StageIntent.SetFocus(LaneID.SECONDARY))
        stage.notePlaying("alice")
        stage.noteLanePlaying(LaneID.SECONDARY, "bob")

        val primarySink = RecordingSink()
        val secondarySink = RecordingSink()
        stage.bindPlayback(primarySink, null)
        stage.secondarySink = secondarySink
        stage.bindLifecycleHooks(
            ensurePlayback = { ensurePrimaryCalls.incrementAndGet() },
            onMuteChanged = {},
        )

        val session = object : LifecycleSession {
            override fun closeGate() = Unit
            override fun onEpoch(epoch: LifecycleEpoch) = Unit
            override fun suspendLanes() {
                stage.primaryLane = null
                stage.playbackSink = null
                stage.playbackBound = false
                stage.secondaryLane = null
                stage.secondarySink = null
            }
            override fun deactivateAudio() = Unit
            override fun prepareAudio() = Unit
            override fun stopListener() = Unit
            override fun ensureLane() { ensurePrimaryCalls.incrementAndGet() }
            override fun restoreUi() = Unit
            override fun releaseLane(role: PlaybackLaneRole) {
                if (role == PlaybackLaneRole.PRIMARY) {
                    stage.primaryLane = null
                    stage.playbackSink = null
                    stage.playbackBound = false
                }
            }
        }
        val wiring = LifecycleWiring(stage, session)
        val coordinator = PiPCoordinator(stage = stage, wiring = wiring, audio = { null })
        coordinator.pip = CountingPip()
        wiring.attachPictureInPicture(coordinator)

        wiring.moveTo(PhoneScenePhase.ACTIVE)
        wiring.moveTo(PhoneScenePhase.INACTIVE)
        wiring.onPictureInPictureEvent(PiPEvent.WillStart)
        wiring.onPictureInPictureEvent(PiPEvent.DidStart)
        ShadowLooper.idleMainLooper()

        assertEquals(PhoneScenePhase.BACKGROUND, wiring.phase)
        assertEquals(PlaybackLaneRole.SECONDARY, stage.lifecycleEpoch.allowedLane)
        assertFalse(stage.laneFace.visible)
        requests.clear()
        ensurePrimaryCalls.set(0)

        // 模拟后台同步投影合入对隐藏主路房间 alice 的屏蔽。
        stage.commitCuration(stage.curation.blocking("alice"), null)
        ShadowLooper.idleMainLooper()
        runBlocking { stage.awaitLoads() }
        ShadowLooper.idleMainLooper()

        assertEquals("副路焦点画中画期间不得重新创建隐藏主路播放器", 0, ensurePrimaryCalls.get())
        assertTrue("副路焦点画中画期间不得为隐藏主路或目录发起联网请求: $requests", requests.isEmpty())
        assertFalse("隐藏主路不得重新绑定", stage.playbackBound)
        assertNull("隐藏主路 playbackSink 应保持清空", stage.playbackSink)
    }

    @Test
    fun testReleasePrimaryClearsPlaybackSinkAndBoundAndMuteInPipSkipsReleasedPrimary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("vela-p7-release", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = Settings(prefs)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = true
        settings.source = FeedSource.popular
        val stage = StageViewModel(
            settings = settings,
            http = object : HTTPClient {
                override suspend fun send(request: HTTPRequest): HTTPResult = HTTPResult(200, ByteArray(0))
            },
            directory = directory.resolve("release-sink"),
            context = context,
        )
        opened.add(stage)
        val primarySink = RecordingSink()
        val secondarySink = RecordingSink()
        stage.bindPlayback(primarySink, null)
        stage.secondarySink = secondarySink
        installRooms(stage, listOf("alice", "bob"))
        stage.dispatch(StageIntent.SetDual(true))
        stage.dispatch(StageIntent.SetFocus(LaneID.SECONDARY))

        val activity = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        val session = AppPlaybackSession(
            activity = activity,
            stage = stage,
            disposeComposition = {},
            restoreComposition = {},
        )
        session.ensureLane()
        assertNotNull(stage.playbackSink)
        assertTrue(stage.playbackBound)

        session.releaseLane(PlaybackLaneRole.PRIMARY)
        assertNull("释放主路后 primaryLane 必须置空", stage.primaryLane)
        assertNull("释放主路后 playbackSink 必须置空", stage.playbackSink)
        assertFalse("释放主路后 playbackBound 必须置假", stage.playbackBound)

        // 释放后通过静音与音量调节推送音量，只应落到副路，不得抛异常。
        stage.secondarySink = secondarySink
        secondarySink.volumeCalls = 0
        stage.applyMute(nextMuted = false, writeSettings = false)
        assertTrue("副路焦点取消静音应更新副路音量", secondarySink.volumeCalls > 0)
        assertFalse(secondarySink.lastMuted)

        session.ensureLane()
        assertNotNull(stage.playbackSink)
        assertTrue(stage.playbackBound)
        session.suspendLanes()
        assertNull("suspendLanes 后 playbackSink 必须置空", stage.playbackSink)
        assertFalse("suspendLanes 后 playbackBound 必须置假", stage.playbackBound)
    }

    @Test
    fun testSecondaryRetryClearsSecondaryPreparedCacheAndPreservesPrimary() {
        val stage = newStage()
        installRooms(stage, listOf("alice", "bob", "carol"))
        stage.dispatch(StageIntent.SetDual(true))
        val renditionA = StreamRendition.unselected(URI("https://cdn.example/alice.m3u8"))
        val renditionB = StreamRendition.unselected(URI("https://cdn.example/bob.m3u8"))
        val now = stage.now()
        stage.prepared.set(renditionA, Double.POSITIVE_INFINITY, "alice", now)
        stage.prepared.set(renditionB, Double.POSITIVE_INFINITY, "bob", now)
        stage.skips.noteManual(-1)
        assertEquals(-1, stage.skips.direction)

        val secondaryTarget = RoomTarget(LaneID.SECONDARY, "bob", stage.secondaryFace!!.generation)
        stage.dispatch(StageIntent.RetryPlayback(secondaryTarget))

        assertNull("副路重试必须清除副路的 prepared 缓存", stage.prepared.fresh("bob", Double.POSITIVE_INFINITY, now))
        assertNotNull("副路重试不得误清主路的 prepared 缓存", stage.prepared.fresh("alice", Double.POSITIVE_INFINITY, now))
        assertEquals("副路重试不得重置主路的跳过方向", -1, stage.skips.direction)
    }

    @Test
    fun testPrimaryPrefetchSkipsAndProtectsSecondaryRoom() = runBlocking {
        val requested = CopyOnWriteArrayList<String>()
        val stage = newStage(
            http = object : HTTPClient {
                override suspend fun send(request: HTTPRequest): HTTPResult {
                    requested.add(request.url)
                    return when {
                        request.url.contains("bob") -> HTTPResult(
                            status = 200,
                            data = """{"room_status":"private","hls_source":""}""".toByteArray(),
                        )
                        request.url.contains("carol") -> HTTPResult(
                            status = 200,
                            data = """{"room_status":"public","hls_source":"https://cdn.example/carol/master.m3u8"}""".toByteArray(),
                        )
                        else -> HTTPResult(
                            status = 200,
                            data = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nchunk.m3u8\n".toByteArray(),
                        )
                    }
                }
            },
        )
        installRooms(stage, listOf("alice", "bob", "carol"))
        stage.dispatch(StageIntent.SetDual(true))
        assertEquals("alice", stage.feed.currentUsername)
        assertEquals("bob", stage.secondaryCursor?.username)

        stage.prefetchAhead(stage.laneFace.generation)

        assertTrue("主路预取必须跳过副路正在看的 bob: $requested", requested.none { it.contains("bob") })
        assertTrue("副路房间 bob 必须保留在列表中", stage.feed.rooms.any { it.username == "bob" })
        assertEquals("副路游标不得被预取筛除", "bob", stage.secondaryCursor?.username)
    }

    @Test
    fun testStageViewModelClampsLaneCountToTwoOnTallPortrait() {
        val stage = newStage()
        installRooms(stage, listOf("alice", "bob", "carol"))
        stage.dispatch(StageIntent.NotePortraitStageSize(402.0, 874.0))
        stage.dispatch(StageIntent.SetLaneCount(3))

        assertEquals("安卓当前最多开启 2 路", 2, stage.state.value.laneCount)
        assertNull("安卓当前不得开启未实现的第三路", stage.state.value.tertiary)
    }

    @Test
    fun testDualPlayFavoriteAndSubmitSearchFocusSecondaryWithoutRestartingPrimary() {
        val stage = newStage()
        installRooms(stage, listOf("alice", "bob", "carol"))
        val primarySink = RecordingSink()
        stage.bindPlayback(primarySink, null)
        stage.dispatch(StageIntent.SetDual(true))
        assertEquals("alice", stage.feed.currentUsername)
        assertEquals("bob", stage.secondaryCursor?.username)
        assertEquals(LaneID.PRIMARY, stage.focusLane)

        val playsBeforeFav = primarySink.playCalls
        val genBeforeFav = stage.laneFace.generation
        stage.dispatch(StageIntent.PlayFavorite("bob"))
        assertEquals("收藏点击副路房间应切焦点到副路", LaneID.SECONDARY, stage.focusLane)
        assertEquals("主路房间不得改变", "alice", stage.feed.currentUsername)
        assertEquals("收藏点击副路房间不得重启主路", playsBeforeFav, primarySink.playCalls)
        assertEquals("收藏点击副路房间不得推进主路播放代次", genBeforeFav, stage.laneFace.generation)

        // 切回主路焦点，再在搜索框输入 bob 回车：应聚焦副路 bob，不挪走也不重启主路。
        stage.dispatch(StageIntent.SetFocus(LaneID.PRIMARY))
        assertEquals(LaneID.PRIMARY, stage.focusLane)
        val genBeforeSearch = stage.laneFace.generation
        stage.dispatch(StageIntent.SetSearchText("bob"))
        stage.dispatch(StageIntent.SubmitSearch)
        assertEquals("搜索命中副路房间应切焦点到副路", LaneID.SECONDARY, stage.focusLane)
        assertEquals("搜索命中副路房间不得改变主路房间", "alice", stage.feed.currentUsername)
        assertEquals("搜索命中副路房间不得推进主路播放代次", genBeforeSearch, stage.laneFace.generation)
    }

    @Test
    fun testManualPipEntryRejectsWhenFocusNotPlaying() {
        val stage = newStage()
        installRooms(stage, listOf("alice", "bob"))
        val session = object : LifecycleSession {
            override fun closeGate() = Unit
            override fun onEpoch(epoch: LifecycleEpoch) = Unit
            override fun suspendLanes() = Unit
            override fun deactivateAudio() = Unit
            override fun prepareAudio() = Unit
            override fun stopListener() = Unit
            override fun ensureLane() = Unit
            override fun restoreUi() = Unit
        }
        val wiring = LifecycleWiring(stage, session)
        val coordinator = PiPCoordinator(stage = stage, wiring = wiring, audio = { null })
        val fakePip = CountingPip()
        coordinator.pip = fakePip
        wiring.attachPictureInPicture(coordinator)

        // 空闲态点击画中画按钮 -> 拒绝进入。
        coordinator.enterFromUi()
        coordinator.toggleFromUi()
        assertEquals("未起播时点击画中画按钮不得进入", 0, fakePip.enterCalls)

        // 失败态 -> 拒绝进入。
        stage.noteFailed(true, "err")
        coordinator.enterFromUi()
        assertEquals("失败态点击画中画按钮不得进入", 0, fakePip.enterCalls)

        // 正常起播后手动暂停 -> 拒绝进入。
        stage.noteFailed(false, "")
        stage.notePlaying("alice")
        stage.dispatch(StageIntent.TogglePause(null))
        coordinator.enterFromUi()
        assertEquals("手动暂停时点击画中画按钮不得进入", 0, fakePip.enterCalls)

        // 恢复播放 -> 允许进入。
        stage.dispatch(StageIntent.TogglePause(null))
        stage.notePlaying("alice")
        coordinator.enterFromUi()
        assertEquals("焦点路正在播放时点击画中画按钮应进入", 1, fakePip.enterCalls)
    }

    @Test
    fun testSharedWorkCancelCancelsInflightThumbnailAndBlocksDuringPip() = runBlocking {
        val stage = newStage()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val requestCount = AtomicInteger(0)
        val rootJob = SupervisorJob()
        val thumbScope = CoroutineScope(rootJob + Dispatchers.IO)
        val innerHttp = object : HTTPClient {
            override suspend fun send(request: HTTPRequest): HTTPResult {
                requestCount.incrementAndGet()
                started.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    cancelled.complete(Unit)
                }
                @Suppress("UNREACHABLE_CODE")
                return HTTPResult(200, ByteArray(0))
            }
        }
        val cache = ThumbnailCache(
            client = vela.app.EpochGatedThumbnailClient(innerHttp, stage),
            scope = thumbScope,
            decode = { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) },
        )
        stage.onCancelSharedWork = { rootJob.cancelChildren() }

        val pending = async { cache.imageOrFetch("https://thumb.example/bob.jpg") }
        started.await()

        // 进入画中画仅放行主路（挂起共享任务）：在途缩略图下载必须立即被真正取消。
        stage.lifecycleEpoch.suspend(PlaybackLaneRole.PRIMARY)
        stage.cancelSharedWork()
        cancelled.await()
        runCatching { pending.await() }
        assertTrue("共享任务挂起后在途缩略图任务必须被取消", cancelled.isCompleted)

        // 画中画期间发起新缩略图下载不得触达底层 HTTP。
        val before = requestCount.get()
        val result = runCatching { cache.imageOrFetch("https://thumb.example/carol.jpg") }.getOrNull()
        assertNull(result)
        assertEquals("画中画期间不得发起新的缩略图 HTTP 请求", before, requestCount.get())

        thumbScope.cancel()
    }

    private fun newStage(
        http: HTTPClient = object : HTTPClient {
            override suspend fun send(request: HTTPRequest): HTTPResult = HTTPResult(200, ByteArray(0))
        },
    ): StageViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("vela-p7-${opened.size}", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = Settings(prefs)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = true
        settings.source = FeedSource.popular
        val dir = directory.resolve("s${opened.size}")
        Files.createDirectories(dir)
        val stage = StageViewModel(
            settings = settings,
            http = http,
            directory = dir,
            context = context,
        )
        opened.add(stage)
        return stage
    }

    private fun installRooms(stage: StageViewModel, names: List<String>) {
        val rooms = names.map { Room(username = it, viewers = 2, show = ShowState.publicShow) }
        stage.updateFeed(installing = FeedSource.popular, intent = LaneSelection.Intent.Reload) { store ->
            store.replace(CatalogPage(rooms = rooms, total = rooms.size, nextOffset = rooms.size))
        }
    }

    private class CountingPip : PictureInPictureControlling {
        override var canAutoEnter: Boolean = true
        override var autoEnter: Boolean = true
        var enterCalls = 0
        override fun enter() {
            enterCalls += 1
        }
        override fun stop() = Unit
    }

    private class RecordingSink : PlaybackSink {
        override val lastError: String? = null
        override val failed: Boolean = false
        var playCalls = 0
        var volumeCalls = 0
        var lastMuted = true
        override fun play(
            rendition: StreamRendition,
            username: String,
            peakBitrate: Double,
            allowed: Boolean,
            isRetry: Boolean,
        ) {
            playCalls += 1
        }
        override fun stop() = Unit
        override fun setVolume(volume: Double, muted: Boolean) {
            volumeCalls += 1
            lastMuted = muted
        }
        override fun setAllowed(allowed: Boolean) = Unit
        override fun refreshFinished(reason: String?, report: Boolean) = Unit
        override fun setRefreshHandler(handler: ((String, Boolean) -> Unit)?) = Unit
        override fun setRenewHandler(handler: (suspend (String, Double) -> StreamRendition?)?) = Unit
    }
}
