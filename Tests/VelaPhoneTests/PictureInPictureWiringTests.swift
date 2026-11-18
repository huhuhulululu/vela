import XCTest
@testable import VelaPhone

/// iOS 画中画接线（修订 A2 的 T15b、修订 A3 的 3 秒回退）：进后台按 `PiPPolicy` 判定、画中画开始与结束、
/// 3 秒内未进入则全停、设置项默认值、音频钩子。画中画控制器用桩，系统控制器只在 iPad 模拟器实跑里验。
/// 年龄未确认，起播与目录不发请求；「焦点路在播」由 `playingName` 与桩出的 `playerAdvancing` 共同决定。
@MainActor
final class PictureInPictureWiringTests: XCTestCase {
    private final class FakePiP: PictureInPictureControlling {
        var canAutoEnter = true
        var autoEnter = true
        var stopCalls = 0
        func stop() { stopCalls += 1 }
    }

    private final class FakeDriver: AudioSessionDriving {
        struct Failure: Error {}
        var calls: [String] = []
        /// 只让接下来这么多次 `setActive(false)` 失败。
        var failDeactivations = 0

        func setCategory(_ category: AudioSessionCategory, mode: AudioSessionMode?, options: AudioSessionOptions) throws {
            calls.append("category \(category) mix=\(options.contains(.mixWithOthers))")
        }

        func setActive(_ active: Bool, notifyOthers: Bool) throws {
            calls.append("active \(active) notify=\(notifyOthers)")
            if !active, failDeactivations > 0 {
                failDeactivations -= 1
                throw Failure()
            }
        }
    }

    private var suites: [String] = []
    private var stages: [StageModel] = []

    override func tearDown() {
        // 回到前台撤掉还在等的回退计时，免得它在后面的测试里改全局的中继开关。
        for stage in stages { stage.platform.scenePhaseChanged(.active, stage: stage) }
        stages = []
        for name in suites { UserDefaults().removePersistentDomain(forName: name) }
        suites = []
        PlaylistRelay.shared.allowStart = true
        RoomThumbnailCache.shared.resumeAll()
        super.tearDown()
    }

    private func makeSettings() -> SettingsStore {
        let name = "vela-phone-pip-\(UUID().uuidString)"
        suites.append(name)
        return SettingsStore(defaults: UserDefaults(suiteName: name)!)
    }

    /// 主路 alice 正在播；会话用桩，不碰真实 `AVAudioSession`。回退等待缩短到 50 毫秒。
    private func makeStage(pip: FakePiP?, playing: Bool = true) -> (StageModel, FakeDriver) {
        let stage = StageModel(settings: makeSettings())
        stages.append(stage)
        stage.updateFeed { $0.insertDirect(Room.direct(username: "alice")!) }
        XCTAssertFalse(stage.ageConfirmed, "测试依赖年龄未确认：起播与目录不发请求")
        let driver = FakeDriver()
        let audio = AudioFocusController(stage: stage, driver: driver)
        audio.pictureInPictureActive = { [weak stage] in stage?.platform.pictureInPicturePlaying ?? false }
        stage.audioFocus = audio
        stage.platform.pip = pip
        stage.platform.playerAdvancing = { _ in playing }
        stage.platform.pipFallbackDelay = 0.05
        stage.primary.playingName = "alice"
        XCTAssertEqual(stage.activity(of: stage.primary), .playing)
        return (stage, driver)
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

    /// 画中画路继续：共享任务挂起，只放行主路；闸门、中继都开着。
    private func assertContinuing(_ stage: StageModel, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(stage.lifecycleSuspended, file: file, line: line)
        XCTAssertEqual(stage.wiring.epoch.allowedLane, .primary, file: file, line: line)
        XCTAssertNotNil(stage.laneToken(stage.primary), file: file, line: line)
        XCTAssertNil(stage.sharedToken(), file: file, line: line)
        XCTAssertTrue(stage.playbackAllowed, "画中画路不关闸门", file: file, line: line)
        XCTAssertTrue(PlaylistRelay.shared.allowStart, file: file, line: line)
    }

    /// 全停：不放行任何路，闸门与中继启动都关。
    private func assertStoppedAll(_ stage: StageModel, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(stage.lifecycleSuspended, file: file, line: line)
        XCTAssertNil(stage.wiring.epoch.allowedLane, file: file, line: line)
        XCTAssertNil(stage.laneToken(stage.primary), file: file, line: line)
        XCTAssertFalse(stage.playbackAllowed, file: file, line: line)
        XCTAssertFalse(PlaylistRelay.shared.allowStart, file: file, line: line)
    }

    // MARK: 设置项与回退判定

    func testAutoEnterSettingDefaultsOnAndPersists() {
        let settings = makeSettings()
        XCTAssertTrue(settings.pipAutoEnter, "回桌面自动进入画中画默认开")
        settings.pipAutoEnter = false
        XCTAssertFalse(settings.pipAutoEnter)
    }

    func testTogglingAutoEnterWritesSettingAndController() {
        let pip = FakePiP()
        let (stage, _) = makeStage(pip: pip)
        stage.platform.setPictureInPictureAutoEnter(false, stage: stage)
        XCTAssertFalse(stage.settings.pipAutoEnter)
        XCTAssertFalse(pip.autoEnter)
        stage.platform.setPictureInPictureAutoEnter(true, stage: stage)
        XCTAssertTrue(stage.settings.pipAutoEnter)
        XCTAssertTrue(pip.autoEnter)
    }

    func testFallbackDecisionOnlyActiveCountsAsEntered() {
        XCTAssertEqual(PiPFallback.timeout, 3)
        XCTAssertTrue(PiPFallback.shouldStop(scene: .background, state: .none))
        XCTAssertTrue(PiPFallback.shouldStop(scene: .background, state: .starting), "3 秒仍在启动按未进入处理")
        XCTAssertTrue(PiPFallback.shouldStop(scene: .background, state: .stopping))
        XCTAssertFalse(PiPFallback.shouldStop(scene: .background, state: .active))
        XCTAssertFalse(PiPFallback.shouldStop(scene: .active, state: .none), "已回前台不再回退")
        XCTAssertFalse(PiPFallback.shouldStop(scene: .inactive, state: .none))
    }

    // MARK: 进后台的策略接线

    func testBackgroundWithPlayingFocusContinuesThatLaneOnly() {
        let (stage, _) = makeStage(pip: FakePiP())
        let actions = background(stage)
        XCTAssertFalse(actions.contains(.gateOff), "\(actions)")
        XCTAssertTrue(actions.contains(.continueLane(.primary)), "\(actions)")
        XCTAssertTrue(actions.contains(.retainRelaySource(.primary)), "\(actions)")
        assertContinuing(stage)
    }

    func testBackgroundWithoutPictureInPictureSupportStopsAll() {
        let (stage, _) = makeStage(pip: nil)
        let actions = background(stage)
        XCTAssertEqual(actions.first, .gateOff, "不支持画中画（iPhone 模拟器）照旧全停")
        assertStoppedAll(stage)
    }

    func testBackgroundWithAutoEnterOffStopsAll() {
        let pip = FakePiP()
        pip.canAutoEnter = false
        let (stage, _) = makeStage(pip: pip)
        XCTAssertEqual(background(stage).first, .gateOff)
        assertStoppedAll(stage)
    }

    func testBackgroundWhenFocusNotAdvancingStopsAll() {
        let (stage, _) = makeStage(pip: FakePiP(), playing: false)
        XCTAssertEqual(background(stage).first, .gateOff, "焦点路没真在播放就不进画中画")
        assertStoppedAll(stage)
    }

    func testBackgroundWhenFocusManuallyPausedStopsAll() {
        let (stage, _) = makeStage(pip: FakePiP())
        stage.primary.manuallyPaused = true
        XCTAssertEqual(background(stage).first, .gateOff)
        assertStoppedAll(stage)
    }

    // MARK: 3 秒回退

    func testNotEnteringWithinTimeoutFallsBackToStopAll() async {
        let pip = FakePiP()
        let (stage, driver) = makeStage(pip: pip)
        _ = background(stage)
        assertContinuing(stage)
        await wait()
        assertStoppedAll(stage)
        XCTAssertEqual(pip.stopCalls, 0, "没开始过就不用关")
        XCTAssertTrue(driver.calls.contains("active false notify=true"), "\(driver.calls)")
    }

    func testStuckStartingFallsBackAndClosesPictureInPicture() async {
        let pip = FakePiP()
        let (stage, _) = makeStage(pip: pip)
        event(.willStart, stage)
        _ = background(stage)
        await wait()
        assertStoppedAll(stage)
        XCTAssertEqual(pip.stopCalls, 1, "不留黑小窗")
    }

    func testStartedPictureInPictureCancelsFallback() async {
        let (stage, _) = makeStage(pip: FakePiP())
        event(.willStart, stage)
        _ = background(stage)
        event(.didStart, stage)
        await wait()
        assertContinuing(stage)
        XCTAssertEqual(stage.platform.pictureInPictureState, .active)
    }

    func testFailureToStartFallsBackImmediately() {
        let (stage, _) = makeStage(pip: FakePiP())
        _ = background(stage)
        event(.failed("AVKitErrorDomain -1001"), stage)
        assertStoppedAll(stage)
    }

    func testLateStartAfterFallbackIsClosed() async {
        let pip = FakePiP()
        let (stage, _) = makeStage(pip: pip)
        _ = background(stage)
        await wait()
        assertStoppedAll(stage)
        event(.willStart, stage)
        event(.didStart, stage)
        XCTAssertEqual(pip.stopCalls, 1, "回退后才起来的画中画立即关闭")
        assertStoppedAll(stage)
    }

    /// 全停之后迟到的 willStart：不得重新激活音频会话（激活了就再没人释放，他人音乐恢复不了）。
    func testLateWillStartAfterStopAllDoesNotReactivateSession() async {
        let pip = FakePiP()
        let (stage, driver) = makeStage(pip: pip)
        _ = background(stage)
        await wait()
        assertStoppedAll(stage)
        driver.calls = []
        event(.willStart, stage)
        XCTAssertFalse(driver.calls.contains { $0.hasPrefix("active true") }, "迟到的 willStart 不激活会话：\(driver.calls)")
        event(.didStart, stage)
        XCTAssertFalse(driver.calls.contains { $0.hasPrefix("active true") }, "\(driver.calls)")
        XCTAssertEqual(pip.stopCalls, 1)
    }

    // MARK: 画中画结束

    func testUserClosingPictureInPictureInBackgroundStopsAll() {
        let (stage, _) = makeStage(pip: FakePiP())
        _ = background(stage)
        event(.didStart, stage)
        event(.willStop, stage)
        event(.didStop, stage)
        assertStoppedAll(stage)
        XCTAssertEqual(stage.platform.pictureInPictureState, PiPPolicy.State.none)
    }

    func testRestoreThenForegroundResumesWithoutStopAll() {
        let pip = FakePiP()
        let (stage, _) = makeStage(pip: pip)
        _ = background(stage)
        event(.didStart, stage)
        event(.restoreRequested, stage)
        event(.willStop, stage)
        event(.didStop, stage)
        assertContinuing(stage)
        let actions = foreground(stage)
        XCTAssertEqual(actions.first, .epochResume, "\(actions)")
        XCTAssertTrue(actions.contains(.requestPlay(.primary, .playing)), "\(actions)")
        XCTAssertFalse(stage.lifecycleSuspended)
        XCTAssertEqual(pip.stopCalls, 0, "已经结束就不用再关")
    }

    func testRestoreWithoutReturningStillStopsAllAfterTimeout() async {
        let (stage, _) = makeStage(pip: FakePiP())
        _ = background(stage)
        event(.didStart, stage)
        event(.restoreRequested, stage)
        event(.didStop, stage)
        await wait()
        assertStoppedAll(stage)
    }

    func testReturningToAppClosesActivePictureInPicture() {
        let pip = FakePiP()
        let (stage, _) = makeStage(pip: pip)
        _ = background(stage)
        event(.didStart, stage)
        let actions = foreground(stage)
        XCTAssertEqual(pip.stopCalls, 1)
        XCTAssertEqual(actions.first, .epochResume)
        XCTAssertFalse(stage.lifecycleSuspended)
    }

    func testForegroundPictureInPictureSurvivesInactiveBounce() {
        let pip = FakePiP()
        let (stage, _) = makeStage(pip: pip)
        event(.willStart, stage)
        event(.didStart, stage)
        XCTAssertTrue(foreground(stage).isEmpty, "没挂起过：控制中心等 .inactive 来回不动画中画")
        XCTAssertEqual(pip.stopCalls, 0)
    }

    // MARK: 音频

    func testAudioHookFollowsPictureInPictureState() {
        let (stage, _) = makeStage(pip: FakePiP())
        let hook = { stage.platform.audioFocus?.pictureInPictureActive() ?? false }
        XCTAssertNotNil(stage.platform.audioFocus, "模型初始化时装上的控制器")
        XCTAssertFalse(hook())
        event(.willStart, stage)
        XCTAssertTrue(hook(), "启动中视同已进入")
        event(.didStart, stage)
        XCTAssertTrue(hook())
        event(.willStop, stage)
        XCTAssertFalse(hook())
        event(.didStop, stage)
        XCTAssertFalse(hook())
    }

    func testMutedStartKeepsPlaybackMixSessionActive() {
        let (stage, driver) = makeStage(pip: FakePiP())
        XCTAssertTrue(stage.muted)
        event(.willStart, stage)
        XCTAssertEqual(driver.calls, ["category playback mix=true", "active true notify=false"])
        XCTAssertTrue(stage.muted, "不改静音")
        event(.didStart, stage)
        stage.toggleMute()
        XCTAssertFalse(stage.muted)
        driver.calls = []
        stage.toggleMute()
        XCTAssertTrue(stage.muted)
        XCTAssertFalse(driver.calls.contains("active false notify=true"), "画中画中再静音保持会话：\(driver.calls)")
    }
    // MARK: 画中画里最后一路停播、停用失败与迟到启动

    /// 画中画里最后一路最终失败：已经没有直播要续播，出声会话要释放，不等关小窗。
    func testFinalFailureDuringPictureInPictureReleasesSession() {
        let (stage, driver) = makeStage(pip: FakePiP())
        stage.toggleMute()
        XCTAssertFalse(stage.muted)
        _ = background(stage)
        event(.didStart, stage)
        assertContinuing(stage)
        driver.calls = []

        stage.notePlaybackFailed(true, on: stage.primary)

        XCTAssertEqual(driver.calls.last, "active false notify=true", "最后一路已停，释放会话：\(driver.calls)")
    }

    /// 全停时停用失败，重试落在迟到的 willStart 期间：画中画没有可续播的路，重试照样补做停用；之后不再留着待补做。
    func testFailedDeactivationInterleavedWithLateStartIsRedone() async {
        let pip = FakePiP()
        let (stage, driver) = makeStage(pip: pip)
        let audio = stage.audioFocus as? AudioFocusController
        audio?.deactivationRetryDelay = 0.4
        stage.prepareAudioSession()
        driver.failDeactivations = 1
        _ = background(stage)
        await wait(0.15)
        assertStoppedAll(stage)
        XCTAssertEqual(audio?.deactivationPending, true, "全停时停用失败")

        event(.willStart, stage)
        await wait(0.6)
        XCTAssertEqual(audio?.deactivationPending, false, "迟到的启动期间重试也要补做停用：\(driver.calls)")
        event(.didStart, stage)
        event(.willStop, stage)
        event(.didStop, stage)
        XCTAssertEqual(pip.stopCalls, 1)
        XCTAssertEqual(driver.calls.filter { $0 == "active false notify=true" }.count, 2, "\(driver.calls)")
        XCTAssertEqual(driver.calls.filter { $0.hasPrefix("active true") }.count, 1, "只有最初那次准备激活过，迟到启动不重新激活：\(driver.calls)")
    }

    /// 重试已经放弃（两次都失败）后，迟到的画中画结束时补做停用。
    func testLateStartEndingRedoesPendingDeactivation() async {
        let pip = FakePiP()
        let (stage, driver) = makeStage(pip: pip)
        let audio = stage.audioFocus as? AudioFocusController
        audio?.deactivationRetryDelay = 0.05
        stage.prepareAudioSession()
        driver.failDeactivations = 2
        _ = background(stage)
        await wait(0.4)
        assertStoppedAll(stage)
        XCTAssertEqual(audio?.deactivationPending, true, "停用与重试都失败")

        event(.willStart, stage)
        event(.failed("late"), stage)
        XCTAssertEqual(audio?.deactivationPending, false, "迟到的启动失败时补做停用：\(driver.calls)")
    }
}
