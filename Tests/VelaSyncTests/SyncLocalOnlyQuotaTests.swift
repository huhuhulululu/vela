#if canImport(CryptoKit) && os(macOS)
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 本机专用「不想看」50 个额度（第 6.6 节）：每次同步开启（创建、加入、启动时已开启）都按当前本机数据重算超额键，
/// 不靠进程内一次性记账，所以激活后中断、重启也会落实（复审 medium）。
final class SyncLocalOnlyQuotaTests: XCTestCase {
    let keys = (0..<60).map { "t:😀\($0)" }

    func testExcessIsRecomputedFromCurrentVetoes() {
        let excess = SyncQuota.localOnlyExcess(vetoedKeys: Set(keys + ["t:asian", "g:m"]))
        XCTAssertEqual(excess.count, 10, "同步域内的键不计入本机专用额度")
        XCTAssertFalse(excess.contains("t:asian"))
        let kept = Set(keys).subtracting(excess)
        XCTAssertEqual(kept.count, 50)
        // 按 UTF-8 字节升序保留前 50 个：被取消的都排在保留的之后。
        XCTAssertTrue(excess.allSatisfy { e in kept.allSatisfy { Array($0.utf8).lexicographicallyPrecedes(Array(e.utf8)) } })
        XCTAssertEqual(SyncQuota.localOnlyExcess(vetoedKeys: Set(keys).subtracting(excess)), [], "落实之后再算为空（幂等）")
    }

    func testJoinTicketListsExcessForConfirmPage() async {
        let t = ScriptedTransport()
        t.gets = [ServiceFixture.blob(ServiceFixture.remoteDoc(favorites: ["zed"]), ver: 3)]
        let service = ServiceFixture.service(t)
        guard case .preview(let ticket) = await service.prepareJoin(ServiceFixture.code, local: SyncLocalData(vetoedKeys: keys)) else { return XCTFail() }
        XCTAssertEqual(ticket.report.droppedLocalOnlyVetoes.count, 10)
    }
}
#endif
