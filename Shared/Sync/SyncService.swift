#if canImport(CryptoKit)
import Foundation
#if SWIFT_PACKAGE
import VelaCore
#endif

/// 钥匙串的完整接口：S1 的 `SyncKeychain`（启动检查、关闭）加上读写条目本身。
protocol SyncKeyStore: SyncKeychain {
    func readEntry() -> SyncKeychainLookup
    func write(_ entry: SyncKeychainEntry) -> SyncKeychainWrite
}

extension SyncKeychainStore: SyncKeyStore {}

enum SyncStartResult: Equatable {
    case notEnabled
    case active
    /// 钥匙串暂时不可用：本次跳过，稍后再试。
    case unavailable
    /// 本构建缺钥匙串授权，不支持同步。
    case unsupported
    /// 需要用户处理的启动状态（第 12.1 节）：说明文字由界面按动作给出。
    case attention(SyncStartupAction)
    case failed(SyncFlowResult)
    /// 停止同步、需要用户「关闭再重开」或重新输入同步码的情况（第 11.4 节）；附界面文案。
    case stopped(String)

    static func == (a: SyncStartResult, b: SyncStartResult) -> Bool {
        switch (a, b) {
        case (.notEnabled, .notEnabled), (.active, .active), (.unavailable, .unavailable), (.unsupported, .unsupported): return true
        case (.attention(let x), .attention(let y)): return x == y
        case (.failed(let x), .failed(let y)): return x == y
        case (.stopped(let x), .stopped(let y)): return x == y
        default: return false
        }
    }
}

enum SyncCreateResult: Equatable {
    /// 已开启；`code` 只交给「保存你的同步码」页。首个周期的结果另给（可能是暂时故障，之后自动补推）。
    case created(code: String, fingerprint: String, report: SyncMigrationReport, firstCycle: SyncCycleResult)
    case failed(String)
}

/// 同步服务：把 S1 的状态机、屏障与 S2 的加密、钥匙串、传输接在一起（第 11、12 节）。
/// 线程模型：除 `runCycle` 外的方法都在主线程调用。协调器的周期在协作线程池里跑，与主线程的本地操作
/// 通过 `gate` 互斥：周期只在等网络时让出 `gate`，所以本地操作在请求期间进来不会和合并交错。
final class SyncService: @unchecked Sendable {
    struct Environment {
        var stateStore: SyncStateStore
        var keyStore: SyncKeyStore
        /// 由同步码里的 origin 与派生材料造传输；测试指向本机服务端。
        var makeTransport: (_ origin: String, _ keys: SyncKeyMaterial) -> SyncTransport
        var wallClock: () -> UInt64 = { UInt64(Date().timeIntervalSince1970 * 1000) }
        var random: (Int) throws -> [UInt8] = SyncCrypto.random
        var sizeLimit: Int = SyncLimits.plaintextBytes
    }

    let env: Environment
    private(set) var coordinator: SyncCoordinator?
    private(set) var keys: SyncKeyMaterial?
    private var origin: String?
    private let gate = DispatchSemaphore(value: 1)
    /// 周期单飞：一个周期从 GET 到最后一次落盘结束前，第二个周期（例如退出前补推）只能排队。
    /// `gate` 在等网络时会放开给本地操作，所以不能用它代替。
    private let flight = DispatchSemaphore(value: 1)
    /// 刚开启（创建、加入、恢复）时超过 50 个、要在本机取消的本机专用「不想看」（第 6.6 节），界面层取走一次。
    private var localOnlyDrops: [String] = []

    func takeLocalOnlyDrops() -> [String] {
        defer { localOnlyDrops = [] }
        return localOnlyDrops
    }

    func noteReport(_ report: SyncMigrationReport) {
        localOnlyDrops = report.droppedLocalOnlyVetoes
    }

    /// 文档变化（本地操作或合并）：在任意线程调用，接收方自己回主线程重建投影。
    var onDocumentChanged: ((SyncDocument) -> Void)?

    init(env: Environment) { self.env = env }

    var isActive: Bool { coordinator != nil }

    /// 当前状态的快照（界面显示用）。
    var state: SyncState? { locked { coordinator?.state } }

    /// 完整同步码：只给「保存」与「查看同步码」两个专用页。
    func currentCode() -> String? {
        guard let origin, case .present(let entry) = env.keyStore.readEntry() else { return nil }
        return SyncCrypto.formatCode(key: entry.key, origin: origin)
    }

    /// 同步等待放在非 async 函数里：只在周期两段计算之间短暂阻塞，不跨 await 持有。
    static func acquire(_ gate: DispatchSemaphore) { gate.wait() }

    private func locked<T>(_ body: () -> T) -> T {
        gate.wait()
        defer { gate.signal() }
        return body()
    }

    // MARK: 启动（第 12.1 节）

    func start(local: () -> SyncLocalData, sink: SyncLocalSink) async -> SyncStartResult {
        let lookup = env.keyStore.readEntry()
        if lookup == .unsupported { return .unsupported }
        let read = env.stateStore.load()
        let action = SyncLifecycle.startupAction(state: read, keychain: env.keyStore.read())
        var entryKeys: SyncKeyMaterial?
        if case .present(let entry) = lookup { entryKeys = try? SyncCrypto.derive(entry.key) }
        switch action {
        case .notEnabled: return .notEnabled
        case .skip: return .unavailable
        case .normal, .resumeCreating:
            guard case .present(var state) = read, let keys = entryKeys, keys.blobID == state.blobID else {
                return .attention(.mismatch)
            }
            if action == .resumeCreating {
                state = SyncLifecycle.completeCreate(state, local: local(), pt: env.wallClock()).state
                noteReport(SyncMigration.stampCreator(local(), clock: state.clock, pt: env.wallClock()).report)
                guard env.stateStore.save(state) else { return .failed(.storageFailed) }
            }
            activate(state, keys: keys)
            return .active
        default:
            break
        }
        let transport = entryKeys.flatMap { keys in read.origin.map { env.makeTransport($0, keys) } } ?? OfflineTransport()
        let recovered = await SyncLifecycle.recover(store: env.stateStore, keychain: env.keyStore, sink: sink, transport: transport)
        if let result = recovered.result, result != .done { return .failed(result) }
        switch recovered.action {
        case .orphanEntry, .discardCreating, .finishDisabling, .finishClearing, .finishClearingLocally: return .notEnabled
        default: return .attention(recovered.action)
        }
    }

    func activate(_ state: SyncState, keys: SyncKeyMaterial) {
        let codec = SyncCryptoCodec(keys: keys, sizeLimit: env.sizeLimit)
        let transport = GatedTransport(env.makeTransport(state.origin, keys), gate: gate)
        let c = SyncCoordinator(state: state, store: env.stateStore, transport: transport, codec: codec, wallClock: env.wallClock, sizeLimit: env.sizeLimit)
        c.onDocumentChanged = { [weak self] doc in self?.onDocumentChanged?(doc) }
        self.keys = keys
        origin = state.origin
        coordinator = c
    }

    private func deactivate() {
        coordinator?.cancel()
        coordinator = nil
        keys = nil
        origin = nil
    }

    // MARK: 同步周期与本地操作

    func runCycle() async -> SyncCycleResult? {
        Self.acquire(flight)
        defer { flight.signal() }
        guard let c = coordinator else { return nil }
        Self.acquire(gate)
        let result = await c.runCycle()
        gate.signal()
        return result
    }

    func perform(_ op: SyncLocalOp, localOnlyVetoCount: Int = 0) -> SyncCoordinator.LocalResult? {
        guard let c = coordinator else { return nil }
        return locked { c.perform(op, localOnlyVetoCount: localOnlyVetoCount) }
    }

    // MARK: 创建（第 12.3 节）

    func create(origin: String, local: SyncLocalData) async -> SyncCreateResult {
        guard SyncCode.isCanonicalOrigin(origin) else { return .failed("服务器地址无效") }
        guard let k = try? env.random(32) else { return .failed("系统随机源不可用") }
        return await create(key: k, origin: origin, local: local)
    }

    /// 用给定的 `K` 创建（新生成的，或加入时 404 后「仍用此码创建」）。
    func create(key k: [UInt8], origin: String, local: SyncLocalData) async -> SyncCreateResult {
        guard let dev = try? randomDevice(), let keys = try? SyncCrypto.derive(k),
              let code = SyncCrypto.formatCode(key: k, origin: origin) else { return .failed("系统随机源不可用") }
        var state = SyncState(phase: .creating, origin: origin, blobID: keys.blobID, clock: SyncClock(dev: dev))
        guard env.stateStore.save(state) else { return .failed("本机保存失败") }
        switch env.keyStore.write(SyncKeychainEntry(key: k, dev: dev, blobID: keys.blobID)) {
        case .ok: break
        case .unsupported:
            _ = env.stateStore.delete()
            return .failed("这个版本的 App 不支持同步（缺钥匙串授权）")
        default:
            _ = env.stateStore.delete()
            return .failed("无法写入钥匙串")
        }
        let completed = SyncLifecycle.completeCreate(state, local: local, pt: env.wallClock())
        state = completed.state
        guard env.stateStore.save(state) else { return .failed("本机保存失败") }
        noteReport(completed.report)
        activate(state, keys: keys)
        let first = await runCycle() ?? .transient
        if case .halted(.gone) = first {
            // 生成的空间标识已被使用（概率可忽略）：删除状态与条目，由用户重试。
            deactivate()
            _ = env.keyStore.delete()
            _ = env.stateStore.delete()
            return .failed("生成的空间标识已被使用，请重试")
        }
        return .created(code: code, fingerprint: keys.fingerprint, report: completed.report, firstCycle: first)
    }

    func randomDevice() throws -> UInt32 {
        for _ in 0..<8 {
            let b = try env.random(4)
            let dev = UInt32(b[0]) << 24 | UInt32(b[1]) << 16 | UInt32(b[2]) << 8 | UInt32(b[3])
            if dev != 0 { return dev }
        }
        throw SyncCryptoError.randomUnavailable
    }

    // MARK: 关闭与清除（第 12.6 节）

    /// 在 gate 内作废协调器令牌并摘下协调器：在途周期回来后一律丢弃，之后的本地操作只在本机生效，
    /// 不会在删除状态之后又把它写回来。返回摘下时的状态与材料，流程失败时据此恢复。
    private func detach() -> (state: SyncState, keys: SyncKeyMaterial)? {
        locked {
            guard let c = coordinator, let keys else { return nil }
            c.cancel()
            let snapshot = (c.state, keys)
            coordinator = nil
            self.keys = nil
            origin = nil
            return snapshot
        }
    }

    func disable(sink: SyncLocalSink) -> SyncFlowResult {
        let live = detach()
        let current = live?.state ?? { if case .present(let s) = env.stateStore.load() { return s } else { return nil } }()
        guard let current else {
            // 只有钥匙串条目（「同步状态丢失」时的关闭）：删条目即可。
            return env.keyStore.delete() ? .done : .storageFailed
        }
        let result = SyncLifecycle.disable(current, store: env.stateStore, keychain: env.keyStore, sink: sink)
        // 屏障失败：同步状态与钥匙串都没动，恢复原样。
        if result == .localSaveFailed, let live { activate(live.state, keys: live.keys) }
        return result
    }

    /// 清除云端。网络暂时失败时状态停在 `clearing`，下次启动按第 12.1 节表格继续；屏障失败则恢复原样。
    func clear(sink: SyncLocalSink) async -> SyncFlowResult {
        guard let live = detach() else { return .storageFailed }
        let result = await SyncLifecycle.clear(live.state, store: env.stateStore, keychain: env.keyStore, sink: sink,
                                               transport: env.makeTransport(live.state.origin, live.keys))
        if result == .localSaveFailed { activate(live.state, keys: live.keys) }
        return result
    }
}

/// 周期在等网络时让出 `gate`，回来先拿回 `gate` 再交还协调器。
final class GatedTransport: SyncTransport {
    let inner: SyncTransport
    let gate: DispatchSemaphore

    init(_ inner: SyncTransport, gate: DispatchSemaphore) {
        self.inner = inner
        self.gate = gate
    }

    func get() async -> SyncServerResponse { await around { await self.inner.get() } }
    func put(_ request: SyncPutRequest) async -> SyncServerResponse { await around { await self.inner.put(request) } }
    func delete(base: UInt64, epoch: String?) async -> SyncServerResponse { await around { await self.inner.delete(base: base, epoch: epoch) } }

    private func around(_ body: () async -> SyncServerResponse) async -> SyncServerResponse {
        gate.signal()
        let response = await body()
        SyncService.acquire(gate)
        return response
    }
}

/// 没有钥匙串条目时恢复流程用的占位传输：不发任何请求。
final class OfflineTransport: SyncTransport {
    func get() async -> SyncServerResponse { .transient }
    func put(_ request: SyncPutRequest) async -> SyncServerResponse { .transient }
    func delete(base: UInt64, epoch: String?) async -> SyncServerResponse { .transient }
}

extension SyncStateRead {
    var origin: String? {
        if case .present(let s) = self { return s.origin }
        return nil
    }
}
#endif
