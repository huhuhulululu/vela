import XCTest
#if SWIFT_PACKAGE
@testable import VelaCore
#else
@testable import VelaPhone
#endif

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

    /// 未挂起时共享任务和每一路的四个 gate 都放行。
    func testNotSuspendedAllowsSharedTasksAndEveryLane() {
        let life = LifecycleEpoch()
        let shared = life.begin()
        let primary = life.begin(lane: .primary)
        let secondary = life.begin(lane: .secondary)
        XCTAssertEqual(shared?.epoch, 0)
        XCTAssertEqual(shared?.scope, .shared)
        XCTAssertEqual(primary?.scope, .lane(.primary))
        XCTAssertEqual(secondary?.scope, .lane(.secondary))
        XCTAssertFalse(life.suspended)
        XCTAssertNil(life.allowedLane)
        for token in [shared!, primary!, secondary!] {
            for gate in LifecycleEpoch.Gate.allCases {
                XCTAssertTrue(life.allows(gate, token: token), "\(token.scope) \(gate)")
            }
        }
    }

    /// 画中画挂起：目录、搜索、收藏查询、侧车、缩略图四个 gate 被拒；
    /// 放行路的请求、中继登记、中继启动、提交通过；另一路被拒。
    /// resume 不回退代次，挂起前的 token 全部失效，之后新开工的 token 通过。
    func testPictureInPictureAllowsOnlyTheGivenLaneThenResumeDropsOldTokens() {
        var life = LifecycleEpoch()
        let sharedBefore = life.begin()
        let primaryBefore = life.begin(lane: .primary)
        let secondaryBefore = life.begin(lane: .secondary)

        life.suspend(allowingLane: .primary)
        XCTAssertTrue(life.suspended)
        XCTAssertEqual(life.allowedLane, .primary)
        XCTAssertEqual(life.epoch, 1)
        XCTAssertTrue(life.allowStart, "放行路时中继仍可启动")

        XCTAssertNil(life.begin(), "共享任务不得开工")
        let sharedNow = LifecycleEpoch.Token(epoch: life.epoch, scope: .shared)
        let sharedTasks = ["目录", "搜索", "收藏查询", "侧车", "缩略图"]
        for name in sharedTasks {
            for gate in LifecycleEpoch.Gate.allCases {
                XCTAssertFalse(life.allows(gate, token: sharedNow), "\(name) \(gate)")
            }
        }
        for token in [sharedBefore!, primaryBefore!, secondaryBefore!] {
            for gate in LifecycleEpoch.Gate.allCases {
                XCTAssertFalse(life.allows(gate, token: token), "挂起前 \(token.scope) \(gate)")
            }
        }

        let pip = life.begin(lane: .primary)
        XCTAssertEqual(pip?.epoch, 1)
        XCTAssertEqual(pip?.scope, .lane(.primary))
        for gate in [.beforeRequest, .beforeRelayRegister, .beforeRelayStart, .beforeCommit] as [LifecycleEpoch.Gate] {
            XCTAssertTrue(life.allows(gate, token: pip!), "放行路 \(gate)")
        }

        XCTAssertNil(life.begin(lane: .secondary), "另一路不得开工")
        let other = LifecycleEpoch.Token(epoch: life.epoch, scope: .lane(.secondary))
        for gate in LifecycleEpoch.Gate.allCases {
            XCTAssertFalse(life.allows(gate, token: other), "另一路 \(gate)")
        }

        life.resume()
        XCTAssertFalse(life.suspended)
        XCTAssertNil(life.allowedLane)
        XCTAssertEqual(life.epoch, 1, "resume 不回退代次")
        XCTAssertTrue(life.allowStart)
        for token in [sharedBefore!, primaryBefore!, secondaryBefore!] {
            for gate in LifecycleEpoch.Gate.allCases {
                XCTAssertFalse(life.allows(gate, token: token), "resume 后旧 token \(token.scope) \(gate)")
            }
        }
        let freshShared = life.begin()
        let freshPrimary = life.begin(lane: .primary)
        let freshSecondary = life.begin(lane: .secondary)
        XCTAssertEqual(freshShared?.epoch, 1)
        XCTAssertEqual(freshPrimary?.epoch, 1)
        XCTAssertEqual(freshSecondary?.epoch, 1)
        for token in [freshShared!, freshPrimary!, freshSecondary!] {
            for gate in LifecycleEpoch.Gate.allCases {
                XCTAssertTrue(life.allows(gate, token: token), "新 token \(token.scope) \(gate)")
            }
        }
    }

    /// 连续两次 `suspend(allowingLane:)` 换路后，只放行最新那一路。
    func testConsecutiveSuspendAllowingLaneKeepsOnlyTheLatestLane() {
        var life = LifecycleEpoch()
        life.suspend(allowingLane: .primary)
        let primary = life.begin(lane: .primary)
        XCTAssertNotNil(primary)
        XCTAssertEqual(life.epoch, 1)

        life.suspend(allowingLane: .secondary)
        XCTAssertEqual(life.allowedLane, .secondary)
        XCTAssertEqual(life.epoch, 2)
        XCTAssertTrue(life.suspended)

        for gate in LifecycleEpoch.Gate.allCases {
            XCTAssertFalse(life.allows(gate, token: primary!), "上一路的旧 token \(gate)")
        }
        XCTAssertNil(life.begin(lane: .primary))
        let primaryNow = LifecycleEpoch.Token(epoch: life.epoch, scope: .lane(.primary))
        for gate in LifecycleEpoch.Gate.allCases {
            XCTAssertFalse(life.allows(gate, token: primaryNow), "上一路 \(gate)")
        }
        XCTAssertNil(life.begin())
        let shared = LifecycleEpoch.Token(epoch: life.epoch, scope: .shared)
        for gate in LifecycleEpoch.Gate.allCases {
            XCTAssertFalse(life.allows(gate, token: shared), "共享 \(gate)")
        }

        let secondary = life.begin(lane: .secondary)
        XCTAssertEqual(secondary?.scope, .lane(.secondary))
        for gate in LifecycleEpoch.Gate.allCases {
            XCTAssertTrue(life.allows(gate, token: secondary!), "最新一路 \(gate)")
        }

        life.suspend()
        XCTAssertNil(life.allowedLane)
        XCTAssertFalse(life.allowStart)
        XCTAssertNil(life.begin(lane: .secondary))
        let closed = LifecycleEpoch.Token(epoch: life.epoch, scope: .lane(.secondary))
        for gate in LifecycleEpoch.Gate.allCases {
            XCTAssertFalse(life.allows(gate, token: closed), "全量挂起后不再放行 \(gate)")
        }
    }
}
