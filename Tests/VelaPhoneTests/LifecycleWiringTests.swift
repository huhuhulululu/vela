import XCTest
@testable import VelaPhone

/// 统一挂起与由 Stage 层恢复（§3.1、§3.2、修订 A2 接线要求）。只在 VelaPhoneTests 里编译：要真的 `StageModel`。
/// 年龄未确认，`playCurrent` 与 `reload` 在第一行就返回，不发网络请求；起播是否被请求看 `playTask` 有没有被重新设上。
@MainActor
final class LifecycleWiringTests: XCTestCase {
    private var suites: [String] = []

    override func tearDown() {
        for name in suites { UserDefaults().removePersistentDomain(forName: name) }
        suites = []
        PlaylistRelay.shared.allowStart = true
        RoomThumbnailCache.shared.resumeAll()
        super.tearDown()
    }

    private func makeStage(secondary: String? = nil) -> StageModel {
        let name = "vela-phone-tests-\(UUID().uuidString)"
        suites.append(name)
        let stage = StageModel(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!))
        stage.updateFeed { $0.insertDirect(Room.direct(username: "alice")!) }
        if let secondary {
            let lane = PlaybackLane(id: .secondary)
            stage.secondary = lane
            stage.attach(lane)
            lane.username = secondary
        }
        XCTAssertFalse(stage.ageConfirmed, "测试依赖年龄未确认：起播与目录不发请求")
        XCTAssertEqual(stage.name(of: stage.primary), "alice")
        return stage
    }

    private func sleeper() -> Task<Void, Never> {
        Task { try? await Task.sleep(nanoseconds: 30_000_000_000) }
    }

    private func background(_ stage: StageModel) -> [PhoneLifecycleAction] {
        stage.platform.scenePhaseChanged(.inactive, stage: stage)
        return stage.platform.scenePhaseChanged(.background, stage: stage)
    }

    private func foreground(_ stage: StageModel) -> [PhoneLifecycleAction] {
        stage.platform.scenePhaseChanged(.inactive, stage: stage)
        return stage.platform.scenePhaseChanged(.active, stage: stage)
    }

    // MARK: 挂起期间 token 被拒

    func testBackgroundRejectsSharedAndLaneTokensAtAllFourGates() {
        let stage = makeStage()
        let sharedBefore = stage.sharedToken()
        let laneBefore = stage.laneToken(stage.primary)
        XCTAssertNotNil(sharedBefore)
        XCTAssertNotNil(laneBefore)

        let actions = background(stage)
        XCTAssertEqual(actions.first, .gateOff)
        XCTAssertTrue(stage.lifecycleSuspended)
        XCTAssertNil(stage.sharedToken(), "目录、搜索、收藏查询、侧车、缩略图都不能开工")
        XCTAssertNil(stage.laneToken(stage.primary))
        for gate in LifecycleEpoch.Gate.allCases {
            XCTAssertFalse(stage.lifecycleAllows(gate, sharedBefore), "\(gate)")
            XCTAssertFalse(stage.lifecycleAllows(gate, laneBefore), "\(gate)")
        }
        XCTAssertFalse(PlaylistRelay.shared.allowStart, "中继 start 前的开关关闭")
        XCTAssertFalse(stage.playbackAllowed, "闸门关闭")

        _ = foreground(stage)
        XCTAssertFalse(stage.lifecycleSuspended)
        XCTAssertNotNil(stage.sharedToken())
        XCTAssertTrue(PlaylistRelay.shared.allowStart)
        XCTAssertTrue(stage.playbackAllowed, "回前台闸门重开")
        for gate in LifecycleEpoch.Gate.allCases {
            XCTAssertFalse(stage.lifecycleAllows(gate, sharedBefore), "挂起前的 token 回前台后仍无效")
        }
    }

    func testInactiveAloneDoesNotSuspend() {
        let stage = makeStage()
        let actions = stage.platform.scenePhaseChanged(.inactive, stage: stage)
        XCTAssertTrue(actions.isEmpty)
        XCTAssertFalse(stage.lifecycleSuspended)
        XCTAssertTrue(stage.playbackAllowed, ".inactive 时画面照常播放")
    }

    func testSuspendCancelsSharedAndLaneTasks() async {
        let stage = makeStage(secondary: "carol")
        let rest = sleeper()
        let targeted = sleeper()
        let search = sleeper()
        let play = sleeper()
        let away = sleeper()
        let secondPlay = sleeper()
        stage.catalogRestTask = rest
        stage.targetedTask = targeted
        stage.siteSearchTask = search
        stage.primary.playTask = play
        stage.primary.awayTask = away
        stage.secondary?.playTask = secondPlay
        let started = expectation(description: "tracked started")
        let cancelled = expectation(description: "tracked cancelled")
        let tracked = Task { @MainActor in
            await stage.trackShared {
                started.fulfill()
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                if Task.isCancelled { cancelled.fulfill() }
            }
        }
        await fulfillment(of: [started], timeout: 2)
        let generation = stage.loadGeneration

        _ = background(stage)

        await fulfillment(of: [cancelled], timeout: 2)
        await tracked.value
        for task in [rest, targeted, search, play, away, secondPlay] {
            XCTAssertTrue(task.isCancelled)
        }
        XCTAssertNil(stage.catalogRestTask)
        XCTAssertNil(stage.targetedTask)
        XCTAssertNil(stage.siteSearchTask)
        XCTAssertGreaterThan(stage.loadGeneration, generation, "在途目录结果按代次作废")
        XCTAssertTrue(stage.wiring.sharedWork.isEmpty)
    }

    func testThumbnailsAndSidecarRefuseWhileSuspended() async {
        let stage = makeStage()
        _ = background(stage)
        let url = URL(string: "http://127.0.0.1:9/vela-test-\(UUID().uuidString).jpg")
        RoomThumbnailCache.shared.prefetch(url: url)
        XCTAssertEqual(RoomThumbnailCache.shared.inflightCount, 0, "挂起期间不下载缩略图")
        let thumb = await RoomThumbnailCache.shared.imageOrFetch(for: url)
        XCTAssertNil(thumb)
        do {
            _ = try await stage.sidecar.get(URL(string: "https://example.invalid/")!)
            XCTFail("挂起期间侧车不应执行")
        } catch {
            XCTAssertTrue(error is CancellationError, "\(error)")
        }
        _ = foreground(stage)
        let before = RoomThumbnailCache.shared.inflightCount
        RoomThumbnailCache.shared.prefetch(url: url)
        XCTAssertEqual(RoomThumbnailCache.shared.inflightCount, before + 1, "回前台后照常下载")
    }

    // MARK: 画中画按路放行（修订 A2 接线要求）

    func testPictureInPictureAllowsOnlyThatLaneAndRejectsSharedTasks() {
        let stage = makeStage(secondary: "carol")
        let focusPlay = sleeper()
        stage.primary.playTask = focusPlay
        let secondPlay = sleeper()
        stage.secondary?.playTask = secondPlay

        stage.platform.run([
            .suspendSharedTasks, .allowLaneRequests(.primary), .settleDwell(.background), .flushSavers,
            .releaseLane(.secondary), .continueLane(.primary), .retainRelaySource(.primary), .noteResumeIntent(nil)
        ], on: stage)

        XCTAssertTrue(stage.lifecycleSuspended)
        XCTAssertNil(stage.sharedToken(), "共享任务（目录、搜索、收藏查询、侧车、缩略图）被拒")
        let pip = stage.laneToken(stage.primary)
        XCTAssertNotNil(pip)
        for gate in LifecycleEpoch.Gate.allCases {
            XCTAssertTrue(stage.lifecycleAllows(gate, pip), "画中画路 \(gate) 放行")
        }
        XCTAssertNil(stage.laneToken(stage.secondary!), "另一路被拒")
        XCTAssertTrue(PlaylistRelay.shared.allowStart, "画中画路仍能起中继")
        XCTAssertFalse(focusPlay.isCancelled, "画中画路的任务不取消")
        XCTAssertTrue(secondPlay.isCancelled)
        XCTAssertTrue(stage.playbackAllowed, "画中画分支不关全局闸门")

        RoomThumbnailCache.shared.prefetch(url: URL(string: "http://127.0.0.1:9/vela-pip-\(UUID().uuidString).jpg"))
        XCTAssertEqual(RoomThumbnailCache.shared.inflightCount, 0, "缩略图随共享任务挂起")
        focusPlay.cancel()
    }

    // MARK: 回前台恢复矩阵

    func testFirstResolveInFlightIsReplayedOnReturn() {
        let stage = makeStage()
        let inflight = sleeper()
        stage.primary.playTask = inflight
        XCTAssertEqual(stage.activity(of: stage.primary), .firstResolve)

        _ = background(stage)
        XCTAssertTrue(inflight.isCancelled)
        XCTAssertNil(stage.primary.playTask)

        let actions = foreground(stage)
        XCTAssertEqual(actions.first, .epochResume)
        XCTAssertTrue(actions.contains(.requestPlay(.primary, .firstResolve)), "\(actions)")
        XCTAssertNotNil(stage.primary.playTask, "由 Stage 层重新起播")
    }

    func testRoomSwitchInFlightIsReplayedOnReturn() {
        let stage = makeStage()
        stage.primary.readyName = "bob"
        stage.primary.playTask = sleeper()
        XCTAssertEqual(stage.activity(of: stage.primary), .roomSwitch)

        _ = background(stage)
        let actions = foreground(stage)
        XCTAssertTrue(actions.contains(.requestPlay(.primary, .roomSwitch)), "\(actions)")
        XCTAssertNotNil(stage.primary.playTask)
    }

    func testPlayingLaneIsReplayedOnReturn() {
        let stage = makeStage()
        stage.primary.playingName = "alice"
        XCTAssertEqual(stage.activity(of: stage.primary), .playing)

        _ = background(stage)
        let actions = foreground(stage)
        XCTAssertTrue(actions.contains(.requestPlay(.primary, .playing)), "\(actions)")
        XCTAssertNotNil(stage.primary.playTask)
    }

    func testFailedLaneKeepsFailureAndDoesNotReplay() {
        let stage = makeStage()
        stage.primary.state = .failed
        stage.primary.playbackNote = "画面中断了"

        _ = background(stage)
        let actions = foreground(stage)
        XCTAssertTrue(actions.contains(.keepFailure(.primary)), "\(actions)")
        XCTAssertNil(stage.primary.playTask, "失败态不自动重播")
        XCTAssertEqual(stage.primary.state, .failed)
        XCTAssertEqual(stage.primary.playbackNote, "画面中断了", "保留失败提示")
    }

    func testManuallyPausedLaneStaysPaused() {
        let stage = makeStage()
        stage.primary.manuallyPaused = true

        _ = background(stage)
        let actions = foreground(stage)
        XCTAssertTrue(actions.contains(.stayPaused(.primary)), "\(actions)")
        XCTAssertNil(stage.primary.playTask)
        XCTAssertTrue(stage.primary.manuallyPaused)
        XCTAssertFalse(stage.laneAllowed(stage.primary), "手动暂停的路闸门仍关")
    }

    func testHiddenLaneStaysReleasedWhileVisibleLaneReplays() {
        let stage = makeStage(secondary: "carol")
        stage.secondary?.visible = false

        _ = background(stage)
        let actions = foreground(stage)
        XCTAssertTrue(actions.contains(.stayReleased(.secondary)), "\(actions)")
        XCTAssertTrue(actions.contains(.requestPlay(.primary, .idle)), "\(actions)")
        XCTAssertNil(stage.secondary?.playTask, "隐藏路保持已释放")
        XCTAssertNotNil(stage.primary.playTask)
    }

    func testLoadingCatalogIsNotedAndRedoneOnReturn() {
        let stage = makeStage()
        stage.loading = true

        let down = background(stage)
        XCTAssertTrue(down.contains(.noteResumeIntent(.reload)), "\(down)")
        XCTAssertEqual(stage.wiring.resumeIntent, .reload)
        XCTAssertFalse(stage.loading, "挂起时结束加载中状态")

        let up = foreground(stage)
        XCTAssertTrue(up.contains(.redoResumeIntent(.reload)), "\(up)")
        XCTAssertNil(stage.wiring.resumeIntent)
    }

    func testNoSuspensionMeansNothingToResume() {
        let stage = makeStage()
        stage.primary.playTask = nil
        let actions = stage.platform.scenePhaseChanged(.active, stage: stage)
        XCTAssertTrue(actions.isEmpty)
        XCTAssertNil(stage.primary.playTask)
    }
}
