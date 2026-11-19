package vela.core.sync

/**
 * 触发时机与退避（docs/plans/sync.md 第 11.3 节）。没有轮询与常驻连接，只有离散触发点。
 * 时刻用无符号毫秒，饱和加法，截止时刻到顶就停在顶。
 */
class SyncScheduler {
    enum class Trigger {
        LAUNCH,
        FOREGROUND,
        ACTIVATE,
        PANEL,
        LOCAL_EDIT,
        MANUAL,
    }

    var lastContactAt: ULong? = null
        private set
    var lastPutAt: ULong? = null
        private set
    var backoffUntil: ULong = 0u
        private set
    var hardUntil: ULong = 0u
        private set
    var failures: Int = 0
        private set

    fun allows(trigger: Trigger, now: ULong): Boolean {
        if (now < hardUntil) return false
        if (trigger == Trigger.MANUAL) return true
        if (now < backoffUntil) return false
        return when (trigger) {
            Trigger.PANEL, Trigger.ACTIVATE -> {
                val last = lastContactAt ?: return true
                now > later(last, PANEL_GAP_MILLIS)
            }
            else -> true
        }
    }

    /** 启动恢复没完成时，哪些离散触发要重新跑一次（不轮询）。 */
    fun allowsBackground(gen: ULong, pushedGen: ULong, now: ULong): Boolean =
        gen > pushedGen && now >= hardUntil

    fun editDelay(now: ULong): ULong {
        var delay = DEBOUNCE_MILLIS
        val last = lastPutAt
        if (last != null && later(last, MIN_PUT_GAP_MILLIS) > now) {
            delay = maxOf(delay, later(last, MIN_PUT_GAP_MILLIS) - now)
        }
        return delay
    }

    fun record(result: SyncCycleResult, now: ULong) {
        when (result) {
            SyncCycleResult.Pushed, SyncCycleResult.Created -> {
                lastPutAt = now
                lastContactAt = now
                failures = 0
                backoffUntil = 0u
            }
            SyncCycleResult.UpToDate -> {
                lastContactAt = now
                failures = 0
                backoffUntil = 0u
            }
            SyncCycleResult.Transient,
            SyncCycleResult.ConflictLimit,
            SyncCycleResult.DecryptFailed,
            SyncCycleResult.StorageFailed,
            -> {
                failures += 1
                val shift = minOf(failures - 1, 20)
                val step = BACKOFF_BASE_MILLIS shl shift
                backoffUntil = later(now, minOf(step, BACKOFF_MAX_MILLIS))
            }
            is SyncCycleResult.RateLimited -> {
                lastContactAt = now
                val clamped = minOf(86_400, maxOf(1, result.retryAfter)).toULong()
                hardUntil = later(now, clamped * 1000u)
            }
            SyncCycleResult.QuotaFull -> {
                lastContactAt = now
                hardUntil = later(now, BACKOFF_MAX_MILLIS)
            }
            is SyncCycleResult.Halted,
            SyncCycleResult.ReadOnly,
            SyncCycleResult.InvalidRemote,
            SyncCycleResult.ClockSkew,
            SyncCycleResult.Oversize,
            SyncCycleResult.PayloadTooLarge,
            -> lastContactAt = now
            SyncCycleResult.Cancelled -> Unit
        }
    }

    companion object {
        const val DEBOUNCE_MILLIS: ULong = 5_000u
        const val MIN_PUT_GAP_MILLIS: ULong = 10_000u
        const val PANEL_GAP_MILLIS: ULong = 60_000u
        const val BACKOFF_BASE_MILLIS: ULong = 30_000u
        const val BACKOFF_MAX_MILLIS: ULong = 3_600_000u

        fun later(now: ULong, delta: ULong): ULong =
            if (now > ULong.MAX_VALUE - delta) ULong.MAX_VALUE else now + delta

        fun retriesStartup(unavailable: Boolean, trigger: Trigger): Boolean {
            if (!unavailable) return false
            return when (trigger) {
                Trigger.FOREGROUND, Trigger.ACTIVATE, Trigger.PANEL, Trigger.MANUAL, Trigger.LAUNCH -> true
                Trigger.LOCAL_EDIT -> false
            }
        }
    }
}
