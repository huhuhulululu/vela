package vela.app.pip

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import vela.app.MainActivity
import vela.app.infra.Endpoints
import vela.app.infra.Settings
import vela.core.base.model.FeedSource
import vela.core.lifecycle.PhoneScenePhase

@RunWith(AndroidJUnit4::class)
class PipEmulatorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        val target = instrumentation.targetContext
        target.getSharedPreferences(Settings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        File(target.filesDir, "Vela").deleteRecursively()
    }

    @Test(timeout = 180_000)
    fun testAutoEnterOffStopsOnHome() {
        val activity = launchReady(autoPip = false)
        UiDevice.getInstance(instrumentation).pressHome()
        assertTrue(waitUntil { activity.wiring.phase == PhoneScenePhase.BACKGROUND })
        assertTrue(waitUntil { activity.session.lane.value == null })
    }

    @Test(timeout = 180_000)
    fun testAutoEnterKeepsPrimaryAfterHome() {
        val activity = launchReady(autoPip = true)
        UiDevice.getInstance(instrumentation).pressHome()
        assertTrue(waitUntil { activity.wiring.phase == PhoneScenePhase.BACKGROUND })
        Thread.sleep(1_500)
        val bridge = activity.pipBridge
        val stillPlaying = activity.session.lane.value != null
        val inPip = bridge?.pictureInPictureState == vela.core.lifecycle.PiPPolicy.State.ACTIVE
        assertTrue(
            "既没留播放器也没进画中画 lane=${activity.session.lane.value} state=${bridge?.pictureInPictureState}",
            stillPlaying || inPip,
        )
    }

    private fun launchReady(autoPip: Boolean): MainActivity {
        val args = InstrumentationRegistry.getArguments()
        val raw = args.getString("upstream").orEmpty().ifEmpty { vela.app.BuildConfig.VELA_UPSTREAM }
        assertTrue(raw.startsWith("http://") || raw.startsWith("https://"))
        val target = instrumentation.targetContext
        Settings(target).apply {
            ageConfirmed = true
            hintSeen = true
            muted = true
            autoEnterPip = autoPip
            source = FeedSource.popular
        }
        if (raw.isNotEmpty()) Endpoints.overrideRoot(raw.trimEnd('/'))
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        val holder = arrayOfNulls<MainActivity>(1)
        launched.onActivity { holder[0] = it }
        val activity = holder[0] ?: error("Activity 没起来")
        assertTrue(
            waitUntil(25_000) { activity.stage.state.value.currentUsername != null },
        )
        assertTrue(
            waitUntil(25_000) {
                val lane = activity.session.lane.value
                lane != null && lane.firstFrameElapsedMs >= 0L
            },
        )
        return activity
    }

    private fun waitUntil(limitMs: Long = 8_000, predicate: () -> Boolean): Boolean {
        val start = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - start < limitMs) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }
}