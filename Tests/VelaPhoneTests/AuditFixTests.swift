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
        stage.restoreDualIfRemembered()
        stage.settings.phoneDual = true
        stage.restoreDualIfRemembered()
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

    func testDisplaySizeChangeWhileLimitedRequestsCapSync() {
        let stage = makeStage(plain(["alice", "bob"]))
        stage.screenScale = 3
        stage.secondary!.displaySize = .zero
        stage.noteThermal(.serious, now: Date())
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
