import XCTest
@testable import VelaCore

/// 第 6.6、6.7、7 节：`U`、`E`、体积处理的确定性用例。
final class SyncMergeTests: XCTestCase {
    typealias F = SyncFixture

    func room(f: SyncRegister? = nil, b: SyncRegister? = nil, n: SyncRegister? = nil) -> SyncRoom { SyncRoom(f: f, b: b, n: n) }

    func testUnionTakesLargerRegisterPerField() {
        var a = SyncDocument(), b = SyncDocument()
        a.rooms["cb:x"] = room(f: F.reg(5, "+"), n: F.reg(5, "X"))
        b.rooms["cb:x"] = room(f: F.reg(4, "-"), b: F.reg(6, "+"))
        b.rooms["cb:y"] = room(b: F.reg(1, "+"))
        a.settings["publicOnly"] = F.reg(3, "0")
        b.settings["publicOnly"] = F.reg(3, "1")
        let m = SyncMerge.merge(a, b)
        XCTAssertEqual(m.rooms["cb:x"], room(f: F.reg(5, "+"), b: F.reg(6, "+"), n: F.reg(5, "X")))
        XCTAssertEqual(m.rooms["cb:y"], room(b: F.reg(1, "+")))
        XCTAssertEqual(m.settings["publicOnly"], F.reg(3, "1"), "同刻 1 胜")
        XCTAssertTrue(m.rooms["cb:x"]!.isBlocked)
    }

    func testSameHLCDeleteWins() {
        var a = SyncDocument(), b = SyncDocument()
        a.rooms["cb:x"] = room(f: F.reg(5, "+"))
        b.rooms["cb:x"] = room(f: F.reg(5, "-"))
        a.veto["g:m"] = F.reg(2, "+")
        b.veto["g:m"] = F.reg(2, "-")
        let m = SyncMerge.merge(a, b)
        XCTAssertEqual(m.rooms["cb:x"]?.f, F.reg(5, "-"))
        XCTAssertEqual(m.veto["g:m"], F.reg(2, "-"))
    }

    func testFavoriteVersusBlockTieBlockWins() {
        let r = room(f: F.reg(5, "+"), b: F.reg(5, "+"))
        XCTAssertTrue(r.isBlocked)
        XCTAssertFalse(r.isFavorite)
        XCTAssertTrue(room(f: F.reg(5, "+", c: 1), b: F.reg(5, "+")).isFavorite)
        XCTAssertTrue(room(f: F.reg(5, "+"), b: F.reg(5, "+", dev: 2)).isBlocked, "HLC 含设备号")
        XCTAssertFalse(room(f: F.reg(5, "-"), b: F.reg(4, "-")).isFavorite)
    }

    func testMergeIsCommutativeAndIdempotent() {
        var a = SyncDocument(), b = SyncDocument()
        a.rooms["cb:x"] = room(f: F.reg(5, "+"))
        b.rooms["cb:x"] = room(b: F.reg(5, "+", dev: 3))
        b.veto["t:a"] = F.reg(9, "+")
        XCTAssertEqual(SyncMerge.merge(a, b), SyncMerge.merge(b, a))
        let m = SyncMerge.merge(a, b)
        XCTAssertEqual(SyncMerge.merge(m, m), m)
        XCTAssertEqual(SyncMerge.merge(m, .empty), m)
    }

    func testTrimFavoritesKeepsNewestThenKeyBytes() {
        var d = SyncDocument()
        for i in 0..<305 { d.rooms[String(format: "cb:u%03d", i)] = room(f: F.reg(UInt64(1000 - i), "+")) }
        // 两个与第 300 名同刻的条目：按键字节升序决定去留。
        d.rooms["cb:tie_b"] = room(f: F.reg(1000 - 299, "+"))
        d.rooms["cb:tie_a"] = room(f: F.reg(1000 - 299, "+"))
        let t = SyncMerge.trim(d)
        let favs = t.rooms.filter { $0.value.isFavorite }
        XCTAssertEqual(favs.count, 300)
        XCTAssertNotNil(t.rooms["cb:tie_a"])
        XCTAssertNil(t.rooms["cb:tie_b"], "第 300 名只剩一个位置：同刻里 cb:tie_a < cb:tie_b < cb:u299")
        XCTAssertNil(t.rooms["cb:u299"])
        XCTAssertNil(t.rooms["cb:u300"])
        XCTAssertNotNil(t.rooms["cb:u298"])
    }

    func testTrimBlockedAndTombstonesSeparately() {
        var d = SyncDocument()
        for i in 0..<302 { d.rooms[String(format: "cb:b%03d", i)] = room(b: F.reg(UInt64(i + 1), "+")) }
        for i in 0..<1_003 {
            d.rooms[String(format: "cb:t%04d", i)] = room(f: F.reg(UInt64(i + 1), "-"), n: F.reg(UInt64(i + 2), "T\(String(format: "%04d", i))"))
        }
        let t = SyncMerge.trim(d)
        XCTAssertNil(t.rooms["cb:b000"])
        XCTAssertNil(t.rooms["cb:b001"])
        XCTAssertNotNil(t.rooms["cb:b002"])
        XCTAssertEqual(t.rooms.filter { $0.key.hasPrefix("cb:t") }.count, 1_000)
        XCTAssertNil(t.rooms["cb:t0002"], "墓碑按三个寄存器的最大 HLC 排序")
        XCTAssertNotNil(t.rooms["cb:t0003"])
        XCTAssertTrue(SyncMerge.withinCounts(t))
    }

    func testTrimVetoCaps() {
        var d = SyncDocument()
        for i in 0..<452 { d.veto[String(format: "t:on%03d", i)] = F.reg(UInt64(i + 1), "+") }
        for i in 0..<501 { d.veto[String(format: "t:off%03d", i)] = F.reg(7, "-") }
        let t = SyncMerge.trim(d)
        XCTAssertEqual(t.veto.values.filter(\.isOn).count, 450)
        XCTAssertNil(t.veto["t:on000"])
        XCTAssertNil(t.veto["t:on001"])
        XCTAssertEqual(t.veto.values.filter { !$0.isOn }.count, 500)
        XCTAssertNil(t.veto["t:off500"], "同 HLC 按键字节升序保留")
        XCTAssertNotNil(t.veto["t:off499"])
    }

    func testTrimNeverWrites() {
        var d = SyncDocument()
        for i in 0..<310 { d.rooms[String(format: "cb:u%03d", i)] = room(f: F.reg(UInt64(i + 1), "+"), n: F.reg(UInt64(i + 1), String(format: "U%03d", i))) }
        let t = SyncMerge.trim(d)
        for (key, value) in t.rooms { XCTAssertEqual(value, d.rooms[key]) }
    }

    // MARK: 体积

    func bigDoc(tombstones: Int, activeTagBytes: Int = 0) -> SyncDocument {
        var d = SyncDocument()
        for i in 0..<tombstones {
            d.veto["t:" + String(format: "%06d", i) + String(repeating: "x", count: 100)] = F.reg(UInt64(i + 1), "-")
        }
        if activeTagBytes > 0 {
            for i in 0..<450 { d.veto["t:on" + String(format: "%03d", i) + String(repeating: "y", count: activeTagBytes)] = F.reg(10_000, "+") }
        }
        return d
    }

    func testFitSizeWithinLimitIsUntouched() {
        let d = bigDoc(tombstones: 10)
        let fitted = SyncMerge.fitSize(d)
        XCTAssertTrue(fitted.fits)
        XCTAssertEqual(fitted.doc, d)
        XCTAssertEqual(fitted.bytes, d.encoded().count)
    }

    func testFitSizeDropsOldestTombstonesFirst() {
        var d = bigDoc(tombstones: 400)
        d.rooms["cb:old"] = room(f: F.reg(1, "-"))
        let full = d.encoded().count
        let limit = full - 300
        let fitted = SyncMerge.fitSize(d, limit: limit)
        XCTAssertTrue(fitted.fits)
        XCTAssertLessThanOrEqual(fitted.bytes, limit)
        XCTAssertEqual(fitted.bytes, fitted.doc.encoded().count)
        XCTAssertNil(fitted.doc.rooms["cb:old"], "同 HLC 时房间墓碑在前")
        XCTAssertNil(fitted.doc.veto["t:000000" + String(repeating: "x", count: 100)])
        XCTAssertNil(fitted.doc.veto["t:000001" + String(repeating: "x", count: 100)])
        XCTAssertNotNil(fitted.doc.veto["t:000399" + String(repeating: "x", count: 100)])
        // 删得刚好：再少删一个就超。
        let removed = d.veto.count - fitted.doc.veto.count
        XCTAssertEqual(removed, 2)
    }

    func testFitSizeStillOverWhenOnlyActiveEntries() {
        let d = bigDoc(tombstones: 5, activeTagBytes: 100)
        let fitted = SyncMerge.fitSize(d, limit: 10_000)
        XCTAssertFalse(fitted.fits)
        XCTAssertTrue(fitted.doc.veto.values.allSatisfy(\.isOn), "墓碑全部删完")
        XCTAssertEqual(fitted.doc.veto.count, 450)
    }
}
