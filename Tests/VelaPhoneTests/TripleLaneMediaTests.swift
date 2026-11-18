import AVFoundation
import XCTest
@testable import VelaPhone

/// 三路的声音、停留与解码资源（ios-triple 修订 v2 的 M8、M10、H3）。会话用桩，时间由测试给。
@MainActor
final class TripleLaneMediaTests: XCTestCase {
    private final class FakeDriver: AudioSessionDriving {
        struct Failure: Error {}
        var calls: [String] = []
        var failCategory = false
        var onCategory: () -> Void = {}
        var onActive: () -> Void = {}

        func setCategory(_ category: AudioSessionCategory, mode: AudioSessionMode?, options: AudioSessionOptions) throws {
            onCategory()
            calls.append("category \(category) mix=\(options.contains(.mixWithOthers))")
            if failCategory { throw Failure() }
        }

        func setActive(_ active: Bool, notifyOthers: Bool) throws {
            onActive()
            calls.append("active \(active) notify=\(notifyOthers)")
        }
    }

    private var suites: [String] = []
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

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

    private func makeStage(lanes: Int = 3) -> StageModel {
        let name = "vela-phone-triple-media-\(UUID().uuidString)"
        suites.append(name)
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!))
        stage.updateFeed(intent: .reload) {
            $0.replace(with: CatalogPage(rooms: ["alice", "bob", "carol", "dave"].map { Room.direct(username: $0)! }, total: 4, nextOffset: 4))
        }
        stage.setLaneCount(lanes)
        return stage
    }

    private func withAudio(muted: Bool) -> (StageModel, FakeDriver) {
        let stage = makeStage(lanes: 1)
        let driver = FakeDriver()
        stage.audioFocus = AudioFocusController(stage: stage, driver: driver)
        stage.setLaneCount(3)
        if !muted {
            stage.toggleMute()
            XCTAssertFalse(stage.muted)
        }
        driver.calls = []
        return (stage, driver)
    }

    private func audible(_ stage: StageModel) -> [LaneID] {
        stage.lanes.filter { !$0.player.player.isMuted && !stage.muted }.map(\.id)
    }

    // MARK: M8 出声

    func testEveryDirectedFocusTransitionIsOrderedAndSingleAudible() {
        let (stage, driver) = withAudio(muted: false)
        XCTAssertEqual(audible(stage), [.primary])
        let path: [LaneID] = [.secondary, .primary, .tertiary, .secondary, .tertiary, .primary]
        for target in path {
            let from = stage.focusLane
            var atCategory: [LaneID] = [.primary, .secondary]
            var atActive: [LaneID] = [.primary, .secondary]
            driver.onCategory = { atCategory = self.audible(stage) }
            driver.onActive = { atActive = self.audible(stage) }
            driver.calls = []
            stage.setFocus(target)
            XCTAssertEqual(atCategory, [], "\(from) → \(target)：改类别时没有路出声")
            XCTAssertEqual(atActive, [], "\(from) → \(target)：激活会话时没有路出声")
            XCTAssertEqual(driver.calls, ["category playback mix=false", "active true notify=false"])
            XCTAssertEqual(audible(stage), [target], "\(from) → \(target)")
        }
    }

    func testNewLaneReducedLanesAndRebuiltItemsStayMutedUnlessFocused() {
        let (stage, _) = withAudio(muted: false)
        XCTAssertEqual(audible(stage), [.primary], "新开的两路都不出声")
        stage.setFocus(.tertiary)
        stage.setLaneCount(2)
        XCTAssertEqual(stage.focusLane, .secondary)
        XCTAssertEqual(audible(stage), [.secondary], "压紧后仍只有焦点路出声")
        stage.setLaneCount(3)
        XCTAssertEqual(audible(stage), [.secondary], "再开的第三路不出声")
        for lane in stage.lanes { stage.pushVolume(to: lane) }
        XCTAssertEqual(audible(stage), [.secondary], "播放项重建后重推音量也只有焦点出声")
        stage.setLaneCount(1)
        XCTAssertEqual(audible(stage), [.primary])
    }

    func testSessionFailureMutesAllThreeAndDoesNotWriteSettings() {
        let (stage, driver) = withAudio(muted: false)
        driver.failCategory = true
        stage.settings.muted = false
        stage.setFocus(.tertiary)
        XCTAssertTrue(stage.muted)
        XCTAssertFalse(stage.settings.muted, "不写偏好")
        XCTAssertEqual(audible(stage), [])
        XCTAssertEqual(stage.focusLane, .tertiary)
    }

    // MARK: M10 停留

    private func settled(_ name: String, raw: TimeInterval, focused: TimeInterval) -> DwellTracker.Settled {
        var tracker = DwellTracker()
        _ = tracker.switchTo(Room.direct(username: name), now: t0)
        tracker.setActive(true, now: t0)
        if focused < raw { tracker.setFocused(false, now: t0.addingTimeInterval(focused)) }
        return tracker.finish(now: t0.addingTimeInterval(raw))!
    }

    func testThreeLaneSettlementsKeepWeightsAndSources() {
        let stage = makeStage()
        stage.primary.dwellSource = .guess
        stage.secondary!.dwellSource = .popular
        stage.tertiary!.dwellSource = .favorites
        let cases: [(PlaybackLane, String, TimeInterval, TimeInterval, Double)] = [
            (stage.primary, "alice", 40, 40, 40),
            (stage.secondary!, "bob", 40, 0, 20),
            (stage.tertiary!, "carol", 40, 10, 25)
        ]
        for (lane, name, raw, focused, weighted) in cases {
            let result = stage.settlement(for: settled(name, raw: raw, focused: focused), lane: lane, reason: .swipe)
            XCTAssertEqual(result.lane, lane.id.role)
            XCTAssertEqual(result.rawSeconds, raw, accuracy: 0.001)
            XCTAssertEqual(result.focusedSeconds, focused, accuracy: 0.001)
            XCTAssertEqual(result.weightedSeconds, weighted, accuracy: 0.001, "非焦点 0.5 权重")
            XCTAssertEqual(result.source, lane.dwellSource, "每路带自己的来源")
        }
    }

    func testLayoutChangeShortStayIsNotAMissAndRemovedLaneSettlesOnce() {
        let stage = makeStage()
        let short = settled("carol", raw: 1, focused: 0)
        XCTAssertFalse(stage.settlement(for: short, lane: stage.tertiary!, reason: .layoutChange).learnsTaste)
        let tertiary = stage.tertiary!
        _ = tertiary.dwell.switchTo(Room.direct(username: "carol"), now: Date())
        stage.setLaneCount(2)
        XCTAssertNil(tertiary.dwell.room, "移除时结算一次")
        XCTAssertNil(tertiary.dwell.finish(now: Date()), "不会再结算第二次")
    }

    func testHotRelatedAndFavoriteSuggestFollowFocusOnly() {
        let stage = makeStage()
        stage.setFocus(.tertiary)
        XCTAssertNil(stage.primary.relatedTask, "非焦点路不排热门关联")
        XCTAssertNil(stage.secondary!.relatedTask)
        XCTAssertTrue(stage.focusPlaybackLane === stage.tertiary)
        XCTAssertEqual(stage.focusRoom?.username, "carol", "收藏提示看焦点路的房间")
    }

    // MARK: H3 解码资源不足

    private let shortage = [(domain: PlaybackFailureClassifier.avFoundationErrorDomain, code: PlaybackFailureClassifier.decoderTemporarilyUnavailableCode)]

    private func info(_ name: String) -> PlaybackFailureInfo {
        PlaybackFailureInfo(stage: "item-failed", username: name, chain: shortage, message: "")
    }

    func testResourceShortageDegradesNonFocusLaneAndProtectsFocus() {
        let stage = makeStage()
        stage.setFocus(.secondary)
        XCTAssertEqual(stage.classifyFailure(info("carol"), on: stage.tertiary!), .resourceDegrade)
        XCTAssertEqual(stage.classifyFailure(info("alice"), on: stage.primary), .resourceDegrade, "主路不是焦点时同样可以降级")
        XCTAssertEqual(stage.classifyFailure(info("bob"), on: stage.secondary!), .roomRetry, "焦点路受保护，不降级")
        stage.tertiary!.player.resourceDegraded?(info("carol"))
        XCTAssertEqual(stage.tertiary!.state, .degraded)
        XCTAssertEqual(stage.tertiary!.playbackNote, "资源不足")
        XCTAssertTrue(stage.tertiary!.degradedHold.held)
        XCTAssertFalse(stage.laneAllowed(stage.tertiary!))
        XCTAssertFalse(stage.tertiary!.state == .failed, "不计入房间失败")
    }

    func testFocusShortageReleasesTertiaryThenSecondaryForIt() {
        let stage = makeStage()
        XCTAssertEqual(stage.classifyFailure(info("alice"), on: stage.primary), .roomRetry)
        XCTAssertEqual(stage.tertiary!.state, .degraded, "焦点路缺解码器：先让出第三路")
        XCTAssertNotEqual(stage.secondary!.state, .degraded)
        _ = stage.classifyFailure(info("alice"), on: stage.primary)
        XCTAssertEqual(stage.secondary!.state, .degraded, "第三路已让出，再让副路")
    }

    func testDegradedLaneRetriesOnFocusOrLaneReduction() {
        let stage = makeStage()
        stage.tertiary!.player.resourceDegraded?(info("carol"))
        stage.setFocus(.tertiary)
        XCTAssertFalse(stage.tertiary!.degradedHold.held, "焦点切到该路时重试一次")
        XCTAssertNotNil(stage.tertiary!.playTask)

        let other = makeStage()
        other.secondary!.player.resourceDegraded?(info("bob"))
        other.setLaneCount(2)
        XCTAssertFalse(other.secondary!.degradedHold.held, "路数减少时重试")
        XCTAssertNotNil(other.secondary!.playTask)
    }

    func testDegradedLaneSchedulesOneShotRetry() {
        let stage = makeStage()
        stage.tertiary!.player.resourceDegraded?(info("carol"))
        XCTAssertNotNil(stage.tertiary!.degradedRetryTask, "60 秒一次性检查")
        stage.setFocus(.tertiary)
        XCTAssertNil(stage.tertiary!.degradedRetryTask, "已恢复就取消检查")
    }
    // MARK: H2 选档三层上限

    private func sizedTriple() -> StageModel {
        let stage = makeStage()
        stage.screenScale = 3
        for lane in stage.lanes { lane.displaySize = CGSize(width: 402, height: 226) }
        return stage
    }

    func testThreeLanesCapEachLaneByItsGridPicture() {
        let stage = sizedTriple()
        for lane in stage.lanes { XCTAssertEqual(stage.playbackCap(on: lane), 1_696_000, "226pt x 3 = 678px") }
        stage.tertiary!.displaySize = .zero
        stage.notePaneSize(CGSize(width: 402, height: 290), on: stage.tertiary!)
        XCTAssertEqual(stage.playbackCap(on: stage.tertiary!), 1_696_000, "还没出画面：按格子里 16:9 画面估算")
        stage.notePaneSize(.zero, on: stage.tertiary!)
        XCTAssertEqual(stage.playbackCap(on: stage.tertiary!), LaneQuality.minimumBitrate, "什么尺寸都没有：最低档")
    }

    func testLayoutChangesRetargetTheSizeCap() {
        let stage = sizedTriple()
        for lane in stage.lanes { lane.currentCap = 1_696_000 }
        stage.setLandscape(true)
        XCTAssertEqual(stage.retargetCap(for: stage.primary), .infinity, "横屏只显示一路：不按格子限档")
        stage.setLandscape(false)
        XCTAssertNil(stage.retargetCap(for: stage.primary))
        stage.setPipSolo(true)
        XCTAssertEqual(stage.retargetCap(for: stage.primary), .infinity, "画中画只留焦点路")
        stage.setPipSolo(false)
        stage.setLaneCount(2)
        XCTAssertEqual(stage.retargetCap(for: stage.secondary!), .infinity, "两路维持最高档")
        stage.setLaneCount(3)
        stage.tertiary!.displaySize = CGSize(width: 402, height: 226)
        XCTAssertEqual(stage.playbackCap(on: stage.tertiary!), 1_696_000)
    }

    func testThermalStepsDownFromTheSizeCapAndRecoversToIt() {
        let stage = sizedTriple()
        stage.noteThermal(.serious, now: t0)
        XCTAssertEqual(stage.playbackCap(on: stage.primary), 896_000, "1.696M 降一档")
        stage.noteThermal(.critical, now: t0.addingTimeInterval(5))
        XCTAssertEqual(stage.playbackCap(on: stage.secondary!), 896_000, "已是最低档保持最低档")
        stage.noteThermal(.fair, now: t0.addingTimeInterval(10))
        stage.noteThermal(.fair, now: t0.addingTimeInterval(70))
        XCTAssertEqual(stage.playbackCap(on: stage.primary), 1_696_000, "回升到尺寸上限，不是不限")
        XCTAssertNil(stage.thermalRecoveryTask)
    }
    /// 回归（三窗实跑发现）：起播带的尺寸或热上限是有限值，不能被当成「离开后降画质」的低档，
    /// 否则 8 秒后画质状态机会「升回 full」并无谓地重建播放项。
    func testFiniteLaneCapIsNotMistakenForTheRestingTier() {
        let stage = sizedTriple()
        let lane = stage.tertiary!
        let url = URL(string: "http://127.0.0.1:9/master.m3u8")!
        let cap = stage.playbackCap(on: lane)
        XCTAssertEqual(cap, 1_696_000)
        stage.startPlayback(
            room: stage.room(of: lane)!, target: StageModel.PlayTarget(master: url, playable: StreamRendition.unselected(url)),
            cap: cap, isRetry: false, lane: lane
        )
        XCTAssertEqual(lane.quality.tier, .full, "尺寸上限不是离开降画质")
        XCTAssertNil(lane.quality.delay(now: Date()), "不排定档换档")
    }
    /// 回归（iPad 三窗冷启动实跑发现）：列表刚到、格子还没布局时就起播，不能落到最低档再换档；
    /// 按竖屏实测的舞台尺寸推算格子（高按路数均分）估算画面高度。
    func testStageSizeEstimatesThePaneBeforeLayout() {
        let stage = makeStage()
        stage.screenScale = 3
        let lane = stage.tertiary!
        XCTAssertEqual(stage.playbackCap(on: lane), LaneQuality.minimumBitrate, "什么尺寸都没有：最低档")
        stage.notePortraitStageSize(CGSize(width: 402, height: 874))
        XCTAssertEqual(stage.estimatedDisplayHeight(lane), 226.125, accuracy: 0.01, "402 宽里 16:9 画面高 226 点，小于格高 290")
        XCTAssertEqual(stage.playbackCap(on: lane), 1_696_000)
        stage.notePaneSize(CGSize(width: 402, height: 150), on: lane)
        XCTAssertEqual(stage.estimatedDisplayHeight(lane), 150, accuracy: 0.01, "量到格子后以格子为准")
    }
    // MARK: 审计 high：serious 升到 critical 要再换一次档

    /// 完整序列 nominal → serious → critical → fair：每次上限变了都要请求换档（不只在「降档与否」翻转时），
    /// 回升到尺寸上限。两路（尺寸不限）与三格（iPad 每格画面 402 点 × 2 = 804 像素，尺寸上限 3.096M）都测。
    func testSeriousToCriticalRetiersAgainAndRecoversToSizeCap() {
        for (lanes, scale, height, expected) in [
            (2, 3.0, 226.0, [Double.infinity, 3_096_000, 1_696_000, .infinity]),
            (3, 2.0, 402.0, [3_096_000, 1_696_000, 896_000, 3_096_000])
        ] {
            let stage = makeStage(lanes: lanes)
            stage.screenScale = scale
            for lane in stage.lanes { lane.displaySize = CGSize(width: 715, height: height) }
            let lane = stage.primary
            func settle() { for each in stage.lanes { each.currentCap = stage.playbackCap(on: each) } }
            stage.noteThermal(.nominal, now: t0)
            settle()
            XCTAssertEqual(lane.currentCap, expected[0], "\(lanes) 路常温")
            for (step, state) in [(1, PhoneThermalState.serious), (2, .critical)] {
                let requests = lane.capSyncRequests
                stage.noteThermal(state, now: t0.addingTimeInterval(Double(step)))
                XCTAssertGreaterThan(lane.capSyncRequests, requests, "\(lanes) 路进入 \(state) 要请求换档")
                XCTAssertEqual(stage.retargetCap(for: lane), expected[step], "\(lanes) 路 \(state)")
                settle()
            }
            stage.noteThermal(.fair, now: t0.addingTimeInterval(10))
            XCTAssertNil(stage.retargetCap(for: lane), "回落不立刻升")
            let requests = lane.capSyncRequests
            stage.noteThermal(.fair, now: t0.addingTimeInterval(70))
            XCTAssertGreaterThan(lane.capSyncRequests, requests)
            XCTAssertEqual(stage.retargetCap(for: lane), expected[3], "\(lanes) 路回升到尺寸上限")
        }
    }
    // MARK: 审计 medium：资源持续不足时不无限「降级 → 重试」

    private func waitUntil(_ timeout: TimeInterval = 3, _ condition: () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > deadline { return false }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return true
    }

    /// 每次自动重试后资源仍不足（又降级）：按退避排 3 次后不再排；之后焦点切到该路仍重试一次；成功出画面后清零。
    func testPersistentShortageStopsAutomaticRetriesAfterBackoff() async {
        let stage = makeStage()
        stage.degradedRetryDelays = [0.02, 0.04, 0.08]
        let lane = stage.tertiary!
        var retries = 0
        lane.player.resourceDegraded?(info("carol"))
        while lane.degradedRetryTask != nil {
            let fired = await waitUntil { !lane.degradedHold.held }
            XCTAssertTrue(fired, "退避到点重试")
            retries += 1
            XCTAssertNotNil(lane.playTask, "第 \(retries) 次自动重试起播")
            lane.player.resourceDegraded?(info("carol"))
            XCTAssertLessThanOrEqual(retries, 3, "不得无限循环")
            if retries > 3 { break }
        }
        XCTAssertEqual(retries, 3, "60、120、240 三次后停止")
        XCTAssertEqual(lane.degradedRetries, 3)
        XCTAssertTrue(lane.degradedHold.held, "停在降级，等用户操作")
        XCTAssertNil(lane.degradedRetryTask, "不再排自动重试")
        stage.setFocus(.tertiary)
        XCTAssertFalse(lane.degradedHold.held, "焦点切到该路仍重试一次")
        stage.notePlaying("carol", on: lane)
        XCTAssertEqual(lane.degradedRetries, 0, "播起来后清零")
    }
    // MARK: 复审 medium：恢复后旧退避任务不能改写重试计数

    /// 用户重试、回前台、切房放开降级门时都要取消在途退避；之后播起来清零，过了原定时刻计数也不被改写。
    func testRecoveryCancelsThePendingBackoffSoTheBudgetIsNotConsumed() async {
        for path in ["retry", "resume", "roomSwitch"] {
            let stage = makeStage()
            stage.degradedRetryDelays = [0.05, 0.1, 0.2]
            let lane = stage.tertiary!
            lane.player.resourceDegraded?(info("carol"))
            XCTAssertNotNil(lane.degradedRetryTask)
            switch path {
            case "retry": stage.retryPlayback(stage.target(.tertiary))
            case "resume": stage.resumePlay(lane, activity: .idle)
            default: stage.step(1, on: lane)
            }
            XCTAssertNil(lane.degradedRetryTask, "\(path)：放开降级门时取消在途退避")
            stage.notePlaying(stage.name(of: lane), on: lane)
            XCTAssertEqual(lane.degradedRetries, 0)
            try? await Task.sleep(nanoseconds: 150_000_000)
            XCTAssertEqual(lane.degradedRetries, 0, "\(path)：原定时刻过了也不改写计数")
        }
    }

    /// 到点时这一路被横屏临时释放（不可见）：不消耗一次重试，也不放开门；回竖屏由恢复流程重试。
    func testBackoffFiringWhileHiddenDoesNotConsumeAnAttempt() async {
        let stage = makeStage()
        stage.degradedRetryDelays = [0.05, 0.1, 0.2]
        let lane = stage.tertiary!
        lane.player.resourceDegraded?(info("carol"))
        stage.setLandscape(true)
        try? await Task.sleep(nanoseconds: 150_000_000)
        XCTAssertEqual(lane.degradedRetries, 0, "不可见时到点不计数")
        XCTAssertTrue(lane.degradedHold.held, "不可见时不放开门")
        stage.setLandscape(false)
        XCTAssertFalse(lane.degradedHold.held, "回竖屏由恢复流程重试")
    }
}
