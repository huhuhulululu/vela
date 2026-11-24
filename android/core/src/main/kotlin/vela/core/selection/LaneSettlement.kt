package vela.core.selection

import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import vela.core.base.lane.LaneEndReason
import vela.core.base.lane.PlaybackLaneRole
import vela.core.base.model.FeedSource
import vela.core.base.model.Prefs
import vela.core.base.model.Room
import vela.core.ledger.WatchLedger
import vela.core.taste.Feedback
import vela.core.taste.TagRarity
import vela.core.taste.Taste

/**
 * 一路停留的结算。学习用加权秒，每日命中率只用焦点秒，账本记加权秒。
 * 焦点段权重 1.0，非焦点段 0.5。每路带来源，不跟另一路串。
 *
 * Swift 的 `LaneSettlement.EndReason` 在 L0b 落成 [LaneEndReason]，这里直接用它，不再套一层类型。
 * `recording(into:)` 的 `into` 在 Kotlin 里是第一个参数。
 */
data class LaneSettlement(
    val lane: PlaybackLaneRole,
    val username: String,
    val source: FeedSource,
    val rawSeconds: Double,
    val weightedSeconds: Double,
    val focusedSeconds: Double,
    val endReason: LaneEndReason,
    val userInitiated: Boolean,
) {
    /** 一段停留里焦点在或不在这一路的时间。 */
    data class Span(
        val seconds: Double,
        val focused: Boolean,
    )

    /**
     * 这一段要不要进口味学习。弱落空（不足 3 秒）只在划走时记；满 3 秒时加权秒若仍算落空则不记
     * （非焦点时间折半后不该把一次观看判成落空）。
     */
    val learnsTaste: Boolean
        get() {
            if (rawSeconds < Prefs.skipBelow) return endReason == LaneEndReason.SWIPE
            return Feedback.grade(weightedSeconds).miss <= 0.0
        }

    /** 学习用的秒数；[learnsTaste] 为假时不要使用。不足 3 秒用原始秒，其余用加权秒。 */
    val learnSeconds: Double
        get() = if (rawSeconds < Prefs.skipBelow) rawSeconds else weightedSeconds

    /** 这一段要不要进每日命中率。没有焦点时间不进；background 与 dualOff 不记落空，够长的命中仍然计。 */
    val countsDaily: Boolean
        get() {
            if (focusedSeconds <= 0.0) return false
            if (dropsMisses(endReason) && Feedback.dailyOutcome(focusedSeconds) == false) return false
            return true
        }

    /** 每日统计用的秒数（只含焦点时间）；[countsDaily] 为假时不要使用。 */
    val dailySeconds: Double
        get() = focusedSeconds

    /** background 与 dualOff 不算用户划走。回前台后另开一段停留。 */
    val resumesAsNewDwell: Boolean
        get() = endReason == LaneEndReason.BACKGROUND || endReason == LaneEndReason.DUAL_OFF

    /**
     * 用本路自己的来源记账。学习与每日统计分开判断，不要记的一边传 null（仍标记「已看过」）。
     */
    fun recording(taste: Taste, room: Room, now: Instant, rarity: TagRarity? = null): Taste =
        taste.recording(
            room = room,
            learnSeconds = if (learnsTaste) learnSeconds else null,
            daily = if (countsDaily) dailySeconds else null,
            source = source,
            now = now,
            rarity = rarity,
        )

    /** 观看账本记加权秒，不记原始秒。 */
    fun recording(ledger: WatchLedger, start: Double, now: Double): WatchLedger =
        ledger.recording(username = username, seconds = weightedSeconds, start = start, now = now)

    companion object {
        /** 热门关联只看焦点路。 */
        fun tracksHotRelated(isFocusLane: Boolean): Boolean = isFocusLane

        fun settle(
            lane: PlaybackLaneRole,
            username: String,
            source: FeedSource,
            spans: List<Span>,
            endReason: LaneEndReason,
            userInitiated: Boolean,
        ): LaneSettlement {
            val raw = spans.fold(0.0) { acc, span -> acc + finite(span.seconds) }
            val focusedSum = spans.fold(0.0) { acc, span ->
                acc + if (span.focused) finite(span.seconds) else 0.0
            }
            val focused = min(raw, focusedSum)
            val weighted = focused + (raw - focused) * 0.5
            return LaneSettlement(
                lane = lane,
                username = username,
                source = source,
                rawSeconds = raw,
                weightedSeconds = weighted,
                focusedSeconds = focused,
                endReason = endReason,
                userInitiated = userInitiated,
            )
        }

        private fun dropsMisses(reason: LaneEndReason): Boolean =
            reason == LaneEndReason.BACKGROUND || reason == LaneEndReason.DUAL_OFF

        private fun finite(seconds: Double): Double =
            if (seconds.isFinite()) max(0.0, seconds) else 0.0
    }
}
