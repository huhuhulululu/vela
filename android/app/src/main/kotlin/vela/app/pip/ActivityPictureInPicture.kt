package vela.app.pip

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import vela.app.R
import vela.app.stage.StageIntent
import vela.app.stage.StageUiState
import vela.app.stage.StageViewModel
import vela.core.base.lane.LaneID

/**
 * Activity 侧画中画：比例夹紧、[RemoteAction] 切换静音、
 * API 31+ [PictureInPictureParams.setAutoEnterEnabled]，26–30 用 [onUserLeaveHint] 兜底。
 */
class ActivityPictureInPicture(
    private val activity: ComponentActivity,
    private val stage: StageViewModel,
    private val onEvent: (PiPEvent) -> Unit,
) : PictureInPictureControlling {
    private data class ParamsKey(
        val focusLane: LaneID,
        val autoEnterEnabled: Boolean,
        val muted: Boolean,
    )

    private var receiverRegistered = false
    private var bound = false
    private var inPip = false
    private var pendingRestore = false
    private var notedWidth = 0.0
    private var notedHeight = 0.0
    private var stateJob: Job? = null
    private var observedKey: ParamsKey? = null

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            pendingRestore = false
            if (!bound) return
            onStageState(stage.state.value)
        }

        override fun onStop(owner: LifecycleOwner) {
            if (!bound) return
            if (pendingRestore) {
                pendingRestore = false
                onEvent(PiPEvent.Failed("dismissed"))
                return
            }
            if (!inPip) return
            // Android 处于可见画中画时停留在 STARTED（paused）；一旦进入 onStop 即表示小窗已关闭、锁屏或正在销毁，
            // 此时 !isFinishing 且 isInPictureInPictureMode 可能仍为 true（onStop 先于 modeChanged(false) 或不发该回调），必须立即全停。
            inPip = false
            pendingRestore = false
            onEvent(PiPEvent.WillStop)
            onEvent(PiPEvent.DidStop)
        }
    }

    override var autoEnter: Boolean = stage.settings.autoEnterPip
        set(value) {
            field = value
            observedKey = currentParamsKey(stage.state.value)
            updateParams(enter = false)
        }

    override val canAutoEnter: Boolean
        get() = autoEnter && supported && bound

    val supported: Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun attach() {
        if (!supported || bound) return
        bound = true
        registerMuteReceiver()
        activity.lifecycle.addObserver(lifecycleObserver)
        observedKey = currentParamsKey(stage.state.value)
        updateParams(enter = false)
        stateJob?.cancel()
        stateJob = activity.lifecycleScope.launch {
            stage.state.collect { state -> onStageState(state) }
        }
        Log.i(TAG, "pip attach")
    }

    fun detach() {
        if (!bound) return
        val wasInPip = inPip
        val wasPendingRestore = pendingRestore
        bound = false
        inPip = false
        pendingRestore = false
        activity.lifecycle.removeObserver(lifecycleObserver)
        stateJob?.cancel()
        stateJob = null
        observedKey = null
        unregisterMuteReceiver()
        if (wasInPip) {
            onEvent(PiPEvent.WillStop)
            onEvent(PiPEvent.DidStop)
        } else if (wasPendingRestore) {
            onEvent(PiPEvent.Failed("detached"))
        }
        Log.i(TAG, "pip detach")
    }

    override fun enter() {
        if (!supported || activity.isInPictureInPictureMode) return
        val params = buildParams()
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                activity.enterPictureInPictureMode(params)
            }
        } catch (error: IllegalStateException) {
            onEvent(PiPEvent.Failed(error.message ?: "enter-failed"))
        } catch (error: IllegalArgumentException) {
            onEvent(PiPEvent.Failed(error.message ?: "bad-aspect"))
        }
    }

    override fun stop() {
        // 回前台时系统会先展开小窗；这里不 finish，避免把整页拆掉。
    }

    override fun abort() {
        // 3 秒回退或迟到启动：关掉可能留下的黑小窗。
        if (activity.isInPictureInPictureMode && !activity.isFinishing) {
            activity.finish()
        }
    }

    /** Activity.onUserLeaveHint：API 31 以下自动进入的兜底。 */
    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT >= 31) return
        if (!canAutoEnter) return
        if (!focusLooksPlayable()) return
        enter()
    }

    fun onPictureInPictureModeChanged(inPip: Boolean) {
        if (inPip) {
            if (this.inPip) return
            this.inPip = true
            pendingRestore = false
            onEvent(PiPEvent.WillStart)
            onEvent(PiPEvent.DidStart)
            updateParams(enter = false)
        } else {
            if (!this.inPip) return
            this.inPip = false
            // Android 上展开回全屏不会先经过 onStop（仍为 STARTED 且未 finish）；关闭小窗则先走 onStop 或 finish。
            if (!activity.isFinishing && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                pendingRestore = true
                onEvent(PiPEvent.RestoreRequested)
            } else {
                pendingRestore = false
            }
            onEvent(PiPEvent.WillStop)
            onEvent(PiPEvent.DidStop)
        }
    }

    /** 视频尺寸变化时刷新比例。无效尺寸忽略；尺寸没变不重复设置。 */
    fun noteVideoSize(width: Double, height: Double) {
        if (!supported) return
        if (width <= 0.0 || height <= 0.0) return
        if (width == notedWidth && height == notedHeight) return
        notedWidth = width
        notedHeight = height
        updateParams(enter = false, width = width, height = height)
    }

    fun refreshMuteAction() {
        observedKey = currentParamsKey(stage.state.value)
        updateParams(enter = false)
    }

    private fun onStageState(state: StageUiState) {
        if (!bound) return
        val inPictureInPicture = inPip || activity.isInPictureInPictureMode
        val resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        // onPause/onStop 离场期间（且不在画中画）不再刷系统参数，待回前台 onResume 时统一同步。
        if (!resumed && !inPictureInPicture) return
        val key = currentParamsKey(state)
        if (key == observedKey) return
        if (inPictureInPicture && observedKey?.muted == key.muted) {
            observedKey = key
            return
        }
        if (observedKey?.focusLane != key.focusLane) {
            notedWidth = 0.0
            notedHeight = 0.0
        }
        observedKey = key
        updateParams(enter = false)
    }

    private fun currentParamsKey(state: StageUiState): ParamsKey = ParamsKey(
        focusLane = state.focusLane,
        autoEnterEnabled = autoEnter && focusLooksPlayable(state),
        muted = state.muted,
    )

    private fun focusLooksPlayable(state: StageUiState = stage.state.value): Boolean {
        val face = state.face(state.focusLane) ?: return false
        return face.playback == vela.app.stage.LanePlayback.Playing &&
            !face.manuallyPaused &&
            !face.playingUsername.isNullOrEmpty()
    }

    private fun updateParams(enter: Boolean, width: Double? = null, height: Double? = null) {
        if (!supported || Build.VERSION.SDK_INT < 26) return
        try {
            activity.setPictureInPictureParams(buildParams(width, height))
        } catch (_: IllegalStateException) {
            // Activity 尚未 resumed 时忽略。
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun buildParams(width: Double? = null, height: Double? = null): PictureInPictureParams {
        val size = resolveSize(width, height)
        val aspect = PiPAspect.clamp(size.first, size.second)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(aspect)
            .setActions(listOf(muteAction()))
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(autoEnter && focusLooksPlayable())
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun resolveSize(width: Double?, height: Double?): Pair<Double, Double> {
        if (width != null && height != null && width > 0 && height > 0) {
            return width to height
        }
        val lane = when (stage.focusLane) {
            LaneID.PRIMARY -> stage.primaryLane
            LaneID.SECONDARY -> stage.secondaryLane
            LaneID.TERTIARY -> null
        }
        val video = lane?.currentVideoSize()
        if (video != null && video.width > 0 && video.height > 0) {
            return video.width to video.height
        }
        if (notedWidth > 0.0 && notedHeight > 0.0) {
            return notedWidth to notedHeight
        }
        return 9.0 to 16.0
    }

    private fun muteAction(): RemoteAction {
        val muted = stage.muted
        val icon = Icon.createWithResource(
            activity,
            if (muted) R.drawable.ic_pip_unmute else R.drawable.ic_pip_mute,
        )
        val title = activity.getString(
            if (muted) R.string.pip_action_unmute else R.string.pip_action_mute,
        )
        val intent = Intent(ACTION_TOGGLE_MUTE).setPackage(activity.packageName)
        val pending = PendingIntent.getBroadcast(
            activity,
            REQUEST_MUTE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(icon, title, title, pending).apply {
            isEnabled = true
        }
    }

    private fun registerMuteReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_TOGGLE_MUTE)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(muteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(muteReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterMuteReceiver() {
        if (!receiverRegistered) return
        try {
            activity.unregisterReceiver(muteReceiver)
        } catch (_: IllegalArgumentException) {
        }
        receiverRegistered = false
    }

    private val muteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_TOGGLE_MUTE) return
            stage.dispatch(StageIntent.ToggleMute)
            refreshMuteAction()
        }
    }

    companion object {
        private const val TAG = "vela.pip"
        const val ACTION_TOGGLE_MUTE = "vela.app.pip.TOGGLE_MUTE"
        private const val REQUEST_MUTE = 4401
    }
}
