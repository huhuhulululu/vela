#if canImport(CryptoKit) && os(macOS)
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 审计 high：退出时再起一个周期，不得与在途周期交错（周期在等网络时放开的是本地操作的门，不是周期本身）。
final class SyncSingleFlightTests: XCTestCase {
    func testConcurrentCyclesNeverInterleave() async {
        let t = ScriptedTransport()
        t.gets = [.notFound(epoch: ServiceFixture.epoch, now: 1_800_000_000_000)]
        t.puts = [.stored(ver: 1, epoch: ServiceFixture.epoch, now: 1_800_000_000_000)]
        let service = ServiceFixture.service(t)
        guard case .created = await service.create(origin: ServiceFixture.origin, local: SyncLocalData(curation: Curation(favorites: ["alice"]))) else { return XCTFail() }
        let remote = ServiceFixture.blob(ServiceFixture.remoteDoc(favorites: ["zed"]), ver: 2)
        t.gets = [remote, remote]
        t.puts = [.stored(ver: 3, epoch: ServiceFixture.epoch, now: 1_800_000_000_000)]
        t.delay = 50_000_000
        async let a = service.runCycle()
        async let b = service.runCycle()
        _ = await (a, b)
        XCTAssertEqual(t.maxInFlight, 1, "两个周期的请求交错了：\(t.log)")
        let state = service.state!
        XCTAssertEqual(state.lastSeenVer, 3, "在途写入的结果不得被另一个周期的快照覆盖")
        XCTAssertNil(state.inflight)
    }

    /// 复审 medium：单飞不得在协作线程上阻塞等待。远多于线程池宽度的周期同时排队时，别的异步任务仍要能跑。
    func testWaitingCyclesDoNotStarveTheThreadPool() {
        let t = ScriptedTransport()
        t.gets = [.notFound(epoch: ServiceFixture.epoch, now: 1_800_000_000_000)]
        t.puts = [.stored(ver: 1, epoch: ServiceFixture.epoch, now: 1_800_000_000_000)]
        let service = ServiceFixture.service(t)
        let created = DispatchSemaphore(value: 0)
        Task.detached {
            _ = await service.create(origin: ServiceFixture.origin, local: SyncLocalData())
            created.signal()
        }
        XCTAssertEqual(created.wait(timeout: .now() + 5), .success)
        t.fallback = .transient
        t.delay = 300_000_000
        for _ in 0..<(ProcessInfo.processInfo.activeProcessorCount * 3) {
            Task.detached { _ = await service.runCycle() }
        }
        Thread.sleep(forTimeInterval: 0.1)
        let probe = DispatchSemaphore(value: 0)
        Task.detached { probe.signal() }
        XCTAssertEqual(probe.wait(timeout: .now() + 2), .success, "线程池被排队的周期占满了")
    }
}
#endif
