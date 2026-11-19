package vela.core.hls

import java.time.Instant
import java.util.concurrent.CancellationException
import vela.core.base.play.RelayFailure

/**
 * 哪些失败算地址过期，以及一次续期最多等多久。
 * 403 和 410 才续期。取消不是网络故障，不能拿去走侧车。
 */
object RenewalPolicy {
    /** 回答加载请求时，常规等待之外最多再等这么多秒。 */
    const val waitBound: Double = 8.0

    fun isExpiry(failure: RelayFailure?): Boolean {
        val http = failure as? RelayFailure.Http ?: return false
        return http.status == 403 || http.status == 410
    }

    fun isCancellation(error: Throwable): Boolean {
        if (error is CancellationException) return true
        val transport = error as? TransportError ?: return false
        return transport.domain == UrlErrors.DOMAIN && transport.code == UrlErrors.CANCELLED
    }
}

/**
 * 一个播放源的续期预算：滑动窗口内最多 [limit] 次，相邻两次至少隔 [minGap] 秒。
 * 时刻由调用方传入，不读墙钟。
 */
class RenewalBudget(
    val limit: Int = 3,
    val window: Double = 60.0,
    val minGap: Double = 3.0,
) {
    private val attempts = mutableListOf<Instant>()

    fun canRenew(now: Instant): Boolean {
        val recent = attempts.filter { now.secondsSince(it) < window }
        if (recent.size >= limit) return false
        val last = recent.lastOrNull()
        if (last != null && now.secondsSince(last) < minGap) return false
        return true
    }

    /** 退回 [date] 那次占用。取消而没有完成的续期用它，既不占次数也不占最短间隔。 */
    fun refund(date: Instant) {
        val index = attempts.lastIndexOf(date)
        if (index >= 0) attempts.removeAt(index)
    }

    /** 允许则记一次并返回 true。不允许不记。 */
    fun take(now: Instant): Boolean {
        if (!canRenew(now)) return false
        attempts.removeAll { now.secondsSince(it) >= window }
        attempts.add(now)
        return true
    }
}

/** 慢续期返回时，代次、房间或取消状态已经变了就不能再写入。 */
object RenewalFreshness {
    fun isCurrent(
        startedGeneration: Int,
        currentGeneration: Int,
        startedRoom: String,
        currentRoom: String?,
        cancelled: Boolean,
    ): Boolean =
        !cancelled && startedGeneration == currentGeneration && currentRoom == startedRoom
}
