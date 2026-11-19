package vela.core.feed

import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.model.dedupeRooms
import vela.core.base.model.lowerRoot
import vela.core.base.site.CatalogPage

/**
 * 房间列表、当前位置、搜索打字预览与翻页。
 * Swift 是值类型，属性的 didSet 会重算可见列表。这里是类，方法就地改同一实例；
 * 需要两份独立状态时用 [copy]。相等比较不含可见列表缓存。
 */
class FeedStore(
    catalog: List<Room> = emptyList(),
    query: RoomQuery = RoomQuery(),
    currentUsername: String? = null,
    reachedEnd: Boolean = false,
    total: Int = 0,
) {
    var catalog: List<Room> = dedupeRooms(catalog)
        private set

    var query: RoomQuery = query
        private set

    var currentUsername: String? = currentUsername
        private set

    /**
     * 搜索框打字期间保留的当前房间。它即使不匹配输入也仍在可见列表里。
     * 回车提交（[setQuery]）或当前房间换走时失效。
     */
    var retained: String? = null
        private set

    var reachedEnd: Boolean = reachedEnd

    var total: Int = total

    private var visible: List<Room> = emptyList()

    init {
        refreshVisible()
    }

    val rooms: List<Room>
        get() = visible

    /** 按当前查询真实匹配的房间数，不含仅因 [retained] 保留的当前房间。 */
    val localMatches: Int
        get() = catalog.count { query.matches(it) }

    val index: Int
        get() {
            val list = rooms
            if (list.isEmpty()) return 0
            val name = currentUsername ?: return 0
            val found = list.indexOfFirst { it.username == name }
            return if (found >= 0) found else 0
        }

    val current: Room?
        get() {
            val list = rooms
            if (list.isEmpty()) return null
            return list[minOf(index, list.size - 1)]
        }

    fun replace(page: CatalogPage) {
        val keep = currentUsername
        updateCatalog(dedupeRooms(page.rooms))
        total = page.total
        reachedEnd = page.rooms.isEmpty() || page.nextOffset >= page.total
        if (keep != null && rooms.any { it.username == keep }) {
            updateCurrent(keep)
        } else {
            updateCurrent(rooms.firstOrNull()?.username)
        }
    }

    /**
     * 追加常规分页的一页。[offset] 是请求这一页时的游标（不传则不做「游标没前进」的兜底）。
     * 整页都是已有房间不算到底：游标仍在前进。同页内重复用户名会留下，不像 [replace] 那样去重。
     */
    fun append(page: CatalogPage, offset: Int? = null) {
        val existing = catalog.mapTo(HashSet()) { it.username.lowerRoot() }
        val fresh = page.rooms.filter { it.username.lowerRoot() !in existing }
        updateCatalog(catalog + fresh)
        total = maxOf(total, page.total)
        val stalled = offset != null && page.nextOffset <= offset
        if (page.rooms.isEmpty() || page.nextOffset >= page.total || stalled) {
            reachedEnd = true
        }
        if (currentUsername == null) {
            updateCurrent(rooms.firstOrNull()?.username)
        }
    }

    /** 把后到的候选并入当前位置之后。不改 [reachedEnd] 与 [total]。返回真正新增的房间数。 */
    fun mergeAfterCurrent(extra: List<Room>, order: (List<Room>) -> List<Room>): Int {
        val folded = PageMerge.keepingCurrent(catalog, extra, currentUsername, order)
        if (folded.added <= 0) return 0
        updateCatalog(folded.rooms)
        if (currentUsername == null) updateCurrent(rooms.firstOrNull()?.username)
        return folded.added
    }

    /**
     * 后台续页：去重后并入当前项之后并重排尾部，同时按这一页更新总量和是否到底。
     * 已经有当前房间时不改选中项。
     */
    fun mergePageAfterCurrent(page: CatalogPage, offset: Int, order: (List<Room>) -> List<Room>) {
        total = maxOf(total, page.total)
        val stalled = page.nextOffset <= offset
        if (page.rooms.isEmpty() || page.nextOffset >= page.total || stalled) {
            reachedEnd = true
        }
        mergeAfterCurrent(page.rooms, order)
    }

    /** 把主路写到可见列表里的这间。`null` 表示这路空了。不在列表里的名字不改当前房间。 */
    fun select(username: String?) {
        if (username == null) {
            updateCurrent(null)
            return
        }
        val match = rooms.firstOrNull { it.username.lowerRoot() == username.lowerRoot() } ?: return
        updateCurrent(match.username)
    }

    fun step(delta: Int): Room? = step(delta, emptySet())

    /**
     * 沿可见列表移动 [delta] 间。[skipping] 里的房间不算落点。
     * 落在这些房间上就沿 delta 方向继续找；方向上没有别的房间就停在原地。
     */
    fun step(delta: Int, skipping: Set<String>): Room? {
        val list = rooms
        if (list.isEmpty() || delta == 0) return current
        val landed = FeedPaging.landingIndex(index, delta, list.map { it.username }, skipping)
        updateCurrent(list[landed].username)
        return current
    }

    fun focus(username: String): Boolean {
        if (rooms.none { it.username.lowerRoot() == username.lowerRoot() }) return false
        select(username)
        return true
    }

    fun insertDirect(room: Room) {
        val stripped = catalog.filter { it.username.lowerRoot() != room.username.lowerRoot() }
        val at = currentUsername?.let { name -> stripped.indexOfFirst { it.username == name } } ?: -1
        val insertIndex = if (at >= 0) at + 1 else 0
        val next = stripped.toMutableList()
        next.add(insertIndex.coerceAtMost(next.size), room)
        updateCatalog(next)
        updateCurrent(room.username)
    }

    /** 把指定房间挪到当前房间之后紧接着的位置。只动当前之后的房间。 */
    fun promoteAfterCurrent(usernames: List<String>) {
        promoteAfter(currentUsername, usernames)
    }

    /** 锚点及之前不动；锚点不在目录里则什么都不做。 */
    fun promoteAfter(anchor: String?, usernames: List<String>) {
        if (anchor == null) return
        val at = catalog.indexOfFirst { it.username == anchor }
        if (at < 0) return
        val tail = catalog.subList(at + 1, catalog.size)
        val taken = HashSet<String>()
        val moved = ArrayList<Room>()
        for (name in usernames.map { it.lowerRoot() }) {
            if (name in taken) continue
            val room = tail.firstOrNull { it.username.lowerRoot() == name } ?: continue
            taken.add(name)
            moved.add(room)
        }
        if (moved.isEmpty()) return
        val rest = tail.filter { it.username.lowerRoot() !in taken }
        updateCatalog(catalog.subList(0, at + 1).toList() + moved + rest)
    }

    /** 对当前房间之后的部分整体重排；当前及之前的位置不动。没有当前房间时重排整个目录。 */
    fun reorderAfterCurrent(order: (List<Room>) -> List<Room>) {
        val found = currentUsername?.let { name -> catalog.indexOfFirst { it.username == name } }
        val start = if (found != null && found >= 0) found + 1 else 0
        if (start >= catalog.size) return
        val head = catalog.subList(0, start).toList()
        val tail = catalog.subList(start, catalog.size).toList()
        updateCatalog(head + order(tail))
    }

    /** 打字预览：只改可见列表，不换当前房间（当前房间被保留，直到提交或换走）。 */
    fun previewQuery(query: RoomQuery) {
        retained = currentUsername
        this.query = query
        refreshVisible()
    }

    fun setQuery(query: RoomQuery) {
        retained = null
        this.query = query
        refreshVisible()
        val list = rooms
        val name = currentUsername
        if (name != null && list.any { it.username == name }) return
        updateCurrent(list.firstOrNull()?.username)
    }

    /** 若当前房间被屏蔽，立刻换到它之后的下一个（没有则取之前的一个）。 */
    fun setBlocked(names: Set<String>) {
        setFilters(blocked = names, only = query.only)
    }

    /**
     * 同时更新屏蔽名单与允许名单。当前房间不再匹配时换到之后的下一个，没有则取之前的一个。
     * [hidden] 为 null 表示不动「不想看」过滤。
     */
    fun setFilters(blocked: Set<String>, only: Set<String>?, hidden: Set<String>? = null) {
        val next = query.copy(
            blocked = blocked.toSet(),
            only = only?.toSet(),
            hidden = if (hidden != null) hidden.toSet() else query.hidden,
        )
        val list = rooms
        val name = currentUsername
        if (name != null) {
            val at = list.indexOfFirst { it.username == name }
            if (at >= 0 && !next.matches(list[at])) {
                query = next
                refreshVisible()
                val forward = list.drop(at + 1).firstOrNull { next.matches(it) }
                val backward = list.take(at).lastOrNull { next.matches(it) }
                updateCurrent((forward ?: backward)?.username)
                return
            }
        }
        setQuery(next)
    }

    /**
     * 直接切到指定房间并保证它就是当前房间。搜索条件会挡住它时先清掉文本；
     * 不在列表里就插到当前之后。返回最终当前房间是否就是它。
     */
    fun reveal(room: Room): Boolean {
        if (!query.matches(room)) {
            val cleared = query.copy(text = "")
            if (cleared.matches(room)) {
                query = cleared
                refreshVisible()
            }
        }
        if (!focus(room.username)) insertDirect(room)
        return current?.username?.lowerRoot() == room.username.lowerRoot()
    }

    fun shouldLoadMore(threshold: Int = 8, blankPages: Int = 0): Boolean =
        shouldLoadMore(currentUsername, threshold, blankPages)

    /**
     * 以可见列表里的 [username] 为锚判断要不要翻页。锚点缺失时与 [index] 一样回落到 0。
     * 列表为空时不看锚点，只看还没到末尾、并且连续空页没超过上限。
     */
    fun shouldLoadMore(username: String?, threshold: Int = 8, blankPages: Int = 0): Boolean {
        val list = rooms
        if (reachedEnd) return false
        if (list.isEmpty()) return blankPages < maxBlankPages
        val position = if (username != null) {
            val found = list.indexOfFirst { it.username == username }
            if (found >= 0) found else 0
        } else {
            0
        }
        return position >= list.size - threshold
    }

    fun prefetchUsernames(ahead: Int = 1): List<String> {
        val list = rooms
        if (list.isEmpty() || ahead <= 0) return emptyList()
        val start = index + 1
        if (start >= list.size) return emptyList()
        val end = minOf(list.size, start + ahead)
        return list.subList(start, end).map { it.username }
    }

    /** 拷贝相等字段与可见列表。目录里的同页重复会保留，不重新去重。 */
    fun copy(): FeedStore {
        val cloned = FeedStore(
            catalog = emptyList(),
            query = query,
            currentUsername = null,
            reachedEnd = reachedEnd,
            total = total,
        )
        cloned.catalog = catalog.toList()
        cloned.currentUsername = currentUsername
        cloned.retained = retained
        cloned.refreshVisible()
        return cloned
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FeedStore) return false
        return catalog == other.catalog &&
            query == other.query &&
            currentUsername == other.currentUsername &&
            retained == other.retained &&
            reachedEnd == other.reachedEnd &&
            total == other.total
    }

    override fun hashCode(): Int {
        var result = catalog.hashCode()
        result = 31 * result + query.hashCode()
        result = 31 * result + (currentUsername?.hashCode() ?: 0)
        result = 31 * result + (retained?.hashCode() ?: 0)
        result = 31 * result + reachedEnd.hashCode()
        result = 31 * result + total
        return result
    }

    private fun updateCatalog(next: List<Room>) {
        catalog = next
        refreshVisible()
    }

    /** 对齐 `currentUsername` 的 didSet：换走之后保留失效。值没变也会再判一次。 */
    private fun updateCurrent(value: String?) {
        currentUsername = value
        if (retained != null && currentUsername != retained) {
            retained = null
            refreshVisible()
        }
    }

    private fun refreshVisible() {
        val keep = retained
        visible = if (keep == null) {
            catalog.filter { query.matches(it) }
        } else {
            catalog.filter { query.matches(it) || it.username == keep }
        }
    }

    companion object {
        /** 可见列表为空（已加载的页全被屏蔽或过滤）时翻页的上限。 */
        const val maxBlankPages: Int = 10
    }
}
