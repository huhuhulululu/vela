import Combine
import Foundation
#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
#endif

/// 跨设备同步在界面层的状态与接线（docs/plans/sync.md 第 11–13、16 节）。
/// 联网只发生在离散的触发点：启动或回前台、本地操作去抖之后、进后台或退出前、打开收藏或口味面板、
/// 点「立即同步」。没有任何常驻定时器、轮询或常驻连接；未开启时不发任何请求。
@MainActor
final class SyncHub: ObservableObject {
    static let publicOnlyKey = "publicOnly"
    static let infoAlwaysOnKey = "infoAlwaysOn"
    static let fullMessage = "同步数据已满，请删掉一些收藏、屏蔽或不想看"

    enum Phase: Equatable {
        case loading
        case off
        /// 本构建缺钥匙串授权：不支持同步。
        case unsupported
        /// 钥匙串暂时不可用（首次解锁前）：本次跳过。
        case unavailable
        case active
        /// 启动检查需要用户处理：显示文案，提供关闭或重新加入。
        case attention(String)
    }

    enum JoinStage {
        case idle
        case checking
        case preview(SyncJoinTicket)
        case notFound(SyncCode)
        case failed(String)
    }

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var fingerprint: String?
    @Published private(set) var lastSuccessAt: Date?
    @Published private(set) var hasPending = false
    @Published private(set) var failure: String?
    @Published private(set) var busy = false
    @Published private(set) var joinStage: JoinStage = .idle
    /// 创建后「保存你的同步码」页要显示的码；用户点「我已保存」后清空。
    @Published private(set) var savedCode: String?
    @Published private(set) var notice: String?

    private(set) var service: SyncService
    private var scheduler = SyncScheduler()
    private weak var model: StageModel?
    private var sink: SyncModelSink?
    private var editTask: Task<Void, Never>?
    private var cycleTask: Task<Void, Never>?
    private var rerun = false
    private var observers: [NSObjectProtocol] = []
    private var panelWatch: [AnyCancellable] = []

    /// 构建注入的默认服务器地址（`Config/Local.xcconfig` 的 `VELA_SYNC_BASE`）。没有配置时隐藏「开启」，但可以「加入」。
    static let configuredOrigin: String? = {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "VelaSyncBase") as? String else { return nil }
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return SyncCode.isCanonicalOrigin(text) ? text : nil
    }()

    init(service: SyncService? = nil) {
        self.service = service ?? SyncService(env: SyncHub.defaultEnvironment())
    }

    /// 加入提交的文件快照：收藏、口味文件与两个同步设置，放在同步状态同一目录下（同卷）。
    static func journal(settings: SettingsStore) -> SyncJoinJournal {
        let keys = [publicOnlyKey, infoAlwaysOnKey]
        return FileJoinJournal(
            dir: SyncStateFileStore.defaultURL().deletingLastPathComponent().appendingPathComponent("sync-join-snapshot"),
            files: [CurationStore.defaultURL(), TasteStore.defaultURL()],
            settingKeys: keys,
            readSettings: { keys.reduce(into: [:]) { out, k in out[k] = settings.explicitBool(forKey: k) } },
            writeSettings: { values in for (k, v) in values { settings.setExplicit(k, v) } }
        )
    }

    static func defaultEnvironment() -> SyncService.Environment {
        SyncService.Environment(
            stateStore: SyncBackupExcludedStore(SyncStateFileStore(url: SyncStateFileStore.defaultURL())),
            keyStore: SyncKeychainStore(),
            makeTransport: { origin, keys in
                SyncHTTPTransport(base: URL(string: origin) ?? URL(fileURLWithPath: "/"), keys: keys)
            }
        )
    }

    var isOn: Bool { phase == .active }
    var canCreate: Bool { Self.configuredOrigin != nil }

    private var nowMillis: UInt64 { UInt64(Date().timeIntervalSince1970 * 1000) }

    private func set<T: Equatable>(_ path: ReferenceWritableKeyPath<SyncHub, T>, _ value: T) {
        if self[keyPath: path] != value { self[keyPath: path] = value }
    }

    // MARK: 接线

    /// 实验开关（默认关）：关着时不显示同步入口、不联网、不读钥匙串（第 1 节）。
    @Published private(set) var experimentEnabled = false
    /// 加入提交中：本机写操作一律被拒，界面置灰并显示「正在加入同步…」。
    @Published private(set) var committing = false
    static let committingMessage = "正在加入同步…"

    func attach(_ model: StageModel) {
        self.model = model
        set(\.experimentEnabled, model.settings.experimentSync)
        guard experimentEnabled else { return }
        wire(model)
    }

    /// 设置里的「实验：跨设备同步」开关。同步已开启时不能直接关（先关闭同步，免得之后再开时本机改动被投影覆盖）。
    /// 启动恢复、开启、加入等流程没结束，或上次加入待修复时，同样拒绝关闭（第 23 节第 14 条：选「拒绝」而不是
    /// 「关的同时停协调器」，停了之后磁盘上仍是已开启，再打开时投影会覆盖期间的本机改动）。
    func setExperiment(_ on: Bool) {
        guard let model, on != experimentEnabled else { return }
        // 磁盘上还有同步状态（例如钥匙串暂时不可用而停在暂停）也算开启着（H9）：关掉开关后的本机编辑，
        // 再开开关时会被旧文档的投影覆盖。
        if !on, phase == .active || service.isActive || service.hasStoredState {
            set(\.notice, "请先关闭同步，再关闭实验开关。")
            return
        }
        if !on, phase == .loading || starting || busy || committing || service.joinRepairPending {
            set(\.notice, "同步正在处理中，请等它结束（或先关闭同步）再关闭实验开关。")
            return
        }
        model.settings.experimentSync = on
        set(\.experimentEnabled, on)
        if on { wire(model) } else { unwire() }
    }

    private var wired = false

    private func wire(_ model: StageModel) {
        guard !wired else { return }
        wired = true
        sink = SyncModelSink(model: model)
        service.onDocumentChanged = { [weak self] doc in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.documentChanged(doc) } }
        }
        RoomInfoPrefs.shared.onUserToggle = { [weak model] value in model?.syncSetting(SyncHub.infoAlwaysOnKey, value) }
        RoomInfoPrefs.shared.allowsToggle = { [weak model] in !(model?.rejectWhileJoining() ?? false) }
        service.configureJournal(Self.journal(settings: model.settings))
        panelWatch = [
            model.$showCuration.removeDuplicates().filter { $0 }.sink { [weak self] _ in self?.trigger(.panel) },
            model.$showTaste.removeDuplicates().filter { $0 }.sink { [weak self] _ in self?.trigger(.panel) },
        ]
        observeLifecycle()
        launchStart()
    }

    /// 排启动恢复：排上的那一刻就进入「启动中」（`starting`，实验开关此时拒绝关闭），不留任务开跑之前的空隙（H5'）。
    private func launchStart() {
        guard !starting else { return }
        starting = true
        let id = UUID()
        startID = id
        startTask = Task { [weak self] in
            await self?.start()
            self?.migrateSiteKeysAfterStart(id: id)
            // 只有当前这一次启动结束才退出「启动中」：摘线后迟到的旧任务不改它。
            if self?.startID == id { self?.starting = false }
        }
    }

    private var startID: UUID?

    /// 启动恢复结束后补做站点键迁移（启动时因残留加入快照推迟的那次）。
    /// 摘线、取消或已被新启动取代时放弃；执行前先冲刷延迟保存并持三把文件锁，避免与后台保存竞态覆写。
    private func migrateSiteKeysAfterStart(id: UUID) {
        guard wired, !Task.isCancelled, startID == id, let model else { return }
        guard StageModel.shouldMigrateSiteKeys(
            version: model.settings.siteKeyVersion,
            journal: service.joinJournalStatus,
            localWriteAllowed: service.localWriteAllowed
        ) else { return }
        model.curationSaver.flush()
        model.tasteSaver.flush()
        model.watchLedgerSaver.flush()
        model.curationStore.withFileLock {
            model.tasteStore.withFileLock {
                model.watchLedgerStore.withFileLock {
                    StageModel.migrateSiteKeysIfAllowed(
                        model.settings,
                        journal: service.joinJournalStatus,
                        localWriteAllowed: service.localWriteAllowed,
                        curation: model.curationStore.url,
                        taste: model.tasteStore.url,
                        ledger: model.watchLedgerStore.url
                    )
                }
            }
        }
    }

    /// 启动恢复的任务：摘线时取消。
    private var startTask: Task<Void, Never>?

    /// 流程在等待之后发现已摘线（实验开关已关）：不 apply、不激活；服务里若已挂上协调器就摘掉（磁盘不动）。
    private func abandonedAfterAwait() -> Bool {
        guard wired, !Task.isCancelled else {
            if service.isActive { service.suspend() }
            return true
        }
        return false
    }

    private func unwire() {
        guard wired else { return }
        wired = false
        cancelJoin()
        startTask?.cancel()
        startTask = nil
        startID = nil
        starting = false
        editTask?.cancel()
        editTask = nil
        for o in observers { NotificationCenter.default.removeObserver(o) }
        observers = []
        panelWatch = []
        RoomInfoPrefs.shared.onUserToggle = nil
        RoomInfoPrefs.shared.allowsToggle = nil
        set(\.phase, .loading)
    }

    private func observeLifecycle() {
        let center = NotificationCenter.default
        #if os(macOS)
        observers.append(center.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.trigger(.activate) }
        })
        observers.append(center.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: .main) { [weak self] _ in
            // 退出作废正在进行的加入；没写「已开启」就是未加入。
            MainActor.assumeIsolated {
                self?.cancelJoin()
                self?.pushBeforeExit()
            }
            SyncPasteboard.expireNow()
        })
        SyncPasteboard.expireLeftover()
        #elseif os(iOS)
        observers.append(center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.trigger(.foreground) }
        })
        observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            // 挂起作废正在进行的加入（提交锁随之释放）。
            MainActor.assumeIsolated {
                self?.cancelJoin()
                self?.pushInBackground()
            }
        })
        #endif
    }

    /// 启动恢复在跑：防止离散触发重复启动。
    private var starting = false

    private func start() async {
        // 调用服务之前再确认一次：已摘线或任务被取消就不读钥匙串、不启动（磁盘为已开启时也不会被激活）。
        guard wired, !Task.isCancelled, let model, let sink else { return }
        let local = model.syncLocalData()
        let result = await service.start(local: { local }, sink: sink)
        if abandonedAfterAwait() { return }
        apply(result)
    }

    private func apply(_ result: SyncStartResult) {
        switch result {
        case .notEnabled:
            set(\.phase, .off)
            // 换回快照后的重读已在服务里经 `SyncModelSink.reloadAfterRollback` 做完（删快照之前）。
            if service.rolledBackAtStartup { set(\.notice, "上次加入同步没有完成，已恢复本机数据。") }
        case .needsRepair(let text): set(\.phase, .attention(text))
        case .unsupported: set(\.phase, .unsupported)
        case .unavailable: set(\.phase, .unavailable)
        case .active:
            activated()
            trigger(.launch)
        case .attention(let action): set(\.phase, .attention(SyncStatusText.startup(action) ?? "同步需要处理，请关闭后重新开启。"))
        case .failed(let flow): set(\.phase, .attention(Self.flowText(flow)))
        case .stopped(let text): set(\.phase, .attention(text))
        }
    }

    /// 开启或恢复之后：投影覆盖本机收藏与「不想看」（第 11.1 节），刷新状态。
    private func activated() {
        set(\.phase, .active)
        // 创建、加入、中断恢复统一在这里落实本机专用「不想看」的 50 个额度（第 6.6 节）。
        // 按当前本机数据重算，不靠一次性记账：激活后中断、重启也会落实。
        if let model { model.dropLocalOnlyVetoes(SyncQuota.localOnlyExcess(vetoedKeys: model.taste.vetoedKeys)) }
        if let doc = service.state?.doc { documentChanged(doc) }
        refreshStatus()
    }

    private func documentChanged(_ doc: SyncDocument) {
        guard service.isActive else { return }
        model?.applySyncProjection(doc)
        refreshStatus()
    }

    private func refreshStatus() {
        guard let state = service.state else {
            set(\.fingerprint, nil)
            set(\.hasPending, false)
            return
        }
        set(\.fingerprint, SyncCode.fingerprint(blobID: state.blobID))
        set(\.hasPending, state.gen > state.pushedGen)
        set(\.lastSuccessAt, state.lastSuccessAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) })
        if let halt = state.halt { set(\.failure, SyncStatusText.halt(halt)) }
    }

    // MARK: 触发（第 11.3 节）

    func trigger(_ trigger: SyncScheduler.Trigger) {
        if SyncScheduler.retriesStartup(unavailable: phase == .unavailable, on: trigger), !starting {
            launchStart()
            return
        }
        guard isOn, service.isActive, scheduler.allows(trigger, now: nowMillis) else { return }
        guard cycleTask == nil else {
            rerun = true
            return
        }
        cycleTask = Task { [weak self] in
            guard let service = self?.service else { return }
            let result = await service.runCycle()
            self?.finishCycle(result)
        }
    }

    private func finishCycle(_ result: SyncCycleResult?) {
        cycleTask = nil
        guard let result else { return }
        scheduler.record(result, now: nowMillis)
        set(\.failure, SyncStatusText.cycle(result))
        refreshStatus()
        if rerun {
            rerun = false
            scheduleEdit()
        }
    }

    /// 本地操作：去抖 5 秒且距上次 PUT 至少 10 秒，然后一个周期。一次性任务，新操作取代旧的。
    private func scheduleEdit() {
        editTask?.cancel()
        let delay = scheduler.editDelay(now: nowMillis)
        editTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay * 1_000_000)
            guard !Task.isCancelled else { return }
            self?.editTask = nil
            self?.trigger(.localEdit)
        }
    }

    func syncNow() { trigger(.manual) }

    private var unpushed: Bool {
        guard let s = service.state else { return false }
        return scheduler.allowsBackground(gen: s.gen, pushedGen: s.pushedGen, now: nowMillis)
    }

    #if os(iOS)
    /// 进后台：有未推送的改动才尽力推一次，用 `beginBackgroundTask` 争取时间，不保证完成。
    private func pushInBackground() {
        guard isOn, unpushed, cycleTask == nil else { return }
        editTask?.cancel()
        editTask = nil
        var task = UIBackgroundTaskIdentifier.invalid
        task = UIApplication.shared.beginBackgroundTask(withName: "vela-sync") {
            UIApplication.shared.endBackgroundTask(task)
        }
        cycleTask = Task { [weak self] in
            let result = await self?.service.runCycle()
            self?.finishCycle(result)
            UIApplication.shared.endBackgroundTask(task)
        }
    }
    #endif

    #if os(macOS)
    /// 退出：有未推送的改动时最多等 3 秒。周期本身不需要主线程，所以这里可以阻塞等待。
    private func pushBeforeExit() {
        guard isOn, unpushed else { return }
        let done = DispatchSemaphore(value: 0)
        let service = self.service
        Task.detached {
            _ = await service.runCycle()
            done.signal()
        }
        _ = done.wait(timeout: .now() + 3)
    }
    #endif

    // MARK: 本地操作

    /// 写同步寄存器。返回 nil 表示同步未开启（调用方按原有本机逻辑处理）。
    func perform(_ op: SyncLocalOp, localOnlyVetoCount: Int = 0) -> SyncOpOutcome? {
        guard isOn, let result = service.perform(op, localOnlyVetoCount: localOnlyVetoCount) else { return nil }
        if case .applied = result.outcome {
            if !result.saved { set(\.failure, "同步状态保存失败，下次操作时重试。") }
            scheduleEdit()
        }
        refreshStatus()
        return result.outcome
    }

    // MARK: 开启（第 12.3 节）

    /// 开启前说明页要列出的内容；前置条件不满足时返回原因。
    func enablePreview() -> Result<SyncMigrationReport, SyncHubError> {
        guard let model else { return .failure(.message("尚未就绪")) }
        guard SyncMigration.canEnable(curation: model.curationStore.lastLoadOutcome, taste: model.tasteStore.lastLoadOutcome) else {
            return .failure(.message("收藏或口味文件读取异常，先处理后再开启同步。"))
        }
        let local = model.syncLocalData()
        return .success(SyncMigration.stampCreator(local, clock: SyncClock(dev: 1), pt: nowMillis).report)
    }

    func enable() async {
        guard let model, let origin = Self.configuredOrigin, !busy else { return }
        guard case .success = enablePreview() else { return }
        busy = true
        defer { busy = false }
        let result = await service.create(origin: origin, local: model.syncLocalData())
        if abandonedAfterAwait() { return }
        switch result {
        case .created(let code, _, _, let first):
            savedCode = code
            activated()
            scheduler.record(first, now: nowMillis)
            set(\.failure, SyncStatusText.cycle(first))
        case .failed(let text):
            set(\.notice, text)
        }
    }

    func dismissSavedCode() { savedCode = nil }

    /// 「查看同步码」页：调用方已通过设备密码或生物识别验证。
    func revealCode() -> String? { service.currentCode() }

    // MARK: 加入（第 12.4 节）

    func prepareJoin(_ text: String) async {
        guard let model, let sink, !busy else { return }
        busy = true
        defer { busy = false }
        // 本机已在某个空间（或残留状态）：先执行本机关闭（含屏障）。
        if phase != .off {
            let closed = service.disable(sink: sink)
            guard closed == .done else {
                joinStage = .failed(Self.flowText(closed))
                return
            }
            set(\.phase, .off)
        }
        guard SyncMigration.canEnable(curation: model.curationStore.lastLoadOutcome, taste: model.tasteStore.lastLoadOutcome) else {
            joinStage = .failed("收藏或口味文件读取异常，先处理后再加入。")
            return
        }
        joinStage = .checking
        joinGeneration += 1
        let generation = joinGeneration
        let prepared = await service.prepareJoin(text.trimmingCharacters(in: .whitespacesAndNewlines), local: model.syncLocalData())
        // 摘线，或检查期间被取消（关面板、进后台、离开加入页，M10）：迟到的结果丢弃，票据作废。
        guard !abandonedAfterAwait(), generation == joinGeneration else {
            if case .preview(let ticket) = prepared { ticket.cancel() }
            if generation == joinGeneration { joinStage = .idle }
            return
        }
        switch prepared {
        case .invalidCode: joinStage = .failed("同步码无效")
        case .notFound(let code): joinStage = .notFound(code)
        case .gone: joinStage = .failed("这个同步码对应的空间已被清除")
        case .preview(let ticket): joinStage = .preview(ticket)
        case .failed(let text): joinStage = .failed(text)
        }
    }

    /// 确认加入：一次性提交（第 12.4 节）。服务在上提交锁之后才取本机数据；提交期间本机写操作一律被拒。
    /// 离开加入页、退出或挂起都会作废这张票。
    func confirmJoin() async {
        guard let model, let sink, case .preview(let ticket) = joinStage, !busy else { return }
        let generation = joinGeneration
        busy = true
        set(\.committing, true)
        defer {
            busy = false
            set(\.committing, false)
        }
        // 加入成功时提交锁保持到这里完成激活（H8）；函数返回时一律释放（失败、取消在服务里已释放，重复释放无害）。
        defer { service.releaseJoinCommit() }
        let result = await service.commitJoin(ticket, local: { [weak model] in
            // 在协作线程上调用：回主线程取（主线程此时空闲，在等这次提交）。
            DispatchQueue.main.sync { MainActor.assumeIsolated { model?.syncLocalData() ?? SyncLocalData() } }
        }, sink: sink, holdLockAfterJoin: true)
        if abandonedAfterAwait() {
            joinStage = .idle
            return
        }
        switch result {
        case .joined:
            joinStage = .idle
            activated()
            // 快照没删掉（H2）：加入已完成；关闭、清除之前会先删它，删不掉就不继续。
            if service.env.journal.exists { set(\.notice, "已加入同步；临时文件没能删除，下次关闭或启动时再清理。") }
        case .cancelled:
            joinStage = .idle
            model.reloadSyncedSettingsFromStore()
        case .reconfirm(let fresh):
            // 重算期间被取消（离页、进后台）：不越过取消重新装上确认页（M11）。
            guard generation == joinGeneration, !ticket.isCancelled else {
                fresh.cancel()
                joinStage = .idle
                return
            }
            joinStage = .preview(fresh)
            set(\.notice, "云端或本机数据有变化，请再确认一次。")
        case .failed(let text):
            joinStage = .idle
            model.reloadSyncedSettingsFromStore()
            set(\.notice, text)
        case .needsRepair(let text):
            joinStage = .idle
            set(\.phase, .attention(text))
        }
    }

    /// 404 时「仍用此码创建」。
    func createFromJoin() async {
        guard let model, case .notFound(let code) = joinStage, !busy else { return }
        busy = true
        defer { busy = false }
        let result = await service.createWithCode(code, local: model.syncLocalData())
        if abandonedAfterAwait() { return }
        joinStage = .idle
        switch result {
        case .created(_, _, _, let first):
            activated()
            scheduler.record(first, now: nowMillis)
            set(\.failure, SyncStatusText.cycle(first))
        case .failed(let text):
            set(\.notice, text)
        }
    }

    /// 取消加入：作废当前票据（确认前什么都没写；提交途中则不落本机），回到未加入。
    func cancelJoin() {
        if case .preview(let ticket) = joinStage { ticket.cancel() }
        joinGeneration += 1
        joinStage = .idle
    }

    /// 检查阶段的代次：取消就加一，等 GET 回来时代次变了就丢弃结果。
    private var joinGeneration = 0

    // MARK: 关闭与清除（第 12.6 节）

    /// 关闭同步。上次加入待修复时这也是重试入口（H3）：服务先完成没做完的恢复（换回快照、删状态与钥匙串、
    /// 按文件重读本机数据），恢复不了就什么都不动、继续提示需要修复。
    func disable() {
        guard let sink, !busy else { return }
        let repairing = service.joinRepairPending
        let result = service.disable(sink: sink)
        finishFlow(result)
        if repairing, result == .done { set(\.notice, "上次加入同步没有完成，已恢复本机数据。") }
    }

    /// 「需要修复」的重试入口，与提示页上的「关闭同步」相同。
    func retryJoinRepair() { disable() }

    func clearCloud() async {
        guard let sink, !busy else { return }
        busy = true
        defer { busy = false }
        editTask?.cancel()
        let result = await service.clear(sink: sink)
        if abandonedAfterAwait() { return }
        finishFlow(result)
    }

    private func finishFlow(_ result: SyncFlowResult) {
        switch result {
        case .done:
            editTask?.cancel()
            editTask = nil
            set(\.phase, .off)
            set(\.failure, nil)
            set(\.fingerprint, nil)
            set(\.hasPending, false)
            set(\.lastSuccessAt, nil)
            scheduler = SyncScheduler()
        default:
            set(\.notice, Self.flowText(result))
            if !service.isActive, phase == .active { set(\.phase, .attention(Self.flowText(result))) }
        }
    }

    func dismissNotice() { set(\.notice, nil) }

    func showNotice(_ text: String) { set(\.notice, text) }

    static func flowText(_ result: SyncFlowResult) -> String { SyncStatusText.flow(result) }
}

enum SyncHubError: Error, Equatable {
    case message(String)
}

/// 投影落盘屏障用的本机存储（第 12.2 节）。屏障可能在协作线程上跑（清除、启动恢复），
/// 所以碰模型的部分一律回主线程；`save` 直接调用两个 store（它们自带锁）。
final class SyncModelSink: SyncJoinSink, @unchecked Sendable {
    private weak var model: StageModel?
    private let curationStore: CurationStore
    private let tasteStore: TasteStore
    private let settings: SettingsStore

    @MainActor
    init(model: StageModel) {
        self.model = model
        curationStore = model.curationStore
        tasteStore = model.tasteStore
        settings = model.settings
    }

    private func onMain<T>(_ body: @MainActor () -> T) -> T {
        if Thread.isMainThread { return MainActor.assumeIsolated(body) }
        return DispatchQueue.main.sync { MainActor.assumeIsolated(body) }
    }

    /// `DeferredSaver` 没有取消接口：先同步写出待写的旧值，紧接着屏障写出更新的投影，结果相同。
    func cancelPendingSaves() {
        onMain {
            model?.curationSaver.flush()
            model?.tasteSaver.flush()
        }
    }

    /// 加入提交拍快照之前（H1）：保存队列里待写的值先写出，再把内存里的当前收藏与口味直接写盘并确认。
    func persistPending() -> Bool {
        onMain {
            guard let model else { return false }
            model.curationSaver.flush()
            model.tasteSaver.flush()
            return curationStore.save(model.curation) && tasteStore.save(model.taste)
        }
    }

    /// 快照换回之后（还在提交锁或待修复闸门内）：按文件重读收藏、口味与两个设置。
    func reloadAfterRollback() {
        onMain { model?.reloadPreferencesAfterJoinRollback() }
    }

    func currentTaste() -> Taste { onMain { model?.taste ?? Taste() } }
    func saveCuration(_ curation: Curation) -> Bool { curationStore.save(curation) }
    func saveTaste(_ taste: Taste) -> Bool { tasteStore.save(taste) }

    func applySettings(_ values: [String: Bool]) {
        onMain { model?.applySyncedSettings(values) }
        settings.synchronize()
    }
}

@MainActor
extension StageModel {
    /// 开启或加入时的本机数据（第 14.1 节）：显式设过的两个同步设置才盖章。
    func syncLocalData() -> SyncLocalData {
        var explicit: [String: Bool] = [:]
        if settings.hasExplicitValue(forKey: SyncHub.publicOnlyKey) { explicit[SyncHub.publicOnlyKey] = settings.publicOnly }
        if settings.hasExplicitValue(forKey: SyncHub.infoAlwaysOnKey) { explicit[SyncHub.infoAlwaysOnKey] = settings.infoAlwaysOn }
        return SyncLocalData(curation: curation, taste: taste, explicitSettings: explicit)
    }

    /// 同步开启时：本地操作先写同步文档，再由投影得到本机收藏；同步关闭时原样用本机函数的结果。
    /// 返回 nil 表示操作被拒绝（同步数据已满），文档与本机视图都不变。
    /// 加入提交中：本机写操作一律拒绝（不排队），并说明原因。返回 true 表示被拒。
    func rejectWhileJoining() -> Bool {
        guard !sync.service.localWriteAllowed else { return false }
        showToast(SyncHub.committingMessage)
        return true
    }

    func syncedCuration(_ op: SyncLocalOp, local: @autoclosure () -> Curation) -> Curation? {
        if rejectWhileJoining() { return nil }
        switch sync.perform(op) {
        case .applied(let replica): return SyncProjection(replica.doc).curation
        case .full:
            showToast(SyncHub.fullMessage)
            return nil
        default: return local()
        }
    }

    private var localOnlyVetoCount: Int {
        taste.vetoedKeys.filter { SyncKeys.syncVetoKey(localKey: $0) == nil }.count
    }

    /// 「不想看」：额度满时拒绝（同步 450 个，另有 50 个本机专用）。
    func syncedVeto(_ key: String) -> Bool {
        if rejectWhileJoining() { return false }
        guard !taste.vetoedKeys.contains(key) else { return true }
        switch sync.perform(.veto(key), localOnlyVetoCount: localOnlyVetoCount) {
        case .vetoQuotaFull, .localOnlyQuotaFull:
            showToast("不想看最多 450 个（另有 50 个本机专用）")
            return false
        case .full:
            showToast(SyncHub.fullMessage)
            return false
        default: return true
        }
    }

    /// 恢复、加强、移除一个当前「不想看」的键：写 `veto.k = -`。
    func syncedUnveto(_ key: String) -> Bool {
        if rejectWhileJoining() { return false }
        guard taste.vetoedKeys.contains(key) else { return true }
        if case .full = sync.perform(.unveto(key)) {
            showToast(SyncHub.fullMessage)
            return false
        }
        return true
    }

    /// 重置画像、清除全部偏好：在所有同步设备上取消「不想看」。
    func syncedClearVetoes() -> Bool {
        if rejectWhileJoining() { return false }
        if case .full = sync.perform(.clearVetoes) {
            showToast(SyncHub.fullMessage)
            return false
        }
        return true
    }

    /// 用户切换同步设置：写 `settings.k`。
    func syncSetting(_ key: String, _ value: Bool) {
        _ = sync.perform(.setting(key, value))
    }

    /// 合并或启动之后由同步文档重建本机视图（第 6.5 节）。同值不提交，避免无谓的列表刷新。
    func applySyncProjection(_ doc: SyncDocument) {
        let projection = SyncProjection(doc)
        let nextCuration = projection.curation
        if nextCuration != curation { commit(nextCuration, nil) }
        let nextTaste = projection.applying(to: taste)
        if nextTaste != taste { commitTaste(nextTaste) }
        applySyncedSettings(projection.settings)
    }

    /// 文档里有的设置键按载荷写入；没有的键本机值不变。
    func applySyncedSettings(_ values: [String: Bool]) {
        if let v = values[SyncHub.publicOnlyKey] { applyPublicOnly(v) }
        if let v = values[SyncHub.infoAlwaysOnKey] { RoomInfoPrefs.shared.applySynced(v) }
    }

    /// 加入提交失败或取消后：屏障可能已把投影里的两个设置写进内存，按（已换回的）存储重新读一遍。
    /// 只同步内存，不写设置（M9）：换回的「未设置」不能被写成显式值，否则之后会被当成用户设置上传。
    func reloadSyncedSettingsFromStore() {
        let value = settings.publicOnly
        if publicOnly != value {
            publicOnly = value
            applySearch()
        }
        RoomInfoPrefs.shared.reload()
    }

    /// 启动时换回了未完成加入的快照：内存里的收藏与口味是换回之前读的，重新从文件读。
    func reloadPreferencesAfterJoinRollback() {
        commit(curationStore.load(), nil)
        commitTaste(tasteStore.load())
        reloadSyncedSettingsFromStore()
    }

    /// 开启时本机专用「不想看」超过 50 个的部分，在说明页列出并取消（第 6.6 节）。
    func dropLocalOnlyVetoes(_ keys: [String]) {
        guard !keys.isEmpty else { return }
        var next = taste
        for key in keys { next = next.settingSyncedVeto(key, false) }
        commitTaste(next)
    }
}
