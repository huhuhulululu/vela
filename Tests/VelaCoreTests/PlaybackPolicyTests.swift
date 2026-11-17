import XCTest
@testable import VelaCore

final class PlaybackPolicyTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)
    let url = URL(string: "https://example.com/a.m3u8")!
    var rend: StreamRendition { StreamRendition(video: url, audio: URL(string: "https://example.com/a-audio.m3u8")) }

    // MARK: 门控与恢复（P1、P2）

    func testGateNeedsEveryCondition() {
        XCTAssertTrue(PlaybackPolicy.allowed(windowVisible: true, manuallyPaused: false, bossHidden: false, systemInactive: false))
        XCTAssertFalse(PlaybackPolicy.allowed(windowVisible: false, manuallyPaused: false, bossHidden: false, systemInactive: false))
        XCTAssertFalse(PlaybackPolicy.allowed(windowVisible: true, manuallyPaused: true, bossHidden: false, systemInactive: false))
        XCTAssertFalse(PlaybackPolicy.allowed(windowVisible: true, manuallyPaused: false, bossHidden: true, systemInactive: false))
        XCTAssertFalse(PlaybackPolicy.allowed(windowVisible: true, manuallyPaused: false, bossHidden: false, systemInactive: true))
    }

    func testResumeActionByPauseLength() {
        XCTAssertEqual(PlaybackPolicy.resumeAction(pausedFor: 1), .resume)
        XCTAssertEqual(PlaybackPolicy.resumeAction(pausedFor: 5), .resume)
        XCTAssertEqual(PlaybackPolicy.resumeAction(pausedFor: 14.9), .resume)
        XCTAssertEqual(PlaybackPolicy.resumeAction(pausedFor: PlaybackPolicy.liveRejoinAfter), .resume)
        XCTAssertEqual(PlaybackPolicy.resumeAction(pausedFor: PlaybackPolicy.liveRejoinAfter + 0.1), .rejoin)
        XCTAssertEqual(PlaybackPolicy.resumeAction(pausedFor: 22), .rejoin)
        XCTAssertEqual(PlaybackPolicy.resumeAction(pausedFor: PlaybackPolicy.reresolveAfter - 1), .rejoin)
        XCTAssertEqual(PlaybackPolicy.resumeAction(pausedFor: PlaybackPolicy.reresolveAfter), .reresolve)
        XCTAssertEqual(PlaybackPolicy.resumeAction(pausedFor: 3600), .reresolve)
        XCTAssertLessThan(PlaybackPolicy.reresolveAfter, 32, "必须短于实测的最低闲置寿命")
    }

    /// 停播释放与侧车空闲销毁的边界：29.9 秒仍保留，30 秒与 30.1 秒放掉。
    func testIdleReleaseBoundaries() {
        XCTAssertEqual(PlaybackPolicy.releasePausedItemAfter, 30)
        XCTAssertEqual(PlaybackPolicy.releasePausedItemAfter, PlaybackPolicy.reresolveAfter)
        XCTAssertFalse(PlaybackPolicy.shouldReleasePausedItem(pausedFor: 29.9, hiddenFor: 29.9))
        XCTAssertTrue(PlaybackPolicy.shouldReleasePausedItem(pausedFor: 30, hiddenFor: 30))
        XCTAssertTrue(PlaybackPolicy.shouldReleasePausedItem(pausedFor: 30.1, hiddenFor: 30.1))
        XCTAssertEqual(PlaybackPolicy.resumeAction(pausedFor: 29.9), .rejoin, "未满 30 秒仍要重建，播放项还在")
        XCTAssertEqual(PlaybackPolicy.resumeAction(pausedFor: 30), .reresolve)
        XCTAssertEqual(PlaybackPolicy.resumeAction(pausedFor: 30.1), .reresolve)

        XCTAssertEqual(SidecarIdlePolicy.seconds, 30)
        XCTAssertFalse(SidecarIdlePolicy.shouldTearDown(idleFor: 29.9))
        XCTAssertTrue(SidecarIdlePolicy.shouldTearDown(idleFor: 30))
        XCTAssertTrue(SidecarIdlePolicy.shouldTearDown(idleFor: 30.1))
        XCTAssertLessThan(SidecarIdlePolicy.seconds, 120, "短于最短的侧车冷却，超时后按需重建")
    }

    /// 窗口可见时手动暂停不释放（保留最后一帧）；不可见才计时，且从变为不可见起算。
    func testReleaseOnlyWhenWindowInvisible() {
        XCTAssertFalse(PlaybackPolicy.shouldReleasePausedItem(pausedFor: 31, hiddenFor: nil), "可见的手动暂停 31 秒不释放")
        XCTAssertFalse(PlaybackPolicy.shouldReleasePausedItem(pausedFor: 3600, hiddenFor: nil))
        XCTAssertFalse(PlaybackPolicy.shouldReleasePausedItem(pausedFor: 60, hiddenFor: 29.9), "暂停后才变不可见，从不可见起再计 30 秒")
        XCTAssertTrue(PlaybackPolicy.shouldReleasePausedItem(pausedFor: 60, hiddenFor: 30))
        XCTAssertTrue(PlaybackPolicy.shouldReleasePausedItem(pausedFor: 60, hiddenFor: 30.1))
        XCTAssertFalse(PlaybackPolicy.shouldReleasePausedItem(pausedFor: 29.9, hiddenFor: 60), "不可见很久但停播未满 30 秒")
    }

    func testWindowInvisibleReasons() {
        XCTAssertFalse(PlaybackPolicy.windowInvisible(windowVisible: true, bossHidden: false, systemInactive: false, appHidden: false))
        XCTAssertTrue(PlaybackPolicy.windowInvisible(windowVisible: false, bossHidden: false, systemInactive: false, appHidden: false))
        XCTAssertTrue(PlaybackPolicy.windowInvisible(windowVisible: true, bossHidden: true, systemInactive: false, appHidden: false))
        XCTAssertTrue(PlaybackPolicy.windowInvisible(windowVisible: true, bossHidden: false, systemInactive: true, appHidden: false))
        XCTAssertTrue(PlaybackPolicy.windowInvisible(windowVisible: true, bossHidden: false, systemInactive: false, appHidden: true))
    }

    func testAudioCheckRechecksBeforeFailing() {
        XCTAssertEqual(PlaybackPolicy.audioCheckStep(enabledTracks: 1, isRecheck: false), .ok)
        XCTAssertEqual(PlaybackPolicy.audioCheckStep(enabledTracks: 0, isRecheck: false), .recheck)
        XCTAssertEqual(PlaybackPolicy.audioCheckStep(enabledTracks: 1, isRecheck: true), .ok)
        XCTAssertEqual(PlaybackPolicy.audioCheckStep(enabledTracks: 0, isRecheck: true), .fail)
    }

    // MARK: 起播看门狗（P3）

    func testWatchdogOnlyFiresWhenShouldBePlayingAndItemNeverPlayed() {
        XCTAssertTrue(PlaybackPolicy.startupStalled(sameItem: true, allowed: true, itemPlayed: false, isPlaying: false))
        // 暂停、老板键、休眠：不该播，不报。
        XCTAssertFalse(PlaybackPolicy.startupStalled(sameItem: true, allowed: false, itemPlayed: false, isPlaying: false))
        // 已经换了播放项，或这个项已开过播 / 正在播：不报。
        XCTAssertFalse(PlaybackPolicy.startupStalled(sameItem: false, allowed: true, itemPlayed: false, isPlaying: false))
        XCTAssertFalse(PlaybackPolicy.startupStalled(sameItem: true, allowed: true, itemPlayed: true, isPlaying: false))
        XCTAssertFalse(PlaybackPolicy.startupStalled(sameItem: true, allowed: true, itemPlayed: false, isPlaying: true))
    }

    // MARK: 预取缓存过期（P4）

    func testPreparedAddressExpires() {
        var cache = PreparedCache()
        cache.set(rend, cap: 1, for: "a", now: t0)
        XCTAssertEqual(PreparedCache.maxAge, 20)
        XCTAssertLessThan(PreparedCache.maxAge, 32, "必须短于实测的最低闲置寿命")
        XCTAssertEqual(cache.fresh("a", cap: 1, now: t0.addingTimeInterval(PreparedCache.maxAge - 1)), rend)
        XCTAssertNil(cache.fresh("a", cap: 1, now: t0.addingTimeInterval(PreparedCache.maxAge)))
        XCTAssertNil(cache.fresh("b", cap: 1, now: t0))
        // 档位上限不同的条目不复用。
        XCTAssertNil(cache.fresh("a", cap: .infinity, now: t0))
    }

    func testPreparedRefreshAndRemove() {
        var cache = PreparedCache()
        cache.set(rend, cap: 1, for: "a", now: t0)
        let newer = URL(string: "https://example.com/b.m3u8")!
        cache.set(StreamRendition(video: newer), cap: 1, for: "a", now: t0.addingTimeInterval(15))
        XCTAssertEqual(cache.fresh("a", cap: 1, now: t0.addingTimeInterval(30))?.video, newer)
        cache.remove("a")
        XCTAssertNil(cache.fresh("a", cap: 1, now: t0.addingTimeInterval(30)))
    }

    func testPreparedSetDropsExpiredEntries() {
        var cache = PreparedCache()
        cache.set(rend, cap: 1, for: "old", now: t0)
        cache.set(rend, cap: 1, for: "new", now: t0.addingTimeInterval(PreparedCache.maxAge + 1))
        XCTAssertNil(cache.fresh("old", cap: 1, now: t0.addingTimeInterval(1)))
    }

    // MARK: 已开播后的卡住看门狗

    func testStuckWatchdogDecision() {
        func act(same: Bool = true, allowed: Bool = true, played: Bool = true, playing: Bool = false,
                 refreshing: Bool = false, budget: Bool = true) -> PlaybackPolicy.StuckAction {
            PlaybackPolicy.stuckAction(sameItem: same, allowed: allowed, itemPlayed: played, isPlaying: playing,
                                       refreshing: refreshing, retryAvailable: budget)
        }
        XCTAssertEqual(act(), .recover)
        // 暂停 / 老板键 / 休眠：不恢复。
        XCTAssertEqual(act(allowed: false), .ignore)
        XCTAssertEqual(act(same: false), .ignore)
        XCTAssertEqual(act(played: false), .ignore)
        XCTAssertEqual(act(playing: true), .ignore)
        XCTAssertEqual(act(refreshing: true), .ignore)
        // 预算用完：不恢复，交给界面，不循环。
        XCTAssertEqual(act(budget: false), .giveUp)
        XCTAssertGreaterThan(PlaybackPolicy.stuckAfter, 6)
        XCTAssertLessThan(PlaybackPolicy.stuckAfter, PlaybackPolicy.startupTimeout)
    }
}

final class RelayRefreshPolicyTests: XCTestCase {
    func testRelayStopsRefreshingOnTerminalFailureAndWhilePaused() {
        XCTAssertTrue(PlaybackPolicy.relayShouldRefresh(allowed: true, failed: false))
        XCTAssertFalse(PlaybackPolicy.relayShouldRefresh(allowed: true, failed: true), "终态失败不再后台刷新")
        XCTAssertFalse(PlaybackPolicy.relayShouldRefresh(allowed: false, failed: false))
        XCTAssertFalse(PlaybackPolicy.relayShouldRefresh(allowed: false, failed: true))
    }
}
