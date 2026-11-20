package vela.app.e2e.b

import android.app.Activity
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.Method
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import vela.app.MainActivity
import vela.app.infra.Endpoints
import vela.app.infra.Settings
import vela.app.lane.LanePlayer
import vela.app.lane.PlaybackLane
import vela.app.stage.AppPlaybackSession
import vela.app.stage.StageIntent
import vela.app.stage.StageViewModel
import vela.core.base.lane.LaneID
import vela.core.base.model.FeedSource
import vela.core.hls.PhoneThermalState

internal object E2eBSupport {
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    fun upstream(): String {
        val raw = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("upstream").orEmpty()
            .ifEmpty { vela.app.BuildConfig.VELA_UPSTREAM }
        check(raw.startsWith("http://") || raw.startsWith("https://")) { "没有伪上游地址" }
        return raw.trimEnd('/')
    }

    fun resetLocalData() {
        val target = instrumentation.targetContext
        target.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        File(target.filesDir, "Vela").deleteRecursively()
    }

    fun launch(): MainActivity {
        val upstream = upstream()
        Endpoints.overrideRoot(upstream)
        val launched = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        val holder = arrayOfNulls<MainActivity>(1)
        launched.onActivity { holder[0] = it }
        val activity = holder[0] ?: error("Activity 没有起来")
        if (Endpoints.siteRoot().trimEnd('/') != upstream) Endpoints.overrideRoot(upstream)
        check(Endpoints.siteRoot().trimEnd('/') == upstream)
        return activity
    }

    fun confirmAgeAndLoadPopular(stage: StageViewModel) {
        check(stage.state.value.ageConfirmed || run {
            stage.dispatch(StageIntent.ConfirmAge)
            waitUntil { stage.state.value.ageConfirmed }
        })
        onMain(stage) { stage.dispatch(StageIntent.DismissHint) }
        onMain(stage) { stage.dispatch(StageIntent.SelectSource(FeedSource.popular)) }
        assertTrue(
            "目录没装上",
            waitUntil(25_000) {
                stage.state.value.source == FeedSource.popular &&
                    stage.state.value.currentUsername != null &&
                    stage.state.value.rooms.size >= 2
            },
        )
    }

    fun enableDualWindow(stage: StageViewModel) {
        onMain(stage) {
            stage.dispatch(StageIntent.NoteActivity)
            stage.dispatch(StageIntent.SetLaneCount(2))
        }
        assertTrue(
            "双窗没打开 laneCount=${stage.state.value.laneCount}",
            waitUntil(8_000) { stage.state.value.laneCount >= 2 && stage.state.value.dualWindow },
        )
    }

    fun waitBothFirstFrames(activity: MainActivity, stage: StageViewModel) {
        assertTrue(
            "两路房间不独立 primary=${stage.feed.currentUsername} secondary=${stage.secondaryCursor?.username}",
            waitUntil(5_000) { stage.state.value.lanesDistinct() },
        )
        assertTrue(
            "主路首帧超时",
            waitUntil(25_000) {
                val lane = activity.session.lane.value
                lane != null && lane.firstFrameElapsedMs in 0..12_000 && !lane.failed
            },
        )
        assertTrue(
            "副路首帧超时 note=${stage.state.value.secondary?.playbackNote}",
            waitUntil(25_000) {
                val secondary = secondaryLane(activity.session)
                secondary != null && secondary.firstFrameElapsedMs in 0..12_000 && !secondary.failed
            },
        )
    }

    fun secondaryLane(session: AppPlaybackSession): PlaybackLane? {
        val field = AppPlaybackSession::class.java.getDeclaredField("secondaryLane")
        field.isAccessible = true
        return field.get(session) as? PlaybackLane
    }

    fun injectThermal(stage: StageViewModel, state: PhoneThermalState, scheduleRecovery: Boolean) {
        val clazz = Class.forName("vela.app.stage.StageViewModelKt")
        val method: Method = clazz.getDeclaredMethod(
            "noteThermal",
            StageViewModel::class.java,
            PhoneThermalState::class.java,
            Instant::class.java,
            java.lang.Boolean.TYPE,
        )
        method.isAccessible = true
        onMain(stage) { method.invoke(null, stage, state, Instant.now(), scheduleRecovery) }
    }

    fun laneQualityLimited(stage: StageViewModel): Boolean {
        val field = StageViewModel::class.java.getDeclaredField("laneQuality")
        field.isAccessible = true
        val quality = field.get(stage)
        val limited = quality.javaClass.getDeclaredMethod("isLimited")
        limited.isAccessible = true
        return limited.invoke(quality) as Boolean
    }

    fun laneCapBps(stage: StageViewModel, id: LaneID): Double {
        val clazz = Class.forName("vela.app.stage.StageViewModelKt")
        val method = clazz.getDeclaredMethod("laneCap", StageViewModel::class.java, LaneID::class.java)
        method.isAccessible = true
        return method.invoke(null, stage, id) as Double
    }

    fun degradeSecondary(stage: StageViewModel) {
        val clazz = Class.forName("vela.app.stage.StageViewModelKt")
        val method = clazz.getDeclaredMethod(
            "degradeLane",
            StageViewModel::class.java,
            LaneID::class.java,
        )
        method.isAccessible = true
        onMain(stage) { method.invoke(null, stage, LaneID.SECONDARY) }
    }

    fun audioEnabled(lane: PlaybackLane): Int {
        val outer = lane.javaClass.getDeclaredField("player")
        outer.isAccessible = true
        val delegate = outer.get(lane) as LanePlayer
        return delegate.audioEnabledCount()
    }

    fun playerVolume(lane: PlaybackLane): Float {
        val outer = lane.javaClass.getDeclaredField("player")
        outer.isAccessible = true
        val delegate = outer.get(lane) as LanePlayer
        val inner = delegate.javaClass.getDeclaredField("player")
        inner.isAccessible = true
        val player = inner.get(delegate) as androidx.media3.common.Player
        return player.volume
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

    fun waitUntil(limitMs: Long = 5_000, predicate: () -> Boolean): Boolean {
        val start = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - start < limitMs) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }
}