import Foundation

/// 钥匙串（或 Keystore）里的条目读取结果（第 12.1、13 节）。条目内容 `{K, dev, blobID}` 由 S2 定义，这里只关心 blobID。
public enum SyncKeychainRead: Equatable, Sendable {
    case none
    case present(blobID: String)
    /// 首次解锁前、Keystore 暂时性错误：本次不做任何判断，跳过同步。
    case unavailable
    /// 永久失效（密钥丢失、解包失败）：「同步密钥丢失，请重新输入同步码」。
    case invalidated
}

public protocol SyncKeychain: AnyObject {
    func read() -> SyncKeychainRead
    func delete() -> Bool
}

/// 投影落盘屏障要用的本机存储（第 12.2 节）。
public protocol SyncLocalSink: AnyObject {
    /// 取消 `curationSaver` 与 `tasteSaver` 中尚未写出的值。
    func cancelPendingSaves()
    /// 当前内存里的口味（投影只改 `vetoed`，其余学习数据保留）。
    func currentTaste() -> Taste
    /// 直接调用 `CurationStore.save(_:)`。
    func saveCuration(_ curation: Curation) -> Bool
    /// 直接调用 `TasteStore.save(_:)`。
    func saveTaste(_ taste: Taste) -> Bool
    /// 按投影写入设置并 `synchronize()`。
    func applySettings(_ settings: [String: Bool])
}

/// 启动时组合状态的处理（第 12.1 节表格）。
public enum SyncStartupAction: Equatable, Sendable {
    case notEnabled
    /// 无状态、有条目：加入或创建在写状态之前中断（加入是一次性事务，第 12.4 节）。删除条目，未开启。
    case orphanEntry
    /// `creating` 无条目：删除状态，未开启。
    case discardCreating
    /// `creating` 有条目且一致：从创建第 5 步继续。
    case resumeCreating
    /// `active` 无条目：「同步密钥丢失，请重新输入同步码」。
    case keyLost
    case normal
    /// `disabling`：先屏障，再继续关闭流程。
    case finishDisabling
    /// `clearing` 有条目：先屏障，再继续清除流程。
    case finishClearing
    /// `clearing` 无条目：先屏障，再继续关闭流程的剩余步骤。
    case finishClearingLocally
    /// 条目 blobID 与状态不一致：停止同步，提示关闭再重开，不自动选择。
    case mismatch
    /// 钥匙串暂时不可用：本次跳过。
    case skip
    /// 同步状态读失败：「暂停：同步状态损坏」，不推送。
    case stateCorrupted
}

public enum SyncFlowResult: Equatable, Sendable {
    case done
    /// 屏障或持久化失败：同步状态与钥匙串都没动，「本机保存失败，未做任何更改」。
    case localSaveFailed
    /// 钥匙串或状态文件删除失败：保留当前 `phase`，下次启动按表格继续。
    case storageFailed
    /// 清除云端时网络暂时失败：保持 `clearing`，稍后重试。
    case remotePending
    /// 清除云端被拒（403）：保持 `clearing`。
    case remoteRejected
}

public enum SyncLifecycle {
    /// 第 12.1 节表格。钥匙串暂时不可用时不做任何判断；`disabling` 不看钥匙串；之后才查 blobID 是否一致。
    /// TODO(sync.md §12.1)：表格里「任意 / 有，blobID 不一致」与 `disabling | 任意` 的优先级未写明，这里让 `disabling` 优先。
    public static func startupAction(state: SyncStateRead, keychain: SyncKeychainRead) -> SyncStartupAction {
        if keychain == .unavailable { return .skip }
        let entry: String?
        if case .present(let id) = keychain { entry = id } else { entry = nil }
        let s: SyncState
        switch state {
        case .none: return entry == nil ? .notEnabled : .orphanEntry
        case .corrupted: return .stateCorrupted
        case .present(let loaded): s = loaded
        }
        if s.phase == .disabling { return .finishDisabling }
        if let entry, entry != s.blobID { return .mismatch }
        let has = entry != nil
        switch s.phase {
        case .creating: return has ? .resumeCreating : .discardCreating
        case .active: return has ? .normal : .keyLost
        case .clearing: return has ? .finishClearing : .finishClearingLocally
        case .disabling: return .finishDisabling
        }
    }

    // MARK: 投影落盘屏障

    /// 第 12.2 节：取消延迟保存 → 由文档重算投影 → 直接保存，两者都成功才通过。可重复执行。
    public static func barrier(_ doc: SyncDocument, sink: SyncLocalSink) -> Bool {
        sink.cancelPendingSaves()
        let projection = SyncProjection(doc)
        let taste = projection.applying(to: sink.currentTaste())
        let curationSaved = sink.saveCuration(projection.curation)
        let tasteSaved = sink.saveTaste(taste)
        guard curationSaved, tasteSaved else { return false }
        sink.applySettings(projection.settings)
        return true
    }

    // MARK: 本机关闭与清除（第 12.6 节）

    /// 屏障 → `phase = disabling` → 删钥匙串条目 → 删同步状态。
    public static func disable(_ state: SyncState, store: SyncStateStore, keychain: SyncKeychain, sink: SyncLocalSink) -> SyncFlowResult {
        guard barrier(state.doc, sink: sink) else { return .localSaveFailed }
        var next = state
        next.phase = .disabling
        guard store.save(next) else { return .localSaveFailed }
        return finishDisable(store: store, keychain: keychain)
    }

    /// 关闭流程写 `disabling` 之后的部分；删除同步状态放在最后一步。
    static func finishDisable(store: SyncStateStore, keychain: SyncKeychain) -> SyncFlowResult {
        guard keychain.delete() else { return .storageFailed }
        guard store.delete() else { return .storageFailed }
        return .done
    }

    /// 屏障 → `phase = clearing` → 签名 delete（409 用响应里的版本重试；410 或 404 视为已完成）→ 本机关闭的剩余步骤。
    public static func clear(
        _ state: SyncState, store: SyncStateStore, keychain: SyncKeychain, sink: SyncLocalSink, transport: SyncTransport
    ) async -> SyncFlowResult {
        guard barrier(state.doc, sink: sink) else { return .localSaveFailed }
        var next = state
        next.phase = .clearing
        guard store.save(next) else { return .localSaveFailed }
        return await finishClear(next, store: store, keychain: keychain, transport: transport)
    }

    static func finishClear(_ state: SyncState, store: SyncStateStore, keychain: SyncKeychain, transport: SyncTransport) async -> SyncFlowResult {
        var epoch = state.epoch
        var base = state.lastSeenVer
        // 还没有 epoch：不能证明空间不存在（别的设备可能已用同一码创建），先 GET 核实（复审 medium）。
        // 404 或 410 才本机关闭；空间存在就用它的 epoch 与版本签名删除；网络失败保持 clearing。
        if epoch == nil {
            switch await transport.get() {
            case .notFound, .gone:
                var disabling = state
                disabling.phase = .disabling
                guard store.save(disabling) else { return .storageFailed }
                return finishDisable(store: store, keychain: keychain)
            case .blob(let ver, _, _, let e, _), .conflict(let ver, _, _, let e, _):
                epoch = e
                base = ver
            default:
                return .remotePending
            }
        }
        for _ in 0...SyncCoordinator.conflictRetries {
            switch await transport.delete(base: base, epoch: epoch) {
            case .deleted, .gone, .notFound:
                var disabling = state
                disabling.phase = .disabling
                guard store.save(disabling) else { return .storageFailed }
                return finishDisable(store: store, keychain: keychain)
            case .conflict(let ver, _, _, _, _):
                base = ver
            case .forbidden:
                return .remoteRejected
            default:
                return .remotePending
            }
        }
        return .remotePending
    }

    /// 启动时执行表格里需要自动处理的几行；其余（需要用户选择或交给协调器的）原样返回动作。
    public static func recover(
        store: SyncStateStore, keychain: SyncKeychain, sink: SyncLocalSink, transport: SyncTransport
    ) async -> (action: SyncStartupAction, result: SyncFlowResult?) {
        let read = store.load()
        let action = startupAction(state: read, keychain: keychain.read())
        if action == .orphanEntry { return (action, keychain.delete() ? .done : .storageFailed) }
        guard case .present(let state) = read else { return (action, nil) }
        switch action {
        case .discardCreating:
            return (action, store.delete() ? .done : .storageFailed)
        case .finishDisabling:
            guard barrier(state.doc, sink: sink) else { return (action, .localSaveFailed) }
            return (action, finishDisable(store: store, keychain: keychain))
        case .finishClearing:
            guard barrier(state.doc, sink: sink) else { return (action, .localSaveFailed) }
            return (action, await finishClear(state, store: store, keychain: keychain, transport: transport))
        case .finishClearingLocally:
            guard barrier(state.doc, sink: sink) else { return (action, .localSaveFailed) }
            var disabling = state
            disabling.phase = .disabling
            guard store.save(disabling) else { return (action, .storageFailed) }
            return (action, finishDisable(store: store, keychain: keychain))
        default:
            return (action, nil)
        }
    }

    /// 创建第 4 步：全部盖章，执行 `E` 与体积检查，`phase = active`。
    public static func completeCreate(_ state: SyncState, local: SyncLocalData, pt: UInt64) -> (state: SyncState, report: SyncMigrationReport) {
        var next = state
        var report = SyncMigrationReport()
        if !state.migrated {
            let stamped = SyncMigration.stampCreator(local, clock: state.clock, pt: pt)
            next.replica = stamped.replica
            next.oversize = !stamped.fits
            next.migrated = true
            next.gen += 1
            report = stamped.report
        }
        next.phase = .active
        return (next, report)
    }
}

/// 敏感剪贴板的最小抽象（macOS 由 `NSPasteboard.general` 实现，测试用内存桩注入）。
public protocol SyncClipboard: AnyObject {
    var changeCount: Int { get }
    /// 原子写入带敏感标记的文本，并返回写入后的 `changeCount`。
    func writeConcealed(_ text: String) -> Int
    func clear()
}

/// 剪贴板清理记录的持久化抽象（macOS 由 `UserDefaults.standard` 实现，测试用内存桩注入）。
public protocol SyncClipboardStampStore: AnyObject {
    var changeCount: Int? { get set }
    var token: String? { get set }
}

/// macOS 同步码剪贴板清理控制器（docs/plans/sync.md 第 4.3、23 节）。
/// 通过可注入的剪贴板与存储接口统一承担复制、120 秒到期、退出清理与启动清理：
/// 首次 `copySensitive` 时注册独立于实验开关的退出回调（重复复制只注册一次），关闭实验开关不撤销；
/// 清理时仅在 `clipboard.changeCount == store.changeCount` 时清空，不动用户之后复制的别的内容。
public final class SyncPasteboardController {
    private let clipboard: SyncClipboard
    private let store: SyncClipboardStampStore
    private let makeToken: () -> String
    private let scheduleExpiry: (_ delaySeconds: TimeInterval, _ action: @escaping () -> Void) -> Void
    private let registerExit: (_ action: @escaping () -> Void) -> Void
    private var exitRegistered = false

    public init(
        clipboard: SyncClipboard,
        store: SyncClipboardStampStore,
        makeToken: @escaping () -> String = { UUID().uuidString },
        scheduleExpiry: @escaping (_ delaySeconds: TimeInterval, _ action: @escaping () -> Void) -> Void,
        registerExit: @escaping (_ action: @escaping () -> Void) -> Void
    ) {
        self.clipboard = clipboard
        self.store = store
        self.makeToken = makeToken
        self.scheduleExpiry = scheduleExpiry
        self.registerExit = registerExit
    }

    /// 写入带敏感标记的同步码，记录当前 `changeCount` 与令牌，确保退出清理已注册，并排一次性到期任务。
    public func copySensitive(_ text: String, expirySeconds: TimeInterval = 120) {
        let stamp = clipboard.writeConcealed(text)
        let token = makeToken()
        store.changeCount = stamp
        store.token = token
        if !exitRegistered {
            exitRegistered = true
            registerExit { [weak self] in self?.expireNow() }
        }
        scheduleExpiry(expirySeconds) { [weak self] in self?.expire(onlyIf: token) }
    }

    /// 到期：只在记录的令牌仍是这次复制时才尝试清理；之后又复制过就什么都不做。
    public func expire(onlyIf token: String) {
        guard store.token == token else { return }
        expireNow()
    }

    /// 剪贴板仍是本 App 最近一次写入的同步码就清空（退出、启动、到期时调用）。
    public func expireNow() {
        guard let stamp = store.changeCount else { return }
        if clipboard.changeCount == stamp { clipboard.clear() }
        store.changeCount = nil
        store.token = nil
    }

    /// 启动时检查上次进程异常退出留下的同步码（未复制过时记录为空，不碰剪贴板）。
    public func expireLeftover() { expireNow() }
}
