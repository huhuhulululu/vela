package vela.core.audio

import vela.core.base.lane.PlaybackLaneRole
import vela.core.hls.AudioPolicy
import vela.core.lifecycle.PipAudioOutput

/**
 * 平台焦点变化。对应 AudioManager 的四个回调，core 不引用 Android。
 * GAIN 不得据此恢复出声。LOSS_TRANSIENT_CAN_DUCK 也不降音量，同样转静音。
 */
enum class PlatformFocusChange {
    GAIN,
    LOSS,
    LOSS_TRANSIENT,
    LOSS_TRANSIENT_CAN_DUCK,
}

enum class DeactivationReason {
    MUTE,
    BACKGROUND,
    LAST_LANE_STOPPED,
}

enum class RouteReason {
    /** 拔耳机。对应 ACTION_AUDIO_BECOMING_NOISY。 */
    OLD_DEVICE_UNAVAILABLE,
    OTHER,
}

/**
 * 要执行的音频意图。从 AudioTransition 的 AudioIntent 改写：
 * 不再描述会话类别，只描述焦点和各路静音。
 */
sealed class AudioIntent {
    data class Unmute(val lane: PlaybackLaneRole) : AudioIntent()
    data class Deactivate(val reason: DeactivationReason) : AudioIntent()
    data class InterruptionBegan(val wasSuspended: Boolean) : AudioIntent()
    data object InterruptionEnded : AudioIntent()
    data class Route(val reason: RouteReason) : AudioIntent()
    data class FocusChanged(val change: PlatformFocusChange) : AudioIntent()
}

/**
 * 只请求短暂焦点。永久 GAIN 会让对方在我们放弃后不能恢复，禁止使用。
 * MAY_DUCK 只压低别人而不打断，也不用。
 */
enum class FocusGain {
    TRANSIENT,
}

sealed class AudioFocusStep {
    data class MuteLane(val role: PlaybackLaneRole) : AudioFocusStep()
    data object MuteAllLanes : AudioFocusStep()

    /** 确保持有 AUDIOFOCUS_GAIN_TRANSIENT。必须排在取消静音之前。 */
    data class Request(val gain: FocusGain) : AudioFocusStep()

    /** 放弃焦点，让先前被打断的 App 恢复。静音时不得继续占着焦点。 */
    data object Abandon : AudioFocusStep()
    data class UnmuteLane(val role: PlaybackLaneRole) : AudioFocusStep()
}

/**
 * 一次转换的步骤和结束后的运行时状态。
 * 裁决 2：没有存过静音时视为静音；静音不请求焦点；取消静音请求 GAIN_TRANSIENT；再静音放弃焦点。
 * 裁决 8：按顺序执行，不能两路同时出声；请求失败只改运行时、不写设置；丢焦点或拔耳机转静音且不自动恢复。
 *
 * [writesSettings] 为 false 时调用方不得把静音写进设置。
 * [keepsPlayback] 为 true 时播放器留着（静音画中画）。这不等于继续占焦点。
 * [preservesAudibleLane] 为 true 时忽略 [audibleLane]。
 */
data class AudioFocusPlan(
    val steps: List<AudioFocusStep>,
    val audibleLane: PlaybackLaneRole?,
    val preservesAudibleLane: Boolean,
    /** 非焦点路：静音，并按 MuteAudioPolicy 拿掉音轨。 */
    val detachAudio: List<PlaybackLaneRole>,
    val writesSettings: Boolean,
    val notice: String?,
    val pausesPlayback: Boolean,
    val ignored: Boolean,
    /** 恒为 false。丢焦点、打断结束、拔耳机、焦点归还都不自动恢复出声。 */
    val resumesAudible: Boolean,
    /** 这次想变成出声。只有这时请求失败才改写成保持静音且不写设置。 */
    val attemptsAudible: Boolean,
    val keepsPlayback: Boolean,
    /** 这次会请求的增益。静音计划为 null。只有 [FocusGain.TRANSIENT]。 */
    val requestedGain: FocusGain?,
) {
    fun requestsFocus(): Boolean = steps.any { it is AudioFocusStep.Request }

    fun abandonsFocus(): Boolean = steps.any { it is AudioFocusStep.Abandon }

    companion object {
        const val FAILURE_NOTICE: String = "暂时无法出声"

        fun plan(
            intent: AudioIntent,
            focus: PlaybackLaneRole,
            currentlyAudible: PlaybackLaneRole?,
            lanes: List<PlaybackLaneRole>,
            pictureInPictureActive: Boolean = false,
        ): AudioFocusPlan {
            if (intent is AudioIntent.FocusChanged) {
                val mapped = when (intent.change) {
                    PlatformFocusChange.GAIN -> AudioIntent.InterruptionEnded
                    PlatformFocusChange.LOSS,
                    PlatformFocusChange.LOSS_TRANSIENT,
                    PlatformFocusChange.LOSS_TRANSIENT_CAN_DUCK,
                    -> AudioIntent.InterruptionBegan(wasSuspended = false)
                }
                return plan(mapped, focus, currentlyAudible, lanes, pictureInPictureActive)
            }
            val detach = lanes.filter { it != focus }
            val planned = when (intent) {
                is AudioIntent.Unmute -> unmute(intent.lane, focus, currentlyAudible, detach)
                is AudioIntent.Deactivate -> {
                    val keepPlayback = intent.reason == DeactivationReason.MUTE && pictureInPictureActive
                    silence(
                        detach = detach,
                        abandon = true,
                        writesSettings = intent.reason == DeactivationReason.MUTE,
                        pausesPlayback = false,
                        keepsPlayback = keepPlayback,
                    )
                }
                is AudioIntent.InterruptionBegan -> {
                    if (intent.wasSuspended) {
                        hold(detach, ignored = true)
                    } else {
                        // 系统已经拿走焦点。再放弃一次，避免静音后仍占着栈，也避免之后被自动归还。
                        // 画中画里只停声，播放器留着。
                        silence(
                            detach = detach,
                            abandon = true,
                            writesSettings = false,
                            pausesPlayback = false,
                            keepsPlayback = pictureInPictureActive,
                        )
                    }
                }
                is AudioIntent.InterruptionEnded -> hold(detach, ignored = false)
                is AudioIntent.Route -> {
                    if (intent.reason != RouteReason.OLD_DEVICE_UNAVAILABLE) {
                        hold(detach, ignored = false)
                    } else {
                        silence(
                            detach = detach,
                            abandon = true,
                            writesSettings = true,
                            pausesPlayback = false,
                            keepsPlayback = pictureInPictureActive,
                        )
                    }
                }
                is AudioIntent.FocusChanged -> error("焦点变化应在上面展开")
            }
            return checked(currentlyAudible, planned)
        }

        /**
         * 想出声但没授予：去掉取消静音和请求，保持静音，不写设置。
         * 没想出声的计划（拔耳机、再静音）原样返回，不因为「激活失败」改写。
         */
        fun finished(plan: AudioFocusPlan, focusGranted: Boolean): AudioFocusPlan {
            if (!plan.attemptsAudible || focusGranted) return plan
            val kept = plan.steps.filter {
                it is AudioFocusStep.MuteLane || it is AudioFocusStep.MuteAllLanes || it is AudioFocusStep.Abandon
            }
            val steps = if (kept.any { it is AudioFocusStep.Abandon }) kept else kept + AudioFocusStep.Abandon
            return plan.copy(
                steps = steps,
                audibleLane = null,
                preservesAudibleLane = false,
                writesSettings = false,
                notice = FAILURE_NOTICE,
                pausesPlayback = false,
                ignored = false,
                resumesAudible = false,
                attemptsAudible = false,
                keepsPlayback = false,
                requestedGain = null,
            )
        }

        /**
         * 启动时按设置决定焦点。缺键和显式静音都不请求、也不把缺省值写回去。
         * 存过的取消静音请求 GAIN_TRANSIENT，但不把同一个值再写一遍。
         */
        fun forStoredMute(
            stored: Boolean?,
            focus: PlaybackLaneRole,
            lanes: List<PlaybackLaneRole>,
        ): AudioFocusPlan {
            val detach = lanes.filter { it != focus }
            val planned = if (AudioPolicy.initialMuted(stored)) {
                silence(
                    detach = detach,
                    abandon = false,
                    writesSettings = false,
                    pausesPlayback = false,
                    keepsPlayback = false,
                )
            } else {
                unmute(focus, focus, currentlyAudible = null, detach).copy(writesSettings = false)
            }
            return checked(null, planned)
        }

        /**
         * 消费 [PipAudioOutput]。静音（含静音画中画）不请求焦点。
         * 正在拿着焦点、或输出要求通知对方时，放弃焦点。
         * [PipAudioOutput.releaseNotifyingOthers] 为 false 只表示画面还要播，不表示可以继续占焦点。
         */
        fun fromPip(
            output: PipAudioOutput,
            holdingFocus: Boolean,
            focus: PlaybackLaneRole,
            currentlyAudible: PlaybackLaneRole?,
            lanes: List<PlaybackLaneRole>,
        ): AudioFocusPlan {
            val detach = lanes.filter { it != focus }
            val planned = if (output.holdsFocus) {
                if (holdingFocus && currentlyAudible == focus) {
                    hold(detach, ignored = false).copy(keepsPlayback = true)
                } else {
                    unmute(focus, focus, currentlyAudible, detach).copy(
                        writesSettings = false,
                        keepsPlayback = !output.releaseNotifyingOthers,
                    )
                }
            } else {
                silence(
                    detach = detach,
                    abandon = holdingFocus || output.releaseNotifyingOthers,
                    writesSettings = false,
                    pausesPlayback = false,
                    keepsPlayback = !output.releaseNotifyingOthers,
                )
            }
            return checked(currentlyAudible, planned)
        }

        /** 按步骤走一遍。任何前缀里同时出声的路不得超过 1。起点是转换前正在出声的那一路。 */
        fun peakAudible(steps: List<AudioFocusStep>, initiallyAudible: PlaybackLaneRole?): Int {
            val audible = LinkedHashSet<PlaybackLaneRole>()
            if (initiallyAudible != null) audible.add(initiallyAudible)
            var peak = audible.size
            for (step in steps) {
                when (step) {
                    is AudioFocusStep.MuteLane -> audible.remove(step.role)
                    AudioFocusStep.MuteAllLanes -> audible.clear()
                    is AudioFocusStep.UnmuteLane -> audible.add(step.role)
                    is AudioFocusStep.Request, AudioFocusStep.Abandon -> Unit
                }
                if (audible.size > peak) peak = audible.size
            }
            return peak
        }

        private fun checked(initiallyAudible: PlaybackLaneRole?, plan: AudioFocusPlan): AudioFocusPlan {
            check(peakAudible(plan.steps, initiallyAudible) <= 1) { "两路同时出声: ${plan.steps}" }
            check(!plan.resumesAudible) { "不允许自动恢复出声" }
            if (plan.requestsFocus()) {
                check(plan.requestedGain == FocusGain.TRANSIENT) { "只允许 GAIN_TRANSIENT" }
            }
            return plan
        }

        private fun unmute(
            lane: PlaybackLaneRole,
            focus: PlaybackLaneRole,
            currentlyAudible: PlaybackLaneRole?,
            detach: List<PlaybackLaneRole>,
        ): AudioFocusPlan {
            if (lane != focus) {
                val steps = mutableListOf<AudioFocusStep>()
                if (currentlyAudible != null && currentlyAudible != focus) {
                    steps.add(AudioFocusStep.MuteLane(currentlyAudible))
                }
                if (steps.none { it is AudioFocusStep.MuteLane && it.role == lane }) {
                    steps.add(AudioFocusStep.MuteLane(lane))
                }
                val audibleAfter = if (currentlyAudible == focus) focus else null
                // 唯一出声的路被静音后不得继续占焦点。焦点路仍出声时不放弃。
                if (audibleAfter == null && currentlyAudible != null) {
                    steps.add(AudioFocusStep.Abandon)
                }
                return AudioFocusPlan(
                    steps = steps,
                    audibleLane = audibleAfter,
                    preservesAudibleLane = false,
                    detachAudio = detach,
                    writesSettings = false,
                    notice = null,
                    pausesPlayback = false,
                    ignored = false,
                    resumesAudible = false,
                    attemptsAudible = false,
                    keepsPlayback = false,
                    requestedGain = null,
                )
            }
            val steps = mutableListOf<AudioFocusStep>()
            if (currentlyAudible != null) {
                steps.add(AudioFocusStep.MuteLane(currentlyAudible))
            }
            steps.add(AudioFocusStep.Request(FocusGain.TRANSIENT))
            steps.add(AudioFocusStep.UnmuteLane(lane))
            return AudioFocusPlan(
                steps = steps,
                audibleLane = lane,
                preservesAudibleLane = false,
                detachAudio = detach,
                writesSettings = true,
                notice = null,
                pausesPlayback = false,
                ignored = false,
                resumesAudible = false,
                attemptsAudible = true,
                keepsPlayback = false,
                requestedGain = FocusGain.TRANSIENT,
            )
        }

        private fun silence(
            detach: List<PlaybackLaneRole>,
            abandon: Boolean,
            writesSettings: Boolean,
            pausesPlayback: Boolean,
            keepsPlayback: Boolean,
        ): AudioFocusPlan {
            val steps = mutableListOf<AudioFocusStep>(AudioFocusStep.MuteAllLanes)
            if (abandon) steps.add(AudioFocusStep.Abandon)
            return AudioFocusPlan(
                steps = steps,
                audibleLane = null,
                preservesAudibleLane = false,
                detachAudio = detach,
                writesSettings = writesSettings,
                notice = null,
                pausesPlayback = pausesPlayback,
                ignored = false,
                resumesAudible = false,
                attemptsAudible = false,
                keepsPlayback = keepsPlayback,
                requestedGain = null,
            )
        }

        private fun hold(
            detach: List<PlaybackLaneRole>,
            ignored: Boolean,
            pausesPlayback: Boolean = false,
        ): AudioFocusPlan = AudioFocusPlan(
            steps = emptyList(),
            audibleLane = null,
            preservesAudibleLane = true,
            detachAudio = detach,
            writesSettings = false,
            notice = null,
            pausesPlayback = pausesPlayback,
            ignored = ignored,
            resumesAudible = false,
            attemptsAudible = false,
            keepsPlayback = false,
            requestedGain = null,
        )
    }
}
