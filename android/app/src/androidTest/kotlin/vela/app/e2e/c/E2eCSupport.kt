package vela.app.e2e.c

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import vela.app.MainActivity
import vela.app.infra.Endpoints
import vela.app.infra.Settings
import vela.app.lane.LaneLog
import vela.app.lane.PlaybackLane
import vela.app.stage.AppPlaybackSession
import vela.app.stage.LanePlayback
import vela.app.stage.StageIntent
import vela.app.stage.StageViewModel
import vela.core.base.lane.LaneID
import vela.core.base.model.FeedSource
import vela.core.lifecycle.PhoneScenePhase
import vela.core.lifecycle.PiPPolicy

internal object E2eCSupport {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    private const val PACKAGE = "vela.app"

    fun upstream(): String {
        val raw = InstrumentationRegistry.getArguments().getString("upstream").orEmpty()
            .ifEmpty { vela.app.BuildConfig.VELA_UPSTREAM }
        check(raw.startsWith("http://") || raw.startsWith("https://")) { "没有伪上游地址" }
        return raw.trimEnd('/')
    }

    fun resetLocalData() {
        val target = instrumentation.targetContext
        target.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        File(target.filesDir, "Vela").deleteRecursively()
    }

    class Launch(
        val activity: MainActivity,
        val scenario: ActivityScenario<MainActivity>,
    )

    fun launchPortrait(
        autoEnterPip: Boolean = true,
        dual: Boolean = false,
        muted: Boolean = true,
    ): Launch {
        val upstream = upstream()
        Endpoints.overrideRoot(upstream)
        resetLocalData()
        val target = instrumentation.targetContext
        target.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Settings.KEY_AGE, true)
            .putBoolean(Settings.KEY_HINT, true)
            .putBoolean(Settings.KEY_MUTED, muted)
            .putBoolean(Settings.KEY_PIP, autoEnterPip)
            .putBoolean(Settings.KEY_DUAL, dual)
            .putString(Settings.KEY_SOURCE, FeedSource.popular.name)
            .commit()
        val launched = ActivityScenario.launch(MainActivity::class.java)
        val holder = arrayOfNulls<MainActivity>(1)
        launched.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            holder[0] = it
        }
        val activity = holder[0] ?: error("Activity 没有起来")
        assertTrue(
            "目录没装上",
            waitUntil(25_000) {
                val state = activity.stage.state.value
                state.rooms.isNotEmpty() && state.currentUsername != null
            },
        )
        assertTrue(
            "焦点路没有出画",
            waitUntil(25_000) {
                val lane = activity.session.lane.value
                lane != null && lane.firstFrameElapsedMs in 0..12_000 && !lane.failed &&
                    activity.stage.state.value.lane.playback == LanePlayback.Playing
            },
        )
        if (dual && activity.stage.state.value.laneCount < 2) {
            onMain(activity) { activity.stage.dispatch(StageIntent.SetDual(true)) }
            assertTrue(
                "双窗没打开",
                waitUntil(8_000) { activity.stage.state.value.laneCount >= 2 },
            )
            assertTrue(
                "两路首帧",
                waitUntil(25_000) {
                    val secondary = activity.session.secondaryLaneForTest
                    secondary != null && secondary.firstFrameElapsedMs in 0..12_000 && !secondary.failed
                },
            )
        }
        return Launch(activity, launched)
    }

    fun bringToForeground(activity: MainActivity) {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        instrumentation.targetContext.startActivity(intent)
        assertTrue(
            "没有回到前台",
            waitUntil(8_000) { activity.wiring.phase == PhoneScenePhase.ACTIVE },
        )
    }

    fun pressHome() {
        UiDevice.getInstance(instrumentation).pressHome()
    }

    fun pipSupported(activity: MainActivity): Boolean =
        activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun inPipOrActive(activity: MainActivity): Boolean {
        val coordinator = activity.wiring.pipCoordinator
        return activity.isInPictureInPictureMode ||
            coordinator?.pictureInPictureState == PiPPolicy.State.ACTIVE ||
            coordinator?.pictureInPictureState == PiPPolicy.State.STARTING ||
            activity.stage.pictureInPicture
    }

    fun waitEnterPipOrStop(activity: MainActivity, limitMs: Long = 4_000): Boolean {
        val start = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - start < limitMs) {
            if (inPipOrActive(activity)) return true
            if (!activity.stage.playbackGateOpen &&
                activity.stage.lifecycleEpoch.allowedLane == null &&
                activity.session.lane.value == null
            ) {
                return false
            }
            Thread.sleep(50)
        }
        return inPipOrActive(activity)
    }

    fun closePipInBackground(activity: MainActivity) {
        onMain(activity) {
            activity.wiring.onPictureInPictureEvent(vela.app.pip.PiPEvent.WillStop)
            activity.wiring.onPictureInPictureEvent(vela.app.pip.PiPEvent.DidStop)
        }
    }

    fun hit(upstream: String, path: String) {
        val connection = URL(upstream + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = "GET"
            connection.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            runCatching { connection.errorStream?.use { it.readBytes() } }
        } finally {
            connection.disconnect()
        }
    }

    fun dumpsysAudio(): String =
        UiDevice.getInstance(instrumentation).executeShellCommand("dumpsys audio")

    /** 焦点栈里是否出现本包（静音时应为假）。 */
    fun packageInAudioFocusStack(dump: String): Boolean {
        val lower = dump.lowercase()
        val markers = listOf(
            "pack: $PACKAGE",
            "pack:$PACKAGE",
            "package:$PACKAGE",
            "clientid=android.media.audiopolicy for $PACKAGE",
        )
        if (markers.any { lower.contains(it.lowercase()) }) return true
        var inStack = false
        for (line in dump.lines()) {
            if (line.contains("Focus stack", ignoreCase = true) ||
                line.contains("focus stack", ignoreCase = true)
            ) {
                inStack = true
                continue
            }
            if (inStack && line.contains(PACKAGE)) return true
            if (inStack && line.isBlank()) inStack = false
        }
        return false
    }

    fun dumpShowsGainTransient(dump: String): Boolean =
        dump.contains("GAIN_TRANSIENT") ||
            dump.contains("gain transient", ignoreCase = true) ||
            dump.contains("gain: gain", ignoreCase = true) && dump.contains(PACKAGE)

    fun holdingAudioFocus(activity: MainActivity): Boolean =
        onMain(activity) { activity.session.audioController().holdingFocus }

    fun secondaryLane(session: AppPlaybackSession): PlaybackLane? = session.secondaryLaneForTest

    fun renderedOutputBufferCount(lane: PlaybackLane): Int =
        onMainSync { lane.renderedOutputBufferCount() }

    fun audioEnabled(lane: PlaybackLane): Int =
        onMainSync { lane.audioEnabledCountForTest() }

    fun uncroppedLogged(): Boolean =
        LaneLog.snapshot().any { line ->
            line.contains("display-check") &&
                line.contains("gravity=fit") &&
                line.contains("uncropped=true")
        }

    fun exoThreads(): List<String> = threadNames().filter { name ->
        name.startsWith("ExoPlayer") && name != "ExoPlayer:Backg"
    }

    fun threadNames(): List<String> {
        val dir = File("/proc/${Process.myPid()}/task")
        return dir.list()?.mapNotNull { tid ->
            runCatching { File(dir, "$tid/comm").readText().trim() }.getOrNull()
        }.orEmpty()
    }

    fun cpuTicks(): Long {
        val stat = File("/proc/self/stat").readText()
        val end = stat.lastIndexOf(')')
        val fields = stat.substring(end + 2).split(' ')
        return fields[11].toLong() + fields[12].toLong()
    }

    fun <T> onMain(stage: StageViewModel, block: () -> T): T {
        val activity = stage.appContext as? Activity ?: error("没有 Activity")
        return onMain(activity, block)
    }

    fun <T> onMain(activity: Activity, block: () -> T): T {
        val box = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            try {
                box.set(block())
            } catch (thrown: Throwable) {
                error.set(thrown)
            } finally {
                latch.countDown()
            }
        }
        assertTrue(latch.await(20, TimeUnit.SECONDS))
        error.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return box.get() as T
    }

    fun <T> onMainSync(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val box = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                box.set(block())
            } catch (thrown: Throwable) {
                error.set(thrown)
            }
        }
        error.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return box.get() as T
    }

    fun waitUntil(limitMs: Long = 5_000, predicate: () -> Boolean): Boolean {
        val start = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - start < limitMs) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }
}