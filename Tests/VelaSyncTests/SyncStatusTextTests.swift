import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 复审 low：落盘失败要如实报告，不能把「保存失败」说成「删除失败」。
final class SyncStatusTextTests: XCTestCase {
    func testFlowFailuresAreDescribedAccurately() {
        XCTAssertFalse(SyncStatusText.flow(.storageFailed).contains("删除"), SyncStatusText.flow(.storageFailed))
        XCTAssertTrue(SyncStatusText.flow(.localSaveFailed).contains("本机保存失败"))
        XCTAssertEqual(SyncStatusText.flow(.done), "")
        XCTAssertTrue(SyncStatusText.flow(.remotePending).contains("清除尚未完成"))
    }
}
