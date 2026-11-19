#if canImport(CryptoKit) && os(macOS)
import Foundation
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 线上互通（可选）：设了环境变量 `VELA_SYNC_LIVE_BASE`（规范 origin）才跑，CI 与默认本地运行一律跳过。
/// 同一套流程打真实服务器：创建、两台内存设备加入与推拉、409 合并、改动互见、清除云端后 410。
/// 每次用新生成的 K；结束时清除云端（服务端留下删除墓碑）。服务器地址只从环境变量来，不写进仓库。
final class SyncLiveServerTests: XCTestCase {
    final class Recorder: @unchecked Sendable {
        private let lock = NSLock()
        private(set) var lines: [String] = []
        func add(_ s: String) { lock.withLock { lines.append(s) } }
    }

    /// 记下每个请求的状态码与耗时；可在第一次 PUT 发出前插一段动作（制造 409）。
    final class LiveTransport: SyncTransport, @unchecked Sendable {
        let inner: SyncTransport
        let device: String
        let recorder: Recorder
        var beforeNextPut: (() async -> Void)?

        init(_ inner: SyncTransport, device: String, recorder: Recorder) {
            self.inner = inner
            self.device = device
            self.recorder = recorder
        }

        static func code(_ r: SyncServerResponse) -> String {
            switch r {
            case .blob, .stored, .deleted: return "200"
            case .notFound: return "404"
            case .gone: return "410"
            case .conflict: return "409"
            case .forbidden: return "403"
            case .payloadTooLarge: return "413"
            case .rateLimited: return "429"
            case .quotaFull: return "507"
            case .transient: return "transient"
            }
        }

        private func timed(_ name: String, _ body: () async -> SyncServerResponse) async -> SyncServerResponse {
            let start = Date()
            let r = await body()
            recorder.add(String(format: "%@ %@ -> %@ %.0f ms", device, name, Self.code(r), Date().timeIntervalSince(start) * 1000))
            return r
        }

        func get() async -> SyncServerResponse { await timed("GET") { await inner.get() } }
        func put(_ request: SyncPutRequest) async -> SyncServerResponse {
            if let hook = beforeNextPut {
                beforeNextPut = nil
                await hook()
            }
            return await timed("PUT base=\(request.base)") { await inner.put(request) }
        }
        func delete(base: UInt64, epoch: String?) async -> SyncServerResponse {
            await timed("DELETE base=\(base)") { await inner.delete(base: base, epoch: epoch) }
        }
    }

    final class LiveDevice {
        let stateStore = MemoryStateStore()
        let keyStore = MemoryKeyStore()
        let sink = MemorySink()
        var service: SyncService!
        var transport: LiveTransport?

        init(name: String, recorder: Recorder) {
            let env = SyncService.Environment(stateStore: stateStore, keyStore: keyStore, makeTransport: { [unowned self] origin, keys in
                let t = LiveTransport(SyncHTTPTransport(base: URL(string: origin)!, keys: keys), device: name, recorder: recorder)
                self.transport = t
                return t
            })
            service = SyncService(env: env)
        }

        var favorites: Set<String> { Set(service.state.map { SyncProjection($0.doc).curation.favorites } ?? []) }
    }

    func testLiveServerEndToEnd() async throws {
        guard let origin = ProcessInfo.processInfo.environment["VELA_SYNC_LIVE_BASE"], !origin.isEmpty else {
            throw XCTSkip("未设 VELA_SYNC_LIVE_BASE，线上互通测试跳过")
        }
        XCTAssertTrue(SyncCode.isCanonicalOrigin(origin), "VELA_SYNC_LIVE_BASE 必须是规范 origin")
        let recorder = Recorder()
        let a = LiveDevice(name: "A", recorder: recorder), b = LiveDevice(name: "B", recorder: recorder)
        var cleared = false
        defer { for line in recorder.lines { print("live " + line) } }

        func step(_ title: String) { recorder.add("== " + title) }

        step("A 创建")
        let created = await a.service.create(origin: origin, local: SyncLocalData(curation: Curation(favorites: ["alice"])))
        guard case .created(let code, _, _, let first) = created else { return XCTFail("创建失败 \(created)") }
        XCTAssertEqual(first, .created)
        do {
            step("B 加入")
            guard case .preview(let ticket) = await b.service.prepareJoin(code, local: SyncLocalData(curation: Curation(favorites: ["bob"]))) else {
                return XCTFail("加入预览失败")
            }
            XCTAssertEqual(ticket.preview.n, 1)
            let joined = await b.service.confirmJoin(ticket, local: SyncLocalData(curation: Curation(favorites: ["bob"])))
            XCTAssertEqual(joined, .pushed)

            step("A 拉取")
            let pulled = await a.service.runCycle()
            XCTAssertEqual(pulled, .upToDate)
            XCTAssertEqual(a.favorites, ["alice", "bob"])

            step("409 合并：B 的 GET 之后、PUT 之前 A 抢先写入")
            _ = a.service.perform(.favorite("carol"))
            _ = b.service.perform(.favorite("dave"))
            b.transport?.beforeNextPut = { _ = await a.service.runCycle() }
            let merged = await b.service.runCycle()
            XCTAssertEqual(merged, .pushed)
            XCTAssertTrue(recorder.lines.contains { $0.hasPrefix("B PUT") && $0.contains("-> 409") }, "应出现一次 409")

            step("改动互见")
            let seen = await a.service.runCycle()
            XCTAssertEqual(seen, .upToDate)
            XCTAssertEqual(a.favorites, ["alice", "bob", "carol", "dave"])
            XCTAssertEqual(a.service.state?.doc, b.service.state?.doc)

            step("A 清除云端，B 随后 410")
            let clearResult = await a.service.clear(sink: a.sink)
            XCTAssertEqual(clearResult, .done)
            cleared = clearResult == .done
            let gone = await b.service.runCycle()
            guard case .halted(.gone) = gone else { return XCTFail("应 410，实际 \(String(describing: gone))") }
        }
        if !cleared { _ = await a.service.clear(sink: a.sink) }
    }
}
#endif
