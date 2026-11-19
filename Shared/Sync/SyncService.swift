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

/// 加入时用户要确认的一切（docs/plans/sync.md 第 12.4 节第 5 步）。持有 `K`，只在内存里。
final class SyncJoinTicket {
    let code: SyncCode
    let keys: SyncKeyMaterial
    let state: SyncState
    let remoteVer: UInt64
    let remoteHash: String
    let remoteEpoch: String
    let remote: SyncDocument
    let preview: SyncJoinPreview

    init(code: SyncCode, keys: SyncKeyMaterial, state: SyncState, remoteVer: UInt64, remoteHash: String, remoteEpoch: String,
         remote: SyncDocument, preview: SyncJoinPreview) {
        self.code = code
        self.keys = keys
        self.state = state
        self.remoteVer = remoteVer
        self.remoteHash = remoteHash
        self.remoteEpoch = remoteEpoch
        self.remote = remote
        self.preview = preview
    }

    var host: String { code.host }
    var fingerprint: String { keys.fingerprint }
}

enum SyncJoinPrepared {
    case invalidCode
    /// 404：这个同步码没有对应的已有空间；可「仍用此码创建」。
    case notFound(SyncCode)
    /// 410：空间已被清除；状态已删除，不加入。
    case gone
    case preview(SyncJoinTicket)
    /// 核对、解封、校验或网络失败：状态已删除，不保存 `K`。
    case failed(String)
}

enum SyncStartResult: Equatable {
    case notEnabled
    case active
    /// 钥匙串暂时不可用：本次跳过，稍后再试。
    case unavailable
    /// 本构建缺钥匙串授权，不支持同步。
    case unsupported
    /// 需要用户处理的启动状态（第 12.1 节）：说明文字由界面按动作给出。
    case attention(SyncStartupAction)
    /// 加入恢复时远端变了且计数不同：重新确认。
    case reconfirmJoin(SyncJoinTicket)
    case failed(SyncFlowResult)
    /// 停止同步、需要用户「关闭再重开」或重新输入同步码的情况（第 11.4 节）；附界面文案。
    case stopped(String)

    static func == (a: SyncStartResult, b: SyncStartResult) -> Bool {
        switch (a, b) {
        case (.notEnabled, .notEnabled), (.active, .active), (.unavailable, .unavailable), (.unsupported, .unsupported): return true
        case (.attention(let x), .attention(let y)): return x == y
        case (.failed(let x), .failed(let y)): return x == y
        case (.stopped(let x), .stopped(let y)): return x == y
        case (.reconfirmJoin(let x), .reconfirmJoin(let y)): return x.remoteHash == y.remoteHash && x.preview == y.preview
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
                guard env.stateStore.save(state) else { return .failed(.storageFailed) }
            }
            activate(state, keys: keys)
            return .active
        case .resumeJoining:
            guard case .present(let state) = read, let plan = state.joinPlan, let keys = entryKeys else { break }
            return await resumeJoin(state, plan: plan, keys: keys, local: local)
        default:
            break
        }
        let transport = entryKeys.flatMap { keys in read.origin.map { env.makeTransport($0, keys) } } ?? OfflineTransport()
        let recovered = await SyncLifecycle.recover(store: env.stateStore, keychain: env.keyStore, sink: sink, transport: transport)
        if let result = recovered.result, result != .done { return .failed(result) }
        switch recovered.action {
        case .discardCreating, .finishDisabling, .finishClearing, .finishClearingLocally: return .notEnabled
        default: return .attention(recovered.action)
        }
    }

    private func activate(_ state: SyncState, keys: SyncKeyMaterial) {
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
        guard let k = try? env.random(32), let dev = try? randomDevice(), let keys = try? SyncCrypto.derive(k),
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

    private func randomDevice() throws -> UInt32 {
        for _ in 0..<8 {
            let b = try env.random(4)
            let dev = UInt32(b[0]) << 24 | UInt32(b[1]) << 16 | UInt32(b[2]) << 8 | UInt32(b[3])
            if dev != 0 { return dev }
        }
        throw SyncCryptoError.randomUnavailable
    }

    // MARK: 加入（第 12.4 节）

    func prepareJoin(_ text: String, local: SyncLocalData) async -> SyncJoinPrepared {
        guard let code = SyncCrypto.parseCode(text), let keys = try? SyncCrypto.derive(code.key), let dev = try? randomDevice() else { return .invalidCode }
        let state = SyncState(phase: .joining, origin: code.origin, blobID: keys.blobID, clock: SyncClock(dev: dev))
        guard env.stateStore.save(state) else { return .failed("本机保存失败") }
        let transport = env.makeTransport(code.origin, keys)
        switch await fetchRemote(transport, keys: keys, state: state, local: local, code: code) {
        case .success(let ticket): return .preview(ticket)
        case .failure(let failure):
            if case .notFound = failure { return .notFound(code) }
            _ = env.stateStore.delete()
            if case .gone = failure { return .gone }
            if case .message(let text) = failure { return .failed(text) }
            return .failed("网络不可用，稍后再试")
        }
    }

    enum FetchFailure: Error { case notFound, gone(deletedAt: UInt64), message(String), network }

    private func fetchRemote(_ transport: SyncTransport, keys: SyncKeyMaterial, state: SyncState, local: SyncLocalData, code: SyncCode) async -> Result<SyncJoinTicket, FetchFailure> {
        switch await transport.get() {
        case .notFound: return .failure(.notFound)
        case .gone(let deletedAt): return .failure(.gone(deletedAt: deletedAt))
        case .blob(let ver, let pub, let data, let epoch, _):
            guard pub == keys.publicKeyText else { return .failure(.message(SyncStatusText.halt(.pubMismatch))) }
            let codec = SyncCryptoCodec(keys: keys, sizeLimit: env.sizeLimit)
            switch codec.open(data, ver: ver) {
            case .unsupported: return .failure(.message("其他设备的 App 更新，请更新本机 App"))
            case .failed: return .failure(.message("无法解密云端数据"))
            case .plaintext(let bytes):
                switch SyncDocument.decode(bytes) {
                case .valid(let doc):
                    let preview = SyncMigration.stampJoin(local, remote: doc, clock: state.clock, pt: env.wallClock()).preview
                    return .success(SyncJoinTicket(code: code, keys: keys, state: state, remoteVer: ver, remoteHash: codec.digest(data),
                                                   remoteEpoch: epoch, remote: doc, preview: preview))
                case .newerSchema: return .failure(.message("其他设备的 App 更新，请更新本机 App"))
                case .invalid: return .failure(.message("云端数据不合法"))
                }
            }
        default:
            return .failure(.network)
        }
    }

    /// 用户确认后：写钥匙串 → 落盘 `joinPlan` → 盖章并合并 → `phase = active` → 立即推送。
    func confirmJoin(_ ticket: SyncJoinTicket, local: SyncLocalData) async -> SyncCycleResult? {
        let entry = SyncKeychainEntry(key: ticket.code.key, dev: ticket.state.clock.dev, blobID: ticket.keys.blobID)
        guard env.keyStore.write(entry) == .ok else { return .storageFailed }
        var state = ticket.state
        state.joinPlan = SyncJoinPlan(origin: ticket.code.origin, blobID: ticket.keys.blobID, epoch: ticket.remoteEpoch,
                                      ver: ticket.remoteVer, envelopeHash: ticket.remoteHash, preview: ticket.preview)
        guard env.stateStore.save(state) else { return .storageFailed }
        return await finishJoin(state, keys: ticket.keys, remote: ticket.remote, local: local)
    }

    private func finishJoin(_ state: SyncState, keys: SyncKeyMaterial, remote: SyncDocument, local: SyncLocalData) async -> SyncCycleResult? {
        let joined = SyncLifecycle.completeJoin(state, local: local, remote: remote, pt: env.wallClock())
        guard env.stateStore.save(joined) else { return .storageFailed }
        activate(joined, keys: keys)
        onDocumentChanged?(joined.doc)
        return await runCycle()
    }

    /// 404 时「仍用此码创建」：写钥匙串条目，`phase` 改为 `creating`，转入创建第 4 步。
    func createWithCode(_ code: SyncCode, local: SyncLocalData) async -> SyncCycleResult? {
        guard case .present(var state) = env.stateStore.load(), state.phase == .joining, let keys = try? SyncCrypto.derive(code.key),
              state.blobID == keys.blobID else { return .storageFailed }
        guard env.keyStore.write(SyncKeychainEntry(key: code.key, dev: state.clock.dev, blobID: keys.blobID)) == .ok else { return .storageFailed }
        state.phase = .creating
        state = SyncLifecycle.completeCreate(state, local: local, pt: env.wallClock()).state
        guard env.stateStore.save(state) else { return .storageFailed }
        activate(state, keys: keys)
        return await runCycle()
    }

    /// 加入流程中途取消（首次预览，或启动恢复时的重新确认）：只删 `joining` 状态与对应的钥匙串条目。
    /// 不跑投影屏障：加入完成之前本机文件才是事实来源，`joining` 状态里的文档是空的，拿它投影会覆盖本机数据。
    func cancelJoin(sink: SyncLocalSink) -> SyncFlowResult {
        guard case .present(let s) = env.stateStore.load(), s.phase == .joining else { return .done }
        return abandonJoin(s)
    }

    private func abandonJoin(_ s: SyncState) -> SyncFlowResult {
        if case .present(let entry) = env.keyStore.readEntry(), entry.blobID == s.blobID {
            guard env.keyStore.delete() else { return .storageFailed }
        }
        return env.stateStore.delete() ? .done : .storageFailed
    }

    /// 启动时 `joining` 且有 `joinPlan`：重新 GET 并重算计数（第 12.4 节第 7 步）。
    private func resumeJoin(_ state: SyncState, plan: SyncJoinPlan, keys: SyncKeyMaterial, local: () -> SyncLocalData) async -> SyncStartResult {
        guard case .present(let entry) = env.keyStore.readEntry(),
              let parsed = SyncCrypto.formatCode(key: entry.key, origin: state.origin).flatMap(SyncCrypto.parseCode) else { return .attention(.mismatch) }
        let data = local()
        switch await fetchRemote(env.makeTransport(state.origin, keys), keys: keys, state: state, local: data, code: parsed) {
        case .success(let ticket):
            // 先按已落盘的计划核对 epoch 与版本水位（第 11.4 节），再决定继续还是重新确认。
            if ticket.remoteEpoch != plan.epoch { return .stopped(SyncStatusText.halt(.epochChanged)) }
            if ticket.remoteVer < plan.ver || (ticket.remoteVer == plan.ver && ticket.remoteHash != plan.envelopeHash) {
                return .stopped(SyncStatusText.halt(.rollback))
            }
            switch SyncLifecycle.joinRecovery(plan: plan, remoteVer: ticket.remoteVer, remoteHash: ticket.remoteHash, recomputed: ticket.preview) {
            case .proceed:
                _ = await finishJoin(state, keys: keys, remote: ticket.remote, local: data)
                return .active
            case .reconfirm: return .reconfirmJoin(ticket)
            case .abort: return .attention(.resumeJoining)
            }
        // 只有网络暂时故障才稍后再试；410、被占用、解不开、空间不见了都要用户关闭再重开或重新输入同步码。
        case .failure(.network): return .unavailable
        case .failure(.gone(let deletedAt)): return .stopped(SyncStatusText.halt(.gone(deletedAt: deletedAt)))
        case .failure(.notFound): return .stopped(SyncStatusText.halt(.missing))
        case .failure(.message(let text)): return .stopped(text)
        }
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
        // 加入尚未完成：本机文件才是事实来源，按取消加入处理，不跑屏障。
        if current.phase == .joining { return abandonJoin(current) }
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
