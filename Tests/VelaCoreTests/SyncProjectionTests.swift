import XCTest
@testable import VelaCore

/// 第 6.5 节：投影顺序、平局屏蔽胜、未知站点不投影、口味与设置。
final class SyncProjectionTests: XCTestCase {
    typealias F = SyncFixture

    func testOrderNewestFirstThenKeyBytes() {
        var d = SyncDocument()
        d.rooms["cb:bbb"] = SyncRoom(f: F.reg(5, "+"))
        d.rooms["cb:aaa"] = SyncRoom(f: F.reg(5, "+"))
        d.rooms["cb:new"] = SyncRoom(f: F.reg(9, "+"), n: F.reg(9, "NeW"))
        d.rooms["cb:old"] = SyncRoom(f: F.reg(1, "+"))
        let p = SyncProjection(d)
        XCTAssertEqual(p.favorites.map(\.key), ["cb:new", "cb:aaa", "cb:bbb", "cb:old"])
        XCTAssertEqual(p.curation.favorites, ["NeW", "aaa", "bbb", "old"])
    }

    func testBothOnLargerHLCWinsTieBlocks() {
        var d = SyncDocument()
        d.rooms["cb:fav"] = SyncRoom(f: F.reg(6, "+"), b: F.reg(5, "+"))
        d.rooms["cb:blk"] = SyncRoom(f: F.reg(5, "+"), b: F.reg(6, "+"))
        d.rooms["cb:tie"] = SyncRoom(f: F.reg(5, "+"), b: F.reg(5, "+"))
        d.rooms["cb:gone"] = SyncRoom(f: F.reg(7, "-"), b: F.reg(7, "-"), n: F.reg(7, "Gone"))
        let c = SyncProjection(d).curation
        XCTAssertEqual(c.favorites, ["fav"])
        XCTAssertEqual(Set(c.blocked), ["blk", "tie"])
        XCTAssertFalse(c.isFavorite("gone") || c.isBlocked("gone"))
    }

    func testUnknownSiteKeptButNotProjected() {
        var d = SyncDocument()
        d.rooms["c4:alice"] = SyncRoom(f: F.reg(5, "+"))
        d.rooms["cb:alice"] = SyncRoom(b: F.reg(4, "+"))
        let p = SyncProjection(d)
        XCTAssertEqual(p.favorites.map(\.key), ["c4:alice"])
        XCTAssertEqual(p.curation.favorites, [])
        XCTAssertEqual(p.curation.blocked, ["alice"])
        XCTAssertNotNil(SyncMerge.merge(d, .empty).rooms["c4:alice"], "未知前缀照常参与合并")
    }

    func testTasteProjectionKeepsEvidenceAndLocalOnly() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        var taste = Taste().recording(room: Room(username: "a", tags: ["asian", "milf"]), watched: 200, source: .guess, now: now)
        taste = taste.vetoing("t:milf", now: now).vetoing(Taste.tagKey("big tits"), now: now)
        var d = SyncDocument()
        d.veto["t:asian"] = F.reg(3, "+")
        d.veto["g:m"] = F.reg(3, "-")
        let projected = SyncProjection(d).applying(to: taste)
        XCTAssertTrue(projected.isVetoed("t:asian"))
        XCTAssertEqual(projected.beliefs["t:asian"]?.hit, taste.beliefs["t:asian"]?.hit)
        XCTAssertFalse(projected.isVetoed("t:milf"), "同步域内、文档里不在的撤销")
        XCTAssertNotNil(projected.beliefs["t:milf"], "证据仍在，条目保留")
        XCTAssertTrue(projected.isVetoed(Taste.tagKey("big tits")), "本机专用不动")
        XCTAssertNil(projected.beliefs["g:m"])
        XCTAssertEqual(SyncProjection(d).applying(to: projected), projected, "重复投影不变")
    }

    func testSettingsProjection() {
        var d = SyncDocument()
        d.settings["publicOnly"] = F.reg(1, "0")
        XCTAssertEqual(SyncProjection(d).settings, ["publicOnly": false])
    }
}
