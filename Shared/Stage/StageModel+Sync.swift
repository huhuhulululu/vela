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

    func attach(_ model: StageModel) {
        self.model = model
        sink = SyncModelSink(model: model)
        service.onDocumentChanged = { [weak self] doc in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.documentChanged(doc) } }
        }
        RoomInfoPrefs.shared.onUserToggle = { [weak model] value in model?.syncSetting(SyncHub.infoAlwaysOnKey, value) }
        panelWatch = [
            model.$showCuration.removeDuplicates().filter { $0 }.sink { [weak self] _ in self?.trigger(.panel) },
            model.$showTaste.removeDuplicates().filter { $0 }.sink { [weak self] _ in self?.trigger(.panel) },
        ]
        observeLifecycle()
        Task { await start() }
    }

    private func observeLifecycle() {
        let center = NotificationCenter.default
        #if os(macOS)
        observers.append(center.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.trigger(.activate) }
        })
        observers.append(center.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.pushBeforeExit() }
        })
        #elseif os(iOS)
        observers.append(center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.trigger(.foreground) }
        })
        observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.pushInBackground() }
        })
        #endif
    }

    private func start() async {
        guard let model, let sink else { return }
        let local = model.syncLocalData()
        let result = await service.start(local: { local }, sink: sink)
        apply(result)
    }

    private func apply(_ result: SyncStartResult) {
        switch result {
        case .notEnabled: set(\.phase, .off)
        case .unsupported: set(\.phase, .unsupported)
        case .unavailable: set(\.phase, .unavailable)
        case .active:
            activated()
            trigger(.launch)
        case .attention(let action): set(\.phase, .attention(SyncStatusText.startup(action) ?? "同步需要处理，请关闭后重新开启。"))
        case .reconfirmJoin(let ticket):
            set(\.phase, .off)
            joinStage = .preview(ticket)
        case .failed(let flow): set(\.phase, .attention(Self.flowText(flow)))
        case .stopped(let text): set(\.phase, .attention(text))
        }
    }

    /// 开启或恢复之后：投影覆盖本机收藏与「不想看」（第 11.1 节），刷新状态。
    private func activated() {
        set(\.phase, .active)
        // 创建、加入、中断恢复统一在这里落实本机专用「不想看」的 50 个额度（第 6.6 节）。
        model?.dropLocalOnlyVetoes(service.takeLocalOnlyDrops())
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
        switch await service.prepareJoin(text.trimmingCharacters(in: .whitespacesAndNewlines), local: model.syncLocalData()) {
        case .invalidCode: joinStage = .failed("同步码无效")
        case .notFound(let code): joinStage = .notFound(code)
        case .gone: joinStage = .failed("这个同步码对应的空间已被清除")
        case .preview(let ticket): joinStage = .preview(ticket)
        case .failed(let text): joinStage = .failed(text)
        }
    }

    func confirmJoin() async {
        guard let model, case .preview(let ticket) = joinStage, !busy else { return }
        busy = true
        defer { busy = false }
        let result = await service.confirmJoin(ticket, local: model.syncLocalData())
        joinStage = .idle
        if service.isActive {
            activated()
            if let result {
                scheduler.record(result, now: nowMillis)
                set(\.failure, SyncStatusText.cycle(result))
            }
        } else {
            set(\.notice, "加入失败：本机保存失败，未做任何更改。")
        }
    }

    /// 404 时「仍用此码创建」。
    func createFromJoin() async {
        guard let model, case .notFound(let code) = joinStage, !busy else { return }
        busy = true
        defer { busy = false }
        let result = await service.createWithCode(code, local: model.syncLocalData())
        joinStage = .idle
        if service.isActive {
            activated()
            if let result { scheduler.record(result, now: nowMillis) }
        }
    }

    func cancelJoin() {
        if let sink {
            let result = service.cancelJoin(sink: sink)
            if result != .done { set(\.notice, Self.flowText(result)) }
        }
        joinStage = .idle
    }

    // MARK: 关闭与清除（第 12.6 节）

    func disable() {
        guard let sink, !busy else { return }
        let result = service.disable(sink: sink)
        finishFlow(result)
    }

    func clearCloud() async {
        guard let sink, !busy else { return }
        busy = true
        defer { busy = false }
        editTask?.cancel()
        finishFlow(await service.clear(sink: sink))
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

    static func flowText(_ result: SyncFlowResult) -> String {
        switch result {
        case .done: return ""
        case .localSaveFailed: return "本机保存失败，未做任何更改。"
        case .storageFailed: return "删除同步状态失败，下次启动时继续。"
        case .remotePending: return "暂时连不上服务器，清除尚未完成，下次启动时继续。"
        case .remoteRejected: return "云端拒绝了清除请求。"
        }
    }
}

enum SyncHubError: Error, Equatable {
    case message(String)
}

/// 投影落盘屏障用的本机存储（第 12.2 节）。屏障可能在协作线程上跑（清除、启动恢复），
/// 所以碰模型的部分一律回主线程；`save` 直接调用两个 store（它们自带锁）。
final class SyncModelSink: SyncLocalSink, @unchecked Sendable {
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
    func syncedCuration(_ op: SyncLocalOp, local: @autoclosure () -> Curation) -> Curation? {
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
        guard taste.vetoedKeys.contains(key) else { return true }
        if case .full = sync.perform(.unveto(key)) {
            showToast(SyncHub.fullMessage)
            return false
        }
        return true
    }

    /// 重置画像、清除全部偏好：在所有同步设备上取消「不想看」。
    func syncedClearVetoes() -> Bool {
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

    /// 开启时本机专用「不想看」超过 50 个的部分，在说明页列出并取消（第 6.6 节）。
    func dropLocalOnlyVetoes(_ keys: [String]) {
        guard !keys.isEmpty else { return }
        var next = taste
        for key in keys { next = next.settingSyncedVeto(key, false) }
        commitTaste(next)
    }
}
