package vela.core.curation

import java.time.Instant
import vela.core.base.model.Room
import vela.core.base.model.timeIntervalSince1970
import vela.core.base.site.FavoriteStatus

/** 状态缓存：按用户名（小写）记结果与查询时间，超过 [ttl] 视为过期。 */
class FavoriteCache private constructor(
    private val entries: Map<String, Entry>,
) {
    constructor() : this(emptyMap())

    private data class Entry(val status: FavoriteStatus, val checkedAt: Instant)

    /** 不论新旧：界面显示用。 */
    fun status(name: String): FavoriteStatus? = entries[Curation.key(name)]?.status

    fun fresh(name: String, now: Instant): FavoriteStatus? {
        val entry = entries[Curation.key(name)] ?: return null
        val elapsed = now.timeIntervalSince1970() - entry.checkedAt.timeIntervalSince1970()
        return if (elapsed <= ttl) entry.status else null
    }

    fun storing(statuses: Map<String, FavoriteStatus>, at: Instant): FavoriteCache {
        if (statuses.isEmpty()) return this
        val next = entries.toMutableMap()
        for ((key, status) in statuses) {
            next[Curation.key(key)] = Entry(status, at)
        }
        return FavoriteCache(next)
    }

    /** 写入带各自时间的结果（来自目录的）。已有更晚的记录不被更早的覆盖。 */
    fun storing(stamped: Map<String, FavoritePlan.Stamped>): FavoriteCache {
        if (stamped.isEmpty()) return this
        val next = entries.toMutableMap()
        for ((key, item) in stamped) {
            val normalized = Curation.key(key)
            val existing = next[normalized]
            if (existing != null &&
                existing.checkedAt.timeIntervalSince1970() > item.at.timeIntervalSince1970()
            ) {
                continue
            }
            next[normalized] = Entry(item.status, item.at)
        }
        return FavoriteCache(next)
    }

    /** 只留仍在收藏名单里的条目。 */
    fun pruned(keeping: List<String>): FavoriteCache {
        val keep = keeping.map { Curation.key(it) }.toHashSet()
        return FavoriteCache(entries.filterKeys { it in keep })
    }

    companion object {
        const val ttl: Double = 180.0
    }
}

/**
 * 最近一次从推荐、热门、新开播取回的房间。每个房间记着自己被取回的时间，
 * 追加页不会续期早先页面里房间的在线状态。
 */
class KnownCatalog {
    data class Entry(val room: Room, val at: Instant)

    private val items = ArrayList<Entry>()

    val entries: List<Entry>
        get() = items.toList()

    val rooms: List<Room>
        get() = items.map { it.room }

    /** 第一页替换，后续页追加。新进来的房间记 [at]，已有的保持原时间。 */
    fun record(page: List<Room>, firstPage: Boolean, at: Instant) {
        val added = page.map { Entry(it, at) }
        if (firstPage) {
            items.clear()
            items.addAll(added)
        } else {
            items.addAll(added)
        }
    }

    fun usable(now: Instant): List<Entry> =
        items.filter { now.timeIntervalSince1970() - it.at.timeIntervalSince1970() <= FavoriteCache.ttl }
}

/** 一次查询计划：哪些已有答案，哪些还要发请求。 */
data class FavoritePlan(
    val resolved: Map<String, FavoriteStatus>,
    val fromCatalog: Map<String, Stamped>,
    val pending: List<String>,
) {
    data class Stamped(val status: FavoriteStatus, val at: Instant)
}

object FavoriteResolver {
    fun plan(
        favorites: List<String>,
        catalog: KnownCatalog,
        cache: FavoriteCache,
        now: Instant,
    ): FavoritePlan {
        val known = HashMap<String, KnownCatalog.Entry>()
        for (entry in catalog.usable(now)) {
            val key = Curation.key(entry.room.ref)
            val existing = known[key]
            if (existing == null ||
                entry.at.timeIntervalSince1970() > existing.at.timeIntervalSince1970()
            ) {
                known[key] = entry
            }
        }
        val resolved = LinkedHashMap<String, FavoriteStatus>()
        val fromCatalog = LinkedHashMap<String, FavoritePlan.Stamped>()
        val pending = ArrayList<String>()
        for (name in favorites) {
            val key = Curation.key(name)
            if (resolved[key] != null || pending.any { Curation.key(it) == key }) continue
            val cached = cache.fresh(name, now)
            if (cached != null) {
                resolved[key] = cached
            } else {
                val entry = known[key]
                if (entry != null) {
                    val status = if (entry.room.show.isPlayable) {
                        FavoriteStatus.Online(entry.room)
                    } else {
                        FavoriteStatus.Offline
                    }
                    resolved[key] = status
                    fromCatalog[key] = FavoritePlan.Stamped(status, entry.at)
                } else {
                    pending.add(name)
                }
            }
        }
        return FavoritePlan(resolved, fromCatalog, pending)
    }

    /**
     * 本批计划查询的名字里，是否有没拿到状态的。只看计划内的名字：
     * 批次进行中新加的收藏不算缺失。
     */
    fun hasUnresolved(pending: List<String>, statuses: Map<String, FavoriteStatus>): Boolean =
        pending.any { statuses[Curation.key(it)] == null }

    /** 在线的收藏房间：人数多的在前，同人数保持收藏名单里的先后。 */
    fun onlineRooms(favorites: List<String>, statuses: Map<String, FavoriteStatus>): List<Room> {
        val seen = HashSet<String>()
        val rooms = ArrayList<Room>()
        for (name in favorites) {
            val key = Curation.key(name)
            if (!seen.add(key)) continue
            val room = statuses[key]?.room ?: continue
            rooms.add(room)
        }
        return rooms.withIndex().sortedWith { lhs, rhs ->
            if (lhs.value.viewers != rhs.value.viewers) {
                rhs.value.viewers.compareTo(lhs.value.viewers)
            } else {
                lhs.index.compareTo(rhs.index)
            }
        }.map { it.value }
    }
}

/** 打开收藏夹时的自动补查冷却。手动刷新与成功会清掉冷却。 */
data class FavoriteRetryGate(
    private var failedAt: Instant? = null,
) {
    fun allowsAutoCheck(now: Instant): Boolean {
        val failed = failedAt ?: return true
        return now.timeIntervalSince1970() - failed.timeIntervalSince1970() >= cooldown
    }

    fun recordFailure(at: Instant) {
        failedAt = at
    }

    fun reset() {
        failedAt = null
    }

    companion object {
        const val cooldown: Double = 60.0
    }
}
