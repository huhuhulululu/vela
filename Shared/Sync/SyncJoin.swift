#if canImport(CryptoKit)
import Foundation
#if SWIFT_PACKAGE
import VelaCore
#endif

/// 加入时用户要确认的一切（docs/plans/sync.md 第 12.4 节）。持有 `K` 与算好的合并结果，只在内存里；
/// 确认之前什么都不落盘，取消就是丢掉它。
final class SyncJoinTicket: @unchecked Sendable {
    let code: SyncCode
    let keys: SyncKeyMaterial
    let dev: UInt32
    let remoteVer: UInt64
    /// 已验证信封的哈希：409 重试时与新响应比较，版本与信封水位不得回退。
    let remoteHash: String
    let remoteEpoch: String
    let remoteNow: UInt64
    let remote: SyncDocument
    /// `merge(本机盖章结果, 远端)` 并做过体积处理的结果，连同盖章后的时钟。
    let replica: SyncReplica
    let fits: Bool
    let preview: SyncJoinPreview
    /// 迁移说明（不合规名字、超额将被取消的「不想看」），确认页列出。
    let report: SyncMigrationReport
    private let lock = NSLock()
    private var cancelledFlag = false
    private var finalizedFlag = false

    init(code: SyncCode, keys: SyncKeyMaterial, dev: UInt32, remoteVer: UInt64, remoteHash: String, remoteEpoch: String, remoteNow: UInt64,
         remote: SyncDocument, replica: SyncReplica, fits: Bool, preview: SyncJoinPreview, report: SyncMigrationReport) {
        self.code = code
        self.keys = keys
        self.dev = dev
        self.remoteVer = remoteVer
        self.remoteHash = remoteHash
        self.remoteEpoch = remoteEpoch
        self.remoteNow = remoteNow
        self.remote = remote
        self.replica = replica
        self.fits = fits
        self.preview = preview
        self.report = report
    }

    var host: String { code.host }
    var fingerprint: String { keys.fingerprint }

    /// 用户取消、离开加入页、App 退出或被挂起：作废这张票。确认前取消什么都不写；提交途中取消则撤销已写的本机部分。
    /// 与提交的最后一步（写「已开启」）由同一把锁裁决：返回 false 表示提交已定局，取消不再生效。
    @discardableResult
    func cancel() -> Bool {
        lock.withLock {
            guard !finalizedFlag else { return false }
            cancelledFlag = true
            return true
        }
    }

    var isCancelled: Bool { lock.withLock { cancelledFlag } }

    /// 提交写「已开启」之前调用：没被取消就定局，之后的取消不再生效。
    func finalize() -> Bool {
        lock.withLock {
            guard !cancelledFlag else { return false }
            finalizedFlag = true
            return true
        }
    }
}

enum SyncJoinPrepared {
    case invalidCode
    /// 404：这个同步码没有对应的已有空间；可「仍用此码创建」。
    case notFound(SyncCode)
    /// 410：空间已被清除，不加入。
    case gone
    case preview(SyncJoinTicket)
    /// 核对、解封、校验或网络失败：什么都没写。
    case failed(String)
}

enum SyncJoinCommit: Equatable {
    /// 已加入：云端已写入合并结果，本机状态、钥匙串与投影都已落盘，同步开启。
    case joined
    /// 取消或退出：未加入，本机原样。
    case cancelled
    /// 提交时云端变了（409）且计数不同：回到确认页，什么都没写。
    case reconfirm(SyncJoinTicket)
    /// 未加入，本机原样；附界面文案。
    case failed(String)
    /// 回滚没有完成：快照留着，下次启动继续收尾；界面提示需要修复。
    case needsRepair(String)

    static func == (a: SyncJoinCommit, b: SyncJoinCommit) -> Bool {
        switch (a, b) {
        case (.joined, .joined), (.cancelled, .cancelled): return true
        case (.reconfirm(let x), .reconfirm(let y)): return x === y
        case (.failed(let x), .failed(let y)), (.needsRepair(let x), .needsRepair(let y)): return x == y
        default: return false
        }
    }
}

/// 加入提交用的本机存储（第 12.4 节、第 23 节第 14 条）：在屏障需要的接口之外，提交前把保存队列里待写的数据
/// 落盘并确认，回滚换回文件之后按文件重读内存。
protocol SyncJoinSink: SyncLocalSink {
    /// 写出保存队列里待写的值，再把内存里的当前收藏与口味直接写盘；都成功才返回 true。
    func persistPending() -> Bool
    /// 快照换回之后：按磁盘重读收藏、口味与两个设置，内存、磁盘、保存队列三者一致。
    func reloadAfterRollback()
}

/// 加入是一次性事务（第 12.4 节，用户裁决）：解析 → 拉取 → 内存里算好合并与预览 → 用户确认 →
/// 先写云端（PUT 成功）→ 再写本机（钥匙串 → 同步状态 → 投影落盘屏障）。本机任一步失败回滚为「未加入」；
/// 云端已写入的是本机数据与云端的合并结果，对其他设备无害。确认前或提交途中取消、退出都等于未加入。
extension SyncService {
    static let joinConflictRetries = 3

    func prepareJoin(_ text: String, local: SyncLocalData) async -> SyncJoinPrepared {
        guard let code = SyncCrypto.parseCode(text), let keys = try? SyncCrypto.derive(code.key), let dev = try? randomDevice() else {
            return .invalidCode
        }
        let transport = env.makeTransport(code.origin, keys)
        switch await transport.get() {
        case .notFound: return .notFound(code)
        case .gone: return .gone
        case .blob(let ver, let pub, let data, let epoch, let now):
            switch ticket(code: code, keys: keys, dev: dev, ver: ver, pub: pub, data: data, epoch: epoch, now: now, local: local) {
            case .success(let t): return .preview(t)
            case .failure(let e): return .failed(e.text)
            }
        default:
            return .failed("网络不可用，稍后再试")
        }
    }

    struct JoinError: Error { let text: String }

    /// 核对公钥、解封、校验远端文档，并在内存里算好合并结果与预览计数。
    private func ticket(code: SyncCode, keys: SyncKeyMaterial, dev: UInt32, ver: UInt64, pub: String, data: String,
                        epoch: String, now: UInt64, local: SyncLocalData) -> Result<SyncJoinTicket, JoinError> {
        guard pub == keys.publicKeyText else { return .failure(JoinError(text: SyncStatusText.halt(.pubMismatch))) }
        let codec = SyncCryptoCodec(keys: keys, sizeLimit: env.sizeLimit)
        switch codec.open(data, ver: ver) {
        case .unsupported: return .failure(JoinError(text: "其他设备的 App 更新，请更新本机 App"))
        case .failed: return .failure(JoinError(text: "无法解密云端数据"))
        case .plaintext(let bytes):
            switch SyncDocument.decode(bytes) {
            case .valid(let doc):
                let stamped = SyncMigration.stampJoin(local, remote: doc, clock: SyncClock(dev: dev), pt: env.wallClock())
                return .success(SyncJoinTicket(code: code, keys: keys, dev: dev, remoteVer: ver, remoteHash: codec.digest(data), remoteEpoch: epoch, remoteNow: now,
                                               remote: doc, replica: stamped.replica, fits: stamped.fits,
                                               preview: stamped.preview, report: stamped.report))
            case .newerSchema: return .failure(JoinError(text: "其他设备的 App 更新，请更新本机 App"))
            case .invalid: return .failure(JoinError(text: "云端数据不合法"))
            }
        }
    }

    /// 用户确认后提交（第 12.4 节）。先上提交锁：从这里到结束，本机收藏、屏蔽、口味与同步设置的写操作一律被拒
    /// （`localWriteAllowed` 为 false），所以 `local` 在上锁之后才取，网络往返期间不可能有新的本机改动被覆盖。
    /// 锁在任何结局下都释放；App 退出时锁随进程消失，没写「已开启」就按未加入处理。
    func commitJoin(_ original: SyncJoinTicket, local takeLocal: () -> SyncLocalData, sink: SyncJoinSink) async -> SyncJoinCommit {
        if original.isCancelled { return .cancelled }
        guard !isActive else { return .failed("这台设备已开启同步，请先关闭") }
        guard case .none = env.stateStore.load(), !env.journal.exists else { return .failed("本机还有未清理的同步状态，请先关闭同步") }
        guard beginCommit() else { return .failed("正在加入同步…") }
        defer { endCommit() }
        let local = takeLocal()
        var t = original
        // 上锁之后的本机数据重算：与确认页的计数不同就回到确认页。
        switch ticketFromRemote(t, local: local) {
        case .success(let fresh):
            if fresh.preview != t.preview { return .reconfirm(fresh) }
            t = fresh
        case .failure(let e): return .failed(e.text)
        }
        guard t.fits else { return .failed(SyncStatusText.cycle(.oversize) ?? "同步数据已满") }
        let wall = env.wallClock()
        guard (wall > t.remoteNow ? wall - t.remoteNow : t.remoteNow - wall) <= SyncLimits.clockSkewMillis else {
            return .failed(SyncStatusText.cycle(.clockSkew) ?? "本机时间不准")
        }
        let codec = SyncCryptoCodec(keys: t.keys, sizeLimit: env.sizeLimit)
        let transport = env.makeTransport(t.code.origin, t.keys)
        for _ in 0...Self.joinConflictRetries {
            let next = t.remoteVer + 1
            guard let envelope = codec.seal(t.replica.doc.encoded(), ver: next) else { return .failed("系统随机源不可用，未加入") }
            let response = await transport.put(SyncPutRequest(base: t.remoteVer, next: next, envelope: envelope, epoch: t.remoteEpoch))
            if original.isCancelled { return .cancelled }
            switch response {
            case .stored(let ver, let epoch, _):
                guard ver == next, epoch == t.remoteEpoch else { return .failed("服务器响应异常，未加入") }
                return commitLocally(original, t, ver: next, envelopeHash: codec.digest(envelope), sink: sink)
            case .conflict(let ver, let pub, let data, let epoch, let now):
                guard epoch == t.remoteEpoch else { return .failed(SyncStatusText.halt(.epochChanged)) }
                // 与普通同步的水位检查一致：版本不得回退，同版本不得换信封（第 11.4 节）。
                if ver < t.remoteVer || (ver == t.remoteVer && codec.digest(data) != t.remoteHash) {
                    return .failed(SyncStatusText.halt(.rollback))
                }
                switch ticket(code: t.code, keys: t.keys, dev: t.dev, ver: ver, pub: pub, data: data, epoch: epoch, now: now, local: local) {
                case .success(let fresh):
                    if fresh.preview != t.preview { return .reconfirm(fresh) }
                    t = fresh
                case .failure(let e): return .failed(e.text)
                }
            case .gone: return .failed("这个同步码对应的空间已被清除，未加入")
            case .forbidden: return .failed("云端拒绝了这台设备的写入，未加入")
            case .notFound: return .failed("云端同步空间不见了，未加入")
            case .rateLimited, .quotaFull, .payloadTooLarge: return .failed("服务器暂时拒绝写入，未加入，稍后再试")
            default: return .failed("网络不可用，未加入，稍后再试")
            }
        }
        return .failed("云端一直在变，未加入，稍后再试")
    }

    private func ticketFromRemote(_ t: SyncJoinTicket, local: SyncLocalData) -> Result<SyncJoinTicket, JoinError> {
        let stamped = SyncMigration.stampJoin(local, remote: t.remote, clock: SyncClock(dev: t.dev), pt: env.wallClock())
        return .success(SyncJoinTicket(code: t.code, keys: t.keys, dev: t.dev, remoteVer: t.remoteVer, remoteHash: t.remoteHash, remoteEpoch: t.remoteEpoch,
                                       remoteNow: t.remoteNow, remote: t.remote, replica: stamped.replica, fits: stamped.fits,
                                       preview: stamped.preview, report: stamped.report))
    }

    /// 本机提交（第 12.4 节、第 23 节第 13、14 条）：待写数据落盘 → 文件快照 → 钥匙串 → 投影（屏障落盘）→
    /// 同步状态（「已开启」，最后写）→ 标记快照已提交 → 删快照。
    /// 每步之间看一次取消；任一步失败或被取消，用快照换回本机文件、按文件重读内存，再删钥匙串与状态（都在提交锁内）。
    /// 回滚本身失败就报「需要修复」。
    private func commitLocally(_ original: SyncJoinTicket, _ t: SyncJoinTicket, ver: UInt64, envelopeHash: String, sink: SyncJoinSink) -> SyncJoinCommit {
        let failed = SyncJoinCommit.failed("本机保存失败，未加入；本机数据保持原样")
        func undo(_ outcome: SyncJoinCommit) -> SyncJoinCommit {
            rollbackJoin(sink: sink) ? outcome : .needsRepair(SyncStatusText.joinRepair)
        }
        if original.isCancelled { return .cancelled }
        // 保存队列里还有最近 2 秒的编辑：先落盘并确认，快照才是提交那一刻的本机数据（H1）。
        guard sink.persistPending() else { return failed }
        guard env.journal.snapshot() else { return failed }
        guard env.keyStore.write(SyncKeychainEntry(key: t.code.key, dev: t.dev, blobID: t.keys.blobID)) == .ok else { return undo(failed) }
        if original.isCancelled { return undo(.cancelled) }
        var state = SyncState(phase: .active, origin: t.code.origin, blobID: t.keys.blobID, clock: t.replica.clock, doc: t.replica.doc)
        state.epoch = t.remoteEpoch
        state.lastSeenVer = ver
        state.lastSeenHash = envelopeHash
        state.maxSentL = t.replica.doc.allRegisters.map(\.hlc.l).max() ?? 0
        state.migrated = true
        guard SyncLifecycle.barrier(state.doc, sink: sink) else { return undo(failed) }
        // 定局：写「已开启」之前与取消用同一把锁裁决。
        guard original.finalize() else { return undo(.cancelled) }
        guard env.stateStore.save(state) else { return undo(failed) }
        // 已定局：快照先标记已提交（之后只能删除），再删。删不掉不算加入失败，但关闭、清除、新建之前必须先清掉它，
        // 清不掉就不继续（`settleJoinSnapshot`），界面据 `journal.exists` 提示（H2）。
        _ = retireSnapshot()
        activate(state, keys: t.keys)
        onDocumentChanged?(state.doc)
        return .joined
    }

    /// 撤销未完成的加入：换回快照、删同步状态与钥匙串条目、按文件重读内存，最后删快照。全部成功才返回 true。
    /// 已提交的快照只删、不换回（也不动状态与钥匙串）；不完整的快照没写过本机，同样只删。
    func rollbackJoin(sink: SyncLocalSink? = nil) -> Bool {
        switch env.journal.status {
        case .none: return true
        case .committed, .incomplete: return env.journal.discard()
        case .pending:
            guard env.journal.restore(), env.stateStore.delete(), env.keyStore.delete() else { return false }
            (sink as? SyncJoinSink)?.reloadAfterRollback()
            return env.journal.discard()
        }
    }

    /// 已定局的快照：标记已提交，再删除。删除成功才返回 true。
    @discardableResult
    func retireSnapshot() -> Bool {
        _ = env.journal.markCommitted()
        return env.journal.discard()
    }

    /// 关闭、清除之前处理残留快照（H2、H3）：已提交或不完整的只删；未定局的，有同步状态（「已开启」已写）也只删，
    /// 没有同步状态就完成待做的恢复。返回 false：快照还在，调用方不得继续（否则之后可能把加入前的旧文件换回）。
    func settleJoinSnapshot(sink: SyncLocalSink?) -> Bool {
        switch env.journal.status {
        case .none: return true
        case .committed, .incomplete: return env.journal.discard()
        case .pending:
            if isActive { return retireSnapshot() }
            if case .none = env.stateStore.load() { return rollbackJoin(sink: sink) }
            return retireSnapshot()
        }
    }

    /// 上次加入没收尾、回滚还没完成（需要修复）：未定局的快照在、同步没开启。期间本机偏好写入一律被拒，
    /// 否则之后的恢复会把这些编辑换掉（H3）。
    var joinRepairPending: Bool { !isActive && env.journal.status == .pending }

    // MARK: 提交锁

    var isCommittingJoin: Bool { commitLock.withLock { committing } }

    /// 本机收藏、屏蔽、口味与同步设置的写操作是否允许（提交中与待修复期间一律拒绝，不排队）。
    var localWriteAllowed: Bool { !isCommittingJoin && !joinRepairPending }

    private func beginCommit() -> Bool {
        commitLock.withLock {
            guard !committing else { return false }
            committing = true
            return true
        }
    }

    private func endCommit() { commitLock.withLock { committing = false } }

    /// 404 时「仍用此码创建」：等同于用这个 `K` 开启同步（第 12.3 节）。
    func createWithCode(_ code: SyncCode, local: SyncLocalData) async -> SyncCreateResult {
        await create(key: code.key, origin: code.origin, local: local)
    }
}
#endif
