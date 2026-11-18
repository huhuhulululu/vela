package vela.core.lifecycle

import vela.core.base.model.CurationKeys
import vela.core.base.net.EndpointBackoff

/**
 * 房间暂时离开时的纪律：不立刻自动跳过，在当前房间停留时按保守间隔只重试一次解析；
 * 重试后仍离开就保持提示，由用户决定重试或换房。换到别的房间、或成功起播后清零。
 * 延迟任务由调用方用一次性任务实现，切房 / 暂停时取消。
 *
 * Swift 是值类型。这里是类，方法就地修改。
 */
class AwayRetry {
    var room: String? = null
        private set

    var retried: Boolean = false
        private set

    sealed class Decision {
        /** 隔 [after] 秒再解析一次。 */
        data class RetryLater(val after: Double) : Decision()

        /** 已重试过：保持提示，不再自动重试。 */
        data object Hold : Decision()

        val note: String
            get() = when (this) {
                is RetryLater -> waitingNote
                Hold -> heldNote
            }
    }

    /** 起播一个房间时调用：与记录的房间不同（换房了）就清零。同一房间（忽略大小写）不动。 */
    fun enter(username: String) {
        val current = room
        if (current != null && CurationKeys.key(current) == CurationKeys.key(username)) return
        clear()
    }

    /**
     * 解析结果为离开：同一房间还没自动重试过就给出延迟重试，已重试过则保持提示。
     * 只给建议、不记次数：重试排程被取消的那次不消耗，真正开始重试时由 [beginRetry] 记。
     */
    fun onAway(username: String): Decision {
        val current = room
        if (current?.let(CurationKeys::key) != CurationKeys.key(username)) {
            room = username
            retried = false
        }
        return if (retried) Decision.Hold else Decision.RetryLater(delay)
    }

    /** 延迟到点、真正开始自动重试解析时调用：记一次，之后再离开就保持提示。 */
    fun beginRetry() {
        retried = true
    }

    /** 成功起播：清零，之后再离开可以再自动重试一次。 */
    fun clear() {
        room = null
        retried = false
    }

    companion object {
        /** 第一次撞上离开后，隔这么久再解析一次。 */
        const val delay: Double = 60.0

        /** 等待自动重试期间的文案。 */
        const val waitingNote: String = "暂时离开，稍后自动重试"

        /** 已自动重试过一次仍离开：不再自动重试。 */
        const val heldNote: String = "暂时离开，可稍后手动重试或换个房间"

        /** 播放门控关闭（暂停等）时不建自动重试：把延迟重试降为保持提示。 */
        fun gated(decision: Decision, playbackAllowed: Boolean): Decision {
            if (decision is Decision.RetryLater && !playbackAllowed) return Decision.Hold
            return decision
        }

        /**
         * 等待提示在自动重试被取消（暂停等）后换成手动重试文案；其他提示原样。
         * 限流等待文案同样换成手动重试。
         */
        fun noteAfterCancel(note: String?): String? = when (note) {
            waitingNote -> heldNote
            EndpointBackoff.waitingNote -> EndpointBackoff.heldNote
            else -> note
        }

        /** 延迟到点时是否还该重试：仍是同一房间、代次没变、允许播放、任务没被取消。 */
        fun shouldFire(
            scheduledRoom: String,
            currentRoom: String?,
            scheduledGeneration: Int,
            currentGeneration: Int,
            playbackAllowed: Boolean,
            cancelled: Boolean,
        ): Boolean {
            if (cancelled || !playbackAllowed || scheduledGeneration != currentGeneration) return false
            val room = currentRoom ?: return false
            return CurationKeys.key(room) == CurationKeys.key(scheduledRoom)
        }
    }
}

/**
 * 等待中的自动重试排程：播放门控变为不允许时作废，
 * 之后即使在到点前恢复也不再触发。延迟任务本身由调用方持有并随之取消。
 */
class AwayRetryTimer {
    var pending: Boolean = false
        private set

    fun schedule() {
        pending = true
    }

    fun clear() {
        pending = false
    }

    /** 播放门控变化：不允许且有排程时作废，返回 true 表示刚作废。 */
    fun gateChanged(allowed: Boolean): Boolean {
        if (allowed || !pending) return false
        pending = false
        return true
    }
}
