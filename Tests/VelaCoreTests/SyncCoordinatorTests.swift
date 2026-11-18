import XCTest
@testable import VelaCore

/// 第 11.2、11.4 节：协调器状态机。
final class SyncCoordinatorTests: XCTestCase {
    typealias F = SyncFixture

    final class Wall {
        var now: UInt64
        init(_ now: UInt64) { self.now = now }
    }

    struct Device {
        let coordinator: SyncCoordinator
        let store: MemoryStateStore
        let transport: MemoryTransport
        let wall: Wall
    }

    let server = MemoryServer()

    func device(dev: UInt32, sizeLimit: Int = SyncLimits.plaintextBytes, state: SyncState? = nil, codec: PlainCodec = PlainCodec()) -> Device {
        let s = state ?? SyncState(phase: .active, origin: "https://sync.example.org", blobID: String(repeating: "a", count: 64), clock: SyncClock(dev: dev))
        let store = MemoryStateStore(s)
        let transport = MemoryTransport(server)
        let wall = Wall(server.now)
        let c = SyncCoordinator(state: s, store: store, transport: transport, codec: codec, wallClock: { wall.now }, sizeLimit: sizeLimit)
        return Device(coordinator: c, store: store, transport: transport, wall: wall)
    }

    func favorites(_ doc: SyncDocument?) -> [String] { doc.map { SyncProjection($0).curation.favorites } ?? [] }

    // MARK: 创建与基本周期

    func testCreatesOn404ThenUpToDate() async {
        let a = device(dev: 1)
        _ = a.coordinator.perform(.favorite("Alice"))
        let first = await a.coordinator.runCycle()
        XCTAssertEqual(first, .created)
        XCTAssertEqual(server.ver, 1)
        XCTAssertEqual(server.puts.first?.base, 0)
        XCTAssertEqual(server.puts.first?.includesPub, true)
        XCTAssertEqual(favorites(server.document), ["Alice"])
        XCTAssertEqual(a.coordinator.state.lastSeenVer, 1)
        XCTAssertEqual(a.coordinator.state.pushedGen, a.coordinator.state.gen)
        XCTAssertNil(a.coordinator.state.inflight)
        let second = await a.coordinator.runCycle()
        XCTAssertEqual(second, .upToDate)
        XCTAssertEqual(server.puts.count, 1, "没有改动不做无意义的 PUT")
        XCTAssertEqual(a.store.stored, a.coordinator.state, "状态已落盘")
    }

    func testCreatesEvenWhenEmpty() async {
        let a = device(dev: 1)
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .created)
        XCTAssertEqual(server.document, .empty)
    }

    func testTwoDevicesConverge() async {
        let a = device(dev: 1), b = device(dev: 2)
        _ = a.coordinator.perform(.favorite("alice"))
        _ = await a.coordinator.runCycle()
        _ = b.coordinator.perform(.block("bob"))
        let rb = await b.coordinator.runCycle()
        XCTAssertEqual(rb, .pushed)
        let ra = await a.coordinator.runCycle()
        XCTAssertEqual(ra, .upToDate)
        XCTAssertEqual(a.coordinator.state.doc, b.coordinator.state.doc)
        XCTAssertEqual(a.coordinator.state.doc, server.document)
    }

    func testNoChangeGetAdvancesWatermark() async {
        let a = device(dev: 1), b = device(dev: 2)
        _ = a.coordinator.perform(.favorite("alice"))
        _ = await a.coordinator.runCycle()
        var changed: [SyncDocument] = []
        b.coordinator.onDocumentChanged = { changed.append($0) }
        let r = await b.coordinator.runCycle()
        XCTAssertEqual(r, .upToDate)
        XCTAssertEqual(b.coordinator.state.lastSeenVer, 1)
        XCTAssertEqual(b.coordinator.state.lastSeenHash, TestSHA256.hex(server.data!))
        XCTAssertEqual(server.puts.count, 1, "只读到、不推送")
        XCTAssertEqual(changed.count, 1, "文档变了才更新投影")
        XCTAssertEqual(b.coordinator.state.clock.l, a.coordinator.state.clock.l, "收到远端后时钟推进")
    }

    // MARK: 请求期间的本地编辑

    func testLocalEditDuringGetIsMergedAndPushed() async {
        let a = device(dev: 1), b = device(dev: 2)
        _ = a.coordinator.perform(.favorite("alice"))
        _ = await a.coordinator.runCycle()
        b.transport.beforeGetReturns = { _ = b.coordinator.perform(.favorite("carol")) }
        let r = await b.coordinator.runCycle()
        XCTAssertEqual(r, .pushed)
        XCTAssertEqual(Set(favorites(server.document)), ["alice", "carol"])
    }

    func testLocalEditDuringPutStaysPending() async {
        let a = device(dev: 1)
        _ = a.coordinator.perform(.favorite("alice"))
        a.transport.beforePutReturns = { _ = a.coordinator.perform(.favorite("dave")) }
        _ = await a.coordinator.runCycle()
        a.transport.beforePutReturns = nil
        XCTAssertGreaterThan(a.coordinator.state.gen, a.coordinator.state.pushedGen, "请求期间的新操作留到下一周期")
        XCTAssertEqual(favorites(server.document), ["alice"])
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .pushed)
        XCTAssertEqual(favorites(server.document), ["dave", "alice"])
        XCTAssertEqual(a.coordinator.state.gen, a.coordinator.state.pushedGen)
    }

    // MARK: 结果未知的 PUT

    func testPutCommittedButResponseLostIsConfirmedNextCycle() async {
        let a = device(dev: 1)
        _ = await a.coordinator.runCycle()
        _ = a.coordinator.perform(.favorite("alice"))
        a.transport.dropNextPutResponse = true
        let lost = await a.coordinator.runCycle()
        XCTAssertEqual(lost, .transient)
        XCTAssertEqual(server.ver, 2, "服务端已提交")
        XCTAssertEqual(a.coordinator.state.inflight?.next, 2, "在途请求保留")
        XCTAssertNotNil(a.store.stored?.inflight, "在途请求在 PUT 之前落盘")
        XCTAssertGreaterThan(a.coordinator.state.gen, a.coordinator.state.pushedGen)
        let next = await a.coordinator.runCycle()
        XCTAssertEqual(next, .upToDate, "确认推送成功，不再重推")
        XCTAssertNil(a.coordinator.state.inflight)
        XCTAssertEqual(a.coordinator.state.pushedGen, a.coordinator.state.gen)
        XCTAssertEqual(server.puts.count, 2)
    }

    func testLostPutOverwrittenByOtherDeviceIsNotConfirmed() async {
        let a = device(dev: 1), b = device(dev: 2)
        _ = await a.coordinator.runCycle()
        _ = a.coordinator.perform(.favorite("alice"))
        a.transport.dropNextPutResponse = true
        _ = await a.coordinator.runCycle()
        _ = b.coordinator.perform(.favorite("bob"))
        _ = await b.coordinator.runCycle()
        let gen = a.coordinator.state.gen
        XCTAssertLessThan(a.coordinator.state.pushedGen, gen)
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .pushed, "版本不是 inflight.next：不确认，重新合并推送（安全）")
        XCTAssertNil(a.coordinator.state.inflight)
        XCTAssertEqual(a.coordinator.state.pushedGen, gen)
        XCTAssertEqual(Set(favorites(server.document)), ["alice", "bob"])
    }

    // MARK: 409

    func testConflictMergesAndRetries() async {
        let a = device(dev: 1), b = device(dev: 2)
        _ = await a.coordinator.runCycle()
        _ = await b.coordinator.runCycle()
        _ = a.coordinator.perform(.favorite("alice"))
        var once = true
        a.transport.beforeGetReturns = {
            guard once else { return }
            once = false
            _ = b.coordinator.perform(.favorite("bob"))
            _ = await b.coordinator.runCycle()
        }
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .pushed)
        XCTAssertEqual(Set(favorites(server.document)), ["alice", "bob"])
        XCTAssertEqual(server.puts.filter { $0.base == 1 }.count, 2, "第一次 PUT 409，用响应合并后重推")
    }

    func testConflictLimitBacksOffKeepingPending() async {
        let a = device(dev: 1), b = device(dev: 2)
        _ = await a.coordinator.runCycle()
        _ = await b.coordinator.runCycle()
        _ = a.coordinator.perform(.favorite("alice"))
        var n = 0
        // 每次 A 的 PUT 之前让 B 抢先写一次：A 连续 409。
        a.transport.beforePutSends = {
            n += 1
            _ = b.coordinator.perform(.favorite(String(format: "b%03d", n)))
            _ = await b.coordinator.runCycle()
        }
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .conflictLimit)
        XCTAssertGreaterThan(a.coordinator.state.gen, a.coordinator.state.pushedGen, "未推送的改动保留")
        XCTAssertEqual(n, 4, "首次加 3 次重试")
        a.transport.beforePutSends = nil
        let later = await a.coordinator.runCycle()
        XCTAssertEqual(later, .pushed)
        XCTAssertTrue(SyncProjection(server.document!).isFavorite("cb:alice"))
    }

    // MARK: 停写类错误

    func seeded() async -> Device {
        let a = device(dev: 1)
        _ = a.coordinator.perform(.favorite("alice"))
        _ = await a.coordinator.runCycle()
        _ = a.coordinator.perform(.favorite("bob"))
        _ = await a.coordinator.runCycle()
        XCTAssertEqual(a.coordinator.state.lastSeenVer, 2)
        return a
    }

    func testRollbackToOlderVersionHalts() async {
        let a = await seeded()
        let old = PlainCodec.envelope(.empty, ver: 1)
        a.transport.scriptedGets = [.blob(ver: 1, pub: "PUB", data: old, epoch: server.epoch, now: server.now)]
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .halted(.rollback))
        XCTAssertEqual(favorites(a.coordinator.state.doc), ["bob", "alice"], "不合并")
        XCTAssertEqual(a.store.stored?.halt, .rollback)
        let gets = server.gets
        let again = await a.coordinator.runCycle()
        XCTAssertEqual(again, .halted(.rollback))
        XCTAssertEqual(server.gets, gets, "停写后不再发请求")
    }

    func testSameVersionDifferentEnvelopeIsRollback() async {
        let a = await seeded()
        a.transport.scriptedGets = [.blob(ver: 2, pub: "PUB", data: PlainCodec.envelope(.empty, ver: 2), epoch: server.epoch, now: server.now)]
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .halted(.rollback))
    }

    func testEpochChangeHalts() async {
        let a = await seeded()
        server.epoch = "e0000000000000000000000000000002"
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .halted(.epochChanged))
    }

    func testGoneHalts() async {
        let a = await seeded()
        server.deletedAt = 1_700_000_000_000
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .halted(.gone(deletedAt: 1_700_000_000_000)))
        XCTAssertEqual(favorites(a.coordinator.state.doc), ["bob", "alice"], "本地数据保留")
    }

    func testNotFoundAfterSeenHalts() async {
        let a = await seeded()
        a.transport.scriptedGets = [.notFound(epoch: server.epoch, now: server.now)]
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .halted(.missing))
        XCTAssertEqual(server.puts.count, 2, "不自动创建")
    }

    func testNotFoundWithInflightCreateHalts() async {
        let a = device(dev: 1)
        a.transport.dropNextPutResponse = true
        _ = await a.coordinator.runCycle()
        XCTAssertEqual(a.coordinator.state.inflight?.base, 0)
        a.transport.scriptedGets = [.notFound(epoch: server.epoch, now: server.now)]
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .halted(.missing), "结果未知的创建之后遇 404 停写")
    }

    func testForbiddenHalts() async {
        let a = await seeded()
        _ = a.coordinator.perform(.favorite("carol"))
        a.transport.putOverride = .forbidden
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .halted(.forbidden))
        XCTAssertTrue(SyncProjection(a.coordinator.state.doc).isFavorite("cb:carol"), "保留一切")
    }

    func testPubMismatchHalts() async {
        let a = await seeded()
        let codec = PlainCodec()
        codec.publicKey = "OTHER"
        let b = device(dev: 2, codec: codec)
        let r = await b.coordinator.runCycle()
        XCTAssertEqual(r, .halted(.pubMismatch))
        _ = a
    }

    // MARK: 只读、非法、解封失败

    func testNewerSchemaIsReadOnly() async {
        let a = await seeded()
        let newer = PlainCodec().seal(Array("{\"schema\":3}".utf8), ver: 3)!
        a.transport.scriptedGets = [.blob(ver: 3, pub: "PUB", data: newer, epoch: server.epoch, now: server.now)]
        _ = a.coordinator.perform(.favorite("carol"))
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .readOnly)
        XCTAssertEqual(server.puts.count, 2, "只读不推送")
        XCTAssertEqual(a.coordinator.state.lastSeenVer, 2, "未通过校验不推进水位")
    }

    func testUnknownEnvelopePrefixIsReadOnly() async {
        let a = await seeded()
        a.transport.scriptedGets = [.blob(ver: 3, pub: "PUB", data: "e9.xxxx", epoch: server.epoch, now: server.now)]
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .readOnly)
    }

    func testInvalidRemoteIsNotMerged() async {
        let a = await seeded()
        let bad = PlainCodec().seal(Array("{\"schema\":2,\"rooms\":{},\"veto\":{}}".utf8), ver: 3)!
        a.transport.scriptedGets = [.blob(ver: 3, pub: "PUB", data: bad, epoch: server.epoch, now: server.now)]
        let before = a.coordinator.state.doc
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .invalidRemote)
        XCTAssertEqual(a.coordinator.state.doc, before)
        XCTAssertNil(a.coordinator.state.halt, "不停用，保留现场")
    }

    func testDecryptFailureHaltsOnlyAfterTwoCycles() async {
        let codec = PlainCodec()
        let a = device(dev: 1, codec: codec)
        _ = await a.coordinator.runCycle()
        codec.failOpen = true
        let first = await a.coordinator.runCycle()
        XCTAssertEqual(first, .decryptFailed)
        XCTAssertNil(a.coordinator.state.halt)
        codec.failOpen = false
        _ = await a.coordinator.runCycle()
        XCTAssertEqual(a.coordinator.state.decryptFailures, 0, "成功一次清零")
        codec.failOpen = true
        _ = await a.coordinator.runCycle()
        let second = await a.coordinator.runCycle()
        XCTAssertEqual(second, .halted(.decryptFailed))
    }

    // MARK: 时钟

    func testOnlineClockSkewPausesWrites() async {
        let a = device(dev: 1)
        _ = await a.coordinator.runCycle()
        a.wall.now = server.now + 2 * 3_600_000
        _ = a.coordinator.perform(.favorite("alice"))
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .clockSkew)
        XCTAssertEqual(server.puts.count, 1)
    }

    func testOfflineFastClockIsRestampedBeforeSend() async {
        let a = device(dev: 1)
        _ = await a.coordinator.runCycle()
        a.wall.now = server.now + 5 * 3_600_000 // 离线期间本机时钟快了 5 小时
        _ = a.coordinator.perform(.unfavorite("nobody"))
        _ = a.coordinator.perform(.favorite("alice"))
        a.wall.now = server.now + 1_000 // 上线前校准
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .pushed)
        let sent = server.document!
        XCTAssertLessThanOrEqual(sent.rooms["cb:alice"]!.f!.hlc.l, server.now + 3_600_000)
        XCTAssertEqual(a.coordinator.state.maxSentL, sent.rooms["cb:alice"]!.f!.hlc.l)
    }

    // MARK: 体积

    func testOversizeMergeStopsPushing() async {
        let b = device(dev: 2)
        for i in 0..<40 { _ = b.coordinator.perform(.favorite(String(format: "user%03d", i))) }
        _ = await b.coordinator.runCycle()
        let limit = server.document!.encodedSize + 200
        let a = device(dev: 1, sizeLimit: limit)
        for i in 0..<10 { _ = a.coordinator.perform(.favorite(String(format: "mine%03d", i))) }
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .oversize)
        XCTAssertTrue(a.coordinator.state.oversize)
        XCTAssertEqual(SyncProjection(a.coordinator.state.doc).favorites.count, 50, "合并结果保留在本机")
        XCTAssertEqual(server.puts.count, 1, "停止推送")
    }

    func testOversizeLocalOpIsRejected() {
        let a = device(dev: 1, sizeLimit: 200)
        let first = a.coordinator.perform(.favorite("averyveryverylongname_0001"))
        guard case .applied = first.outcome else { return XCTFail("第一个放得下") }
        let before = a.coordinator.state
        let full = a.coordinator.perform(.favorite("averyveryverylongname_0002"))
        XCTAssertEqual(full.outcome, .full)
        XCTAssertEqual(a.coordinator.state, before, "拒绝的操作：文档、时钟与 gen 都不变")
    }

    // MARK: 取消与落盘失败

    func testCancelledResponseIsDropped() async {
        let a = device(dev: 1)
        _ = a.coordinator.perform(.favorite("alice"))
        a.transport.beforeGetReturns = { a.coordinator.cancel() }
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .cancelled)
        XCTAssertEqual(server.puts.count, 0)
        XCTAssertNil(a.coordinator.state.epoch, "迟到响应不写状态")
    }

    func testStateSaveFailureBlocksPut() async {
        let a = device(dev: 1)
        a.store.failSaves = true
        let local = a.coordinator.perform(.favorite("alice"))
        XCTAssertFalse(local.saved, "保存失败要提示")
        XCTAssertTrue(SyncProjection(a.coordinator.state.doc).isFavorite("cb:alice"), "操作在内存中仍生效")
        let r = await a.coordinator.runCycle()
        XCTAssertEqual(r, .storageFailed)
        XCTAssertEqual(server.puts.count, 0, "在途请求没落盘就不发 PUT")
    }
}
