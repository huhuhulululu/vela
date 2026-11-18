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

    private func makeStage(secondary: String? = nil, http: URLSessionHTTPClient = URLSessionHTTPClient()) -> StageModel {
        let name = "vela-phone-tests-\(UUID().uuidString)"
        suites.append(name)
        let stage = StageModel(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!), http: http)
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

    func testDegradedLaneIsReleasedBeforeReplayOnReturn() {
        let stage = makeStage(secondary: "bob")
        stage.secondary?.degradedHold.degrade()
        XCTAssertFalse(stage.laneAllowed(stage.secondary!), "降级扣住时该路闸门关闭")

        _ = background(stage)
        let actions = foreground(stage)
        XCTAssertTrue(actions.contains { if case .requestPlay(.secondary, _) = $0 { return true }; return false }, "\(actions)")
        XCTAssertFalse(stage.secondary!.degradedHold.held, "回前台恢复清除降级扣住")
        XCTAssertNotNil(stage.secondary?.playTask)
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
    // MARK: 审计修复：预取、侧车、旧恢复入口、停留计时

    /// 站点请求一律记下来、不出网：`hang` 为真时挂着不回，便于观察取消；否则回 404。
    private final class StubProtocol: URLProtocol {
        final class Log: @unchecked Sendable {
            private let lock = NSLock()
            private var startedURLs: [String] = []
            private var stoppedURLs: [String] = []
            var hang = false
            func start(_ url: String) { lock.lock(); startedURLs.append(url); lock.unlock() }
            func stop(_ url: String) { lock.lock(); stoppedURLs.append(url); lock.unlock() }
            var started: [String] { lock.lock(); defer { lock.unlock() }; return startedURLs }
            var stopped: [String] { lock.lock(); defer { lock.unlock() }; return stoppedURLs }
            func reset() { lock.lock(); startedURLs = []; stoppedURLs = []; hang = false; lock.unlock() }
        }
        static let log = Log()

        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

        override func startLoading() {
            let url = request.url?.absoluteString ?? ""
            Self.log.start(url)
            guard !Self.log.hang, let target = request.url else { return }
            let response = HTTPURLResponse(url: target, statusCode: 404, httpVersion: nil, headerFields: nil)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data())
            client?.urlProtocolDidFinishLoading(self)
        }

        override func stopLoading() {
            Self.log.stop(request.url?.absoluteString ?? "")
        }
    }

    private func stubbedHTTP(hang: Bool) -> URLSessionHTTPClient {
        StubProtocol.log.reset()
        StubProtocol.log.hang = hang
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubProtocol.self]
        return URLSessionHTTPClient(session: URLSession(configuration: configuration))
    }

    private func waitUntil(_ timeout: TimeInterval = 2, _ condition: () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
        return condition()
    }

    /// 主路起播一次（真的 `lastPlay`）：本地中继起监听，播放地址指向不会应答的本机端口。
    private func startRealPlayback(_ stage: StageModel) async throws -> Bool {
        PlaylistRelay.shared.allowStart = true
        let listening = await PlaylistRelay.shared.waitUntilListening(timeout: 5)
        XCTAssertTrue(listening, "本地中继没能监听")
        guard listening else { return false }
        let room = try XCTUnwrap(Room.direct(username: "alice"))
        let rendition = StreamRendition(video: try XCTUnwrap(URL(string: "http://127.0.0.1:9/lifecycle-probe.m3u8")))
        stage.startPlayback(room: room, target: .init(master: nil, playable: rendition), cap: .infinity, isRetry: false, lane: stage.primary)
        XCTAssertTrue(stage.primary.player.hasItem)
        return true
    }

    /// 预取登记在该路任务里：进后台时连同在途请求一起取消，后台不再发站点请求。
    func testBackgroundCancelsInflightPrefetch() async throws {
        let stage = makeStage(http: stubbedHTTP(hang: true))
        stage.updateFeed { $0.replace(with: CatalogPage(rooms: [Room.direct(username: "alice")!, Room.direct(username: "bobprefetch")!], total: 2, nextOffset: 2)) }
        stage.updateFeed { $0.select("alice") }
        defer { stage.primary.player.stop() }
        guard try await startRealPlayback(stage) else { return }
        let sent = await waitUntil { StubProtocol.log.started.contains { $0.contains("bobprefetch") } }
        XCTAssertTrue(sent, "前台照常预取下一间：\(StubProtocol.log.started)")

        _ = background(stage)

        let cancelled = await waitUntil { StubProtocol.log.stopped.contains { $0.contains("bobprefetch") } }
        XCTAssertTrue(cancelled, "进后台取消在途预取请求")
        XCTAssertNil(stage.primary.prefetchTask, "挂起时预取任务已撤")
    }

    /// 画中画只放行那一路的起播与续期；预取属于共享工作，画中画期间不预取。
    func testPictureInPictureDoesNotPrefetch() async {
        let stage = makeStage(http: stubbedHTTP(hang: false))
        stage.updateFeed { $0.replace(with: CatalogPage(rooms: [Room.direct(username: "alice")!, Room.direct(username: "bobprefetch")!], total: 2, nextOffset: 2)) }
        stage.updateFeed { $0.select("alice") }
        stage.platform.run([
            .suspendSharedTasks, .allowLaneRequests(.primary), .settleDwell(.background), .flushSavers,
            .continueLane(.primary), .retainRelaySource(.primary), .noteResumeIntent(nil)
        ], on: stage)
        XCTAssertNotNil(stage.laneToken(stage.primary), "画中画路本身放行")

        await stage.prefetchAhead(on: stage.primary)
        stage.startPrefetch(on: stage.primary)
        try? await Task.sleep(nanoseconds: 200_000_000)

        XCTAssertTrue(StubProtocol.log.started.isEmpty, "画中画期间不得为下一房间发请求：\(StubProtocol.log.started)")
        XCTAssertNil(stage.primary.prefetchTask)
    }

    /// 侧车在等 `beforeLoad` 时被挂起：之后不得再建网页视图、不得加载页面。
    func testSidecarSuspendedDuringBeforeLoadDoesNotCreateWebView() async {
        var release: CheckedContinuation<Void, Never>?
        let sidecar = SiteSidecar(beforeLoad: { await withCheckedContinuation { release = $0 } })
        let call = Task { @MainActor in try await sidecar.get(URL(string: "https://example.invalid/")!) }
        let waiting = await waitUntil { release != nil }
        XCTAssertTrue(waiting)

        sidecar.suspendAll()
        release?.resume()
        let result = await call.result

        XCTAssertFalse(sidecar.hasWebView, "挂起后不建网页视图")
        if case .failure(let error) = result {
            XCTAssertTrue(error is CancellationError, "\(error)")
        } else {
            XCTFail("挂起后侧车调用应以取消结束")
        }
        sidecar.suspendAll()
    }

    /// 失败态回前台：保留失败，播放器也不能按自己记下的 `lastPlay` 重新解析起播（§3.2 只由 Stage 层决定）。
    func testFailedLaneWithRealLastPlayIsNotRestartedByPlayerOnReturn() async throws {
        let stage = makeStage()
        defer { stage.primary.player.stop() }
        guard try await startRealPlayback(stage) else { return }
        stage.primary.playTask = nil
        stage.primary.state = .failed

        _ = background(stage)
        let actions = foreground(stage)

        XCTAssertTrue(actions.contains(.keepFailure(.primary)), "\(actions)")
        XCTAssertFalse(stage.primary.player.refreshPending, "播放器没有自己请求重新解析")
        try? await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertNil(stage.primary.playTask, "失败态不自动重播")
        XCTAssertEqual(stage.primary.state, .failed)
    }

    /// 手动暂停回前台保持暂停；之后取消暂停由 Stage 层重新起播，不走播放器的旧恢复入口。
    func testManuallyPausedLaneResumesThroughStageAfterReturn() async throws {
        let stage = makeStage()
        defer { stage.primary.player.stop() }
        guard try await startRealPlayback(stage) else { return }
        stage.primary.playTask = nil
        stage.primary.manuallyPaused = true

        _ = background(stage)
        _ = foreground(stage)
        XCTAssertNil(stage.primary.playTask)

        stage.togglePause(nil)
        XCTAssertFalse(stage.primary.player.refreshPending, "不经播放器的 lastPlay 重新解析")
        XCTAssertNotNil(stage.primary.playTask, "由 Stage 层重新起播")
    }
}
