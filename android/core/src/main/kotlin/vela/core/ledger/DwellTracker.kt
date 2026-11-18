package vela.core.ledger

import java.time.Instant
import vela.core.base.model.FeedSource
import vela.core.base.model.Prefs
import vela.core.base.model.Room
import vela.core.base.model.lowerRoot
import vela.core.base.model.timeIntervalSince1970
import kotlin.math.max

/**
 * 停留时长记账：只累计「实际在播放且可见」的时间，换房间时结算上一个房间。
 * 播放失败被自动跳过的房间用户没看到画面，结算时不产生学习信号。
 *
 * Swift 是值类型。这里是类，方法直接改自己；[equals] 仍按全部存储字段比较。
 */
class DwellTracker {
    data class Settled(
        val room: Room,
        val dwell: Double,
        val ledgerSeconds: Double,
        val start: Instant?,
    )

    sealed class RelatedPlan {
        /** 已不在该房间，或已触发过。 */
        data object None : RelatedPlan()

        /** 当前没在计时（暂停、缓冲、隐藏）：不必唤醒，等恢复计时后再排。 */
        data object Idle : RelatedPlan()

        data class Wait(val seconds: Double) : RelatedPlan()

        data object Due : RelatedPlan()
    }

    var room: Room? = null
        private set

    private var accumulated: Double = 0.0
    private var activeSince: Instant? = null
    private var failed: Boolean = false
    private var discarded: Boolean = false
    private var relatedDone: Boolean = false

    /** 本次观看首次开始实际播放的时刻；暂停、缓冲后恢复不改它。 */
    var startedAt: Instant? = null
        private set

    private var excluded: Double = 0.0
    private var exclusionMark: Double? = null

    /**
     * 列表改动后是否要结算停留：当前房间换了，或来源换了（新来源的第一个房间恰好与旧房间同名时，
     * 用户名没变，但旧来源下的停留必须按旧来源结算，新来源重新开始）。
     */
    fun switchTo(next: Room?, now: Instant): Settled? {
        val done = settled(now)
        room = next
        accumulated = 0.0
        activeSince = null
        failed = false
        discarded = false
        relatedDone = false
        startedAt = null
        excluded = 0.0
        exclusionMark = null
        return done
    }

    /** 结束记账（退出应用）：结算当前房间并清空。 */
    fun finish(now: Instant): Settled? = switchTo(null, now)

    val isActive: Boolean
        get() = activeSince != null

    /** 当前是否在播放且可见。重复设置同一状态无副作用。 */
    fun setActive(active: Boolean, now: Instant) {
        if (room == null) return
        if (active) {
            if (activeSince == null) activeSince = now
            if (startedAt == null) startedAt = now
        } else if (activeSince != null) {
            accumulated += elapsed(activeSince!!, now)
            activeSince = null
        }
    }

    /** 当前房间播放失败（将被自动跳过）：结算时不记信号。 */
    fun markFailed() {
        failed = true
    }

    /** 丢弃当前房间的停留（例如用户屏蔽了它）：结算时不记任何信号，之后也不会被恢复。 */
    fun discard() {
        discarded = true
    }

    val isDiscarded: Boolean
        get() = discarded

    /**
     * 排除段开关（暂停学习期间为开）：开着时这段观看不计入账本，关上后继续计。
     * 重复设置同一状态无副作用。
     */
    fun setLedgerExcluded(on: Boolean, now: Instant) {
        if (room == null) return
        if (on) {
            if (exclusionMark == null) exclusionMark = dwell(now)
        } else if (exclusionMark != null) {
            excluded += max(0.0, dwell(now) - exclusionMark!!)
            exclusionMark = null
        }
    }

    /** 可计入账本的实时秒数（含当前在播段）；排除段进行中时冻结在进入排除段那一刻。 */
    fun ledgerDwell(now: Instant): Double {
        val total = exclusionMark ?: dwell(now)
        return max(0.0, total - excluded)
    }

    /** 当前房间后来播放成功（例如用户重试）：恢复记信号。 */
    fun clearFailure() {
        failed = false
    }

    fun dwell(now: Instant): Double = accumulated + (activeSince?.let { elapsed(it, now) } ?: 0.0)

    /** 热门关联的下一步。每个房间只触发一次（[markRelatedDone]）。 */
    fun relatedPlan(username: String, now: Instant): RelatedPlan {
        val current = room
        if (current == null || relatedDone || current.username.lowerRoot() != username.lowerRoot()) {
            return RelatedPlan.None
        }
        val remaining = max(0.0, Prefs.relatedAfter - dwell(now))
        if (remaining <= 0.0) return RelatedPlan.Due
        return if (isActive) RelatedPlan.Wait(remaining) else RelatedPlan.Idle
    }

    fun markRelatedDone() {
        relatedDone = true
    }

    private fun settled(now: Instant): Settled? {
        val current = room ?: return null
        if (failed || discarded) return null
        return Settled(room = current, dwell = dwell(now), ledgerSeconds = ledgerDwell(now), start = startedAt)
    }

    private fun elapsed(from: Instant, to: Instant): Double =
        max(0.0, to.timeIntervalSince1970() - from.timeIntervalSince1970())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DwellTracker) return false
        return room == other.room &&
            accumulated == other.accumulated &&
            activeSince == other.activeSince &&
            failed == other.failed &&
            discarded == other.discarded &&
            relatedDone == other.relatedDone &&
            startedAt == other.startedAt &&
            excluded == other.excluded &&
            exclusionMark == other.exclusionMark
    }

    override fun hashCode(): Int {
        var result = room?.hashCode() ?: 0
        result = 31 * result + accumulated.hashCode()
        result = 31 * result + (activeSince?.hashCode() ?: 0)
        result = 31 * result + failed.hashCode()
        result = 31 * result + discarded.hashCode()
        result = 31 * result + relatedDone.hashCode()
        result = 31 * result + (startedAt?.hashCode() ?: 0)
        result = 31 * result + excluded.hashCode()
        result = 31 * result + (exclusionMark?.hashCode() ?: 0)
        return result
    }

    companion object {
        fun shouldSettle(before: String?, after: String?, sourceChanged: Boolean): Boolean =
            before != after || (sourceChanged && after != null)
    }
}

/**
 * 停留记账的来源归属：以「当前列表实际属于哪个来源」为准，而不是用户刚选中的来源。
 */
object ListOrigin {
    /** 换房间时新房间所在列表的来源：列表正被整体替换（`installing` 非 nil）时是新来源，否则仍是已加载的来源。 */
    fun dwell(loaded: FeedSource, installing: FeedSource?): FeedSource = installing ?: loaded
}
