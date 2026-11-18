import XCTest
@testable import VelaPhone

/// 三路画中画矩阵（ios-triple 修订 v2 M7）：分别以 P、S、T 为焦点进入，其余两路的播放项、中继源与在途任务全部释放；
/// 启动中与进行中禁止切焦点、改路数；覆盖失败、卡在启动、超时、迟到回调、后台关闭、回 App 恢复、与横屏叠加。
/// 画中画控制器用桩；系统控制器的行为只能在 iPad 模拟器实跑里验。
@MainActor
final class TriplePictureInPictureTests: XCTestCase {
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

    /// 三路，都「在播」；回退等待缩短到 50 毫秒。
    private func makeStage(focus: LaneID = .primary) -> (StageModel, FakePiP) {
        let pip = FakePiP()
        let name = "vela-phone-triplepip-\(UUID().uuidString)"
        suites.append(name)
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!))
        stages.append(stage)
        stage.updateFeed(intent: .reload) {
            $0.replace(with: CatalogPage(rooms: ["alice", "bob", "carol", "dave"].map { Room.direct(username: $0)! }, total: 4, nextOffset: 4))
        }
        let audio = AudioFocusController(stage: stage, driver: FakeDriver())
        audio.pictureInPictureActive = { [weak stage] in stage?.platform.pictureInPicturePlaying ?? false }
        stage.audioFocus = audio
        stage.platform.pip = pip
        stage.platform.playerAdvancing = { _ in true }
        stage.platform.pipFallbackDelay = 0.05
        stage.setLaneCount(3)
        if focus != .primary { stage.setFocus(focus) }
        for lane in stage.lanes { lane.playingName = stage.name(of: lane) }
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

    private func assertReleased(_ lane: PlaybackLane, _ label: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertFalse(lane.player.hasItem, "\(label) 播放项已释放", file: file, line: line)
        XCTAssertNil(lane.playTask, "\(label) 没有在途起播", file: file, line: line)
        XCTAssertNil(lane.prefetchTask, "\(label) 没有在途预取", file: file, line: line)
        XCTAssertNil(lane.capTask, "\(label) 没有在途换档", file: file, line: line)
    }

    // MARK: 以 P、S、T 为焦点进入

    func testOnlyFocusLaneCarriesTheController() {
        for focus in LaneID.allCases {
            for lane in LaneID.allCases {
                XCTAssertEqual(PhoneRoomPage.carriesPictureInPicture(lane: lane, focus: focus), lane == focus)
            }
        }
    }

    func testBackgroundWithEachFocusContinuesOnlyThatLane() {
        for focus in LaneID.allCases {
            let (stage, _) = makeStage(focus: focus)
            let actions = background(stage)
            XCTAssertTrue(actions.contains(.continueLane(focus.role)), "焦点 \(focus)：\(actions)")
            for other in stage.lanes where other.id != focus {
                XCTAssertTrue(actions.contains(.releaseLane(other.id.role)), "焦点 \(focus) 释放 \(other.id)：\(actions)")
                assertReleased(other, "焦点 \(focus) 时的 \(other.id)")
                XCTAssertNil(stage.laneToken(other), "其余路的请求被拒")
            }
            XCTAssertEqual(stage.wiring.epoch.allowedLane, focus.role)
            XCTAssertNotNil(stage.laneToken(stage.findLane(focus)!))
            XCTAssertNil(stage.sharedToken())
        }
    }

    func testForegroundEntryWithEachFocusReleasesOthersAndExitRestoresThem() {
        for focus in LaneID.allCases {
            let (stage, _) = makeStage(focus: focus)
            let others = stage.lanes.filter { $0.id != focus }
            event(.willStart, stage)
            for other in others {
                XCTAssertFalse(other.visible, "焦点 \(focus)：进入时释放 \(other.id)")
                assertReleased(other, "焦点 \(focus) 的 \(other.id)")
            }
            XCTAssertTrue(stage.findLane(focus)!.visible)
            event(.didStart, stage)
            event(.willStop, stage)
            event(.didStop, stage)
            for other in others {
                XCTAssertTrue(other.visible, "退出后恢复 \(other.id)")
                XCTAssertNotNil(other.playTask)
            }
            XCTAssertEqual(stage.laneCount, 3)
        }
    }

    // MARK: 启动中与进行中锁定

    func testFocusAndLaneCountLockedWhileStartingOrActive() {
        let (stage, _) = makeStage(focus: .tertiary)
        event(.willStart, stage)
        stage.setFocus(.primary)
        stage.setLaneCount(1)
        XCTAssertEqual(stage.focusLane, .tertiary, "启动中不能切焦点")
        XCTAssertEqual(stage.laneCount, 3, "启动中不能改路数")
        event(.didStart, stage)
        stage.setFocus(.secondary)
        stage.setLaneCount(2)
        XCTAssertEqual(stage.focusLane, .tertiary)
        XCTAssertEqual(stage.laneCount, 3)
        XCTAssertEqual(stage.settings.laneCount, 3, "被拒的改动不写设置")
        event(.didStop, stage)
        stage.setLaneCount(2)
        XCTAssertEqual(stage.laneCount, 2)
        XCTAssertEqual(stage.focusLane, .secondary, "小窗收掉后才压紧，焦点随迁")
    }

    // MARK: 失败、卡住、超时、迟到回调

    func testForegroundStartFailureRestoresBothOtherLanes() {
        let (stage, _) = makeStage(focus: .secondary)
        event(.willStart, stage)
        event(.failed("AVKitErrorDomain -1001"), stage)
        for other in [stage.primary, stage.tertiary!] {
            XCTAssertTrue(other.visible)
            XCTAssertNotNil(other.playTask)
        }
    }

    func testBackgroundStartFailureStopsAllThree() {
        let (stage, _) = makeStage(focus: .tertiary)
        _ = background(stage)
        event(.willStart, stage)
        event(.failed("AVKitErrorDomain -1001"), stage)
        XCTAssertNil(stage.wiring.epoch.allowedLane)
        XCTAssertFalse(stage.playbackAllowed)
        for lane in stage.lanes { XCTAssertFalse(lane.player.hasItem, "\(lane.id)") }
    }

    func testStuckStartingAndTimeoutStopAllThreeAndCloseSmallWindow() async {
        let (stuck, pip) = makeStage(focus: .tertiary)
        event(.willStart, stuck)
        _ = background(stuck)
        await wait()
        XCTAssertNil(stuck.wiring.epoch.allowedLane)
        XCTAssertEqual(pip.stopCalls, 1, "不留黑小窗")
        for lane in stuck.lanes { XCTAssertFalse(lane.player.hasItem) }

        let (silent, quiet) = makeStage(focus: .secondary)
        _ = background(silent)
        await wait()
        XCTAssertTrue(silent.lifecycleSuspended)
        for lane in silent.lanes { XCTAssertNil(silent.laneToken(lane), "超时后不放行任何路") }
        XCTAssertEqual(quiet.stopCalls, 0)
    }

    func testLateStartAfterFallbackIsClosedAndDoesNotResumeLanes() async {
        let (stage, pip) = makeStage(focus: .tertiary)
        _ = background(stage)
        await wait()
        event(.willStart, stage)
        event(.didStart, stage)
        XCTAssertEqual(pip.stopCalls, 1, "回退后才起来的画中画立即关闭")
        XCTAssertNil(stage.wiring.epoch.allowedLane)
        for lane in stage.lanes { XCTAssertFalse(lane.player.hasItem) }
    }

    // MARK: 后台关闭、回 App 恢复、与横屏叠加

    func testClosingSmallWindowInBackgroundStopsAll() {
        let (stage, _) = makeStage(focus: .secondary)
        _ = background(stage)
        event(.willStart, stage)
        event(.didStart, stage)
        event(.didStop, stage)
        XCTAssertNil(stage.wiring.epoch.allowedLane)
        XCTAssertFalse(stage.playbackAllowed)
    }

    func testReturningToAppRestoresAllThreeLanes() {
        let (stage, _) = makeStage(focus: .tertiary)
        _ = background(stage)
        event(.willStart, stage)
        event(.didStart, stage)
        _ = foreground(stage)
        event(.willStop, stage)
        event(.didStop, stage)
        XCTAssertFalse(stage.lifecycleSuspended)
        XCTAssertEqual(stage.lanes.count, 3)
        for lane in stage.lanes {
            XCTAssertTrue(lane.visible, "\(lane.id)")
            XCTAssertNotNil(lane.playTask, "回 App 后 \(lane.id) 恢复")
        }
        XCTAssertEqual(stage.focusLane, .tertiary)
    }

    func testPictureInPictureOnTopOfLandscapeRestoresOnlyAfterBothEnd() {
        let (stage, _) = makeStage(focus: .secondary)
        stage.setLandscape(true)
        event(.willStart, stage)
        event(.didStart, stage)
        event(.willStop, stage)
        event(.didStop, stage)
        XCTAssertFalse(stage.primary.visible, "画中画结束但仍横屏：非焦点路保持释放")
        XCTAssertFalse(stage.tertiary!.visible)
        XCTAssertNil(stage.tertiary!.playTask)
        stage.setLandscape(false)
        XCTAssertTrue(stage.primary.visible)
        XCTAssertNotNil(stage.tertiary!.playTask)
    }
    // MARK: 审计 medium：画中画期间路数菜单禁用，结束后补判舞台尺寸

    func testLaneCountLockIsPublishedAndStageFitIsReappliedAfterPictureInPicture() {
        let (stage, _) = makeStage(focus: .secondary)
        stage.notePortraitStageSize(CGSize(width: 402, height: 874))
        XCTAssertFalse(stage.laneCountLocked)
        event(.willStart, stage)
        XCTAssertTrue(stage.laneCountLocked, "启动中就锁：界面据此禁用路数菜单并说明原因")
        stage.notePortraitStageSize(CGSize(width: 300, height: 400))
        XCTAssertEqual(stage.laneCount, 3, "画中画期间不改路数")
        event(.didStart, stage)
        XCTAssertTrue(stage.laneCountLocked)
        event(.willStop, stage)
        event(.didStop, stage)
        XCTAssertFalse(stage.laneCountLocked)
        XCTAssertEqual(stage.laneCount, 2, "小窗收掉后补一次判定：放不下三格就降到 2 路")
        XCTAssertEqual(stage.settings.laneCount, 3, "临时降路不改用户选择")
    }

    func testFailedStartAlsoReleasesTheLock() {
        let (stage, _) = makeStage()
        event(.willStart, stage)
        event(.failed("AVKitErrorDomain -1001"), stage)
        XCTAssertFalse(stage.laneCountLocked)
        stage.setLaneCount(2)
        XCTAssertEqual(stage.laneCount, 2)
    }
}
