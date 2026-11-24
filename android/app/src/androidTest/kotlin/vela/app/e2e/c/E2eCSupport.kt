package vela.app.e2e.c

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
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
import vela.core.base.model.FeedSource

/**
 * 里程碑 C 仪器测试共用：伪上游、主线程读写、条件等待、双窗起播、dumpsys 音频焦点。
 * 不反射私有字段；播放器与舞台状态只在主线程读。
 */
internal object E2eCSupport {
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    fun upstream(): String {
        val raw = InstrumentationRegistry.getArguments()
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

    fun seedPrefs(
        age: Boolean = true,
        hint: Boolean = true,
        muted: Boolean = true,
        autoEnterPip: Boolean = true,
        dual: Boolean = false,
    ) {
        val target = instrumentation.targetContext
        target.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean(Settings.KEY_AGE, age)
            .putBoolean(Settings.KEY_HINT, hint)
            .putBoolean(Settings.KEY_MUTED, muted)
            .putBoolean(Settings.KEY_PIP, autoEnterPip)
            .putBoolean(Settings.KEY_DUAL, dual)
            .putString(Settings.KEY_SOURCE, FeedSource.popular.name)
            .commit()
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

    fun waitFocusFirstFrame(activity: MainActivity) {
        assertTrue(
            "焦点路首帧超时",
            waitUntil(25_000) {
                onMain(activity) {
                    val lane = activity.session.lane.value
                    lane != null && lane.firstFrameElapsedMs in 0..12_000 && !lane.failed
                }
            },
        )
    }

    fun waitBothFirstFrames(activity: MainActivity, stage: StageViewModel) {
        assertTrue(
            "两路房间不独立",
            waitUntil(5_000) { stage.state.value.lanesDistinct() },
        )
        waitFocusFirstFrame(activity)
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

    fun audioEnabled(lane: PlaybackLane): Int {
        return runOnMainSync { lane.audioEnabledCountForTest() }
    }

    fun playerVolume(lane: PlaybackLane): Float {
        return runOnMainSync { lane.playerVolumeForTest() }
    }

    fun supportsPictureInPicture(activity: MainActivity): Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** 读系统音频焦点栈；只看本包是否在栈里、是否持有 GAIN_TRANSIENT。 */
    fun dumpsysAudioFocus(): String {
        val device = UiDevice.getInstance(instrumentation)
        return device.executeShellCommand("dumpsys audio").orEmpty()
    }

    fun packageInAudioFocusStack(dump: String, packageName: String = "vela.app"): Boolean {
        val lines = dump.lineSequence()
            .dropWhile { !it.contains("Audio Focus", ignoreCase = true) }
            .takeWhile { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() &&
                    !trimmed.startsWith("Audio Focus stack entry count") &&
                    !trimmed.startsWith("Audio dumping") &&
                    !trimmed.startsWith("Audio Policy")
            }
            .toList()
        // 焦点栈条目常见含 pack: / package: / uid 与客户端信息。
        return lines.any { line ->
            line.contains(packageName) &&
                (
                    line.contains("pack:", ignoreCase = true) ||
                        line.contains("package:", ignoreCase = true) ||
                        line.contains("client", ignoreCase = true)
                    )
        }
    }

    fun hasGainTransient(dump: String, packageName: String = "vela.app"): Boolean {
        val lines = dump.lines().filter { it.contains(packageName) }
        return lines.any { line ->
            line.contains("GAIN_TRANSIENT") &&
                !line.contains("GAIN_TRANSIENT_MAY_DUCK") &&
                !line.contains("LOSS")
        } || dump.contains("GAIN_TRANSIENT") && packageInAudioFocusStack(dump, packageName)
    }

    fun exoPlaybackThreads(): List<String> = threadNames().filter { name ->
        name.startsWith("ExoPlayer") && name != "ExoPlayer:Backg"
    }

    fun threadNames(): List<String> {
        val dir = File("/proc/${Process.myPid()}/task")
        return dir.list()?.mapNotNull { tid ->
            runCatching { File(dir, "$tid/comm").readText().trim() }.getOrNull()
        }.orEmpty()
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

    /** 仅在超时失败后采集现场快照，避免在等待开始前就求值字符串拿到旧状态。 */
    fun assertWaitUntil(
        activity: MainActivity,
        message: String,
        limitMs: Long = 5_000,
        extra: (() -> String)? = null,
        predicate: () -> Boolean,
    ) {
        if (waitUntil(limitMs, predicate)) return
        val tail = extra?.invoke()?.takeIf { it.isNotEmpty() }?.let { " | $it" }.orEmpty()
        assertTrue("$message | ${diagnosePip(activity)}$tail", false)
    }

    /** 输出生命周期、画中画标志、两路播放状态与最近画中画事件，便于模拟器失败定位。 */
    fun diagnosePip(activity: MainActivity): String {
        val stage = activity.stage
        val state = stage.state.value
        val coordinator = activity.wiring.pipCoordinator
        val primaryLane = activity.session.lane.value
        val secondaryLane = secondaryLane(activity.session)
        val primaryPlaying = state.lane.playback == vela.app.stage.LanePlayback.Playing ||
            (primaryLane != null && primaryLane.firstFrameElapsedMs >= 0L && !primaryLane.failed)
        val secondaryPlaying = state.secondary?.playback == vela.app.stage.LanePlayback.Playing ||
            (secondaryLane != null && secondaryLane.firstFrameElapsedMs >= 0L && !secondaryLane.failed)
        return buildString {
            append("phase=").append(activity.wiring.phase)
            append(" lifecycle=").append(activity.lifecycle.currentState)
            append(" inPip=").append(activity.isInPictureInPictureMode)
            append(" finishing=").append(activity.isFinishing)
            append(" destroyed=").append(activity.isDestroyed)
            append(" pipState=").append(coordinator?.pictureInPictureState)
            append(" stagePip=").append(stage.pictureInPicture)
            append(" gate=").append(stage.playbackGateOpen)
            append(" allowed=").append(stage.lifecycleEpoch.allowedLane)
            append(" lanes=").append(state.laneCount)
            append(" focus=").append(stage.focusLane)
            append(" primary={playing=").append(primaryPlaying)
            append(",face=").append(state.lane.playback)
            append(",lane=").append(primaryLane != null)
            append("}")
            append(" secondary={playing=").append(secondaryPlaying)
            append(",face=").append(state.secondary?.playback)
            append(",visible=").append(state.secondary?.visible)
            append(",lane=").append(secondaryLane != null)
            append("}")
            append(" events=").append(coordinator?.recentEvents(10).orEmpty())
        }
    }

    fun overrideUpstream() {
        Endpoints.overrideRoot(upstream())
    }
}
