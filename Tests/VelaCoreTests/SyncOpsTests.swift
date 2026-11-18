import XCTest
@testable import VelaCore

/// 第 6.4 节：本地操作到寄存器的映射。
final class SyncOpsTests: XCTestCase {
    typealias F = SyncFixture
    let dev: UInt32 = 0xa1b2c3d4

    func replica(_ doc: SyncDocument = .empty) -> SyncReplica { SyncReplica(doc: doc, clock: SyncClock(dev: dev)) }

    func applied(_ op: SyncLocalOp, _ r: SyncReplica, pt: UInt64 = 1_000, file: StaticString = #filePath, line: UInt = #line) -> SyncReplica {
        guard case .applied(let next) = SyncOps.apply(op, to: r, pt: pt) else {
            XCTFail("expected applied: \(op)", file: file, line: line)
            return r
        }
        return next
    }

    func h(_ l: UInt64, _ c: UInt16 = 0) -> HLC { HLC(l: l, c: c, dev: dev) }

    func testFavoriteWritesPairAndDisplayName() {
        let r = applied(.favorite(" Alice "), replica())
        XCTAssertEqual(r.doc.rooms["cb:alice"], SyncRoom(f: SyncRegister(hlc: h(1000), payload: "+"), b: SyncRegister(hlc: h(1000), payload: "-"), n: SyncRegister(hlc: h(1000), payload: "Alice")))
        let lower = applied(.favorite("bob"), replica())
        XCTAssertNil(lower.doc.rooms["cb:bob"]?.n, "原文与键相同且没有 n 时不写 n")
        let again = applied(.favorite("alice"), r, pt: 1_001)
        XCTAssertEqual(again.doc.rooms["cb:alice"]?.n?.payload, "alice", "已有 n 时总是写")
    }

    func testBlockWritesPair() {
        let r = applied(.block("Bob"), applied(.favorite("bob"), replica()), pt: 1_000)
        XCTAssertEqual(r.doc.rooms["cb:bob"]?.b, SyncRegister(hlc: h(1000, 1), payload: "+"))
        XCTAssertEqual(r.doc.rooms["cb:bob"]?.f, SyncRegister(hlc: h(1000, 1), payload: "-"))
        XCTAssertTrue(SyncProjection(r.doc).isBlocked("cb:bob"))
    }

    func testPairedBookkeepingCoversUnseenOlderBlock() {
        // 对方有一条本机没见过、时间更早的屏蔽；本机收藏后又取消收藏，旧屏蔽不能冒出来。
        var remote = SyncDocument()
        remote.rooms["cb:xx"] = SyncRoom(b: F.reg(500, "+", dev: 9))
        var mine = applied(.favorite("xx"), replica())
        mine = applied(.unfavorite("xx"), mine, pt: 1_001)
        let merged = SyncProjection(SyncMerge.merge(mine.doc, remote))
        XCTAssertFalse(merged.isBlocked("cb:xx"))
        XCTAssertFalse(merged.isFavorite("cb:xx"))
    }

    func testPreconditionsNoWrite() {
        let r = replica()
        XCTAssertEqual(SyncOps.apply(.unfavorite("alice"), to: r, pt: 1), .unchanged)
        XCTAssertEqual(SyncOps.apply(.unblock("alice"), to: r, pt: 1), .unchanged)
        XCTAssertEqual(SyncOps.apply(.favorite("a"), to: r, pt: 1), .unchanged, "键不合法")
        let blocked = applied(.block("alice"), r)
        XCTAssertEqual(SyncOps.apply(.restoreFavorite("alice"), to: blocked, pt: 2_000), .unchanged)
        let fav = applied(.favorite("alice"), r)
        XCTAssertEqual(SyncOps.apply(.restoreFavorite("alice"), to: fav, pt: 2_000), .unchanged)
        XCTAssertEqual(SyncOps.apply(.unveto("t:asian"), to: r, pt: 1), .unchanged)
        XCTAssertEqual(SyncOps.apply(.clearVetoes, to: r, pt: 1), .unchanged)
        XCTAssertEqual(SyncOps.apply(.setting("muted", true), to: r, pt: 1), .unchanged)
        XCTAssertEqual(r.clock, SyncClock(dev: dev), "不写就不 tick")
    }

    func testRestoreFavoriteWritesLikeFavorite() {
        let r = applied(.restoreFavorite("Alice"), replica())
        XCTAssertTrue(SyncProjection(r.doc).isFavorite("cb:alice"))
        XCTAssertEqual(r.doc.rooms["cb:alice"]?.b?.payload, "-")
    }

    func testUnfavoriteAndUnblockWriteMinus() {
        var r = applied(.favorite("alice"), replica())
        r = applied(.unfavorite("ALICE"), r)
        XCTAssertEqual(r.doc.rooms["cb:alice"]?.f, SyncRegister(hlc: h(1000, 1), payload: "-"))
        r = applied(.block("bob"), r)
        r = applied(.unblock("bob"), r)
        XCTAssertEqual(r.doc.rooms["cb:bob"]?.b?.payload, "-")
        XCTAssertEqual(r.doc.rooms["cb:bob"]?.f?.payload, "-")
    }

    func testDeleteThenAddInSameMillisecondKeepsAdd() {
        var r = applied(.favorite("alice"), replica())
        r = applied(.unfavorite("alice"), r)
        r = applied(.favorite("alice"), r)
        XCTAssertTrue(SyncProjection(r.doc).isFavorite("cb:alice"))
    }

    func testVetoMappingAndQuota() {
        var r = applied(.veto(Taste.tagKey("Asian")), replica())
        XCTAssertEqual(r.doc.veto["t:asian"]?.payload, "+")
        r = applied(.unveto("t:asian"), r)
        XCTAssertEqual(r.doc.veto["t:asian"]?.payload, "-")
        XCTAssertEqual(SyncOps.apply(.veto(Taste.tagKey("big tits")), to: r, pt: 1), .localOnly)
        XCTAssertEqual(SyncOps.apply(.veto(Taste.tagKey("big tits")), to: r, pt: 1, localOnlyVetoCount: 50), .localOnlyQuotaFull)
        var full = SyncDocument()
        for i in 0..<450 { full.veto[String(format: "t:k%03d", i)] = F.reg(1, "+") }
        XCTAssertEqual(SyncOps.apply(.veto("t:new"), to: replica(full), pt: 5), .vetoQuotaFull)
        guard case .applied = SyncOps.apply(.veto("t:k001"), to: replica(full), pt: 5) else { return XCTFail("已在的键再标不占额度") }
    }

    func testClearVetoesSharesOneTick() {
        var r = applied(.veto("t:a"), replica())
        r = applied(.veto("g:m"), r)
        r = applied(.unveto("g:m"), r)
        r = applied(.veto("t:b"), r)
        r = applied(.clearVetoes, r, pt: 2_000)
        XCTAssertEqual(r.doc.veto["t:a"], SyncRegister(hlc: h(2000), payload: "-"))
        XCTAssertEqual(r.doc.veto["t:b"], SyncRegister(hlc: h(2000), payload: "-"))
        XCTAssertEqual(r.doc.veto["g:m"]?.hlc, h(1000, 2), "原本是 - 的不重写")
    }

    func testSettingWrites() {
        let r = applied(.setting("infoAlwaysOn", true), replica())
        XCTAssertEqual(r.doc.settings["infoAlwaysOn"], SyncRegister(hlc: h(1000), payload: "1"))
    }

    // MARK: 导入

    func testImportWindowIsFixedByFileContent() {
        let names = ["a", "Alice", "alice", "bob"] + (0..<400).map { String(format: "u%03d", $0) }
        let window = SyncOps.importWindow(names)
        XCTAssertEqual(window.count, 300)
        XCTAssertEqual(window.prefix(3).map(\.key), ["cb:alice", "cb:bob", "cb:u000"])
        XCTAssertEqual(window.last?.key, "cb:u297")
    }

    func testImportFileOrderIsNewestAndIdempotent() {
        var r = applied(.favorite("old"), replica(), pt: 500)
        r = applied(.importing(["carol", "dave", "old", "erin"]), r)
        let p = SyncProjection(r.doc)
        XCTAssertEqual(p.favorites.map(\.key), ["cb:carol", "cb:dave", "cb:erin", "cb:old"], "文件里排在前面的更新；已收藏的不重写")
        XCTAssertEqual(SyncOps.apply(.importing(["carol", "dave", "old", "erin"]), to: r, pt: 2_000), .unchanged)
        let removed = applied(.unfavorite("dave"), r)
        let again = applied(.importing(["carol", "dave", "old", "erin"]), removed, pt: 3_000)
        XCTAssertTrue(SyncProjection(again.doc).isFavorite("cb:dave"), "两次之间删掉的名字会被重新加入")
    }

    func testImportSkipsBlockedAndPushesOutOldFavorites() {
        var r = replica()
        for i in 0..<300 { r = applied(.favorite(String(format: "old%03d", i)), r, pt: UInt64(100 + i)) }
        r = applied(.block("blk"), r, pt: 900)
        let fresh = (0..<10).map { String(format: "new%03d", $0) } + ["blk"]
        r = applied(.importing(fresh), r, pt: 1_000)
        let p = SyncProjection(r.doc)
        XCTAssertEqual(p.favorites.count, 300)
        XCTAssertEqual(Array(p.favorites.prefix(10).map(\.key)), (0..<10).map { String(format: "cb:new%03d", $0) })
        XCTAssertFalse(p.isFavorite("cb:old000"), "原有最旧的被挤掉")
        XCTAssertTrue(p.isBlocked("cb:blk"))
    }

    // MARK: 体积

    func testLocalOpRejectedWhenFull() {
        var d = SyncDocument()
        for i in 0..<450 { d.veto["t:" + String(format: "%03d", i) + String(repeating: "\u{4E00}", count: 42)] = F.reg(1, "+") }
        for i in 0..<300 { d.rooms[String(format: "cb:f%03d_", i) + String(repeating: "x", count: 25)] = SyncRoom(f: F.reg(2, "+"), b: F.reg(2, "-"), n: F.reg(2, String(format: "F%03d_", i) + String(repeating: "x", count: 25))) }
        for i in 0..<300 { d.rooms[String(format: "cb:b%03d_", i) + String(repeating: "x", count: 25)] = SyncRoom(f: F.reg(2, "-"), b: F.reg(2, "+"), n: F.reg(2, String(format: "B%03d_", i) + String(repeating: "x", count: 25))) }
        XCTAssertLessThan(d.encoded().count, SyncLimits.plaintextBytes, "数量上限内的在用部分远小于 P")
        // 用很小的上限验证拒写路径：直接调体积函数。
        let fitted = SyncMerge.fitSize(d, limit: 1_000)
        XCTAssertFalse(fitted.fits)
    }
}
