import XCTest
@testable import VelaCore

/// 第 12 节：启动检查表每一行、投影落盘屏障与故障注入、关闭与清除、加入恢复。
final class SyncLifecycleTests: XCTestCase {
    typealias F = SyncFixture
    let blob = String(repeating: "a", count: 64)
    let other = String(repeating: "b", count: 64)

    func state(_ phase: SyncPhase, doc: SyncDocument = .empty) -> SyncState {
        SyncState(phase: phase, origin: "https://sync.example.org", blobID: blob, clock: SyncClock(dev: 1), doc: doc)
    }

    func sampleDoc() -> SyncDocument {
        var d = SyncDocument()
        d.rooms["cb:alice"] = SyncRoom(f: F.reg(5, "+"), n: F.reg(5, "Alice"))
        d.rooms["xx:bob"] = SyncRoom(f: F.reg(6, "+"))  // 本端不认识的站点（c4 已是合法站点，第 23 节第 15 条）
        d.veto["t:asian"] = F.reg(4, "+")
        d.settings["publicOnly"] = F.reg(3, "0")
        return d
    }

    // MARK: 第 12.1 节表格

    func testStartupTableEveryRow() {
        let present = SyncKeychainRead.present(blobID: blob)
        let cases: [(SyncStateRead, SyncKeychainRead, SyncStartupAction)] = [
            (.none, .none, .notEnabled),
            (.none, present, .orphanEntry),
            (.present(state(.creating)), .none, .discardCreating),
            (.present(state(.creating)), present, .resumeCreating),
            (.present(state(.active)), .none, .keyLost),
            (.present(state(.active)), .invalidated, .keyLost),
            (.present(state(.active)), present, .normal),
            (.present(state(.disabling)), .none, .finishDisabling),
            (.present(state(.disabling)), present, .finishDisabling),
            (.present(state(.clearing)), present, .finishClearing),
            (.present(state(.clearing)), .none, .finishClearingLocally),
            (.present(state(.active)), .present(blobID: other), .mismatch),
            (.present(state(.creating)), .present(blobID: other), .mismatch),
            (.present(state(.clearing)), .present(blobID: other), .mismatch),
            (.present(state(.active)), .unavailable, .skip),
            (.none, .unavailable, .skip),
            (.corrupted, present, .stateCorrupted),
        ]
        for (s, k, expected) in cases {
            XCTAssertEqual(SyncLifecycle.startupAction(state: s, keychain: k), expected, "\(s) / \(k)")
        }
    }

    func recover(_ s: SyncState?, _ k: SyncKeychainRead, sink: MemorySink = MemorySink(), server: MemoryServer = MemoryServer()) async
        -> (SyncStartupAction, SyncFlowResult?, MemoryStateStore, MemoryKeychain) {
        let store = MemoryStateStore(s)
        let keychain = MemoryKeychain(k)
        let r = await SyncLifecycle.recover(store: store, keychain: keychain, sink: sink, transport: MemoryTransport(server))
        return (r.action, r.result, store, keychain)
    }

    func testRecoverDiscardsUnfinishedCreate() async {
        let (action, result, store, _) = await recover(state(.creating), .none)
        XCTAssertEqual(result, .done, "\(action)")
        XCTAssertNil(store.stored)
    }

    /// 加入是一次性事务：只剩钥匙串条目没有状态，说明提交在写状态之前中断，删掉条目即未加入（第 12.1、12.4 节）。
    func testRecoverDeletesOrphanEntry() async {
        let (action, result, store, keychain) = await recover(nil, .present(blobID: blob))
        XCTAssertEqual(action, .orphanEntry)
        XCTAssertEqual(result, .done)
        XCTAssertNil(store.stored)
        XCTAssertEqual(keychain.entry, .none)
    }



    func testRecoverLeavesUserChoicesAlone() async {
        for (s, k) in [(state(.active), SyncKeychainRead.none), (state(.active), .present(blobID: other)), (state(.creating), .present(blobID: blob)), (state(.active), .unavailable)] {
            let (_, result, store, keychain) = await recover(s, k)
            XCTAssertNil(result)
            XCTAssertEqual(store.stored, s)
            XCTAssertEqual(keychain.entry, k)
        }
    }

    func testRecoverFinishDisablingRunsBarrierFirst() async {
        let sink = MemorySink()
        let (action, result, store, keychain) = await recover(state(.disabling, doc: sampleDoc()), .present(blobID: blob), sink: sink)
        XCTAssertEqual(action, .finishDisabling)
        XCTAssertEqual(result, .done)
        XCTAssertEqual(sink.curation?.favorites, ["Alice"])
        XCTAssertNil(store.stored)
        XCTAssertEqual(keychain.entry, .none)
    }

    func testRecoverFinishClearingDeletesRemote() async {
        let server = MemoryServer()
        server.ver = 4
        server.pub = "PUB"
        server.data = PlainCodec.envelope(.empty, ver: 4)
        var s = state(.clearing, doc: sampleDoc())
        s.lastSeenVer = 3 // 409 后用响应里的版本重试
        let (action, result, store, keychain) = await recover(s, .present(blobID: blob), server: server)
        XCTAssertEqual(action, .finishClearing)
        XCTAssertEqual(result, .done)
        XCTAssertNotNil(server.deletedAt)
        XCTAssertNil(store.stored)
        XCTAssertEqual(keychain.entry, .none)
    }

    func testRecoverClearingWithoutEntryFinishesLocally() async {
        let sink = MemorySink()
        let server = MemoryServer()
        let (action, result, store, _) = await recover(state(.clearing, doc: sampleDoc()), .none, sink: sink, server: server)
        XCTAssertEqual(action, .finishClearingLocally)
        XCTAssertEqual(result, .done)
        XCTAssertNil(store.stored)
        XCTAssertEqual(sink.curation?.favorites, ["Alice"])
        XCTAssertEqual(server.gets, 0, "没有钥匙串就不碰云端")
    }

    func testRecoverBarrierFailureLeavesEverything() async {
        for phase in [SyncPhase.disabling, .clearing] {
            let sink = MemorySink()
            sink.failTaste = true
            let s = state(phase, doc: sampleDoc())
            let (_, result, store, keychain) = await recover(s, .present(blobID: blob), sink: sink)
            XCTAssertEqual(result, .localSaveFailed)
            XCTAssertEqual(store.stored, s, "同步状态不动")
            XCTAssertEqual(keychain.entry, .present(blobID: blob), "钥匙串不动")
        }
    }

    // MARK: 屏障

    func testBarrierProjectsAndSaves() {
        let sink = MemorySink()
        sink.taste = Taste().vetoing("t:milf", now: Date(timeIntervalSince1970: 0)).vetoing(Taste.tagKey("big tits"), now: Date(timeIntervalSince1970: 0))
        XCTAssertTrue(SyncLifecycle.barrier(sampleDoc(), sink: sink))
        XCTAssertEqual(sink.cancelled, 1, "先取消延迟保存")
        XCTAssertEqual(sink.curation, Curation(favorites: ["Alice"]), "未知站点不投影")
        XCTAssertTrue(sink.taste.isVetoed("t:asian"))
        XCTAssertFalse(sink.taste.isVetoed("t:milf"))
        XCTAssertTrue(sink.taste.isVetoed(Taste.tagKey("big tits")))
        XCTAssertEqual(sink.settings, ["publicOnly": false])
        XCTAssertTrue(SyncLifecycle.barrier(sampleDoc(), sink: sink), "可重复执行")
    }

    func testDisableBarrierFailureChangesNothing() {
        for failCuration in [true, false] {
            let sink = MemorySink()
            sink.failCuration = failCuration
            sink.failTaste = !failCuration
            let s = state(.active, doc: sampleDoc())
            let store = MemoryStateStore(s), keychain = MemoryKeychain(.present(blobID: blob))
            XCTAssertEqual(SyncLifecycle.disable(s, store: store, keychain: keychain, sink: sink), .localSaveFailed)
            XCTAssertEqual(store.stored, s)
            XCTAssertEqual(store.saves, 0)
            XCTAssertEqual(keychain.entry, .present(blobID: blob))
        }
    }

    func testDisableOrderAndInterruptedRecovery() async {
        let s = state(.active, doc: sampleDoc())
        let store = MemoryStateStore(s), keychain = MemoryKeychain(.present(blobID: blob)), sink = MemorySink()
        keychain.failDelete = true // 在「删钥匙串」这一步中断
        XCTAssertEqual(SyncLifecycle.disable(s, store: store, keychain: keychain, sink: sink), .storageFailed)
        XCTAssertEqual(store.stored?.phase, .disabling, "删除同步状态放在最后一步")
        keychain.failDelete = false
        let r = await SyncLifecycle.recover(store: store, keychain: keychain, sink: sink, transport: MemoryTransport(MemoryServer()))
        XCTAssertEqual(r.action, .finishDisabling)
        XCTAssertEqual(r.result, .done)
        XCTAssertNil(store.stored)
        XCTAssertEqual(keychain.entry, .none)
    }

    /// 复审 medium：还没有 epoch 不能证明空间不存在（另一台设备可能已用同一码创建）。先 GET 核实：
    /// 404 或 410 才本机关闭；空间存在就取它的 epoch 与版本签名删除；网络失败保持 clearing。
    func testClearWithoutEpochChecksServerFirst() async {
        let s = state(.active, doc: sampleDoc())
        XCTAssertNil(s.epoch)
        // 云端没有：GET 404，不发 delete，本机关闭。
        do {
            let store = MemoryStateStore(s), keychain = MemoryKeychain(.present(blobID: blob)), transport = MemoryTransport(MemoryServer())
            let result = await SyncLifecycle.clear(s, store: store, keychain: keychain, sink: MemorySink(), transport: transport)
            XCTAssertEqual(result, .done)
            XCTAssertEqual(transport.deletes, [])
            XCTAssertNil(store.stored)
        }
        // 云端有（别的设备创建的）：按 GET 到的版本签名删除。
        do {
            let server = MemoryServer()
            server.ver = 5
            server.pub = "PUB"
            server.data = PlainCodec.envelope(.empty, ver: 5)
            let store = MemoryStateStore(s), keychain = MemoryKeychain(.present(blobID: blob)), transport = MemoryTransport(server)
            let result = await SyncLifecycle.clear(s, store: store, keychain: keychain, sink: MemorySink(), transport: transport)
            XCTAssertEqual(result, .done)
            XCTAssertEqual(transport.deletes, [5])
            XCTAssertNotNil(server.deletedAt)
        }
        // 网络失败：保持 clearing，钥匙串不动。
        do {
            let store = MemoryStateStore(s), keychain = MemoryKeychain(.present(blobID: blob)), transport = MemoryTransport(MemoryServer())
            transport.scriptedGets = [.transient]
            let result = await SyncLifecycle.clear(s, store: store, keychain: keychain, sink: MemorySink(), transport: transport)
            XCTAssertEqual(result, .remotePending)
            XCTAssertEqual(store.stored?.phase, .clearing)
            XCTAssertEqual(keychain.entry, .present(blobID: blob))
        }
    }

    func testClearKeepsClearingWhenNetworkFails() async {
        let server = MemoryServer()
        server.ver = 2
        server.pub = "PUB"
        server.data = PlainCodec.envelope(.empty, ver: 2)
        var s = state(.active, doc: sampleDoc())
        s.lastSeenVer = 2
        let store = MemoryStateStore(s), keychain = MemoryKeychain(.present(blobID: blob)), sink = MemorySink()
        let transport = MemoryTransport(server)
        transport.failDeletes = 1
        let first = await SyncLifecycle.clear(s, store: store, keychain: keychain, sink: sink, transport: transport)
        XCTAssertEqual(first, .remotePending)
        XCTAssertEqual(store.stored?.phase, .clearing)
        XCTAssertEqual(keychain.entry, .present(blobID: blob))
        let second = await SyncLifecycle.recover(store: store, keychain: keychain, sink: sink, transport: transport)
        XCTAssertEqual(second.result, .done)
        XCTAssertNotNil(server.deletedAt)
        // 响应丢失后重试得到 410 也视为已完成。
        let again = await SyncLifecycle.finishClear(s, store: MemoryStateStore(s), keychain: MemoryKeychain(.present(blobID: blob)), transport: transport)
        XCTAssertEqual(again, .done)
    }

    // MARK: 加入（第 12.4 节）



    func testCompleteCreateStampsAll() {
        let local = SyncLocalData(curation: Curation(favorites: ["alice", "x"]), vetoedKeys: ["g:m"])
        let (created, report) = SyncLifecycle.completeCreate(state(.creating), local: local, pt: 100)
        XCTAssertEqual(created.phase, .active)
        XCTAssertEqual(report.invalidNames, ["x"])
        XCTAssertEqual(SyncProjection(created.doc).veto, ["g:m": true])
    }
}
