#if canImport(CryptoKit) && os(macOS)
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 审计 medium：加入与中断恢复的创建也要落实本机专用「不想看」50 个的额度（第 6.6 节），由界面层取走并取消多出的。
final class SyncLocalOnlyQuotaTests: XCTestCase {
    let local = SyncLocalData(vetoedKeys: (0..<60).map { "t:😀\($0)" })

    func testJoinReportsLocalOnlyVetoesBeyondFifty() async {
        let t = ScriptedTransport()
        t.gets = [ServiceFixture.blob(ServiceFixture.remoteDoc(favorites: ["zed"]), ver: 3)]
        let service = ServiceFixture.service(t)
        guard case .preview(let ticket) = await service.prepareJoin(ServiceFixture.code, local: local) else { return XCTFail() }
        XCTAssertEqual(ticket.report.droppedLocalOnlyVetoes.count, 10, "确认页要能写出将被取消的数量")
        _ = await service.confirmJoin(ticket, local: local)
        XCTAssertEqual(service.takeLocalOnlyDrops().count, 10)
        XCTAssertEqual(service.takeLocalOnlyDrops(), [], "只交一次")
    }

    func testResumedCreateReportsLocalOnlyVetoesBeyondFifty() async {
        let store = MemoryStateStore(), keys = MemoryKeyStore()
        _ = store.save(SyncState(phase: .creating, origin: ServiceFixture.origin, blobID: ServiceFixture.keys.blobID, clock: SyncClock(dev: 7)))
        keys.entry = SyncKeychainEntry(key: ServiceFixture.key, dev: 7, blobID: ServiceFixture.keys.blobID)
        let service = ServiceFixture.service(ScriptedTransport(), store: store, keys: keys)
        let result = await service.start(local: { self.local }, sink: MemorySink())
        XCTAssertEqual(result, .active)
        XCTAssertEqual(service.takeLocalOnlyDrops().count, 10)
    }
}
#endif
