import XCTest
@testable import VelaPhone

/// PR3 三方审计的修复回归（空主路、收藏夹目标路、画中画与双窗开关、学习暂停与清除、同名换来源、热上限同步、
/// 副路热门关联、全站搜索直达）。年龄未确认：起播与目录不发请求。
@MainActor
final class AuditFixTests: XCTestCase {
    private var suites: [String] = []

    override func setUp() {
        super.setUp()
        TestPrefs.reset()
    }

    override func tearDown() {
        TestPrefs.reset()
        for name in suites { UserDefaults().removePersistentDomain(forName: name) }
        suites = []
        PlaylistRelay.shared.allowStart = true
        super.tearDown()
    }

    private func room(_ name: String, tags: [String] = []) -> Room {
        Room(username: name, viewers: 10, gender: .female, tags: tags, show: .publicShow, secondsOnline: nil)
    }

    private func makeStage(_ rooms: [Room], dual: Bool = true, source: FeedSource? = nil) -> StageModel {
        let name = "vela-phone-audit-\(UUID().uuidString)"
        suites.append(name)
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!))
        stage.updateFeed(installing: source, intent: .reload) {
            $0.replace(with: CatalogPage(rooms: rooms, total: rooms.count, nextOffset: rooms.count))
        }
        if dual { stage.setDual(true) }
        return stage
    }

    private func plain(_ names: [String]) -> [Room] { names.map { room($0) } }

    // MARK: 空主路不回落到首间（sol high 1）

    func testEmptyPrimaryDoesNotFallBackToFirstRoom() {
        let stage = makeStage(plain(["alice", "bob"]))
        stage.setFocus(.secondary)
        stage.blockCurrent(stage.target(.primary))
        XCTAssertNil(stage.name(of: stage.primary), "主路空了")
        XCTAssertNil(stage.room(of: stage.primary), "空主路不能经 feed.current 回落到 bob")
        XCTAssertEqual(stage.name(of: stage.secondary!), "bob")
        XCTAssertNil(stage.target(.primary))
    }

    func testSingleWindowStillFollowsFeedCurrent() {
        let stage = makeStage(plain(["alice", "bob"]), dual: false)
        XCTAssertEqual(stage.room(of: stage.primary)?.username, "alice")
    }

    // MARK: 收藏夹目标路（blind H1、sol high 2）

    func testFocusTargetFollowsFocusLane() {
        let stage = makeStage(plain(["alice", "bob", "carol"]))
        XCTAssertEqual(stage.focusTarget()?.laneID, .primary)
        stage.setFocus(.secondary)
        let target = stage.focusTarget()
        XCTAssertEqual(target?.laneID, .secondary)
        XCTAssertEqual(target?.username, "bob")
        stage.toggleFavorite(stage.focusTarget())
        XCTAssertTrue(stage.curation.isFavorite("bob"))
        XCTAssertFalse(stage.curation.isFavorite("alice"))
        stage.blockCurrent(stage.focusTarget())
        XCTAssertTrue(stage.curation.isBlocked("bob"))
        XCTAssertFalse(stage.curation.isBlocked("alice"))
    }

    // MARK: 画中画与双窗开关（blind M1、sol high 3、grok high）

    private final class FakePiP: PictureInPictureControlling {
        var canAutoEnter = true
        var autoEnter = true
        var stopCalls = 0
        var abortCalls = 0
        func stop() { stopCalls += 1 }
        func abort() { abortCalls += 1 }
    }

    func testDualToggleIgnoredWhilePictureInPictureIsUp() {
        let stage = makeStage(plain(["alice", "bob", "carol"]))
        stage.setFocus(.secondary)
        stage.platform.pictureInPictureEvent(.willStart, stage: stage)
        stage.platform.pictureInPictureEvent(.didStart, stage: stage)
        stage.setDual(false)
        XCTAssertTrue(stage.dualWindow, "小窗开着时不能关双窗")
        XCTAssertEqual(stage.focusLane, .secondary)
        XCTAssertTrue(stage.secondary!.visible || stage.secondary === stage.focusPlaybackLane)
        stage.platform.pictureInPictureEvent(.didStop, stage: stage)
        stage.setDual(false)
        XCTAssertFalse(stage.dualWindow, "小窗收掉后可以关")
        XCTAssertEqual(stage.name(of: stage.primary), "bob")
    }

    func testOpeningDualIgnoredWhilePictureInPictureIsUp() {
        let stage = makeStage(plain(["alice", "bob"]), dual: false)
        stage.platform.pictureInPictureEvent(.willStart, stage: stage)
        stage.setDual(true)
        XCTAssertFalse(stage.dualWindow)
        XCTAssertNil(stage.secondary)
    }

    func testNewSecondaryHonoursPipSoloWhenOpenedViaRestore() {
        let stage = makeStage(plain(["alice", "bob"]), dual: false)
        stage.setPipSolo(true)
        stage.restoreLanesIfRemembered()
        stage.settings.phoneDual = true
        stage.restoreLanesIfRemembered()
        if let secondary = stage.secondary { XCTAssertFalse(secondary.visible, "新副路不能在画中画期间起播") }
    }

    func testFallbackReleasesPipLockAndSolo() async {
        let stage = makeStage(plain(["alice", "bob", "carol"]))
        let pip = FakePiP()
        stage.platform.pip = pip
        stage.platform.playerAdvancing = { _ in true }
        stage.platform.pipFallbackDelay = 0.05
        stage.primary.playingName = "alice"
        stage.platform.pictureInPictureEvent(.willStart, stage: stage)
        XCTAssertTrue(stage.pipSolo)
        stage.platform.scenePhaseChanged(.inactive, stage: stage)
        stage.platform.scenePhaseChanged(.background, stage: stage)
        try? await Task.sleep(nanoseconds: 400_000_000)
        XCTAssertEqual(stage.platform.pictureInPictureState, .none, "回退后不再等委托")
        XCTAssertFalse(stage.pipSolo)
        XCTAssertFalse(stage.platform.focusLocked)
        stage.platform.scenePhaseChanged(.inactive, stage: stage)
        stage.platform.scenePhaseChanged(.active, stage: stage)
        XCTAssertTrue(stage.secondary!.visible)
        XCTAssertNotNil(stage.secondary!.playTask, "回前台恢复被扣住的那一路")
        stage.setFocus(.secondary)
        XCTAssertEqual(stage.focusLane, .secondary, "焦点可以换了")
    }

    // MARK: 加载线按本路判断（blind M2）

    func testLoadingLineFollowsOwnLaneGate() {
        XCTAssertTrue(PhoneRoomPage.showsLoadingLine(active: true, videoReady: false, hasNote: false, laneAllowed: true))
        XCTAssertFalse(PhoneRoomPage.showsLoadingLine(active: true, videoReady: false, hasNote: false, laneAllowed: false), "本路闸门关着不空转")
        XCTAssertFalse(PhoneRoomPage.showsLoadingLine(active: true, videoReady: true, hasNote: false, laneAllowed: true))
        XCTAssertFalse(PhoneRoomPage.showsLoadingLine(active: false, videoReady: false, hasNote: false, laneAllowed: true))
        XCTAssertFalse(PhoneRoomPage.showsLoadingLine(active: true, videoReady: false, hasNote: true, laneAllowed: true))
        let stage = makeStage(plain(["alice", "bob"]))
        stage.secondary!.manuallyPaused = true
        stage.applyPower()
        XCTAssertTrue(stage.playbackGateOpen, "全局闸门开着")
        XCTAssertFalse(stage.laneAllowed(stage.secondary!), "副路自己的闸门关着")
    }

    // MARK: 全站搜索直达（blind L1）

    func testDirectSearchHitOnSecondaryRoomRevealsInsteadOfSqueezing() {
        let stage = makeStage(plain(["alice", "bob", "carol"]))
        stage.applyDirectSearchHit(room("bob"))
        XCTAssertEqual(stage.focusLane, .secondary, "目标正在副路：只切焦点")
        XCTAssertEqual(stage.name(of: stage.primary), "alice")
        XCTAssertEqual(stage.name(of: stage.secondary!), "bob")
    }

    func testDirectSearchHitOnNewRoomGoesToPrimaryAndStaysDistinct() {
        let stage = makeStage(plain(["alice", "bob", "carol"]))
        stage.applyDirectSearchHit(room("zed"))
        XCTAssertEqual(stage.name(of: stage.primary), "zed")
        XCTAssertNotEqual(stage.name(of: stage.primary), stage.name(of: stage.secondary!))
    }

    // MARK: 学习暂停与清除（sol medium 4、5）

    func testPausingLearningExcludesLedgerOnBothLanes() {
        let stage = makeStage(plain(["alice", "bob"]))
        let t0 = Date()
        for lane in stage.lanes { lane.dwell.setActive(true, now: t0) }
        stage.setLearningPaused(true)
        let later = t0.addingTimeInterval(30)
        for lane in stage.lanes {
            XCTAssertLessThan(lane.dwell.ledgerDwell(now: later), 1, "暂停学习后两路的账本秒数都冻结")
        }
        stage.setLearningPaused(false)
        let secondary = stage.secondary!
        XCTAssertGreaterThan(secondary.dwell.ledgerDwell(now: later.addingTimeInterval(10)), 5 - 5, "恢复后副路的排除段已关闭")
    }

    func testClearAllPreferencesDiscardsEveryLanesDwell() {
        let stage = makeStage(plain(["alice", "bob"]))
        for lane in stage.lanes { lane.dwell.setActive(true, now: Date().addingTimeInterval(-60)) }
        stage.clearAllPreferences()
        for lane in stage.lanes { XCTAssertTrue(lane.dwell.isDiscarded) }
        stage.settleDwell(.background)
        XCTAssertNil(stage.watchLedger.entries["alice"])
        XCTAssertNil(stage.watchLedger.entries["bob"])
    }

    // MARK: 副路同名换来源（sol medium 6）

    func testSecondarySameNameSourceChangeSettlesAndUpdatesSource() {
        let stage = makeStage(plain(["alice", "bob", "carol"]), source: .guess)
        stage.secondary!.dwell.setActive(true, now: Date().addingTimeInterval(-40))
        let before = stage.secondary!.dwellSource
        XCTAssertEqual(before, .guess)
        stage.updateFeed(installing: .popular, intent: .reload) {
            $0.replace(with: CatalogPage(rooms: ["alice", "bob", "dave"].map { Room.direct(username: $0)! }, total: 3, nextOffset: 3))
        }
        XCTAssertEqual(stage.name(of: stage.secondary!), "bob", "房间没变")
        XCTAssertEqual(stage.secondary!.dwellSource, .popular, "来源跟着换，不再沿用旧来源计权")
        XCTAssertGreaterThan(stage.watchLedger.entries["bob"]?.totalSeconds ?? 0, 0, "旧来源下的停留已结算")
    }

    // MARK: 热上限同步（sol medium 7、grok medium 3）

    func testCapSyncNeededWhenThermalChangedAfterPlaybackStarted() {
        let stage = makeStage(plain(["alice", "bob"]))
        stage.screenScale = 3
        for lane in stage.lanes { lane.displaySize = CGSize(width: 393, height: 221); lane.currentCap = .infinity }
        XCTAssertFalse(stage.needsCapSync(stage.primary))
        stage.noteThermal(.serious, now: Date())
        XCTAssertTrue(stage.needsCapSync(stage.primary), "起播期间升温：就绪后要补换档")
    }

    /// ios-triple 修订 v2 H2：两路时上限与画面尺寸无关，尺寸只在三格时影响选档。
    func testDisplaySizeChangeInThreeLanesRequestsCapSync() {
        let stage = makeStage(plain(["alice", "bob", "carol"]))
        stage.setLaneCount(3)
        stage.screenScale = 3
        stage.secondary!.displaySize = .zero
        stage.secondary!.currentCap = LaneQuality.minimumBitrate
        XCTAssertFalse(stage.needsCapSync(stage.secondary!))
        stage.noteDisplaySize(CGSize(width: 393, height: 221), on: stage.secondary!)
        XCTAssertTrue(stage.needsCapSync(stage.secondary!), "量到真实画面高度后上限变了")
        XCTAssertEqual(stage.secondary!.displaySize.height, 221)
    }

    func testDisplaySizeSameValueIsIgnored() {
        let stage = makeStage(plain(["alice", "bob"]), dual: false)
        stage.noteDisplaySize(CGSize(width: 100, height: 50), on: stage.primary)
        stage.noteDisplaySize(CGSize(width: 100, height: 50), on: stage.primary)
        XCTAssertEqual(stage.primary.displaySize, CGSize(width: 100, height: 50))
    }

    // MARK: 副路热门关联（sol medium 8、grok medium 1）

    func testRelatedPromotionWorksForFocusSecondaryRoom() {
        let rooms = [
            room("alice", tags: ["x"]), room("bob", tags: ["x", "y"]), room("zed", tags: ["q"]),
            room("kim", tags: ["x", "y"]), room("lee", tags: ["y"])
        ]
        let stage = makeStage(rooms, source: .guess)
        stage.source = .guess
        stage.setFocus(.secondary)
        XCTAssertEqual(stage.name(of: stage.secondary!), "bob")
        stage.promoteRelated(to: "bob")
        let names = stage.feed.rooms.map(\.username)
        let at = names.firstIndex(of: "bob")!
        XCTAssertEqual(names[at + 1], "kim", "相近的房间紧随副路正在看的那一间之后")
        XCTAssertEqual(stage.name(of: stage.primary), "alice")
        XCTAssertEqual(stage.name(of: stage.secondary!), "bob")
    }

    // MARK: 复审 1：热上限补同步在首帧就绪时重试

    func testReadyAfterThermalLimitRequestsCapSync() {
        let stage = makeStage(plain(["alice", "bob"]))
        stage.screenScale = 3
        for lane in stage.lanes { lane.displaySize = CGSize(width: 393, height: 221); lane.currentCap = .infinity }
        stage.noteThermal(.serious, now: Date())
        let lane = stage.secondary!
        let before = lane.capSyncRequests
        // 起播时的补换档发生在首帧之前，播放器会拒绝；首帧就绪时必须再请求一次。
        stage.noteReady("bob", on: lane)
        XCTAssertGreaterThan(lane.capSyncRequests, before, "就绪时补做尚未应用的上限同步")
    }

    func testReadyWithUpToDateCapDoesNotRequestCapSync() {
        let stage = makeStage(plain(["alice", "bob"]))
        let lane = stage.secondary!
        lane.currentCap = .infinity
        let before = lane.capSyncRequests
        stage.noteReady("bob", on: lane)
        XCTAssertEqual(lane.capSyncRequests, before, "上限没变不用换档")
    }

    // MARK: 复审 2：空主路恢复后停留要初始化

    func testClosingDualAfterPrimaryEmptiedInitialisesPrimaryDwell() {
        let stage = makeStage(plain(["alice", "bob"]))
        stage.setFocus(.secondary)
        stage.blockCurrent(stage.target(.primary))
        XCTAssertNil(stage.primary.dwell.room, "空主路没有停留房间")
        stage.setDual(false)
        XCTAssertEqual(stage.name(of: stage.primary), "bob")
        XCTAssertEqual(stage.primary.dwell.room?.username, "bob", "恢复后的主路要开始记停留")
    }

    func testReloadRestoringEmptyPrimaryInitialisesDwell() {
        let stage = makeStage(plain(["alice", "bob"]))
        stage.setFocus(.secondary)
        stage.blockCurrent(stage.target(.primary))
        stage.unblock("alice")
        if let name = stage.name(of: stage.primary) {
            XCTAssertEqual(stage.primary.dwell.room?.username, name, "主路回来了，停留跟着初始化")
        }
        stage.updateFeed(installing: stage.loadedSource, intent: .reload) {
            $0.replace(with: CatalogPage(rooms: ["alice", "bob"].map { Room.direct(username: $0)! }, total: 2, nextOffset: 2))
        }
        XCTAssertEqual(stage.primary.dwell.room?.username, stage.name(of: stage.primary))
        XCTAssertNotNil(stage.primary.dwell.room)
    }

    // MARK: 复审 3：副路加载线不受主路暂停影响

    func testSecondaryLoadingLineSurvivesPrimaryPause() {
        let stage = makeStage(plain(["alice", "bob"]))
        stage.primary.manuallyPaused = true
        stage.applyPower()
        XCTAssertFalse(stage.playbackGateOpen, "playbackGateOpen 其实是主路的闸门")
        XCTAssertTrue(stage.laneAllowed(stage.secondary!))
        XCTAssertTrue(PhoneRoomPage.loadingLaneAllowed(model: stage, lane: stage.secondary!), "副路自己允许就该显示加载线")
        XCTAssertFalse(PhoneRoomPage.loadingLaneAllowed(model: stage, lane: stage.primary))
    }

    // MARK: 复审 4：回退清掉协调器

    func testFallbackAbortsCoordinatorNotJustStops() async {
        let stage = makeStage(plain(["alice", "bob"]))
        let pip = FakePiP()
        stage.platform.pip = pip
        stage.platform.playerAdvancing = { _ in true }
        stage.platform.pipFallbackDelay = 0.05
        stage.primary.playingName = "alice"
        stage.platform.pictureInPictureEvent(.willStart, stage: stage)
        stage.platform.scenePhaseChanged(.inactive, stage: stage)
        stage.platform.scenePhaseChanged(.background, stage: stage)
        try? await Task.sleep(nanoseconds: 400_000_000)
        XCTAssertEqual(pip.abortCalls, 1, "回退走中止入口，不只是 stop()")
    }

    func testCoordinatorAbortClearsActiveSoItCanRebind() {
        let coordinator = PictureInPictureCoordinator(autoEnter: true)
        coordinator.deliver(.willStart)
        XCTAssertTrue(coordinator.active)
        coordinator.abort()
        XCTAssertFalse(coordinator.active, "中止后不再卡在 active，attach 与 detach 的守卫才会放行")
        XCTAssertFalse(coordinator.possible)
        coordinator.deliver(.didStop)
        XCTAssertFalse(coordinator.active, "旧控制器迟到的事件无害")
    }
}

// MARK: - 加入同步又一轮修复（PR #11，H1、H5、M7）

/// 同步界面层的事务缺陷回归：真的 `StageModel` 与真实 `DeferredSaver`、偏好文件；同步服务换成本机替身（不联网、不碰钥匙串）。
/// 模型自己的同步入口保持实验开关关闭（默认值），用例里另造一个 `SyncHub` 接在模型上。
@MainActor
final class SyncJoinWiringTests: XCTestCase {
    private var suites: [String] = []
    private var hubs: [SyncHub] = []
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)
    private static let key = [UInt8](repeating: 5, count: 32)
    private static let origin = "https://sync.example"

    override func setUp() {
        super.setUp()
        TestPrefs.reset()
    }

    override func tearDown() {
        for hub in hubs {
            hub.disable()
            hub.setExperiment(false)
        }
        hubs = []
        RoomInfoPrefs.shared.onUserToggle = nil
        RoomInfoPrefs.shared.allowsToggle = nil
        TestPrefs.reset()
        for name in suites { UserDefaults().removePersistentDomain(forName: name) }
        suites = []
        super.tearDown()
    }

    final class StateStore: SyncStateStore {
        var stored: SyncState?
        func load() -> SyncStateRead { stored.map { .present($0) } ?? .none }
        func save(_ state: SyncState) -> Bool { stored = state; return true }
        func delete() -> Bool { stored = nil; return true }
    }

    final class KeyStore: SyncKeyStore {
        var entry: SyncKeychainEntry?
        /// 钥匙串暂时不可用（首次解锁前）。
        var unavailable = false
        private(set) var reads = 0
        func readEntry() -> SyncKeychainLookup {
            reads += 1
            if unavailable { return .unavailable }
            return entry.map { .present($0) } ?? .none
        }
        func write(_ entry: SyncKeychainEntry) -> SyncKeychainWrite { self.entry = entry; return .ok }
        func read() -> SyncKeychainRead {
            reads += 1
            if unavailable { return .unavailable }
            return entry.map { .present(blobID: $0.blobID) } ?? .none
        }
        func delete() -> Bool { entry = nil; return true }
    }

    /// 慢网络：每个请求等 `delay` 纳秒后返回脚本里的响应（没有脚本就是暂时失败）。
    final class SlowTransport: SyncTransport, @unchecked Sendable {
        private let lock = NSLock()
        private var gets: [SyncServerResponse] = []
        private var puts: [SyncServerResponse] = []
        var delay: UInt64 = 300_000_000
        func script(gets: [SyncServerResponse], puts: [SyncServerResponse] = []) {
            lock.withLock {
                self.gets = gets
                self.puts = puts
            }
        }
        func get() async -> SyncServerResponse {
            let r = lock.withLock { gets.isEmpty ? SyncServerResponse.transient : gets.removeFirst() }
            try? await Task.sleep(nanoseconds: delay)
            return r
        }
        func put(_ request: SyncPutRequest) async -> SyncServerResponse {
            let r = lock.withLock { puts.isEmpty ? SyncServerResponse.transient : puts.removeFirst() }
            try? await Task.sleep(nanoseconds: delay)
            return r
        }
        func delete(base: UInt64, epoch: String?) async -> SyncServerResponse { .transient }
    }

    private func makeStage() -> StageModel {
        let name = "vela-phone-sync-\(UUID().uuidString)"
        suites.append(name)
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!))
        XCTAssertFalse(stage.sync.experimentEnabled, "实验开关默认关闭")
        return stage
    }

    private func hub(_ stage: StageModel, store: StateStore, keys: KeyStore, transport: SlowTransport) -> (SyncHub, SyncService) {
        let service = SyncService(env: SyncService.Environment(stateStore: store, keyStore: keys, makeTransport: { _, _ in transport }))
        let hub = SyncHub(service: service)
        hubs.append(hub)
        stage.settings.experimentSync = true
        hub.attach(stage)
        return (hub, service)
    }

    private func waitUntil(_ seconds: Double = 3, _ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(seconds)
        while !condition(), Date() < deadline { try? await Task.sleep(nanoseconds: 20_000_000) }
    }

    private func settled(_ name: String, seconds: TimeInterval) -> DwellTracker.Settled {
        var tracker = DwellTracker()
        _ = tracker.switchTo(Room.direct(username: name), now: t0)
        tracker.setActive(true, now: t0)
        return tracker.finish(now: t0.addingTimeInterval(seconds))!
    }

    // MARK: H5 实验开关关闭后不得留下活动同步

    /// 启动恢复还在跑时关闭实验开关：要么拒绝关闭，要么之后绝不激活。
    func testH5_turningExperimentOffWhileStartingNeverLeavesActiveSync() async throws {
        let stage = makeStage()
        let store = StateStore(), keys = KeyStore(), transport = SlowTransport()
        let material = try SyncCrypto.derive(Self.key)
        var state = SyncState(phase: .active, origin: Self.origin, blobID: material.blobID, clock: SyncClock(dev: 7))
        state.epoch = "0123456789abcdef0123456789abcdef"
        store.stored = state
        keys.entry = SyncKeychainEntry(key: Self.key, dev: 7, blobID: material.blobID)
        let (hub, service) = hub(stage, store: store, keys: keys, transport: transport)
        hub.setExperiment(false)
        try? await Task.sleep(nanoseconds: 500_000_000)
        XCTAssertFalse(!hub.experimentEnabled && (hub.phase == .active || service.isActive),
                       "实验已关却有活动同步：phase=\(hub.phase) active=\(service.isActive)")
        if hub.experimentEnabled { XCTAssertNotNil(hub.notice, "拒绝关闭要说明原因") }
    }

    /// 「仍用此码创建」在等首个周期时关闭实验开关：同上。
    func testH5_turningExperimentOffDuringCreateNeverLeavesActiveSync() async throws {
        let stage = makeStage()
        let store = StateStore(), keys = KeyStore(), transport = SlowTransport()
        transport.delay = 10_000_000
        let (hub, service) = hub(stage, store: store, keys: keys, transport: transport)
        await waitUntil { hub.phase == .off }
        XCTAssertEqual(hub.phase, .off)
        transport.script(gets: [.notFound(epoch: "0123456789abcdef0123456789abcdef", now: 1_800_000_000_000)])
        let code = try XCTUnwrap(SyncCrypto.formatCode(key: Self.key, origin: Self.origin))
        await hub.prepareJoin(code)
        guard case .notFound = hub.joinStage else { return XCTFail("前提：404 可用此码创建，实际 \(hub.joinStage)") }
        transport.delay = 600_000_000
        let create = Task { await hub.createFromJoin() }
        await waitUntil { service.isActive }
        XCTAssertTrue(hub.busy, "前提：创建还在等首个周期")
        hub.setExperiment(false)
        await create.value
        XCTAssertFalse(!hub.experimentEnabled && (hub.phase == .active || service.isActive),
                       "实验已关却有活动同步：phase=\(hub.phase) active=\(service.isActive)")
    }

    /// 钥匙串暂时不可用时，离散触发会重排启动；排上之后立刻关实验开关：要么拒绝关闭，要么之后绝不再启动同步
    /// （不读钥匙串、不激活）。
    func testH5_turningExperimentOffRightAfterUnavailableRetryNeverStartsSync() async throws {
        let stage = makeStage()
        let store = StateStore(), keys = KeyStore(), transport = SlowTransport()
        let material = try SyncCrypto.derive(Self.key)
        var state = SyncState(phase: .active, origin: Self.origin, blobID: material.blobID, clock: SyncClock(dev: 7))
        state.epoch = "0123456789abcdef0123456789abcdef"
        store.stored = state
        keys.entry = SyncKeychainEntry(key: Self.key, dev: 7, blobID: material.blobID)
        keys.unavailable = true
        let (hub, service) = hub(stage, store: store, keys: keys, transport: transport)
        await waitUntil { hub.phase == .unavailable }
        XCTAssertEqual(hub.phase, .unavailable, "前提：钥匙串暂时不可用")
        keys.unavailable = false
        let readsBefore = keys.reads
        hub.trigger(.foreground)
        hub.setExperiment(false)
        try? await Task.sleep(nanoseconds: 500_000_000)
        if hub.experimentEnabled {
            XCTAssertNotNil(hub.notice, "拒绝关闭要说明原因")
        } else {
            XCTAssertEqual(keys.reads, readsBefore, "开关已关：排上的启动不再调用服务（不读钥匙串）")
            XCTAssertFalse(service.isActive)
            XCTAssertNotEqual(hub.phase, .active)
        }
    }

    private static let epoch = "0123456789abcdef0123456789abcdef"
    private static var nowMillis: UInt64 { UInt64(Date().timeIntervalSince1970 * 1000) }

    /// 已有空间的 GET 响应：远端收藏 zed。
    private static func remoteBlob(ver: UInt64) throws -> SyncServerResponse {
        let keys = try SyncCrypto.derive(key)
        var doc = SyncDocument()
        doc.rooms["cb:zed"] = SyncRoom(f: SyncRegister(hlc: HLC(l: nowMillis - 60_000, c: 0, dev: 9), payload: "+"))
        let data = try XCTUnwrap(SyncCryptoCodec(keys: keys).seal(doc.encoded(), ver: ver))
        return .blob(ver: ver, pub: keys.publicKeyText, data: data, epoch: epoch, now: nowMillis)
    }

    private func isIdle(_ stage: SyncHub.JoinStage) -> Bool { if case .idle = stage { return true } else { return false } }

    // MARK: H8 提交锁保持到界面激活之后

    /// 加入成功：主线程激活界面、按文档重建投影时提交锁仍在（这段空隙里本机写操作被拒），激活之后才释放。
    func testH8_commitLockIsHeldUntilJoinActivationFinishes() async throws {
        let stage = makeStage()
        let store = StateStore(), keys = KeyStore(), transport = SlowTransport()
        transport.delay = 10_000_000
        let (hub, service) = hub(stage, store: store, keys: keys, transport: transport)
        await waitUntil { hub.phase == .off }
        transport.script(gets: [try Self.remoteBlob(ver: 3)], puts: [.stored(ver: 4, epoch: Self.epoch, now: Self.nowMillis)])
        let code = try XCTUnwrap(SyncCrypto.formatCode(key: Self.key, origin: Self.origin))
        await hub.prepareJoin(code)
        guard case .preview = hub.joinStage else { return XCTFail("前提：确认页，实际 \(hub.joinStage)") }
        var lockedAtProjection: [Bool] = []
        let watch = stage.$curation.dropFirst().sink { _ in lockedAtProjection.append(service.isCommittingJoin) }
        await hub.confirmJoin()
        watch.cancel()
        XCTAssertEqual(hub.phase, .active)
        XCTAssertTrue(stage.curation.isFavorite("zed"), "前提：激活时按文档重建了投影")
        XCTAssertFalse(lockedAtProjection.isEmpty)
        XCTAssertTrue(lockedAtProjection.allSatisfy { $0 }, "提交锁要保持到主线程完成激活")
        XCTAssertFalse(service.isCommittingJoin, "激活之后释放")
        XCTAssertTrue(service.localWriteAllowed)
    }

    // MARK: M9 回滚重读不写设置

    /// 快照换回「未设置」之后的重读：只同步内存，不把默认值写成显式设置（否则之后会被当成用户设置上传）。
    func testM9_rollbackReloadKeepsUnsetSettingUnset() {
        let stage = makeStage()
        XCTAssertFalse(stage.settings.hasExplicitValue(forKey: SyncHub.publicOnlyKey), "前提：没设过")
        let original = stage.publicOnly
        stage.applyPublicOnly(!original)  // 投影写入
        stage.settings.setExplicit(SyncHub.publicOnlyKey, nil)  // 快照换回「未设置」
        SyncModelSink(model: stage).reloadAfterRollback()
        XCTAssertFalse(stage.settings.hasExplicitValue(forKey: SyncHub.publicOnlyKey), "仍是未设置")
        XCTAssertEqual(stage.publicOnly, stage.settings.publicOnly, "内存跟随存储")
    }

    // MARK: M10 检查阶段取消后迟到的结果丢弃

    /// 粘贴同步码后在等 GET 时取消（关面板、进后台）：迟到的响应不得重新装上确认页票据。
    func testM10_cancelWhileCheckingDropsLateResult() async throws {
        let stage = makeStage()
        let store = StateStore(), keys = KeyStore(), transport = SlowTransport()
        transport.delay = 10_000_000
        let (hub, _) = hub(stage, store: store, keys: keys, transport: transport)
        await waitUntil { hub.phase == .off }
        transport.delay = 400_000_000
        transport.script(gets: [try Self.remoteBlob(ver: 3)])
        let code = try XCTUnwrap(SyncCrypto.formatCode(key: Self.key, origin: Self.origin))
        let prepare = Task { await hub.prepareJoin(code) }
        await waitUntil { if case .checking = hub.joinStage { return true } else { return false } }
        guard case .checking = hub.joinStage else { return XCTFail("前提：在检查") }
        hub.cancelJoin()
        await prepare.value
        XCTAssertTrue(isIdle(hub.joinStage), "取消后迟到的结果丢弃，实际 \(hub.joinStage)")
        XCTAssertFalse(hub.busy)
    }

    // MARK: M7 停留记账不绕过提交锁

    /// 加入提交中：停留结算不改口味、不排保存；锁一放开照常记账。
    func testM7_dwellRecordIsRejectedWhileCommitting() {
        let stage = makeStage()
        let service = stage.sync.service
        let before = stage.taste
        service.commitLock.withLock { service.committing = true }
        stage.record(settled("alice", seconds: 120), from: stage.primary, reason: .swipe)
        XCTAssertEqual(stage.taste, before, "提交中不改口味")
        stage.tasteSaver.flush()
        XCTAssertEqual(stage.tasteStore.load(), before, "提交中不写偏好文件")
        service.commitLock.withLock { service.committing = false }
        stage.record(settled("alice", seconds: 120), from: stage.primary, reason: .swipe)
        XCTAssertNotEqual(stage.taste, before, "锁放开后照常记账")
    }

    /// 未定局的快照：完成恢复前不接受口味写入。
    final class PendingJournal: SyncJoinJournal {
        var status: SyncJoinJournalStatus { .pending }
        func snapshot() -> Bool { false }
        func markCommitted() -> Bool { false }
        func restore() -> Bool { false }
        func discard() -> Bool { false }
    }

    /// 上次加入待修复（回滚没完成）：停留结算同样不改口味。
    func testM7_dwellRecordIsRejectedWhileJoinRepairPending() {
        let stage = makeStage()
        let before = stage.taste
        stage.sync.service.configureJournal(PendingJournal())
        defer { stage.sync.service.configureJournal(NoJournal()) }
        XCTAssertTrue(stage.sync.service.joinRepairPending)
        stage.record(settled("alice", seconds: 120), from: stage.primary, reason: .swipe)
        XCTAssertEqual(stage.taste, before)
        XCTAssertTrue(stage.rejectWhileJoining(), "收藏、屏蔽等写入同样被拒")
    }

    // MARK: H1 模型 sink 落盘与重读

    /// 提交拍快照之前：保存队列里待写的收藏先写盘；换回之后按文件重读，保存队列里不留会覆盖换回结果的旧值。
    func testH1_modelSinkPersistsPendingEditsAndReloadsAfterRollback() {
        let stage = makeStage()
        stage.commit(Curation(favorites: ["alice"]), nil)
        XCTAssertNotEqual(stage.curationStore.load(), stage.curation, "前提：alice 还在保存队列里")
        let sink = SyncModelSink(model: stage)
        XCTAssertTrue(sink.persistPending())
        XCTAssertEqual(stage.curationStore.load(), stage.curation, "落盘并确认")
        XCTAssertTrue(stage.curationStore.save(Curation(favorites: ["bob"])), "模拟快照换回")
        sink.reloadAfterRollback()
        XCTAssertEqual(stage.curation.favorites, ["bob"], "内存按换回的文件重读")
        stage.curationSaver.flush()
        XCTAssertEqual(stage.curationStore.load().favorites, ["bob"], "保存队列与磁盘一致")
    }
}
