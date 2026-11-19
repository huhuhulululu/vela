package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.core.base.model.FeedSource
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.net.UserAgent
import vela.core.base.play.StreamRendition

/**
 * 起播链。假客户端只回合成 JSON 和合成主列表，不连接直播站点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StagePlaybackChainTest {
    private val harness = StageHarness()
    private lateinit var settings: Settings
    private lateinit var directory: java.nio.file.Path
    private val clock = ManualClock()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context)
        directory = Files.createTempDirectory("vela-stage-play")
        settings.ageConfirmed = true
        settings.source = FeedSource.popular
    }

    @After
    fun tearDown() {
        deleteTree(directory)
        harness.close()
    }

    @Test
    fun testUnboundPlayDoesNotFetchStream() = harness.onStage {
        val http = scriptHttp { request ->
            if (request.url.contains("room-list")) catalogBody(listOf("ann")) else error("不该请求 ${request.url}")
        }
        val stage = open(http)
        try {
            stage.awaitLoads()
            assertEquals("ann", stage.state.value.currentUsername)
            assertTrue(http.urls.none { it.contains("chatvideocontext") || it.contains("get_edge_hls_url_ajax") })
            assertTrue(stage.state.value.lane.generation > 0)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testChatContextStartsPlaybackOnce() = harness.onStage {
        val http = scriptHttp { request -> answerHappy(request) }
        val stage = open(http)
        val sink = RecordingSink()
        try {
            stage.awaitLoads()
            stage.prefetchEnabled = false
            stage.bindPlayback(sink)
            stage.requestPlay()
            stage.awaitUntil { sink.played.isNotEmpty() }
            assertEquals(listOf("ann"), sink.played)
            assertEquals(1, http.urls.count { it.contains("chatvideocontext") })
            assertEquals(0, http.urls.count { it.contains("get_edge_hls_url_ajax") })
            val master = http.requests.first { it.url.contains("master.m3u8") }
            assertEquals(UserAgent.safari, master.header("User-Agent"))
            assertEquals("https://chaturbate.com/", master.header("Referer"))
            assertFalse(sink.renditions.single().isUnselected)
            assertTrue(sink.renditions.single().video.toString().contains("cdn.example"))
            assertEquals(0, stage.challengeGate.consecutive)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testLoopbackHttpSourceIsPlayable() = harness.onStage {
        val nested = "http://10.0.2.2:9/hls/ann/master.m3u8"
        val root = "http://10.0.2.2:9/master.m3u8"
        val http = scriptHttp { request ->
            when {
                request.url.contains("room-list") -> catalogBody(listOf("ann"))
                request.url.contains("chatvideocontext") ->
                    ok("""{"room_status":"public","hls_source":"$nested"}""")
                request.url == root -> ok(LOOPBACK_MASTER)
                else -> error("意外请求 ${request.url}")
            }
        }
        val stage = open(http)
        val sink = RecordingSink()
        try {
            stage.awaitLoads()
            stage.prefetchEnabled = false
            stage.bindPlayback(sink)
            stage.requestPlay()
            stage.awaitUntil { sink.played.isNotEmpty() }
            val video = sink.renditions.single().video
            assertEquals("http://10.0.2.2:9/media.m3u8", video.toString())
            assertFalse(sink.renditions.single().isUnselected)
            assertEquals(0, http.urls.count { it.contains("get_edge_hls_url_ajax") })
        } finally {
            stage.close()
        }
    }

    @Test
    fun testForeignHttpSourceIsNotPlayed() = harness.onStage {
        val http = scriptHttp { request ->
            when {
                request.url.contains("room-list") -> catalogBody(listOf("ann"))
                request.url.contains("chatvideocontext") ->
                    ok("""{"room_status":"public","hls_source":"http://evil.example/a.m3u8"}""")
                request.url.contains("get_edge_hls_url_ajax") ->
                    ok("""{"success":true,"url":"http://evil.example/a.m3u8","room_status":"public"}""")
                else -> error("意外请求 ${request.url}")
            }
        }
        val stage = open(http)
        val sink = RecordingSink()
        try {
            stage.awaitLoads()
            stage.prefetchEnabled = false
            stage.bindPlayback(sink)
            stage.requestPlay()
            stage.awaitUntil { stage.state.value.lane.playbackNote != null }
            assertTrue(sink.played.isEmpty())
            assertEquals("当前地区不可观看", stage.state.value.lane.playbackNote)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testStreamChallengeOpensSheetAndSuspendsSidecar() = harness.onStage {
        val page = FakeSidecarPage(challengePayload())
        val http = scriptHttp { request ->
            when {
                request.url.contains("room-list") -> catalogBody(listOf("ann"))
                request.url.contains("chatvideocontext") || request.url.contains("get_edge_hls_url_ajax") ->
                    ok(CHALLENGE_HTML)
                else -> error("意外请求 ${request.url}")
            }
        }
        val stage = open(http)
        val sink = RecordingSink()
        try {
            stage.awaitLoads()
            stage.prefetchEnabled = false
            stage.skipDelayMillis = 60_000
            stage.bindPlayback(sink)
            stage.installSidecar(page)
            stage.requestPlay()
            stage.awaitUntil { stage.state.value.surface == StageSurface.Challenge }
            assertEquals("需要确认访问后才能播放", stage.state.value.lane.playbackNote)
            assertTrue(page.detached)
            assertTrue(sink.stops > 0)
            assertTrue(sink.played.isEmpty())
            assertTrue(stage.sidecarUntil != null)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testRepeatedChallengeSkipsInsteadOfSheet() = harness.onStage {
        val page = FakeSidecarPage(challengePayload())
        val http = scriptHttp { request ->
            when {
                request.url.contains("room-list") -> catalogBody(listOf("ann"))
                request.url.contains("chatvideocontext") || request.url.contains("get_edge_hls_url_ajax") ->
                    ok(CHALLENGE_HTML)
                else -> error("意外请求 ${request.url}")
            }
        }
        val stage = open(http)
        val sink = RecordingSink()
        try {
            stage.awaitLoads()
            stage.prefetchEnabled = false
            stage.skipDelayMillis = 60_000
            stage.bindPlayback(sink)
            stage.installSidecar(page)
            stage.challengeGate.register(stage.now())
            stage.requestPlay()
            stage.awaitUntil { stage.state.value.lane.playbackNote?.contains("连续要求确认") == true }
            assertEquals(StageSurface.None, stage.state.value.surface)
            assertEquals("站点连续要求确认，这一间先跳过", stage.state.value.lane.playbackNote)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testAwayHoldsWithoutPlaying() = harness.onStage {
        val http = scriptHttp { request ->
            when {
                request.url.contains("room-list") -> catalogBody(listOf("ann"))
                request.url.contains("chatvideocontext") -> ok("""{"room_status":"away"}""")
                else -> error("意外请求 ${request.url}")
            }
        }
        val stage = open(http)
        val sink = RecordingSink()
        try {
            stage.awaitLoads()
            stage.prefetchEnabled = false
            stage.awayDelayOverrideMillis = 60_000
            stage.bindPlayback(sink)
            stage.requestPlay()
            stage.awaitUntil { stage.state.value.lane.playbackNote != null }
            assertTrue(sink.played.isEmpty())
            assertEquals("暂时离开，稍后自动重试", stage.state.value.lane.playbackNote)
            assertTrue(stage.awayTimer.pending)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testPausedBoundPlayDoesNotResolve() = harness.onStage {
        val http = scriptHttp { request ->
            if (request.url.contains("room-list")) catalogBody(listOf("ann")) else error("不该请求 ${request.url}")
        }
        val stage = open(http)
        val sink = RecordingSink()
        try {
            stage.awaitLoads()
            val generation = stage.state.value.lane.generation
            stage.bindPlayback(sink)
            stage.dispatch(StageIntent.TogglePause(null))
            assertTrue(stage.state.value.lane.manuallyPaused)
            stage.requestPlay()
            yield()
            assertEquals(generation, stage.state.value.lane.generation)
            assertTrue(http.urls.none { it.contains("chatvideocontext") })
            assertEquals(false, sink.allowed)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testNewerRoomCancelsStaleResolve() = harness.onStage {
        val gate = CompletableDeferred<Unit>()
        val chats = AtomicInteger()
        val http = scriptHttp { request ->
            when {
                request.url.contains("room-list") -> catalogBody(listOf("ann", "bea"))
                request.url.contains("chatvideocontext") -> {
                    if (chats.incrementAndGet() == 1) gate.await()
                    ok("""{"room_status":"public","hls_source":"https://cdn.example/master.m3u8"}""")
                }
                request.url.contains("master.m3u8") -> ok(MASTER)
                else -> error("意外请求 ${request.url}")
            }
        }
        val stage = open(http)
        val sink = RecordingSink()
        try {
            stage.awaitLoads()
            stage.prefetchEnabled = false
            stage.bindPlayback(sink)
            stage.requestPlay()
            stage.awaitUntil { chats.get() == 1 }
            assertTrue(stage.dispatchStep())
            gate.complete(Unit)
            stage.awaitUntil { sink.played.isNotEmpty() }
            assertEquals(listOf("bea"), sink.played)
            assertEquals("bea", stage.state.value.currentUsername)
        } finally {
            stage.close()
        }
    }

    private fun open(http: ScriptHttp): StageViewModel =
        StageViewModel(settings, http, directory, clock, harness.dispatcher)

    private fun StageViewModel.dispatchStep(): Boolean {
        val before = state.value.currentUsername
        dispatch(StageIntent.Step(1))
        return state.value.currentUsername != before
    }

    companion object {
        private const val MASTER =
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nhttps://cdn.example/v.m3u8\n"
        private const val LOOPBACK_MASTER =
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nmedia.m3u8\n"
        private const val CHALLENGE_HTML = "<html>Just a moment</html>"
    }
}

internal class RecordingSink : PlaybackSink {
    val played = mutableListOf<String>()
    val renditions = mutableListOf<StreamRendition>()
    var stops: Int = 0
    var allowed: Boolean? = null

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
        renditions.add(rendition)
        this.allowed = allowed
    }

    override fun stop() {
        stops += 1
    }

    override fun setVolume(volume: Double, muted: Boolean) = Unit

    override fun setAllowed(allowed: Boolean) {
        this.allowed = allowed
    }

    override fun refreshFinished(reason: String?, report: Boolean) = Unit

    override fun setRefreshHandler(handler: ((String, Boolean) -> Unit)?) = Unit

    override fun setRenewHandler(handler: (suspend (String, Double) -> StreamRendition?)?) = Unit
}

internal class ScriptHttp(
    private val handler: suspend (HTTPRequest) -> HTTPResult,
) : HTTPClient {
    val urls = mutableListOf<String>()
    val requests = mutableListOf<HTTPRequest>()

    override suspend fun send(request: HTTPRequest): HTTPResult {
        urls.add(request.url)
        requests.add(request)
        return handler(request)
    }
}

internal fun scriptHttp(handler: suspend (HTTPRequest) -> HTTPResult): ScriptHttp = ScriptHttp(handler)

internal fun ok(body: String, status: Int = 200): HTTPResult =
    HTTPResult(status = status, data = body.toByteArray(Charsets.UTF_8), headers = emptyMap())

internal fun catalogBody(names: List<String>): HTTPResult = ok(catalogJson(names).toString(Charsets.UTF_8))

internal fun answerHappy(request: HTTPRequest): HTTPResult = when {
    request.url.contains("room-list") -> catalogBody(listOf("ann"))
    request.url.contains("chatvideocontext") ->
        ok("""{"room_status":"public","hls_source":"https://cdn.example/master.m3u8"}""")
    request.url.contains("master.m3u8") ->
        ok("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nhttps://cdn.example/v.m3u8\n")
    else -> error("意外请求 ${request.url}")
}

internal class FakeSidecarPage(private val payload: String) : SidecarPage {
    override var loaded: Boolean = false
    var detached: Boolean = false
    var destroyed: Boolean = false
    var lastScript: String? = null

    override suspend fun load(url: String) {
        loaded = true
    }

    override suspend fun evaluate(script: String): String {
        lastScript = script
        return payload
    }

    override fun detach() {
        detached = true
    }

    override fun attach() {
        detached = false
    }

    override fun destroy() {
        destroyed = true
        loaded = false
    }
}

internal fun challengePayload(): String =
    """{"status":403,"body":"<!DOCTYPE html>Just a moment</html>"}"""

internal fun ticketPayload(): String =
    """{"status":200,"body":"{\"success\":true,\"url\":\"https://cdn.example/master.m3u8\",\"room_status\":\"public\"}"}"""
