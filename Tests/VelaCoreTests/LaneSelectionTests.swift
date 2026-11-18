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
