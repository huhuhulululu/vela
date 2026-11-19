package vela.app.lifecycle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import vela.app.infra.Settings
import vela.app.stage.LifecycleSession
import vela.app.stage.LifecycleWiring
import vela.app.stage.SidecarPage
import vela.app.stage.StageIntent
import vela.app.stage.StageViewModel
import vela.core.base.model.FeedSource
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.lifecycle.LifecycleEpoch
import vela.core.lifecycle.PhoneScenePhase

/**
 * 不起 ExoPlayer。核对进后台的动作顺序，以及回前台按冻结裁决 7 取舍。
 * 目录只用合成 JSON。
 */
@RunWith(AndroidJUnit4::class)
class LifecycleOrderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val opened = ArrayList<StageViewModel>()

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            opened.forEach { runCatching { it.close() } }
        }
        opened.clear()
    }

    @Test
    fun testInactiveRoundTripDoesNothing() {
        val harness = open(CatalogHttp())
        instrumentation.runOnMainSync {
            harness.wiring.moveTo(PhoneScenePhase.ACTIVE)
            harness.wiring.moveTo(PhoneScenePhase.INACTIVE)
            harness.wiring.moveTo(PhoneScenePhase.ACTIVE)
        }
        assertTrue(harness.wiring.trace().isEmpty())
        assertFalse(harness.wiring.wasSuspended)
        assertEquals(0, harness.session.suspends)
    }

    @Test
    fun testBackgroundOrderSettlesAndForegroundReplays() {
        val harness = open(CatalogHttp())
        assertTrue(waitUntil { harness.stage.state.value.currentUsername == "synth_nova" })
        val page = RecordingPage()
        instrumentation.runOnMainSync {
            harness.stage.attachSidecar(page)
            harness.stage.notePlaying("synth_nova")
            harness.wiring.moveTo(PhoneScenePhase.ACTIVE)
            harness.wiring.moveTo(PhoneScenePhase.INACTIVE)
            harness.wiring.moveTo(PhoneScenePhase.BACKGROUND)
        }
        assertEquals(BACKGROUND, harness.wiring.trace())
        assertTrue(harness.wiring.epochSuspended)
        assertEquals(1uL, harness.wiring.epoch)
        assertFalse(harness.wiring.gateOpen)
        assertEquals("synth_nova", harness.wiring.lastSettlementUsername)
        assertEquals(1, harness.wiring.flushCount)
        assertEquals(1, harness.session.suspends)
        assertEquals(1, harness.session.audioStops)
        assertEquals(1, harness.session.listenerStops)
        assertTrue(page.destroyed)
        assertTrue(
            harness.session.calls.indexOf("suspend-lanes") < harness.session.calls.indexOf("audio"),
        )
        assertTrue(harness.session.calls.indexOf("audio") < harness.session.calls.indexOf("stop-listener"))

        instrumentation.runOnMainSync {
            harness.wiring.moveTo(PhoneScenePhase.BACKGROUND)
            harness.wiring.moveTo(PhoneScenePhase.INACTIVE)
            harness.wiring.moveTo(PhoneScenePhase.ACTIVE)
        }
        assertEquals(
            BACKGROUND + listOf("epoch-resume", "request-play"),
            harness.wiring.trace(),
        )
        assertFalse(harness.wiring.epochSuspended)
        assertFalse(harness.wiring.wasSuspended)
        assertTrue(harness.wiring.gateOpen)
        assertEquals(1, harness.session.ensures)
        assertEquals("synth_nova", harness.stage.state.value.currentUsername)
    }

    @Test
    fun testForegroundKeepsFailurePauseAndHidden() {
        val failed = open(CatalogHttp())
        assertTrue(waitUntil { failed.stage.state.value.currentUsername != null })
        instrumentation.runOnMainSync {
            failed.stage.noteFailed(true, "合成失败")
            failed.wiring.moveTo(PhoneScenePhase.BACKGROUND)
            failed.wiring.moveTo(PhoneScenePhase.ACTIVE)
        }
        assertTrue(failed.wiring.trace().contains("keep-failure"))
        assertFalse(failed.wiring.trace().contains("request-play"))
        assertEquals(vela.app.stage.LanePlayback.Failed, failed.stage.state.value.lane.playback)
        assertEquals("合成失败", failed.stage.state.value.lane.playbackNote)
        assertEquals(0, failed.session.ensures)

        val paused = open(CatalogHttp())
        assertTrue(waitUntil { paused.stage.state.value.currentUsername != null })
        instrumentation.runOnMainSync {
            paused.stage.notePlaying(paused.stage.state.value.currentUsername)
            paused.stage.dispatch(StageIntent.TogglePause(null))
            paused.wiring.moveTo(PhoneScenePhase.BACKGROUND)
            paused.wiring.moveTo(PhoneScenePhase.ACTIVE)
        }
        assertTrue(paused.stage.state.value.lane.manuallyPaused)
        assertTrue(paused.wiring.trace().contains("stay-paused"))
        assertFalse(paused.wiring.trace().contains("request-play"))

        val hidden = open(CatalogHttp())
        assertTrue(waitUntil { hidden.stage.state.value.currentUsername != null })
        instrumentation.runOnMainSync {
            hidden.stage.notePlaying(hidden.stage.state.value.currentUsername)
            hidden.wiring.setPrimaryVisible(false)
            hidden.wiring.moveTo(PhoneScenePhase.BACKGROUND)
            hidden.wiring.moveTo(PhoneScenePhase.ACTIVE)
        }
        assertTrue(hidden.wiring.trace().contains("stay-released"))
        assertFalse(hidden.wiring.trace().contains("request-play"))
        assertEquals(0, hidden.session.ensures)
    }

    @Test
    fun testInFlightCatalogIsDroppedWhenBackgrounded() {
        val http = HoldHttp()
        val harness = open(http)
        assertTrue(waitUntil { http.started.get() >= 1 })
        instrumentation.runOnMainSync {
            harness.wiring.moveTo(PhoneScenePhase.BACKGROUND)
        }
        http.gate.complete(Unit)
        Thread.sleep(300)
        assertTrue(harness.stage.state.value.rooms.isEmpty())
        assertTrue(harness.wiring.epochSuspended)
        assertTrue(harness.wiring.trace().contains("epoch-suspend"))
        assertTrue(harness.wiring.trace().indexOf("gate-off") < harness.wiring.trace().indexOf("epoch-suspend"))
        assertTrue(
            harness.wiring.trace().indexOf("epoch-suspend") < harness.wiring.trace().indexOf("suspend-lanes"),
        )
    }

    private fun open(http: HTTPClient): Harness {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("lifecycle-order", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = Settings(prefs)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = true
        settings.source = FeedSource.popular
        val directory = Files.createTempDirectory(Paths.get(context.filesDir.absolutePath), "lifecycle-order")
        val session = RecordingSession()
        val holder = arrayOfNulls<Harness>(1)
        instrumentation.runOnMainSync {
            val stage = StageViewModel(
                settings = settings,
                http = http,
                directory = directory,
                dispatcher = Dispatchers.Main.immediate,
                context = context,
            )
            val wiring = LifecycleWiring(stage, session)
            holder[0] = Harness(stage, wiring, session)
        }
        val harness = holder[0]!!
        opened.add(harness.stage)
        return harness
    }

    private fun waitUntil(limitMs: Long = 5_000, predicate: () -> Boolean): Boolean {
        val start = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - start < limitMs) {
            if (predicate()) return true
            Thread.sleep(20)
        }
        return false
    }

    private class Harness(
        val stage: StageViewModel,
        val wiring: LifecycleWiring,
        val session: RecordingSession,
    )

    private class RecordingSession : LifecycleSession {
        val calls = ArrayList<String>()
        var suspends: Int = 0
        var audioStops: Int = 0
        var listenerStops: Int = 0
        var ensures: Int = 0

        override fun closeGate() {
            calls.add("close-gate")
        }

        override fun onEpoch(epoch: LifecycleEpoch) {
            calls.add("epoch")
        }

        override fun suspendLanes() {
            suspends += 1
            calls.add("suspend-lanes")
        }

        override fun deactivateAudio() {
            audioStops += 1
            calls.add("audio")
        }

        override fun prepareAudio() {
            calls.add("audio-prepare")
        }

        override fun stopListener() {
            listenerStops += 1
            calls.add("stop-listener")
        }

        override fun ensureLane() {
            ensures += 1
            calls.add("ensure-lane")
        }

        override fun restoreUi() {
            calls.add("restore-ui")
        }
    }

    private class RecordingPage : SidecarPage {
        override val loaded: Boolean = false
        var destroyed: Boolean = false
        override suspend fun load(url: String) = Unit
        override suspend fun evaluate(script: String): String = ""
        override fun detach() = Unit
        override fun attach() = Unit
        override fun destroy() {
            destroyed = true
        }
    }

    private class CatalogHttp : HTTPClient {
        override suspend fun send(request: HTTPRequest): HTTPResult =
            HTTPResult(status = 200, data = CATALOG, headers = emptyMap())
    }

    private class HoldHttp : HTTPClient {
        val started = AtomicInteger()
        val gate = CompletableDeferred<Unit>()

        override suspend fun send(request: HTTPRequest): HTTPResult {
            started.incrementAndGet()
            gate.await()
            return HTTPResult(status = 200, data = CATALOG, headers = emptyMap())
        }
    }

    companion object {
        private val CATALOG =
            """{"rooms":[{"username":"synth_nova","current_show":"public","num_users":4,"tags":["synth"]}],"total_count":1}"""
                .toByteArray(Charsets.UTF_8)

        private val BACKGROUND = listOf(
            "gate-off",
            "epoch-suspend",
            "settle-dwell",
            "flush-savers",
            "suspend-lanes",
            "audio-deactivate",
            "stop-listener",
            "destroy-sidecar",
            "note-resume",
        )
    }
}
