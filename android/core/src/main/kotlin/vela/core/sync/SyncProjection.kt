package vela.core.sync

import vela.core.curation.Curation
import vela.core.taste.Belief
import vela.core.taste.Taste

/** 投影：同步文档 → 本机视图。只读文档，不写回。 */
class SyncProjection(val doc: SyncDocument) {
    data class Entry(val key: String, val display: String)

    val favorites: List<Entry>
    val blocked: List<Entry>
    val veto: Map<String, Boolean>
    val settings: Map<String, Boolean>

    init {
        val favs = ArrayList<Pair<Hlc, String>>()
        val blocks = ArrayList<Pair<Hlc, String>>()
        for ((key, room) in doc.rooms) {
            if (room.isFavorite) favs.add(room.f!!.hlc to key)
            else if (room.isBlocked) blocks.add(room.b!!.hlc to key)
        }
        fun entries(items: List<Pair<Hlc, String>>): List<Entry> =
            items.sortedWith { a, b ->
                if (a.first != b.first) {
                    if (a.first > b.first) -1 else 1
                } else {
                    a.second.utf8Compare(b.second)
                }
            }.map { item ->
                Entry(item.second, doc.rooms.getValue(item.second).n?.payload ?: SyncKeys.usernameOf(item.second))
            }
        favorites = entries(favs)
        blocked = entries(blocks)
        veto = doc.veto.mapValues { it.value.isOn }
        settings = doc.settings.mapValues { it.value.payload == "1" }
    }

    fun isFavorite(key: String): Boolean = favorites.any { it.key == key }
    fun isBlocked(key: String): Boolean = blocked.any { it.key == key }

    /** 本机 `Curation`：只取 `cb:` 键，去前缀用显示名写回。 */
    val curation: Curation
        get() {
            fun local(list: List<Entry>) = list.filter { SyncKeys.isProjected(it.key) }.map { it.display }
            return Curation(favorites = local(favorites), blocked = local(blocked))
        }

    /**
     * 文档里的每个「不想看」键按载荷设置；本机已标、属于同步域、但文档里没有的键撤销。
     * 同步域外的键不动。只改 `vetoed`。
     */
    fun applying(to: Taste): Taste {
        var next = to
        for ((key, on) in veto) next = next.settingSyncedVeto(key, on)
        for (local in to.vetoedKeys) {
            val key = SyncKeys.syncVetoKey(local) ?: continue
            if (veto[key] == true) continue
            next = next.settingSyncedVeto(local, false)
        }
        return next
    }
}

/** 第 6.5 节 `Taste.settingSyncedVeto`。安卓 `Taste` 尚未有这个方法（不在 S4 文件范围），投影在这里实现同一规则。 */
fun Taste.settingSyncedVeto(key: String, vetoed: Boolean): Taste {
    val current = beliefs[key]
    if (vetoed) {
        val belief = current?.copy(vetoed = true) ?: Belief(vetoed = true)
        return copy(beliefs = beliefs + (key to belief))
    }
    if (current == null) return this
    val updated = current.copy(vetoed = false)
    if (updated.hit == 0.0 && updated.miss == 0.0 && updated.hits == 0 && updated.misses == 0) {
        return copy(beliefs = beliefs - key)
    }
    return copy(beliefs = beliefs + (key to updated))
}
