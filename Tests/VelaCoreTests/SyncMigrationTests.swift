import XCTest
@testable import VelaCore

/// 第 12.4、14.1 节：迁移盖章与加入预览计数。
final class SyncMigrationTests: XCTestCase {
    typealias F = SyncFixture
    let dev: UInt32 = 7
    var clock: SyncClock { SyncClock(dev: dev) }

    func testCanEnableOnlyAfterCleanLoads() {
        XCTAssertTrue(SyncMigration.canEnable(curation: .loaded, taste: .missing))
        XCTAssertFalse(SyncMigration.canEnable(curation: .quarantined, taste: .loaded))
        XCTAssertFalse(SyncMigration.canEnable(curation: .loaded, taste: .readOnly))
        XCTAssertFalse(SyncMigration.canEnable(curation: nil, taste: .loaded))
    }

    func testCreatorStampsInOrderAndPreservesProjection() {
        let curation = Curation(favorites: ["Newest", "middle", "oldest", "x"], blocked: ["Spam1", "spam2"])
        let local = SyncLocalData(curation: curation, vetoedKeys: ["t:zeta", "g:m", "t:alpha", Taste.tagKey("big tits")], explicitSettings: ["publicOnly": false, "volume": true])
        let (r, report, fits) = SyncMigration.stampCreator(local, clock: clock, pt: 1_000)
        XCTAssertTrue(fits)
        XCTAssertEqual(report.invalidNames, ["x"])
        XCTAssertEqual(report.localOnlyVetoes, [Taste.tagKey("big tits")])
        let p = SyncProjection(r.doc)
        XCTAssertEqual(p.curation.favorites, ["Newest", "middle", "oldest"])
        XCTAssertEqual(p.curation.blocked, ["Spam1", "spam2"])
        // 收藏从末尾往前：oldest c=0、middle c=1、Newest c=2；屏蔽接着；veto 按字节升序；设置最后。
        XCTAssertEqual(r.doc.rooms["cb:oldest"]?.f?.hlc, HLC(l: 1000, c: 0, dev: dev))
        XCTAssertEqual(r.doc.rooms["cb:newest"]?.f?.hlc, HLC(l: 1000, c: 2, dev: dev))
        XCTAssertEqual(r.doc.rooms["cb:spam2"]?.b?.hlc, HLC(l: 1000, c: 3, dev: dev))
        XCTAssertEqual(r.doc.rooms["cb:spam1"]?.b?.hlc, HLC(l: 1000, c: 4, dev: dev))
        XCTAssertEqual(r.doc.veto["g:m"]?.hlc, HLC(l: 1000, c: 5, dev: dev))
        XCTAssertEqual(r.doc.veto["t:alpha"]?.hlc, HLC(l: 1000, c: 6, dev: dev))
        XCTAssertEqual(r.doc.veto["t:zeta"]?.hlc, HLC(l: 1000, c: 7, dev: dev))
        XCTAssertEqual(r.doc.settings["publicOnly"], SyncRegister(hlc: HLC(l: 1000, c: 8, dev: dev), payload: "0"))
        XCTAssertNil(r.doc.settings["volume"])
        XCTAssertNil(r.doc.rooms["cb:middle"]?.b, "迁移不写成对的 -")
        XCTAssertEqual(r.doc.rooms["cb:newest"]?.n?.payload, "Newest")
        XCTAssertNil(r.doc.rooms["cb:middle"]?.n)
        XCTAssertEqual(r.clock.current, HLC(l: 1000, c: 8, dev: dev))
    }

    func testCreatorVetoQuotas() {
        let synced = (0..<455).map { String(format: "t:k%03d", $0) }
        let localOnly = (0..<52).map { Taste.tagKey(String(format: "x %02d", $0)) }
        let (r, report, _) = SyncMigration.stampCreator(SyncLocalData(vetoedKeys: synced + localOnly), clock: clock, pt: 1)
        XCTAssertEqual(r.doc.veto.count, 450)
        XCTAssertEqual(report.droppedSyncVetoes, (450..<455).map { String(format: "t:k%03d", $0) })
        XCTAssertEqual(report.localOnlyVetoes.count, 50)
        XCTAssertEqual(report.droppedLocalOnlyVetoes, [Taste.tagKey("x 50"), Taste.tagKey("x 51")])
    }

    func remote() -> SyncDocument {
        var d = SyncDocument()
        d.rooms["cb:shared"] = SyncRoom(f: F.reg(5_000, "+", dev: 9))
        d.rooms["cb:theyblock"] = SyncRoom(b: F.reg(5_000, "+", dev: 9))
        d.rooms["cb:tomb"] = SyncRoom(f: F.reg(6_000, "-", dev: 9))
        d.veto["t:on"] = F.reg(5_000, "+", dev: 9)
        d.veto["t:off"] = F.reg(5_000, "-", dev: 9)
        d.settings["publicOnly"] = F.reg(5_000, "1", dev: 9)
        return d
    }

    func testJoinStampRulesAndCounts() {
        let local = SyncLocalData(
            curation: Curation(favorites: ["shared", "theyblock", "tomb", "mine"], blocked: ["blk"]),
            vetoedKeys: ["t:on", "t:off", "t:new"],
            explicitSettings: ["publicOnly": false, "infoAlwaysOn": true]
        )
        let (r, preview, _, fits) = SyncMigration.stampJoin(local, remote: remote(), clock: clock, pt: 1_000)
        XCTAssertTrue(fits)
        XCTAssertEqual(preview, SyncJoinPreview(n: 2, m: 1, v: 2, x: 0, y: 0, z: 1))
        let p = SyncProjection(r.doc)
        XCTAssertEqual(Set(p.curation.favorites), ["shared", "tomb", "mine"], "墓碑被本机收藏盖过")
        XCTAssertEqual(Set(p.curation.blocked), ["theyblock", "blk"], "云端屏蔽优先")
        XCTAssertEqual(r.doc.rooms["cb:shared"]?.f?.hlc.dev, 9, "远端已是收藏不盖章，保留远端时间")
        XCTAssertEqual(p.veto, ["t:on": true, "t:off": true, "t:new": true])
        XCTAssertEqual(p.settings, ["publicOnly": true, "infoAlwaysOn": true], "远端有值以远端为准")
        XCTAssertGreaterThan(r.doc.rooms["cb:mine"]!.f!.hlc, HLC(l: 6_000, c: 0, dev: 9), "盖章排在远端之后")
    }

    func testJoinCountsOverflow() {
        var far = SyncDocument()
        for i in 0..<300 { far.rooms[String(format: "cb:r%03d", i)] = SyncRoom(f: F.reg(UInt64(100 + i), "+", dev: 9)) }
        let local = SyncLocalData(curation: Curation(favorites: (0..<5).map { String(format: "l%03d", $0) }))
        let (r, preview, _, _) = SyncMigration.stampJoin(local, remote: far, clock: clock, pt: 1)
        XCTAssertEqual(preview.n, 5)
        XCTAssertEqual(preview.x, 5)
        XCTAssertEqual(SyncProjection(r.doc).favorites.count, 300)
        XCTAssertNil(r.doc.rooms["cb:r000"])
    }
}
