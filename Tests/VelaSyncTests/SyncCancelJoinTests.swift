#if canImport(CryptoKit) && os(macOS)
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 审计 high：取消加入不得用 joining 状态里的空文档跑投影屏障，把本机收藏与偏好覆盖掉（第 12.2、12.4 节）。
final class SyncCancelJoinTests: XCTestCase {
    func testCancellingFirstJoinLeavesLocalFilesUntouched() async {
        let t = ScriptedTransport()
        t.gets = [ServiceFixture.blob(ServiceFixture.remoteDoc(favorites: ["zed"]), ver: 3)]
        let store = MemoryStateStore(), keys = MemoryKeyStore()
        let service = ServiceFixture.service(t, store: store, keys: keys)
        let sink = MemorySink()
        sink.curation = Curation(favorites: ["alice"], blocked: ["bob"])
        guard case .preview = await service.prepareJoin(ServiceFixture.code, local: SyncLocalData(curation: sink.curation)) else { return XCTFail() }
        XCTAssertEqual(service.cancelJoin(sink: sink), .done)
        XCTAssertEqual(sink.saveCount, 0, "取消加入不跑屏障")
        XCTAssertEqual(sink.curation, Curation(favorites: ["alice"], blocked: ["bob"]))
        XCTAssertEqual(store.load(), .none)
        XCTAssertNil(keys.entry)
    }

    /// 确认后崩溃、启动时需要重新确认再取消：同样只清加入状态与钥匙串条目，本机文件原样。
    func testCancellingResumedJoinLeavesLocalFilesUntouched() async {
        let store = MemoryStateStore(), keys = MemoryKeyStore()
        var state = SyncState(phase: .joining, origin: ServiceFixture.origin, blobID: ServiceFixture.keys.blobID, clock: SyncClock(dev: 7))
        state.joinPlan = SyncJoinPlan(origin: ServiceFixture.origin, blobID: state.blobID, epoch: ServiceFixture.epoch, ver: 3,
                                      envelopeHash: "x", preview: SyncJoinPreview(n: 9))
        _ = store.save(state)
        keys.entry = SyncKeychainEntry(key: ServiceFixture.key, dev: 7, blobID: state.blobID)
        let service = ServiceFixture.service(ScriptedTransport(), store: store, keys: keys)
        let sink = MemorySink()
        sink.taste = Taste().vetoing("t:asian", now: Date())
        let before = sink.taste
        XCTAssertEqual(service.cancelJoin(sink: sink), .done)
        XCTAssertEqual(sink.saveCount, 0)
        XCTAssertEqual(sink.taste, before)
        XCTAssertEqual(store.load(), .none)
        XCTAssertNil(keys.entry)
    }

    /// 「关闭同步」落在 joining 状态上（启动提示里的关闭）也走同一条路径。
    func testDisableOnJoiningStateDoesNotRunBarrier() {
        let store = MemoryStateStore()
        _ = store.save(SyncState(phase: .joining, origin: ServiceFixture.origin, blobID: ServiceFixture.keys.blobID, clock: SyncClock(dev: 7)))
        let service = ServiceFixture.service(ScriptedTransport(), store: store)
        let sink = MemorySink()
        sink.curation = Curation(favorites: ["alice"])
        XCTAssertEqual(service.disable(sink: sink), .done)
        XCTAssertEqual(sink.saveCount, 0)
        XCTAssertEqual(sink.curation.favorites, ["alice"])
    }
}
#endif
