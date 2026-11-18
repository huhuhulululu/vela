package vela.core.recommend

import java.time.Instant
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import vela.core.base.model.FeedSource
import vela.core.base.model.Prefs
import vela.core.base.model.Room
import vela.core.base.model.lowerRoot
import vela.core.base.model.timeIntervalSince1970
import vela.core.ledger.StreamerAffinity
import vela.core.taste.BetaSampler
import vela.core.taste.Taste

/**
 * 本机「猜你喜欢」：按标签的 Beta-Bernoulli 汤普森采样排序。
 * 每个标签（与性别）从自己的后验里抽一个样本当「这次它有多对味」，房间取其标签样本里最高的三个求均值
 * （再混入 25% 的性别样本），加上很小的热度项做平局区分，减去看过降权，收藏加分。
 * 探索来自后验本身的不确定性：证据少的标签方差大、偶尔抽到高值，证据多的标签稳定，因此不再另加随机位。
 */
object Recommender {
    const val viewerCeiling: Double = 20_000.0
    const val seenWindow: Double = 6.0 * 3_600.0

    /** 热度权重很小：只在口味样本相近时分先后。 */
    const val hotWeight: Double = 0.04
    const val seenWeight: Double = 0.6

    /** 收藏的用户加分：大于看过降权的上限，收藏过的主播刚看过也不沉底。 */
    const val favoriteBoost: Double = 1.0
    const val topTags: Int = 3
    const val genderShare: Double = 0.25
    const val reasonMinHit: Double = 1.0
    const val reasonMinMean: Double = 0.6

    fun hot(room: Room): Double {
        val viewers = max(room.viewers, 0).toDouble()
        return min(1.0, ln(1.0 + viewers) / ln(1.0 + viewerCeiling))
    }

    fun seenPenalty(room: Room, taste: Taste, now: Instant): Double {
        val last = taste.lastSeen(room.username) ?: return 0.0
        val age = now.timeIntervalSince1970() - last.timeIntervalSince1970()
        if (age < 0.0 || age >= seenWindow) return 0.0
        return seenWeight * (1.0 - age / seenWindow)
    }

    fun arrange(
        rooms: List<Room>,
        source: FeedSource,
        taste: Taste,
        now: Instant,
        seed: ULong,
        favorites: Set<String> = emptySet(),
        signals: RankSignals = RankSignals.none,
    ): List<Room> = when (source) {
        FeedSource.guess -> recommend(rooms, taste, now, seed, favorites, signals)
        FeedSource.fresh -> newest(rooms)
        FeedSource.popular, FeedSource.favorites -> rooms
    }

    /** 开播时长从短到长；没有时长字段的排在后面，同值保持原顺序。 */
    fun newest(rooms: List<Room>): List<Room> {
        return rooms.withIndex().sortedWith { lhs, rhs ->
            val a = lhs.value.secondsOnline
            val b = rhs.value.secondsOnline
            when {
                a != null && b != null -> if (a != b) a.compareTo(b) else lhs.index.compareTo(rhs.index)
                a == null && b == null -> lhs.index.compareTo(rhs.index)
                a != null -> -1
                else -> 1
            }
        }.map { it.value }
    }

    /**
     * 同一个种子与输入给出同一个顺序。同一标签在一次排序里只抽一次样本（每个臂一个样本）。
     * 「不想看」不在这里删房间：可见性由 `RoomQuery.hidden` 管，房间留在目录里，恢复后才排得回来。
     * `signals` 为空（默认）时与旧排序完全一致；给了稀有度时每个标签样本按权重 w 向 0.5 收缩
     * （`0.5 + w * (样本 - 0.5)`，常见标签几乎不再区分房间）；给了观看账本时非收藏主播加亲和度（有上限）。
     */
    fun recommend(
        rooms: List<Room>,
        taste: Taste,
        now: Instant,
        seed: ULong,
        favorites: Set<String> = emptySet(),
        signals: RankSignals = RankSignals.none,
    ): List<Room> {
        val rng = SplitMix64(seed)
        val draws = HashMap<String, Double>()
        fun draw(key: String): Double {
            draws[key]?.let { return it }
            val posterior = taste.posterior(key, now)
            var value = BetaSampler.sample(alpha = posterior.alpha, beta = posterior.beta, rng = rng)
            val rarity = signals.rarity
            if (rarity != null) value = 0.5 + rarity.weight(key) * (value - 0.5)
            draws[key] = value
            return value
        }
        fun theta(room: Room): Double {
            val samples = room.tags.take(Prefs.maxTagsPerRoom).map { draw(Taste.tagKey(it)) }
            val top = samples.sortedWith(::descendingDouble).take(topTags)
            val tagPart = if (top.isEmpty()) {
                BetaSampler.sample(alpha = 1.0, beta = 1.0, rng = rng)
            } else {
                top.sum() / top.size.toDouble()
            }
            val gender = room.gender ?: return tagPart
            return (1.0 - genderShare) * tagPart + genderShare * draw(Taste.genderKey(gender))
        }
        val scored = rooms.map { room ->
            val score = theta(room) + hotWeight * hot(room) - seenPenalty(room, taste, now) + bonus(room, favorites, signals)
            room to score
        }
        return scored.sortedWith { left, right ->
            if (left.second != right.second) {
                if (left.second > right.second) -1 else 1
            } else {
                left.first.username.compareTo(right.first.username)
            }
        }.map { it.first }
    }

    /**
     * 收藏加分与主播亲和度不叠加：收藏的取 [favoriteBoost]，其余取亲和度
     * （上限 `StreamerAffinity.cap` 小于收藏加分）。
     */
    internal fun bonus(room: Room, favorites: Set<String>, signals: RankSignals): Double {
        if (room.username.lowerRoot() in favorites) return favoriteBoost
        val ledger = signals.ledger ?: return 0.0
        return StreamerAffinity.score(ledger = ledger, username = room.username, blocked = signals.blocked)
    }

    /** 推荐理由，单行短句：收藏的主播；有足够依据时取该房间命中率最高的 1 到 2 个标签；否则「试试新口味」。 */
    fun reason(room: Room, taste: Taste, favorites: Set<String>, now: Instant): String {
        if (room.username.lowerRoot() in favorites) return "你收藏的主播"
        val liked = ArrayList<Pair<String, Double>>()
        for (tag in room.tags.take(Prefs.maxTagsPerRoom)) {
            val key = Taste.tagKey(tag)
            val belief = taste.beliefs[key] ?: continue
            if (belief.vetoed) continue
            val posterior = taste.posterior(key, now)
            val mean = posterior.alpha / (posterior.alpha + posterior.beta)
            if (posterior.alpha - 1.0 >= reasonMinHit && mean >= reasonMinMean) liked.add(tag to mean)
        }
        val best = liked.sortedWith { left, right ->
            if (left.second != right.second) {
                if (left.second > right.second) -1 else 1
            } else {
                left.first.compareTo(right.first)
            }
        }.take(2)
        if (best.isEmpty()) return "试试新口味"
        return "因为你常看 " + best.joinToString("、") { it.first }
    }

    /** 在候选里找尚未看过、标签相近的房间，按相似度取前 [limit] 个用户名。 */
    fun related(to: Room, among: List<Room>, taste: Taste, limit: Int = 3): List<String> {
        val mine = to.tags.map { it.lowerRoot() }.toSet()
        if (mine.isEmpty() || limit <= 0) return emptyList()
        val scored = ArrayList<Pair<Room, Double>>()
        for (other in among) {
            if (other.username.lowerRoot() == to.username.lowerRoot()) continue
            if (taste.lastSeen(other.username) != null) continue
            val theirs = other.tags.map { it.lowerRoot() }.toSet()
            val shared = mine.intersect(theirs).size
            if (shared == 0) continue
            val jaccard = shared.toDouble() / mine.union(theirs).size.toDouble()
            scored.add(other to jaccard + if (other.gender == to.gender) 0.1 else 0.0)
        }
        return scored.sortedWith { left, right ->
            if (left.second != right.second) {
                if (left.second > right.second) -1 else 1
            } else if (left.first.viewers != right.first.viewers) {
                right.first.viewers.compareTo(left.first.viewers)
            } else {
                left.first.username.compareTo(right.first.username)
            }
        }.take(limit).map { it.first.username }
    }

    /** 与 Swift `sorted(by: >)` 一致：大的在前，相等保持稳定。 */
    private fun descendingDouble(left: Double, right: Double): Int = when {
        left != right && left > right -> -1
        left != right && left < right -> 1
        else -> 0
    }
}
