import XCTest
#if SWIFT_PACKAGE
@testable import VelaCore
#else
@testable import VelaPhone
#endif

/// 联合选房：单窗必须与 FeedStore 自己的选择一致；双窗按 §4.1 调整副路并避免两路同房。
final class LaneSelectionTests: XCTestCase {
    private func room(_ name: String, tags: [String] = []) -> Room {
        Room(username: name, viewers: 10, gender: .female, tags: tags, show: .publicShow)
    }

    private func makeFeed(_ names: [String], current: String? = nil, query: RoomQuery = RoomQuery()) -> FeedStore {
        var store = FeedStore(query: query)
        store.replace(with: CatalogPage(rooms: names.map { room($0) }, total: max(names.count, 1), nextOffset: names.count))
        if let current {
            XCTAssertTrue(store.focus(current), "测试夹具应能停在 \(current)")
        }
        return store
    }

    private func resolve(
        before feed: FeedStore,
        afterRooms: [String],
        proposed: String?,
        secondary: LaneCursor?,
        focus: LaneID = .primary,
        primaryDirection: Int = 1,
        intent: LaneSelection.Intent
    ) -> LaneSelection.Outcome {
        LaneSelection.resolve(
            before: LaneSelection.Snapshot(
                rooms: feed.rooms.map(\.username),
                primary: LaneCursor(username: feed.currentUsername, direction: primaryDirection),
                secondary: secondary,
                focus: focus
            ),
            afterRooms: afterRooms,
            proposedPrimary: proposed,
            intent: intent
        )
    }

    // MARK: 屏蔽主路

    /// 屏蔽主路后，FeedStore 会落到副路那间；主路沿自己的方向再找一间，两路不重复。
    func testBlockingPrimarySkipsTheSecondaryRoom() {
        var before = makeFeed(["a", "b", "c", "d"], current: "b")
        var blocked = before
        blocked.setBlocked(["b"])
        XCTAssertEqual(blocked.currentUsername, "c", "单窗被动选择是后一间")
        XCTAssertEqual(blocked.rooms.map(\.username), ["a", "c", "d"])

        let forward = resolve(
            before: before,
            afterRooms: blocked.rooms.map(\.username),
            proposed: blocked.currentUsername,
            secondary: LaneCursor(username: "d"),
            intent: .filter
        )
        XCTAssertEqual(forward.primary.username, "c", "没落到副路时保留 FeedStore 的选择")
        XCTAssertEqual(forward.secondary?.username, "d")

        let collision = resolve(
            before: before,
            afterRooms: blocked.rooms.map(\.username),
            proposed: blocked.currentUsername,
            secondary: LaneCursor(username: "c"),
            intent: .filter
        )
        XCTAssertEqual(collision.primary.username, "d")
        XCTAssertEqual(collision.secondary?.username, "c")
        XCTAssertNotEqual(collision.primary.username, collision.secondary?.username)

        let backward = resolve(
            before: before,
            afterRooms: blocked.rooms.map(\.username),
            proposed: blocked.currentUsername,
            secondary: LaneCursor(username: "c"),
            primaryDirection: -1,
            intent: .filter
        )
        XCTAssertEqual(backward.primary.username, "a", "主路沿自己的方向（向上）另找")
        XCTAssertEqual(backward.secondary?.username, "c")
        XCTAssertEqual(backward.primary.direction, -1)
    }

    // MARK: 取消主路收藏

    func testUnfavoritingPrimaryDoesNotDuplicateSecondary() {
        let favorites: Set<String> = ["a", "b", "c"]
        var before = makeFeed(["a", "b", "c"], current: "b", query: RoomQuery(only: favorites))
        var after = before
        after.setFilters(blocked: [], only: ["a", "c"])
        XCTAssertEqual(after.rooms.map(\.username), ["a", "c"])
        XCTAssertEqual(after.currentUsername, "c")

        let outcome = resolve(
            before: before,
            afterRooms: after.rooms.map(\.username),
            proposed: after.currentUsername,
            secondary: LaneCursor(username: "c"),
            intent: .filter
        )
        XCTAssertEqual(outcome.primary.username, "a", "向前没有别的房间，就反向离开副路")
        XCTAssertEqual(outcome.secondary?.username, "c")
        XCTAssertNotEqual(outcome.primary.username, outcome.secondary?.username)
    }

    // MARK: 提交本地搜索

    /// setQuery 在当前房间两侧都有匹配时取新列表第一项，而不是原位置的后一项。副路仍匹配则不动。
    func testSubmittingLocalSearchKeepsSecondaryWhenItStillMatches() {
        var before = makeFeed(["amy", "bob", "cara", "dave"], current: "bob")
        var after = before
        after.setQuery(RoomQuery(text: "a"))
        XCTAssertEqual(after.rooms.map(\.username), ["amy", "cara", "dave"])
        XCTAssertEqual(after.currentUsername, "amy")

        let outcome = resolve(
            before: before,
            afterRooms: after.rooms.map(\.username),
            proposed: after.currentUsername,
            secondary: LaneCursor(username: "dave"),
            intent: .filter
        )
        XCTAssertEqual(outcome.primary.username, "amy")
        XCTAssertEqual(outcome.secondary?.username, "dave", "全站重载才会把副路改到主路的后一间")
    }

    // MARK: 两间缩成一间

    func testTwoRoomsCollapseToTheFocusedLane() {
        let before = makeFeed(["a", "b"], current: "a")
        let onPrimary = resolve(
            before: before,
            afterRooms: ["a"],
            proposed: "a",
            secondary: LaneCursor(username: "b"),
            focus: .primary,
            intent: .filter
        )
        XCTAssertEqual(onPrimary.primary.username, "a")
        XCTAssertNotNil(onPrimary.secondary)
        XCTAssertNil(onPrimary.secondary?.username)
        XCTAssertEqual(onPrimary.focus, .primary)
        XCTAssertEqual(onPrimary.reasons[.secondary], .empty)

        let onSecondary = resolve(
            before: before,
            afterRooms: ["a"],
            proposed: "a",
            secondary: LaneCursor(username: "b"),
            focus: .secondary,
            intent: .filter
        )
        XCTAssertNil(onSecondary.primary.username)
        XCTAssertEqual(onSecondary.secondary?.username, "a")
        XCTAssertEqual(onSecondary.focus, .secondary)
        XCTAssertEqual(onSecondary.reasons[.primary], .empty)
    }

    // MARK: 空列表与续页恢复

    func testEmptyListClearsBothLanes() {
        let before = makeFeed(["a", "b"], current: "a")
        var after = before
        after.setBlocked(["a", "b"])
        XCTAssertNil(after.current)
        XCTAssertTrue(after.rooms.isEmpty)

        let outcome = resolve(
            before: before,
            afterRooms: [],
            proposed: nil,
            secondary: LaneCursor(username: "b"),
            intent: .filter
        )
        XCTAssertNil(outcome.primary.username)
        XCTAssertNotNil(outcome.secondary, "双窗的副路还在，只是没有房间")
        XCTAssertNil(outcome.secondary?.username)
        XCTAssertEqual(outcome.reasons[.primary], .empty)
        XCTAssertEqual(outcome.reasons[.secondary], .empty)
    }

    func testBlankResumeReselectsBothLanes() {
        var resumed = FeedStore()
        resumed.append(CatalogPage(rooms: [room("c"), room("d"), room("e")], total: 3, nextOffset: 3))
        XCTAssertEqual(resumed.currentUsername, "c")

        let outcome = LaneSelection.resolve(
            before: LaneSelection.Snapshot(
                rooms: [],
                primary: LaneCursor(username: nil, direction: -1),
                secondary: LaneCursor(username: nil, direction: 1),
                focus: .primary
            ),
            afterRooms: resumed.rooms.map(\.username),
            proposedPrimary: resumed.currentUsername,
            intent: .blankResume
        )
        XCTAssertEqual(outcome.primary.username, "c")
        XCTAssertEqual(outcome.secondary?.username, "d")
        XCTAssertEqual(outcome.primary.direction, -1, "重新选房不改这路原来的方向")
        XCTAssertEqual(outcome.reasons[.primary], .resume)
        XCTAssertEqual(outcome.reasons[.secondary], .resume)
        XCTAssertNotEqual(outcome.primary.username, outcome.secondary?.username)
    }

    /// 经过 `updateFeed` 用的入口：可见列表由空变非空（续页、放宽筛选）时按 `.blankResume` 两路都选上。
    func testFeedChangeFromBlankResumesBothLanes() {
        let blank = LaneSelection.Snapshot(
            rooms: [],
            primary: LaneCursor(username: nil, direction: 1),
            secondary: LaneCursor(username: nil, direction: 1),
            focus: .primary
        )
        for intent in [LaneSelection.Intent.merge, .filter] {
            let outcome = LaneSelection.resolveFeedChange(
                before: blank, afterRooms: ["c", "d", "e"], proposedPrimary: "c", intent: intent
            )
            XCTAssertEqual(outcome.primary.username, "c", "\(intent)")
            XCTAssertEqual(outcome.secondary?.username, "d", "\(intent) 副路也要续上")
            XCTAssertEqual(outcome.reasons[.primary], .resume, "\(intent)")
            XCTAssertEqual(outcome.reasons[.secondary], .resume, "\(intent)")
        }
        XCTAssertEqual(LaneSelection.feedIntent(.merge, beforeRooms: [], afterRooms: ["a"]), .blankResume)
        XCTAssertEqual(LaneSelection.feedIntent(.merge, beforeRooms: ["a"], afterRooms: ["a", "b"]), .merge, "非空到非空不改")
        XCTAssertEqual(LaneSelection.feedIntent(.filter, beforeRooms: [], afterRooms: []), .filter, "仍为空不改")
        XCTAssertEqual(LaneSelection.feedIntent(.reload, beforeRooms: [], afterRooms: ["a"]), .reload, "整表换源保留原意图")
        XCTAssertEqual(LaneSelection.feedIntent(.reveal("a"), beforeRooms: [], afterRooms: ["a"]), .reveal("a"))
    }

    /// 单窗：续页恢复与 FeedStore 的选择一致（macOS 行为不变）。
    func testFeedChangeFromBlankSingleWindowMatchesFeedStore() {
        var resumed = FeedStore()
        resumed.append(CatalogPage(rooms: [room("c"), room("d")], total: 2, nextOffset: 2))
        let outcome = LaneSelection.resolveFeedChange(
            before: LaneSelection.Snapshot(rooms: [], primary: LaneCursor(), secondary: nil, focus: .primary),
            afterRooms: resumed.rooms.map(\.username), proposedPrimary: resumed.currentUsername, intent: .merge
        )
        XCTAssertEqual(outcome.primary.username, resumed.currentUsername)
        XCTAssertNil(outcome.secondary)
    }

    // MARK: reveal

    func testRevealTargetOnTheOtherLaneOnlySwitchesFocus() {
        let before = makeFeed(["a", "b", "c"], current: "a")
        var revealed = before
        XCTAssertTrue(revealed.reveal(room("b")))
        XCTAssertEqual(revealed.currentUsername, "b", "单窗会把主路直接改到目标")

        let outcome = resolve(
            before: before,
            afterRooms: revealed.rooms.map(\.username),
            proposed: revealed.currentUsername,
            secondary: LaneCursor(username: "b"),
            focus: .primary,
            intent: .reveal("b")
        )
        XCTAssertEqual(outcome.focus, .secondary)
        XCTAssertEqual(outcome.primary.username, "a")
        XCTAssertEqual(outcome.secondary?.username, "b")
        XCTAssertTrue(outcome.reasons.isEmpty, "只切焦点，两路游标都不结算")
    }

    // MARK: 全站搜索与整表重载

    func testSiteSearchInstallAndExitPlaceSecondaryAfterPrimary() {
        let before = makeFeed(["a", "b", "c"], current: "b")
        var installed = FeedStore(currentUsername: "b")
        installed.replace(with: SiteSearch.page([room("s1"), room("b"), room("s3")], blocked: []))
        installed.setQuery(RoomQuery())
        XCTAssertEqual(installed.currentUsername, "b")

        let install = resolve(
            before: before,
            afterRooms: installed.rooms.map(\.username),
            proposed: installed.currentUsername,
            secondary: LaneCursor(username: "c"),
            intent: .searchInstall
        )
        XCTAssertEqual(install.primary.username, "b")
        XCTAssertEqual(install.secondary?.username, "s3", "副路取新列表里主路之后的第一间，不沿用旧副路")

        var restored = makeFeed(["a", "b", "c"], current: "a")
        let exit = LaneSelection.resolve(
            before: LaneSelection.Snapshot(
                rooms: installed.rooms.map(\.username),
                primary: LaneCursor(username: "s1"),
                secondary: LaneCursor(username: "s3"),
                focus: .primary
            ),
            afterRooms: restored.rooms.map(\.username),
            proposedPrimary: restored.currentUsername,
            intent: .searchExit
        )
        XCTAssertEqual(restored.currentUsername, "a")
        XCTAssertEqual(exit.primary.username, "a")
        XCTAssertEqual(exit.secondary?.username, "b")
        XCTAssertEqual(exit.reasons[.primary], .sourceChange)
        XCTAssertEqual(exit.reasons[.secondary], .sourceChange)
    }

    func testReloadPlacesSecondaryAfterPrimaryInsteadOfKeepingIt() {
        var before = makeFeed(["a", "b", "c", "d"], current: "b")
        var reloaded = before
        reloaded.replace(with: CatalogPage(
            rooms: [room("a"), room("b"), room("c"), room("d")],
            total: 4,
            nextOffset: 4
        ))
        reloaded.setQuery(RoomQuery())
        XCTAssertEqual(reloaded.currentUsername, "b")

        let outcome = resolve(
            before: before,
            afterRooms: reloaded.rooms.map(\.username),
            proposed: reloaded.currentUsername,
            secondary: LaneCursor(username: "d"),
            intent: .reload
        )
        XCTAssertEqual(outcome.primary.username, "b")
        XCTAssertEqual(outcome.secondary?.username, "c")

        let atEnd = resolve(
            before: makeFeed(["a", "b", "c"], current: "c"),
            afterRooms: ["a", "b", "c"],
            proposed: "c",
            secondary: LaneCursor(username: "a"),
            intent: .reload
        )
        XCTAssertEqual(atEnd.primary.username, "c")
        XCTAssertNil(atEnd.secondary?.username, "主路已经是最后一间时副路为空，不绕回列表开头")
    }

    // MARK: 合并、重排、前置

    func testMergeKeepsLanesWhoseRoomsRemain() {
        var before = makeFeed(["a", "b", "c", "d"], current: "b")
        var after = before
        after.reorderAfterCurrent { rooms in
            rooms.sorted { $0.username > $1.username }
        }
        XCTAssertEqual(after.catalog.map(\.username), ["a", "b", "d", "c"])
        XCTAssertEqual(after.currentUsername, "b")

        let outcome = resolve(
            before: before,
            afterRooms: after.rooms.map(\.username),
            proposed: after.currentUsername,
            secondary: LaneCursor(username: "d", direction: -1),
            intent: .merge
        )
        XCTAssertEqual(outcome.primary.username, "b")
        XCTAssertEqual(outcome.secondary?.username, "d")
        XCTAssertEqual(outcome.secondary?.direction, -1)
        XCTAssertTrue(outcome.reasons.isEmpty)
    }

    // MARK: 副路消失

    /// 「不想看」或取消收藏让副路房间消失：沿这路原来的方向找，找不到再反方向，再没有就空。
    func testSecondaryLostToUnfavoriteOrHiddenSearchesAlongItsDirection() {
        let all: Set<String> = ["a", "b", "c", "d", "e"]
        var favorites = makeFeed(["a", "b", "c", "d", "e"], current: "a", query: RoomQuery(only: all))
        var dropped = favorites
        dropped.setFilters(blocked: [], only: ["a", "b", "d", "e"])
        XCTAssertEqual(dropped.rooms.map(\.username), ["a", "b", "d", "e"])
        XCTAssertEqual(dropped.currentUsername, "a")

        let forward = resolve(
            before: favorites,
            afterRooms: dropped.rooms.map(\.username),
            proposed: dropped.currentUsername,
            secondary: LaneCursor(username: "c", direction: 1),
            intent: .filter
        )
        XCTAssertEqual(forward.secondary?.username, "d")
        XCTAssertEqual(forward.primary.username, "a")

        let backward = resolve(
            before: favorites,
            afterRooms: dropped.rooms.map(\.username),
            proposed: dropped.currentUsername,
            secondary: LaneCursor(username: "c", direction: -1),
            intent: .filter
        )
        XCTAssertEqual(backward.secondary?.username, "b")

        var tagged = FeedStore()
        tagged.replace(with: CatalogPage(
            rooms: [
                room("a"), room("b"), room("c"), room("d"),
                room("e", tags: ["nope"])
            ],
            total: 5,
            nextOffset: 5
        ))
        XCTAssertTrue(tagged.focus("a"))
        var hidden = tagged
        hidden.setFilters(blocked: [], only: nil, hidden: [Taste.tagKey("nope")])
        XCTAssertEqual(hidden.rooms.map(\.username), ["a", "b", "c", "d"])
        let reversed = resolve(
            before: tagged,
            afterRooms: hidden.rooms.map(\.username),
            proposed: hidden.currentUsername,
            secondary: LaneCursor(username: "e", direction: 1),
            intent: .filter
        )
        XCTAssertEqual(reversed.secondary?.username, "d", "原方向没有下一间，就反方向找")
        XCTAssertEqual(reversed.primary.username, "a")
    }

    // MARK: 自动跳过

    func testAutoSkipUsesEachLanesOwnDirection() {
        let before = makeFeed(["a", "b", "c", "d"], current: "b")
        let primaryCursor = LaneCursor(username: "b", direction: -1)
        let secondaryCursor = LaneCursor(username: "c", direction: 1)

        let primaryHop = LaneSelection.resolve(
            before: LaneSelection.Snapshot(
                rooms: before.rooms.map(\.username),
                primary: primaryCursor,
                secondary: secondaryCursor,
                focus: .primary
            ),
            afterRooms: before.rooms.map(\.username),
            proposedPrimary: before.currentUsername,
            intent: .move(.primary, primaryCursor.autoDelta)
        )
        XCTAssertEqual(primaryHop.primary.username, "a")
        XCTAssertEqual(primaryHop.primary.autoDelta, -1)
        XCTAssertEqual(primaryHop.secondary?.username, "c", "另一路的方向和房间都不被动")
        XCTAssertEqual(primaryHop.secondary?.autoDelta, 1)
        XCTAssertEqual(primaryHop.reasons[.primary], .move)

        let secondaryHop = LaneSelection.resolve(
            before: LaneSelection.Snapshot(
                rooms: before.rooms.map(\.username),
                primary: primaryCursor,
                secondary: secondaryCursor,
                focus: .secondary
            ),
            afterRooms: before.rooms.map(\.username),
            proposedPrimary: before.currentUsername,
            intent: .move(.secondary, secondaryCursor.autoDelta)
        )
        XCTAssertEqual(secondaryHop.primary.username, "b")
        XCTAssertEqual(secondaryHop.primary.autoDelta, -1)
        XCTAssertEqual(secondaryHop.secondary?.username, "d")
        XCTAssertEqual(secondaryHop.reasons[.secondary], .move)
    }

    func testMoveDoesNotLandOnTheOtherLane() {
        let before = makeFeed(["a", "b", "c", "d"], current: "b")
        var stepped = before
        stepped.step(1, skipping: ["c"])

        let outcome = resolve(
            before: before,
            afterRooms: before.rooms.map(\.username),
            proposed: before.currentUsername,
            secondary: LaneCursor(username: "c"),
            intent: .move(.primary, 1)
        )
        XCTAssertEqual(stepped.currentUsername, "d")
        XCTAssertEqual(outcome.primary.username, stepped.currentUsername)
        XCTAssertEqual(outcome.secondary?.username, "c")

        let blocked = resolve(
            before: makeFeed(["a", "b"], current: "a"),
            afterRooms: ["a", "b"],
            proposed: "a",
            secondary: LaneCursor(username: "b"),
            intent: .move(.primary, 1)
        )
        XCTAssertEqual(blocked.primary.username, "a", "方向上只剩副路时主路停住")
        XCTAssertEqual(blocked.secondary?.username, "b")
        XCTAssertTrue(blocked.reasons.isEmpty)
    }

    // MARK: 单窗

    func testSingleWindowMatchesFeedStoreSelection() {
        func matches(
            _ label: String,
            intent: LaneSelection.Intent,
            before: FeedStore,
            _ mutate: (inout FeedStore) -> Void,
            file: StaticString = #filePath,
            line: UInt = #line
        ) {
            var after = before
            mutate(&after)
            let outcome = resolve(
                before: before,
                afterRooms: after.rooms.map(\.username),
                proposed: after.currentUsername,
                secondary: nil,
                intent: intent
            )
            XCTAssertEqual(outcome.primary.username, after.currentUsername, label, file: file, line: line)
            XCTAssertNil(outcome.secondary, label, file: file, line: line)
            XCTAssertEqual(outcome.focus, .primary, label, file: file, line: line)
        }

        let middle = makeFeed(["a", "b", "c", "d"], current: "b")
        matches("屏蔽主路", intent: .filter, before: middle) { $0.setBlocked(["b"]) }
        matches("屏蔽后一间仍留在主路", intent: .filter, before: middle) { $0.setBlocked(["c"]) }

        let favorites = makeFeed(["a", "b", "c"], current: "b", query: RoomQuery(only: ["a", "b", "c"]))
        matches("取消主路收藏", intent: .filter, before: favorites) { $0.setFilters(blocked: [], only: ["a", "c"]) }

        let searchable = makeFeed(["amy", "bob", "cara", "dave"], current: "bob")
        matches("提交本地搜索", intent: .filter, before: searchable) { $0.setQuery(RoomQuery(text: "a")) }

        matches("整表重载", intent: .reload, before: middle) {
            $0.replace(with: CatalogPage(rooms: [room("x"), room("y")], total: 2, nextOffset: 2))
            $0.setQuery(RoomQuery())
        }
        matches("全站搜索装入", intent: .searchInstall, before: middle) {
            $0.replace(with: SiteSearch.page([room("s1"), room("s2")], blocked: []))
            $0.setQuery(RoomQuery())
        }
        matches("全站搜索退出", intent: .searchExit, before: middle) {
            $0.replace(with: CatalogPage(rooms: [room("a"), room("b"), room("c")], total: 3, nextOffset: 3))
            $0.setQuery(RoomQuery())
        }
        matches("重排", intent: .merge, before: middle) {
            $0.reorderAfterCurrent { $0.reversed() }
        }
        matches("reveal", intent: .reveal("d"), before: middle) { _ = $0.reveal(room("d")) }
        matches("向下划", intent: .move(.primary, 1), before: middle) { _ = $0.step(1) }
        matches("向上划", intent: .move(.primary, -1), before: middle) { _ = $0.step(-1) }
        matches("划动为零", intent: .move(.primary, 0), before: middle) { _ = $0.step(0) }
        matches("划过尽头", intent: .move(.primary, 20), before: middle) { _ = $0.step(20) }

        var empty = FeedStore()
        matches("空列表续页", intent: .blankResume, before: empty) {
            $0.append(CatalogPage(rooms: [room("c"), room("d")], total: 2, nextOffset: 2))
        }

        var last = makeFeed(["solo"], current: "solo")
        matches("屏蔽最后一间", intent: .filter, before: last) { $0.setBlocked(["solo"]) }
    }
}

final class RoomTargetTests: XCTestCase {
    func testRoomTargetMatchesLaneNameAndGeneration() {
        let target = RoomTarget(laneID: .secondary, username: "Bob", generation: 4)
        XCTAssertEqual(target.laneID, .secondary)
        XCTAssertEqual(target.username, "Bob")
        XCTAssertEqual(target.generation, 4)
        XCTAssertTrue(target.matches(laneID: .secondary, username: "bob", generation: 4))
        XCTAssertFalse(target.matches(laneID: .secondary, username: "bob", generation: 5), "代次变了就放弃")
        XCTAssertFalse(target.matches(laneID: .primary, username: "bob", generation: 4), "不是这一路就放弃")
        XCTAssertFalse(target.matches(laneID: .secondary, username: "ann", generation: 4), "房间换了就放弃")
        XCTAssertEqual(RoomTarget.primary(username: "a", generation: 1).laneID, .primary)
    }
}

/// 副路原本空着（列表曾只剩一间）：列表重新有了别的房间时要补上，不能一直空着。
final class LaneSelectionRefillTests: XCTestCase {
    private func snapshot(rooms: [String], primary: String?, secondary: String?) -> LaneSelection.Snapshot {
        LaneSelection.Snapshot(
            rooms: rooms, primary: LaneCursor(username: primary), secondary: LaneCursor(username: secondary), focus: .primary
        )
    }

    func testFilterRelaxRefillsEmptySecondary() {
        let before = snapshot(rooms: ["bob"], primary: "bob", secondary: nil)
        let out = LaneSelection.resolve(
            before: before, afterRooms: ["alice", "alex", "bob", "alina"], proposedPrimary: "bob", intent: .filter
        )
        XCTAssertEqual(out.primary.username, "bob")
        XCTAssertEqual(out.secondary?.username, "alina", "沿副路方向（向后）取主路之后的一间")
    }

    func testMergeRefillsEmptySecondaryEvenWhenPrimaryIsLast() {
        let before = snapshot(rooms: ["bob"], primary: "bob", secondary: nil)
        let out = LaneSelection.resolve(
            before: before, afterRooms: ["alice", "bob"], proposedPrimary: "bob", intent: .merge
        )
        XCTAssertEqual(out.secondary?.username, "alice", "后面没有就取前面一间")
    }

    func testRefillNeverDuplicatesPrimary() {
        let before = snapshot(rooms: ["a"], primary: "a", secondary: nil)
        let out = LaneSelection.resolve(before: before, afterRooms: ["a", "b", "c"], proposedPrimary: "a", intent: .merge)
        XCTAssertNotEqual(out.primary.username, out.secondary?.username)
    }

    func testStillSingleRoomLeavesSecondaryEmpty() {
        let before = snapshot(rooms: ["bob"], primary: "bob", secondary: nil)
        let out = LaneSelection.resolve(before: before, afterRooms: ["bob"], proposedPrimary: "bob", intent: .filter)
        XCTAssertNil(out.secondary?.username)
    }
}

/// 副路房间消失时找替补要排除主路房间，否则主路被挤走（审计 H2）。
final class LaneSelectionReplacementExcludesPrimaryTests: XCTestCase {
    private func snapshot(primary: String, secondary: String, direction: Int) -> LaneSelection.Snapshot {
        LaneSelection.Snapshot(
            rooms: ["a", "b", "c", "d"], primary: LaneCursor(username: primary),
            secondary: LaneCursor(username: secondary, direction: direction), focus: .primary
        )
    }

    func testSecondaryBeforePrimaryMovingBackwardKeepsPrimaryWhenBlocked() {
        // 主 b、副 a、方向 -1，屏蔽 a：副路往前没有、往后第一间是主路 b，必须跳过取 c。
        let out = LaneSelection.resolve(
            before: snapshot(primary: "b", secondary: "a", direction: -1),
            afterRooms: ["b", "c", "d"], proposedPrimary: "b", intent: .filter
        )
        XCTAssertEqual(out.primary.username, "b")
        XCTAssertEqual(out.secondary?.username, "c")
    }

    func testSecondaryAfterPrimaryMovingForwardKeepsPrimaryWhenBlocked() {
        // 主 c、副 b、方向 +1，屏蔽 b：往后第一间是主路 c，必须跳过取 d。
        let out = LaneSelection.resolve(
            before: snapshot(primary: "c", secondary: "b", direction: 1),
            afterRooms: ["a", "c", "d"], proposedPrimary: "c", intent: .filter
        )
        XCTAssertEqual(out.primary.username, "c")
        XCTAssertEqual(out.secondary?.username, "d")
    }

    func testMergePathExcludesPrimaryToo() {
        let out = LaneSelection.resolve(
            before: snapshot(primary: "b", secondary: "a", direction: -1),
            afterRooms: ["b", "c", "d"], proposedPrimary: "b", intent: .merge
        )
        XCTAssertEqual(out.primary.username, "b")
        XCTAssertEqual(out.secondary?.username, "c")
    }
}

/// 双窗共享的「最近划走」：一路划走的房间，另一路向前划时不再落上去。
final class LaneRecentSkipTests: XCTestCase {

    private func recentSnapshot(
        rooms: [String] = ["a", "b", "c", "d", "e"], primary: String, secondary: String,
        recent: Set<String>, primaryPrevious: String? = nil, secondaryPrevious: String? = nil
    ) -> LaneSelection.Snapshot {
        LaneSelection.Snapshot(
            rooms: rooms,
            primary: LaneCursor(username: primary, previous: primaryPrevious),
            secondary: LaneCursor(username: secondary, previous: secondaryPrevious),
            focus: .primary, recent: recent
        )
    }

    private func move(_ before: LaneSelection.Snapshot, _ lane: LaneID, _ delta: Int) -> LaneSelection.Outcome {
        LaneSelection.resolve(
            before: before, afterRooms: before.rooms, proposedPrimary: before.primary.username, intent: .move(lane, delta)
        )
    }

    /// 复现用户实测：一路划走的房间，另一路向前划时不能再落上去。
    func testForwardSwipeSkipsRoomsRecentlyLeftByEitherLane() {
        let before = recentSnapshot(primary: "c", secondary: "a", recent: ["d"])
        let out = move(before, .primary, 1)
        XCTAssertEqual(out.primary.username, "e", "d 刚被划走，向前跳过")
        XCTAssertEqual(out.secondary?.username, "a")
    }

    func testForwardSwipeStillSkipsOtherLaneRoomAndRecent() {
        let before = recentSnapshot(primary: "a", secondary: "b", recent: ["c"])
        XCTAssertEqual(move(before, .primary, 1).primary.username, "d")
        let sec = recentSnapshot(primary: "d", secondary: "a", recent: ["b", "c"])
        XCTAssertEqual(move(sec, .secondary, 1).secondary?.username, "e", "b、c 最近划走，d 是主路，落到 e")
    }

    func testRecentMatchingIsCaseInsensitive() {
        let before = recentSnapshot(rooms: ["a", "b", "C", "d"], primary: "a", secondary: "d", recent: ["c"])
        XCTAssertEqual(move(before, .primary, 1).primary.username, "b")
        let after = recentSnapshot(rooms: ["a", "B", "c", "d"], primary: "a", secondary: "d", recent: ["b", "c"])
        XCTAssertEqual(move(after, .primary, 1).primary.username, "B", "b、c 都最近划走且 d 是副路：放宽后取 b（保留列表里的大小写）")
    }

    /// 向后划回本路自己的上一间允许；再往前的最近房间仍跳过。
    func testBackSwipeToOwnPreviousRoomIsAllowed() {
        let before = recentSnapshot(primary: "a", secondary: "d", recent: ["c", "b"], secondaryPrevious: "c")
        XCTAssertEqual(move(before, .secondary, -1).secondary?.username, "c", "c 是副路自己的上一间")
        let noPrevious = recentSnapshot(primary: "a", secondary: "d", recent: ["c"])
        XCTAssertEqual(move(noPrevious, .secondary, -1).secondary?.username, "b", "c 不是本路上一间，跳过")
    }

    func testOwnPreviousRoomStillRefusedWhenTheOtherLaneIsOnIt() {
        let before = recentSnapshot(primary: "c", secondary: "d", recent: ["c"], secondaryPrevious: "c")
        let out = move(before, .secondary, -1)
        XCTAssertNotEqual(out.secondary?.username, "c", "绝不让两路同房")
    }

    /// 过滤后无处落脚：先放宽最近划走，仍不行才原地；任何情况下两路不同房。
    /// 回归：本路当前房间自己也在最近里（刚从别处划回来）时，往前没有可去处不能落到另一路的房间上。
    func testCurrentRoomInRecentNeverLandsOnTheOtherLane() {
        let before = recentSnapshot(rooms: ["a", "b", "c"], primary: "a", secondary: "b", recent: ["b", "c"], secondaryPrevious: "c")
        let out = move(before, .secondary, -1)
        XCTAssertEqual(out.secondary?.username, "b")
        XCTAssertEqual(out.primary.username, "a")
    }

    func testShortListRelaxesRecentBeforeStayingPut() {
        let before = recentSnapshot(rooms: ["a", "b", "c"], primary: "a", secondary: "b", recent: ["c"])
        let out = move(before, .primary, 1)
        XCTAssertEqual(out.primary.username, "c", "放宽最近划走后可落在 c")
        XCTAssertNotEqual(out.primary.username, out.secondary?.username)
    }

    func testShortListStaysPutWhenNothingElseExists() {
        let before = recentSnapshot(rooms: ["a", "b"], primary: "a", secondary: "b", recent: ["a", "b"])
        let out = move(before, .primary, 1)
        XCTAssertEqual(out.primary.username, "a")
        XCTAssertEqual(out.secondary?.username, "b")
    }

    func testRecentDoesNotAffectDirectReveal() {
        let before = recentSnapshot(primary: "a", secondary: "b", recent: ["d"])
        let out = LaneSelection.resolve(before: before, afterRooms: before.rooms, proposedPrimary: "d", intent: .reveal("d"))
        XCTAssertEqual(out.primary.username, "d")
    }

    func testSingleWindowIgnoresRecent() {
        let before = LaneSelection.Snapshot(
            rooms: ["a", "b", "c"], primary: LaneCursor(username: "a"), secondary: nil, focus: .primary, recent: ["b"]
        )
        let out = LaneSelection.resolve(before: before, afterRooms: before.rooms, proposedPrimary: "b", intent: .move(.primary, 1))
        XCTAssertEqual(out.primary.username, "b", "单窗行为不变")
    }

    // MARK: RecentSkips

    func testRecentSkipsExpireByReadTime() {
        var skips = RecentSkips()
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        skips.record("Alice", at: t0)
        XCTAssertEqual(skips.active(at: t0.addingTimeInterval(30 * 60 - 1)), ["alice"])
        XCTAssertTrue(skips.active(at: t0.addingTimeInterval(30 * 60)).isEmpty, "满 30 分钟恢复可选")
    }

    func testRecentSkipsRerecordRefreshesTimestamp() {
        var skips = RecentSkips()
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        skips.record("a", at: t0)
        skips.record("A", at: t0.addingTimeInterval(20 * 60))
        XCTAssertEqual(skips.count, 1)
        XCTAssertEqual(skips.active(at: t0.addingTimeInterval(40 * 60)), ["a"])
    }

    func testRecentSkipsCapacityDropsOldest() {
        var skips = RecentSkips()
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        for index in 0..<(RecentSkips.capacity + 5) {
            skips.record("r\(index)", at: t0.addingTimeInterval(Double(index)))
        }
        XCTAssertEqual(skips.count, RecentSkips.capacity)
        let active = skips.active(at: t0.addingTimeInterval(300))
        XCTAssertFalse(active.contains("r0"))
        XCTAssertTrue(active.contains("r\(RecentSkips.capacity + 4)"))
    }
}

/// 三路（ios-triple 计划 T-2、修订 v2 的 M4、M5）：任一时刻各路两两不同，替补按 P → S → T，
/// 候选不够时焦点路优先保留，空格留给非焦点路。
final class TripleLaneSelectionTests: XCTestCase {
    private func snap(
        _ rooms: [String], _ primary: String?, _ secondary: String?, _ tertiary: String?,
        focus: LaneID = .primary, directions: (Int, Int, Int) = (1, 1, 1), recent: Set<String> = []
    ) -> LaneSelection.Snapshot {
        LaneSelection.Snapshot(
            rooms: rooms,
            primary: LaneCursor(username: primary, direction: directions.0),
            secondary: LaneCursor(username: secondary, direction: directions.1),
            tertiary: LaneCursor(username: tertiary, direction: directions.2),
            focus: focus, recent: recent
        )
    }

    private func run(
        _ before: LaneSelection.Snapshot, after: [String]? = nil, proposed: String?, _ intent: LaneSelection.Intent
    ) -> LaneSelection.Outcome {
        LaneSelection.resolve(
            before: before, afterRooms: after ?? before.rooms, proposedPrimary: proposed, intent: intent
        )
    }

    private func names(_ out: LaneSelection.Outcome) -> [String?] {
        [out.primary.username, out.secondary?.username, out.tertiary?.username]
    }

    private func assertDistinct(_ out: LaneSelection.Outcome, file: StaticString = #filePath, line: UInt = #line) {
        let taken = names(out).compactMap { $0?.lowercased() }
        XCTAssertEqual(Set(taken).count, taken.count, "三路房间重复 \(names(out))", file: file, line: line)
    }

    // MARK: 路身份

    func testLaneIDsActiveCountAndRoleMapping() {
        XCTAssertEqual(LaneID.allCases, [.primary, .secondary, .tertiary])
        XCTAssertEqual(LaneID.active(count: 0), [.primary], "夹紧到至少 1 路")
        XCTAssertEqual(LaneID.active(count: 2), [.primary, .secondary])
        XCTAssertEqual(LaneID.active(count: 3), [.primary, .secondary, .tertiary])
        XCTAssertEqual(LaneID.active(count: 9), [.primary, .secondary, .tertiary], "最多 3 路")
        for id in LaneID.allCases { XCTAssertEqual(id.role.laneID, id) }
        XCTAssertEqual(LaneID.tertiary.role, .tertiary)
        XCTAssertEqual(PlaybackLaneRole.allCases.map(\.label), ["primary", "secondary", "tertiary"])
        XCTAssertEqual(LaneID.tertiary.label, "tertiary")
    }

    // MARK: 屏蔽

    func testBlockingPrimaryYieldsPastBothOtherLanes() {
        let before = snap(["a", "b", "c", "d", "e"], "a", "b", "c")
        let out = run(before, after: ["b", "c", "d", "e"], proposed: "b", .filter)
        XCTAssertEqual(names(out), ["d", "b", "c"], "主路落到副路那间时沿自己的方向越过副路和第三路")
        XCTAssertNil(out.reasons[.secondary])
        XCTAssertNil(out.reasons[.tertiary], "未受影响的路不结算")
        XCTAssertEqual(out.reasons[.primary], .filter)
    }

    func testBlockingSecondaryReplacementSkipsTertiaryRoom() {
        let before = snap(["a", "b", "c", "d", "e"], "a", "b", "c")
        let out = run(before, after: ["a", "c", "d", "e"], proposed: "a", .filter)
        XCTAssertEqual(names(out), ["a", "d", "c"], "副路替补沿方向找，跳过第三路正在播的 c")
        XCTAssertEqual(out.reasons[.secondary], .filter)
        XCTAssertNil(out.reasons[.tertiary])
    }

    func testBlockingTertiaryFindsNextFreeRoomAlongItsDirection() {
        let forward = run(snap(["a", "b", "c", "d", "e"], "a", "b", "c"), after: ["a", "b", "d", "e"], proposed: "a", .filter)
        XCTAssertEqual(names(forward), ["a", "b", "d"])
        let backward = run(
            snap(["a", "b", "c", "d", "e"], "d", "e", "c", directions: (1, 1, -1)),
            after: ["a", "b", "d", "e"], proposed: "d", .filter
        )
        XCTAssertEqual(names(backward), ["d", "e", "b"], "第三路向前方移动时先找前面")
    }

    // MARK: 取消收藏、列表只剩 1 到 3 个

    func testUnfavoritingTwoLanesWithTwoRoomsLeftLeavesANonFocusLaneEmpty() {
        let before = snap(["a", "b", "c", "d"], "a", "b", "c")
        let out = run(before, after: ["a", "d"], proposed: "a", .filter)
        XCTAssertEqual(names(out), ["a", "d", nil], "替补按 P → S → T：副路拿到 d，第三路没有可去处")
        XCTAssertEqual(out.reasons[.tertiary], .empty)
        assertDistinct(out)
    }

    func testFocusLaneKeepsARoomWhenCandidatesRunShort() {
        let before = snap(["a", "b", "c", "d"], "a", "b", "c", focus: .tertiary)
        let out = run(before, after: ["a", "d"], proposed: "a", .filter)
        XCTAssertEqual(out.focus, .tertiary)
        XCTAssertNotNil(out.tertiary?.username, "焦点路优先保留")
        XCTAssertEqual(names(out).compactMap { $0 }.count, 2)
        XCTAssertEqual(out.primary.username, "a")
        XCTAssertNil(out.secondary?.username, "空格留给非焦点路")
        assertDistinct(out)
    }

    func testOnlyOneRoomGoesToTheFocusLane() {
        for focus in LaneID.allCases {
            let out = run(snap(["a", "b", "c"], "a", "b", "c", focus: focus), after: ["b"], proposed: "b", .filter)
            XCTAssertEqual(out.focus, focus)
            for id in LaneID.allCases {
                let name = id == .primary ? out.primary.username : id == .secondary ? out.secondary?.username : out.tertiary?.username
                XCTAssertEqual(name, id == focus ? "b" : nil, "焦点 \(focus) 时 \(id)")
            }
        }
    }

    func testExactlyThreeRoomsStayDistinctAfterMerge() {
        let before = snap(["a", "b", "c", "d"], "a", "b", "c")
        let out = run(before, after: ["c", "a", "b"], proposed: "a", .merge)
        XCTAssertEqual(names(out), ["a", "b", "c"], "合并、重排：房间还在就不动")
        XCTAssertTrue(out.reasons.isEmpty)
    }

    func testMergeRefillsEmptyTertiaryWithoutDuplicating() {
        let before = snap(["a", "b"], "a", "b", nil)
        let out = run(before, after: ["a", "b", "c", "d"], proposed: "a", .merge)
        XCTAssertEqual(names(out), ["a", "b", "c"])
        XCTAssertEqual(out.reasons[.tertiary], .merge)
    }

    // MARK: 空列表与续页恢复

    func testEmptyListClearsAllThreeAndBlankResumeRefillsInOrder() {
        let cleared = run(snap(["a", "b", "c"], "a", "b", "c", focus: .secondary), after: [], proposed: nil, .filter)
        XCTAssertEqual(names(cleared), [nil, nil, nil])
        XCTAssertEqual(cleared.focus, .secondary)
        let blank = snap([], nil, nil, nil, focus: .secondary)
        let resumed = LaneSelection.resolveFeedChange(before: blank, afterRooms: ["a", "b", "c", "d"], proposedPrimary: "a", intent: .merge)
        XCTAssertEqual(names(resumed), ["a", "b", "c"])
        XCTAssertEqual(resumed.reasons[.tertiary], .resume)
    }

    // MARK: 搜索装入与退出、切来源

    func testSearchInstallExitAndReloadChainLanesAfterPrimary() {
        let install = run(snap(["a", "b", "c"], "b", "c", "a"), after: ["s1", "b", "s3", "s4"], proposed: "b", .searchInstall)
        XCTAssertEqual(names(install), ["b", "s3", "s4"])
        let exit = run(snap(["s1", "b", "s3", "s4"], "b", "s3", "s4"), after: ["a", "b", "c", "d"], proposed: "a", .searchExit)
        XCTAssertEqual(names(exit), ["a", "b", "c"])
        XCTAssertEqual(exit.reasons[.tertiary], .sourceChange)
        let nearEnd = run(snap(["a", "b", "c"], "a", "b", "c"), proposed: "b", .reload)
        XCTAssertEqual(names(nearEnd), ["b", "c", nil], "不绕回列表开头（与双窗一致）")
    }

    // MARK: reveal

    func testRevealTargetOnAnotherLaneOnlySwitchesFocus() {
        let before = snap(["a", "b", "c", "d"], "a", "b", "c", recent: ["c"])
        let onTertiary = run(before, proposed: "c", .reveal("c"))
        XCTAssertEqual(onTertiary.focus, .tertiary)
        XCTAssertEqual(names(onTertiary), ["a", "b", "c"])
        XCTAssertTrue(onTertiary.reasons.isEmpty, "只切焦点，各路都不结算")
        let fromTertiary = run(snap(["a", "b", "c", "d"], "a", "b", "c", focus: .tertiary), proposed: "b", .reveal("b"))
        XCTAssertEqual(fromTertiary.focus, .secondary)
        XCTAssertEqual(names(fromTertiary), ["a", "b", "c"])
    }

    func testRevealNewTargetGoesToPrimaryAndKeepsOthersDistinct() {
        let before = snap(["a", "b", "c", "d"], "a", "b", "c", focus: .tertiary, recent: ["d"])
        let out = run(before, proposed: "d", .reveal("d"))
        XCTAssertEqual(out.focus, .primary)
        XCTAssertEqual(names(out), ["d", "b", "c"], "reveal 直达不受最近划走约束")
    }

    // MARK: 划动与最近划走

    func testMoveNeverLandsOnEitherOtherLane() {
        let before = snap(["a", "b", "c", "d", "e"], "a", "b", "c")
        XCTAssertEqual(names(run(before, proposed: nil, .move(.secondary, 1))), ["a", "d", "c"])
        XCTAssertEqual(names(run(before, proposed: nil, .move(.primary, 1))), ["d", "b", "c"])
        let stuck = run(before, proposed: nil, .move(.tertiary, -1))
        XCTAssertEqual(names(stuck), ["a", "b", "c"], "前面两间都被占：原地不动")
        XCTAssertTrue(stuck.reasons.isEmpty)
    }

    func testRecentSkipsAreSharedByAllThreeLanes() {
        let before = snap(["a", "b", "c", "d", "e", "f", "g"], "a", "c", "e", recent: ["b", "d"])
        let out = run(before, proposed: nil, .move(.primary, 1))
        XCTAssertEqual(out.primary.username, "f", "b、d 最近划走，c、e 被另两路占着")
        let relaxed = run(snap(["a", "b", "c", "d"], "a", "b", "c", recent: ["d"]), proposed: nil, .move(.primary, 1))
        XCTAssertEqual(relaxed.primary.username, "d", "无处落脚时先放宽最近划走")
        assertDistinct(relaxed)
    }

    // MARK: 开路补位

    func testFillPlacesNewLanesAfterTheirPredecessorAndRespectsRecent() {
        let two = LaneSelection.Snapshot(
            rooms: ["a", "b", "c", "d", "e"], primary: LaneCursor(username: "a"),
            secondary: LaneCursor(username: "b"), tertiary: LaneCursor(), focus: .secondary
        )
        let out = run(two, proposed: "a", .fill)
        XCTAssertEqual(names(out), ["a", "b", "c"])
        XCTAssertEqual(out.focus, .secondary, "补位不动焦点")
        XCTAssertNil(out.reasons[.primary])
        XCTAssertNil(out.reasons[.secondary])
        var recent = two
        recent.recent = ["c"]
        XCTAssertEqual(run(recent, proposed: "a", .fill).tertiary?.username, "d", "自动补位避开最近划走")
        let single = LaneSelection.Snapshot(
            rooms: ["a", "b", "c"], primary: LaneCursor(username: "c"),
            secondary: LaneCursor(), tertiary: LaneCursor(), focus: .primary
        )
        let atEnd = run(single, proposed: "c", .fill)
        XCTAssertEqual(atEnd.primary.username, "c")
        XCTAssertNotNil(atEnd.secondary?.username, "主路在最后一间时补位往前找")
        XCTAssertNotNil(atEnd.tertiary?.username)
        assertDistinct(atEnd)
    }

    /// 确定性：同样输入两次结果相同，且两路时与旧双窗规则一致（第三路为 nil 不影响）。
    func testTwoLaneSnapshotWithoutTertiaryKeepsDualBehavior() {
        let dual = LaneSelection.Snapshot(
            rooms: ["a", "b", "c"], primary: LaneCursor(username: "a"), secondary: LaneCursor(username: "b"), focus: .primary
        )
        let out = run(dual, after: ["b", "c"], proposed: "b", .filter)
        XCTAssertEqual(out.primary.username, "c")
        XCTAssertEqual(out.secondary?.username, "b")
        XCTAssertNil(out.tertiary)
        XCTAssertEqual(run(dual, after: ["b", "c"], proposed: "b", .filter), out)
    }
}

/// 竖屏多格的格子高度（ios-triple 修订 v2 M12）：`(H − (N−1)×间距) / N`，三格每格不足 160 点时最多 2 路。
final class LaneLayoutTests: XCTestCase {
    func testPaneHeightAndThreeLaneFit() {
        XCTAssertEqual(LaneLayout.gap, 2)
        XCTAssertEqual(LaneLayout.paneHeight(stage: 874, lanes: 3), 290, accuracy: 0.001, "iPhone 17 Pro：(874 − 4) / 3")
        XCTAssertEqual(LaneLayout.paneHeight(stage: 874, lanes: 2), 436, accuracy: 0.001)
        XCTAssertEqual(LaneLayout.paneHeight(stage: 874, lanes: 1), 874, accuracy: 0.001)
        XCTAssertEqual(LaneLayout.paneHeight(stage: 0, lanes: 3), 1, accuracy: 0.001, "不出现 0 或负高度")
        XCTAssertTrue(LaneLayout.fitsThreeLanes(stageHeight: 874))
        XCTAssertTrue(LaneLayout.fitsThreeLanes(stageHeight: 3 * 160 + 4))
        XCTAssertFalse(LaneLayout.fitsThreeLanes(stageHeight: 3 * 160 + 3), "每格低于 160 点")
        XCTAssertFalse(LaneLayout.fitsThreeLanes(stageHeight: 400))
    }
}
