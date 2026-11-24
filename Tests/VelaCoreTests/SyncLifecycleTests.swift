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
        d.rooms["c4:bob"] = SyncRoom(f: F.reg(6, "+"))
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

    // MARK: 剪贴板清理控制器（真实执行链路）

    private final class MemoryClipboard: SyncClipboard {
        var changeCount = 1
        var text: String?
        var concealedWrites = 0
        var clears = 0

        func writeConcealed(_ text: String) -> Int {
            self.text = text
            concealedWrites += 1
            changeCount += 1
            return changeCount
        }

        func writeOther(_ text: String) {
            self.text = text
            changeCount += 1
        }

        func clear() {
            text = nil
            clears += 1
            changeCount += 1
        }
    }

    private final class MemoryStampStore: SyncClipboardStampStore {
        var changeCount: Int?
        var token: String?
    }

    /// 审计 F：验证「复制同步码 → 关实验开关（移除同步观察者）→ 退出时剪贴板未变则清、已变则不动」的真实执行链路。
    func testPasteboardControllerClearsOnExitAfterExperimentOffOnlyWhenUnchanged() {
        let clipboard = MemoryClipboard()
        let store = MemoryStampStore()
        var syncObservers: [() -> Void] = []
        var exitHandlers: [() -> Void] = []
        var scheduled: [(TimeInterval, () -> Void)] = []
        var nextToken = 0

        let controller = SyncPasteboardController(
            clipboard: clipboard,
            store: store,
            makeToken: { nextToken += 1; return "tok-\(nextToken)" },
            scheduleExpiry: { delay, action in scheduled.append((delay, action)) },
            registerExit: { action in exitHandlers.append(action) }
        )

        // 模拟 SyncHub.wire() 注册同步观察者，复制同步码后关闭实验开关（清空 syncObservers）。
        var syncExitRan = false
        syncObservers.append { syncExitRan = true }
        controller.copySensitive("vela2:secret-code-1")
        XCTAssertEqual(clipboard.text, "vela2:secret-code-1")
        XCTAssertEqual(clipboard.concealedWrites, 1)
        XCTAssertEqual(exitHandlers.count, 1, "首次复制注册独立退出回调")
        XCTAssertEqual(scheduled.first?.0, 120)

        // 关闭实验开关：只移除同步观察者，不影响剪贴板退出回调。
        syncObservers.removeAll()
        // 触发 App 退出：剪贴板未变，必须清空同步码。
        for run in syncObservers + exitHandlers { run() }
        XCTAssertFalse(syncExitRan)
        XCTAssertNil(clipboard.text, "关闭实验开关后退出仍须清掉未变动的同步码")
        XCTAssertEqual(clipboard.clears, 1)
        XCTAssertNil(store.changeCount)
        XCTAssertNil(store.token)

        // 再次复制同步码（不重复注册退出回调），用户之后复制别的内容，再关闭开关并退出：不得清掉用户新内容。
        controller.copySensitive("vela2:secret-code-2")
        XCTAssertEqual(exitHandlers.count, 1, "重复复制不得重复注册退出观察者")
        clipboard.writeOther("user-other-text")
        for run in exitHandlers { run() }
        XCTAssertEqual(clipboard.text, "user-other-text", "用户之后复制了别的内容，退出时不动剪贴板")
        XCTAssertEqual(clipboard.clears, 1)
        XCTAssertNil(store.changeCount)
        XCTAssertNil(store.token)
    }

    func testPasteboardControllerPerCopyTokenAndLeftoverCleanup() {
        let clipboard = MemoryClipboard()
        let store = MemoryStampStore()
        var timers: [() -> Void] = []
        var nextToken = 0
        let controller = SyncPasteboardController(
            clipboard: clipboard,
            store: store,
            makeToken: { nextToken += 1; return "t\(nextToken)" },
            scheduleExpiry: { _, action in timers.append(action) },
            registerExit: { _ in }
        )

        // 连续复制两次：第一次的 120 秒到期不得清掉第二次复制；第二次到期才清。
        controller.copySensitive("code-1")
        controller.copySensitive("code-2")
        timers[0]()
        XCTAssertEqual(clipboard.text, "code-2", "旧令牌到期不提前清掉后一次复制")
        timers[1]()
        XCTAssertNil(clipboard.text)

        // 上次进程崩溃残留：重启后未变则清、已变则不动。
        let stamp = clipboard.writeConcealed("crashed-code")
        store.changeCount = stamp
        store.token = "old"
        controller.expireLeftover()
        XCTAssertNil(clipboard.text)

        let stamp2 = clipboard.writeConcealed("crashed-code-2")
        store.changeCount = stamp2
        clipboard.writeOther("kept-after-crash")
        controller.expireLeftover()
        XCTAssertEqual(clipboard.text, "kept-after-crash")
    }
}
