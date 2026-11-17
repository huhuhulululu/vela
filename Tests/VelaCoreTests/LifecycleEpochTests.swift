import XCTest
@testable import VelaCore

final class LifecycleEpochTests: XCTestCase {
    /// §3.1：挂起后请求、提交、中继注册、中继启动四处都拒绝；恢复后旧代次仍作废。
    func testSuspendInvalidatesRequestCommitRelayRegisterAndStart() {
        var life = LifecycleEpoch()
        let token = life.begin()
        XCTAssertEqual(token?.epoch, 0)
        XCTAssertFalse(life.suspended)
        XCTAssertTrue(life.allowStart)
        for gate in LifecycleEpoch.Gate.allCases {
            XCTAssertTrue(life.allows(gate, token: token!), "\(gate)")
        }

        life.suspend()
        XCTAssertTrue(life.suspended)
        XCTAssertEqual(life.epoch, 1)
        XCTAssertFalse(life.allowStart)
        XCTAssertNil(life.begin())
        for gate in LifecycleEpoch.Gate.allCases {
            XCTAssertFalse(life.allows(gate, token: token!), "\(gate)")
        }
        XCTAssertFalse(life.allows(.beforeCommit, token: token!), "代次不一致就丢弃结果")

        life.resume()
        XCTAssertFalse(life.suspended)
        XCTAssertEqual(life.epoch, 1)
        XCTAssertTrue(life.allowStart)
        for gate in LifecycleEpoch.Gate.allCases {
            XCTAssertFalse(life.allows(gate, token: token!), "恢复后仍丢弃挂起前的工作 \(gate)")
        }
        let fresh = life.begin()
        XCTAssertEqual(fresh?.epoch, 1)
        for gate in LifecycleEpoch.Gate.allCases {
            XCTAssertTrue(life.allows(gate, token: fresh!))
        }
    }
}
