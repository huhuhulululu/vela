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

    init(code: SyncCode, keys: SyncKeyMaterial, dev: UInt32, remoteVer: UInt64, remoteEpoch: String, remoteNow: UInt64,
         remote: SyncDocument, replica: SyncReplica, fits: Bool, preview: SyncJoinPreview, report: SyncMigrationReport) {
        self.code = code
        self.keys = keys
        self.dev = dev
        self.remoteVer = remoteVer
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

    /// 用户取消或离开加入页：确认前取消什么都不写；提交途中取消则云端写完也不落本机。
    func cancel() { lock.withLock { cancelledFlag = true } }
    var isCancelled: Bool { lock.withLock { cancelledFlag } }
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

    static func == (a: SyncJoinCommit, b: SyncJoinCommit) -> Bool {
        switch (a, b) {
        case (.joined, .joined), (.cancelled, .cancelled): return true
        case (.reconfirm(let x), .reconfirm(let y)): return x === y
        case (.failed(let x), .failed(let y)): return x == y
        default: return false
        }
    }
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
                return .success(SyncJoinTicket(code: code, keys: keys, dev: dev, remoteVer: ver, remoteEpoch: epoch, remoteNow: now,
                                               remote: doc, replica: stamped.replica, fits: stamped.fits,
                                               preview: stamped.preview, report: stamped.report))
            case .newerSchema: return .failure(JoinError(text: "其他设备的 App 更新，请更新本机 App"))
            case .invalid: return .failure(JoinError(text: "云端数据不合法"))
            }
        }
    }

    /// 用户确认后提交。`local` 取确认这一刻的本机数据：与预览时算出的计数不同就回到确认页。
    func commitJoin(_ original: SyncJoinTicket, local: SyncLocalData, sink: SyncLocalSink) async -> SyncJoinCommit {
        if original.isCancelled { return .cancelled }
        guard !isActive else { return .failed("这台设备已开启同步，请先关闭") }
        guard case .none = env.stateStore.load() else { return .failed("本机还有未清理的同步状态，请先关闭同步") }
        var t = original
        // 确认这一刻重算：用户在预览期间改了本机数据时，计数会变。
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
            if original.isCancelled || t.isCancelled { return .cancelled }
            switch response {
            case .stored(let ver, let epoch, _):
                guard ver == next, epoch == t.remoteEpoch else { return .failed("服务器响应异常，未加入") }
                return commitLocally(t, ver: next, envelopeHash: codec.digest(envelope), sink: sink, local: local)
            case .conflict(let ver, let pub, let data, let epoch, let now):
                guard epoch == t.remoteEpoch else { return .failed(SyncStatusText.halt(.epochChanged)) }
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
        return .success(SyncJoinTicket(code: t.code, keys: t.keys, dev: t.dev, remoteVer: t.remoteVer, remoteEpoch: t.remoteEpoch,
                                       remoteNow: t.remoteNow, remote: t.remote, replica: stamped.replica, fits: stamped.fits,
                                       preview: stamped.preview, report: stamped.report))
    }

    /// 本机提交：钥匙串 → 同步状态 → 投影落盘屏障。任一步失败就撤销已写的部分并恢复本机原文件，回到未加入。
    private func commitLocally(_ t: SyncJoinTicket, ver: UInt64, envelopeHash: String, sink: SyncLocalSink, local: SyncLocalData) -> SyncJoinCommit {
        let originalTaste = sink.currentTaste()
        let failed = SyncJoinCommit.failed("本机保存失败，未加入；本机数据保持原样")
        func rollback(restoreFiles: Bool) -> SyncJoinCommit {
            _ = env.keyStore.delete()
            _ = env.stateStore.delete()
            if restoreFiles {
                _ = sink.saveCuration(local.curation)
                _ = sink.saveTaste(originalTaste)
            }
            return failed
        }
        guard env.keyStore.write(SyncKeychainEntry(key: t.code.key, dev: t.dev, blobID: t.keys.blobID)) == .ok else {
            return rollback(restoreFiles: false)
        }
        var state = SyncState(phase: .active, origin: t.code.origin, blobID: t.keys.blobID, clock: t.replica.clock, doc: t.replica.doc)
        state.epoch = t.remoteEpoch
        state.lastSeenVer = ver
        state.lastSeenHash = envelopeHash
        state.maxSentL = t.replica.doc.allRegisters.map(\.hlc.l).max() ?? 0
        state.migrated = true
        guard env.stateStore.save(state) else { return rollback(restoreFiles: false) }
        guard SyncLifecycle.barrier(state.doc, sink: sink) else { return rollback(restoreFiles: true) }
        noteReport(t.report)
        activate(state, keys: t.keys)
        onDocumentChanged?(state.doc)
        return .joined
    }

    /// 404 时「仍用此码创建」：等同于用这个 `K` 开启同步（第 12.3 节）。
    func createWithCode(_ code: SyncCode, local: SyncLocalData) async -> SyncCreateResult {
        await create(key: code.key, origin: code.origin, local: local)
    }
}
#endif
