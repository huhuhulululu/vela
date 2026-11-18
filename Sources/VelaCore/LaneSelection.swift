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

/// 双窗共享的「最近划走」：任一路因划动离开的房间，30 分钟内另一路向前划时不再落上去。
/// 只在内存里；不用定时器，到期按读取时刻判断；超过容量丢最旧。
public struct RecentSkips: Equatable, Sendable {
    public static let window: TimeInterval = 30 * 60
    public static let capacity = 200

    private struct Entry: Equatable, Sendable {
        var name: String
        var at: Date
    }

    private var entries: [Entry] = []

    public init() {}

    public var count: Int { entries.count }

    public mutating func record(_ name: String, at now: Date) {
        let key = name.lowercased()
        entries.removeAll { $0.name == key }
        entries.append(Entry(name: key, at: now))
        if entries.count > Self.capacity { entries.removeFirst(entries.count - Self.capacity) }
    }

    /// `now` 时仍在期内的房间（小写）。
    public func active(at now: Date) -> Set<String> {
        Set(entries.lazy.filter { now.timeIntervalSince($0.at) < Self.window }.map(\.name))
    }
}

/// 变更后各路停在哪一间、焦点在哪一路，以及房间变了的原因。
///
/// 单窗（只有主路）时，除划动外主路直接采用副本上 FeedStore 已经选出的
/// `proposedPrimary`，因此与 FeedStore 自己的 `setFilters` / `setQuery` / `replace` / `step` 一致。
/// 多路（2 或 3 路）按 §4.1 与 ios-triple 修订 v2 的 M4、M5 联合分配：一次分配内维护占用集合，
/// 已分配的房间立即加入；替补顺序固定为 primary → secondary → tertiary；永不同房。
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
        /// 开新路：已有房间的路不动，空路沿前一路往后补（避开最近划走，没有再放宽）。
        case fill
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

    /// 变更前的可见顺序、各路游标和焦点。`secondary`、`tertiary` 都为 nil 表示单窗。
    public struct Snapshot: Equatable, Sendable {
        public var rooms: [String]
        public var primary: LaneCursor
        public var secondary: LaneCursor?
        public var tertiary: LaneCursor?
        public var focus: LaneID
        /// 此刻仍在期内的「最近划走」房间（小写）。只有多路的划动与补位用它。
        public var recent: Set<String>

        public init(
            rooms: [String], primary: LaneCursor, secondary: LaneCursor?, tertiary: LaneCursor? = nil,
            focus: LaneID, recent: Set<String> = []
        ) {
            self.rooms = rooms
            self.primary = primary
            self.secondary = secondary
            self.tertiary = tertiary
            self.focus = focus
            self.recent = recent
        }

        /// 这一路的游标；不在场的路为 nil。
        public func cursor(_ id: LaneID) -> LaneCursor? {
            switch id {
            case .primary: return primary
            case .secondary: return secondary
            case .tertiary: return tertiary
            }
        }

        /// 在场的路，按 P → S → T。
        public var lanes: [LaneID] { LaneID.allCases.filter { cursor($0) != nil } }
    }

    public struct Outcome: Equatable, Sendable {
        public var primary: LaneCursor
        public var secondary: LaneCursor?
        public var tertiary: LaneCursor?
        public var focus: LaneID
        public var reasons: [LaneID: Reason]

        public init(
            primary: LaneCursor, secondary: LaneCursor?, tertiary: LaneCursor? = nil, focus: LaneID, reasons: [LaneID: Reason]
        ) {
            self.primary = primary
            self.secondary = secondary
            self.tertiary = tertiary
            self.focus = focus
            self.reasons = reasons
        }

        public func cursor(_ id: LaneID) -> LaneCursor? {
            switch id {
            case .primary: return primary
            case .secondary: return secondary
            case .tertiary: return tertiary
            }
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

    /// 可见列表由空变非空，且这次是被动变化（合并续页、筛选放宽）时，按 `.blankResume` 各路重新选房。
    /// 整表重载、全站搜索装入或退出、reveal、划动、补位有自己的落点规则，保留原意图。
    /// 单窗时 `.blankResume` 与这两种意图一样采用 FeedStore 的选择，结果不变。
    public static func feedIntent(_ intent: Intent, beforeRooms: [String], afterRooms: [String]) -> Intent {
        guard beforeRooms.isEmpty, !afterRooms.isEmpty else { return intent }
        switch intent {
        case .merge, .filter:
            return .blankResume
        case .reload, .searchInstall, .searchExit, .reveal, .move, .blankResume, .fill:
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
        let order = before.lanes
        if order.count <= 1 {
            return singleWindow(before: before, afterRooms: afterRooms, proposedPrimary: proposedPrimary, intent: intent)
        }
        var board = Board(before: before, order: order)
        if afterRooms.isEmpty {
            for id in order { board[id] = nil }
            return board.outcome(before, placed: .empty)
        }
        if afterRooms.count == 1 {
            for id in order { board[id] = id == board.focus ? afterRooms[0] : nil }
            return board.outcome(before, placed: placed(intent))
        }
        switch intent {
        case .filter:
            kept(&board, before: before, after: afterRooms, primary: proposedPrimary.flatMap { canonical($0, in: afterRooms) })
        case .merge:
            let choice = proposedPrimary.flatMap { canonical($0, in: afterRooms) }
                ?? before.primary.username.flatMap { canonical($0, in: afterRooms) }
            kept(&board, before: before, after: afterRooms, primary: choice)
        case .reload, .searchInstall, .searchExit:
            chained(&board, after: afterRooms, primary: proposedPrimary.flatMap { canonical($0, in: afterRooms) })
        case .blankResume:
            chained(&board, after: afterRooms, primary: proposedPrimary.flatMap { canonical($0, in: afterRooms) } ?? afterRooms.first)
        case .reveal(let target):
            revealed(&board, after: afterRooms, proposedPrimary: proposedPrimary, target: target)
        case .move(let lane, let delta):
            moved(&board, before: before, after: afterRooms, lane: lane, delta: delta)
        case .fill:
            filled(&board, before: before, after: afterRooms)
        }
        keepFocusFilled(&board, after: afterRooms)
        return board.outcome(before, placed: placed(intent))
    }

    // MARK: 单窗

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
            // 单窗没有别的路可划。
        } else {
            primary.username = proposedPrimary
        }
        var reasons: [LaneID: Reason] = [:]
        note(&reasons, lane: .primary, from: before.primary.username, to: primary.username, placed: placed(intent))
        return Outcome(primary: primary, secondary: nil, focus: .primary, reasons: reasons)
    }

    // MARK: 意图

    /// 筛选、合并：房间还在就不动。主路采用给定的选择，落到别的路正在播的房间就沿自己的方向让开；
    /// 房间消失的路按 P → S → T 沿各自方向找替补（再反向），仍空着的路从主路出发补上一间。
    private static func kept(_ board: inout Board, before: Snapshot, after: [String], primary choice: String?) {
        var missing: [LaneID] = []
        for id in board.order where id != .primary {
            guard let name = board[id] else { continue }
            if let kept = canonical(name, in: after) {
                board[id] = kept
            } else {
                board[id] = nil
                missing.append(id)
            }
        }
        board[.primary] = choice
        if let taken = choice, board.isTaken(taken, except: .primary) {
            board[.primary] = neighbor(
                of: taken, in: after, direction: board.direction(.primary), excluding: board.taken(except: .primary)
            )
        }
        for id in missing {
            guard let old = before.cursor(id)?.username else { continue }
            board[id] = replacement(
                for: old, before: before.rooms, after: after, direction: board.direction(id), excluding: board.taken(except: id)
            )
        }
        refill(&board, after: after)
    }

    /// 整表重载、全站搜索装入或退出、续页恢复：主路采用给定的选择，其余各路依次取前一路之后的第一间，不绕回开头。
    private static func chained(_ board: inout Board, after: [String], primary choice: String?) {
        board[.primary] = choice
        var anchor = choice
        for id in board.order where id != .primary {
            board[id] = anchor.flatMap { roomAfter($0, in: after) }
            if let placed = board[id] { anchor = placed }
        }
    }

    /// 目标正在某一路播放时只切焦点。否则主路改到目标上、焦点回主路，与目标同房的路置空。
    private static func revealed(_ board: inout Board, after: [String], proposedPrimary: String?, target: String) {
        if let hit = board.order.first(where: { same(board[$0], target) }) {
            board.focus = hit
            for id in board.order { board[id] = canonical(board[id], in: after) ?? board[id] }
            return
        }
        let placed = canonical(target, in: after) ?? proposedPrimary.flatMap { canonical($0, in: after) }
        board[.primary] = placed
        board.focus = .primary
        for id in board.order where id != .primary {
            if let kept = canonical(board[id], in: after), !same(kept, placed) {
                board[id] = kept
            } else if same(board[id], placed) {
                board[id] = nil
            }
        }
    }

    /// 划动一路：跳过其他各路此刻的房间与最近划走（本路上一间除外）。
    private static func moved(_ board: inout Board, before: Snapshot, after: [String], lane: LaneID, delta: Int) {
        guard board.order.contains(lane), var cursor = before.cursor(lane) else { return }
        cursor = step(cursor, delta: delta, rooms: after, skipping: board.taken(except: lane), recent: before.recent)
        board[lane] = cursor.username
        board.directions[lane] = cursor.direction
    }

    /// 开新路：已有房间的路保留（仍在列表里），空路依次沿前一路之后补，避开最近划走；没有再放宽，往后没有就往前找。
    private static func filled(_ board: inout Board, before: Snapshot, after: [String]) {
        for id in board.order { board[id] = canonical(board[id], in: after) }
        var anchor = board[.primary]
        for id in board.order where id != .primary {
            if let name = board[id] {
                anchor = name
                continue
            }
            guard let from = anchor else { continue }
            let taken = board.taken(except: id)
            let strict = neighbor(of: from, in: after, direction: 1, excluding: taken.union(before.recent))
            board[id] = strict ?? neighbor(of: from, in: after, direction: 1, excluding: taken)
            if let placed = board[id] { anchor = placed }
        }
    }

    // MARK: 找房间

    /// `recent` 里的房间先一并跳过（本路自己的上一间除外）；这样没有落点时放宽成只避开其他各路，仍不行才原地。
    private static func step(
        _ cursor: LaneCursor, delta: Int, rooms: [String], skipping: Set<String>, recent: Set<String> = []
    ) -> LaneCursor {
        var cursor = cursor
        guard delta != 0 else { return cursor }
        cursor.direction = delta > 0 ? 1 : -1
        guard !rooms.isEmpty else {
            cursor.username = nil
            return cursor
        }
        let origin = cursor.username.flatMap { index(of: $0, in: rooms) } ?? 0
        var landed = LanePaging.landingIndex(from: origin, delta: delta, names: rooms, skipping: skipping)
        if !recent.isEmpty {
            var shunned = recent
            if let previous = cursor.previous?.lowercased() { shunned.remove(previous) }
            let banned = Set(skipping.map { $0.lowercased() }).union(shunned)
            let strict = LanePaging.landingIndex(from: origin, delta: delta, names: rooms, skipping: banned)
            // 过滤后原地不动（或只剩被避开的房间，如本路当前房间也在最近里）才放宽（列表短或已到头）。
            if strict != origin, !banned.contains(rooms[strict].lowercased()) { landed = strict }
        }
        cursor.username = rooms[landed]
        return cursor
    }

    /// 非主路原本空着（列表曾只剩一间）而现在有了别的房间：沿这一路自己的方向从主路出发补上一间，不能一直空着。
    private static func refill(_ board: inout Board, after: [String]) {
        guard let anchor = board[.primary] else { return }
        for id in board.order where id != .primary && board[id] == nil {
            board[id] = neighbor(of: anchor, in: after, direction: board.direction(id), excluding: board.taken(except: id))
        }
    }

    /// 焦点路没分到房间而别处还有：先取一间空闲的，没有就按 T → S → P 从非焦点路拿过来（焦点优先，空格留给非焦点路）。
    private static func keepFocusFilled(_ board: inout Board, after: [String]) {
        let focus = board.focus
        guard board[focus] == nil else { return }
        let taken = board.taken(except: focus)
        if let free = after.first(where: { !taken.contains($0.lowercased()) }) {
            board[focus] = free
            return
        }
        guard let donor = board.order.reversed().first(where: { $0 != focus && board[$0] != nil }) else { return }
        board[focus] = board[donor]
        board[donor] = nil
    }

    /// 从已经不在新列表里的房间出发，沿方向找仍可见、未被占用的下一间，没有再反方向。
    private static func replacement(
        for missing: String, before: [String], after: [String], direction: Int, excluding taken: Set<String>
    ) -> String? {
        guard let at = index(of: missing, in: before) else { return nil }
        func allowed(_ name: String) -> Bool {
            !same(name, missing) && !taken.contains(name.lowercased()) && after.contains { same($0, name) }
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

    private static func roomAfter(_ name: String, in rooms: [String]) -> String? {
        guard let at = index(of: name, in: rooms) else { return nil }
        let next = at + 1
        guard next < rooms.count else { return nil }
        return rooms[next]
    }

    // MARK: 一次分配的工作台

    /// 一次分配内各路的房间、方向与焦点；`taken` 就是动态占用集合（已分配的房间立即算占用）。
    private struct Board {
        let order: [LaneID]
        var rooms: [LaneID: String] = [:]
        var directions: [LaneID: Int] = [:]
        var focus: LaneID

        init(before: Snapshot, order: [LaneID]) {
            self.order = order
            for id in order {
                guard let cursor = before.cursor(id) else { continue }
                rooms[id] = cursor.username
                directions[id] = cursor.direction
            }
            focus = order.contains(before.focus) ? before.focus : .primary
        }

        subscript(_ id: LaneID) -> String? {
            get { rooms[id] }
            set { rooms[id] = newValue }
        }

        func direction(_ id: LaneID) -> Int { directions[id] ?? 1 }

        /// 除 `lane` 以外各路此刻占着的房间（小写）。
        func taken(except lane: LaneID) -> Set<String> {
            Set(order.filter { $0 != lane }.compactMap { rooms[$0]?.lowercased() })
        }

        func isTaken(_ name: String, except lane: LaneID) -> Bool {
            taken(except: lane).contains(name.lowercased())
        }

        func outcome(_ before: Snapshot, placed: Reason) -> Outcome {
            var reasons: [LaneID: Reason] = [:]
            var cursors: [LaneID: LaneCursor] = [:]
            for id in order {
                guard var cursor = before.cursor(id) else { continue }
                let old = cursor.username
                cursor.username = rooms[id]
                cursor.direction = direction(id)
                cursors[id] = cursor
                note(&reasons, lane: id, from: old, to: cursor.username, placed: placed)
            }
            return Outcome(
                primary: cursors[.primary] ?? before.primary, secondary: cursors[.secondary], tertiary: cursors[.tertiary],
                focus: focus, reasons: reasons
            )
        }
    }

    // MARK: 结果

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
        case .blankResume, .fill: return .resume
        }
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
