package vela.core.ledger

import java.time.Instant
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import vela.core.base.model.Prefs
import vela.core.base.model.lowerRoot
import vela.core.base.model.timeIntervalSince1970
import vela.core.base.model.trimSwiftWhitespace
import kotlin.math.max

/**
 * 与 Swift `JSONEncoder` / `JSONDecoder` 对齐：缺省值也写出（空 `entries` 不能省），
 * 空的可选字段省略（`encodeIfPresent`），未知字段忽略。
 */
internal val ledgerJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * 实际观看累计：为温和的收藏提示记账（不自动收藏）。
 * Swift 是值类型；这里用不可变 data class，更新方法返回新值。
 */
@Serializable
data class WatchLedger(
    @Required val entries: Map<String, Entry> = emptyMap(),
) {
    @Serializable
    data class Entry(
        val totalSeconds: Double,
        val visitCount: Int,
        val lastVisitStart: Double? = null,
        val suggested: Boolean,
        val lastUsed: Double,
    ) {
        companion object {
            fun empty(now: Double): Entry = Entry(
                totalSeconds = 0.0,
                visitCount = 0,
                lastVisitStart = null,
                suggested = false,
                lastUsed = now,
            )
        }
    }

    /** 结算一段有效停留：累计秒数，并按规则计「有效次数」。 */
    fun recording(
        username: String,
        seconds: Double,
        start: Double,
        now: Double = Instant.now().timeIntervalSince1970(),
    ): WatchLedger {
        val name = key(username)
        if (name.isEmpty() || seconds <= 0.0) return capped(now)
        val current = entries[name] ?: Entry.empty(now)
        val total = current.totalSeconds + max(0.0, seconds)
        val counted = seconds >= visitMinSeconds && run {
            val last = current.lastVisitStart
            last == null || start - last >= visitGapSeconds
        }
        val updated = if (counted) {
            current.copy(
                totalSeconds = total,
                visitCount = current.visitCount + 1,
                lastVisitStart = start,
                lastUsed = now,
            )
        } else {
            current.copy(totalSeconds = total, lastUsed = now)
        }
        return copy(entries = entries + (name to updated)).capped(now)
    }

    /** 是否应提示收藏（可选带上当前在播、尚未结算的观看）。 */
    fun shouldSuggest(
        username: String,
        favorited: Boolean,
        blocked: Boolean,
        liveSeconds: Double = 0.0,
        liveStart: Double? = null,
    ): Boolean {
        if (favorited || blocked) return false
        val name = key(username)
        if (name.isEmpty()) return false
        val entry = entries[name]
        if (entry?.suggested == true) return false
        val (total, visits) = metrics(entry, liveSeconds, liveStart)
        return total >= totalThreshold || visits >= visitCountThreshold
    }

    /** 距离累计满 10 分钟还差多少（含当前在播段）；已达标或已提示时返回 null。 */
    fun secondsUntilTotalSuggest(
        username: String,
        favorited: Boolean,
        blocked: Boolean,
        liveSeconds: Double,
        liveStart: Double?,
    ): Double? {
        if (favorited || blocked) return null
        val name = key(username)
        if (name.isEmpty() || entries[name]?.suggested == true) return null
        if (shouldSuggest(username, favorited = false, blocked = false, liveSeconds = liveSeconds, liveStart = liveStart)) {
            return null
        }
        val (total, _) = metrics(entries[name], liveSeconds, liveStart)
        if (total >= totalThreshold) return null
        return max(0.0, totalThreshold - total)
    }

    /**
     * 下一个需要重新判定的时间点：累计满 10 分钟，或本次观看满 60 秒（若那时会凑够第三次有效观看）。
     * 没有后续检查点（已达标、已提示、已收藏或屏蔽）时返回 null。
     */
    fun secondsUntilNextSuggestCheck(
        username: String,
        favorited: Boolean,
        blocked: Boolean,
        liveSeconds: Double,
        liveStart: Double?,
    ): Double? {
        val total = secondsUntilTotalSuggest(username, favorited, blocked, liveSeconds, liveStart)
        if (total == null || liveSeconds >= visitMinSeconds || liveStart == null) return total
        val entry = entries[key(username)]
        val last = entry?.lastVisitStart
        val counts = last == null || liveStart - last >= visitGapSeconds
        if (!counts || (entry?.visitCount ?: 0) + 1 < visitCountThreshold) return total
        return minOf(total, visitMinSeconds - max(0.0, liveSeconds))
    }

    fun markingSuggested(
        username: String,
        now: Double = Instant.now().timeIntervalSince1970(),
    ): WatchLedger {
        val name = key(username)
        if (name.isEmpty()) return this
        val updated = (entries[name] ?: Entry.empty(now)).copy(suggested = true, lastUsed = now)
        return copy(entries = entries + (name to updated)).capped(now)
    }

    fun cleared(): WatchLedger = WatchLedger()

    /**
     * 超出 [maxEntries] 时按 `lastUsed` 从新到旧保留。
     * [now] 与 Swift 一样不参与筛选，留着是为了调用点一致。
     */
    fun capped(now: Double): WatchLedger {
        if (entries.size <= maxEntries) {
            return WatchLedger(entries.filterKeys { it.length <= Prefs.maxNameLength })
        }
        val kept = entries
            .filterKeys { it.length <= Prefs.maxNameLength }
            .entries
            .sortedByDescending { it.value.lastUsed }
            .take(maxEntries)
        return WatchLedger(kept.associate { it.key to it.value })
    }

    private fun metrics(entry: Entry?, liveSeconds: Double, liveStart: Double?): Pair<Double, Int> {
        val total = (entry?.totalSeconds ?: 0.0) + max(0.0, liveSeconds)
        var visits = entry?.visitCount ?: 0
        if (liveSeconds >= visitMinSeconds && liveStart != null) {
            val last = entry?.lastVisitStart
            if (last == null || liveStart - last >= visitGapSeconds) visits += 1
        }
        return total to visits
    }

    companion object {
        const val totalThreshold: Double = 600.0
        const val visitMinSeconds: Double = 60.0
        const val visitGapSeconds: Double = 30.0 * 60.0
        const val visitCountThreshold: Int = 3
        const val maxEntries: Int = 500

        fun key(username: String): String {
            val trimmed = username.trimSwiftWhitespace().take(Prefs.maxNameLength)
            return trimmed.lowerRoot()
        }
    }
}
