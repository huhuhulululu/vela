#if canImport(CryptoKit) && os(macOS)
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 加入 = 一次性事务（docs/plans/sync.md 第 12.4 节，用户裁决）：确认前与提交途中取消或退出都等于「未加入」，
/// 本机收藏、口味、钥匙串、同步状态文件原样；提交 = 先写云端 PUT 成功，再写本机，本机写失败回滚为未加入。
final class SyncJoinTransactionTests: XCTestCase {
    let localCuration = Curation(favorites: ["alice"], blocked: ["mallory"])
    var local: SyncLocalData { SyncLocalData(curation: localCuration, vetoedKeys: ["t:asian"]) }

    struct Device {
        let t = ScriptedTransport()
        let store = MemoryStateStore()
        let keys = MemoryKeyStore()
        let sink = MemorySink()
        let service: SyncService
        let originalTaste: Taste
        let journal: MemoryJournal

        init(curation: Curation) {
            journal = MemoryJournal(sink)
            service = ServiceFixture.service(t, store: store, keys: keys, journal: journal)
            sink.curation = curation
            sink.taste = Taste().vetoing("t:asian", now: Date(timeIntervalSince1970: 1_000))
            originalTaste = sink.taste
        }
    }

    func device() -> Device {
        let d = Device(curation: localCuration)
        d.t.gets = [ServiceFixture.blob(ServiceFixture.remoteDoc(favorites: ["zed"]), ver: 3)]
        return d
    }

    func assertUntouched(_ d: Device, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(d.store.load(), .none, "不留同步状态", file: file, line: line)
        XCTAssertNil(d.keys.entry, "不留钥匙串条目", file: file, line: line)
        XCTAssertEqual(d.sink.curation, localCuration, "本机收藏原样", file: file, line: line)
        XCTAssertEqual(d.sink.taste, d.originalTaste, "本机口味原样", file: file, line: line)
        XCTAssertFalse(d.service.isActive, file: file, line: line)
        XCTAssertFalse(d.journal.exists, "快照已换回或删除", file: file, line: line)
    }

    func testPreviewPersistsNothing() async {
        let d = device()
        guard case .preview(let ticket) = await d.service.prepareJoin(ServiceFixture.code, local: local) else { return XCTFail() }
        XCTAssertEqual(ticket.preview.n, 1)
        XCTAssertEqual(d.store.saves, 0, "确认前不写同步状态")
        XCTAssertEqual(d.keys.writes, 0, "确认前不写钥匙串")
        XCTAssertEqual(d.sink.saveCount, 0)
        assertUntouched(d)
    }

    func testCancelAfterPreviewLeavesEverythingAsIs() async {
        let d = device()
        guard case .preview(let ticket) = await d.service.prepareJoin(ServiceFixture.code, local: local) else { return XCTFail() }
        ticket.cancel()
        let r = await d.service.commitJoin(ticket, local: { self.local }, sink: d.sink)
        XCTAssertEqual(r, .cancelled)
        XCTAssertFalse(d.t.log.contains { $0.hasPrefix("PUT") }, "已取消的加入不写云端")
        assertUntouched(d)
    }

    func testCancelDuringCommitLeavesLocalAsIs() async {
        let d = device()
        guard case .preview(let ticket) = await d.service.prepareJoin(ServiceFixture.code, local: local) else { return XCTFail() }
        d.t.puts = [.stored(ver: 4, epoch: ServiceFixture.epoch, now: 1_800_000_000_000)]
        d.t.onPut = { ticket.cancel() }
        let r = await d.service.commitJoin(ticket, local: { self.local }, sink: d.sink)
        XCTAssertEqual(r, .cancelled)
        assertUntouched(d)
    }

    func testRemoteWriteFailureWritesNothingLocally() async {
        for response in [SyncServerResponse.transient, .forbidden, .gone(deletedAt: 1)] {
            let d = device()
            guard case .preview(let ticket) = await d.service.prepareJoin(ServiceFixture.code, local: local) else { return XCTFail() }
            d.t.puts = [response]
            guard case .failed = await d.service.commitJoin(ticket, local: { self.local }, sink: d.sink) else { return XCTFail("\(response)") }
            assertUntouched(d)
        }
    }

    func testLocalWriteFailureRollsBack() async {
        for failure in ["keychain", "state", "barrier"] {
            let d = device()
            guard case .preview(let ticket) = await d.service.prepareJoin(ServiceFixture.code, local: local) else { return XCTFail() }
            d.t.puts = [.stored(ver: 4, epoch: ServiceFixture.epoch, now: 1_800_000_000_000)]
            switch failure {
            case "keychain": d.keys.failWrite = true
            case "state": d.store.failFromSave = 1
            default: d.sink.failOnSave = 2  // 收藏写成功、口味写失败：收藏要被恢复
            }
            guard case .failed = await d.service.commitJoin(ticket, local: { self.local }, sink: d.sink) else { return XCTFail(failure) }
            assertUntouched(d)
        }
    }

    func testSuccessfulCommitPersistsWatermarkAndProjection() async {
        let d = device()
        guard case .preview(let ticket) = await d.service.prepareJoin(ServiceFixture.code, local: local) else { return XCTFail() }
        d.t.puts = [.stored(ver: 4, epoch: ServiceFixture.epoch, now: 1_800_000_000_000)]
        guard case .joined = await d.service.commitJoin(ticket, local: { self.local }, sink: d.sink) else { return XCTFail() }
        XCTAssertTrue(d.service.isActive)
        guard case .present(let s) = d.store.load() else { return XCTFail() }
        XCTAssertEqual(s.phase, .active)
        XCTAssertEqual(s.epoch, ServiceFixture.epoch)
        XCTAssertEqual(s.lastSeenVer, 4, "水位是本次写入的版本")
        XCTAssertNotNil(s.lastSeenHash)
        XCTAssertEqual(s.gen, s.pushedGen, "提交即已推送")
        XCTAssertNotNil(d.keys.entry)
        XCTAssertEqual(Set(d.sink.curation.favorites), ["alice", "zed"])
        XCTAssertEqual(d.sink.curation.blocked, ["mallory"])
    }

    /// 提交时 409：重新合并；计数不变就重试，计数变了就回到确认页（什么都没写）。
    func testConflictDuringCommit() async {
        let d = device()
        guard case .preview(let ticket) = await d.service.prepareJoin(ServiceFixture.code, local: local) else { return XCTFail() }
        d.t.puts = [ServiceFixture.blob(ServiceFixture.remoteDoc(favorites: ["zed", "yan"]), ver: 4, conflict: true),
                    .stored(ver: 5, epoch: ServiceFixture.epoch, now: 1_800_000_000_000)]
        guard case .joined = await d.service.commitJoin(ticket, local: { self.local }, sink: d.sink) else { return XCTFail() }
        XCTAssertEqual(Set(d.sink.curation.favorites), ["alice", "zed", "yan"])

        let e = device()
        guard case .preview(let t2) = await e.service.prepareJoin(ServiceFixture.code, local: local) else { return XCTFail() }
        e.t.puts = [ServiceFixture.blob(ServiceFixture.remoteDoc(favorites: ["zed", "alice"]), ver: 4, conflict: true)]
        guard case .reconfirm(let again) = await e.service.commitJoin(t2, local: { self.local }, sink: e.sink) else { return XCTFail() }
        XCTAssertEqual(again.preview.n, 0, "远端已有 alice，不再上传")
        assertUntouched(e)
    }

    /// 只剩钥匙串条目没有状态文件（提交写到一半退出）：启动时删掉条目，视为未加入。
    func testOrphanKeychainEntryIsRemovedAtStartup() async {
        let d = Device(curation: localCuration)
        d.keys.entry = SyncKeychainEntry(key: ServiceFixture.key, dev: 7, blobID: ServiceFixture.keys.blobID)
        let r = await d.service.start(local: { self.local }, sink: d.sink)
        XCTAssertEqual(r, .notEnabled)
        assertUntouched(d)
    }
}
#endif
