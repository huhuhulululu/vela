package vela.core.taste

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Prefs
import vela.core.base.model.Room
import vela.core.base.model.TasteKeys
import vela.core.base.model.lowerRoot
import vela.core.base.model.timeIntervalSince1970
import vela.core.sites.RoomKey
import vela.core.sites.SiteKeyMigration
import vela.core.sites.SiteKeys

/** 一个标签（或性别）的命中与落空证据。Beta-Bernoulli：后验是 Beta(1 + hit, 1 + miss)。 */
@Serializable
data class Belief(
    val hit: Double = 0.0,
    val miss: Double = 0.0,
    val at: Double = 0.0,
    val hits: Int = 0,
    val misses: Int = 0,
    /** 用户明确标了「不想看」：不衰减，排序时直接过滤。 */
    val vetoed: Boolean = false,
)

/** 某一天「猜你喜欢」里的命中与落空次数。 */
@Serializable
data class DayTally(
    val hit: Int = 0,
    val miss: Int = 0,
)

/** 衰减到 now 之后的 Beta 参数（先验 α = β = 1）。 */
data class Posterior(
    val alpha: Double,
    val beta: Double,
)

/**
 * 本机口味画像：按标签与性别的命中 / 落空统计、最近看过、每日命中率。
 * 更新返回新值。不含旧版 `Preferences` 迁移。
 *
 * `tagKey` / `genderKey` 转调 [TasteKeys]。时刻参数是 [Instant]，时长和证据仍是 `Double` 秒。
 */
@Serializable(with = TasteSerializer::class)
data class Taste(
    val beliefs: Map<String, Belief> = emptyMap(),
    val seen: Map<String, Double> = emptyMap(),
    val daily: Map<String, DayTally> = emptyMap(),
    val paused: Boolean = false,
) {
    fun posterior(key: String, now: Instant): Posterior {
        val belief = beliefs[key] ?: return Posterior(alpha = 1.0, beta = 1.0)
        val factor = if (belief.vetoed) 1.0 else decay(now.timeIntervalSince1970() - belief.at)
        return Posterior(alpha = 1.0 + belief.hit * factor, beta = 1.0 + belief.miss * factor)
    }

    fun mean(key: String, now: Instant): Double {
        val value = posterior(key, now)
        return value.alpha / (value.alpha + value.beta)
    }

    fun isVetoed(key: String): Boolean = beliefs[key]?.vetoed ?: false

    /** 所有「不想看」的口味键，供列表可见性过滤使用。 */
    val vetoedKeys: Set<String>
        get() = beliefs.filterValues { it.vetoed }.keys.toSet()

    fun isVetoed(room: Room): Boolean {
        if (room.tags.take(Prefs.maxTagsPerRoom).any { isVetoed(tagKey(it)) }) return true
        val gender = room.gender ?: return false
        return isVetoed(genderKey(gender))
    }

    fun lastSeen(username: String): Instant? {
        val seconds = seen[clipped(username)] ?: return null
        return instantFromSeconds(seconds)
    }

    /** 外部参数名与 Swift `marking(seen:)` 一致。 */
    fun marking(seen: String, now: Instant): Taste {
        val next = this.seen.toMutableMap()
        next[clipped(seen)] = now.timeIntervalSince1970()
        return copy(seen = trimmedSeen(next, now))
    }

    fun recording(
        room: Room,
        watched: Double,
        source: FeedSource,
        now: Instant,
        rarity: TagRarity? = null,
    ): Taste = recording(
        room = room,
        learnSeconds = watched,
        daily = watched,
        source = source,
        now = now,
        rarity = rarity,
    )

    /**
     * 学习秒数与每日统计分开记。某一边传 null 表示这一边不记（仍标记已看过）。
     * 两边传入同一个秒数时，结果与 [recording] 的 `watched` 重载相同。
     */
    fun recording(
        room: Room,
        learnSeconds: Double?,
        daily: Double?,
        source: FeedSource,
        now: Instant,
        rarity: TagRarity? = null,
    ): Taste = settling(
        room = room,
        feedback = if (learnSeconds != null) Feedback.grade(learnSeconds) else Feedback.none,
        dwell = daily,
        source = source,
        now = now,
        rarity = rarity,
    )

    /** 收藏：比停留更强的命中信号。暂停时原样返回，不标记已看过。 */
    fun following(room: Room, now: Instant, rarity: TagRarity? = null): Taste {
        if (paused) return this
        return applying(room, hit = followStep, miss = 0.0, now = now, rarity = rarity)
    }

    /** 对当前房间不感兴趣：强落空，也标记看过。 */
    fun rejecting(room: Room, source: FeedSource, now: Instant): Taste {
        val base = marking(room.ref, now)
        if (paused) return base
        var next = base.applying(room, hit = 0.0, miss = rejectStep, now = now, rarity = null)
        if (source == FeedSource.guess) next = next.withTally(isHit = false, now = now)
        return next
    }

    /** 手动加强：先撤销「不想看」，再衰减并加一个固定值。不累计显示用的次数。 */
    fun boosting(key: String, now: Instant): Taste {
        val next = beliefs.toMutableMap()
        val current = next[key]
        if (current != null) next[key] = current.copy(vetoed = false)
        bump(next, key, hit = boostStep, miss = 0.0, counts = false, now = now)
        return copy(beliefs = trimmedBeliefs(next, now))
    }

    fun vetoing(key: String, now: Instant): Taste {
        val next = beliefs.toMutableMap()
        val current = next[key] ?: Belief()
        next[key] = current.copy(vetoed = true, at = now.timeIntervalSince1970())
        return copy(beliefs = trimmedBeliefs(next, now))
    }

    /**
     * 恢复：撤销「不想看」。明确标的取消过滤；学出来的不想看去掉落空证据。
     * 没有命中证据时画像里不留空条目。
     */
    fun restoring(key: String, now: Instant): Taste {
        val current = beliefs[key] ?: return this
        val factor = if (current.vetoed) 1.0 else decay(now.timeIntervalSince1970() - current.at)
        val hit = current.hit * factor
        val updated = current.copy(
            hit = hit,
            miss = 0.0,
            misses = 0,
            vetoed = false,
            at = now.timeIntervalSince1970(),
        )
        val next = beliefs.toMutableMap()
        if (updated.hit < 0.05 && updated.hits == 0) next.remove(key) else next[key] = updated
        return copy(beliefs = next)
    }

    fun removing(key: String): Taste {
        if (key !in beliefs) return this
        return copy(beliefs = beliefs - key)
    }

    fun settingPaused(value: Boolean): Taste = copy(paused = value)

    /** 清掉全部标签与性别统计和每日命中率，保留最近看过与暂停设置。 */
    fun resettingProfile(): Taste = Taste(seen = seen, paused = paused)

    /** 连最近看过一起清，只保留暂停设置。 */
    fun erased(): Taste = Taste(paused = paused)

    fun capped(now: Instant): Taste {
        val keyLimit = Prefs.maxNameLength + 2
        val nextBeliefs = trimmedBeliefs(beliefs.filterKeys { it.length <= keyLimit }, now)
        val nextSeen = trimmedSeen(seen.filterKeys { it.length <= Prefs.maxNameLength }, now)
        val nextDaily = if (daily.size > maxDays) {
            daily.entries.sortedByDescending { it.key }.take(maxDays).associate { it.key to it.value }
        } else {
            daily
        }
        return copy(beliefs = nextBeliefs, seen = nextSeen, daily = nextDaily)
    }

    fun summary(now: Instant, zone: ZoneId = ZoneId.systemDefault()): TasteSummary {
        val liked = ArrayList<TasteRow>()
        val disliked = ArrayList<TasteRow>()
        val genders = ArrayList<TasteRow>()
        var tagCount = 0
        for ((key, belief) in beliefs) {
            val value = posterior(key, now)
            val rowMean = value.alpha / (value.alpha + value.beta)
            val row = TasteRow(
                key = key,
                name = key.drop(2),
                mean = rowMean,
                hits = belief.hits,
                vetoed = belief.vetoed,
            )
            if (key.startsWith("g:")) {
                genders.add(row)
                continue
            }
            tagCount += 1
            val learnedDislike = value.beta - 1.0 >= learnedDislikeEvidence && row.mean <= learnedDislikeMean
            if (belief.vetoed || learnedDislike) {
                disliked.add(row)
            } else if (value.alpha - 1.0 >= 0.5 && row.mean >= 0.5) {
                liked.add(row)
            }
        }
        val week = weekBars(daily, now, zone)
        val hit = week.sumOf { it.hit }
        val total = week.sumOf { it.total }
        return TasteSummary(
            liked = liked.sortedWith(::compareRows).take(maxLikedRows),
            disliked = disliked.sortedBy { it.name },
            genders = genders.sortedWith(::compareRows),
            tagCount = tagCount,
            week = week,
            hitRate = if (total >= minRateEvents) hit.toDouble() / total.toDouble() else null,
        )
    }

    /** 冷启动整理：候选房间里最常见、画像里还没有的标签。 */
    fun suggestedTags(rooms: List<Room>, limit: Int = 12): List<String> {
        val counts = LinkedHashMap<String, Int>()
        for (room in rooms) {
            val tags = LinkedHashSet<String>()
            for (tag in room.tags.take(Prefs.maxTagsPerRoom)) {
                tags.add(tag.lowerRoot())
            }
            for (tag in tags) {
                if (tag.isEmpty() || tag.length > Prefs.maxNameLength) continue
                if (beliefs[tagKey(tag)] != null) continue
                counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return counts.entries
            .sortedWith { left, right ->
                if (left.value != right.value) right.value - left.value else left.key.compareTo(right.key)
            }
            .take(limit)
            .map { it.key }
    }

    private fun settling(
        room: Room,
        feedback: Feedback,
        dwell: Double?,
        source: FeedSource,
        now: Instant,
        rarity: TagRarity?,
    ): Taste {
        val base = marking(room.ref, now)
        if (paused) return base
        var next = base
        if (feedback != Feedback.none) {
            val scale = if (source == FeedSource.guess) 1.0 else otherSourceScale
            next = base.applying(
                room,
                hit = feedback.hit * scale,
                miss = feedback.miss * scale,
                now = now,
                rarity = rarity,
            )
        }
        if (source == FeedSource.guess && dwell != null) {
            val isHit = Feedback.dailyOutcome(dwell)
            if (isHit != null) next = next.withTally(isHit, now)
        }
        return next
    }

    private fun applying(room: Room, hit: Double, miss: Double, now: Instant, rarity: TagRarity?): Taste {
        val next = beliefs.toMutableMap()
        for (tag in room.tags.take(Prefs.maxTagsPerRoom)) {
            val key = tagKey(tag)
            val weight = rarity?.weight(key) ?: 1.0
            bump(next, key, hit = hit * weight, miss = miss * weight, counts = true, now = now)
        }
        val gender = room.gender
        if (gender != null) {
            bump(
                next,
                genderKey(gender),
                hit = hit * genderScale,
                miss = miss * genderScale,
                counts = true,
                now = now,
            )
        }
        return copy(beliefs = trimmedBeliefs(next, now))
    }

    private fun withTally(isHit: Boolean, now: Instant): Taste {
        val next = daily.toMutableMap()
        val key = dayKey(now)
        val current = next[key] ?: DayTally()
        next[key] = if (isHit) current.copy(hit = current.hit + 1) else current.copy(miss = current.miss + 1)
        if (next.size > maxDays) {
            val drop = next.keys.sorted().dropLast(maxDays)
            for (old in drop) next.remove(old)
        }
        return copy(daily = next.toMap())
    }

    companion object {
        const val halfLife: Double = 7.0 * 86_400
        const val maxBeliefs: Int = 500
        const val maxDays: Int = 30

        /** 手动加强一次相当于多少次命中。 */
        const val boostStep: Double = 3.0

        /** 「不感兴趣」相当于多少次落空。 */
        const val rejectStep: Double = 3.0

        /** 收藏相当于多少次命中，与 [Feedback.followHit] 一致。 */
        const val followStep: Double = Feedback.followHit

        const val minRateEvents: Int = 10
        const val coldStartTags: Int = 5

        /** 性别是粗粒度维度，证据按此比例折算，免得压过标签。 */
        internal const val genderScale: Double = 0.5

        /** 非「猜你喜欢」来源的停留也学习，但证据减半。 */
        internal const val otherSourceScale: Double = 0.5

        internal const val maxLikedRows: Int = 30
        internal const val learnedDislikeEvidence: Double = 3.0
        internal const val learnedDislikeMean: Double = 0.25

        fun tagKey(tag: String): String = TasteKeys.tagKey(tag)

        fun genderKey(gender: Gender): String = TasteKeys.genderKey(gender)

        /** 默认时区是系统时区，对齐 Swift `Calendar.current`。测试要固定时区时传入 [zone]。 */
        fun dayKey(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
            val date = instant.atZone(zone).toLocalDate()
            return String.format(Locale.ROOT, "%04d-%02d-%02d", date.year, date.monthValue, date.dayOfMonth)
        }

        private fun decay(age: Double): Double = 0.5.pow(max(0.0, age) / halfLife)

        private fun clipped(name: String): String = seenKey(name)

        /** 小写 ref，再截断到用户名上限。裸名与 `cb:` 归到同一键。 */
        internal fun seenKey(name: String): String =
            RoomKey.normalizeRef(name).take(Prefs.maxNameLength)

        private fun bump(
            beliefs: MutableMap<String, Belief>,
            key: String,
            hit: Double,
            miss: Double,
            counts: Boolean,
            now: Instant,
        ) {
            val existing = beliefs[key]
            var belief = existing ?: Belief()
            if (existing != null && !belief.vetoed) {
                val factor = decay(now.timeIntervalSince1970() - belief.at)
                belief = belief.copy(hit = belief.hit * factor, miss = belief.miss * factor)
            }
            var hits = belief.hits
            var misses = belief.misses
            if (counts && hit > 0.0) hits += 1
            if (counts && miss > 0.0) misses += 1
            beliefs[key] = belief.copy(
                hit = belief.hit + hit,
                miss = belief.miss + miss,
                at = now.timeIntervalSince1970(),
                hits = hits,
                misses = misses,
            )
        }

        /** 超出上限时保留证据最强的标签（「不想看」优先）。强度相同保持原顺序。 */
        private fun trimmedBeliefs(beliefs: Map<String, Belief>, now: Instant): Map<String, Belief> {
            if (beliefs.size <= maxBeliefs) return beliefs.toMap()
            val nowSeconds = now.timeIntervalSince1970()
            fun strength(belief: Belief): Double {
                if (belief.vetoed) return Double.POSITIVE_INFINITY
                return (belief.hit + belief.miss) * decay(nowSeconds - belief.at)
            }
            return beliefs.entries
                .sortedWith(compareByDescending { strength(it.value) })
                .take(maxBeliefs)
                .associate { it.key to it.value }
        }

        private fun trimmedSeen(seen: Map<String, Double>, now: Instant): Map<String, Double> {
            val cutoff = now.timeIntervalSince1970() - Prefs.seenRetention
            val live = seen.filterValues { it >= cutoff }
            if (live.size <= Prefs.maxSeen) return live.toMap()
            return live.entries
                .sortedByDescending { it.value }
                .take(Prefs.maxSeen)
                .associate { it.key to it.value }
        }

        /** 含今天的最近 7 天，旧的在前。`weekday` 周日为 1。 */
        private fun weekBars(daily: Map<String, DayTally>, now: Instant, zone: ZoneId): List<DayBar> {
            val start = now.atZone(zone)
            val bars = ArrayList<DayBar>(7)
            for (back in 6 downTo 0) {
                val day = start.minusDays(back.toLong()).toLocalDate()
                val key = String.format(Locale.ROOT, "%04d-%02d-%02d", day.year, day.monthValue, day.dayOfMonth)
                val tally = daily[key] ?: DayTally()
                bars.add(DayBar(weekday = swiftWeekday(day), hit = tally.hit, miss = tally.miss))
            }
            return bars
        }

        private fun swiftWeekday(date: LocalDate): Int = when (date.dayOfWeek) {
            DayOfWeek.SUNDAY -> 1
            DayOfWeek.MONDAY -> 2
            DayOfWeek.TUESDAY -> 3
            DayOfWeek.WEDNESDAY -> 4
            DayOfWeek.THURSDAY -> 5
            DayOfWeek.FRIDAY -> 6
            DayOfWeek.SATURDAY -> 7
        }

        /** 均值降序，再按命中次数降序，再按名字升序。 */
        private fun compareRows(left: TasteRow, right: TasteRow): Int {
            if (left.mean != right.mean) return if (left.mean > right.mean) -1 else 1
            if (left.hits != right.hits) return if (left.hits > right.hits) -1 else 1
            return left.name.compareTo(right.name)
        }

        private fun instantFromSeconds(seconds: Double): Instant {
            val whole = floor(seconds).toLong()
            var nanos = ((seconds - whole.toDouble()) * 1_000_000_000.0).toLong()
            if (nanos >= 1_000_000_000L) return Instant.ofEpochSecond(whole + 1, 0)
            if (nanos < 0L) nanos = 0L
            return Instant.ofEpochSecond(whole, nanos)
        }
    }
}

object TasteSerializer : KSerializer<Taste> {
    override val descriptor: SerialDescriptor = Disk.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Taste) {
        val disk = LinkedHashMap<String, Double>()
        for ((key, seenAt) in value.seen) {
            val stored = SiteKeys.disk(key)
            disk[stored] = maxOf(disk[stored] ?: 0.0, seenAt)
        }
        encoder.encodeSerializableValue(
            Disk.serializer(),
            Disk(
                beliefs = value.beliefs,
                seen = disk,
                daily = value.daily,
                paused = value.paused,
                keyVersion = SiteKeyMigration.version,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): Taste {
        val raw = decoder.decodeSerializableValue(Incoming.serializer())
        val merged = LinkedHashMap<String, Double>()
        for ((key, seenAt) in raw.seen) {
            val clipped = Taste.seenKey(SiteKeys.memory(key))
            merged[clipped] = maxOf(merged[clipped] ?: 0.0, seenAt)
        }
        return Taste(
            beliefs = raw.beliefs,
            seen = merged,
            daily = raw.daily,
            paused = raw.paused,
        )
    }

    @Serializable
    private data class Incoming(
        val beliefs: Map<String, Belief> = emptyMap(),
        val seen: Map<String, Double> = emptyMap(),
        val daily: Map<String, DayTally> = emptyMap(),
        val paused: Boolean = false,
        val keyVersion: Int = 0,
    )

    @Serializable
    private data class Disk(
        val beliefs: Map<String, Belief>,
        val seen: Map<String, Double>,
        val daily: Map<String, DayTally>,
        val paused: Boolean,
        val keyVersion: Int,
    )
}
