package vela.app.pip

import android.os.Handler
import android.os.Looper
import android.util.Log
import vela.app.audio.AudioFocusController
import vela.app.stage.LanePlayback
import vela.app.stage.LifecycleWiring
import vela.app.stage.StageViewModel
import vela.app.stage.openRoles
import vela.core.base.lane.LaneID
import vela.core.base.lane.PlaybackLaneRole
import vela.core.lifecycle.PhoneLifecycleAction
import vela.core.lifecycle.PhoneScenePhase
import vela.core.lifecycle.PiPPolicy

/**
 * 画中画状态机与 3 秒回退。语义对齐 iOS `PlatformHooks` 的画中画段落：
 * 只给焦点路；starting/active 锁焦点与路数；超时或失败则全停。
 */
class PiPCoordinator(
    private val stage: StageViewModel,
    private val wiring: LifecycleWiring,
    private val audio: () -> AudioFocusController?,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    var pip: PictureInPictureControlling? = null

    /** 焦点路播放器是不是真在走。测试可替换。 */
    var playerAdvancing: (LaneID) -> Boolean = { true }

    /** 回退等待；测试缩短。单位秒。 */
    var fallbackDelaySeconds: Double = PiPFallback.TIMEOUT_SECONDS

    var pictureInPictureState: PiPPolicy.State = PiPPolicy.State.NONE
        private set

    val pictureInPicturePlaying: Boolean
        get() = pictureInPictureState == PiPPolicy.State.ACTIVE ||
            pictureInPictureState == PiPPolicy.State.STARTING

    private var continuing = false
    private var restoreRequested = false
    private var fallbackRunnable: Runnable? = null

    fun syncAudioHook() {
        audio()?.pictureInPictureActive = pictureInPicturePlaying
    }

    fun setAutoEnter(enabled: Boolean) {
        stage.settings.autoEnterPip = enabled
        pip?.autoEnter = enabled
        stage.autoEnterPip = enabled
        stage.publishPipFlags()
        Log.i(TAG, "pip auto-enter ${if (enabled) "on" else "off"}")
    }

    /** 进后台时交给 [PhoneLifecycle] 的输入。没有控制器时为 null，照旧全停。 */
    fun pictureInPictureInput(scene: PhoneScenePhase = PhoneScenePhase.BACKGROUND): PiPPolicy.Input? {
        val controller = pip ?: return null
        val focus = stage.focusLane.role
        val playing = focusIsPlaying()
        return PiPPolicy.Input(
            platform = PiPPolicy.Platform.PHONE,
            scene = scene,
            pictureInPicture = pictureInPictureState,
            autoEnterEnabled = controller.canAutoEnter,
            muted = stage.muted,
            focus = focus,
            lanes = stage.openRoles(),
            focusPlaying = playing,
            bossKey = false,
            lockScreenOrSystemSleep = false,
            windowMinimized = false,
            windowOccluded = false,
        )
    }

    fun onContinued(actions: List<PhoneLifecycleAction>) {
        val focus = continuedLane(actions) ?: return
        continuing = true
        Log.i(TAG, "pip continue lane=$focus state=$pictureInPictureState")
        if (pictureInPictureState != PiPPolicy.State.ACTIVE) {
            armFallback("timeout")
        }
    }

    fun onReturningToForeground() {
        cancelFallback()
        continuing = false
        restoreRequested = false
        if (pictureInPicturePlaying) {
            pip?.stop()
        }
        if (!pictureInPicturePlaying) {
            stage.setPipSolo(false)
        }
    }

    fun onForegroundIdle() {
        if (!pictureInPicturePlaying) {
            stage.setPipSolo(false)
        }
    }

    fun deliver(event: PiPEvent) {
        when (event) {
            PiPEvent.WillStart -> {
                pictureInPictureState = PiPPolicy.State.STARTING
                stage.syncLaneCountLock(true)
                stage.setPipSolo(true)
                stage.focusLocked = true
                stage.publishPipFlags()
                syncAudioHook()
                keepMutedSession()
            }
            PiPEvent.DidStart -> {
                pictureInPictureState = PiPPolicy.State.ACTIVE
                stage.syncLaneCountLock(true)
                stage.focusLocked = true
                stage.publishPipFlags()
                syncAudioHook()
                Log.i(TAG, "pip started")
                if (continuing) {
                    cancelFallback()
                } else if (wiring.phase == PhoneScenePhase.BACKGROUND) {
                    Log.i(TAG, "pip late-start closed")
                    pip?.abort()
                }
            }
            is PiPEvent.Failed -> {
                pictureInPictureState = PiPPolicy.State.NONE
                stage.syncLaneCountLock(false)
                stage.setPipSolo(false)
                stage.focusLocked = false
                stage.publishPipFlags()
                syncAudioHook()
                Log.i(TAG, "pip failed error=${event.message}")
                if (continuing && wiring.phase == PhoneScenePhase.BACKGROUND) {
                    fallbackStop("failed")
                }
            }
            PiPEvent.WillStop -> {
                pictureInPictureState = PiPPolicy.State.STOPPING
                stage.syncLaneCountLock(true)
                stage.publishPipFlags()
                syncAudioHook()
            }
            PiPEvent.DidStop -> {
                pictureInPictureState = PiPPolicy.State.NONE
                stage.syncLaneCountLock(false)
                stage.setPipSolo(false)
                stage.focusLocked = false
                stage.publishPipFlags()
                syncAudioHook()
                Log.i(TAG, "pip stopped restore=$restoreRequested")
                if (!continuing || wiring.phase != PhoneScenePhase.BACKGROUND) {
                    return
                }
                if (restoreRequested) {
                    armFallback("restore-timeout")
                } else {
                    stopAllAfterPictureInPicture()
                }
            }
            PiPEvent.RestoreRequested -> {
                restoreRequested = true
            }
        }
    }

    fun enterFromUi() {
        pip?.enter()
    }

    fun toggleFromUi() {
        if (pictureInPicturePlaying) {
            pip?.stop()
        } else {
            pip?.enter()
        }
    }

    private fun focusIsPlaying(): Boolean {
        val id = stage.focusLane
        val face = when (id) {
            LaneID.PRIMARY -> stage.laneFace
            LaneID.SECONDARY -> stage.secondaryFace
            LaneID.TERTIARY -> stage.tertiaryFace
        } ?: return false
        if (face.playback != LanePlayback.Playing) return false
        if (face.playingUsername.isNullOrEmpty()) return false
        if (face.manuallyPaused) return false
        return playerAdvancing(id)
    }

    private fun keepMutedSession() {
        if (!continuing && wiring.phase == PhoneScenePhase.BACKGROUND) {
            Log.i(TAG, "pip late-start session untouched")
            return
        }
        if (!stage.muted) return
        val controller = audio() ?: return
        // 静音画中画：不申请焦点，只同步钩子状态。
        controller.pictureInPictureActive = true
    }

    private fun armFallback(reason: String) {
        cancelFallback()
        val delayMs = (fallbackDelaySeconds.coerceAtLeast(0.0) * 1000.0).toLong()
        val runnable = Runnable {
            fallbackRunnable = null
            if (!continuing) return@Runnable
            if (!PiPFallback.shouldStop(wiring.phase, pictureInPictureState)) return@Runnable
            fallbackStop(reason)
        }
        fallbackRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelFallback() {
        val pending = fallbackRunnable ?: return
        fallbackRunnable = null
        mainHandler.removeCallbacks(pending)
    }

    private fun fallbackStop(reason: String) {
        Log.i(TAG, "pip fallback-stop reason=$reason")
        if (pictureInPictureState != PiPPolicy.State.NONE) {
            pip?.abort()
        }
        pictureInPictureState = PiPPolicy.State.NONE
        stage.syncLaneCountLock(false)
        stage.setPipSolo(false)
        stage.focusLocked = false
        stage.publishPipFlags()
        syncAudioHook()
        stopAllAfterPictureInPicture()
    }

    private fun stopAllAfterPictureInPicture() {
        cancelFallback()
        continuing = false
        restoreRequested = false
        if (wiring.phase != PhoneScenePhase.BACKGROUND) return
        val input = pictureInPictureInput() ?: return
        val ended = input.copy(pictureInPicture = PiPPolicy.State.STOPPING)
        wiring.applyPictureInPictureStop(ended)
        Log.i(TAG, "pip background stop-all")
    }

    companion object {
        private const val TAG = "vela.pip"

        fun continuedLane(actions: List<PhoneLifecycleAction>): PlaybackLaneRole? {
            for (action in actions) {
                if (action is PhoneLifecycleAction.ContinueLane) return action.role
            }
            return null
        }
    }
}
