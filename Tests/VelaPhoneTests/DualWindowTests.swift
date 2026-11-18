import XCTest
@testable import VelaPhone

/// 双窗接入（T18，S8、S9、S12 的开关项）。年龄未确认：起播与目录不发请求，起播是否被请求看 `playTask`。
@MainActor
final class DualWindowTests: XCTestCase {
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

    private func defaults() -> UserDefaults {
        let name = "vela-phone-dual-\(UUID().uuidString)"
        suites.append(name)
        return UserDefaults(suiteName: name)!
    }

    private func room(_ name: String) -> Room { Room.direct(username: name)! }

    /// 列表装入后主路停在第一间。
    private func makeStage(_ names: [String] = ["alice", "bob", "carol", "dave"], defaults: UserDefaults? = nil) -> StageModel {
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: defaults ?? self.defaults()))
        install(stage, names)
        XCTAssertFalse(stage.ageConfirmed)
        return stage
    }

    private func install(_ stage: StageModel, _ names: [String], source: FeedSource? = nil) {
        stage.updateFeed(installing: source, intent: .reload) {
            $0.replace(with: CatalogPage(rooms: names.map(room), total: names.count, nextOffset: names.count))
        }
    }

    private func dual(_ names: [String] = ["alice", "bob", "carol", "dave"]) -> StageModel {
        let stage = makeStage(names)
        stage.setDual(true)
        return stage
    }

    private func names(_ stage: StageModel) -> (String?, String?) {
        (stage.name(of: stage.primary), stage.secondary.flatMap { stage.name(of: $0) })
    }

    private func assertDistinct(_ stage: StageModel, _ message: String = "", file: StaticString = #filePath, line: UInt = #line) {
        let (primary, secondary) = names(stage)
        if let primary, let secondary {
            XCTAssertNotEqual(primary.lowercased(), secondary.lowercased(), "两路房间重复 \(message)", file: file, line: line)
        }
    }

    // MARK: 设置与开关

    func testPhoneDualDefaultsOffAndIsRemembered() {
        let store = defaults()
        XCTAssertFalse(SettingsStore(defaults: store).phoneDual, "裁决 1：竖屏默认单窗")
        let first = SettingsStore(defaults: store)
        first.phoneDual = true
        XCTAssertTrue(SettingsStore(defaults: store).phoneDual)
    }

    func testRememberedDualOpensSecondaryAtLaunch() {
        let store = defaults()
        SettingsStore(defaults: store).phoneDual = true
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: store))
        XCTAssertTrue(stage.dualWindow)
        XCTAssertNotNil(stage.secondary)
        install(stage, ["alice", "bob", "carol"])
        XCTAssertEqual(names(stage).0, "alice")
        XCTAssertEqual(names(stage).1, "bob", "列表到达后副路取主路之后的第一间")
    }

    func testSetDualOnCreatesSecondaryAfterPrimary() {
        let stage = makeStage()
        XCTAssertEqual(stage.lanes.count, 1)
        XCTAssertFalse(stage.dualWindow)
        stage.setDual(true)
        XCTAssertEqual(stage.lanes.count, 2)
        XCTAssertTrue(stage.dualWindow)
        XCTAssertEqual(names(stage).0, "alice")
        XCTAssertEqual(names(stage).1, "bob")
        XCTAssertEqual(stage.focusLane, .primary)
        XCTAssertTrue(stage.settings.phoneDual, "开关被记住")
        XCTAssertNotNil(stage.secondary?.playTask, "副路请求了起播")
    }

    func testSetDualIsIdempotent() {
        let stage = dual()
        let lane = stage.secondary
        stage.setDual(true)
        XCTAssertTrue(stage.secondary === lane, "同值不重建副路")
    }

    func testSetDualOffReleasesSecondary() {
        let stage = dual()
        let lane = stage.secondary!
        stage.setDual(false)
        XCTAssertNil(stage.secondary)
        XCTAssertFalse(stage.dualWindow)
        XCTAssertEqual(stage.lanes.count, 1)
        XCTAssertFalse(lane.player.hasItem)
        XCTAssertNil(lane.playTask)
        XCTAssertFalse(stage.settings.phoneDual)
        XCTAssertEqual(stage.focusLane, .primary)
    }

    func testSetDualOffWithSecondaryFocusKeepsFocusedRoomOnPrimary() {
        let stage = dual()
        stage.setFocus(.secondary)
        XCTAssertEqual(stage.focusLane, .secondary)
        stage.setDual(false)
        XCTAssertEqual(stage.name(of: stage.primary), "bob", "焦点路的房间留下")
        XCTAssertEqual(stage.focusLane, .primary)
        XCTAssertNotNil(stage.primary.playTask)
    }

    func testSingleRoomListLeavesSecondaryEmpty() {
        let stage = dual(["alice"])
        XCTAssertEqual(names(stage).0, "alice")
        XCTAssertNil(names(stage).1)
    }

    func testPhonePlatformSupportsDual() {
        XCTAssertTrue(makeStage().platform.supportsDual)
    }

    // MARK: S8：两路房间永不重复

    func testBlockPrimaryKeepsSecondaryAndStaysDistinct() {
        let stage = dual()
        stage.blockCurrent(stage.target(.primary))
        XCTAssertEqual(names(stage).1, "bob")
        XCTAssertEqual(names(stage).0, "carol")
        assertDistinct(stage)
    }

    func testBlockSecondaryFindsNextRoom() {
        let stage = dual()
        stage.blockCurrent(stage.target(.secondary))
        XCTAssertEqual(names(stage).0, "alice")
        XCTAssertEqual(names(stage).1, "carol")
        assertDistinct(stage)
    }

    func testUnfavoriteSecondaryRoomInFavoritesSource() {
        let stage = makeStage()
        stage.source = .favorites
        stage.commit(Curation().favoriting("alice").favoriting("bob").favoriting("carol"), nil)
        install(stage, ["alice", "bob", "carol"], source: .favorites)
        stage.setDual(true)
        XCTAssertEqual(names(stage).1, "bob")
        stage.removeFavorite("bob")
        XCTAssertEqual(names(stage).0, "alice")
        XCTAssertEqual(names(stage).1, "carol")
        assertDistinct(stage)
    }

    func testUnfavoritePrimaryRoomInFavoritesSource() {
        let stage = makeStage()
        stage.source = .favorites
        stage.commit(Curation().favoriting("alice").favoriting("bob").favoriting("carol"), nil)
        install(stage, ["alice", "bob", "carol"], source: .favorites)
        stage.setDual(true)
        stage.removeFavorite("alice")
        assertDistinct(stage)
        XCTAssertEqual(names(stage).1, "bob")
        XCTAssertEqual(names(stage).0, "carol")
    }

    func testLocalSearchNarrowingStaysDistinct() {
        let stage = dual(["alice", "alex", "bob", "alina"])
        XCTAssertEqual(names(stage).1, "alex")
        stage.searchText = "al"
        stage.applySearch()
        assertDistinct(stage, "搜索 al")
        XCTAssertNotNil(names(stage).0)
        XCTAssertNotNil(names(stage).1)
        stage.searchText = "bob"
        stage.applySearch()
        XCTAssertEqual(names(stage).0, "bob")
        XCTAssertNil(names(stage).1, "只剩一间时焦点路保留它，另一路空着")
        stage.searchText = ""
        stage.applySearch()
        assertDistinct(stage, "清空搜索")
        XCTAssertNotNil(names(stage).1)
    }

    func testSiteSearchInstallAndExitStayDistinct() {
        let stage = dual()
        stage.updateFeed(intent: .searchInstall) {
            $0.replace(with: CatalogPage(rooms: [self.room("zed"), self.room("yan"), self.room("xi")], total: 3, nextOffset: 3))
        }
        XCTAssertEqual(names(stage).0, "zed")
        XCTAssertEqual(names(stage).1, "yan")
        stage.updateFeed(intent: .searchExit) {
            $0.replace(with: CatalogPage(rooms: ["alice", "bob"].map(self.room), total: 2, nextOffset: 2))
        }
        assertDistinct(stage)
        XCTAssertNotNil(names(stage).1)
    }

    func testSwitchingSourceStaysDistinct() {
        let stage = dual()
        install(stage, ["m1", "m2", "m3"], source: .popular)
        XCTAssertEqual(names(stage).0, "m1")
        XCTAssertEqual(names(stage).1, "m2")
        assertDistinct(stage)
    }

    func testEmptyListThenPageResumeReselectsBothLanes() {
        let stage = dual()
        install(stage, [])
        XCTAssertNil(names(stage).0)
        XCTAssertNil(names(stage).1)
        stage.updateFeed(intent: .merge) {
            _ = $0.mergeAfterCurrent([self.room("p1"), self.room("p2"), self.room("p3")]) { $0 }
        }
        XCTAssertNotNil(names(stage).0)
        XCTAssertNotNil(names(stage).1)
        assertDistinct(stage)
    }

    func testRevealTargetPlayingOnOtherLaneOnlySwitchesFocus() {
        let stage = dual()
        stage.updateFeed(intent: .reveal("bob")) { _ = $0.reveal(self.room("bob")) }
        XCTAssertEqual(stage.focusLane, .secondary)
        XCTAssertEqual(names(stage).0, "alice")
        XCTAssertEqual(names(stage).1, "bob")
    }

    func testRevealOtherRoomMovesPrimaryAndFocusesIt() {
        let stage = dual()
        stage.setFocus(.secondary)
        stage.updateFeed(intent: .reveal("dave")) { _ = $0.reveal(self.room("dave")) }
        XCTAssertEqual(stage.focusLane, .primary)
        XCTAssertEqual(names(stage).0, "dave")
        assertDistinct(stage)
    }

    func testSwipeSecondarySkipsPrimaryRoom() {
        let stage = makeStage(["alice", "bob", "carol"])
        stage.setDual(true)
        // 副路在 bob，向上一间是 alice（主路在播），要跳过去落到后面的 carol 再回头不可；向前先是 carol。
        stage.step(1, on: stage.secondary!)
        XCTAssertEqual(names(stage).1, "carol")
        stage.step(-1, on: stage.secondary!)
        XCTAssertEqual(names(stage).1, "bob")
        stage.step(-1, on: stage.secondary!)
        XCTAssertEqual(names(stage).1, "bob", "上一间是主路的房间，不能落上去")
        assertDistinct(stage)
    }

    // MARK: 共享「最近划走」：一路划走的房间，另一路向前划时不再落上去

    private let five = ["alice", "bob", "carol", "dave", "erin"]

    func testPrimaryForwardSkipsRoomsTheSecondaryJustLeft() {
        let stage = makeStage(five)
        stage.setDual(true)
        stage.step(1, on: stage.secondary!)
        stage.step(1, on: stage.secondary!)
        XCTAssertEqual(names(stage).1, "dave", "副路 bob→carol→dave，离开过 bob、carol")
        stage.step(1, on: stage.primary)
        XCTAssertEqual(names(stage).0, "erin", "bob、carol 刚被副路划走，dave 在副路，主路落 erin")
        assertDistinct(stage)
    }

    func testSecondaryForwardSkipsRoomsThePrimaryJustLeft() {
        let stage = makeStage(five)
        stage.setDual(true)
        stage.step(1, on: stage.primary)
        stage.step(1, on: stage.primary)
        XCTAssertEqual(names(stage).0, "dave", "主路 alice→carol→dave，离开过 alice、carol")
        stage.step(1, on: stage.secondary!)
        XCTAssertEqual(names(stage).1, "erin", "carol 刚被主路划走，dave 在主路，副路落 erin")
        assertDistinct(stage)
    }

    func testBackSwipeToOwnPreviousRoomIsAllowed() {
        let stage = makeStage(five)
        stage.setDual(true)
        stage.step(1, on: stage.primary)
        XCTAssertEqual(names(stage).0, "carol")
        stage.step(-1, on: stage.primary)
        XCTAssertEqual(names(stage).0, "alice", "alice 是主路自己的上一间，划回允许")
        assertDistinct(stage)
    }

    func testRecentSkipExpiresAndDirectRevealIgnoresIt() {
        let stage = makeStage(five)
        stage.setDual(true)
        stage.recentSkips.record("carol", at: Date().addingTimeInterval(-31 * 60))
        stage.recentSkips.record("dave", at: Date())
        stage.step(1, on: stage.primary)
        XCTAssertEqual(names(stage).0, "carol", "carol 的记录已过 30 分钟")
        stage.updateFeed(intent: .reveal("dave")) { $0.select("dave") }
        XCTAssertEqual(names(stage).0, "dave", "直达不受最近划走限制")
        assertDistinct(stage)
    }

    func testSingleWindowSwipesAreNotRecorded() {
        let stage = makeStage(five)
        stage.step(1)
        stage.step(1)
        XCTAssertEqual(stage.recentSkips.count, 0)
        XCTAssertEqual(names(stage).0, "carol")
    }

    func testRecentSkipsSurviveListReload() {
        let stage = makeStage(five)
        stage.setDual(true)
        stage.step(1, on: stage.secondary!)
        install(stage, five)
        XCTAssertEqual(stage.recentSkips.count, 1, "重载不清记录")
    }

    // MARK: S9：副路有焦点时，操作只作用于副路

    func testFavoriteOnSecondaryTargetsSecondaryRoomOnly() {
        let stage = dual()
        stage.setFocus(.secondary)
        stage.toggleFavorite(stage.target(.secondary))
        XCTAssertTrue(stage.curation.isFavorite("bob"))
        XCTAssertFalse(stage.curation.isFavorite("alice"))
        XCTAssertEqual(names(stage).0, "alice")
    }

    func testBlockOnSecondaryLeavesPrimaryUntouched() {
        let stage = dual()
        let before = stage.primary.playGeneration
        stage.setFocus(.secondary)
        stage.blockCurrent(stage.target(.secondary))
        XCTAssertTrue(stage.curation.isBlocked("bob"))
        XCTAssertFalse(stage.curation.isBlocked("alice"))
        XCTAssertEqual(names(stage).0, "alice")
        XCTAssertEqual(stage.primary.playGeneration, before, "主路没被重新起播")
    }

    func testNotInterestedOnSecondaryMovesOnlySecondary() {
        let stage = dual()
        stage.setFocus(.secondary)
        stage.markNotInterested(stage.target(.secondary))
        XCTAssertEqual(names(stage).0, "alice")
        XCTAssertEqual(names(stage).1, "carol")
        XCTAssertFalse(stage.secondary!.manuallyPaused)
    }

    func testPauseOnSecondaryDoesNotPausePrimary() {
        let stage = dual()
        stage.setFocus(.secondary)
        stage.togglePause(stage.target(.secondary))
        XCTAssertTrue(stage.secondary!.manuallyPaused)
        XCTAssertFalse(stage.primary.manuallyPaused)
        XCTAssertFalse(stage.laneAllowed(stage.secondary!))
        XCTAssertTrue(stage.laneAllowed(stage.primary))
    }

    func testRetryOnSecondaryRestartsOnlySecondary() {
        let stage = dual()
        stage.setFocus(.secondary)
        stage.primary.playTask = nil
        stage.secondary!.playTask = nil
        stage.retryPlayback(stage.target(.secondary))
        XCTAssertNotNil(stage.secondary!.playTask)
        XCTAssertNil(stage.primary.playTask)
    }

    func testStaleTargetIsIgnoredAfterRoomChange() {
        let stage = dual()
        let target = stage.target(.secondary)
        stage.step(1, on: stage.secondary!)
        stage.toggleFavorite(target)
        XCTAssertFalse(stage.curation.isFavorite("bob"), "点下去之后这一路换了房间：放弃")
        XCTAssertFalse(stage.curation.isFavorite("carol"))
    }

    // MARK: 焦点

    func testSetFocusUpdatesFocusAndDwellFlags() {
        let stage = dual()
        XCTAssertEqual(stage.focusLane, .primary)
        stage.setFocus(.secondary)
        XCTAssertEqual(stage.focusLane, .secondary)
        XCTAssertTrue(stage.focusPlaybackLane === stage.secondary)
        stage.setFocus(.primary)
        XCTAssertEqual(stage.focusLane, .primary)
    }

    func testSetFocusInSingleWindowIsIgnored() {
        let stage = makeStage()
        stage.setFocus(.secondary)
        XCTAssertEqual(stage.focusLane, .primary)
    }

    func testSwipingNonFocusPaneTakesFocusViaMove() {
        let stage = dual()
        stage.setFocus(.secondary)
        stage.setFocus(.primary)
        stage.step(1, on: stage.primary)
        XCTAssertEqual(names(stage).0, "carol")
        assertDistinct(stage)
    }

    func testIndexOfLaneFollowsItsRoom() {
        let stage = dual()
        XCTAssertEqual(stage.index(of: stage.primary), 0)
        XCTAssertEqual(stage.index(of: stage.secondary!), 1)
    }

    // MARK: 横屏

    func testLandscapeReleasesNonFocusLane() {
        let stage = dual()
        let hidden = stage.secondary!
        stage.setLandscape(true)
        XCTAssertTrue(stage.landscape)
        XCTAssertFalse(hidden.visible)
        XCTAssertFalse(hidden.player.hasItem)
        XCTAssertNil(hidden.playTask)
        XCTAssertFalse(stage.laneAllowed(hidden))
        XCTAssertTrue(stage.dualWindow, "横屏不改变双窗开关")
        XCTAssertEqual(names(stage).1, "bob", "房间保留，回竖屏恢复")
    }

    func testLandscapeReleasesPrimaryWhenSecondaryHasFocus() {
        let stage = dual()
        stage.setFocus(.secondary)
        stage.setLandscape(true)
        XCTAssertFalse(stage.primary.visible)
        XCTAssertTrue(stage.secondary!.visible)
    }

    func testBackToPortraitRequestsPlayForReleasedLane() {
        let stage = dual()
        let hidden = stage.secondary!
        stage.setLandscape(true)
        stage.setLandscape(false)
        XCTAssertFalse(stage.landscape)
        XCTAssertTrue(hidden.visible)
        XCTAssertNotNil(hidden.playTask, "回竖屏用 requestPlay(lane:) 恢复")
    }

    func testRoomChangeWhileHiddenDoesNotStartPlayback() {
        let stage = dual()
        stage.setLandscape(true)
        stage.updateFeed { $0.setFilters(blocked: ["bob"], only: nil) }
        XCTAssertEqual(names(stage).1, "carol", "列表变动照常换掉副路房间")
        XCTAssertNil(stage.secondary!.playTask, "横屏时副路被换房也不起播")
    }

    func testSetLandscapeIsIdempotentAndIgnoredInSingleWindow() {
        let stage = makeStage()
        stage.setLandscape(true)
        XCTAssertTrue(stage.primary.visible, "单窗横屏仍播主路")
        XCTAssertNil(stage.secondary)
    }

    func testClosingDualInLandscapeRestoresVisibility() {
        let stage = dual()
        stage.setFocus(.secondary)
        stage.setLandscape(true)
        stage.setDual(false)
        XCTAssertTrue(stage.primary.visible)
        XCTAssertEqual(stage.name(of: stage.primary), "bob")
    }
}

/// 两路房间互不相同的结论（日志与验收都读它）。
@MainActor
final class DualWindowDistinctTests: XCTestCase {
    override func setUp() {
        super.setUp()
        TestPrefs.reset()
    }

    override func tearDown() {
        TestPrefs.reset()
        super.tearDown()
    }

    func testLanesDistinctFollowsRooms() {
        let name = "vela-phone-distinct-\(UUID().uuidString)"
        defer { UserDefaults().removePersistentDomain(forName: name) }
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!))
        XCTAssertTrue(stage.lanesDistinct, "单窗没有重复一说")
        stage.updateFeed(intent: .reload) {
            $0.replace(with: CatalogPage(rooms: ["a1", "b2", "c3"].map { Room.direct(username: $0)! }, total: 3, nextOffset: 3))
        }
        stage.setDual(true)
        XCTAssertTrue(stage.lanesDistinct)
        stage.secondary!.username = stage.primary.username
        XCTAssertFalse(stage.lanesDistinct, "判定本身认得出重复")
    }
}
