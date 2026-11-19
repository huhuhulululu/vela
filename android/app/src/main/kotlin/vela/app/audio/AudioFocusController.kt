package vela.app.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import vela.core.audio.AudioFocusPlan
import vela.core.audio.AudioFocusStep
import vela.core.audio.AudioIntent
import vela.core.audio.DeactivationReason
import vela.core.audio.FocusGain
import vela.core.audio.PlatformFocusChange
import vela.core.audio.RouteReason
import vela.core.base.lane.PlaybackLaneRole
import vela.core.lifecycle.PipAudioOutput

/**
 * 用 [AudioFocusRequest] 执行 [AudioFocusPlan]。
 * 不用 ExoPlayer 的 handleAudioFocus：那个请求永久 GAIN，对方不会在我们静音后恢复。
 *
 * 只在主线程调用。[start] 登记拔耳机广播，[stop] 放弃焦点并注销。
 * 静音不调用 requestAudioFocus。取消静音只请求 AUDIOFOCUS_GAIN_TRANSIENT。
 */
class AudioFocusController(
    private val context: Context,
    private val audioManager: AudioManager,
    private val sinks: Sinks,
) {
    interface Sinks {
        fun muteLane(role: PlaybackLaneRole)
        fun muteAll()
        fun unmuteLane(role: PlaybackLaneRole)
        fun detachLane(role: PlaybackLaneRole)
        fun writeMuted(muted: Boolean)
        fun showNotice(text: String)
        fun pausePlayback()
    }

    var focus: PlaybackLaneRole = PlaybackLaneRole.PRIMARY
    var lanes: List<PlaybackLaneRole> = listOf(PlaybackLaneRole.PRIMARY, PlaybackLaneRole.SECONDARY)
    var pictureInPictureActive: Boolean = false

    var audibleLane: PlaybackLaneRole? = null
        private set

    var holdingFocus: Boolean = false
        private set

    private var request: AudioFocusRequest? = null
    private var receiverRegistered: Boolean = false
    private var depth: Int = 0
    private val deferred = ArrayDeque<AudioIntent>()
    private val nested = ArrayDeque<AudioFocusPlan>()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val mapped = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> PlatformFocusChange.GAIN
            AudioManager.AUDIOFOCUS_LOSS -> PlatformFocusChange.LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> PlatformFocusChange.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> PlatformFocusChange.LOSS_TRANSIENT_CAN_DUCK
            else -> null
        } ?: return@OnAudioFocusChangeListener
        execute(AudioIntent.FocusChanged(mapped))
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                execute(AudioIntent.Route(RouteReason.OLD_DEVICE_UNAVAILABLE))
            }
        }
    }

    /** 登记 ACTION_AUDIO_BECOMING_NOISY。重复调用无效果。 */
    fun start() {
        if (receiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(noisyReceiver, filter)
        }
        receiverRegistered = true
    }

    /** 进后台：静音并放弃焦点，不写设置，然后注销拔耳机广播。 */
    fun stop() {
        execute(AudioIntent.Deactivate(DeactivationReason.BACKGROUND))
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(noisyReceiver)
        } catch (_: IllegalArgumentException) {
            // 已经注销。
        }
        receiverRegistered = false
    }

    fun applyStored(storedMuted: Boolean?): AudioFocusPlan =
        apply(AudioFocusPlan.forStoredMute(storedMuted, focus, lanes))

    fun applyPip(output: PipAudioOutput): AudioFocusPlan =
        apply(AudioFocusPlan.fromPip(output, holdingFocus, focus, audibleLane, lanes))

    fun execute(intent: AudioIntent): AudioFocusPlan {
        if (depth > 0) {
            deferred.addLast(intent)
            return AudioFocusPlan.plan(intent, focus, audibleLane, lanes, pictureInPictureActive)
        }
        return apply(AudioFocusPlan.plan(intent, focus, audibleLane, lanes, pictureInPictureActive))
    }

    fun apply(planned: AudioFocusPlan): AudioFocusPlan {
        if (depth > 0) {
            nested.addLast(planned)
            return planned
        }
        depth += 1
        try {
            val done = applyNow(planned)
            drain()
            return done
        } finally {
            depth = 0
            deferred.clear()
            nested.clear()
        }
    }

    private fun drain() {
        while (deferred.isNotEmpty() || nested.isNotEmpty()) {
            while (deferred.isNotEmpty()) {
                val intent = deferred.removeFirst()
                applyNow(AudioFocusPlan.plan(intent, focus, audibleLane, lanes, pictureInPictureActive))
            }
            while (nested.isNotEmpty()) {
                applyNow(nested.removeFirst())
            }
        }
    }

    private fun applyNow(planned: AudioFocusPlan): AudioFocusPlan {
        var granted = !planned.attemptsAudible
        for (step in planned.steps) {
            when (step) {
                is AudioFocusStep.MuteLane -> sinks.muteLane(step.role)
                AudioFocusStep.MuteAllLanes -> sinks.muteAll()
                is AudioFocusStep.Request -> {
                    check(step.gain == FocusGain.TRANSIENT) { "只允许 GAIN_TRANSIENT" }
                    granted = ensureTransient()
                    if (!granted) break
                }
                AudioFocusStep.Abandon -> abandon()
                is AudioFocusStep.UnmuteLane -> sinks.unmuteLane(step.role)
            }
        }
        if (planned.attemptsAudible && !granted) {
            abandon()
        }
        for (role in planned.detachAudio) {
            sinks.detachLane(role)
        }
        val done = AudioFocusPlan.finished(planned, focusGranted = granted)
        if (!done.preservesAudibleLane) {
            audibleLane = done.audibleLane
        }
        if (done.writesSettings) {
            sinks.writeMuted(done.audibleLane == null)
        }
        if (done.pausesPlayback) {
            sinks.pausePlayback()
        }
        done.notice?.let(sinks::showNotice)
        return done
    }

    /** 已经拿着短暂焦点就不再请求，避免切路时先放弃、让对方响一下再被打断。 */
    private fun ensureTransient(): Boolean {
        if (holdingFocus && request != null) return true
        val built = buildRequest()
        val result = audioManager.requestAudioFocus(built)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        request = built
        holdingFocus = true
        return true
    }

    private fun buildRequest(): AudioFocusRequest {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
            .build()
    }

    private fun abandon() {
        val current = request
        request = null
        holdingFocus = false
        if (current != null) {
            audioManager.abandonAudioFocusRequest(current)
        }
    }
}
