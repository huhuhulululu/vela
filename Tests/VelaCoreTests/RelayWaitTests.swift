import XCTest
@testable import VelaCore

/// 用虚拟时钟驱动 `RelayWait`：睡眠只推进时间，刷新按脚本让分片在指定时刻前进。
final class RelayWaitTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 2_000_000)
    private let body = Data("body".utf8)

    private final class World {
        var clock: Date
        var feed = RelayFeed()
        var refreshes = 0
        var sleeps: [TimeInterval] = []
        init(start: Date) { clock = start }
    }

    private func info(_ seq: Int) -> MediaPlaylistInfo {
        MediaPlaylistInfo(targetDuration: 2, lastSequence: seq, lastSegmentDuration: 1.6)
    }

    private func advance(_ world: World, to seq: Int) {
        guard let ticket = world.feed.begin() else { return }
        _ = world.feed.finish(ticket, outcome: .success(body: body, info: info(seq)), now: world.clock)
    }

    /// 在 `request` 时刻收到请求；`advanceAt` 非 nil 时，该时刻之后的刷新能拿到新分片。
    private func run(_ world: World, advanceAt: Date?, seedAt: Date?) async -> RelayFeed.Serve {
        await RelayWait.resolve(
            targetDuration: 2,
            decide: { world.feed.serve(now: world.clock) },
            refresh: { _ in
                world.refreshes += 1
                if let advanceAt, world.clock >= advanceAt { self.advance(world, to: 11) }
                else if let seedAt, world.clock >= seedAt, world.feed.info == nil { self.advance(world, to: 10) }
                else if let ticket = world.feed.begin() {
                    _ = world.feed.finish(ticket, outcome: .success(body: self.body, info: world.feed.info ?? self.info(10)), now: world.clock)
                }
            },
            now: { world.clock },
            sleep: { world.sleeps.append($0); world.clock = world.clock.addingTimeInterval($0) }
        )
    }

    func testPublishJitterOf2point6SecondsDoesNotFail() async {
        let world = World(start: t0)
        advance(world, to: 10)
        world.clock = t0.addingTimeInterval(2.1) // AVPlayer 在 2.1 秒请求，已超过 1×T 宽限
        let serve = await run(world, advanceAt: t0.addingTimeInterval(2.6), seedAt: nil)
        guard case .body = serve else { return XCTFail("2.6 秒才前进的正常抖动不该失败：\(serve)") }
    }

    func testJitterUpToOneAndHalfTargetNeverFails() async {
        for gap in stride(from: 2.0, through: 3.0, by: 0.1) {
            let world = World(start: t0)
            advance(world, to: 10)
            world.clock = t0.addingTimeInterval(2.05)
            let serve = await run(world, advanceAt: t0.addingTimeInterval(gap), seedAt: nil)
            guard case .body = serve else { return XCTFail("间隔 \(gap) 秒不该失败") }
        }
    }

    func testRealStallStillExpiresAfterBoundedWait() async {
        let world = World(start: t0)
        advance(world, to: 10)
        world.clock = t0.addingTimeInterval(2.1)
        let serve = await run(world, advanceAt: nil, seedAt: nil)
        guard case .expired = serve else { return XCTFail("真实停滞必须失败：\(serve)") }
        let waited = world.clock.timeIntervalSince(t0.addingTimeInterval(2.1))
        XCTAssertEqual(waited, RelayPolicy.waitBound(targetDuration: 2), accuracy: 0.001, "等待有界")
    }

    func testFreshFeedReturnsImmediatelyWithoutWaiting() async {
        let world = World(start: t0)
        advance(world, to: 10)
        world.clock = t0.addingTimeInterval(1.0)
        let serve = await run(world, advanceAt: nil, seedAt: nil)
        guard case .body = serve else { return XCTFail() }
        XCTAssertEqual(world.refreshes, 0)
        XCTAssertTrue(world.sleeps.isEmpty)
    }

    func testFirstFetchLateStillServesInsteadOfRedirecting() async {
        let world = World(start: t0)
        let serve = await run(world, advanceAt: nil, seedAt: t0.addingTimeInterval(0.5))
        guard case .body = serve else { return XCTFail("起播首取稍慢应在等待内拿到内容：\(serve)") }
    }

    func testNeverAnyContentEndsEmptyForCallerToFail() async {
        // 起播前完全拿不到列表：返回 .empty，由调用方回 503（不再 302 到上游）。
        let world = World(start: t0)
        let serve = await RelayWait.resolve(
            targetDuration: 2,
            decide: { world.feed.serve(now: world.clock) },
            refresh: { _ in world.refreshes += 1 },
            now: { world.clock },
            sleep: { world.clock = world.clock.addingTimeInterval($0) }
        )
        XCTAssertEqual(serve, .empty)
        XCTAssertGreaterThan(world.refreshes, 1)
    }

    func testRefreshReceivesRemainingBudgetAndNoRefreshOnceExhausted() async {
        let world = World(start: t0)
        advance(world, to: 10)
        world.clock = t0.addingTimeInterval(2.1)
        var budgets: [TimeInterval] = []
        _ = await RelayWait.resolve(
            targetDuration: 2,
            decide: { world.feed.serve(now: world.clock) },
            refresh: { budget in budgets.append(budget); world.refreshes += 1 },
            now: { world.clock },
            sleep: { world.clock = world.clock.addingTimeInterval($0) }
        )
        XCTAssertEqual(budgets.first ?? 0, 2, accuracy: 0.001)
        XCTAssertTrue(budgets.allSatisfy { $0 > 0 }, "预算用完后不再发起刷新")
        XCTAssertEqual(world.clock.timeIntervalSince(t0.addingTimeInterval(2.1)), 2, accuracy: 0.001)
    }

    /// 真实时间：单次刷新要 3 秒，等待上限 0.4 秒，总等待不得被它拖过上限。
    @available(macOS 12, *)
    func testSlowRefreshCannotBreakTheWaitBound() async {
        let start = Date()
        let serve = await RelayWait.resolve(
            targetDuration: 0.4,
            decide: { .empty },
            refresh: { budget in await RelayWait.bounded(budget) { try? await Task.sleep(nanoseconds: 3_000_000_000) } },
            now: { Date() },
            sleep: { try? await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) }
        )
        XCTAssertEqual(serve, .empty)
        let waited = Date().timeIntervalSince(start)
        XCTAssertLessThan(waited, 0.4 + 0.3, "总等待应约等于上限，不是 3 秒")
    }

    @available(macOS 12, *)
    func testBoundedReturnsEarlyWhenOperationFinishesFirst() async {
        let start = Date()
        await RelayWait.bounded(5) { try? await Task.sleep(nanoseconds: 50_000_000) }
        XCTAssertLessThan(Date().timeIntervalSince(start), 1)
    }
}
