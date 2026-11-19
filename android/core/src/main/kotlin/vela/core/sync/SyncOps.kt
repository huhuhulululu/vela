package vela.core.sync

import vela.core.base.model.trimSwiftWhitespace

data class SyncReplica(var doc: SyncDocument = SyncDocument.empty(), var clock: SyncClock)

sealed class SyncLocalOp {
    data class Favorite(val name: String) : SyncLocalOp()
    data class RestoreFavorite(val name: String) : SyncLocalOp()
    data class Unfavorite(val name: String) : SyncLocalOp()
    data class Block(val name: String) : SyncLocalOp()
    data class Unblock(val name: String) : SyncLocalOp()
    data class Importing(val names: List<String>) : SyncLocalOp()
    data class Veto(val key: String) : SyncLocalOp()
    data class Unveto(val key: String) : SyncLocalOp()
    data object ClearVetoes : SyncLocalOp()
    data class Setting(val key: String, val value: Boolean) : SyncLocalOp()
}

sealed class SyncOpOutcome {
    data class Applied(val replica: SyncReplica) : SyncOpOutcome()
    data object Unchanged : SyncOpOutcome()
    data object LocalOnly : SyncOpOutcome()
    data object VetoQuotaFull : SyncOpOutcome()
    data object LocalOnlyQuotaFull : SyncOpOutcome()
    data object Full : SyncOpOutcome()
}

object SyncOps {
    fun apply(
        op: SyncLocalOp,
        replica: SyncReplica,
        pt: Long,
        localOnlyVetoCount: Int = 0,
        sizeLimit: Int = SyncLimits.plaintextBytes,
    ): SyncOpOutcome {
        val r = SyncReplica(copyDoc(replica.doc), SyncClock(replica.clock.l, replica.clock.c, replica.clock.dev))
        val projection = r.doc
        when (op) {
            is SyncLocalOp.Favorite -> {
                val key = SyncKeys.roomKey(op.name) ?: return SyncOpOutcome.Unchanged
                writeFavorite(key, op.name, r, r.clock.tick(pt))
            }
            is SyncLocalOp.RestoreFavorite -> {
                val key = SyncKeys.roomKey(op.name) ?: return SyncOpOutcome.Unchanged
                val room = projection.rooms[key]
                if (room?.isBlocked == true || room?.isFavorite == true) return SyncOpOutcome.Unchanged
                writeFavorite(key, op.name, r, r.clock.tick(pt))
            }
            is SyncLocalOp.Unfavorite -> {
                val key = SyncKeys.roomKey(op.name) ?: return SyncOpOutcome.Unchanged
                if (projection.rooms[key]?.isFavorite != true) return SyncOpOutcome.Unchanged
                val room = r.doc.rooms.getOrPut(key) { SyncRoom() }
                room.f = SyncRegister.of(r.clock.tick(pt), "-")
            }
            is SyncLocalOp.Block -> {
                val key = SyncKeys.roomKey(op.name) ?: return SyncOpOutcome.Unchanged
                val h = r.clock.tick(pt)
                val room = r.doc.rooms.getOrPut(key) { SyncRoom() }
                room.b = SyncRegister.of(h, "+")
                room.f = SyncRegister.of(h, "-")
                writeDisplay(key, op.name, room, h)
            }
            is SyncLocalOp.Unblock -> {
                val key = SyncKeys.roomKey(op.name) ?: return SyncOpOutcome.Unchanged
                if (projection.rooms[key]?.isBlocked != true) return SyncOpOutcome.Unchanged
                val room = r.doc.rooms.getOrPut(key) { SyncRoom() }
                room.b = SyncRegister.of(r.clock.tick(pt), "-")
            }
            is SyncLocalOp.Importing -> {
                val fresh = importWindow(op.names).filter { item ->
                    val room = projection.rooms[item.second]
                    room?.isFavorite != true && room?.isBlocked != true
                }
                if (fresh.isEmpty()) return SyncOpOutcome.Unchanged
                for (item in fresh.asReversed()) writeFavorite(item.second, item.first, r, r.clock.tick(pt))
            }
            is SyncLocalOp.Veto -> {
                val key = SyncKeys.syncVetoKey(op.key) ?: return if (localOnlyVetoCount < SyncLimits.localOnlyVetoes) {
                    SyncOpOutcome.LocalOnly
                } else {
                    SyncOpOutcome.LocalOnlyQuotaFull
                }
                val active = r.doc.veto.values.count { it.isOn }
                val already = r.doc.veto[key]?.isOn == true
                if (!already && active >= SyncLimits.activeVetoes) return SyncOpOutcome.VetoQuotaFull
                r.doc.veto[key] = SyncRegister.of(r.clock.tick(pt), "+")
            }
            is SyncLocalOp.Unveto -> {
                val key = SyncKeys.syncVetoKey(op.key) ?: return SyncOpOutcome.LocalOnly
                if (projection.veto[key]?.isOn != true) return SyncOpOutcome.Unchanged
                r.doc.veto[key] = SyncRegister.of(r.clock.tick(pt), "-")
            }
            SyncLocalOp.ClearVetoes -> {
                val on = r.doc.veto.filter { it.value.isOn }.keys.toList()
                if (on.isEmpty()) return SyncOpOutcome.Unchanged
                val h = r.clock.tick(pt)
                for (key in on) r.doc.veto[key] = SyncRegister.of(h, "-")
            }
            is SyncLocalOp.Setting -> {
                if (op.key !in SyncKeys.settingKeys) return SyncOpOutcome.Unchanged
                r.doc.settings[op.key] = SyncRegister.of(r.clock.tick(pt), if (op.value) "1" else "0")
            }
        }
        val fitted = SyncMerge.fitSize(r.doc, sizeLimit)
        if (!fitted.fits) return SyncOpOutcome.Full
        r.doc = fitted.doc
        return SyncOpOutcome.Applied(r)
    }

    /** 导入的固定窗口：按文件顺序去掉不合规名字、按键去重，取前 300 个。 */
    fun importWindow(names: List<String>): List<Pair<String, String>> {
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, String>>()
        for (name in names) {
            val key = SyncKeys.roomKey(name) ?: continue
            if (!seen.add(key)) continue
            out.add(name to key)
            if (out.size == SyncLimits.importWindow) break
        }
        return out
    }

    fun writeFavorite(key: String, original: String, r: SyncReplica, h: Hlc) {
        val room = r.doc.rooms.getOrPut(key) { SyncRoom() }
        room.f = SyncRegister.of(h, "+")
        room.b = SyncRegister.of(h, "-")
        writeDisplay(key, original, room, h)
    }

    /** 显示原文与键不同，或该条目已有 `n` 时，写 `n`。 */
    fun writeDisplay(key: String, original: String, room: SyncRoom, h: Hlc) {
        val trimmed = original.trimSwiftWhitespace()
        val differs = !trimmed.utf8Equals(SyncKeys.usernameOf(key))
        if (!differs && room.n == null) return
        room.n = SyncRegister.of(h, SyncKeys.displayName(original, key))
    }

    private fun copyDoc(doc: SyncDocument): SyncDocument = SyncDocument(
        rooms = doc.rooms.mapValues { SyncRoom(it.value.f, it.value.b, it.value.n) }.toMutableMap(),
        veto = doc.veto.toMutableMap(),
        settings = doc.settings.toMutableMap(),
    )
}
