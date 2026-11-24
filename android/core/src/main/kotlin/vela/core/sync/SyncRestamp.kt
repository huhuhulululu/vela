package vela.core.sync

/** 离线快钟在发出前重新盖章（第 5.2 节）。候选集里有一个已发出则整组不动。 */
object SyncRestamp {
    sealed class Result {
        data object Unchanged : Result()
        data class Restamped(val count: Int) : Result()
        data object BlockedBySent : Result()
    }

    fun presend(replica: SyncReplica, serverNow: Long, maxSentL: Long, pt: Long): Result {
        val threshold = serverNow + SyncLimits.clockSkewMillis
        val dev = replica.clock.dev
        fun candidate(r: SyncRegister?) = r != null && r.hlc.dev == dev && r.hlc.l > threshold
        val doc = replica.doc
        val groups = LinkedHashSet<Hlc>()
        val others = ArrayList<Hlc>()
        for (room in doc.rooms.values) {
            for (r in room.registers) {
                if (candidate(r)) groups.add(r.hlc) else others.add(r.hlc)
            }
        }
        for (r in doc.veto.values + doc.settings.values) {
            if (candidate(r)) groups.add(r.hlc) else others.add(r.hlc)
        }
        if (groups.isEmpty()) return Result.Unchanged
        if (groups.any { it.l <= maxSentL }) return Result.BlockedBySent
        val floor = Hlc(serverNow, 0, 0)
        val base = maxOf(others.maxOrNull() ?: floor, floor)
        replica.clock.reset(base)
        val mapping = LinkedHashMap<Hlc, Hlc>()
        for (old in groups.sorted()) mapping[old] = replica.clock.tick(pt)
        fun moved(r: SyncRegister?): SyncRegister? {
            if (r == null) return null
            val fresh = mapping[r.hlc] ?: return r
            return SyncRegister.of(fresh, r.payload)
        }
        val next = SyncDocument(
            rooms = doc.rooms.mapValues { (_, room) -> SyncRoom(moved(room.f), moved(room.b), moved(room.n)) }.toMutableMap(),
            veto = doc.veto.toMutableMap(),
            settings = doc.settings.toMutableMap(),
        )
        var count = 0
        for ((key, room) in doc.rooms) {
            val updated = next.rooms.getValue(key)
            count += room.registers.zip(updated.registers).count { it.first != it.second }
        }
        for ((key, r) in doc.veto) {
            if (mapping.containsKey(r.hlc)) {
                next.veto[key] = moved(r)!!
                count++
            }
        }
        for ((key, r) in doc.settings) {
            if (mapping.containsKey(r.hlc)) {
                next.settings[key] = moved(r)!!
                count++
            }
        }
        replica.doc = next
        return Result.Restamped(count)
    }
}
