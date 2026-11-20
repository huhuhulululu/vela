package vela.app.pip

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import vela.app.audio.AudioFocusController
import vela.app.stage.AppPlaybackSession
import vela.app.stage.LifecycleWiring
import vela.app.stage.StageViewModel
import vela.app.stage.phoneSnapshots
import vela.app.stage.setPipSolo
import vela.app.stage.syncLaneCountLock
import vela.core.audio.AudioIntent
import vela.core.base.lane.LaneID
import vela.core.base.lane.PlaybackLaneRole
import vela.core.lifecycle.PhoneLifecycle
import vela.core.lifecycle.PhoneLifecycleAction
import vela.core.lifecycle.PhoneScenePhase
import vela.core.lifecycle.PiPPolicy

/**
 * 画中画生命周期桥，语义对齐 iOS [PlatformHooks] 的画中画部分。
 */
class PipBridge(
    private val stage: StageViewModel,
    private val wiring: LifecycleWiring,
    private val session: AppPlaybackSession,
    private val host: PipHost,
    private val audio: AudioFocusController,
    private val videoViewProvider: () -> View?,
) {
    var pictureInPictureState: PiPPolicy.State = PiPPolicy.State.NONE
        private set

    val pictureInPicturePlaying: Boolean
        get() = pictureInPictureState == PiPPolicy.State.ACTIVE ||
            pictureInPictureState == PiPPolicy.State.STARTING

    private var pipContinuing: Boolean = false
    private var pipRestoreRequested: Boolean = false
    private val handler = Handler(Looper.getMainLooper())
    private var fallbackPosted: Runnable? = null

    init {
        host.autoEnter = stage.settings.autoEnterPip
        audio.pictureInPictureActive = false
        host.registerMuteReceiver()
    }

    fun close() {
        cancelFallback()
        host.unregisterMuteReceiver()
    }

    fun setAutoEnter(enabled: Boolean) {
        stage.settings.autoEnterPip = enabled
        host.autoEnter = enabled
    }

    fun toggleFromChrome() {
        if (!host.supported) return
        if (host.inPictureInPicture) {
            deliver(PipEvent.WillStop)
            host.onModeChanged(false)
            deliver(PipEvent.DidStop)
        } else {
            requestEnter()
        }
    }

    fun onUserLeaveHint() {
        if (!host.supported) return
        val input = pictureInPictureInput() ?: return
        if (!input.autoEnterEnabled || !input.focusPlaying) return
        host.onUserLeaveHint(focusPlaying = true)
    }

    fun onPictureInPictureModeChanged(inPip: Boolean) {
        host.onModeChanged(inPip)
        if (inPip) {
            if (pictureInPictureState == PiPPolicy.State.NONE) {
                deliver(PipEvent.WillStart)
            }
            deliver(PipEvent.DidStart)
        } else if (pictureInPictureState != PiPPolicy.State.NONE) {
            deliver(PipEvent.DidStop)
        }
        refreshParams()
    }

    fun beforeBackgroundTransition(): PiPPolicy.Input? {
        if (!host.supported) return null
        return pictureInPictureInput()
    }

    fun afterBackgroundActions(actions: List<PhoneLifecycleAction>) {
        val focus = continuedLane(actions) ?: run {
            pipContinuing = false
            audio.pictureInPictureActive = false
            return
        }
        pipContinuing = true
        audio.pictureInPictureActive = pictureInPicturePlaying
        Log.i(TAG, "pip continue lane=$focus state=$pictureInPictureState")
        if (pictureInPictureState != PiPPolicy.State.ACTIVE) {
            armFallback("timeout")
            if (!host.inPictureInPicture) {
                requestEnter()
            }
        }
        refreshParams()
    }

    fun onReturningToForeground() {
        cancelFallback()
        pipContinuing = false
        pipRestoreRequested = false
        if (pictureInPicturePlaying) {
            deliver(PipEvent.WillStop)
            host.onModeChanged(false)
            deliver(PipEvent.DidStop)
        }
        if (wiring.phase == PhoneScenePhase.ACTIVE && !pictureInPicturePlaying) {
            stage.setPipSolo(false)
        }
    }

    fun deliver(event: PipEvent) {
        when (event) {
            PipEvent.WillStart -> {
                pictureInPictureState = PiPPolicy.State.STARTING
                stage.syncLaneCountLock(locked = true)
                stage.setPipSolo(true)
                keepMutedSessionIfNeeded()
            }
            PipEvent.DidStart -> {
                pictureInPictureState = PiPPolicy.State.ACTIVE
                stage.syncLaneCountLock(locked = true)
                cancelFallback()
                audio.pictureInPictureActive = true
                if (!pipContinuing && wiring.phase == PhoneScenePhase.BACKGROUND) {
                    Log.i(TAG, "pip late-start close")
                    host.onModeChanged(false)
                    finishLateEnd()
                }
            }
            is PipEvent.Failed -> {
                pictureInPictureState = PiPPolicy.State.NONE
                stage.syncLaneCountLock(locked = false)
                stage.setPipSolo(false)
                audio.pictureInPictureActive = false
                Log.i(TAG, "pip failed error=${event.message}")
                if (pipContinuing && wiring.phase == PhoneScenePhase.BACKGROUND) {
                    fallbackStop("failed")
                } else {
                    finishLateEnd()
                }
            }
            PipEvent.WillStop -> {
                pictureInPictureState = PiPPolicy.State.STOPPING
                stage.syncLaneCountLock(locked = true)
            }
            PipEvent.DidStop -> {
                pictureInPictureState = PiPPolicy.State.NONE
                stage.syncLaneCountLock(locked = false)
                stage.setPipSolo(false)
                audio.pictureInPictureActive = false
                if (!pipContinuing || wiring.phase != PhoneScenePhase.BACKGROUND) {
                    finishLateEnd()
                    return
                }
                if (pipRestoreRequested) {
                    armFallback("restore-timeout")
                } else {
                    stopAllAfterPictureInPicture()
                }
            }
            PipEvent.RestoreRequested -> pipRestoreRequested = true
        }
        syncFocusLock()
        refreshParams()
    }

    fun refreshParams() {
        if (!host.supported) return
        val lane = session.laneForRole(stage.focusLane.role) ?: return
        val aspect = PipAspect.clamp(lane.videoWidth, lane.videoHeight)
        val rect = host.sourceRectOf(videoViewProvider())
        host.updateParams(
            aspect = aspect,
            sourceRect = rect,
            muted = stage.muted,
            actionsEnabled = host.inPictureInPicture || pictureInPicturePlaying,
        )
    }

    private fun requestEnter() {
        val lane = session.laneForRole(stage.focusLane.role) ?: return
        val aspect = PipAspect.clamp(lane.videoWidth, lane.videoHeight)
        val rect = host.sourceRectOf(videoViewProvider())
        if (host.enter(aspect, rect, stage.muted)) {
            deliver(PipEvent.WillStart)
        }
    }

    private fun pictureInPictureInput(): PiPPolicy.Input? {
        if (!host.supported) return null
        val focus = stage.focusLane.role
        val playing = session.isLaneAdvancing(focus) &&
            stage.snapshotAllowsPlayback(focus)
        val canAuto = host.autoEnter && session.laneForRole(focus) != null
        return PiPPolicy.Input(
            platform = PiPPolicy.Platform.PHONE,
            scene = wiring.phase,
            pictureInPicture = pictureInPictureState,
            autoEnterEnabled = canAuto,
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

    private fun continuedLane(actions: List<PhoneLifecycleAction>): PlaybackLaneRole? {
        for (action in actions) {
            if (action is PhoneLifecycleAction.ContinueLane) return action.role
        }
        return null
    }

    private fun armFallback(reason: String) {
        cancelFallback()
        val task = Runnable {
            fallbackPosted = null
            if (!pipContinuing) return@Runnable
            if (!PipFallback.shouldStop(wiring.phase, pictureInPictureState)) return@Runnable
            fallbackStop(reason)
        }
        fallbackPosted = task
        handler.postDelayed(task, PipFallback.TIMEOUT_MS)
    }

    private fun cancelFallback() {
        fallbackPosted?.let(handler::removeCallbacks)
        fallbackPosted = null
    }

    private fun fallbackStop(reason: String) {
        Log.i(TAG, "pip fallback-stop reason=$reason")
        if (pictureInPictureState != PiPPolicy.State.NONE) {
            host.onModeChanged(false)
        }
        pictureInPictureState = PiPPolicy.State.NONE
        stage.syncLaneCountLock(locked = false)
        stage.setPipSolo(false)
        audio.pictureInPictureActive = false
        pipContinuing = false
        pipRestoreRequested = false
        stopAllAfterPictureInPicture()
    }

    private fun stopAllAfterPictureInPicture() {
        cancelFallback()
        pipContinuing = false
        pipRestoreRequested = false
        if (wiring.phase != PhoneScenePhase.BACKGROUND) return
        val pip = pictureInPictureInput() ?: return
        val ended = pip.copy(pictureInPicture = PiPPolicy.State.STOPPING)
        val actions = PhoneLifecycle.actions(
            from = PhoneScenePhase.BACKGROUND,
            to = PhoneScenePhase.BACKGROUND,
            wasSuspended = wiring.wasSuspended,
            lanes = stage.phoneSnapshots(),
            resumeIntent = stage.notedResume,
            pip = ended,
        )
        wiring.applyPictureInPictureStop(actions)
        Log.i(TAG, "pip background stop-all")
    }

    private fun finishLateEnd() {
        if (wiring.phase == PhoneScenePhase.BACKGROUND) {
            audio.execute(AudioIntent.Deactivate(vela.core.audio.DeactivationReason.BACKGROUND))
        }
    }

    private fun keepMutedSessionIfNeeded() {
        if (!pipContinuing && wiring.phase == PhoneScenePhase.BACKGROUND) return
        if (!stage.muted) return
        val output = PiPPolicy.decide(
            pictureInPictureInput()?.copy(scene = PhoneScenePhase.BACKGROUND) ?: return,
        ).audio
        audio.applyPip(output)
    }

    private fun syncFocusLock() {
        val locked = pictureInPicturePlaying
        if (stage.focusLocked != locked) {
            stage.focusLocked = locked
            stage.publish()
        }
    }

    companion object {
        private const val TAG = "vela.pip"
    }
}

private fun StageViewModel.openRoles(): List<PlaybackLaneRole> {
    val roles = ArrayList<PlaybackLaneRole>()
    roles.add(PlaybackLaneRole.PRIMARY)
    if (laneCount >= 2) roles.add(PlaybackLaneRole.SECONDARY)
    return roles
}

internal fun StageViewModel.snapshotAllowsPlayback(role: PlaybackLaneRole): Boolean {
    val id = when (role) {
        PlaybackLaneRole.PRIMARY -> LaneID.PRIMARY
        PlaybackLaneRole.SECONDARY -> LaneID.SECONDARY
        PlaybackLaneRole.TERTIARY -> LaneID.TERTIARY
    }
    val snap = phoneSnapshots().firstOrNull { it.role == role } ?: return false
    return PhoneLifecycle.shouldPlay(snap)
}