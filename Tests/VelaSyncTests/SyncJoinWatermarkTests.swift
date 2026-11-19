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

    /// 审计 high：恢复加入时 410、空间被占用、解不开不是「钥匙串暂不可用」，要给出能关闭的停止状态；只有网络故障才稍后再试。
    func testResumedJoinClassifiesRemoteFailures() async {
        let other = try! SyncCrypto.derive([UInt8](repeating: 8, count: 32))
        let cases: [(SyncServerResponse, SyncStartResult)] = [
            (.gone(deletedAt: 1_700_000_000_000), .stopped(SyncStatusText.halt(.gone(deletedAt: 1_700_000_000_000)))),
            (.blob(ver: 3, pub: other.publicKeyText, data: "e1.x.y", epoch: ServiceFixture.epoch, now: 0), .stopped(SyncStatusText.halt(.pubMismatch))),
            (.blob(ver: 3, pub: ServiceFixture.keys.publicKeyText, data: "e1.AAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAAAAAAAA", epoch: ServiceFixture.epoch, now: 0), .stopped("无法解密云端数据")),
            (.transient, .unavailable),
        ]
        for (response, expected) in cases {
            let (service, t) = resumable()
            t.gets = [response]
            let result = await service.start(local: { SyncLocalData() }, sink: MemorySink())
            XCTAssertEqual(result, expected, "\(response)")
        }
    }

    /// 审计 medium：恢复加入时落盘失败不得报「已开启」；保留 joining 状态，下次启动再恢复。
    func testResumedJoinSaveFailureIsReported() async {
        let store = MemoryStateStore(), keys = MemoryKeyStore()
        let (service, t) = resumable(store: store, keys: keys)
        t.gets = [ServiceFixture.blob(ServiceFixture.remoteDoc(favorites: ["zed"]), ver: 4)]
        store.failFromSave = store.saves + 1
        let result = await service.start(local: { SyncLocalData() }, sink: MemorySink())
        XCTAssertEqual(result, .failed(.storageFailed))
        XCTAssertFalse(service.isActive)
        guard case .present(let s) = store.load() else { return XCTFail() }
        XCTAssertEqual(s.phase, .joining)
    }

    func resumable(store: MemoryStateStore = MemoryStateStore(), keys: MemoryKeyStore = MemoryKeyStore()) -> (SyncService, ScriptedTransport) {
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
