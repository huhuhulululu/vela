/// 离线快钟在发出前重新盖章（docs/plans/sync.md 第 5.2 节第 2 条）。
public enum SyncRestamp {
    public enum Result: Equatable, Sendable {
        /// 没有本机写的、超前服务端 1 小时以上的寄存器。
        case unchanged
        /// 候选集全部从未离开本机，已按原 HLC 升序逐组重新 tick（载荷不变）。
        case restamped(count: Int)
        /// 候选集里有已发出过的寄存器（`l ≤ maxSentL`）：不改写任何寄存器，照常推送，诊断页记一条。
        /// TODO(sync.md §5.2)：「其余照常推进」按「整组不重新盖章」实现，未逐个改写未发出的那部分。
        case blockedBySent
    }

    /// `serverNow` 为本周期 GET 得到的服务端时间 `N`；`pt` 为本机墙钟（调用方已通过在线偏差检查）。
    public static func presend(_ replica: inout SyncReplica, serverNow: UInt64, maxSentL: UInt64, pt: UInt64) -> Result {
        let threshold = serverNow + SyncLimits.clockSkewMillis
        let dev = replica.clock.dev
        func candidate(_ r: SyncRegister?) -> Bool { r.map { $0.hlc.dev == dev && $0.hlc.l > threshold } ?? false }
        let doc = replica.doc
        var groups = Set<HLC>()
        var others: [HLC] = []
        for room in doc.rooms.values {
            for r in room.registers { if candidate(r) { groups.insert(r.hlc) } else { others.append(r.hlc) } }
        }
        for r in Array(doc.veto.values) + Array(doc.settings.values) {
            if candidate(r) { groups.insert(r.hlc) } else { others.append(r.hlc) }
        }
        guard !groups.isEmpty else { return .unchanged }
        guard groups.allSatisfy({ $0.l > maxSentL }) else { return .blockedBySent }
        let floor = HLC(l: serverNow, c: 0, dev: 0)
        let base = max(others.max() ?? floor, floor)
        replica.clock.reset(to: base)
        var mapping: [HLC: HLC] = [:]
        for old in groups.sorted() { mapping[old] = replica.clock.tick(pt: pt) }
        func moved(_ r: SyncRegister?) -> SyncRegister? {
            guard let r, let fresh = mapping[r.hlc] else { return r }
            return SyncRegister(hlc: fresh, payload: r.payload)
        }
        var next = doc
        var count = 0
        for (key, room) in doc.rooms {
            let updated = SyncRoom(f: moved(room.f), b: moved(room.b), n: moved(room.n))
            count += zip(room.registers, updated.registers).filter { $0.0 != $0.1 }.count
            next.rooms[key] = updated
        }
        for (key, r) in doc.veto where mapping[r.hlc] != nil {
            next.veto[key] = moved(r)
            count += 1
        }
        for (key, r) in doc.settings where mapping[r.hlc] != nil {
            next.settings[key] = moved(r)
            count += 1
        }
        replica.doc = next
        return .restamped(count: count)
    }
}
