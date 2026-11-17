import XCTest
@testable import VelaCore

private final class Box: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: StreamRendition?
    private var count = 0
    var value: StreamRendition? { lock.withLock { stored } }
    var applies: Int { lock.withLock { count } }
    func set(_ v: StreamRendition) { lock.withLock { stored = v; count += 1 } }
}

final class RenewalCoordinatorTests: XCTestCase {
    private func rendition(_ n: Int) -> StreamRendition {
        StreamRendition(video: URL(string: "https://example.com/\(n).m3u8")!)
    }

    /// R1：视频与音轨共用一次续期，加入方醒来时新地址一定已写入。
    func testJoinerSeesInstalledRenditionWhenItReturns() async {
        // CI 机器慢：加入方必须在 fetch 进行中到达，留足余量（原 0.5ms 对 2ms 在 CI 上会晚到）。
        for _ in 0..<10 {
            let coordinator = RenewalCoordinator()
            let box = Box()
            let fresh = rendition(2)
            let fetch: @Sendable () async -> StreamRendition? = {
                try? await Task.sleep(nanoseconds: 100_000_000)
                return fresh
            }
            let apply: @Sendable (StreamRendition) -> Bool = { box.set($0); return true }
            async let starter = coordinator.renew(fetch: fetch, apply: apply)
            async let joiner: (Bool, StreamRendition?) = {
                try? await Task.sleep(nanoseconds: 5_000_000)
                let ok = await coordinator.renew(fetch: fetch, apply: apply)
                return (ok, box.value)
            }()
            let started = await starter
            let (joinedOK, seen) = await joiner
            XCTAssertTrue(started)
            XCTAssertTrue(joinedOK)
            XCTAssertEqual(seen, fresh)
            XCTAssertEqual(box.applies, 1)
        }
    }

    /// apply 失败（源已换走）时发起方也返回 false，而不是「解析结果非空」。
    func testFailedApplyIsReportedAsFailure() async {
        let coordinator = RenewalCoordinator()
        let ok = await coordinator.renew(fetch: { StreamRendition(video: URL(string: "https://example.com/a.m3u8")!) }, apply: { _ in false })
        XCTAssertFalse(ok)
        let none = await RenewalCoordinator().renew(fetch: { nil }, apply: { _ in true })
        XCTAssertFalse(none)
    }

    /// R2：续期完成但续期后的重取还没结束时仍算「续期中」。
    func testRecoveryCoversRefetchAfterRenewal() async {
        let coordinator = RenewalCoordinator()
        XCTAssertFalse(coordinator.isActive)
        let gate = AsyncGate()
        let refetching = Task {
            await coordinator.recovering {
                _ = await coordinator.renew(fetch: { StreamRendition(video: URL(string: "https://example.com/b.m3u8")!) }, apply: { _ in true })
                await gate.wait()
            }
        }
        try? await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertTrue(coordinator.isActive, "续期已完成、重取未完成")
        await gate.open()
        await refetching.value
        XCTAssertFalse(coordinator.isActive)
    }

    func testRecoveryEndsWhenBodyThrows() async {
        let coordinator = RenewalCoordinator()
        struct Boom: Error {}
        do { try await coordinator.recovering { throw Boom() } } catch {}
        XCTAssertFalse(coordinator.isActive)
    }

    /// R3：取消后在途续期的结果不再写入，也不再占着「续期中」。
    func testCancelDropsInFlightRenewal() async {
        let coordinator = RenewalCoordinator()
        let box = Box()
        let gate = AsyncGate()
        let result = Task {
            await coordinator.renew(fetch: {
                await gate.wait()
                return StreamRendition(video: URL(string: "https://example.com/c.m3u8")!)
            }, apply: { box.set($0); return true })
        }
        try? await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertTrue(coordinator.isActive)
        coordinator.cancel()
        XCTAssertFalse(coordinator.isActive)
        await gate.open()
        let ok = await result.value
        XCTAssertFalse(ok)
        XCTAssertEqual(box.applies, 0)
    }

    /// S1：取消后新的续期注册被拒绝（不发起 fetch），直到 resume。
    func testCancelRejectsNewRegistrationUntilResume() async {
        let coordinator = RenewalCoordinator()
        coordinator.cancel()
        let fetched = Box()
        let rejected = await coordinator.renew(fetch: { fetched.set(StreamRendition(video: URL(string: "https://example.com/x.m3u8")!)); return fetched.value }, apply: { _ in true })
        XCTAssertFalse(rejected)
        XCTAssertEqual(fetched.applies, 0, "被拒绝时不发起 fetch")
        coordinator.resume()
        let ok = await coordinator.renew(now: Date().addingTimeInterval(10), fetch: { StreamRendition(video: URL(string: "https://example.com/y.m3u8")!) }, apply: { _ in true })
        XCTAssertTrue(ok)
    }

    /// S1：提交方记下注册时的代次，取消后核对失败。
    func testEpochChangesOnCancel() {
        let coordinator = RenewalCoordinator()
        let registered = coordinator.epoch
        XCTAssertTrue(coordinator.isCurrent(registered))
        coordinator.cancel()
        XCTAssertFalse(coordinator.isCurrent(registered))
        coordinator.resume()
        XCTAssertFalse(coordinator.isCurrent(registered), "resume 不让旧代次复活")
        XCTAssertTrue(coordinator.isCurrent(coordinator.epoch))
    }

    /// G1：取消的续期退回额度，恢复后立刻还能续期（3 秒最短间隔不被占用）。
    func testCancelledRenewalRefundsBudget() async {
        let coordinator = RenewalCoordinator(budget: RenewalBudget(limit: 1, window: 60, minGap: 3))
        let gate = AsyncGate()
        let now = Date()
        let first = Task {
            await coordinator.renew(now: now, fetch: { await gate.wait(); return nil }, apply: { _ in true })
        }
        try? await Task.sleep(nanoseconds: 50_000_000)
        coordinator.cancel()
        await gate.open()
        let cancelled = await first.value
        XCTAssertFalse(cancelled)
        coordinator.resume()
        let again = await coordinator.renew(now: now.addingTimeInterval(1), fetch: { StreamRendition(video: URL(string: "https://example.com/z.m3u8")!) }, apply: { _ in true })
        XCTAssertTrue(again, "额度已退回")
    }

    /// G1：真正完成（哪怕拿不到地址）的续期照常计数。
    func testCompletedFailureStillConsumesBudget() async {
        let coordinator = RenewalCoordinator(budget: RenewalBudget(limit: 1, window: 60, minGap: 3))
        let now = Date()
        _ = await coordinator.renew(now: now, fetch: { nil }, apply: { _ in true })
        let second = await coordinator.renew(now: now.addingTimeInterval(5), fetch: { StreamRendition(video: URL(string: "https://example.com/z.m3u8")!) }, apply: { _ in true })
        XCTAssertFalse(second)
    }

    func testRenewalFreshnessRejectsStaleResults() {
        func current(_ gen: Int, _ room: String?, cancelled: Bool = false) -> Bool {
            RenewalFreshness.isCurrent(startedGeneration: 3, currentGeneration: gen, startedRoom: "a", currentRoom: room, cancelled: cancelled)
        }
        XCTAssertTrue(current(3, "a"))
        XCTAssertFalse(current(4, "a"), "换档 / 重新解析 / rejoin 后代次已变")
        XCTAssertFalse(current(3, "b"))
        XCTAssertFalse(current(3, nil))
        XCTAssertFalse(current(3, "a", cancelled: true))
    }
}

private actor AsyncGate {
    private var opened = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    func wait() async {
        if opened { return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func open() {
        opened = true
        waiters.forEach { $0.resume() }
        waiters = []
    }
}
