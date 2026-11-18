package vela.core.curation

import java.net.URI
import java.time.Instant
import vela.core.base.model.BroadcastClock
import vela.core.base.model.Room
import vela.core.base.model.ViewerCount
import vela.core.base.model.trimSwiftWhitespace
import vela.core.base.site.FavoriteStatus

/** 收藏夹弹窗里的一行。 */
data class FavoriteEntry(
    val username: String,
    val state: State,
    val room: Room?,
    val thumbnailURL: URI?,
    val detail: String,
) {
    val id: String
        get() = username

    enum class State {
        online,
        offline,
        restricted,
        missing,
        unknown,
    }
}

/**
 * 收藏夹弹窗的数据：在线的在前（人数多的在前），其余按离线、地区受限、未查询、不存在分段。
 * 离线的按上次开播由近到远，不知道时间的排在离线末尾。
 */
data class FavoritesBoard(
    val online: List<FavoriteEntry>,
    val others: List<FavoriteEntry>,
) {
    val isEmpty: Boolean
        get() = online.isEmpty() && others.isEmpty()

    val onlineCount: Int
        get() = online.size

    /** 菜单栏列表顶部的一行摘要。 */
    fun menuSummary(checking: Boolean): String {
        if (isEmpty) return "还没有收藏"
        if (online.isNotEmpty()) return "$onlineCount 位在线"
        if (checking) return "正在查询…"
        if (others.all { it.state == FavoriteEntry.State.unknown }) return "还没有查询状态"
        return "收藏的人现在都不在线"
    }

    companion object {
        /**
         * [statuses] 的键按用户名不区分大小写匹配。没有状态时，[catalog] 里可播放的房间算在线。
         * [lastBroadcast] 的键为小写用户名。
         */
        fun make(
            favorites: List<String>,
            statuses: Map<String, FavoriteStatus>,
            catalog: List<Room>,
            lastBroadcast: Map<String, Instant> = emptyMap(),
            now: Instant = Instant.now(),
        ): FavoritesBoard {
            val byKey = HashMap<String, FavoriteStatus>()
            for (key in statuses.keys.sorted()) {
                val normalized = Curation.key(key)
                if (byKey[normalized] == null || key == normalized) {
                    val status = statuses[key] ?: continue
                    byKey[normalized] = status
                }
            }
            val playable = HashMap<String, Room>()
            for (room in catalog) {
                if (!room.show.isPlayable) continue
                val key = Curation.key(room.username)
                if (playable[key] == null) playable[key] = room
            }

            val seen = HashSet<String>()
            val onlineEntries = ArrayList<FavoriteEntry>()
            val offline = ArrayList<FavoriteEntry>()
            val restricted = ArrayList<FavoriteEntry>()
            val unknown = ArrayList<FavoriteEntry>()
            val missing = ArrayList<FavoriteEntry>()
            for (raw in favorites) {
                val name = raw.trimSwiftWhitespace()
                val key = Curation.key(name)
                if (key.isEmpty() || !seen.add(key)) continue
                val status = byKey[key] ?: playable[key]?.let { FavoriteStatus.Online(it) }
                when (status) {
                    is FavoriteStatus.Online -> onlineEntries.add(
                        FavoriteEntry(
                            username = name,
                            state = FavoriteEntry.State.online,
                            room = status.room,
                            thumbnailURL = status.room.thumbnailURL
                                ?: URI.create("https://thumb.live.mmcdn.com/riw/$key.jpg"),
                            detail = onlineDetail(status.room),
                        ),
                    )
                    FavoriteStatus.Offline -> {
                        val whenOnline = lastBroadcast[key]
                        val detail = if (whenOnline != null) BroadcastAgo.string(whenOnline, now) else "离线"
                        offline.add(FavoriteEntry(name, FavoriteEntry.State.offline, null, null, detail))
                    }
                    FavoriteStatus.Restricted ->
                        restricted.add(
                            FavoriteEntry(name, FavoriteEntry.State.restricted, null, null, "当前地区不可观看"),
                        )
                    FavoriteStatus.NotFound ->
                        missing.add(FavoriteEntry(name, FavoriteEntry.State.missing, null, null, "账号不存在"))
                    null -> unknown.add(FavoriteEntry(name, FavoriteEntry.State.unknown, null, null, "未查询"))
                }
            }

            val sortedOnline = onlineEntries.withIndex().sortedWith { lhs, rhs ->
                val left = lhs.value.room?.viewers ?: 0
                val right = rhs.value.room?.viewers ?: 0
                if (left != right) right.compareTo(left) else lhs.index.compareTo(rhs.index)
            }.map { it.value }
            return FavoritesBoard(
                online = sortedOnline,
                others = recentFirst(offline, lastBroadcast) + restricted + unknown + missing,
            )
        }

        /** 离线分组：上次开播近的在前；没有时间的排在后面，同组保持收藏先后。 */
        internal fun recentFirst(
            entries: List<FavoriteEntry>,
            lastBroadcast: Map<String, Instant>,
        ): List<FavoriteEntry> = entries.withIndex().sortedWith { lhs, rhs ->
            val left = lastBroadcast[Curation.key(lhs.value.username)]
            val right = lastBroadcast[Curation.key(rhs.value.username)]
            when {
                left != null && right != null ->
                    if (left != right) right.compareTo(left) else lhs.index.compareTo(rhs.index)
                left != null -> -1
                right != null -> 1
                else -> lhs.index.compareTo(rhs.index)
            }
        }.map { it.value }

        private fun onlineDetail(room: Room): String {
            val viewers = "${ViewerCount.string(room.viewers)}人在看"
            val clock = BroadcastClock.string(room.secondsOnline) ?: return viewers
            return "$viewers · $clock"
        }
    }
}
