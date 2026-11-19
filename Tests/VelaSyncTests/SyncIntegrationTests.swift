#if canImport(CryptoKit) && os(macOS)
import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import VelaCore
@testable import VelaSync
#endif

/// 对本机起的 S3 服务端实测（docs/plans/sync.md 第 18 节 S2 ④）：创建、加入、推送、拉取、409 合并重试、403、410。
final class SyncIntegrationTests: XCTestCase {
    static let origin = "https://sync.example"
    var server: SyncServerHarness!
    static var ipCounter = 0

    override func setUpWithError() throws {
        server = try SyncServerHarness()
    }

    override func tearDown() {
        server?.stop()
    }

    func device() -> TestDevice {
        Self.ipCounter += 1
        return TestDevice(server: server, ip: "10.20.\(Self.ipCounter / 250).\(Self.ipCounter % 250 + 1)")
    }

    func createdCode(_ a: TestDevice, favorites: [String] = ["alice"]) async throws -> String {
        let result = await a.service.create(origin: Self.origin, local: a.local(favorites: favorites))
        guard case .created(let code, let fp, _, let first) = result else { XCTFail("创建失败 \(result)"); throw XCTSkip("") }
        XCTAssertEqual(first, .created)
        XCTAssertEqual(fp.count, 9)
        return code
    }

    func testCreateThenJoinShowsPreviewAndBothDevicesConverge() async throws {
        let a = device(), b = device()
        let code = try await createdCode(a, favorites: ["alice", "Bob_X"])
        XCTAssertEqual(a.service.currentCode(), code)

        b.tick(10)
        let prepared = await b.service.prepareJoin(code, local: b.local(favorites: ["carol", "alice"], blocked: ["dave"]))
        guard case .preview(let ticket) = prepared else { return XCTFail("加入预览失败 \(prepared)") }
        XCTAssertEqual(ticket.host, "sync.example")
        XCTAssertEqual(ticket.fingerprint, SyncCode.fingerprint(blobID: a.service.state!.blobID))
        // alice 远端已是收藏，不盖章；carol 新上传；dave 新屏蔽。
        XCTAssertEqual(ticket.preview, SyncJoinPreview(n: 1, m: 1, v: 0, x: 0, y: 0, z: 0))
        let pushed = await b.service.commitJoin(ticket, local: b.local(favorites: ["carol", "alice"], blocked: ["dave"]), sink: b.sink)
        XCTAssertEqual(pushed, .joined)
        XCTAssertEqual(b.service.state?.phase, .active)

        a.tick(10)
        let pulled = await a.service.runCycle()
        XCTAssertEqual(pulled, .upToDate)
        XCTAssertEqual(a.doc, b.doc)
        XCTAssertEqual(SyncProjection(a.doc!).curation.favorites.sorted(), ["Bob_X", "alice", "carol"].sorted())
        XCTAssertEqual(SyncProjection(a.doc!).curation.blocked, ["dave"])
    }

    func testPushThenPullCarriesLocalEdits() async throws {
        let a = device(), b = device()
        let code = try await createdCode(a)
        guard case .preview(let ticket) = await b.service.prepareJoin(code, local: b.local()) else { return XCTFail() }
        _ = await b.service.commitJoin(ticket, local: b.local(), sink: b.sink)

        a.tick(1000)
        XCTAssertNotNil(a.service.perform(.block("mallory")))
        let r1 = await a.service.runCycle()
        XCTAssertEqual(r1, .pushed)
        b.tick(1000)
        let r2 = await b.service.runCycle()
        XCTAssertEqual(r2, .upToDate)
        XCTAssertEqual(SyncProjection(b.doc!).curation.blocked, ["mallory"])
        // 无改动的周期不发 PUT。
        let r3 = await b.service.runCycle()
        XCTAssertEqual(r3, .upToDate)
    }

    /// 409：B 的 GET 与 PUT 之间 A 抢先写入，B 用 409 的响应体合并后重试，最终两边的改动都在。
    func testConflictMergesAndRetries() async throws {
        let a = device(), b = device()
        let code = try await createdCode(a)
        guard case .preview(let ticket) = await b.service.prepareJoin(code, local: b.local()) else { return XCTFail() }
        _ = await b.service.commitJoin(ticket, local: b.local(), sink: b.sink)
        _ = await a.service.runCycle()

        a.tick(500)
        _ = a.service.perform(.favorite("erin"))
        b.tick(600)
        _ = b.service.perform(.favorite("frank"))
        // 先让 A 推上去，B 手里的版本就旧了。
        let r4 = await a.service.runCycle()
        XCTAssertEqual(r4, .pushed)
        // B 的周期会先 GET 到新版本，看不到 409；改为直接用旧版本发一次 PUT，验证 409 带体。
        let keys = b.service.keys!
        let transport = SyncHTTPTransport(base: server.base, keys: keys, extraHeaders: ["CF-Connecting-IP": b.ip])
        let codec = SyncCryptoCodec(keys: keys)
        let oldVer = b.service.state!.lastSeenVer
        let stale = SyncPutRequest(base: oldVer, next: oldVer + 1, envelope: codec.seal(b.doc!.encoded(), ver: oldVer + 1)!, epoch: b.service.state!.epoch)
        guard case .conflict(let ver, _, _, _, _) = await transport.put(stale) else { return XCTFail("旧版本应得 409") }
        XCTAssertEqual(ver, oldVer + 1)

        // 协调器路径：在 B 的 GET 返回后、PUT 发出前让 A 再写一次，B 必然撞 409 并重试成功。
        a.tick(500)
        _ = a.service.perform(.favorite("grace"))
        let racing = RacingTransport(inner: SyncHTTPTransport(base: server.base, keys: keys, extraHeaders: ["CF-Connecting-IP": b.ip])) {
            _ = await a.service.runCycle()
        }
        let state = b.service.state!
        let coordinator = SyncCoordinator(state: state, store: b.stateStore, transport: racing, codec: codec, wallClock: { b.clock })
        let r5 = await coordinator.runCycle()
        XCTAssertEqual(r5, .pushed)
        XCTAssertEqual(racing.conflicts, 1)
        let favorites = SyncProjection(coordinator.state.doc).curation.favorites
        for name in ["alice", "erin", "frank", "grace"] { XCTAssertTrue(favorites.contains(name), name) }
    }

    /// 403：签名用错私钥，服务端验签失败，协调器停写并提示关闭再重开。
    func testForbiddenHaltsWrites() async throws {
        let a = device()
        _ = try await createdCode(a)
        let wrong = try SyncCrypto.derive([UInt8](repeating: 7, count: 32))
        let real = a.service.keys!
        a.signingOverride = SyncKeyMaterial(blobID: real.blobID, encKey: real.encKey, signCounter: 0, d32: wrong.d32, publicKey: real.publicKey)
        // 改写需要新的传输：重新启动服务让它用被替换的签名材料。
        _ = await a.service.start(local: { a.local() }, sink: a.sink)
        a.tick(100)
        _ = a.service.perform(.favorite("trudy"))
        let r6 = await a.service.runCycle()
        XCTAssertEqual(r6, .halted(.forbidden))
        XCTAssertEqual(a.service.state?.halt, .forbidden)
        // 停写后不再发请求。
        let r7 = await a.service.runCycle()
        XCTAssertEqual(r7, .halted(.forbidden))
    }

    /// 410：一台设备清除云端后，另一台下次同步停止并提示「已于 <日期> 被清除」，本地数据保留。
    func testClearCloudMakesOtherDeviceSeeGone() async throws {
        let a = device(), b = device()
        let code = try await createdCode(a)
        guard case .preview(let ticket) = await b.service.prepareJoin(code, local: b.local()) else { return XCTFail() }
        _ = await b.service.commitJoin(ticket, local: b.local(), sink: b.sink)
        _ = await a.service.runCycle()

        let r8 = await a.service.clear(sink: a.sink)
        XCTAssertEqual(r8, .done)
        XCTAssertFalse(a.service.isActive)
        XCTAssertNil(a.keyStore.entry)
        XCTAssertEqual(a.sink.curation.favorites, ["alice"], "屏障把本机数据写稳")

        b.tick(100)
        let result = await b.service.runCycle()
        guard case .halted(.gone(let deletedAt)) = result else { return XCTFail("应 410，实际 \(String(describing: result))") }
        XCTAssertGreaterThan(deletedAt, 0)
        let message = SyncStatusText.halt(.gone(deletedAt: deletedAt))
        XCTAssertTrue(message.contains("被清除"), message)
        XCTAssertTrue(message.contains("本机数据都还在"), message)
        XCTAssertEqual(SyncProjection(b.doc!).curation.favorites, ["alice"], "本地数据保留")

        // 旧码再加入：410，不加入。
        let c = device()
        guard case .gone = await c.service.prepareJoin(code, local: c.local()) else { return XCTFail() }
        XCTAssertEqual(c.stateStore.load(), .none)
    }

    func testJoinUnknownCodeOffersCreate() async throws {
        let c = device()
        let k = [UInt8](repeating: 3, count: 32)
        let code = SyncCrypto.formatCode(key: k, origin: Self.origin)!
        guard case .notFound(let parsed) = await c.service.prepareJoin(code, local: c.local(favorites: ["zed"])) else { return XCTFail() }
        guard case .created(_, _, _, let first) = await c.service.createWithCode(parsed, local: c.local(favorites: ["zed"])) else { return XCTFail() }
        XCTAssertEqual(first, .created)
        XCTAssertEqual(c.service.currentCode(), code)
    }

    func testInvalidCodeIsRejectedWithoutState() async throws {
        let c = device()
        guard case .invalidCode = await c.service.prepareJoin("vela2:nope", local: c.local()) else { return XCTFail() }
        XCTAssertEqual(c.stateStore.load(), .none)
    }
}

/// 在 GET 返回后、第一次 PUT 发出前插入一段动作（模拟另一台设备抢先写入），并数 409。
final class RacingTransport: SyncTransport {
    let inner: SyncTransport
    let beforeFirstPut: () async -> Void
    var fired = false
    var conflicts = 0

    init(inner: SyncTransport, beforeFirstPut: @escaping () async -> Void) {
        self.inner = inner
        self.beforeFirstPut = beforeFirstPut
    }

    func get() async -> SyncServerResponse { await inner.get() }
    func put(_ request: SyncPutRequest) async -> SyncServerResponse {
        if !fired {
            fired = true
            await beforeFirstPut()
        }
        let r = await inner.put(request)
        if case .conflict = r { conflicts += 1 }
        return r
    }
    func delete(base: UInt64, epoch: String?) async -> SyncServerResponse { await inner.delete(base: base, epoch: epoch) }
}
#endif
