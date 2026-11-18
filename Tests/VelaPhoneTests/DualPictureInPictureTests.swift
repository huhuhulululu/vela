import XCTest
@testable import VelaPhone

/// 双窗画中画（修订 A2 的 T18b、修订 A3）：只有焦点路进画中画，进入时释放另一路，回 App 恢复双窗；
/// 自动进入 3 秒内没成功就按无画中画处理（全停释放，不留后台播放或黑小窗）。
/// 画中画控制器用桩；系统控制器的行为只能在 iPad 模拟器实跑里验。
@MainActor
final class DualPictureInPictureTests: XCTestCase {
    private final class FakePiP: PictureInPictureControlling {
        var canAutoEnter = true
        var autoEnter = true
        var stopCalls = 0
        func stop() { stopCalls += 1 }
    }

    private final class FakeDriver: AudioSessionDriving {
        func setCategory(_ category: AudioSessionCategory, mode: AudioSessionMode?, options: AudioSessionOptions) throws {}
        func setActive(_ active: Bool, notifyOthers: Bool) throws {}
    }

    private var suites: [String] = []
    private var stages: [StageModel] = []

    override func setUp() {
        super.setUp()
        TestPrefs.reset()
    }

    override func tearDown() {
        TestPrefs.reset()
        for stage in stages { stage.platform.scenePhaseChanged(.active, stage: stage) }
        stages = []
        for name in suites { UserDefaults().removePersistentDomain(forName: name) }
        suites = []
        PlaylistRelay.shared.allowStart = true
        RoomThumbnailCache.shared.resumeAll()
        super.tearDown()
    }

    /// 双窗，两路都「在播」；回退等待缩短到 50 毫秒。
    private func makeStage(pip supported: Bool = true, focus: LaneID = .primary) -> (StageModel, FakePiP?) {
        let pip: FakePiP? = supported ? FakePiP() : nil
        let name = "vela-phone-dualpip-\(UUID().uuidString)"
        suites.append(name)
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!))
        stages.append(stage)
        stage.updateFeed(intent: .reload) {
            $0.replace(with: CatalogPage(rooms: ["alice", "bob", "carol"].map { Room.direct(username: $0)! }, total: 3, nextOffset: 3))
        }
        XCTAssertFalse(stage.ageConfirmed)
        let audio = AudioFocusController(stage: stage, driver: FakeDriver())
        audio.pictureInPictureActive = { [weak stage] in stage?.platform.pictureInPicturePlaying ?? false }
        stage.audioFocus = audio
        stage.platform.pip = pip
        stage.platform.playerAdvancing = { _ in true }
        stage.platform.pipFallbackDelay = 0.05
        stage.setDual(true)
        if focus != .primary { stage.setFocus(focus) }
        stage.primary.playingName = "alice"
        stage.secondary!.playingName = "bob"
        return (stage, pip)
    }

    private func background(_ stage: StageModel) -> [PhoneLifecycleAction] {
        stage.platform.scenePhaseChanged(.inactive, stage: stage)
        return stage.platform.scenePhaseChanged(.background, stage: stage)
    }

    private func foreground(_ stage: StageModel) -> [PhoneLifecycleAction] {
        stage.platform.scenePhaseChanged(.inactive, stage: stage)
        return stage.platform.scenePhaseChanged(.active, stage: stage)
    }

    private func event(_ event: PiPEvent, _ stage: StageModel) {
        stage.platform.pictureInPictureEvent(event, stage: stage)
    }

    private func wait(_ seconds: Double = 0.3) async {
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    // MARK: 只有焦点路进画中画

    func testOnlyFocusLaneCarriesThePictureInPictureController() {
        XCTAssertTrue(PhoneRoomPage.carriesPictureInPicture(lane: .primary, focus: .primary))
        XCTAssertFalse(PhoneRoomPage.carriesPictureInPicture(lane: .secondary, focus: .primary))
        XCTAssertTrue(PhoneRoomPage.carriesPictureInPicture(lane: .secondary, focus: .secondary))
        XCTAssertFalse(PhoneRoomPage.carriesPictureInPicture(lane: .primary, focus: .secondary))
    }

    func testBackgroundKeepsFocusPrimaryAndReleasesSecondary() {
        let (stage, _) = makeStage()
        let actions = background(stage)
        XCTAssertTrue(actions.contains(.continueLane(.primary)), "\(actions)")
        XCTAssertTrue(actions.contains(.releaseLane(.secondary)), "\(actions)")
        XCTAssertFalse(actions.contains(.gateOff), "\(actions)")
        XCTAssertEqual(stage.wiring.epoch.allowedLane, .primary)
        XCTAssertNotNil(stage.laneToken(stage.primary))
        XCTAssertNil(stage.laneToken(stage.secondary!), "另一路的请求被拒")
        XCTAssertNil(stage.sharedToken())
        XCTAssertFalse(stage.secondary!.player.hasItem)
    }

    func testBackgroundKeepsFocusSecondaryAndReleasesPrimary() {
        let (stage, _) = makeStage(focus: .secondary)
        let actions = background(stage)
        XCTAssertTrue(actions.contains(.continueLane(.secondary)), "\(actions)")
        XCTAssertTrue(actions.contains(.releaseLane(.primary)), "\(actions)")
        XCTAssertEqual(stage.wiring.epoch.allowedLane, .secondary)
        XCTAssertNil(stage.laneToken(stage.primary))
        XCTAssertNotNil(stage.laneToken(stage.secondary!))
    }

    func testReturningToAppRestoresBothLanes() {
        let (stage, _) = makeStage()
        _ = background(stage)
        let actions = foreground(stage)
        XCTAssertTrue(actions.contains(.requestPlay(.primary, .playing)), "\(actions)")
        XCTAssertTrue(actions.contains(.requestPlay(.secondary, .idle)) || actions.contains { if case .requestPlay(.secondary, _) = $0 { return true } else { return false } }, "\(actions)")
        XCTAssertFalse(stage.lifecycleSuspended)
        XCTAssertNotNil(stage.primary.playTask)
        XCTAssertNotNil(stage.secondary!.playTask, "回 App 恢复双窗")
        XCTAssertEqual(stage.lanes.count, 2)
    }

    // MARK: 3 秒回退

    func testNotEnteringWithinTimeoutStopsBothLanes() async {
        let (stage, pip) = makeStage()
        _ = background(stage)
        XCTAssertNotNil(stage.laneToken(stage.primary), "等待期间焦点路仍放行")
        await wait()
        XCTAssertTrue(stage.lifecycleSuspended)
        XCTAssertNil(stage.wiring.epoch.allowedLane, "回退后不放行任何路")
        XCTAssertNil(stage.laneToken(stage.primary))
        XCTAssertNil(stage.laneToken(stage.secondary!))
        XCTAssertFalse(stage.playbackAllowed)
        XCTAssertFalse(stage.primary.player.hasItem)
        XCTAssertFalse(stage.secondary!.player.hasItem)
        XCTAssertEqual(pip?.stopCalls, 0, "没开始过就不用关")
    }

    func testStartFailureFallsBackImmediatelyWithoutLeavingPlayback() {
        let (stage, _) = makeStage()
        _ = background(stage)
        event(.willStart, stage)
        event(.failed("AVKitErrorDomain -1001"), stage)
        XCTAssertNil(stage.wiring.epoch.allowedLane)
        XCTAssertFalse(stage.playbackAllowed)
        XCTAssertFalse(stage.primary.player.hasItem)
        XCTAssertFalse(stage.secondary!.player.hasItem)
        XCTAssertEqual(stage.platform.pictureInPictureState, .none)
    }

    func testStuckStartingClosesSmallWindowAndStopsAll() async {
        let (stage, pip) = makeStage()
        event(.willStart, stage)
        _ = background(stage)
        await wait()
        XCTAssertNil(stage.wiring.epoch.allowedLane)
        XCTAssertEqual(pip?.stopCalls, 1, "不留黑小窗")
        XCTAssertFalse(stage.primary.player.hasItem)
    }

    func testEnteringInTimeKeepsFocusLaneAlive() async {
        let (stage, pip) = makeStage()
        _ = background(stage)
        event(.willStart, stage)
        event(.didStart, stage)
        await wait()
        XCTAssertEqual(stage.wiring.epoch.allowedLane, .primary)
        XCTAssertEqual(pip?.stopCalls, 0)
        XCTAssertTrue(stage.platform.pictureInPicturePlaying)
    }

    func testClosingSmallWindowInBackgroundStopsBothLanes() {
        let (stage, _) = makeStage()
        _ = background(stage)
        event(.willStart, stage)
        event(.didStart, stage)
        event(.didStop, stage)
        XCTAssertNil(stage.wiring.epoch.allowedLane)
        XCTAssertFalse(stage.playbackAllowed)
    }

    // MARK: 前台里手动进入画中画

    func testForegroundEntryReleasesOtherLaneAndExitRestoresIt() {
        let (stage, _) = makeStage()
        let other = stage.secondary!
        event(.willStart, stage)
        XCTAssertFalse(other.visible, "进入画中画时释放另一路")
        XCTAssertFalse(other.player.hasItem)
        XCTAssertTrue(stage.primary.visible)
        event(.didStart, stage)
        event(.willStop, stage)
        event(.didStop, stage)
        XCTAssertTrue(other.visible, "退出后恢复双窗")
        XCTAssertNotNil(other.playTask)
    }

    func testForegroundStartFailureRestoresOtherLane() {
        let (stage, _) = makeStage()
        let other = stage.secondary!
        event(.willStart, stage)
        event(.failed("AVKitErrorDomain -1001"), stage)
        XCTAssertTrue(other.visible)
        XCTAssertNotNil(other.playTask)
    }

    func testFocusCannotChangeWhileSmallWindowIsUp() {
        let (stage, _) = makeStage()
        event(.willStart, stage)
        event(.didStart, stage)
        stage.setFocus(.secondary)
        XCTAssertEqual(stage.focusLane, .primary, "画中画里那一路不能被换掉")
        event(.didStop, stage)
        stage.setFocus(.secondary)
        XCTAssertEqual(stage.focusLane, .secondary)
    }

    func testSingleWindowForegroundEntryDoesNotTouchVisibility() {
        let name = "vela-phone-dualpip-\(UUID().uuidString)"
        suites.append(name)
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!))
        stages.append(stage)
        stage.platform.pip = FakePiP()
        event(.willStart, stage)
        XCTAssertTrue(stage.primary.visible)
        event(.didStop, stage)
        XCTAssertTrue(stage.primary.visible)
    }
}
