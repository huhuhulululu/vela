import XCTest
@testable import VelaCore

/// 第 5.2 节：离线快钟重新盖章。
final class SyncRestampTests: XCTestCase {
    typealias F = SyncFixture
    let dev: UInt32 = 5
    let hour: UInt64 = 3_600_000
    let n: UInt64 = 10_000_000_000

    func replicaWithFastOps() -> SyncReplica {
        var r = SyncReplica(clock: SyncClock(dev: dev))
        r.doc.rooms["cb:peer"] = SyncRoom(f: F.reg(n - 50, "+", dev: 9))
        let fast = n + 3 * hour
        guard case .applied(let a) = SyncOps.apply(.favorite("Alice"), to: r, pt: fast) else { fatalError() }
        guard case .applied(let b) = SyncOps.apply(.veto("t:x"), to: a, pt: fast) else { fatalError() }
        guard case .applied(let c) = SyncOps.apply(.setting("publicOnly", true), to: b, pt: fast + 1) else { fatalError() }
        return c
    }

    func testRestampsUnsentFastRegistersInOrder() {
        var r = replicaWithFastOps()
        let pt = n + 10
        XCTAssertEqual(SyncRestamp.presend(&r, serverNow: n, maxSentL: 0, pt: pt), .restamped(count: 5))
        let room = r.doc.rooms["cb:alice"]!
        XCTAssertEqual(room.f?.hlc, HLC(l: pt, c: 0, dev: dev), "同一次操作的 f、b、n 共用一个新 tick")
        XCTAssertEqual(room.b?.hlc, room.f?.hlc)
        XCTAssertEqual(room.n?.hlc, room.f?.hlc)
        XCTAssertEqual(room.f?.payload, "+")
        XCTAssertEqual(room.b?.payload, "-")
        XCTAssertEqual(r.doc.veto["t:x"]?.hlc, HLC(l: pt, c: 1, dev: dev))
        XCTAssertEqual(r.doc.settings["publicOnly"], SyncRegister(hlc: HLC(l: pt, c: 2, dev: dev), payload: "1"))
        XCTAssertEqual(r.doc.rooms["cb:peer"]?.f?.hlc.dev, 9, "别人的寄存器不动")
        XCTAssertEqual(r.clock.current, HLC(l: pt, c: 2, dev: dev))
        XCTAssertEqual(SyncRestamp.presend(&r, serverNow: n, maxSentL: 0, pt: pt), .unchanged)
    }

    func testRestampFloorIsMaxOfOthersAndServerTime() {
        var r = replicaWithFastOps()
        r.doc.rooms["cb:late"] = SyncRoom(b: F.reg(n + 100, "+", dev: 9))
        _ = SyncRestamp.presend(&r, serverNow: n, maxSentL: 0, pt: n - 5)
        XCTAssertEqual(r.doc.rooms["cb:alice"]?.f?.hlc, HLC(l: n + 100, c: 1, dev: dev), "本机墙钟落后时排在已有最大值之后")
    }

    func testAlreadySentRegistersAreNeverRewritten() {
        var r = replicaWithFastOps()
        let before = r
        XCTAssertEqual(SyncRestamp.presend(&r, serverNow: n, maxSentL: n + 3 * hour, pt: n), .blockedBySent)
        XCTAssertEqual(r, before)
    }

    func testWithinOneHourIsUnchanged() {
        var r = SyncReplica(clock: SyncClock(dev: dev))
        guard case .applied(var a) = SyncOps.apply(.favorite("alice"), to: r, pt: n + hour) else { return XCTFail() }
        XCTAssertEqual(SyncRestamp.presend(&a, serverNow: n, maxSentL: 0, pt: n), .unchanged)
        r = a
        XCTAssertEqual(r.doc.rooms["cb:alice"]?.f?.hlc.l, n + hour)
    }

    func testRestampedDeleteStillBeatsEarlierAdd() {
        // 先看见远端收藏，再离线（快钟）删除；重新盖章后删除仍排在收藏之后。
        var r = SyncReplica(clock: SyncClock(dev: dev))
        r.doc.rooms["cb:alice"] = SyncRoom(f: F.reg(n - 10, "+", dev: 9), b: F.reg(n - 10, "-", dev: 9))
        r.clock.receive(r.doc.maxHLC)
        guard case .applied(var a) = SyncOps.apply(.unfavorite("alice"), to: r, pt: n + 5 * hour) else { return XCTFail() }
        _ = SyncRestamp.presend(&a, serverNow: n, maxSentL: 0, pt: n)
        XCTAssertFalse(SyncProjection(a.doc).isFavorite("cb:alice"))
        XCTAssertLessThanOrEqual(a.doc.rooms["cb:alice"]!.f!.hlc.l, n + hour)
    }
}
