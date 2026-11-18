import Foundation

/// 可见列表上的落点。`skipping` 为空时与 `FeedStore.step(_:)` 的夹紧公式相同；
/// 落在被跳过的房间上时，沿 delta 方向继续找，找不到就停在原地。
enum LanePaging {
    static func landingIndex(from index: Int, delta: Int, names: [String], skipping: Set<String>) -> Int {
        let count = names.count
        guard count > 0 else { return 0 }
        let origin = min(max(index, 0), count - 1)
        guard delta != 0 else { return origin }
        var next = min(max(origin + delta, 0), count - 1)
        let banned = Set(skipping.map { $0.lowercased() })
        guard !banned.isEmpty else { return next }
        let direction = delta > 0 ? 1 : -1
        while banned.contains(names[next].lowercased()) {
            let candidate = next + direction
            guard candidate >= 0, candidate < count else { break }
            next = candidate
        }
        if banned.contains(names[next].lowercased()), !banned.contains(names[origin].lowercased()) {
            return origin
        }
        return next
    }
}

/// 变更后两路各停在哪一间、焦点在哪一路，以及房间变了的原因。
///
/// 单窗（`secondary == nil`）时，除划动外主路直接采用副本上 FeedStore 已经选出的
/// `proposedPrimary`，因此与 FeedStore 自己的 `setFilters` / `setQuery` / `replace` / `step` 一致。
/// 双窗再按 §4.1 调整副路，并在主路被动落到副路时沿主路自己的方向另找。
public enum LaneSelection {
    public enum Intent: Equatable, Sendable {
        case filter
        case reload
        case searchInstall
        case searchExit
        case merge
        case reveal(String)
        case move(LaneID, Int)
        case blankResume
    }

    /// 一路房间发生变化时的原因。变成空路时用 `.empty`，不再另记触发它的意图。
    public enum Reason: Equatable, Sendable {
        case filter
        case sourceChange
        case move
        case reveal
        case resume
        case merge
        case empty
    }

    /// 变更前的可见顺序、两路游标和焦点。`secondary == nil` 表示单窗。
    public struct Snapshot: Equatable, Sendable {
        public var rooms: [String]
        public var primary: LaneCursor
        public var secondary: LaneCursor?
        public var focus: LaneID

        public init(rooms: [String], primary: LaneCursor, secondary: LaneCursor?, focus: LaneID) {
            self.rooms = rooms
            self.primary = primary
            self.secondary = secondary
            self.focus = focus
        }
    }

    public struct Outcome: Equatable, Sendable {
        public var primary: LaneCursor
        public var secondary: LaneCursor?
        public var focus: LaneID
        public var reasons: [LaneID: Reason]

        public init(primary: LaneCursor, secondary: LaneCursor?, focus: LaneID, reasons: [LaneID: Reason]) {
            self.primary = primary
            self.secondary = secondary
            self.focus = focus
            self.reasons = reasons
        }
    }

    /// `updateFeed` 用的入口：先用 `feedIntent` 识别续页恢复，再 `resolve`。
    public static func resolveFeedChange(
        before: Snapshot,
        afterRooms: [String],
        proposedPrimary: String?,
        intent: Intent
    ) -> Outcome {
        resolve(
            before: before, afterRooms: afterRooms, proposedPrimary: proposedPrimary,
            intent: feedIntent(intent, beforeRooms: before.rooms, afterRooms: afterRooms)
        )
    }

    /// 可见列表由空变非空，且这次是被动变化（合并续页、筛选放宽）时，按 `.blankResume` 两路重新选房。
    /// 整表重载、全站搜索装入或退出、reveal、划动有自己的落点规则，保留原意图。
    /// 单窗时 `.blankResume` 与这两种意图一样采用 FeedStore 的选择，结果不变。
    public static func feedIntent(_ intent: Intent, beforeRooms: [String], afterRooms: [String]) -> Intent {
        guard beforeRooms.isEmpty, !afterRooms.isEmpty else { return intent }
        switch intent {
        case .merge, .filter:
            return .blankResume
        case .reload, .searchInstall, .searchExit, .reveal, .move, .blankResume:
            return intent
        }
    }

    /// - Parameters:
    ///   - before: 变更前的快照。
    ///   - afterRooms: 变更后的可见用户名，顺序就是划动顺序。
    ///   - proposedPrimary: 副本执行完 FeedStore 变更后的主路用户名。划动不看它，从变更前的游标重算。
    ///   - intent: 这次变更的意图。
    public static func resolve(
        before: Snapshot,
        afterRooms: [String],
        proposedPrimary: String?,
        intent: Intent
    ) -> Outcome {
        if before.secondary == nil {
            return singleWindow(before: before, afterRooms: afterRooms, proposedPrimary: proposedPrimary, intent: intent)
        }
        if afterRooms.isEmpty {
            return cleared(before)
        }
        if afterRooms.count == 1 {
            return onlyRoom(before, room: afterRooms[0], intent: intent)
        }
        switch intent {
        case .filter:
            return filtered(before, afterRooms: afterRooms, proposedPrimary: proposedPrimary)
        case .reload, .searchInstall, .searchExit:
            return reloaded(before, afterRooms: afterRooms, proposedPrimary: proposedPrimary)
        case .merge:
            return merged(before, afterRooms: afterRooms, proposedPrimary: proposedPrimary)
        case .reveal(let target):
            return revealed(before, afterRooms: afterRooms, proposedPrimary: proposedPrimary, target: target)
        case .move(let lane, let delta):
            return moved(before, afterRooms: afterRooms, lane: lane, delta: delta)
        case .blankResume:
            return resumed(before, afterRooms: afterRooms, proposedPrimary: proposedPrimary)
        }
    }

    // MARK: 单窗与空列表

    private static func singleWindow(
        before: Snapshot,
        afterRooms: [String],
        proposedPrimary: String?,
        intent: Intent
    ) -> Outcome {
        var primary = before.primary
        if case .move(let lane, let delta) = intent, lane == .primary {
            primary = step(primary, delta: delta, rooms: afterRooms, skipping: [])
        } else if case .move = intent {
            // 单窗没有副路可划。
        } else {
            primary.username = proposedPrimary
        }
        var reasons: [LaneID: Reason] = [:]
        note(&reasons, lane: .primary, from: before.primary.username, to: primary.username, placed: placed(intent))
        return Outcome(primary: primary, secondary: nil, focus: .primary, reasons: reasons)
    }

    private static func cleared(_ before: Snapshot) -> Outcome {
        var primary = before.primary
        primary.username = nil
        var secondary = before.secondary
        secondary?.username = nil
        var reasons: [LaneID: Reason] = [:]
        note(&reasons, lane: .primary, from: before.primary.username, to: nil, placed: .empty)
        if let old = before.secondary {
            note(&reasons, lane: .secondary, from: old.username, to: nil, placed: .empty)
        }
        return Outcome(primary: primary, secondary: secondary, focus: keptFocus(before), reasons: reasons)
    }

    /// 列表只剩一个房间：焦点路留下它，另一路空着。
    private static func onlyRoom(_ before: Snapshot, room: String, intent: Intent) -> Outcome {
        var primary = before.primary
        var secondary = before.secondary ?? LaneCursor()
        let focus = keptFocus(before)
        if focus == .secondary {
            secondary.username = room
            primary.username = nil
        } else {
            primary.username = room
            secondary.username = nil
        }
        var reasons: [LaneID: Reason] = [:]
        note(&reasons, lane: .primary, from: before.primary.username, to: primary.username, placed: placed(intent))
        note(&reasons, lane: .secondary, from: before.secondary?.username, to: secondary.username, placed: placed(intent))
        return Outcome(primary: primary, secondary: secondary, focus: focus, reasons: reasons)
    }

    // MARK: 意图

    /// 房间还在就不动。消失的副路沿自己的方向找，再反向；主路采用 FeedStore 的选择，落到副路就让开。
    private static func filtered(_ before: Snapshot, afterRooms: [String], proposedPrimary: String?) -> Outcome {
        var secondary = before.secondary ?? LaneCursor()
        if let name = secondary.username {
            if let kept = canonical(name, in: afterRooms) {
                secondary.username = kept
            } else {
                secondary.username = replacement(
                    for: name, before: before.rooms, after: afterRooms, direction: secondary.direction
                )
            }
        }
        var primary = before.primary
        primary.username = proposedPrimary.flatMap { canonical($0, in: afterRooms) }
        primary = yield(primary, to: secondary.username, rooms: afterRooms)
        secondary = refilled(secondary, besides: primary, rooms: afterRooms)
        return outcome(before, primary: primary, secondary: secondary, placed: .filter)
    }

    /// 整表重载、全站搜索装入或退出：副路取新列表里主路之后的第一间。
    private static func reloaded(_ before: Snapshot, afterRooms: [String], proposedPrimary: String?) -> Outcome {
        var primary = before.primary
        primary.username = proposedPrimary.flatMap { canonical($0, in: afterRooms) }
        var secondary = before.secondary ?? LaneCursor()
        secondary.username = roomAfter(primary.username, in: afterRooms)
        return outcome(before, primary: primary, secondary: secondary, placed: .sourceChange)
    }

    /// 合并、重排、前置：还在列表里的房间不动。
    private static func merged(_ before: Snapshot, afterRooms: [String], proposedPrimary: String?) -> Outcome {
        var primary = before.primary
        if let proposed = proposedPrimary, let kept = canonical(proposed, in: afterRooms) {
            primary.username = kept
        } else if let old = primary.username, let kept = canonical(old, in: afterRooms) {
            primary.username = kept
        } else {
            primary.username = nil
        }
        var secondary = before.secondary ?? LaneCursor()
        if let name = secondary.username {
            if let kept = canonical(name, in: afterRooms) {
                secondary.username = kept
            } else {
                secondary.username = replacement(
                    for: name, before: before.rooms, after: afterRooms, direction: secondary.direction
                )
            }
        }
        primary = yield(primary, to: secondary.username, rooms: afterRooms)
        secondary = refilled(secondary, besides: primary, rooms: afterRooms)
        return outcome(before, primary: primary, secondary: secondary, placed: .merge)
    }

    /// 目标正在另一路播放时只切焦点。否则主路改到目标上。
    private static func revealed(
        _ before: Snapshot,
        afterRooms: [String],
        proposedPrimary: String?,
        target: String
    ) -> Outcome {
        var primary = before.primary
        var secondary = before.secondary ?? LaneCursor()
        var focus = keptFocus(before)
        if same(secondary.username, target) {
            focus = .secondary
            primary.username = canonical(primary.username, in: afterRooms) ?? primary.username
            secondary.username = canonical(secondary.username, in: afterRooms) ?? secondary.username
        } else if same(primary.username, target) {
            focus = .primary
            primary.username = canonical(primary.username, in: afterRooms) ?? primary.username
            if let kept = secondary.username.flatMap({ canonical($0, in: afterRooms) }) {
                secondary.username = kept
            }
        } else {
            let placed = canonical(target, in: afterRooms)
                ?? proposedPrimary.flatMap { canonical($0, in: afterRooms) }
            primary.username = placed
            focus = .primary
            if let name = secondary.username, let kept = canonical(name, in: afterRooms), !same(kept, placed) {
                secondary.username = kept
            } else if same(secondary.username, placed) {
                secondary.username = nil
            }
            primary = yield(primary, to: secondary.username, rooms: afterRooms)
        }
        return outcome(before, primary: primary, secondary: secondary, focus: focus, placed: .reveal)
    }

    private static func moved(_ before: Snapshot, afterRooms: [String], lane: LaneID, delta: Int) -> Outcome {
        var primary = before.primary
        var secondary = before.secondary ?? LaneCursor()
        switch lane {
        case .primary:
            primary = step(primary, delta: delta, rooms: afterRooms, skipping: skipping(secondary.username))
        case .secondary:
            secondary = step(secondary, delta: delta, rooms: afterRooms, skipping: skipping(primary.username))
        }
        return outcome(before, primary: primary, secondary: secondary, placed: .move)
    }

    /// 空列表续上可见房间：主路用 FeedStore 选出的那间（通常是第一间），副路取它的后一间。
    private static func resumed(_ before: Snapshot, afterRooms: [String], proposedPrimary: String?) -> Outcome {
        var primary = before.primary
        if let proposed = proposedPrimary, let kept = canonical(proposed, in: afterRooms) {
            primary.username = kept
        } else {
            primary.username = afterRooms.first
        }
        var secondary = before.secondary ?? LaneCursor()
        secondary.username = roomAfter(primary.username, in: afterRooms)
        return outcome(before, primary: primary, secondary: secondary, placed: .resume)
    }

    // MARK: 找房间

    private static func step(_ cursor: LaneCursor, delta: Int, rooms: [String], skipping: Set<String>) -> LaneCursor {
        var cursor = cursor
        guard delta != 0 else { return cursor }
        cursor.direction = delta > 0 ? 1 : -1
        guard !rooms.isEmpty else {
            cursor.username = nil
            return cursor
        }
        let origin = cursor.username.flatMap { index(of: $0, in: rooms) } ?? 0
        let landed = LanePaging.landingIndex(from: origin, delta: delta, names: rooms, skipping: skipping)
        cursor.username = rooms[landed]
        return cursor
    }

    /// 副路原本空着（列表曾只剩一间）而现在有了别的房间：沿副路自己的方向补上一间，不能一直空着。
    private static func refilled(_ secondary: LaneCursor, besides primary: LaneCursor, rooms: [String]) -> LaneCursor {
        guard secondary.username == nil, let anchor = primary.username else { return secondary }
        var filled = secondary
        filled.username = neighbor(of: anchor, in: rooms, direction: secondary.direction, excluding: [anchor])
        return filled
    }

    /// 主路与副路同房时，主路从副路这间出发，沿自己的方向另找，找不到再反向。
    private static func yield(_ primary: LaneCursor, to taken: String?, rooms: [String]) -> LaneCursor {
        guard let taken, same(primary.username, taken) else { return primary }
        var primary = primary
        primary.username = neighbor(of: taken, in: rooms, direction: primary.direction, excluding: [taken])
        return primary
    }

    /// 从已经不在新列表里的房间出发，沿方向找仍可见的下一间，没有再反方向。
    private static func replacement(for missing: String, before: [String], after: [String], direction: Int) -> String? {
        guard let at = index(of: missing, in: before) else { return nil }
        func allowed(_ name: String) -> Bool {
            !same(name, missing) && after.contains { same($0, name) }
        }
        let later = before[(at + 1)...].first(where: allowed).flatMap { canonical($0, in: after) }
        let earlier = before[..<at].last(where: allowed).flatMap { canonical($0, in: after) }
        return direction >= 0 ? (later ?? earlier) : (earlier ?? later)
    }

    private static func neighbor(of name: String, in rooms: [String], direction: Int, excluding: Set<String>) -> String? {
        guard let at = index(of: name, in: rooms) else { return nil }
        let banned = Set(excluding.map { $0.lowercased() })
        func allowed(_ candidate: String) -> Bool {
            !banned.contains(candidate.lowercased()) && !same(candidate, name)
        }
        let later = rooms[(at + 1)...].first(where: allowed)
        let earlier = rooms[..<at].last(where: allowed)
        return direction >= 0 ? (later ?? earlier) : (earlier ?? later)
    }

    private static func roomAfter(_ name: String?, in rooms: [String]) -> String? {
        guard let name, let at = index(of: name, in: rooms) else { return nil }
        let next = at + 1
        guard next < rooms.count else { return nil }
        return rooms[next]
    }

    private static func skipping(_ username: String?) -> Set<String> {
        guard let username else { return [] }
        return [username]
    }

    // MARK: 结果

    private static func outcome(
        _ before: Snapshot,
        primary: LaneCursor,
        secondary: LaneCursor,
        focus: LaneID? = nil,
        placed: Reason
    ) -> Outcome {
        var reasons: [LaneID: Reason] = [:]
        note(&reasons, lane: .primary, from: before.primary.username, to: primary.username, placed: placed)
        note(&reasons, lane: .secondary, from: before.secondary?.username, to: secondary.username, placed: placed)
        return Outcome(primary: primary, secondary: secondary, focus: focus ?? keptFocus(before), reasons: reasons)
    }

    private static func note(
        _ reasons: inout [LaneID: Reason],
        lane: LaneID,
        from old: String?,
        to new: String?,
        placed: Reason
    ) {
        guard !same(old, new) else { return }
        reasons[lane] = new == nil ? .empty : placed
    }

    private static func placed(_ intent: Intent) -> Reason {
        switch intent {
        case .filter: return .filter
        case .reload, .searchInstall, .searchExit: return .sourceChange
        case .merge: return .merge
        case .reveal: return .reveal
        case .move: return .move
        case .blankResume: return .resume
        }
    }

    private static func keptFocus(_ before: Snapshot) -> LaneID {
        before.secondary == nil ? .primary : before.focus
    }

    private static func index(of name: String?, in rooms: [String]) -> Int? {
        guard let name else { return nil }
        return rooms.firstIndex { same($0, name) }
    }

    private static func canonical(_ name: String?, in rooms: [String]) -> String? {
        guard let name else { return nil }
        return rooms.first { same($0, name) }
    }

    private static func same(_ lhs: String?, _ rhs: String?) -> Bool {
        switch (lhs, rhs) {
        case (nil, nil):
            return true
        case let (left?, right?):
            return left.lowercased() == right.lowercased()
        default:
            return false
        }
    }
}
