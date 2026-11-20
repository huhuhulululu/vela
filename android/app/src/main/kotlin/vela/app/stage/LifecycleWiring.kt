package vela.app.stage

import android.media.AudioManager
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vela.app.audio.AudioFocusController
import vela.app.lane.PlaybackLane
import vela.app.playback.LaneHub
import vela.core.audio.AudioFocusPlan
import vela.core.audio.AudioIntent
import vela.core.audio.DeactivationReason
import vela.core.base.lane.LaneEndReason
import vela.core.base.lane.PlaybackLaneRole
import vela.core.base.model.Room
import vela.core.lifecycle.LifecycleEpoch
import vela.core.lifecycle.PhoneLaneActivity
import vela.core.lifecycle.PhoneLaneSnapshot
import vela.core.lifecycle.PhoneLifecycle
import vela.core.lifecycle.PhoneLifecycleAction
import vela.core.lifecycle.PhoneResumeIntent
import vela.core.lifecycle.PhoneScenePhase

/**
 * 前后台接线。动作顺序只来自 [PhoneLifecycle.actions]，这里不重排。
 *
 * 没有画中画时（本包不传 `pip`，画中画留给 PiP1）进后台的顺序是：
 * 关闸门、作废代次、结算停留、刷盘、释放各路、放弃音频焦点、停监听并销毁侧车、记下恢复意图。
 * 回到 active 且曾经挂起过：恢复代次，重做恢复意图，再按冻结裁决 7 逐路处理。
 * inactive 只停停留计时，画面继续播。
 */
class LifecycleWiring(
    private val stage: StageViewModel,
    private val session: LifecycleSession,
) {
    var phase: PhoneScenePhase = PhoneScenePhase.INACTIVE
        private set

    var wasSuspended: Boolean = false
        private set

    private val steps = ArrayList<String>()

    fun trace(): List<String> = steps.toList()

    val epoch: ULong
        get() = stage.lifecycleEpoch.epoch

    val epochSuspended: Boolean
        get() = stage.lifecycleEpoch.suspended

    val gateOpen: Boolean
        get() = stage.playbackGateOpen

    val lastSettlementUsername: String?
        get() = stage.lastSettlementUsername

    val flushCount: Int
        get() = stage.flushCount

    fun setPrimaryVisible(visible: Boolean) {
        stage.primaryVisible = visible
    }

    fun moveTo(next: PhoneScenePhase) {
        if (next == phase) return
        // 只在进后台时捕获。恢复时任务已被取消，再捕获会把记下的意图盖成 null。
        val intent = if (next == PhoneScenePhase.BACKGROUND) {
            stage.captureResumeIntent()
        } else {
            stage.notedResume
        }
        val lanes = stage.phoneSnapshots()
        // PiP1 才会传入画中画输入。这里恒为 null：回桌面一律全停。
        val actions = PhoneLifecycle.actions(
            from = phase,
            to = next,
            wasSuspended = wasSuspended,
            lanes = lanes,
            resumeIntent = intent,
            pip = null,
        )
        val previous = phase
        phase = next
        if (next == PhoneScenePhase.BACKGROUND && previous != PhoneScenePhase.BACKGROUND && actions.isNotEmpty()) {
            wasSuspended = true
        }
        for (action in actions) apply(action)
        if (next == PhoneScenePhase.ACTIVE) {
            // 冷启动没有 EpochResume。只在会话还没初始化时套用保存的静音偏好并登记拔耳机。
            // 没挂起过的 INACTIVE→ACTIVE 也会走到这里；会话已在时不能再 applyStored，
            // 否则会按保存的出声重新申请焦点，把失焦后的运行时静音冲掉。
            if (actions.none { it == PhoneLifecycleAction.EpochResume }) {
                session.prepareAudio()
            }
            wasSuspended = false
        }
        stage.syncSceneAttentive(PhoneLifecycle.attentive(phase))
        Log.i(TAG, "phase=$phase actions=${actions.size}")
    }

    private fun apply(action: PhoneLifecycleAction) {
        when (action) {
            PhoneLifecycleAction.GateOff -> {
                steps.add("gate-off")
                stage.playbackGateOpen = false
                stage.cancelPlaybackWork()
                session.closeGate()
            }
            PhoneLifecycleAction.EpochSuspend -> {
                steps.add("epoch-suspend")
                stage.invalidateGenerations()
                stage.cancelSharedWork()
                session.onEpoch(stage.lifecycleEpoch)
            }
            is PhoneLifecycleAction.SettleDwell -> {
                steps.add("settle-dwell")
                stage.settleBackground(action.reason)
            }
            PhoneLifecycleAction.FlushSavers -> {
                steps.add("flush-savers")
                stage.flushSavers()
            }
            PhoneLifecycleAction.SuspendLanes -> {
                steps.add("suspend-lanes")
                session.suspendLanes()
            }
            PhoneLifecycleAction.AudioDeactivate -> {
                steps.add("audio-deactivate")
                session.deactivateAudio()
            }
            PhoneLifecycleAction.StopListener -> {
                steps.add("stop-listener")
                session.stopListener()
                steps.add("destroy-sidecar")
                stage.destroySidecar()
            }
            is PhoneLifecycleAction.NoteResumeIntent -> {
                steps.add("note-resume")
                stage.notedResume = action.intent
            }
            PhoneLifecycleAction.EpochResume -> {
                steps.add("epoch-resume")
                stage.resumeGenerations()
                stage.reopenDwell()
                session.onEpoch(stage.lifecycleEpoch)
                session.prepareAudio()
                session.restoreUi()
            }
            is PhoneLifecycleAction.RedoResumeIntent -> {
                stage.notedResume = null
                when (action.intent) {
                    PhoneResumeIntent.RELOAD -> {
                        steps.add("redo-reload")
                        stage.redoReload()
                    }
                    PhoneResumeIntent.SEARCH -> {
                        steps.add("redo-search")
                        stage.redoSearch()
                    }
                }
            }
            is PhoneLifecycleAction.RequestPlay -> {
                steps.add("request-play")
                when (action.role) {
                    PlaybackLaneRole.PRIMARY -> {
                        stage.primaryActivity = action.activity
                        session.ensureLane()
                        stage.requestPlay()
                    }
                    PlaybackLaneRole.SECONDARY -> {
                        stage.secondaryActivity = action.activity
                        stage.ensureSecondary?.invoke()
                        stage.requestPlay(vela.core.base.lane.LaneID.SECONDARY)
                    }
                    PlaybackLaneRole.TERTIARY -> steps.add("request-play-tertiary")
                }
            }
            is PhoneLifecycleAction.KeepFailure -> steps.add("keep-failure")
            is PhoneLifecycleAction.StayPaused -> steps.add("stay-paused")
            is PhoneLifecycleAction.StayReleased -> steps.add("stay-released")
            PhoneLifecycleAction.SuspendSharedTasks -> {
                steps.add("suspend-shared")
                stage.cancelSharedWork()
            }
            is PhoneLifecycleAction.AllowLaneRequests -> {
                steps.add("allow-lane")
                stage.lifecycleEpoch.suspend(action.role)
                session.onEpoch(stage.lifecycleEpoch)
            }
            is PhoneLifecycleAction.ReleaseLane -> {
                steps.add("release-lane")
                session.suspendLanes()
            }
            is PhoneLifecycleAction.ContinueLane -> steps.add("continue-lane")
            is PhoneLifecycleAction.RetainRelaySource -> steps.add("retain-relay")
        }
    }

    companion object {
        private const val TAG = "vela.lifecycle"
    }
}

/** 播放器、焦点和中继监听。纯界面状态留在 [StageViewModel]。 */
interface LifecycleSession {
    fun closeGate()
    fun onEpoch(epoch: LifecycleEpoch)
    fun suspendLanes()
    fun deactivateAudio()
    fun prepareAudio()
    fun stopListener()
    fun ensureLane()
    fun restoreUi()
}

/**
 * 正式会话：进后台释放 ExoPlayer（播放线程随之退出），销毁侧车网页，放弃音频焦点。
 * 组合在释放播放器之前拆掉，避免界面还去碰已经 release 的播放器。
 */
class AppPlaybackSession(
    private val activity: ComponentActivity,
    private val stage: StageViewModel,
    private val hub: LaneHub = LaneHub(),
    private val disposeComposition: () -> Unit,
    private val restoreComposition: () -> Unit,
) : LifecycleSession {
    private val mutableLane = MutableStateFlow<PlaybackLane?>(null)
    val lane: StateFlow<PlaybackLane?> = mutableLane.asStateFlow()
    private var secondaryLane: PlaybackLane? = null

    var webHost: FrameLayout? = null
    private var observed: PlaybackLane? = null
    private var observedSecondary: PlaybackLane? = null
    private var audioStarted: Boolean = false
    private val lanesByRole = HashMap<PlaybackLaneRole, PlaybackLane>()

    private val audio: AudioFocusController = AudioFocusController(
        context = activity,
        audioManager = activity.getSystemService(AudioManager::class.java),
        sinks = StageAudioSinks(stage, { mutableLane.value }, lanesByRole),
    ).also { controller ->
        controller.focus = PlaybackLaneRole.PRIMARY
        controller.lanes = listOf(PlaybackLaneRole.PRIMARY)
    }

    init {
        stage.ensureSecondary = { ensureSecondary() }
        stage.releaseSecondaryLane = { releaseSecondaryLane() }
        stage.onLaneFocusChanged = { id ->
            audio.focus = id.role
            audio.lanes = stage.openRoles()
            if (!stage.muted) audio.execute(AudioIntent.Unmute(id.role))
        }
        stage.onLanesChanged = {
            audio.focus = stage.focusLane.role
            audio.lanes = stage.openRoles()
        }
    }

    fun onMuteChanged(muted: Boolean) {
        audio.focus = stage.focusLane.role
        audio.lanes = stage.openRoles()
        if (muted) {
            audio.execute(AudioIntent.Deactivate(DeactivationReason.MUTE))
        } else {
            audio.execute(AudioIntent.Unmute(stage.focusLane.role))
        }
    }

    override fun closeGate() {
        mutableLane.value?.setAllowed(false)
        secondaryLane?.setAllowed(false)
    }

    override fun onEpoch(epoch: LifecycleEpoch) {
        hub.allowStart = epoch.allowStart
        if (epoch.allowStart) hub.start()
    }

    override fun suspendLanes() {
        stopObserving()
        disposeComposition()
        val current = mutableLane.value
        val secondary = secondaryLane
        mutableLane.value = null
        secondaryLane = null
        lanesByRole.clear()
        if (current == null && secondary == null) return
        stage.playerReleased = true
        if (current != null) {
            current.suspendNow()
            current.release()
        }
        if (secondary != null) {
            secondary.suspendNow()
            secondary.release()
        }
    }

    override fun deactivateAudio() {
        audio.stop()
        audioStarted = false
    }

    override fun prepareAudio() {
        if (audioStarted) return
        audio.start()
        audioStarted = true
        audio.applyStored(stage.settings.muted)
    }

    override fun stopListener() {
        hub.stopListener()
    }

    override fun ensureLane() {
        if (!audioStarted) prepareAudio()
        if (mutableLane.value != null) return
        if (hub.listeningPort == 0 && hub.allowStart) hub.start()
        val created = PlaybackLane.create(activity, hub = hub)
        stage.playerReleased = false
        mutableLane.value = created
        lanesByRole[PlaybackLaneRole.PRIMARY] = created
        val host = webHost
        if (host != null) stage.bindLane(created, host) else stage.bindLane(created, null)
        observe(created)
    }

    fun ensureSecondary() {
        if (secondaryLane != null) return
        if (hub.listeningPort == 0 && hub.allowStart) hub.start()
        val created = PlaybackLane.create(activity, id = vela.core.base.lane.LaneID.SECONDARY, hub = hub)
        stage.playerReleased = false
        secondaryLane = created
        lanesByRole[PlaybackLaneRole.SECONDARY] = created
        stage.bindLane(created, null)
        observeSecondary(created)
    }

    /** 用户关掉双窗或第二路退出时，完整释放副路播放器与会话引用。 */
    fun releaseSecondaryLane() {
        val secondary = secondaryLane ?: return
        observedSecondary?.onPlaybackFace = null
        observedSecondary = null
        secondaryLane = null
        lanesByRole.remove(PlaybackLaneRole.SECONDARY)
        secondary.release()
        audio.focus = stage.focusLane.role
        audio.lanes = stage.openRoles()
    }

    override fun restoreUi() {
        restoreComposition()
    }

    fun releaseLeftovers() {
        stopObserving()
        val current = mutableLane.value
        val secondary = secondaryLane
        mutableLane.value = null
        secondaryLane = null
        lanesByRole.clear()
        if (current != null || secondary != null) {
            stage.playerReleased = true
            current?.release()
            secondary?.release()
        }
        if (audioStarted) {
            audio.stop()
            audioStarted = false
        }
        hub.stopListener()
    }

    private fun observe(lane: PlaybackLane) {
        stopObserving()
        observed = lane
        lane.onPlaybackFace = { stage.syncFromPlayback(lane) }
        stage.syncFromPlayback(lane)
    }

    private fun observeSecondary(lane: PlaybackLane) {
        observedSecondary?.onPlaybackFace = null
        observedSecondary = lane
        lane.onPlaybackFace = { stage.syncFromPlayback(lane) }
        stage.syncFromPlayback(lane)
    }

    private fun stopObserving() {
        observed?.onPlaybackFace = null
        observed = null
        observedSecondary?.onPlaybackFace = null
        observedSecondary = null
    }

    private class StageAudioSinks(
        private val stage: StageViewModel,
        private val lane: () -> PlaybackLane?,
        private val byRole: Map<PlaybackLaneRole, PlaybackLane>,
    ) : AudioFocusController.Sinks {
        override fun muteLane(role: PlaybackLaneRole) {
            (byRole[role] ?: if (role == PlaybackLaneRole.PRIMARY) lane() else null)
                ?.setVolume(0.0, true)
        }

        override fun muteAll() {
            if (byRole.isEmpty()) {
                lane()?.setVolume(0.0, true)
            } else {
                byRole.values.forEach { it.setVolume(0.0, true) }
            }
        }

        override fun unmuteLane(role: PlaybackLaneRole) {
            (byRole[role] ?: if (role == PlaybackLaneRole.PRIMARY) lane() else null)
                ?.setVolume(stage.volume, false)
        }

        override fun detachLane(role: PlaybackLaneRole) {
            // 没开的那一路不存在时，不要把另一路的声音关掉。
            byRole[role]?.setVolume(0.0, true)
        }

        override fun writeMuted(muted: Boolean) {
            stage.applyMute(muted, writeSettings = true)
        }

        override fun syncRuntimeMuted(muted: Boolean) {
            stage.applyMute(muted, writeSettings = false)
        }

        override fun showNotice(text: String) {
            val message = text.ifEmpty { AudioFocusPlan.FAILURE_NOTICE }
            stage.showNotice(message)
        }

        override fun pausePlayback() = Unit
    }

}

internal fun StageViewModel.phoneSnapshots(): List<PhoneLaneSnapshot> {
    val lanes = ArrayList<PhoneLaneSnapshot>()
    lanes.add(
        PhoneLaneSnapshot(
            role = PlaybackLaneRole.PRIMARY,
            username = feed.currentUsername,
            manuallyPaused = laneFace.manuallyPaused,
            visible = primaryVisible,
            failed = laneFace.playback == LanePlayback.Failed,
            activity = primaryActivity,
        ),
    )
    if (laneCount >= 2 && secondaryCursor != null) {
        lanes.add(
            PhoneLaneSnapshot(
                role = PlaybackLaneRole.SECONDARY,
                username = secondaryCursor?.username,
                manuallyPaused = secondarySnapshotPaused(),
                visible = secondarySnapshotVisible(),
                failed = secondarySnapshotFailed(),
                activity = secondaryActivity,
            ),
        )
    }
    return lanes
}

internal fun StageViewModel.settleBackground(reason: LaneEndReason) {
    settleAllLanes(reason)
}

internal fun StageViewModel.flushSavers() {
    tasteSaver.flush()
    curationSaver.flush()
    watchLedgerSaver.flush()
    flushCount += 1
}

internal fun StageViewModel.reopenDwell() {
    reopenDwellLanes()
}

internal fun StageViewModel.syncSceneAttentive(attentive: Boolean) {
    sceneAttentive = attentive
    if (!attentive) {
        freezeDwells()
    } else {
        updateDwellActive()
    }
}

internal fun StageViewModel.destroySidecar() {
    sidecarHolder?.close()
    sidecarHolder = null
    sidecarUntil = null
}

internal fun StageViewModel.redoReload() {
    track { reload() }
}

internal fun StageViewModel.redoSearch() {
    val keyword = (pendingSearchKeyword ?: siteSearch ?: searchText).stageTrim()
    if (keyword.isEmpty()) return
    startSiteSearch(keyword, Room.direct(keyword))
}
