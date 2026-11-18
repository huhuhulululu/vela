package vela.core.taste

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import vela.core.base.model.Prefs
import vela.core.base.model.Room

/**
 * 标签在一组候选房间里的稀有度权重（类似 IDF）：
 * `idf = ln((N + 1) / (df + 1))`，再除以「只出现在 1 个房间」时的 idf `ln((N + 1) / 2)`，
 * 夹在 `[floor, 1]`。候选里没出现的标签按最稀有处理（权重 1）。候选少于 2 个时全部为 1。
 */
class TagRarity(rooms: List<Room>) {
    val roomCount: Int
    private val counts: Map<String, Int>

    init {
        val built = HashMap<String, Int>()
        for (room in rooms) {
            val keys = LinkedHashSet<String>()
            for (tag in room.tags.take(Prefs.maxTagsPerRoom)) {
                keys.add(Taste.tagKey(tag))
            }
            for (key in keys) {
                built[key] = (built[key] ?: 0) + 1
            }
        }
        roomCount = rooms.size
        counts = built
    }

    /** 含该标签的候选房间数（[key] 为 [Taste.tagKey]）。 */
    fun documentFrequency(key: String): Int = counts[key] ?: 0

    /** 归一化后的稀有度权重，[key] 为 [Taste.tagKey]。 */
    fun weight(key: String): Double {
        val n = roomCount.toDouble()
        val top = ln((n + 1.0) / 2.0)
        if (top <= 0.0) return 1.0
        val idf = ln((n + 1.0) / (documentFrequency(key).toDouble() + 1.0))
        return min(1.0, max(floor, idf / top))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TagRarity) return false
        return roomCount == other.roomCount && counts == other.counts
    }

    override fun hashCode(): Int = 31 * roomCount + counts.hashCode()

    companion object {
        /** 下限：常见标签的影响变小，但不归零。 */
        const val floor: Double = 0.1
    }
}
