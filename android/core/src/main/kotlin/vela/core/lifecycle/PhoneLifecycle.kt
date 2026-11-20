package vela.core.lifecycle

import vela.core.base.lane.LaneEndReason
import vela.core.base.lane.PlaybackLaneRole

/** 手机场景阶段。不用界面框架的场景相位，Core 里用这个值。 */
enum class PhoneScenePhase {
    ACTIVE,
    INACTIVE,
    BACKGROUND,
}

/** 进后台前正在做、回前台要重做的共享工作。 */
enum class PhoneResumeIntent {
    RELOAD,
    SEARCH,
}

/** 某一路回前台时原先在做的事。满足 [PhoneLifecycle.shouldPlay] 时原样带回。 */
enum class PhoneLaneActivity {
    IDLE,
    FIRST_RESOLVE,
    ROOM_SWITCH,
    REFRESHING,
    PLAYING,
}

/** 回前台时拿来判断该不该重新起播的一路快照。 */
data class PhoneLaneSnapshot(
    var role: PlaybackLaneRole,
    var username: String?,
    var manuallyPaused: Boolean,
    var visible: Boolean,
    var failed: Boolean,
    var activity: PhoneLaneActivity,
)

/** [PhoneLifecycle.actions] 给出的有序动作。进后台与回前台各是一条固定序列。 */
sealed class PhoneLifecycleAction {
    data object GateOff : PhoneLifecycleAction()
    data object EpochSuspend : PhoneLifecycleAction()
    data class SettleDwell(val reason: LaneEndReason) : PhoneLifecycleAction()
    data object FlushSavers : PhoneLifecycleAction()
    data object SuspendLanes : PhoneLifecycleAction()
    data object AudioDeactivate : PhoneLifecycleAction()
    data object StopListener : PhoneLifecycleAction()
    data class NoteResumeIntent(val intent: PhoneResumeIntent?) : PhoneLifecycleAction()
    data object EpochResume : PhoneLifecycleAction()
    data class RedoResumeIntent(val intent: PhoneResumeIntent) : PhoneLifecycleAction()
    data class RequestPlay(val role: PlaybackLaneRole, val activity: PhoneLaneActivity) : PhoneLifecycleAction()
    data class KeepFailure(val role: PlaybackLaneRole) : PhoneLifecycleAction()
    data class StayPaused(val role: PlaybackLaneRole) : PhoneLifecycleAction()
    data class StayReleased(val role: PlaybackLaneRole) : PhoneLifecycleAction()

    /** 挂起目录、搜索、收藏查询、侧车、缩略图。不挂起画中画那一路。 */
    data object SuspendSharedTasks : PhoneLifecycleAction()

    /** [LifecycleEpoch] 放行这一路的请求。只跟 [SuspendSharedTasks] 一起出现。 */
    data class AllowLaneRequests(val role: PlaybackLaneRole) : PhoneLifecycleAction()

    /** 释放这一路：播放项、中继源，不允许再播。 */
    data class ReleaseLane(val role: PlaybackLaneRole) : PhoneLifecycleAction()

    /** 这一路继续播放（画中画中，或即将自动进入）。 */
    data class ContinueLane(val role: PlaybackLaneRole) : PhoneLifecycleAction()

    /** 中继保持监听，源表只留这一路。 */
    data class RetainRelaySource(val role: PlaybackLaneRole) : PhoneLifecycleAction()
}

/**
 * 前后台切换的纯函数。进入后台与从挂起回到前台的动作顺序都在这里，不碰系统 API。
 * `pip == null` 时进后台全停。
 */
object PhoneLifecycle {
    /** [PhoneScenePhase.ACTIVE] 与 [PhoneScenePhase.INACTIVE] 时画面仍在。 */
    fun pointVisible(phase: PhoneScenePhase): Boolean = phase != PhoneScenePhase.BACKGROUND

    /** 只有 [PhoneScenePhase.ACTIVE] 才算在看。inactive 时画面照常播，停留不计时。 */
    fun attentive(phase: PhoneScenePhase): Boolean = phase == PhoneScenePhase.ACTIVE

    /** 该路是否该重新解析地址。不读上次播放。空用户名不算。 */
    fun shouldPlay(lane: PhoneLaneSnapshot): Boolean {
        val username = lane.username
        if (username.isNullOrEmpty()) return false
        return !lane.manuallyPaused && lane.visible && !lane.failed
    }

    /**
     * `to == background`（且原先不在后台）给出进后台序列。
     * 回到 active 只看 [wasSuspended]：中间经过 inactive 也一样。没挂起过则什么都不做。
     * 有画中画输入时，焦点路已在画中画或将自动进入，则该路继续、其余路释放、中继只留该路。
     * 已经在后台且状态变为 stopping 时，补做全停。
     * 快照里能找到焦点路时，「焦点路正在播放」取 `pip.focusPlaying ∧ shouldPlay`（只收紧、不覆盖）。
     */
    fun actions(
        from: PhoneScenePhase,
        to: PhoneScenePhase,
        wasSuspended: Boolean,
        lanes: List<PhoneLaneSnapshot>,
        resumeIntent: PhoneResumeIntent?,
        pip: PiPPolicy.Input? = null,
    ): List<PhoneLifecycleAction> {
        if (to == PhoneScenePhase.BACKGROUND) {
            if (from != PhoneScenePhase.BACKGROUND) {
                if (pip != null) {
                    val focus = continuedLane(evaluate(pip, lanes, to))
                    if (focus != null) return pictureInPictureBackground(lanes, focus, resumeIntent)
                }
                return backgroundStop(resumeIntent)
            }
            if (pip != null && pip.pictureInPicture == PiPPolicy.State.STOPPING) {
                return backgroundStop(resumeIntent)
            }
            return emptyList()
        }
        if (to != PhoneScenePhase.ACTIVE || !wasSuspended) return emptyList()
        val result = ArrayList<PhoneLifecycleAction>()
        result.add(PhoneLifecycleAction.EpochResume)
        if (resumeIntent != null) result.add(PhoneLifecycleAction.RedoResumeIntent(resumeIntent))
        result.addAll(lanes.map { outcome(it) })
        return result
    }

    /** 没有画中画时的进后台序列。顺序保持修订前的合同。 */
    private fun backgroundStop(resumeIntent: PhoneResumeIntent?): List<PhoneLifecycleAction> = listOf(
        PhoneLifecycleAction.GateOff,
        PhoneLifecycleAction.EpochSuspend,
        PhoneLifecycleAction.SettleDwell(LaneEndReason.BACKGROUND),
        PhoneLifecycleAction.FlushSavers,
        PhoneLifecycleAction.SuspendLanes,
        PhoneLifecycleAction.AudioDeactivate,
        PhoneLifecycleAction.StopListener,
        PhoneLifecycleAction.NoteResumeIntent(resumeIntent),
    )

    /**
     * 画中画继续时不发全局 gateOff、不 audioDeactivate、不 stopListener。
     * 非焦点路逐路释放。共享任务挂起，但画中画路的请求放行。
     */
    private fun pictureInPictureBackground(
        lanes: List<PhoneLaneSnapshot>,
        focus: PlaybackLaneRole,
        resumeIntent: PhoneResumeIntent?,
    ): List<PhoneLifecycleAction> = buildList {
        add(PhoneLifecycleAction.SuspendSharedTasks)
        add(PhoneLifecycleAction.AllowLaneRequests(focus))
        add(PhoneLifecycleAction.SettleDwell(LaneEndReason.BACKGROUND))
        add(PhoneLifecycleAction.FlushSavers)
        for (lane in lanes) {
            if (lane.role != focus) add(PhoneLifecycleAction.ReleaseLane(lane.role))
        }
        add(PhoneLifecycleAction.ContinueLane(focus))
        add(PhoneLifecycleAction.RetainRelaySource(focus))
        add(PhoneLifecycleAction.NoteResumeIntent(resumeIntent))
    }

    private fun evaluate(
        pip: PiPPolicy.Input,
        lanes: List<PhoneLaneSnapshot>,
        scene: PhoneScenePhase,
    ): PiPPolicy.Decision {
        var input = pip.copy(scene = scene)
        if (lanes.isNotEmpty()) input = input.copy(lanes = lanes.map { it.role })
        val focusLane = lanes.firstOrNull { it.role == input.focus }
        if (focusLane != null) {
            input = input.copy(focusPlaying = pip.focusPlaying && shouldPlay(focusLane))
        }
        return PiPPolicy.decide(input)
    }

    /** 中继留着、而且留下的那一路允许播放，才算画中画继续。 */
    private fun continuedLane(decision: PiPPolicy.Decision): PlaybackLaneRole? {
        if (!decision.keepsRelayListening) return null
        val source = decision.retainedRelaySource ?: return null
        if (!decision.playbackAllowed(source)) return null
        return source
    }

    /** 隐藏优先（保持已释放），其次手动暂停，其次失败态，其余满足 [shouldPlay] 才重新起播。 */
    private fun outcome(lane: PhoneLaneSnapshot): PhoneLifecycleAction {
        if (!lane.visible) return PhoneLifecycleAction.StayReleased(lane.role)
        if (lane.manuallyPaused) return PhoneLifecycleAction.StayPaused(lane.role)
        if (lane.failed) return PhoneLifecycleAction.KeepFailure(lane.role)
        if (shouldPlay(lane)) return PhoneLifecycleAction.RequestPlay(lane.role, lane.activity)
        return PhoneLifecycleAction.StayReleased(lane.role)
    }
}
