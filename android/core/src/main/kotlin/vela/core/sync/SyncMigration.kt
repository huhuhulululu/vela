package vela.core.sync

import vela.core.curation.Curation

enum class PreferenceLoadOutcome { LOADED, MISSING, QUARANTINED, READ_ONLY }

data class SyncLocalData(
    val curation: Curation = Curation(),
    val vetoedKeys: List<String> = emptyList(),
    val explicitSettings: Map<String, Boolean> = emptyMap(),
)

data class SyncMigrationReport(
    val invalidNames: List<String> = emptyList(),
    val droppedSyncVetoes: List<String> = emptyList(),
    val droppedLocalOnlyVetoes: List<String> = emptyList(),
    val localOnlyVetoes: List<String> = emptyList(),
)

data class SyncJoinPreview(
    var n: Int = 0,
    var m: Int = 0,
    var v: Int = 0,
    var x: Int = 0,
    var y: Int = 0,
    var z: Int = 0,
) {
    fun sameCounts(other: SyncJoinPreview): Boolean =
        n == other.n && m == other.m && v == other.v && x == other.x && y == other.y && z == other.z
}

data class JoinStamp(
    val replica: SyncReplica,
    val preview: SyncJoinPreview,
    val report: SyncMigrationReport,
    val fits: Boolean,
)

object SyncMigration {
    fun canEnable(curation: PreferenceLoadOutcome?, taste: PreferenceLoadOutcome?): Boolean {
        if (curation == null || taste == null) return false
        val ok = setOf(PreferenceLoadOutcome.LOADED, PreferenceLoadOutcome.MISSING)
        return curation in ok && taste in ok
    }

    private class Prepared(
        val favorites: List<Pair<String, String>>,
        val blocked: List<Pair<String, String>>,
        val vetoes: List<String>,
        val settings: List<Pair<String, Boolean>>,
        val report: SyncMigrationReport,
    )

    private fun prepare(local: SyncLocalData): Prepared {
        val invalid = ArrayList<String>()
        fun split(names: List<String>): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            for (name in names) {
                val key = SyncKeys.roomKey(name)
                if (key != null) out.add(name to key) else invalid.add(name)
            }
            return out
        }
        val favorites = split(local.curation.favorites)
        val blocked = split(local.curation.blocked)
        val synced = HashSet<String>()
        val localOnly = ArrayList<String>()
        for (key in local.vetoedKeys) {
            val s = SyncKeys.syncVetoKey(key)
            if (s != null) synced.add(s) else localOnly.add(key)
        }
        val sortedSynced = SyncDocument.sortedKeys(synced)
        val sortedLocal = SyncDocument.sortedKeys(localOnly)
        val settings = SyncDocument.sortedKeys(local.explicitSettings.keys.filter { it in SyncKeys.settingKeys })
            .map { it to local.explicitSettings.getValue(it) }
        val report = SyncMigrationReport(
            invalidNames = invalid,
            droppedSyncVetoes = sortedSynced.drop(SyncLimits.activeVetoes),
            droppedLocalOnlyVetoes = sortedLocal.drop(SyncLimits.localOnlyVetoes),
            localOnlyVetoes = sortedLocal.take(SyncLimits.localOnlyVetoes),
        )
        return Prepared(favorites, blocked, sortedSynced.take(SyncLimits.activeVetoes), settings, report)
    }

    private fun stampFavorite(item: Pair<String, String>, doc: SyncDocument, h: Hlc) {
        val room = doc.rooms.getOrPut(item.second) { SyncRoom() }
        room.f = SyncRegister.of(h, "+")
        SyncOps.writeDisplay(item.second, item.first, room, h)
    }

    private fun stampBlock(item: Pair<String, String>, doc: SyncDocument, h: Hlc) {
        val room = doc.rooms.getOrPut(item.second) { SyncRoom() }
        room.b = SyncRegister.of(h, "+")
        SyncOps.writeDisplay(item.second, item.first, room, h)
    }

    fun stampCreator(local: SyncLocalData, clock: SyncClock, pt: Long): Triple<SyncReplica, SyncMigrationReport, Boolean> {
        val p = prepare(local)
        val c = SyncClock(clock.l, clock.c, clock.dev)
        val doc = SyncDocument.empty()
        for (item in p.favorites.asReversed()) stampFavorite(item, doc, c.tick(pt))
        for (item in p.blocked.asReversed()) stampBlock(item, doc, c.tick(pt))
        for (key in p.vetoes) doc.veto[key] = SyncRegister.of(c.tick(pt), "+")
        for (s in p.settings) doc.settings[s.first] = SyncRegister.of(c.tick(pt), if (s.second) "1" else "0")
        val fitted = SyncMerge.fitSize(doc)
        return Triple(SyncReplica(fitted.doc, c), p.report, fitted.fits)
    }

    /** 加入方先让时钟看见远端，再盖章，然后与远端合并。 */
    fun stampJoin(local: SyncLocalData, remote: SyncDocument, clock: SyncClock, pt: Long): JoinStamp {
        val p = prepare(local)
        val theirs = SyncProjection(remote)
        val c = SyncClock(clock.l, clock.c, clock.dev)
        c.receive(remote.maxHlc)
        val doc = SyncDocument.empty()
        val preview = SyncJoinPreview()
        for (item in p.favorites.asReversed()) {
            when {
                theirs.isBlocked(item.second) -> preview.z += 1
                !theirs.isFavorite(item.second) -> {
                    stampFavorite(item, doc, c.tick(pt))
                    preview.n += 1
                }
            }
        }
        for (item in p.blocked.asReversed()) {
            if (!theirs.isBlocked(item.second)) {
                stampBlock(item, doc, c.tick(pt))
                preview.m += 1
            }
        }
        for (key in p.vetoes) {
            if (theirs.veto[key] != true) {
                doc.veto[key] = SyncRegister.of(c.tick(pt), "+")
                preview.v += 1
            }
        }
        for (s in p.settings) {
            if (remote.settings[s.first] == null) {
                doc.settings[s.first] = SyncRegister.of(c.tick(pt), if (s.second) "1" else "0")
            }
        }
        val united = SyncMerge.union(doc, remote)
        val fitted = SyncMerge.fitSize(united)
        for ((key, room) in united.rooms) {
            val kept = fitted.doc.rooms[key]
            if (room.isFavorite && kept?.isFavorite != true) preview.x += 1
            if (room.isBlocked && kept?.isBlocked != true) preview.y += 1
        }
        return JoinStamp(SyncReplica(fitted.doc, c), preview, p.report, fitted.fits)
    }
}
