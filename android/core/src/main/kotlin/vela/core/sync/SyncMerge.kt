package vela.core.sync

/** `merge(A, B) = E(U(A, B))`，随后按第 6.7 节做体积处理。 */
object SyncMerge {
    fun union(a: SyncDocument, b: SyncDocument): SyncDocument {
        val out = SyncDocument(
            rooms = a.rooms.mapValues { SyncRoom(it.value.f, it.value.b, it.value.n) }.toMutableMap(),
            veto = a.veto.toMutableMap(),
            settings = a.settings.toMutableMap(),
        )
        for ((key, room) in b.rooms) {
            val mine = out.rooms[key]
            if (mine == null) {
                out.rooms[key] = SyncRoom(room.f, room.b, room.n)
            } else {
                out.rooms[key] = SyncRoom(
                    f = SyncRegister.larger(mine.f, room.f),
                    b = SyncRegister.larger(mine.b, room.b),
                    n = SyncRegister.larger(mine.n, room.n),
                )
            }
        }
        for ((key, reg) in b.veto) out.veto[key] = SyncRegister.larger(out.veto[key], reg)!!
        for ((key, reg) in b.settings) out.settings[key] = SyncRegister.larger(out.settings[key], reg)!!
        return out
    }

    fun merge(a: SyncDocument, b: SyncDocument): SyncDocument = trim(union(a, b))

    private enum class RoomClass { FAVORITE, BLOCKED, TOMBSTONE }

    private fun classify(room: SyncRoom): RoomClass = when {
        room.isFavorite -> RoomClass.FAVORITE
        room.isBlocked -> RoomClass.BLOCKED
        else -> RoomClass.TOMBSTONE
    }

    private fun newerFirst(a: Pair<Hlc, String>, b: Pair<Hlc, String>): Boolean {
        if (a.first != b.first) return a.first > b.first
        return a.second.utf8Precedes(b.second)
    }

    fun trim(doc: SyncDocument): SyncDocument {
        val out = SyncDocument(
            rooms = doc.rooms.mapValues { SyncRoom(it.value.f, it.value.b, it.value.n) }.toMutableMap(),
            veto = doc.veto.toMutableMap(),
            settings = doc.settings.toMutableMap(),
        )
        val groups = mutableMapOf(
            RoomClass.FAVORITE to ArrayList<Pair<Hlc, String>>(),
            RoomClass.BLOCKED to ArrayList(),
            RoomClass.TOMBSTONE to ArrayList(),
        )
        for ((key, room) in doc.rooms) {
            when (classify(room)) {
                RoomClass.FAVORITE -> groups.getValue(RoomClass.FAVORITE).add(room.f!!.hlc to key)
                RoomClass.BLOCKED -> groups.getValue(RoomClass.BLOCKED).add(room.b!!.hlc to key)
                RoomClass.TOMBSTONE -> groups.getValue(RoomClass.TOMBSTONE).add(room.newestHlc to key)
            }
        }
        val caps = mapOf(
            RoomClass.FAVORITE to SyncLimits.favorites,
            RoomClass.BLOCKED to SyncLimits.blocked,
            RoomClass.TOMBSTONE to SyncLimits.roomTombstones,
        )
        for ((cls, items) in groups) {
            val cap = caps.getValue(cls)
            if (items.size > cap) {
                for (item in items.sortedWith { x, y -> if (newerFirst(x, y)) -1 else if (newerFirst(y, x)) 1 else 0 }.drop(cap)) {
                    out.rooms.remove(item.second)
                }
            }
        }
        val active = doc.veto.filter { it.value.isOn }.map { it.value.hlc to it.key }
        val dead = doc.veto.filter { !it.value.isOn }.map { it.value.hlc to it.key }
        for ((items, cap) in listOf(active to SyncLimits.activeVetoes, dead to SyncLimits.vetoTombstones)) {
            if (items.size > cap) {
                for (item in items.sortedWith { x, y -> if (newerFirst(x, y)) -1 else if (newerFirst(y, x)) 1 else 0 }.drop(cap)) {
                    out.veto.remove(item.second)
                }
            }
        }
        return out
    }

    fun withinCounts(doc: SyncDocument): Boolean {
        var fav = 0
        var blk = 0
        var tomb = 0
        for (room in doc.rooms.values) {
            when (classify(room)) {
                RoomClass.FAVORITE -> fav++
                RoomClass.BLOCKED -> blk++
                RoomClass.TOMBSTONE -> tomb++
            }
        }
        val active = doc.veto.values.count { it.isOn }
        return fav <= SyncLimits.favorites &&
            blk <= SyncLimits.blocked &&
            tomb <= SyncLimits.roomTombstones &&
            active <= SyncLimits.activeVetoes &&
            doc.veto.size - active <= SyncLimits.vetoTombstones
    }

    data class Fitted(val doc: SyncDocument, val bytes: Int, val fits: Boolean)

    /**
     * 第 6.7 节：先执行 `E`，再实测；超过上限时按「最旧墓碑」全序依次删除墓碑。
     * 全序：HLC 字节升序；同 HLC 房间墓碑在前、「不想看」墓碑在后；再按键字节升序。
     */
    fun fitSize(input: SyncDocument, limit: Int = SyncLimits.plaintextBytes): Fitted {
        val doc = trim(input)
        var size = doc.encodedSize
        if (size <= limit) return Fitted(doc, size, true)
        data class Tomb(val hlc: Hlc, val isVeto: Boolean, val key: String)
        val tombs = ArrayList<Tomb>()
        for ((key, room) in doc.rooms) {
            if (classify(room) == RoomClass.TOMBSTONE) tombs.add(Tomb(room.newestHlc, false, key))
        }
        for ((key, reg) in doc.veto) {
            if (!reg.isOn) tombs.add(Tomb(reg.hlc, true, key))
        }
        tombs.sortWith { a, b ->
            if (a.hlc != b.hlc) return@sortWith if (a.hlc < b.hlc) -1 else 1
            if (a.isVeto != b.isVeto) return@sortWith if (!a.isVeto) -1 else 1
            a.key.utf8Compare(b.key)
        }
        for (tomb in tombs) {
            if (size <= limit) break
            if (tomb.isVeto) {
                val comma = if (doc.veto.size > 1) 1 else 0
                size -= SyncDocument.pairBytes(tomb.key, doc.veto.getValue(tomb.key)) + comma
                doc.veto.remove(tomb.key)
            } else {
                val comma = if (doc.rooms.size > 1) 1 else 0
                size -= SyncDocument.roomBytes(tomb.key, doc.rooms.getValue(tomb.key)) + comma
                doc.rooms.remove(tomb.key)
            }
        }
        return Fitted(doc, size, size <= limit)
    }
}
