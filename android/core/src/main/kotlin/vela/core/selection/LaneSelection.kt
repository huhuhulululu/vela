package vela.core.selection

import vela.core.base.lane.LaneCursor
import vela.core.base.lane.LaneID
import vela.core.base.model.lowerRoot

/**
 * 变更后两路各停在哪一间、焦点在哪一路，以及房间变了的原因。
 *
 * 单窗（`secondary == null`）时，除划动外主路直接采用副本上 FeedStore 已经选出的
 * `proposedPrimary`，因此与 FeedStore 自己的 `setFilters` / `setQuery` / `replace` / `step` 一致。
 * 双窗再调整副路，并在主路被动落到副路时沿主路自己的方向另找。
 *
 * Swift 的 `LaneCursor` 是值类型。这里的游标是可变的 `data class`，进出都 [LaneCursor.copy]，
 * 避免改到调用方拿着的那一份。用户名比较用 [lowerRoot]。
 */
object LaneSelection {
    sealed class Intent {
        data object Filter : Intent()
        data object Reload : Intent()
        data object SearchInstall : Intent()
        data object SearchExit : Intent()
        data object Merge : Intent()
        data class Reveal(val target: String) : Intent()
        data class Move(val lane: LaneID, val delta: Int) : Intent()
        data object BlankResume : Intent()
    }

    /** 一路房间发生变化时的原因。变成空路时用 [Reason.EMPTY]，不再另记触发它的意图。 */
    enum class Reason {
        FILTER,
        SOURCE_CHANGE,
        MOVE,
        REVEAL,
        RESUME,
        MERGE,
        EMPTY,
    }

    /** 变更前的可见顺序、两路游标和焦点。`secondary == null` 表示单窗。 */
    data class Snapshot(
        val rooms: List<String>,
        val primary: LaneCursor,
        val secondary: LaneCursor?,
        val focus: LaneID,
    )

    data class Outcome(
        val primary: LaneCursor,
        val secondary: LaneCursor?,
        val focus: LaneID,
        val reasons: Map<LaneID, Reason>,
    )

    /** `updateFeed` 用的入口：先用 [feedIntent] 识别续页恢复，再 [resolve]。 */
    fun resolveFeedChange(
        before: Snapshot,
        afterRooms: List<String>,
        proposedPrimary: String?,
        intent: Intent,
    ): Outcome = resolve(
        before = before,
        afterRooms = afterRooms,
        proposedPrimary = proposedPrimary,
        intent = feedIntent(intent, before.rooms, afterRooms),
    )

    /**
     * 可见列表由空变非空，且这次是被动变化（合并续页、筛选放宽）时，按 [Intent.BlankResume] 两路重新选房。
     * 整表重载、全站搜索装入或退出、reveal、划动有自己的落点规则，保留原意图。
     * 单窗时 [Intent.BlankResume] 与这两种意图一样采用 FeedStore 的选择，结果不变。
     */
    fun feedIntent(intent: Intent, beforeRooms: List<String>, afterRooms: List<String>): Intent {
        if (beforeRooms.isNotEmpty() || afterRooms.isEmpty()) return intent
        return when (intent) {
            Intent.Merge, Intent.Filter -> Intent.BlankResume
            Intent.Reload, Intent.SearchInstall, Intent.SearchExit, Intent.BlankResume -> intent
            is Intent.Reveal, is Intent.Move -> intent
        }
    }

    /**
     * @param before 变更前的快照。
     * @param afterRooms 变更后的可见用户名，顺序就是划动顺序。
     * @param proposedPrimary 副本执行完 FeedStore 变更后的主路用户名。划动不看它，从变更前的游标重算。
     * @param intent 这次变更的意图。
     */
    fun resolve(
        before: Snapshot,
        afterRooms: List<String>,
        proposedPrimary: String?,
        intent: Intent,
    ): Outcome {
        if (before.secondary == null) {
            return singleWindow(before, afterRooms, proposedPrimary, intent)
        }
        if (afterRooms.isEmpty()) return cleared(before)
        if (afterRooms.size == 1) return onlyRoom(before, afterRooms[0], intent)
        return when (intent) {
            Intent.Filter -> filtered(before, afterRooms, proposedPrimary)
            Intent.Reload, Intent.SearchInstall, Intent.SearchExit ->
                reloaded(before, afterRooms, proposedPrimary)
            Intent.Merge -> merged(before, afterRooms, proposedPrimary)
            is Intent.Reveal -> revealed(before, afterRooms, proposedPrimary, intent.target)
            is Intent.Move -> moved(before, afterRooms, intent.lane, intent.delta)
            Intent.BlankResume -> resumed(before, afterRooms, proposedPrimary)
        }
    }

    private fun singleWindow(
        before: Snapshot,
        afterRooms: List<String>,
        proposedPrimary: String?,
        intent: Intent,
    ): Outcome {
        val primary = when {
            intent is Intent.Move && intent.lane == LaneID.PRIMARY ->
                step(before.primary, intent.delta, afterRooms, emptySet())
            intent is Intent.Move -> before.primary.copy()
            else -> before.primary.copy(username = proposedPrimary)
        }
        val reasons = LinkedHashMap<LaneID, Reason>()
        note(reasons, LaneID.PRIMARY, before.primary.username, primary.username, placed(intent))
        return Outcome(
            primary = primary.copy(),
            secondary = null,
            focus = LaneID.PRIMARY,
            reasons = reasons.toMap(),
        )
    }

    private fun cleared(before: Snapshot): Outcome {
        val primary = before.primary.copy(username = null)
        val secondary = before.secondary?.copy(username = null)
        val reasons = LinkedHashMap<LaneID, Reason>()
        note(reasons, LaneID.PRIMARY, before.primary.username, null, Reason.EMPTY)
        val old = before.secondary
        if (old != null) {
            note(reasons, LaneID.SECONDARY, old.username, null, Reason.EMPTY)
        }
        return Outcome(primary, secondary, keptFocus(before), reasons.toMap())
    }

    /** 列表只剩一个房间：焦点路留下它，另一路空着。 */
    private fun onlyRoom(before: Snapshot, room: String, intent: Intent): Outcome {
        val focus = keptFocus(before)
        val secondaryBase = before.secondary ?: LaneCursor()
        val primary: LaneCursor
        val secondary: LaneCursor
        if (focus == LaneID.SECONDARY) {
            secondary = secondaryBase.copy(username = room)
            primary = before.primary.copy(username = null)
        } else {
            primary = before.primary.copy(username = room)
            secondary = secondaryBase.copy(username = null)
        }
        return outcome(before, primary, secondary, focus, placed(intent))
    }

    /** 房间还在就不动。消失的副路沿自己的方向找，再反向；主路采用 FeedStore 的选择，落到副路就让开。 */
    private fun filtered(before: Snapshot, afterRooms: List<String>, proposedPrimary: String?): Outcome {
        val secondary = (before.secondary ?: LaneCursor()).copy()
        val name = secondary.username
        if (name != null) {
            val kept = canonical(name, afterRooms)
            secondary.username = kept ?: replacement(name, before.rooms, afterRooms, secondary.direction)
        }
        var primary = before.primary.copy(
            username = proposedPrimary?.let { canonical(it, afterRooms) },
        )
        primary = yield(primary, secondary.username, afterRooms)
        return outcome(before, primary, secondary, placed = Reason.FILTER)
    }

    /** 整表重载、全站搜索装入或退出：副路取新列表里主路之后的第一间。 */
    private fun reloaded(before: Snapshot, afterRooms: List<String>, proposedPrimary: String?): Outcome {
        val primary = before.primary.copy(
            username = proposedPrimary?.let { canonical(it, afterRooms) },
        )
        val secondary = (before.secondary ?: LaneCursor()).copy(
            username = roomAfter(primary.username, afterRooms),
        )
        return outcome(before, primary, secondary, placed = Reason.SOURCE_CHANGE)
    }

    /** 合并、重排、前置：还在列表里的房间不动。 */
    private fun merged(before: Snapshot, afterRooms: List<String>, proposedPrimary: String?): Outcome {
        val primaryKept = proposedPrimary?.let { canonical(it, afterRooms) }
            ?: before.primary.username?.let { canonical(it, afterRooms) }
        var primary = before.primary.copy(username = primaryKept)
        val secondary = (before.secondary ?: LaneCursor()).copy()
        val name = secondary.username
        if (name != null) {
            val kept = canonical(name, afterRooms)
            secondary.username = kept ?: replacement(name, before.rooms, afterRooms, secondary.direction)
        }
        primary = yield(primary, secondary.username, afterRooms)
        return outcome(before, primary, secondary, placed = Reason.MERGE)
    }

    /** 目标正在另一路播放时只切焦点。否则主路改到目标上。 */
    private fun revealed(
        before: Snapshot,
        afterRooms: List<String>,
        proposedPrimary: String?,
        target: String,
    ): Outcome {
        var primary = before.primary.copy()
        val secondary = (before.secondary ?: LaneCursor()).copy()
        var focus = keptFocus(before)
        if (same(secondary.username, target)) {
            focus = LaneID.SECONDARY
            primary.username = canonical(primary.username, afterRooms) ?: primary.username
            secondary.username = canonical(secondary.username, afterRooms) ?: secondary.username
        } else if (same(primary.username, target)) {
            focus = LaneID.PRIMARY
            primary.username = canonical(primary.username, afterRooms) ?: primary.username
            val kept = secondary.username?.let { canonical(it, afterRooms) }
            if (kept != null) secondary.username = kept
        } else {
            val placed = canonical(target, afterRooms)
                ?: proposedPrimary?.let { canonical(it, afterRooms) }
            primary.username = placed
            focus = LaneID.PRIMARY
            val name = secondary.username
            val kept = name?.let { canonical(it, afterRooms) }
            if (name != null && kept != null && !same(kept, placed)) {
                secondary.username = kept
            } else if (same(secondary.username, placed)) {
                secondary.username = null
            }
            primary = yield(primary, secondary.username, afterRooms)
        }
        return outcome(before, primary, secondary, focus, Reason.REVEAL)
    }

    private fun moved(before: Snapshot, afterRooms: List<String>, lane: LaneID, delta: Int): Outcome {
        var primary = before.primary.copy()
        var secondary = (before.secondary ?: LaneCursor()).copy()
        when (lane) {
            LaneID.PRIMARY ->
                primary = step(primary, delta, afterRooms, skipping(secondary.username))
            LaneID.SECONDARY ->
                secondary = step(secondary, delta, afterRooms, skipping(primary.username))
            // 快照仍只有两路。第三路的选房不在契约包；这里不改游标，避免把两窗结果写错。
            LaneID.TERTIARY -> Unit
        }
        return outcome(before, primary, secondary, placed = Reason.MOVE)
    }

    /** 空列表续上可见房间：主路用 FeedStore 选出的那间（通常是第一间），副路取它的后一间。 */
    private fun resumed(before: Snapshot, afterRooms: List<String>, proposedPrimary: String?): Outcome {
        val kept = proposedPrimary?.let { canonical(it, afterRooms) }
        val primary = before.primary.copy(username = kept ?: afterRooms.firstOrNull())
        val secondary = (before.secondary ?: LaneCursor()).copy(
            username = roomAfter(primary.username, afterRooms),
        )
        return outcome(before, primary, secondary, placed = Reason.RESUME)
    }

    private fun step(cursor: LaneCursor, delta: Int, rooms: List<String>, skipping: Set<String>): LaneCursor {
        if (delta == 0) return cursor.copy()
        val direction = if (delta > 0) 1 else -1
        if (rooms.isEmpty()) return cursor.copy(username = null, direction = direction)
        val origin = indexOf(cursor.username, rooms) ?: 0
        val landed = LanePaging.landingIndex(origin, delta, rooms, skipping)
        return cursor.copy(username = rooms[landed], direction = direction)
    }

    /** 主路与副路同房时，主路从副路这间出发，沿自己的方向另找，找不到再反向。 */
    private fun yield(primary: LaneCursor, taken: String?, rooms: List<String>): LaneCursor {
        if (taken == null || !same(primary.username, taken)) return primary
        return primary.copy(
            username = neighbor(taken, rooms, primary.direction, setOf(taken)),
        )
    }

    /** 从已经不在新列表里的房间出发，沿方向找仍可见的下一间，没有再反方向。 */
    private fun replacement(
        missing: String,
        before: List<String>,
        after: List<String>,
        direction: Int,
    ): String? {
        val at = indexOf(missing, before) ?: return null
        fun allowed(name: String): Boolean = !same(name, missing) && after.any { same(it, name) }
        val later = before.drop(at + 1).firstOrNull(::allowed)?.let { canonical(it, after) }
        val earlier = before.take(at).lastOrNull(::allowed)?.let { canonical(it, after) }
        return if (direction >= 0) later ?: earlier else earlier ?: later
    }

    private fun neighbor(name: String, rooms: List<String>, direction: Int, excluding: Set<String>): String? {
        val at = indexOf(name, rooms) ?: return null
        val banned = excluding.mapTo(HashSet()) { it.lowerRoot() }
        fun allowed(candidate: String): Boolean =
            candidate.lowerRoot() !in banned && !same(candidate, name)
        val later = rooms.drop(at + 1).firstOrNull(::allowed)
        val earlier = rooms.take(at).lastOrNull(::allowed)
        return if (direction >= 0) later ?: earlier else earlier ?: later
    }

    private fun roomAfter(name: String?, rooms: List<String>): String? {
        val at = indexOf(name, rooms) ?: return null
        val next = at + 1
        if (next >= rooms.size) return null
        return rooms[next]
    }

    private fun skipping(username: String?): Set<String> =
        if (username == null) emptySet() else setOf(username)

    private fun outcome(
        before: Snapshot,
        primary: LaneCursor,
        secondary: LaneCursor,
        focus: LaneID? = null,
        placed: Reason,
    ): Outcome {
        val reasons = LinkedHashMap<LaneID, Reason>()
        note(reasons, LaneID.PRIMARY, before.primary.username, primary.username, placed)
        note(reasons, LaneID.SECONDARY, before.secondary?.username, secondary.username, placed)
        return Outcome(
            primary = primary.copy(),
            secondary = secondary.copy(),
            focus = focus ?: keptFocus(before),
            reasons = reasons.toMap(),
        )
    }

    private fun note(
        reasons: MutableMap<LaneID, Reason>,
        lane: LaneID,
        old: String?,
        new: String?,
        placed: Reason,
    ) {
        if (same(old, new)) return
        reasons[lane] = if (new == null) Reason.EMPTY else placed
    }

    private fun placed(intent: Intent): Reason = when (intent) {
        Intent.Filter -> Reason.FILTER
        Intent.Reload, Intent.SearchInstall, Intent.SearchExit -> Reason.SOURCE_CHANGE
        Intent.Merge -> Reason.MERGE
        is Intent.Reveal -> Reason.REVEAL
        is Intent.Move -> Reason.MOVE
        Intent.BlankResume -> Reason.RESUME
    }

    private fun keptFocus(before: Snapshot): LaneID =
        if (before.secondary == null) LaneID.PRIMARY else before.focus

    private fun indexOf(name: String?, rooms: List<String>): Int? {
        if (name == null) return null
        val index = rooms.indexOfFirst { same(it, name) }
        return if (index >= 0) index else null
    }

    private fun canonical(name: String?, rooms: List<String>): String? {
        if (name == null) return null
        return rooms.firstOrNull { same(it, name) }
    }

    private fun same(lhs: String?, rhs: String?): Boolean = when {
        lhs == null && rhs == null -> true
        lhs != null && rhs != null -> lhs.lowerRoot() == rhs.lowerRoot()
        else -> false
    }
}
