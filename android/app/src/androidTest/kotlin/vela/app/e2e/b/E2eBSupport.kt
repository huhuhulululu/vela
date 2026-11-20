package vela.app.e2e.b

import android.app.Activity
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import vela.app.MainActivity
import vela.app.infra.Endpoints
import vela.app.infra.Settings
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
            onMain(stage) { stage.dispatch(StageIntent.ConfirmAge) }
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
                onMain(activity) {
                    val lane = activity.session.lane.value
                    lane != null && lane.firstFrameElapsedMs in 0..12_000 && !lane.failed
                }
            },
        )
        assertTrue(
            "副路首帧超时 note=${stage.state.value.secondary?.playbackNote}",
            waitUntil(25_000) {
                onMain(activity) {
                    val secondary = secondaryLane(activity.session)
                    secondary != null && secondary.firstFrameElapsedMs in 0..12_000 && !secondary.failed
                }
            },
        )
    }

    fun secondaryLane(session: AppPlaybackSession): PlaybackLane? = session.secondaryLaneForTest

    fun injectThermal(stage: StageViewModel, state: PhoneThermalState, scheduleRecovery: Boolean) {
        onMain(stage) {
            stage.injectThermalForTest(state, Instant.now(), scheduleRecovery)
        }
    }

    fun laneQualityLimited(stage: StageViewModel): Boolean {
        return onMain(stage) { stage.isQualityLimitedForTest }
    }

    fun laneCapBps(stage: StageViewModel, id: LaneID): Double {
        return onMain(stage) { stage.laneCapForTest(id) }
    }

    fun degradeSecondary(stage: StageViewModel) {
        onMain(stage) { stage.degradeLaneForTest(LaneID.SECONDARY) }
    }

    fun audioEnabled(lane: PlaybackLane): Int {
        return runOnMainSync { lane.audioEnabledCountForTest() }
    }

    fun playerVolume(lane: PlaybackLane): Float {
        return runOnMainSync { lane.playerVolumeForTest() }
    }

    fun renderedOutputBufferCount(lane: PlaybackLane): Int {
        return runOnMainSync { lane.renderedOutputBufferCount() }
    }

    /**
     * 在 [windowMs] 内按 [stepMs] 采样两路是否同时出画；ExoPlayer 读数在主线程完成。
     */
    fun dualSimultaneousRenderRatio(
        activity: MainActivity,
        windowMs: Long,
        stepMs: Long = 500L,
    ): Double {
        var samples = 0
        var bothRendering = 0
        var lastPrimary = -1
        var lastSecondary = -1
        val deadline = android.os.SystemClock.elapsedRealtime() + windowMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            samples += 1
            val sample = onMain(activity) {
                val primary = activity.session.lane.value
                val secondary = secondaryLane(activity.session)
                val primaryRendered = primary?.let { renderedOutputBufferCount(it) } ?: 0
                val secondaryRendered = secondary?.let { renderedOutputBufferCount(it) } ?: 0
                val primaryFailed = primary?.failed == true
                val secondaryFailed = secondary?.failed == true
                intArrayOf(primaryRendered, secondaryRendered, if (primaryFailed) 1 else 0, if (secondaryFailed) 1 else 0)
            }
            val primaryRendered = sample[0]
            val secondaryRendered = sample[1]
            val advancing = (primaryRendered > lastPrimary || lastPrimary < 0) &&
                (secondaryRendered > lastSecondary || lastSecondary < 0) &&
                primaryRendered > 0 && secondaryRendered > 0
            if (advancing && sample[2] == 0 && sample[3] == 0) {
                bothRendering += 1
            }
            lastPrimary = primaryRendered
            lastSecondary = secondaryRendered
            Thread.sleep(stepMs)
        }
        return if (samples == 0) 0.0 else bothRendering.toDouble() / samples.toDouble()
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

    fun <T> runOnMainSync(block: () -> T): T {
        // 已在主线程（例如嵌套在 onMain 里）时直接执行；Instrumentation.runOnMainSync 不允许从主线程调用。
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
