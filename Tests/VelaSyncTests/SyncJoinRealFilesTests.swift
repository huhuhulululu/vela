#if canImport(CryptoKit) && os(macOS)
import Foundation
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 加入提交又一轮修复（PR #11，grok 与 sol 两路审计 H1–H6）：一律用真实文件存储（`SyncStateFileStore`、
/// `FileJoinJournal`、`CurationStore`、`TasteStore`）与真实 `DeferredSaver`，不用总返回固定值的内存桩。
final class SyncJoinRealFilesTests: XCTestCase {
    /// 两个同步设置（真实实现在 UserDefaults）：快照与投影共用这一份。
    final class Settings { var values: [String: Bool] = [:] }

    /// 状态文件存储外面套一层钩子：保存后可执行注入动作、可让保存失败。
    final class HookedStore: SyncStateStore {
        let inner: SyncStateStore
        var failSave = false
        var afterSave: ((SyncState) -> Void)?
        init(_ inner: SyncStateStore) { self.inner = inner }
        func load() -> SyncStateRead { inner.load() }
        func save(_ state: SyncState) -> Bool {
            guard !failSave, inner.save(state) else { return false }
            afterSave?(state)
            return true
        }
        func delete() -> Bool { inner.delete() }
    }

    /// 与 App 的 `SyncModelSink` 同构：内存里的收藏与口味经 `DeferredSaver` 延迟落盘，屏障直接调 store。
    final class FileSink: SyncJoinSink {
        let curationStore: CurationStore
        let tasteStore: TasteStore
        let curationSaver: DeferredSaver<Curation>
        let tasteSaver: DeferredSaver<Taste>
        let settings: Settings
        var curation: Curation
        var taste: Taste
        /// 口味保存前调用；返回 false 表示这次保存失败。
        var tasteSaveHook: (() -> Bool)?

        init(dir: URL, settings: Settings) {
            let c = CurationStore(url: dir.appendingPathComponent("feed-curation.json"))
            let t = TasteStore(url: dir.appendingPathComponent("feed-taste.json"))
            curationStore = c
            tasteStore = t
            curationSaver = DeferredSaver { _ = c.save($0) }
            tasteSaver = DeferredSaver { _ = t.save($0) }
            self.settings = settings
            curation = c.load()
            taste = t.load()
        }

        /// 用户编辑：内存先变，文件 2 秒后才写。
        func edit(_ next: Curation) {
            curation = next
            curationSaver.schedule(next)
        }

        var local: SyncLocalData { SyncLocalData(curation: curation, taste: taste, explicitSettings: [:]) }

        func cancelPendingSaves() {
            curationSaver.flush()
            tasteSaver.flush()
        }
        func currentTaste() -> Taste { taste }
        func saveCuration(_ c: Curation) -> Bool { curationStore.save(c) }
        func saveTaste(_ t: Taste) -> Bool {
            if let hook = tasteSaveHook, !hook() { return false }
            return tasteStore.save(t)
        }
        func applySettings(_ values: [String: Bool]) { settings.values.merge(values) { $1 } }

        func persistPending() -> Bool {
            cancelPendingSaves()
            return curationStore.save(curation) && tasteStore.save(taste)
        }

        func reloadAfterRollback() {
            curation = curationStore.load()
            taste = tasteStore.load()
        }
    }

    final class Rig {
        let dir: URL
        let settings = Settings()
        let keys = MemoryKeyStore()
        let t = ScriptedTransport()
        let sink: FileSink
        var store: HookedStore
        var journal: FileJoinJournal
        var service: SyncService
        /// 当前这次「启动」的状态文件存储（带损坏标记）；`configureBacking` 在每次重启时对它生效。
        var backing: SyncBackupExcludedStore!
        var configureBacking: ((SyncBackupExcludedStore) -> Void)?

        var stateURL: URL { dir.appendingPathComponent("sync-state.json") }
        var snapshotDir: URL { dir.appendingPathComponent("sync-join-snapshot") }

        init(dir: URL) {
            self.dir = dir
            sink = FileSink(dir: dir, settings: settings)
            store = HookedStore(NoopStore())
            journal = FileJoinJournal(dir: dir, files: [], settingKeys: [], readSettings: { [:] }, writeSettings: { _ in })
            service = SyncService(env: SyncService.Environment(stateStore: store, keyStore: keys, makeTransport: { _, _ in ScriptedTransport() }))
            restart()
        }

        /// 模拟 App 重启：重新造状态存储、快照与服务（文件都还在）。
        func restart() {
            backing = SyncBackupExcludedStore(SyncStateFileStore(url: stateURL))
            configureBacking?(backing)
            store = HookedStore(backing)
            let settings = self.settings
            journal = FileJoinJournal(dir: snapshotDir, files: [sink.curationStore.url, sink.tasteStore.url],
                                      settingKeys: ["publicOnly", "infoAlwaysOn"],
                                      readSettings: { settings.values },
                                      writeSettings: { values in for (k, v) in values { settings.values[k] = v } })
            let transport = t
            service = SyncService(env: SyncService.Environment(
                stateStore: store, keyStore: keys, makeTransport: { _, _ in transport }, journal: journal,
                wallClock: { 1_800_000_000_000 },
                random: { n in n == 32 ? ServiceFixture.key : [UInt8](repeating: 1, count: n) }))
        }

        func ticket() async -> SyncJoinTicket? {
            t.gets = [ServiceFixture.blob(ServiceFixture.remoteDoc(favorites: ["zed"]), ver: 3)]
            t.puts = [.stored(ver: 4, epoch: ServiceFixture.epoch, now: 1_800_000_000_000)]
            guard case .preview(let ticket) = await service.prepareJoin(ServiceFixture.code, local: sink.local) else { return nil }
            return ticket
        }

        func commit(_ ticket: SyncJoinTicket) async -> SyncJoinCommit {
            await service.commitJoin(ticket, local: { self.sink.local }, sink: sink)
        }

        func diskFavorites() -> Set<String> { Set(sink.curationStore.load().favorites) }
    }

    final class NoopStore: SyncStateStore {
        func load() -> SyncStateRead { .none }
        func save(_ state: SyncState) -> Bool { true }
        func delete() -> Bool { true }
    }

    func rig() throws -> Rig {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("join-real-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock {
            for d in [dir, dir.appendingPathComponent("sync-join-snapshot")] {
                try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: d.path)
            }
            try? FileManager.default.removeItem(at: dir)
        }
        XCTAssertTrue(CurationStore(url: dir.appendingPathComponent("feed-curation.json")).save(Curation(favorites: ["alice"])))
        let r = Rig(dir: dir)
        r.settings.values = ["publicOnly": true]
        return r
    }

    func chmod(_ url: URL, _ mode: Int) {
        XCTAssertNoThrow(try FileManager.default.setAttributes([.posixPermissions: mode], ofItemAtPath: url.path))
    }

    // MARK: H1 快照时机

    /// 保存队列里还没落盘的收藏（2 秒内的编辑），回滚后不能丢：快照之前先落盘，回滚后内存按文件重读。
    func testH1_pendingEditBeforeCommitSurvivesRollback() async throws {
        let r = try rig()
        r.sink.edit(Curation(favorites: ["alice", "bob"]))
        XCTAssertEqual(r.diskFavorites(), ["alice"], "前提：bob 还在保存队列里")
        guard let t = await r.ticket() else { return XCTFail() }
        r.store.failSave = true
        guard case .failed = await r.commit(t) else { return XCTFail() }
        XCTAssertEqual(r.diskFavorites(), ["alice", "bob"], "换回的是提交那一刻的本机数据，包括还没落盘的编辑")
        XCTAssertEqual(Set(r.sink.curation.favorites), r.diskFavorites(), "内存与磁盘一致")
        r.sink.cancelPendingSaves()
        XCTAssertEqual(r.diskFavorites(), ["alice", "bob"], "保存队列里没有会覆盖换回结果的旧值")
        XCTAssertNil(r.keys.entry)
        XCTAssertEqual(r.store.load(), .none)
        XCTAssertFalse(r.journal.exists)
    }

    // MARK: H2 已提交快照残留

    /// 成功提交后快照删不掉：关闭同步必须先清掉它（清不掉就不关），下次启动也绝不能把加入前的旧文件换回。
    func testH2_leftoverSnapshotAfterJoinIsNeverRestored() async throws {
        let r = try rig()
        guard let t = await r.ticket() else { return XCTFail() }
        let snapshotDir = r.snapshotDir
        r.store.afterSave = { [weak self] state in
            if state.phase == .active { self?.chmod(snapshotDir, 0o500) }
        }
        let got1 = await r.commit(t)
        XCTAssertEqual(got1, .joined)
        r.store.afterSave = nil
        XCTAssertTrue(r.journal.exists, "前提：快照没删掉")
        XCTAssertEqual(r.diskFavorites(), ["alice", "zed"])

        // 快照还删不掉时，关闭同步不得继续（否则状态删了、快照留着，下次启动会换回旧文件）。
        XCTAssertNotEqual(r.service.disable(sink: r.sink), .done)
        XCTAssertNotNil(r.keys.entry, "没关成：钥匙串原样")
        guard case .present = r.store.load() else { return XCTFail("没关成：同步状态原样") }

        chmod(snapshotDir, 0o700)
        XCTAssertEqual(r.service.disable(sink: r.sink), .done)
        XCTAssertFalse(r.journal.exists, "关闭流程一并清理残留快照")
        r.restart()
        let got2 = await r.service.start(local: { r.sink.local }, sink: r.sink)
        XCTAssertEqual(got2, .notEnabled)
        XCTAssertEqual(r.diskFavorites(), ["alice", "zed"], "不换回加入前的旧文件")
    }

    /// 已提交的快照（带完成标记）只能删除：即使同步状态已被删掉，启动也不换回、不删 K。
    func testH2_committedSnapshotOnlyDiscardedAtStartup() async throws {
        let r = try rig()
        XCTAssertTrue(r.journal.snapshot())
        XCTAssertTrue(r.journal.markCommitted())
        XCTAssertEqual(r.journal.status, .committed)
        XCTAssertTrue(r.sink.curationStore.save(Curation(favorites: ["alice", "zed"])))
        r.restart()
        _ = await r.service.start(local: { r.sink.local }, sink: r.sink)
        XCTAssertEqual(r.diskFavorites(), ["alice", "zed"])
        XCTAssertFalse(r.journal.exists)
    }

    // MARK: H3 需要修复期间隔离

    /// 回滚失败（需要修复）之后：本机写操作继续被拒，重启也一样；新建流程不得绕过；「关闭同步」执行待完成的恢复。
    func testH3_needsRepairKeepsWritesLockedUntilRollbackCompletes() async throws {
        let r = try rig()
        guard let t = await r.ticket() else { return XCTFail() }
        let dir = r.dir
        let journal = r.journal
        // 屏障写口味时失败，同时让目录只读：换回快照的 rename 失败。
        r.sink.tasteSaveHook = { [weak self] in
            guard journal.exists else { return true }
            self?.chmod(dir, 0o500)
            return false
        }
        guard case .needsRepair = await r.commit(t) else { return XCTFail() }
        r.sink.tasteSaveHook = nil
        XCTAssertFalse(r.service.isCommittingJoin)
        XCTAssertFalse(r.service.localWriteAllowed, "待修复期间偏好写入继续被拒")

        r.restart()
        guard case .needsRepair = await r.service.start(local: { r.sink.local }, sink: r.sink) else { return XCTFail() }
        XCTAssertFalse(r.service.localWriteAllowed, "重启后仍被拒")

        chmod(dir, 0o700)
        guard case .failed = await r.service.create(origin: ServiceFixture.origin, local: r.sink.local) else {
            return XCTFail("新建流程不得绕过残留快照")
        }
        XCTAssertEqual(r.store.load(), .none, "新建被拒：没有写同步状态")

        XCTAssertEqual(r.service.disable(sink: r.sink), .done, "关闭同步 = 重试待完成的恢复")
        XCTAssertEqual(r.diskFavorites(), ["alice"], "换回加入前的本机数据")
        XCTAssertEqual(Set(r.sink.curation.favorites), ["alice"], "内存按换回的文件重读")
        XCTAssertNil(r.keys.entry)
        XCTAssertEqual(r.store.load(), .none)
        XCTAssertFalse(r.journal.exists)
        XCTAssertTrue(r.service.localWriteAllowed)
    }

    // MARK: H4 状态损坏

    /// 状态文件损坏：第一次启动把它改名隔离，第二次启动也必须认出「损坏」并保留 K，直到用户明确关闭。
    func testH4_corruptedStateKeepsKeyAcrossTwoLaunches() async throws {
        let r = try rig()
        try Data("{not json".utf8).write(to: r.stateURL)
        r.keys.entry = SyncKeychainEntry(key: ServiceFixture.key, dev: 7, blobID: ServiceFixture.keys.blobID)
        let got3 = await r.service.start(local: { r.sink.local }, sink: r.sink)
        XCTAssertEqual(got3, .attention(.stateCorrupted))
        r.restart()
        let got4 = await r.service.start(local: { r.sink.local }, sink: r.sink)
        XCTAssertEqual(got4, .attention(.stateCorrupted), "第二次启动仍是损坏")
        XCTAssertNotNil(r.keys.entry, "K 保留")
        r.restart()
        let got5 = await r.service.start(local: { r.sink.local }, sink: r.sink)
        XCTAssertEqual(got5, .attention(.stateCorrupted), "第三次也一样")
        XCTAssertNotNil(r.keys.entry)

        // 用户明确关闭：删 K 并清掉损坏标记，之后是未开启。
        XCTAssertEqual(r.service.disable(sink: r.sink), .done)
        XCTAssertNil(r.keys.entry)
        r.restart()
        let got6 = await r.service.start(local: { r.sink.local }, sink: r.sink)
        XCTAssertEqual(got6, .notEnabled)
    }

    /// 损坏标记写不成（磁盘满）：原文件不能被改名隔离掉，之后连续启动仍认出损坏、K 仍在。
    func testH4a_markerWriteFailureKeepsCorruptFileAndKey() async throws {
        let r = try rig()
        try Data("{not json".utf8).write(to: r.stateURL)
        r.keys.entry = SyncKeychainEntry(key: ServiceFixture.key, dev: 7, blobID: ServiceFixture.keys.blobID)
        r.configureBacking = { $0.writeMarker = { _ in false } }
        r.restart()
        let first = await r.service.start(local: { r.sink.local }, sink: r.sink)
        XCTAssertEqual(first, .attention(.stateCorrupted))
        XCTAssertTrue(FileManager.default.fileExists(atPath: r.stateURL.path), "标记没写成：坏文件留在原处")
        for launch in 2...3 {
            r.restart()
            let again = await r.service.start(local: { r.sink.local }, sink: r.sink)
            XCTAssertEqual(again, .attention(.stateCorrupted), "第 \(launch) 次启动")
            XCTAssertNotNil(r.keys.entry, "第 \(launch) 次启动后 K 仍在")
        }
        r.configureBacking = nil
        for launch in 4...5 {
            r.restart()
            let again = await r.service.start(local: { r.sink.local }, sink: r.sink)
            XCTAssertEqual(again, .attention(.stateCorrupted), "磁盘恢复后第 \(launch) 次启动")
            XCTAssertNotNil(r.keys.entry)
        }
    }

    /// 改名隔离之后进程立刻退出：标记必须已经先落盘（写标记时坏文件还在原处），之后连续启动 K 仍在。
    func testH4a_markerPersistedBeforeQuarantineSurvivesExit() async throws {
        let r = try rig()
        try Data("{not json".utf8).write(to: r.stateURL)
        r.keys.entry = SyncKeychainEntry(key: ServiceFixture.key, dev: 7, blobID: ServiceFixture.keys.blobID)
        let stateURL = r.stateURL
        var fileWhenMarked: [Bool] = []
        r.configureBacking = { store in
            store.writeMarker = { url in
                fileWhenMarked.append(FileManager.default.fileExists(atPath: stateURL.path))
                return FileManager.default.createFile(atPath: url.path, contents: Data())
            }
        }
        r.restart()
        _ = r.backing.load()
        XCTAssertEqual(fileWhenMarked.first, true, "标记先于改名持久化：之后任何时刻退出，磁盘上都已有标记")
        XCTAssertFalse(FileManager.default.fileExists(atPath: stateURL.path), "前提：坏文件已改名隔离")
        // 模拟此刻进程退出：不再调用这个实例的任何方法，直接重启。
        r.configureBacking = nil
        for launch in 2...3 {
            r.restart()
            let again = await r.service.start(local: { r.sink.local }, sink: r.sink)
            XCTAssertEqual(again, .attention(.stateCorrupted), "第 \(launch) 次启动")
            XCTAssertNotNil(r.keys.entry, "第 \(launch) 次启动后 K 仍在")
        }
    }

    /// 状态损坏时关闭：先清状态与损坏标记，最后删 K。标记删不掉就不删 K（不留「有标记无 K」的卡死状态）。
    func testH4b_disableClearsMarkerBeforeDeletingKey() async throws {
        let r = try rig()
        try Data("{not json".utf8).write(to: r.stateURL)
        r.keys.entry = SyncKeychainEntry(key: ServiceFixture.key, dev: 7, blobID: ServiceFixture.keys.blobID)
        let first = await r.service.start(local: { r.sink.local }, sink: r.sink)
        XCTAssertEqual(first, .attention(.stateCorrupted))
        chmod(r.dir, 0o500)
        XCTAssertEqual(r.service.disable(sink: r.sink), .storageFailed)
        XCTAssertNotNil(r.keys.entry, "标记没删掉：K 必须还在，之后可以重试关闭")
        chmod(r.dir, 0o700)
        r.restart()
        let again = await r.service.start(local: { r.sink.local }, sink: r.sink)
        XCTAssertEqual(again, .attention(.stateCorrupted))
        XCTAssertEqual(r.service.disable(sink: r.sink), .done)
        XCTAssertNil(r.keys.entry)
        XCTAssertFalse(FileManager.default.fileExists(atPath: r.backing.corruptMarker.path))
        r.restart()
        let after = await r.service.start(local: { r.sink.local }, sink: r.sink)
        XCTAssertEqual(after, .notEnabled)
    }

    /// K 删不掉（标记已清）：下次启动「无状态、有条目」走孤儿条目清理，不会卡死在损坏。
    func testH4b_keyDeleteFailureFallsBackToOrphanCleanup() async throws {
        let r = try rig()
        try Data("{not json".utf8).write(to: r.stateURL)
        r.keys.entry = SyncKeychainEntry(key: ServiceFixture.key, dev: 7, blobID: ServiceFixture.keys.blobID)
        _ = await r.service.start(local: { r.sink.local }, sink: r.sink)
        r.keys.failDelete = true
        XCTAssertEqual(r.service.disable(sink: r.sink), .storageFailed)
        r.keys.failDelete = false
        r.restart()
        let again = await r.service.start(local: { r.sink.local }, sink: r.sink)
        XCTAssertEqual(again, .notEnabled, "标记已清：不再是损坏")
        XCTAssertNil(r.keys.entry, "孤儿条目被清理")
    }

    // MARK: H7 标记写不成时完好状态照常读出

    /// 损坏标记写不成（磁盘满、配额）而状态文件是完好的：不得报「状态损坏」（否则用户按提示关闭会删掉未读的状态与 K），
    /// 要照常读出；之后的关闭走正常流程。
    func testH7_markerWriteFailureStillReadsValidState() async throws {
        let r = try rig()
        var state = SyncState(phase: .active, origin: ServiceFixture.origin, blobID: ServiceFixture.keys.blobID, clock: SyncClock(dev: 7))
        state.epoch = ServiceFixture.epoch
        XCTAssertTrue(SyncStateFileStore(url: r.stateURL).save(state))
        r.keys.entry = SyncKeychainEntry(key: ServiceFixture.key, dev: 7, blobID: ServiceFixture.keys.blobID)
        r.configureBacking = { $0.writeMarker = { _ in false } }
        r.restart()
        guard case .present(let read) = r.backing.load() else { return XCTFail("完好状态必须读出") }
        XCTAssertEqual(read.phase, .active)
        let started = await r.service.start(local: { r.sink.local }, sink: r.sink)
        XCTAssertEqual(started, .active, "标记写不成不影响读完好的状态")
        XCTAssertTrue(FileManager.default.fileExists(atPath: r.stateURL.path))
        XCTAssertNotNil(r.keys.entry)
        // 关闭：状态确被读出，走正常关闭流程（屏障 → disabling → 删 K → 删状态）。
        XCTAssertEqual(r.service.disable(sink: r.sink), .done)
        XCTAssertNil(r.keys.entry)
    }

    // MARK: H8 提交锁保持到激活之后

    /// 加入成功后，调用方要求保持提交锁时：返回 `.joined` 后锁仍在（激活界面之前本机写操作照样被拒），释放后才放行。
    func testH8_commitLockCanBeHeldUntilCallerActivates() async throws {
        let r = try rig()
        guard let t = await r.ticket() else { return XCTFail() }
        let result = await r.service.commitJoin(t, local: { r.sink.local }, sink: r.sink, holdLockAfterJoin: true)
        XCTAssertEqual(result, .joined)
        XCTAssertTrue(r.service.isCommittingJoin, "激活之前锁仍在")
        XCTAssertFalse(r.service.localWriteAllowed)
        r.service.releaseJoinCommit()
        XCTAssertTrue(r.service.localWriteAllowed)
    }

    /// 失败与取消照旧在返回前释放锁，即使调用方要求保持。
    func testH8_failureReleasesLockEvenWhenHoldRequested() async throws {
        let r = try rig()
        guard let t = await r.ticket() else { return XCTFail() }
        r.store.failSave = true
        guard case .failed = await r.service.commitJoin(t, local: { r.sink.local }, sink: r.sink, holdLockAfterJoin: true) else { return XCTFail() }
        XCTAssertFalse(r.service.isCommittingJoin)
    }

    // MARK: H6 半份快照

    /// 启动时遇到没有完整性标记的快照目录（构建到一半）：直接删除，不换回。
    func testH6_halfSnapshotDirectoryIsDeletedNotRestored() async throws {
        let r = try rig()
        try FileManager.default.createDirectory(at: r.snapshotDir, withIntermediateDirectories: true)
        // 半份：只复制了收藏（旧数据），口味的缺席标记与设置都没写。
        XCTAssertTrue(CurationStore(url: r.snapshotDir.appendingPathComponent("feed-curation.json")).save(Curation(favorites: ["stale"])))
        XCTAssertTrue(r.sink.curationStore.save(Curation(favorites: ["alice", "bob"])))
        r.settings.values = ["publicOnly": false]
        r.restart()
        let got7 = await r.service.start(local: { r.sink.local }, sink: r.sink)
        XCTAssertEqual(got7, .notEnabled)
        XCTAssertEqual(r.diskFavorites(), ["alice", "bob"], "不把半份旧数据换回")
        XCTAssertEqual(r.settings.values, ["publicOnly": false])
        XCTAssertFalse(FileManager.default.fileExists(atPath: r.snapshotDir.path), "半份快照被删除")
    }

    /// 快照中途写失败：不留任何目录（临时目录也清掉），返回失败。
    func testH6_failedSnapshotWriteLeavesNoDirectory() throws {
        let r = try rig()
        r.journal.writeFile = { url, data in
            url.lastPathComponent == "settings.json" ? false : FileManager.default.createFile(atPath: url.path, contents: data)
        }
        XCTAssertFalse(r.journal.snapshot())
        XCTAssertFalse(r.journal.exists)
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: r.dir.path).filter { $0.hasPrefix("sync-join-snapshot") }
        XCTAssertEqual(leftovers, [], "临时目录与半份目录都不留")
    }

    /// 快照在临时目录完整构建后原子发布：发布出来的目录一定带完整性标记，状态为「未完成的提交」。
    func testH6_publishedSnapshotIsComplete() throws {
        let r = try rig()
        XCTAssertTrue(r.journal.snapshot())
        XCTAssertEqual(r.journal.status, .pending)
        XCTAssertTrue(FileManager.default.fileExists(atPath: r.snapshotDir.appendingPathComponent(FileJoinJournal.completeMarker).path))
        XCTAssertTrue(r.journal.discard())
        XCTAssertEqual(r.journal.status, .none)
    }

    // MARK: 坏文件只读清除后不重启即可重新开启

    /// 坏文件改名隔离失败让底层 `SyncStateFileStore` 置上只读标记后，用户「关闭同步」清除成功须复位只读标记，
    /// 不重启 App（沿用同一个 `SyncService` 与存储实例）即可重新开启同步。
    func testReadOnlyCorruptedStateReenablesAfterDisableWithoutRestart() async throws {
        let r = try rig()
        try Data("{not json".utf8).write(to: r.stateURL)
        r.keys.entry = SyncKeychainEntry(key: ServiceFixture.key, dev: 7, blobID: ServiceFixture.keys.blobID)
        let dir = r.dir
        r.configureBacking = { store in
            store.writeMarker = { url in
                let ok = FileManager.default.createFile(atPath: url.path, contents: Data())
                // 标记写完立刻把目录置只读，让紧接着的改名隔离失败，从而置上底层只读标记。
                try? FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
                return ok
            }
        }
        r.restart()
        let started = await r.service.start(local: { r.sink.local }, sink: r.sink)
        XCTAssertEqual(started, .attention(.stateCorrupted))
        XCTAssertTrue(FileManager.default.fileExists(atPath: r.stateURL.path), "前提：改名隔离失败，坏文件留在原处")

        chmod(dir, 0o700)
        let probe = SyncState(phase: .creating, origin: ServiceFixture.origin, blobID: ServiceFixture.keys.blobID, clock: SyncClock(dev: 7))
        XCTAssertFalse(r.backing.save(probe), "前提：底层只读标记已置上")
        // 移走坏文件而保留损坏标记：`disable` 与 `create` 的 `load()` 都会在 `SyncBackupExcludedStore` 短路，
        // 不会再调底层 `inner.load()`，从而真正检验 `inner.delete()` 复位只读标记。
        try FileManager.default.removeItem(at: r.stateURL)
        // 不调用 r.restart()：同一进程内关闭后直接重新开启。
        XCTAssertEqual(r.service.disable(sink: r.sink), .done)
        XCTAssertNil(r.keys.entry)
        r.t.gets = [.notFound(epoch: ServiceFixture.epoch, now: 1_800_000_000_000)]
        r.t.puts = [.stored(ver: 1, epoch: ServiceFixture.epoch, now: 1_800_000_000_000)]
        guard case .created = await r.service.create(origin: ServiceFixture.origin, local: r.sink.local) else {
            return XCTFail("清除损坏状态后不重启应能重新开启")
        }
        XCTAssertTrue(r.service.isActive)
    }
}
#endif
