import XCTest
@testable import VelaPhone

/// iOS 三窗（ios-triple 计划 R2，T-2 与修订 v2 的 H1、M9）：路数 1 / 2 / 3 的选择与迁移、增减路数的释放与压紧、
/// 横屏临时释放、三路两两不重复、焦点路操作只作用于焦点路。年龄未确认：起播与目录不发请求，起播是否被请求看 `playTask`。
@MainActor
final class TripleWindowTests: XCTestCase {
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
        let name = "vela-phone-triple-\(UUID().uuidString)"
        suites.append(name)
        return UserDefaults(suiteName: name)!
    }

    private func room(_ name: String) -> Room { Room.direct(username: name)! }

    private func install(_ stage: StageModel, _ names: [String], source: FeedSource? = nil) {
        stage.updateFeed(installing: source, intent: .reload) {
            $0.replace(with: CatalogPage(rooms: names.map(room), total: names.count, nextOffset: names.count))
        }
    }

    private func makeStage(_ names: [String] = ["alice", "bob", "carol", "dave", "erin"], defaults: UserDefaults? = nil) -> StageModel {
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: defaults ?? self.defaults()))
        install(stage, names)
        return stage
    }

    private func triple(_ names: [String] = ["alice", "bob", "carol", "dave", "erin"]) -> StageModel {
        let stage = makeStage(names)
        stage.setLaneCount(3)
        return stage
    }

    private func names(_ stage: StageModel) -> [String?] {
        [stage.primary, stage.secondary, stage.tertiary].map { lane in lane.flatMap { stage.name(of: $0) } }
    }

    private func assertDistinct(_ stage: StageModel, _ message: String = "", file: StaticString = #filePath, line: UInt = #line) {
        let taken = names(stage).compactMap { $0?.lowercased() }
        XCTAssertEqual(Set(taken).count, taken.count, "三路房间重复 \(names(stage)) \(message)", file: file, line: line)
        XCTAssertTrue(stage.lanesDistinct, file: file, line: line)
    }

    // MARK: M9 设置与迁移

    func testLaneCountMigration() {
        let fresh = defaults()
        XCTAssertEqual(SettingsStore(defaults: fresh).laneCount, 1, "全新安装默认 1 路")

        let oldOff = defaults()
        oldOff.set(false, forKey: "phoneDual")
        XCTAssertEqual(SettingsStore(defaults: oldOff).laneCount, 1)
        XCTAssertEqual(oldOff.object(forKey: "laneCount") as? Int, 1, "迁移结果立即落盘")

        let oldOn = defaults()
        oldOn.set(true, forKey: "phoneDual")
        XCTAssertEqual(SettingsStore(defaults: oldOn).laneCount, 2, "旧 phoneDual=true 迁移为 2")
        XCTAssertEqual(oldOn.object(forKey: "laneCount") as? Int, 2)

        let both = defaults()
        both.set(true, forKey: "phoneDual")
        both.set(3, forKey: "laneCount")
        XCTAssertEqual(SettingsStore(defaults: both).laneCount, 3, "新键存在且合法就用新键")

        for bad in [0, 4, -1] {
            let store = defaults()
            store.set(bad, forKey: "laneCount")
            store.set(true, forKey: "phoneDual")
            XCTAssertEqual(SettingsStore(defaults: store).laneCount, 1, "非法值 \(bad) 按 1")
        }
        let text = defaults()
        text.set("three", forKey: "laneCount")
        XCTAssertEqual(SettingsStore(defaults: text).laneCount, 1)
    }

    func testRememberedThreeLanesRestoreAtLaunch() {
        let store = defaults()
        SettingsStore(defaults: store).laneCount = 3
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: store))
        XCTAssertEqual(stage.laneCount, 3)
        XCTAssertNotNil(stage.tertiary)
        install(stage, ["alice", "bob", "carol", "dave"])
        XCTAssertEqual(names(stage), ["alice", "bob", "carol"], "列表到达后依次取前一路之后的一间")
    }

    func testTemporaryLaneCountDoesNotOverwriteChoice() {
        let stage = makeStage()
        stage.setLaneCount(3)
        stage.setLaneCount(1, remember: false)
        XCTAssertEqual(stage.laneCount, 1)
        XCTAssertEqual(stage.settings.laneCount, 3, "临时单路不覆盖用户选择")
    }

    // MARK: 增加路数

    func testOneToThreeOpensLanesInOrderAndRemembers() {
        let stage = makeStage()
        stage.setLaneCount(3)
        XCTAssertEqual(stage.lanes.map(\.id), [.primary, .secondary, .tertiary])
        XCTAssertEqual(names(stage), ["alice", "bob", "carol"])
        XCTAssertEqual(stage.settings.laneCount, 3)
        XCTAssertTrue(stage.dualWindow)
        XCTAssertEqual(stage.focusLane, .primary)
        XCTAssertNotNil(stage.secondary?.playTask)
        XCTAssertNotNil(stage.tertiary?.playTask)
    }

    func testTwoToThreeKeepsExistingLanesUntouched() {
        let stage = makeStage()
        stage.setLaneCount(2)
        stage.setFocus(.secondary)
        let secondary = stage.secondary!
        let generations = (stage.primary.playGeneration, secondary.playGeneration)
        stage.setLaneCount(3)
        XCTAssertTrue(stage.secondary === secondary)
        XCTAssertEqual(stage.primary.playGeneration, generations.0, "已有的路不重新起播")
        XCTAssertEqual(secondary.playGeneration, generations.1)
        XCTAssertEqual(names(stage), ["alice", "bob", "carol"])
        XCTAssertEqual(stage.focusLane, .secondary, "加路不动焦点")
    }

    func testSetLaneCountIsIdempotentAndClamped() {
        let stage = triple()
        let lane = stage.tertiary
        stage.setLaneCount(3)
        XCTAssertTrue(stage.tertiary === lane)
        stage.setLaneCount(9)
        XCTAssertEqual(stage.laneCount, 3)
        stage.setDual(true)
        XCTAssertEqual(stage.laneCount, 3, "旧的开双窗入口不把三路降成两路")
    }

    func testNewLaneSkipsRecentlySwipedRoom() {
        let stage = makeStage()
        stage.setLaneCount(2)
        stage.step(1, on: stage.primary)
        XCTAssertEqual(names(stage)[0], "carol", "主路越过副路的 bob")
        stage.step(-1, on: stage.primary)
        XCTAssertEqual(names(stage)[0], "alice", "向后划回本路自己的上一间")
        stage.setLaneCount(3)
        XCTAssertEqual(names(stage)[2], "dave", "刚划走的 carol 在最近划走里，补位不落上去")
        assertDistinct(stage)
    }

    // MARK: H1 减少路数（每种焦点位置）

    func testThreeToTwoWithFocusOnPrimaryOrSecondaryDropsTertiary() {
        for focus in [LaneID.primary, .secondary] {
            let stage = triple()
            stage.setFocus(focus)
            let secondary = stage.secondary!
            let tertiary = stage.tertiary!
            let generation = secondary.playGeneration
            stage.setLaneCount(2)
            XCTAssertEqual(stage.lanes.map(\.id), [.primary, .secondary], "焦点 \(focus)")
            XCTAssertTrue(stage.secondary === secondary)
            XCTAssertEqual(secondary.playGeneration, generation, "保留的路不动")
            XCTAssertNil(stage.tertiary)
            XCTAssertFalse(tertiary.player.hasItem)
            XCTAssertNil(tertiary.playTask)
            XCTAssertNil(tertiary.dwell.room, "被移除的路已结算停留")
            XCTAssertEqual(stage.focusLane, focus)
            XCTAssertEqual(names(stage), ["alice", "bob", nil])
            XCTAssertEqual(stage.settings.laneCount, 2)
        }
    }

    func testThreeToTwoWithFocusOnTertiaryCompactsItIntoSecondary() {
        let stage = triple()
        stage.setFocus(.tertiary)
        let tertiary = stage.tertiary!
        let oldSecondary = stage.secondary!
        let generation = tertiary.playGeneration
        let primaryGeneration = stage.primary.playGeneration
        stage.setLaneCount(2)
        XCTAssertTrue(stage.secondary === tertiary, "第三路的状态整体迁到副路")
        XCTAssertEqual(tertiary.id, .secondary)
        XCTAssertEqual(tertiary.player.laneLabel, "secondary")
        XCTAssertEqual(tertiary.playGeneration, generation, "迁移不重新起播")
        XCTAssertEqual(stage.focusLane, .secondary, "焦点随迁")
        XCTAssertEqual(names(stage), ["alice", "carol", nil])
        XCTAssertNil(stage.tertiary)
        XCTAssertFalse(oldSecondary.player.hasItem, "被移除的副路已释放")
        XCTAssertNil(oldSecondary.playTask)
        XCTAssertEqual(stage.primary.playGeneration, primaryGeneration)
        XCTAssertTrue(stage.focusPlaybackLane === tertiary)
        XCTAssertNil(stage.target(.tertiary))
        XCTAssertEqual(stage.target(.secondary)?.username, "carol")
    }

    func testThreeToOneKeepsTheFocusedRoomOnPrimary() {
        for (focus, room) in [(LaneID.primary, "alice"), (.secondary, "bob"), (.tertiary, "carol")] {
            let stage = triple()
            stage.setFocus(focus)
            let others = stage.lanes.filter { $0.id != .primary }
            stage.setLaneCount(1)
            XCTAssertEqual(stage.lanes.map(\.id), [.primary], "焦点 \(focus)")
            XCTAssertEqual(stage.name(of: stage.primary), room, "焦点路的房间留下")
            XCTAssertEqual(stage.focusLane, .primary)
            XCTAssertFalse(stage.dualWindow)
            for lane in others {
                XCTAssertFalse(lane.player.hasItem)
                XCTAssertNil(lane.playTask)
            }
            if focus != .primary { XCTAssertNotNil(stage.primary.playTask, "主路换到焦点房间并起播") }
        }
    }

    func testTwoToOneWithEitherFocus() {
        for (focus, room) in [(LaneID.primary, "alice"), (.secondary, "bob")] {
            let stage = makeStage()
            stage.setLaneCount(2)
            stage.setFocus(focus)
            stage.setLaneCount(1)
            XCTAssertEqual(stage.lanes.count, 1)
            XCTAssertEqual(stage.name(of: stage.primary), room)
            XCTAssertEqual(stage.settings.laneCount, 1)
        }
    }

    // MARK: H1 临时释放（横屏）

    func testLandscapeReleasesBothNonFocusLanesAndKeepsTheirRooms() {
        let stage = triple()
        stage.setFocus(.secondary)
        stage.setLandscape(true)
        XCTAssertTrue(stage.secondary!.visible)
        for lane in [stage.primary, stage.tertiary!] {
            XCTAssertFalse(lane.visible)
            XCTAssertFalse(lane.player.hasItem)
            XCTAssertNil(lane.playTask)
        }
        XCTAssertEqual(names(stage), ["alice", "bob", "carol"], "逻辑路状态全部保留")
        XCTAssertEqual(stage.laneCount, 3, "横屏不改用户选择")
        stage.setLandscape(false)
        for lane in stage.lanes { XCTAssertTrue(lane.visible) }
        XCTAssertNotNil(stage.primary.playTask, "回竖屏按原路数恢复")
        XCTAssertNotNil(stage.tertiary!.playTask)
    }

    func testChangingLaneCountWhileInLandscape() {
        let stage = triple()
        stage.setFocus(.tertiary)
        stage.setLandscape(true)
        stage.setLaneCount(2)
        XCTAssertEqual(stage.focusLane, .secondary)
        XCTAssertTrue(stage.secondary!.visible, "焦点路仍显示")
        XCTAssertFalse(stage.primary.visible, "横屏期间非焦点路仍释放")
        XCTAssertNil(stage.primary.playTask)
        stage.setLaneCount(3)
        XCTAssertFalse(stage.tertiary!.visible, "横屏期间新开的路不起播")
        XCTAssertNil(stage.tertiary!.playTask)
        stage.setLandscape(false)
        XCTAssertEqual(stage.lanes.count, 3)
        XCTAssertNotNil(stage.primary.playTask)
        XCTAssertNotNil(stage.tertiary!.playTask)
        assertDistinct(stage)
    }

    // MARK: T-2 三路两两不重复

    func testBlockingEachLaneKeepsAllThreeDistinct() {
        for id in LaneID.allCases {
            let stage = triple()
            let before = names(stage)
            stage.blockCurrent(stage.target(id))
            assertDistinct(stage, "屏蔽 \(id)")
            for (index, other) in LaneID.allCases.enumerated() where other != id {
                XCTAssertEqual(names(stage)[index], before[index], "屏蔽 \(id) 不动 \(other)")
            }
            XCTAssertFalse(names(stage).contains(before[LaneID.allCases.firstIndex(of: id)!]))
        }
    }

    func testUnfavoriteInFavoritesSourceStaysDistinct() {
        let stage = makeStage()
        stage.source = .favorites
        stage.commit(Curation().favoriting("alice").favoriting("bob").favoriting("carol").favoriting("dave"), nil)
        install(stage, ["alice", "bob", "carol", "dave"], source: .favorites)
        stage.setLaneCount(3)
        stage.removeFavorite("bob")
        XCTAssertEqual(names(stage), ["alice", "dave", "carol"])
        stage.removeFavorite("dave")
        XCTAssertEqual(names(stage), ["alice", nil, "carol"], "只剩两间：非焦点路空着")
        assertDistinct(stage)
    }

    func testListShrinkingToOneThenBackKeepsFocusFirst() {
        let stage = triple(["alice", "alex", "bob", "alina", "aldo"])
        stage.setFocus(.tertiary)
        stage.searchText = "bob"
        stage.applySearch()
        XCTAssertEqual(names(stage), [nil, nil, "bob"], "只剩一间：焦点路保留它")
        stage.searchText = ""
        stage.applySearch()
        assertDistinct(stage, "清空搜索")
        XCTAssertNotNil(names(stage)[0])
        XCTAssertNotNil(names(stage)[1])
        XCTAssertEqual(names(stage)[2], "bob")
    }

    func testSiteSearchInstallAndExitStayDistinct() {
        let stage = triple()
        stage.updateFeed(intent: .searchInstall) {
            $0.replace(with: CatalogPage(rooms: ["zed", "yan", "xi", "wu"].map(self.room), total: 4, nextOffset: 4))
        }
        XCTAssertEqual(names(stage), ["zed", "yan", "xi"])
        install(stage, ["alice", "bob", "carol", "dave"])
        assertDistinct(stage)
    }

    func testEmptyListThenResumeRefillsAllThree() {
        let stage = triple()
        stage.updateFeed { $0.setFilters(blocked: ["alice", "bob", "carol", "dave", "erin"], only: nil) }
        XCTAssertEqual(names(stage), [nil, nil, nil])
        stage.updateFeed { $0.setFilters(blocked: [], only: nil) }
        XCTAssertEqual(names(stage), ["alice", "bob", "carol"])
    }

    func testRevealTargetOnTertiaryOnlySwitchesFocus() {
        let stage = triple()
        let generations = stage.lanes.map(\.playGeneration)
        stage.applyDirectSearchHit(room("carol"))
        XCTAssertEqual(stage.focusLane, .tertiary)
        XCTAssertEqual(names(stage), ["alice", "bob", "carol"])
        XCTAssertEqual(stage.lanes.map(\.playGeneration), generations, "各路都不重新起播")
    }

    func testSwipeOnTertiaryNeverLandsOnOtherLanes() {
        let stage = triple()
        stage.step(-1, on: stage.tertiary!)
        XCTAssertEqual(names(stage)[2], "carol", "前面两间都被占：原地")
        stage.step(1, on: stage.tertiary!)
        XCTAssertEqual(names(stage)[2], "dave")
        stage.step(1, on: stage.secondary!)
        XCTAssertEqual(names(stage)[1], "erin", "跳过第三路的 dave，最近划走的 carol 也跳过")
        assertDistinct(stage)
    }

    // MARK: 焦点路操作只作用于焦点路

    func testTargetedActionsOnTertiaryOnlyTouchTertiary() {
        let stage = triple()
        stage.setFocus(.tertiary)
        stage.togglePause(stage.target(.tertiary, showing: "carol"))
        XCTAssertTrue(stage.tertiary!.manuallyPaused)
        XCTAssertFalse(stage.primary.manuallyPaused)
        XCTAssertFalse(stage.secondary!.manuallyPaused)
        stage.toggleFavorite(stage.target(.tertiary, showing: "carol"))
        XCTAssertTrue(stage.curation.isFavorite("carol"))
        XCTAssertFalse(stage.curation.isFavorite("alice"))
        stage.markNotInterested(stage.target(.tertiary, showing: "carol"))
        XCTAssertEqual(names(stage)[0], "alice")
        XCTAssertEqual(names(stage)[1], "bob")
        XCTAssertNotEqual(names(stage)[2], "carol")
        assertDistinct(stage)
    }

    func testFocusCanMoveToEveryLaneAndNotToMissingOne() {
        let stage = makeStage()
        stage.setLaneCount(2)
        stage.setFocus(.tertiary)
        XCTAssertEqual(stage.focusLane, .primary, "焦点不能落在没开的路上")
        stage.setLaneCount(3)
        for id in [LaneID.tertiary, .secondary, .primary, .tertiary] {
            stage.setFocus(id)
            XCTAssertEqual(stage.focusLane, id)
            XCTAssertTrue(stage.focusPlaybackLane === stage.findLane(id))
        }
    }

    func testLaneRoomsLogConclusionCoversThreeLanes() {
        let stage = triple()
        XCTAssertTrue(stage.lanesDistinct)
        stage.tertiary!.username = stage.secondary!.username
        XCTAssertFalse(stage.lanesDistinct, "第三路与副路重复也认得出")
    }
    // MARK: M12 可用尺寸

    func testShortScreenCapsAtTwoLanesWithoutOverwritingChoice() {
        let stage = triple()
        stage.notePortraitStageSize(CGSize(width: 402, height: 874))
        XCTAssertTrue(stage.threeLanesFit)
        XCTAssertEqual(stage.laneCount, 3)
        stage.notePortraitStageSize(CGSize(width: 402, height: 400))
        XCTAssertFalse(stage.threeLanesFit, "每格不足 160 点")
        XCTAssertEqual(stage.maxLaneCount, 2)
        XCTAssertEqual(stage.laneCount, 2, "临时降到 2 路")
        XCTAssertEqual(stage.settings.laneCount, 3, "不覆盖用户选择")
        stage.setLaneCount(3)
        XCTAssertEqual(stage.laneCount, 2, "放不下时选不了第三路")
        stage.notePortraitStageSize(CGSize(width: 402, height: 874))
        XCTAssertEqual(stage.laneCount, 3, "又放得下时按用户选择恢复")
        assertDistinct(stage)
    }
    // MARK: 审计 high：空主路不能被列表首间冒充

    /// 焦点在第三路、屏蔽主路后剩下两间正好分属副路与第三路：主路合法地为空。
    /// 再向前划占着首间的副路，不能落到第三路正在播的房间上（审计发现：快照把 `feed.current` 的回退值当主路）。
    func testEmptyPrimaryIsNotImpersonatedWhenSwipingForwardInThreeLanes() {
        let stage = triple(["alice", "bob", "carol"])
        stage.setFocus(.tertiary)
        stage.blockCurrent(stage.target(.primary))
        XCTAssertEqual(names(stage), [nil, "bob", "carol"], "主路合法地为空")
        let snapshot = stage.laneSnapshot(of: stage.feed)
        XCTAssertNil(snapshot.primary.username, "快照里主路保持为空，不取列表首间")
        stage.step(1, on: stage.secondary!)
        assertDistinct(stage, "向前划副路")
        XCTAssertEqual(names(stage)[1], "bob", "没有空房可去：副路原地")
        stage.step(-1, on: stage.tertiary!)
        assertDistinct(stage, "向后划第三路")
        stage.applyDirectSearchHit(room("carol"))
        assertDistinct(stage, "reveal 第三路的房间")
        XCTAssertEqual(stage.focusLane, .tertiary)
    }

    /// 两路时主路为空（列表只剩副路那一间），向前划副路仍两路不重复，主路仍为空。
    func testEmptyPrimaryStaysEmptyWhenSwipingForwardInTwoLanes() {
        let stage = makeStage(["alice", "bob"])
        stage.setLaneCount(2)
        stage.setFocus(.secondary)
        stage.blockCurrent(stage.target(.primary))
        XCTAssertEqual(names(stage), [nil, "bob", nil])
        XCTAssertNil(stage.laneSnapshot(of: stage.feed).primary.username)
        stage.step(1, on: stage.secondary!)
        XCTAssertEqual(names(stage), [nil, "bob", nil])
        assertDistinct(stage)
    }
    // MARK: 审计 medium：扩路时已有的空路也要补位

    /// 两路时主路在列表末尾、副路合法地为空（重载不绕回开头）；扩到三路时 `.fill` 给空着的副路也分了房间，必须一并提交。
    func testExpandingFillsAnExistingEmptyLaneToo() {
        let stage = makeStage(["alice", "bob", "carol"])
        stage.setLaneCount(2)
        stage.step(1, on: stage.primary)
        install(stage, ["alice", "bob", "carol"])
        XCTAssertEqual(names(stage), ["carol", nil, nil], "重载：主路在末尾，副路为空")
        stage.setLaneCount(3)
        XCTAssertEqual(names(stage), ["carol", "bob", "alice"], "副路与第三路都补上")
        XCTAssertNotNil(stage.secondary!.playTask, "补上的副路起播")
        assertDistinct(stage)
    }
}
