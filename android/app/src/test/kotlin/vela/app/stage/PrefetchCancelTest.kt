package vela.app.stage

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.core.base.model.FeedSource
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.lifecycle.LifecycleEpoch
import vela.core.lifecycle.PhoneScenePhase

/**
 * 预取如果没登记，进后台或暂停取消不到它，挂起的聊天上下文返回后还会继续打 edge。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefetchCancelTest {
    @Test
    fun testBackgroundCancelsPrefetchBeforeEdgeFallback() {
        val probe = Probe()
        val harness = open(probe.http)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            harness.stage.attachPlayback(RecordingSink())
            harness.stage.requestPlay()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("预取没有开始", probe.beeStarted.get() >= 1)
            harness.wiring.moveTo(PhoneScenePhase.ACTIVE)
            harness.wiring.moveTo(PhoneScenePhase.BACKGROUND)
            probe.release()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("预取在取消后仍继续", 0, probe.continued.get())
            assertEquals("后台仍发起了 edge", 0, probe.edges.get())
        } finally {
            harness.stage.close()
            harness.directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testPauseCancelsPrefetchBeforeEdgeFallback() {
        val probe = Probe()
        val harness = open(probe.http)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            harness.stage.attachPlayback(RecordingSink())
            harness.stage.requestPlay()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(probe.beeStarted.get() >= 1)
            harness.stage.dispatch(StageIntent.TogglePause(null))
            probe.release()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, probe.continued.get())
            assertEquals(0, probe.edges.get())
        } finally {
            harness.stage.close()
            harness.directory.toFile().deleteRecursively()
        }
    }

    private fun open(http: ScriptHttp): Harness {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val settings = Settings(context)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = true
        settings.source = FeedSource.popular
        val directory = Files.createTempDirectory("vela-prefetch")
        val stage = StageViewModel(settings = settings, http = http, directory = directory, context = context)
        val wiring = LifecycleWiring(stage, object : LifecycleSession {
            override fun closeGate() = Unit
            override fun onEpoch(epoch: LifecycleEpoch) = Unit
            override fun suspendLanes() = Unit
            override fun deactivateAudio() = Unit
            override fun prepareAudio() = Unit
            override fun stopListener() = Unit
            override fun ensureLane() = Unit
            override fun restoreUi() = Unit
        })
        return Harness(stage, wiring, directory)
    }

    private class Harness(
        val stage: StageViewModel,
        val wiring: LifecycleWiring,
        val directory: java.nio.file.Path,
    )

    private class Probe {
        val beeStarted = AtomicInteger()
        val continued = AtomicInteger()
        val edges = AtomicInteger()
        private val gate = CompletableDeferred<Unit>()

        fun release() {
            gate.complete(Unit)
        }

        val http: ScriptHttp = scriptHttp { request -> answer(request) }

        private suspend fun answer(request: HTTPRequest): HTTPResult = when {
            request.url.contains("room-list") -> catalogBody(listOf("ann", "bee"))
            request.url.contains("chatvideocontext") && request.url.contains("/bee") -> {
                beeStarted.incrementAndGet()
                gate.await()
                continued.incrementAndGet()
                ok("""{"room_status":"public"}""")
            }
            request.url.contains("chatvideocontext") ->
                ok("""{"room_status":"public","hls_source":"https://cdn.example/master.m3u8"}""")
            request.url.contains("master.m3u8") ->
                ok("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nhttps://cdn.example/v.m3u8\n")
            request.url.contains("get_edge_hls_url_ajax") -> {
                edges.incrementAndGet()
                ok("""{"success":false,"room_status":"offline"}""")
            }
            else -> error("意外请求 ${request.url}")
        }
    }
}
