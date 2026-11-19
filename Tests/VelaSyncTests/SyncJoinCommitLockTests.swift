#if canImport(CryptoKit) && os(macOS)
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 加入提交的机制（第 12.4 节、第 23 节第 13 条，用户裁决）：提交锁、文件快照回滚、「已开启」最后写、启动时收尾。
final class SyncJoinCommitLockTests: XCTestCase {
    let localCuration = Curation(favorites: ["alice"], blocked: ["mallory"])

    final class Rig {
        let t = ScriptedTransport()
        let store = MemoryStateStore()
        let keys = MemoryKeyStore()
        let sink = MemorySink()
        let journal: MemoryJournal
        let service: SyncService
        init() {
            journal = MemoryJournal(sink)
            service = ServiceFixture.service(t, store: store, keys: keys, journal: journal)
        }
    }

    func rig() -> Rig {
        let r = Rig()
        r.sink.curation = localCuration
        r.sink.settings = ["publicOnly": true]
        r.t.gets = [ServiceFixture.blob(ServiceFixture.remoteDoc(favorites: ["zed"]), ver: 3)]
        r.t.puts = [.stored(ver: 4, epoch: ServiceFixture.epoch, now: 1_800_000_000_000)]
        return r
    }

    func ticket(_ r: Rig) async -> SyncJoinTicket? {
        guard case .preview(let t) = await r.service.prepareJoin(ServiceFixture.code, local: SyncLocalData(curation: localCuration)) else { return nil }
        return t
    }

    func assertNotJoined(_ r: Rig, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(r.store.load(), .none, file: file, line: line)
        XCTAssertNil(r.keys.entry, file: file, line: line)
        XCTAssertEqual(r.sink.curation, localCuration, file: file, line: line)
        XCTAssertEqual(r.sink.settings, ["publicOnly": true], file: file, line: line)
        XCTAssertFalse(r.journal.exists, file: file, line: line)
        XCTAssertFalse(r.service.isActive, file: file, line: line)
        XCTAssertFalse(r.service.isCommittingJoin, "锁在任何结局下都释放", file: file, line: line)
    }

    /// 确认之后到提交结束：本机写操作一律被拒；本机快照在上锁之后才取。
    func testLocalWritesAreRejectedWhileCommitting() async {
        let r = rig()
        guard let t = await ticket(r) else { return XCTFail() }
        XCTAssertTrue(r.service.localWriteAllowed)
        var lockedWhenSnapshotTaken = false
        var lockedDuringPut = false
        r.t.onPut = {
            lockedDuringPut = r.service.isCommittingJoin && !r.service.localWriteAllowed
        }
        let result = await r.service.commitJoin(t, local: {
            lockedWhenSnapshotTaken = r.service.isCommittingJoin
            return SyncLocalData(curation: self.localCuration)
        }, sink: r.sink)
        XCTAssertEqual(result, .joined)
        XCTAssertTrue(lockedWhenSnapshotTaken)
        XCTAssertTrue(lockedDuringPut)
        XCTAssertFalse(r.service.isCommittingJoin)
        XCTAssertFalse(r.journal.exists, "成功后删掉快照")
    }

    /// 写本机各步失败或在各步之间取消：用快照换回，删掉钥匙串与状态。
    func testEveryLocalStepFailureRollsBackFromSnapshot() async {
        for step in ["snapshot", "keychain", "barrier", "state", "cancel-after-keychain", "cancel-after-barrier"] {
            let r = rig()
            guard let t = await ticket(r) else { return XCTFail() }
            switch step {
            case "snapshot": r.journal.failSnapshot = true
            case "keychain": r.keys.failWrite = true
            case "barrier": r.sink.failOnSave = 2
            case "state": r.store.failFromSave = 1
            case "cancel-after-keychain": r.keys.onWrite = { t.cancel() }
            default: r.sink.onSave = { t.cancel() }
            }
            let result = await r.service.commitJoin(t, local: { SyncLocalData(curation: self.localCuration) }, sink: r.sink)
            if step.hasPrefix("cancel") { XCTAssertEqual(result, .cancelled, step) } else if case .failed = result {} else { XCTFail("\(step) \(result)") }
            assertNotJoined(r)
        }
    }

    /// 回滚自身失败：明确报告「需要修复」，快照留着，下次启动继续收尾。
    func testRollbackFailureNeedsRepair() async {
        let r = rig()
        guard let t = await ticket(r) else { return XCTFail() }
        r.sink.failOnSave = 2
        r.journal.failRestore = true
        guard case .needsRepair = await r.service.commitJoin(t, local: { SyncLocalData(curation: self.localCuration) }, sink: r.sink) else { return XCTFail() }
        XCTAssertTrue(r.journal.exists)
        XCTAssertFalse(r.service.isCommittingJoin)
        let again = await r.service.start(local: { SyncLocalData() }, sink: r.sink)
        guard case .needsRepair = again else { return XCTFail("启动时回滚仍失败要继续提示，实际 \(again)") }
        r.journal.failRestore = false
        let fixed = await r.service.start(local: { SyncLocalData() }, sink: r.sink)
        XCTAssertEqual(fixed, .notEnabled)
        assertNotJoined(r)
    }

    /// 启动时发现快照：没有「已开启」就换回快照、删掉钥匙串与状态；已开启说明提交已完成，只删快照。
    func testStartupFinishesInterruptedCommit() async {
        let r = rig()
        _ = r.journal.snapshot()
        r.sink.curation = Curation(favorites: ["alice", "zed"])
        r.keys.entry = SyncKeychainEntry(key: ServiceFixture.key, dev: 7, blobID: ServiceFixture.keys.blobID)
        let result = await r.service.start(local: { SyncLocalData() }, sink: r.sink)
        XCTAssertEqual(result, .notEnabled)
        assertNotJoined(r)

        let done = rig()
        _ = done.journal.snapshot()
        var s = SyncState(phase: .active, origin: ServiceFixture.origin, blobID: ServiceFixture.keys.blobID, clock: SyncClock(dev: 7))
        s.epoch = ServiceFixture.epoch
        _ = done.store.save(s)
        done.keys.entry = SyncKeychainEntry(key: ServiceFixture.key, dev: 7, blobID: ServiceFixture.keys.blobID)
        let active = await done.service.start(local: { SyncLocalData() }, sink: done.sink)
        XCTAssertEqual(active, .active)
        XCTAssertFalse(done.journal.exists)
        XCTAssertNotNil(done.keys.entry)
    }

    /// 状态文件坏掉：进入「状态损坏」，绝不删 K（复审 high）。
    func testCorruptedStateNeverDeletesKey() async {
        let r = rig()
        r.store.corrupted = true
        r.keys.entry = SyncKeychainEntry(key: ServiceFixture.key, dev: 7, blobID: ServiceFixture.keys.blobID)
        let result = await r.service.start(local: { SyncLocalData() }, sink: r.sink)
        XCTAssertEqual(result, .attention(.stateCorrupted))
        XCTAssertNotNil(r.keys.entry)
    }

    /// 上锁后重算发现计数变了（要回确认页），而重算期间票据已被取消（离页、进后台）：不得返回新票据，按取消处理。
    func testM11_cancelBeforeReconfirmReturnsCancelled() async {
        let r = rig()
        guard let t = await ticket(r) else { return XCTFail() }
        let result = await r.service.commitJoin(t, local: {
            t.cancel()
            return SyncLocalData(curation: Curation(favorites: ["alice", "bob"], blocked: ["mallory"]))
        }, sink: r.sink)
        XCTAssertEqual(result, .cancelled, "取消在重算窗口内发生，不能越过它重新装上确认页")
        XCTAssertFalse(r.t.log.contains { $0.hasPrefix("PUT") })
        assertNotJoined(r)
    }

    /// 409 后重算计数变了、而等 PUT 期间票据已被取消：同样按取消处理。
    func testM11_cancelDuringConflictedPutReturnsCancelled() async {
        let r = rig()
        guard let t = await ticket(r) else { return XCTFail() }
        r.t.puts = [ServiceFixture.blob(ServiceFixture.remoteDoc(favorites: ["zed", "alice"]), ver: 4, conflict: true)]
        r.t.onPut = { t.cancel() }
        let result = await r.service.commitJoin(t, local: { SyncLocalData(curation: self.localCuration) }, sink: r.sink)
        XCTAssertEqual(result, .cancelled)
        assertNotJoined(r)
    }

    /// 409 重试：响应版本与信封水位不得回退（与普通同步一致）。
    func testConflictMustNotRollBackWatermark() async {
        for (ver, label) in [(UInt64(2), "版本回退"), (UInt64(3), "同版本换了信封")] {
            let r = rig()
            guard let t = await ticket(r) else { return XCTFail() }
            r.t.puts = [ServiceFixture.blob(ServiceFixture.remoteDoc(favorites: ["zed"]), ver: ver, conflict: true)]
            guard case .failed = await r.service.commitJoin(t, local: { SyncLocalData(curation: self.localCuration) }, sink: r.sink) else {
                return XCTFail(label)
            }
            assertNotJoined(r)
        }
    }
}
#endif
