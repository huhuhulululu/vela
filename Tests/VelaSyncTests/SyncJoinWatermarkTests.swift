#if canImport(CryptoKit) && os(macOS)
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 审计 high：加入后云端空间不见了（404）不得自动重新创建；恢复加入时 epoch 变了或版本回退要停下。
final class SyncJoinWatermarkTests: XCTestCase {
    func testAfterJoinA404HaltsInsteadOfRecreating() async {
        let t = ScriptedTransport()
        t.gets = [ServiceFixture.blob(ServiceFixture.remoteDoc(favorites: ["zed"]), ver: 3), .notFound(epoch: ServiceFixture.epoch, now: 1_800_000_000_000)]
        let service = ServiceFixture.service(t)
        guard case .preview(let ticket) = await service.prepareJoin(ServiceFixture.code, local: SyncLocalData()) else { return XCTFail() }
        let result = await service.confirmJoin(ticket, local: SyncLocalData())
        XCTAssertEqual(result, .halted(.missing))
        XCTAssertFalse(t.log.contains("PUT 0"), "不得以 base=0 重新创建")
    }

    func testResumedJoinStopsWhenEpochChanged() async {
        let (service, t) = resumable()
        t.gets = [ServiceFixture.blob(ServiceFixture.remoteDoc(favorites: ["zed"]), ver: 3, epoch: "ffffffffffffffffffffffffffffffff")]
        let result = await service.start(local: { SyncLocalData() }, sink: MemorySink())
        XCTAssertEqual(result, .stopped(SyncStatusText.halt(.epochChanged)))
        XCTAssertFalse(service.isActive)
    }

    func testResumedJoinStopsOnRollback() async {
        let (service, t) = resumable()
        t.gets = [ServiceFixture.blob(ServiceFixture.remoteDoc(favorites: ["zed"]), ver: 2)]
        let result = await service.start(local: { SyncLocalData() }, sink: MemorySink())
        XCTAssertEqual(result, .stopped(SyncStatusText.halt(.rollback)))
    }

    func resumable() -> (SyncService, ScriptedTransport) {
        let store = MemoryStateStore(), keys = MemoryKeyStore()
        var state = SyncState(phase: .joining, origin: ServiceFixture.origin, blobID: ServiceFixture.keys.blobID, clock: SyncClock(dev: 7))
        state.joinPlan = SyncJoinPlan(origin: ServiceFixture.origin, blobID: state.blobID, epoch: ServiceFixture.epoch, ver: 3,
                                      envelopeHash: "x", preview: SyncJoinPreview())
        _ = store.save(state)
        keys.entry = SyncKeychainEntry(key: ServiceFixture.key, dev: 7, blobID: state.blobID)
        let t = ScriptedTransport()
        return (ServiceFixture.service(t, store: store, keys: keys), t)
    }
}
#endif
