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
    /// 无状态、有条目：「同步状态丢失」，提供「用同一同步码重新加入」或「关闭」。
    case stateLost
    /// `creating` 无条目：删除状态，未开启。
    case discardCreating
    /// `creating` 有条目且一致：从创建第 5 步继续。
    case resumeCreating
    /// `joining` 无条目：删除状态，未开启，提示重新粘贴。
    case discardJoining
    /// `joining` 有条目且一致：按第 12.4 节「恢复」继续。
    case resumeJoining
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
        case .none: return entry == nil ? .notEnabled : .stateLost
        case .corrupted: return .stateCorrupted
        case .present(let loaded): s = loaded
        }
        if s.phase == .disabling { return .finishDisabling }
        if let entry, entry != s.blobID { return .mismatch }
        let has = entry != nil
        switch s.phase {
        case .creating: return has ? .resumeCreating : .discardCreating
        case .joining: return has ? .resumeJoining : .discardJoining
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
        var base = state.lastSeenVer
        for _ in 0...SyncCoordinator.conflictRetries {
            switch await transport.delete(base: base, epoch: state.epoch) {
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
        guard case .present(let state) = read else { return (action, nil) }
        switch action {
        case .discardCreating, .discardJoining:
            return (action, store.delete() ? .done : .storageFailed)
        case .resumeJoining where state.joinPlan == nil:
            // 确认前崩溃：删除钥匙串条目与状态，提示重新粘贴（第 12.4 节第 7 步）。
            return (action, finishDisable(store: store, keychain: keychain))
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

    // MARK: 加入恢复（第 12.4 节第 7 步）

    public enum JoinRecovery: Equatable, Sendable {
        /// 远端未变，或变了但计数完全相同：直接执行盖章与合并。
        case proceed
        /// 计数不同：重新展示确认页；确认后用新计数覆盖 `joinPlan`，取消则本机关闭。
        case reconfirm(SyncJoinPreview)
        /// `joinPlan` 不存在：删除钥匙串条目与状态，提示重新粘贴。
        case abort
    }

    public static func joinRecovery(plan: SyncJoinPlan?, remoteVer: UInt64, remoteHash: String, recomputed: SyncJoinPreview) -> JoinRecovery {
        guard let plan else { return .abort }
        if plan.ver == remoteVer, plan.envelopeHash == remoteHash { return .proceed }
        return plan.preview == recomputed ? .proceed : .reconfirm(recomputed)
    }

    /// 确认后的盖章与合并：迁移标记防止重复盖章，结束时 `phase = active`、清空 `joinPlan`。
    public static func completeJoin(_ state: SyncState, local: SyncLocalData, remote: SyncDocument, pt: UInt64) -> SyncState {
        var next = state
        if !state.migrated {
            let stamped = SyncMigration.stampJoin(local, remote: remote, clock: state.clock, pt: pt)
            next.replica = stamped.replica
            next.oversize = !stamped.fits
            next.migrated = true
            next.gen += 1
        }
        next.phase = .active
        next.joinPlan = nil
        return next
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
