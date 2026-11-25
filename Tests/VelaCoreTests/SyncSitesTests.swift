import XCTest
@testable import VelaCore

/// 同步带上 Cam4（docs/plans/sync.md 第 6.3 节、第 23 节第 15 条，用户裁决方向 1）：合法站点 {cb, c4}，
/// 房间键经 `RoomKey` 归一化，投影按站点写回带前缀的 ref，不认识的站点只留在文档里。
final class SyncSitesTests: XCTestCase {
    let dev: UInt32 = 0xa1b2c3d4

    private func replica(_ doc: SyncDocument = .empty) -> SyncReplica { SyncReplica(doc: doc, clock: SyncClock(dev: dev)) }

    private func applied(_ op: SyncLocalOp, _ r: SyncReplica, pt: UInt64, file: StaticString = #filePath, line: UInt = #line) -> SyncReplica {
        guard case .applied(let next) = SyncOps.apply(op, to: r, pt: pt) else {
            XCTFail("expected applied: \(op)", file: file, line: line)
            return r
        }
        return next
    }

    func testRoomKeyUsesSiteNormalization() {
        XCTAssertEqual(SyncKeys.roomKey(localName: " Alice "), "cb:alice", "裸名按 cb")
        XCTAssertEqual(SyncKeys.roomKey(localName: "cb:Alice"), "cb:alice")
        XCTAssertEqual(SyncKeys.roomKey(localName: "c4:Sample_One"), "c4:sample_one")
        XCTAssertEqual(SyncKeys.roomKey(localName: "C4:sample_one"), "c4:sample_one", "站点段大小写不敏感")
        XCTAssertNil(SyncKeys.roomKey(localName: "c4:x"), "名字不合法")
        XCTAssertNil(SyncKeys.roomKey(localName: "xx:zoe"), "本端不认识的站点不进同步")
        XCTAssertTrue(SyncKeys.isProjected("c4:sample_one"))
        XCTAssertTrue(SyncKeys.isProjected("cb:alice"))
        XCTAssertFalse(SyncKeys.isProjected("xx:zoe"))
    }

    func testCam4FavoritesAndBlocksRoundTripWithoutLoss() {
        var r = replica()
        r = applied(.favorite("c4:sample_one"), r, pt: 1_000)
        r = applied(.favorite("Sample_One"), r, pt: 1_001)
        r = applied(.block("c4:sample_two"), r, pt: 1_002)
        XCTAssertEqual(Set(r.doc.rooms.keys), ["c4:sample_one", "cb:sample_one", "c4:sample_two"], "同名的 cb 与 c4 是两个键")
        XCTAssertNil(r.doc.rooms["c4:sample_one"]?.n, "c4 原文去掉前缀后与键相同，不写 n")

        guard case .valid(let decoded) = SyncDocument.decode(r.doc.encoded()) else { return XCTFail("严格 JSON 往返失败") }
        XCTAssertEqual(decoded, r.doc)
        let curation = SyncProjection(decoded).curation
        XCTAssertEqual(curation.favorites, ["Sample_One", "c4:sample_one"])
        XCTAssertEqual(curation.blocked, ["c4:sample_two"])
        XCTAssertTrue(curation.isFavorite("c4:sample_one"))
        XCTAssertTrue(curation.isFavorite("sample_one"))
        XCTAssertFalse(curation.isBlocked("sample_two"), "cb 同名房间没被屏蔽")

        let unfav = applied(.unfavorite("c4:sample_one"), r, pt: 1_003)
        let after = SyncProjection(unfav.doc).curation
        XCTAssertEqual(after.favorites, ["Sample_One"], "取消 c4 收藏不动 cb 同名收藏")
    }

    func testProjectionKeepsCam4AndSkipsUnknownSites() {
        var r = replica()
        r = applied(.favorite("c4:sample_one"), r, pt: 1_000)
        r.doc.rooms["xx:zoe"] = SyncRoom(f: SyncRegister(hlc: HLC(l: 2_000, c: 0, dev: dev), payload: "+"))
        let projection = SyncProjection(r.doc)
        XCTAssertEqual(projection.curation.favorites, ["c4:sample_one"], "c4 投影回本机，xx 只留在文档里")
        XCTAssertEqual(projection.favorites.map(\.key), ["xx:zoe", "c4:sample_one"])
    }

    func testLegacyBareLocalNamesMigrateAsChaturbate() {
        let local = SyncLocalData(curation: Curation(favorites: ["Alice", "c4:sample_one"], blocked: ["bob"]))
        let (r, report, fits) = SyncMigration.stampCreator(local, clock: SyncClock(dev: dev), pt: 1_000)
        XCTAssertTrue(fits)
        XCTAssertTrue(report.invalidNames.isEmpty)
        XCTAssertEqual(Set(r.doc.rooms.keys), ["cb:alice", "c4:sample_one", "cb:bob"])
        let back = SyncProjection(r.doc).curation
        XCTAssertEqual(back.favorites, ["Alice", "c4:sample_one"])
        XCTAssertEqual(back.blocked, ["bob"])
    }

    /// 同步开着时启动迁移只改磁盘格式：迁移前后读出的本机数据相同，盖出的同步文档逐字节相同，
    /// 所以不会被当成本机改动上传（第 6.4 节：只有用户操作产生寄存器写入）。
    func testSiteKeyMigrationDoesNotChangeWhatSyncSees() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sync-sites-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let curation = dir.appendingPathComponent("feed-curation.json")
        try Data(#"{"favorites":["Alice","bob"],"blocked":["spam_1"]}"#.utf8).write(to: curation)
        let before = CurationStore(url: curation).load()
        let report = SiteKeyMigration.run(
            curation: curation, taste: dir.appendingPathComponent("feed-taste.json"),
            ledger: dir.appendingPathComponent("watch-ledger.json")
        )
        XCTAssertEqual(report.migrated, [curation])
        let after = CurationStore(url: curation).load()
        XCTAssertEqual(after, before)
        let clock = SyncClock(dev: dev)
        let a = SyncMigration.stampCreator(SyncLocalData(curation: before), clock: clock, pt: 1_000).replica.doc
        let b = SyncMigration.stampCreator(SyncLocalData(curation: after), clock: clock, pt: 1_000).replica.doc
        XCTAssertEqual(a.encoded(), b.encoded())
    }
}
