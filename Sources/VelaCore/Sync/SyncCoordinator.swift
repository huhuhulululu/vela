/// 一次同步周期的结果。
public enum SyncCycleResult: Equatable, Sendable {
    case upToDate
    case pushed
    case created
    case halted(SyncHalt)
    /// 远端 schema 更大或信封前缀未知：只读（不合并、不推送）。
    case readOnly
    /// 远端文档不合法：不合并、不推送，保留现场。
    case invalidRemote
    /// AEAD 解封失败，第一次：下次触发再试。
    case decryptFailed
    /// 本机时间与服务端相差超过 1 小时：暂停写入。
    case clockSkew
    /// 合并结果超过体积上限：停止推送。
    case oversize
    case transient
    case rateLimited(retryAfter: Int)
    case quotaFull
    case payloadTooLarge
    /// 一个周期内 409 超过 3 次：退避。
    case conflictLimit
    /// 关闭、清除、加入开始后迟到的响应：丢弃，不写状态。
    case cancelled
    /// 同步状态落盘失败：本周期不发写请求。
    case storageFailed
}

/// 每个空间一个的串行协调器（docs/plans/sync.md 第 11.2 节）。调用方保证同一时刻只有一个周期在跑。
/// 本地操作可以在请求期间进来：合并用的是请求返回时内存里的当前状态，所以不会丢。
public final class SyncCoordinator: @unchecked Sendable {
    public private(set) var state: SyncState
    let store: SyncStateStore
    let transport: SyncTransport
    let codec: SyncEnvelopeCodec
    /// 本机墙钟毫秒。
    let wallClock: () -> UInt64
    /// 文档变化后调用，调用方据此重建投影。
    public var onDocumentChanged: ((SyncDocument) -> Void)?
    private var token = 0
    /// 明文体积上限 `P`。按现有数量上限，诚实文档最大约 42 万字节，到不了 `P`；测试用更小的值覆盖超限路径。
    let sizeLimit: Int
    /// 一个周期最多处理的 409 次数。
    static let conflictRetries = 3

    public init(
        state: SyncState, store: SyncStateStore, transport: SyncTransport, codec: SyncEnvelopeCodec,
        wallClock: @escaping () -> UInt64, sizeLimit: Int = SyncLimits.plaintextBytes
    ) {
        self.sizeLimit = sizeLimit
        self.state = state
        self.store = store
        self.transport = transport
        self.codec = codec
        self.wallClock = wallClock
    }

    /// 关闭、清除、加入开始时调用：令牌不符的迟到响应一律丢弃。
    public func cancel() { token += 1 }

    // MARK: 本地操作（第 11.1 节写顺序）

    public struct LocalResult: Equatable, Sendable {
        public let outcome: SyncOpOutcome
        /// `sync-state.json` 是否写成功；失败时操作在内存中仍生效，界面提示「保存失败」，下次操作时重试。
        public let saved: Bool
    }

    public func perform(_ op: SyncLocalOp, localOnlyVetoCount: Int = 0) -> LocalResult {
        let outcome = SyncOps.apply(op, to: state.replica, pt: wallClock(), localOnlyVetoCount: localOnlyVetoCount, sizeLimit: sizeLimit)
        guard case .applied(let next) = outcome else { return LocalResult(outcome: outcome, saved: true) }
        state.replica = next
        state.oversize = false
        state.gen += 1
        let saved = store.save(state)
        onDocumentChanged?(state.doc)
        return LocalResult(outcome: outcome, saved: saved)
    }

    // MARK: 同步周期

    public func runCycle() async -> SyncCycleResult {
        if let halt = state.halt { return .halted(halt) }
        let myToken = token
        var response = await transport.get()
        guard token == myToken else { return .cancelled }
        var conflicts = 0
        var countedDecryptFailure = false
        while true {
            let remote: (ver: UInt64, doc: SyncDocument, now: UInt64)
            switch response {
            case .blob(let ver, let pub, let data, let epoch, let now), .conflict(let ver, let pub, let data, let epoch, let now):
                switch accept(ver: ver, pub: pub, data: data, epoch: epoch, countedDecryptFailure: &countedDecryptFailure) {
                case .success(let doc): remote = (ver, doc, now)
                case .failure(let result): return result
                }
            case .notFound(let epoch, let now):
                if state.lastSeenVer > 0 || state.inflight != nil { return halt(.missing) }
                if let known = state.epoch, known != epoch { return halt(.epochChanged) }
                state.epoch = epoch
                remote = (0, .empty, now)
            default:
                return terminal(response)
            }
            mergeRemote(remote.doc)
            guard store.save(state) else { return .storageFailed }
            if remote.ver > 0, state.doc == remote.doc, state.gen == state.pushedGen { return success(.upToDate) }
            if state.oversize { return .oversize }
            let wall = wallClock()
            let skew = wall > remote.now ? wall - remote.now : remote.now - wall
            if skew > SyncLimits.clockSkewMillis { return .clockSkew }
            var replica = state.replica
            if case .restamped = SyncRestamp.presend(&replica, serverNow: remote.now, maxSentL: state.maxSentL, pt: wall) {
                state.replica = replica
                onDocumentChanged?(state.doc)
            }
            guard let envelope = codec.seal(state.doc.encoded(), ver: remote.ver + 1) else { return .transient }
            let hash = codec.digest(envelope)
            let request = SyncPutRequest(base: remote.ver, next: remote.ver + 1, envelope: envelope, epoch: state.epoch)
            state.inflight = SyncInflight(base: request.base, next: request.next, envelopeHash: hash, sentGen: state.gen)
            state.maxSentL = max(state.maxSentL, state.doc.allRegisters.map(\.hlc.l).max() ?? 0)
            guard store.save(state) else { return .storageFailed }
            response = await transport.put(request)
            guard token == myToken else { return .cancelled }
            switch response {
            case .stored(let ver, let epoch, _):
                if let known = state.epoch, known != epoch { return halt(.epochChanged) }
                state.lastSeenVer = ver
                state.lastSeenHash = hash
                state.pushedGen = max(state.pushedGen, state.inflight?.sentGen ?? 0)
                state.inflight = nil
                guard store.save(state) else { return .storageFailed }
                return success(request.base == 0 ? .created : .pushed)
            case .conflict:
                conflicts += 1
                if conflicts > Self.conflictRetries { return .conflictLimit }
                continue
            case .notFound:
                return halt(.missing)
            default:
                return terminal(response)
            }
        }
    }

    enum Accepted {
        case success(SyncDocument)
        case failure(SyncCycleResult)
    }

    /// 第 2、3 步：epoch 与水位、`pub`、解封、校验；通过后推进水位并确认在途请求。
    private func accept(ver: UInt64, pub: String, data: String, epoch: String, countedDecryptFailure: inout Bool) -> Accepted {
        if let known = state.epoch, known != epoch { return .failure(halt(.epochChanged)) }
        let hash = codec.digest(data)
        if ver < state.lastSeenVer || (ver == state.lastSeenVer && state.lastSeenHash != nil && state.lastSeenHash != hash) {
            return .failure(halt(.rollback))
        }
        guard pub == codec.publicKey else { return .failure(halt(.pubMismatch)) }
        let plaintext: [UInt8]
        switch codec.open(data, ver: ver) {
        case .plaintext(let bytes): plaintext = bytes
        case .unsupported: return .failure(.readOnly)
        case .failed:
            if !countedDecryptFailure {
                countedDecryptFailure = true
                state.decryptFailures += 1
            }
            if state.decryptFailures >= 2 { return .failure(halt(.decryptFailed)) }
            _ = store.save(state)
            return .failure(.decryptFailed)
        }
        let doc: SyncDocument
        switch SyncDocument.decode(plaintext) {
        case .valid(let d): doc = d
        case .newerSchema: return .failure(.readOnly)
        case .invalid(let reason):
            state.lastFailure = "invalid:\(reason)"
            _ = store.save(state)
            return .failure(.invalidRemote)
        }
        state.decryptFailures = 0
        state.epoch = epoch
        state.lastSeenVer = ver
        state.lastSeenHash = hash
        if let inflight = state.inflight {
            if ver == inflight.next, hash == inflight.envelopeHash { state.pushedGen = max(state.pushedGen, inflight.sentGen) }
            state.inflight = nil
        }
        guard store.save(state) else { return .failure(.storageFailed) }
        return .success(doc)
    }

    /// 第 4 步：与此刻内存里的当前状态合并，做体积处理，推进 HLC。
    private func mergeRemote(_ remote: SyncDocument) {
        let fitted = SyncMerge.fitSize(SyncMerge.union(state.doc, remote), limit: sizeLimit)
        let changed = fitted.doc != state.doc
        state.doc = fitted.doc
        state.oversize = !fitted.fits
        state.clock.receive(remote.maxHLC)
        if changed { onDocumentChanged?(state.doc) }
    }

    private func success(_ result: SyncCycleResult) -> SyncCycleResult {
        state.lastSuccessAt = wallClock()
        state.lastFailure = nil
        _ = store.save(state)
        return result
    }

    private func halt(_ reason: SyncHalt) -> SyncCycleResult {
        state.halt = reason
        _ = store.save(state)
        return .halted(reason)
    }

    private func terminal(_ response: SyncServerResponse) -> SyncCycleResult {
        switch response {
        case .gone(let deletedAt): return halt(.gone(deletedAt: deletedAt))
        case .forbidden: return halt(.forbidden)
        case .rateLimited(let after): return .rateLimited(retryAfter: after)
        case .quotaFull: return .quotaFull
        case .payloadTooLarge: return .payloadTooLarge
        default: return .transient
        }
    }
}
