import XCTest
@testable import VelaCore

/// 第 7.2 节：本端随机多副本模拟，证明确定性加最终一致。
/// N 个副本（2 到 6）随机增删收藏、屏蔽、「不想看」与设置，含快钟设备与超过各上限的操作量，
/// 随机顺序两两合并、重复合并，最后与同一个「服务端」副本轮流同步直到连续两轮无变化。
final class SyncSimulationTests: XCTestCase {
    /// 固定种子写进测试；失败时打印种子与组号。
    static let seed: UInt64 = 0x5EED_2026_1118
    static let runs = 1_000

    struct SplitMix64: RandomNumberGenerator {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
    }

    struct Device {
        var replica: SyncReplica
        /// 本机墙钟相对真实时间的偏移（快钟设备为正）。
        var skew: UInt64
        var maxSentL: UInt64 = 0
    }

    struct Outcome {
        var heavy = false
        var trimmedSomething = false
    }

    func testRandomReplicasConverge() {
        var heavyRuns = 0, trimmedRuns = 0, ops = 0
        for run in 0..<Self.runs {
            var rng = SplitMix64(state: Self.seed &+ UInt64(run) &* 0x1000_0000_01)
            let heavy = run % 10 == 0
            let result = simulate(run: run, heavy: heavy, rng: &rng, ops: &ops)
            if result.heavy { heavyRuns += 1 }
            if result.trimmedSomething { trimmedRuns += 1 }
            if testRun?.failureCount ?? 0 > 0 {
                print("同步模拟失败：seed=\(Self.seed) run=\(run)")
                return
            }
        }
        print("同步模拟：\(Self.runs) 组，其中重负载 \(heavyRuns) 组、实际触发裁剪 \(trimmedRuns) 组，本地操作 \(ops) 次")
        XCTAssertGreaterThan(trimmedRuns, 0, "重负载组必须真的超过上限")
    }

    func simulate(run: Int, heavy: Bool, rng: inout SplitMix64, ops: inout Int) -> Outcome {
        var outcome = Outcome(heavy: heavy)
        let count = Int.random(in: 2...6, using: &rng)
        var now: UInt64 = 1_800_000_000_000
        var devices = (0..<count).map { i in
            Device(replica: SyncReplica(clock: SyncClock(dev: UInt32(i + 1) &* 0x0101_0101)), skew: i == 0 && run % 3 == 0 ? 3 * 3_600_000 : 0)
        }
        var server = SyncDocument.empty
        // 所有本地操作写出的寄存器之并：轻负载组里它就是最终文档（每个操作都生效）。
        var oracle = SyncDocument.empty
        let namePool = heavy ? 700 : 120
        let tagPool = heavy ? 1_200 : 60
        let steps = heavy ? 120 : 40

        func name() -> String { String(format: "u%03d", Int.random(in: 0..<namePool, using: &rng)) }
        func tag() -> String { String(format: "t:k%03d", Int.random(in: 0..<tagPool, using: &rng)) }

        func maxL(_ d: SyncDocument) -> UInt64 { d.allRegisters.map(\.hlc.l).max() ?? 0 }

        func merge(_ a: SyncDocument, _ b: SyncDocument) -> SyncDocument {
            let u = SyncMerge.union(a, b)
            let fitted = SyncMerge.fitSize(u)
            if fitted.doc != u { outcome.trimmedSomething = true }
            return fitted.doc
        }

        func syncWithServer(_ i: Int) {
            var d = devices[i]
            d.replica.doc = merge(d.replica.doc, server)
            d.replica.clock.receive(server.maxHLC)
            _ = SyncRestamp.presend(&d.replica, serverNow: now, maxSentL: d.maxSentL, pt: now)
            server = d.replica.doc
            d.maxSentL = max(d.maxSentL, maxL(d.replica.doc))
            devices[i] = d
        }

        if heavy {
            // 重负载：各副本先以大量本地数据开启同步（迁移盖章一次写几百个寄存器），制造超过各上限的并集。
            for i in 0..<count {
                let favs = (0..<Int.random(in: 100...300, using: &rng)).map { _ in name() }
                let blocks = (0..<Int.random(in: 0...120, using: &rng)).map { _ in name() }
                let vetoes = (0..<Int.random(in: 200...460, using: &rng)).map { _ in tag() }
                let local = SyncLocalData(curation: Curation(favorites: favs, blocked: blocks), vetoedKeys: vetoes)
                devices[i].replica = SyncMigration.stampCreator(local, clock: devices[i].replica.clock, pt: now + devices[i].skew).replica
                if Bool.random(using: &rng), case .applied(let next) = SyncOps.apply(.clearVetoes, to: devices[i].replica, pt: now + devices[i].skew + 1) {
                    devices[i].replica = next
                }
            }
        }

        for _ in 0..<steps {
            now += UInt64.random(in: 0...3, using: &rng)
            let i = Int.random(in: 0..<count, using: &rng)
            switch Int.random(in: 0..<100, using: &rng) {
            case 0..<55:
                let op: SyncLocalOp
                switch Int.random(in: 0..<12, using: &rng) {
                case 0, 1, 2: op = .favorite(name())
                case 3: op = .unfavorite(name())
                case 4, 5: op = .block(name())
                case 6: op = .unblock(name())
                case 7: op = .restoreFavorite(name())
                case 8: op = .veto(tag())
                case 9: op = .unveto(tag())
                case 10: op = Bool.random(using: &rng) ? .setting("publicOnly", Bool.random(using: &rng)) : .clearVetoes
                default:
                    let size = heavy ? Int.random(in: 50...320, using: &rng) : Int.random(in: 1...8, using: &rng)
                    op = .importing((0..<size).map { _ in name() })
                }
                ops += 1
                if case .applied(let next) = SyncOps.apply(op, to: devices[i].replica, pt: now + devices[i].skew) {
                    devices[i].replica = next
                    oracle = SyncMerge.union(oracle, next.doc)
                }
            case 55..<80:
                // 两两合并抽象了「经服务端交换」：离开设备的寄存器都算已发出（第 5.2 节 maxSentL）。
                let j = Int.random(in: 0..<count, using: &rng)
                devices[j].maxSentL = max(devices[j].maxSentL, maxL(devices[j].replica.doc))
                let merged = merge(devices[i].replica.doc, devices[j].replica.doc)
                if Int.random(in: 0..<4, using: &rng) == 0 {
                    XCTAssertEqual(merged, merge(devices[j].replica.doc, devices[i].replica.doc), "交换律 run=\(run)")
                }
                devices[i].replica.doc = merged
                devices[i].replica.clock.receive(merged.maxHLC)
                if Bool.random(using: &rng) {
                    devices[i].maxSentL = max(devices[i].maxSentL, maxL(devices[i].replica.doc))
                    devices[j].replica.doc = merged
                    devices[j].replica.clock.receive(merged.maxHLC)
                }
                if Bool.random(using: &rng) { // 重复合并
                    XCTAssertEqual(merge(devices[i].replica.doc, merged), devices[i].replica.doc, "幂等 run=\(run)")
                }
            default:
                syncWithServer(i)
            }
            XCTAssertTrue(SyncMerge.withinCounts(devices[i].replica.doc), "上限 run=\(run)")
        }

        // 收尾：依次与同一个服务端副本同步，直到连续两轮无变化。
        var quiet = 0, rounds = 0
        while quiet < 2 {
            let before = devices.map(\.replica.doc) + [server]
            for i in 0..<count { syncWithServer(i) }
            quiet = before == devices.map(\.replica.doc) + [server] ? quiet + 1 : 0
            rounds += 1
            if rounds > 20 { XCTFail("收尾不收敛 run=\(run)"); break }
        }
        let final = devices[0].replica.doc
        for d in devices { XCTAssertEqual(d.replica.doc, final, "最终一致 run=\(run)") }
        XCTAssertTrue(SyncMerge.withinCounts(final), "最终上限 run=\(run)")
        XCTAssertLessThanOrEqual(final.encoded().count, SyncLimits.plaintextBytes)
        XCTAssertEqual(SyncDocument.decode(final.encoded()), .valid(final), "最终文档合法 run=\(run)")
        if !heavy, !outcome.trimmedSomething, devices.allSatisfy({ $0.skew == 0 }) {
            XCTAssertEqual(final, oracle, "从未超限时所有操作全部生效 run=\(run)")
        }
        return outcome
    }
}
