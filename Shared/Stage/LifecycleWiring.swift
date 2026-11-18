import Foundation
import os

/// 统一挂起的运行时状态，挂在 `StageModel.wiring` 上（扩展里不能有存储属性）。
struct LifecycleWiring {
    /// 统一挂起代次（§3.1）。macOS 从不挂起，所有检查恒放行。
    var epoch = LifecycleEpoch()
    /// 进后台时记下的、回前台要重做的共享工作。
    var resumeIntent: PhoneResumeIntent?
    /// 进后台那一刻每路在做什么。挂起会取消起播任务，回前台时现场已读不到，所以先记下。
    var suspendedActivity: [PlaybackLaneRole: PhoneLaneActivity] = [:]
    /// 最近一次全站搜索的请求，回前台重做 `.search` 用。
    var lastSiteSearch: (keyword: String, fallback: Room?)?
    /// 经 `trackShared` 跑的在途共享工作（目录重载、翻页、上次开播、热门标签），挂起时逐个取消。
    var sharedWork: [UUID: Task<Void, Never>] = [:]
}

@MainActor
extension StageModel {
    static let lifecycleLog = Logger(subsystem: "com.vela.stage", category: "lifecycle")

    var lifecycleSuspended: Bool { wiring.epoch.suspended }

    // MARK: 校验点（§3.1：发请求前、提交结果前、注册中继源前、中继 start 前）

    /// 开工一个共享任务。挂起（含只放行画中画那一路）时为 nil，调用方直接放弃。
    func sharedToken() -> LifecycleEpoch.Token? {
        wiring.epoch.begin()
    }

    /// 开工某一路的工作。全量挂起时为 nil；画中画只放行那一路。
    func laneToken(_ lane: PlaybackLane) -> LifecycleEpoch.Token? {
        wiring.epoch.begin(lane: lane.id.role)
    }

    func lifecycleAllows(_ gate: LifecycleEpoch.Gate, _ token: LifecycleEpoch.Token?) -> Bool {
        guard let token else { return false }
        return wiring.epoch.allows(gate, token: token)
    }

    /// 在可取消的子任务里跑一段共享工作：挂起时取消它，在途请求随之取消。
    /// 取消只是让请求早点停，提交前仍要各自核对 token。
    func trackShared(_ work: @escaping @MainActor () async -> Void) async {
        let id = UUID()
        let task = Task { @MainActor in await work() }
        wiring.sharedWork[id] = task
        await withTaskCancellationHandler {
            await task.value
        } onCancel: {
            task.cancel()
        }
        wiring.sharedWork[id] = nil
    }

    // MARK: 快照

    func lane(for role: PlaybackLaneRole) -> PlaybackLane? {
        lanes.first { $0.id.role == role }
    }

    /// 此刻这一路在做什么：刷新优先；画面已在播是 playing；有起播任务时，已出过别的房间的画面算切房，否则算首次解析。
    func activity(of lane: PlaybackLane) -> PhoneLaneActivity {
        if lane.player.refreshPending { return .refreshing }
        guard let current = name(of: lane) else { return .idle }
        if lane.playingName == current { return .playing }
        guard lane.playTask != nil else { return .idle }
        if let shown = lane.readyName, shown != current { return .roomSwitch }
        return .firstResolve
    }

    /// 回前台判断用的一路快照。挂起期间用进后台时记下的活动。
    func phoneSnapshot(of lane: PlaybackLane) -> PhoneLaneSnapshot {
        PhoneLaneSnapshot(
            role: lane.id.role,
            username: name(of: lane),
            manuallyPaused: lane.manuallyPaused,
            visible: lane.visible,
            failed: lane.state == .failed,
            activity: wiring.suspendedActivity[lane.id.role] ?? activity(of: lane)
        )
    }

    func phoneSnapshots() -> [PhoneLaneSnapshot] {
        lanes.map { phoneSnapshot(of: $0) }
    }

    /// 进后台这一刻在做、回前台要重做的共享工作：全站搜索优先，其次目录加载。
    func currentResumeIntent() -> PhoneResumeIntent? {
        if siteSearchTask != nil, wiring.lastSiteSearch != nil { return .search }
        return loading ? .reload : nil
    }

    // MARK: 挂起与恢复

    /// 挂起 §3.1 列出的异步工作。`lane` 非 nil 时只放行这一路（画中画），共享任务照样挂起。
    func suspendWork(allowingLane lane: PlaybackLaneRole? = nil) {
        if !wiring.epoch.suspended {
            wiring.suspendedActivity = Dictionary(uniqueKeysWithValues: lanes.map { ($0.id.role, activity(of: $0)) })
        }
        if let lane {
            wiring.epoch.suspend(allowingLane: lane)
        } else {
            wiring.epoch.suspend()
        }
        PlaylistRelay.shared.allowStart = wiring.epoch.allowStart
        // 代次加一：已在途的目录结果即使漏过某个校验点，也会被原有的代次检查丢掉。
        loadGeneration += 1
        for task in wiring.sharedWork.values { task.cancel() }
        invalidateCatalogRest()
        targetedTask?.cancel()
        targetedTask = nil
        if siteSearchTask != nil {
            searchEpoch.invalidate()
            siteSearchTask?.cancel()
            siteSearchTask = nil
            _ = searchEpoch.releaseLoading()
        }
        loading = false
        favoriteRunner.cancel()
        sidecar.suspendAll()
        RoomThumbnailCache.shared.suspendAll()
        for each in lanes where each.id.role != lane {
            cancelLaneWork(each)
        }
        // 预取是为别的房间发请求：画中画放行的那一路也不留。
        for each in lanes {
            each.prefetchTask?.cancel()
            each.prefetchTask = nil
        }
        let allowing = lane.map { Self.label($0) } ?? "none"
        Self.lifecycleLog.info("epoch suspend allowing=\(allowing, privacy: .public)")
    }

    /// 取消这一路自己的起播、预取、跳过、离开重试、关联与换档任务；代次加一，在途的续期、刷新结果作废。
    func cancelLaneWork(_ lane: PlaybackLane) {
        lane.playTask?.cancel()
        lane.playTask = nil
        lane.prefetchTask?.cancel()
        lane.prefetchTask = nil
        lane.skipTask?.cancel()
        lane.skipTask = nil
        lane.awayTask?.cancel()
        lane.awayTask = nil
        lane.awayTimer.clear()
        lane.relatedTask?.cancel()
        lane.relatedTask = nil
        lane.qualityTask?.cancel()
        lane.qualityTask = nil
        lane.capTask?.cancel()
        lane.capTask = nil
        lane.playGeneration += 1
    }

    /// 回到前台：允许新的工作与中继启动，侧车与缩略图恢复；停留重新进入当前房间。
    func resumeWork() {
        wiring.epoch.resume()
        PlaylistRelay.shared.allowStart = wiring.epoch.allowStart
        sidecar.resumeAll()
        RoomThumbnailCache.shared.resumeAll()
        wiring.suspendedActivity = [:]
        for lane in lanes {
            _ = lane.dwell.switchTo(room(of: lane), now: Date())
            lane.dwell.setLedgerExcluded(taste.paused, now: Date())
        }
        Self.lifecycleLog.info("epoch resume")
    }

    /// 停留结算（进后台）：每路结算到此刻，学习与账本照常记。
    func settleDwell(_ reason: LaneSettlement.EndReason) {
        endView(reason: reason)
        let text = String(describing: reason)
        Self.lifecycleLog.info("dwell settled reason=\(text, privacy: .public)")
    }

    func flushSavers() {
        tasteSaver.flush()
        curationSaver.flush()
        watchLedgerSaver.flush()
    }

    /// 每路立刻释放播放项与中继源（`suspendNow` 补写 `pausedAt`、置 `allowed = false`）。
    func suspendLanes() {
        for lane in lanes { release(lane) }
    }

    func release(_ lane: PlaybackLane) {
        cancelLaneWork(lane)
        lane.player.suspendNow()
        let name = Self.label(lane.id.role)
        Self.lifecycleLog.info("lane suspend lane=\(name, privacy: .public)")
    }

    func redo(_ intent: PhoneResumeIntent) {
        wiring.resumeIntent = nil
        switch intent {
        case .reload:
            Task { await reload() }
        case .search:
            if let last = wiring.lastSiteSearch {
                startSiteSearch(last.keyword, fallback: last.fallback)
            } else {
                Task { await reload() }
            }
        }
    }

    /// 由 Stage 层重新起播（§3.2）：按这一路的房间重新解析，不读播放器的 `lastPlay`。
    func resumePlay(_ lane: PlaybackLane, activity: PhoneLaneActivity) {
        lane.player.forgetSuspendedResume()
        // 回前台是资源降级扣住的规定清除入口之一（§4.4），先放开再起播。
        releaseDegradedHold(lane, .foregroundResume)
        requestPlay(on: lane)
        let name = Self.label(lane.id.role)
        let doing = String(describing: activity)
        Self.lifecycleLog.info("lane resume lane=\(name, privacy: .public) activity=\(doing, privacy: .public)")
    }

    nonisolated static func label(_ role: PlaybackLaneRole) -> String {
        role.label
    }
}
