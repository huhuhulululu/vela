package vela.app.stage

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.core.base.model.FeedSource
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.lane.PlaybackLaneRole
import vela.core.lifecycle.LifecycleEpoch
import vela.core.lifecycle.PhoneScenePhase

/**
 * 恢复时如果重新捕获，任务已经被取消，保存的恢复意图会被丢掉。
 * 目录和搜索都要在进后台时记下，回来用那一份，用完清掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumeIntentTest {
    @Test
    fun testInFlightCatalogSurvivesBackgroundRoundTrip() {
        val http = HoldHttp(catalog("room_a"))
        val harness = open(http)
        try {
            assertTrue(http.started.get() >= 1)
            harness.wiring.moveTo(PhoneScenePhase.ACTIVE)
            harness.wiring.moveTo(PhoneScenePhase.BACKGROUND)
            http.release()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(harness.stage.state.value.rooms.isEmpty())
            harness.wiring.moveTo(PhoneScenePhase.ACTIVE)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(harness.wiring.trace().contains("redo-reload"))
            assertEquals("room_a", harness.stage.state.value.currentUsername)
            assertNull(harness.stage.notedResume)
        } finally {
            harness.stage.close()
            harness.directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testInFlightSearchIsRedoneFromTheSavedIntent() {
        val searches = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val http = scriptHttp { request ->
            if (request.url.contains("keywords=")) {
                searches.incrementAndGet()
                gate.await()
                return@scriptHttp catalog("orchid_room")
            }
            catalog("other")
        }
        val harness = open(http)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("other", harness.stage.state.value.currentUsername)
            harness.stage.dispatch(StageIntent.SetSearchText("orchid"))
            harness.stage.dispatch(StageIntent.SubmitSearch)
            assertTrue(searches.get() >= 1)
            harness.wiring.moveTo(PhoneScenePhase.ACTIVE)
            harness.wiring.moveTo(PhoneScenePhase.BACKGROUND)
            val before = searches.get()
            gate.complete(Unit)
            shadowOf(Looper.getMainLooper()).idle()
            harness.wiring.moveTo(PhoneScenePhase.ACTIVE)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(harness.wiring.trace().contains("redo-search"))
            assertTrue("搜索没有重做 before=$before after=${searches.get()}", searches.get() > before)
            assertEquals("orchid", harness.stage.state.value.siteSearch)
            assertNull(harness.stage.notedResume)
        } finally {
            harness.stage.close()
            harness.directory.toFile().deleteRecursively()
        }
    }

    private fun open(http: HTTPClient): Harness {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val settings = Settings(context)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = true
        settings.source = FeedSource.popular
        val directory = Files.createTempDirectory("vela-resume")
        val stage = StageViewModel(settings = settings, http = http, directory = directory, context = context)
        val wiring = LifecycleWiring(stage, IdleSession())
        return Harness(stage, wiring, directory)
    }

    private class Harness(
        val stage: StageViewModel,
        val wiring: LifecycleWiring,
        val directory: java.nio.file.Path,
    )

    private class IdleSession : LifecycleSession {
        override fun closeGate() = Unit
        override fun onEpoch(epoch: LifecycleEpoch) = Unit
        override fun suspendLanes() = Unit
        override fun releaseLane(role: PlaybackLaneRole) = Unit
        override fun retainRelayFor(role: PlaybackLaneRole) = Unit
        override fun deactivateAudio() = Unit
        override fun prepareAudio() = Unit
        override fun stopListener() = Unit
        override fun ensureLane() = Unit
        override fun restoreUi() = Unit
    }

    private class HoldHttp(private val body: HTTPResult) : HTTPClient {
        val started = AtomicInteger()
        private val gate = CompletableDeferred<Unit>()

        fun release() {
            gate.complete(Unit)
        }

        override suspend fun send(request: HTTPRequest): HTTPResult {
            started.incrementAndGet()
            gate.await()
            return body
        }
    }
}

private fun catalog(name: String): HTTPResult = catalogBody(listOf(name))
