import XCTest
@testable import VelaCore

private func room(_ name: String, show: ShowState = .publicShow, tags: [String] = []) -> Room {
    Room(username: name, viewers: 5, tags: tags, show: show)
}

/// 同一时间只有一批：并发数、结果归属与「在途」标记。
@available(macOS 10.15, *)
@MainActor
final class SupersedingRunnerTests: XCTestCase {
    private final class Probe {
        var running = 0
        var peak = 0
        var gates: [CheckedContinuation<Void, Never>] = []
        var currentAtEnd: [Int: Bool] = [:]
    }

    func testOverlappingRunsNeverRunConcurrentlyAndAllCallersGetLatestResult() async {
        let runner = SupersedingRunner<Int>()
        let probe = Probe()
        let work: @MainActor (Int) async -> Int = { id in
            probe.running += 1
            probe.peak = max(probe.peak, probe.running)
            // 第一批在这里挂起，直到被取消。
            if id == 1 {
                while !Task.isCancelled { try? await Task.sleep(nanoseconds: 2_000_000) }
            }
            probe.currentAtEnd[id] = runner.isCurrent(id)
            probe.running -= 1
            return id * 10
        }
        async let a = runner.run(work)
        try? await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertTrue(runner.isRunning)
        async let b = runner.run(work)
        let (ra, rb) = await (a, b)
        XCTAssertEqual(probe.peak, 1, "同一时间只有一批")
        XCTAssertEqual(ra, 20, "被取代的调用方拿最新一批的结果")
        XCTAssertEqual(rb, 20)
        XCTAssertEqual(probe.currentAtEnd[1], false, "旧批次不再是当前批次，不应写回")
        XCTAssertEqual(probe.currentAtEnd[2], true)
        XCTAssertFalse(runner.isRunning, "全部结束后不再有在途批次")
    }

    func testIsRunningStaysTrueUntilLatestFinishes() async {
        let runner = SupersedingRunner<Int>()
        let first = Task { @MainActor in await runner.run { _ in try? await Task.sleep(nanoseconds: 30_000_000); return 1 } }
        try? await Task.sleep(nanoseconds: 5_000_000)
        XCTAssertTrue(runner.isRunning)
        _ = await first.value
        XCTAssertFalse(runner.isRunning)
    }

    /// iOS 挂起：`cancel()` 取消在途批次，批次看到取消后提前结束；之后的新批照常跑。
    func testCancelStopsInflightBatchAndLaterRunsStillWork() async {
        let runner = SupersedingRunner<Int>()
        let probe = Probe()
        let first = Task { @MainActor in
            await runner.run { _ in
                while !Task.isCancelled { try? await Task.sleep(nanoseconds: 2_000_000) }
                probe.currentAtEnd[1] = Task.isCancelled
                return -1
            }
        }
        try? await Task.sleep(nanoseconds: 10_000_000)
        XCTAssertTrue(runner.isRunning)
        runner.cancel()
        let cancelled = await first.value
        XCTAssertEqual(cancelled, -1)
        XCTAssertEqual(probe.currentAtEnd[1], true, "批次内看得到取消")
        XCTAssertFalse(runner.isRunning)
        let next = await runner.run { id in id * 10 }
        XCTAssertEqual(next, 20, "取消后新批照常执行")
    }
}

final class ReviewFixTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    // F3：追加页不续期早先页面的房间。
    func testAppendedPageDoesNotRefreshEarlierRooms() {
        var catalog = KnownCatalog()
        catalog.record([room("early")], firstPage: true, at: t0)
        catalog.record([room("late")], firstPage: false, at: t0.addingTimeInterval(150))
        let now = t0.addingTimeInterval(FavoriteCache.ttl + 10)
        XCTAssertEqual(catalog.usable(now: now).map(\.room.username), ["late"], "第一页已过期，不因追加页被续期")
        let plan = FavoriteResolver.plan(favorites: ["early", "late"], catalog: catalog, cache: FavoriteCache(), now: now)
        XCTAssertEqual(plan.pending, ["early"])
        XCTAssertEqual(plan.fromCatalog["late"]?.at, t0.addingTimeInterval(150))
    }

    func testCatalogStatusesKeepTheirOwnTimeInCache() {
        var catalog = KnownCatalog()
        catalog.record([room("a")], firstPage: true, at: t0)
        let plan = FavoriteResolver.plan(favorites: ["a"], catalog: catalog, cache: FavoriteCache(), now: t0.addingTimeInterval(60))
        let cache = FavoriteCache().storing(stamped: plan.fromCatalog)
        XCTAssertNotNil(cache.fresh(for: "a", now: t0.addingTimeInterval(FavoriteCache.ttl)))
        XCTAssertNil(cache.fresh(for: "a", now: t0.addingTimeInterval(FavoriteCache.ttl + 1)), "按房间自己被取回的时间过期")
    }

    func testOlderCatalogStampDoesNotOverwriteNewerCacheEntry() {
        let cache = FavoriteCache().storing(["a": .offline], at: t0.addingTimeInterval(100))
        let stamped = ["a": FavoritePlan.Stamped(status: .notFound, at: t0)]
        XCTAssertEqual(cache.storing(stamped: stamped).status(for: "a"), .offline)
    }

    // F4：收藏来源只显示当前收藏名单。
    func testFavoritesFeedOnlyShowsCurrentFavoritesAfterBlockAndUnblock() {
        var store = FeedStore(query: RoomQuery(only: ["a", "b"]))
        store.replace(with: CatalogPage(rooms: [room("A"), room("B")], total: 2, nextOffset: 2))
        // 屏蔽 A 同时移出收藏。
        store.setFilters(blocked: ["a"], only: ["b"])
        XCTAssertEqual(store.rooms.map(\.username), ["B"])
        // 解除屏蔽：A 不在收藏名单里，不该重新出现。
        store.setFilters(blocked: [], only: ["b"])
        XCTAssertEqual(store.rooms.map(\.username), ["B"])
        // 名单变化同步：再收藏回来才显示。
        store.setFilters(blocked: [], only: ["a", "b"])
        XCTAssertEqual(store.rooms.map(\.username), ["A", "B"])
    }

    func testSetBlockedKeepsAllowList() {
        var store = FeedStore(query: RoomQuery(only: ["a"]))
        store.replace(with: CatalogPage(rooms: [room("a"), room("z")], total: 2, nextOffset: 2))
        store.setBlocked([])
        XCTAssertEqual(store.rooms.map(\.username), ["a"])
    }

    // F5：从收藏夹点击切换，搜索条件不能让播放的变成别的房间。
    func testRevealClearsSearchThatHidesTarget() {
        var store = FeedStore(query: RoomQuery(text: "blonde"))
        store.replace(with: CatalogPage(rooms: [room("x", tags: ["blonde"]), room("fav", tags: ["tall"])], total: 2, nextOffset: 2))
        XCTAssertEqual(store.rooms.map(\.username), ["x"])
        XCTAssertTrue(store.reveal(room("fav", tags: ["tall"])))
        XCTAssertEqual(store.current?.username, "fav")
        XCTAssertEqual(store.query.text, "")
    }

    func testRevealInsertsMissingRoomAndKeepsSearchWhenItMatches() {
        var store = FeedStore(query: RoomQuery(text: "blonde"))
        store.replace(with: CatalogPage(rooms: [room("x", tags: ["blonde"])], total: 1, nextOffset: 1))
        XCTAssertTrue(store.reveal(room("new", tags: ["blonde"])))
        XCTAssertEqual(store.current?.username, "new")
        XCTAssertEqual(store.query.text, "blonde", "目标本就匹配时不动搜索条件")
    }

    // U3：恢复 = 撤销「不想看」。
    func testRestoreUndoesVetoAndLearnedDislike() {
        let now = t0
        var taste = Taste().boosting("t:a", now: now)
        taste = taste.vetoing("t:a", now: now)
        XCTAssertTrue(taste.isVetoed("t:a"))
        XCTAssertTrue(taste.summary(now: now).disliked.contains { $0.key == "t:a" })
        let restored = taste.restoring("t:a", now: now)
        XCTAssertFalse(restored.isVetoed("t:a"))
        XCTAssertFalse(restored.summary(now: now).disliked.contains { $0.key == "t:a" })
        XCTAssertGreaterThan(restored.mean("t:a", now: now), 0.5, "之前的命中证据保留")
        // 学出来的不想看（没有显式过滤）：落空证据清掉。
        var learned = Taste()
        for _ in 0..<15 { learned = learned.recording(room: room("r", tags: ["z"]), watched: 1, source: .guess, now: now) }
        XCTAssertTrue(learned.summary(now: now).disliked.contains { $0.key == "t:z" })
        XCTAssertFalse(learned.restoring("t:z", now: now).summary(now: now).disliked.contains { $0.key == "t:z" })
        XCTAssertEqual(Taste().restoring("t:none", now: now), Taste())
    }

    // U5：画像变化后对当前位置之后立即重排，当前及之前不动。
    func testTasteChangeReordersOnlyAfterCurrent() {
        let rooms = (0..<8).map { room("r\($0)", tags: [$0 % 2 == 0 ? "even" : "odd"]) }
        var store = FeedStore()
        store.replace(with: CatalogPage(rooms: rooms, total: 8, nextOffset: 8))
        store.focus("r2")
        let before = store.catalog.map(\.username)
        let taste = Taste().vetoing("t:odd", now: t0)
        store.reorderAfterCurrent {
            Recommender.arrange($0, source: .guess, taste: taste, now: t0, seed: 1)
        }
        let after = store.catalog.map(\.username)
        XCTAssertEqual(Array(after.prefix(3)), Array(before.prefix(3)), "当前及之前不动")
        XCTAssertEqual(store.currentUsername, "r2")
        XCTAssertEqual(Set(after), Set(before), "排序不删房间")
        store.setFilters(blocked: [], only: nil, hidden: taste.vetoedKeys)
        XCTAssertFalse(store.rooms.dropFirst(3).contains { Int($0.username.dropFirst())! % 2 == 1 }, "不想看的标签在可见列表里被过滤")
    }

    // U6：来源切换时同名房间也要结算。
    func testSourceChangeSettlesDwellEvenWhenFirstRoomHasSameName() {
        XCTAssertFalse(DwellTracker.shouldSettle(before: "a", after: "a", sourceChanged: false))
        XCTAssertTrue(DwellTracker.shouldSettle(before: "a", after: "a", sourceChanged: true))
        XCTAssertTrue(DwellTracker.shouldSettle(before: "a", after: "b", sourceChanged: false))
        XCTAssertFalse(DwellTracker.shouldSettle(before: nil, after: nil, sourceChanged: true))
        // 追踪器本身：同名房间 switchTo 会结算旧来源下的停留，并重新开始。
        var tracker = DwellTracker()
        _ = tracker.switchTo(room("a"), now: t0)
        tracker.setActive(true, now: t0)
        let settled = tracker.switchTo(room("a"), now: t0.addingTimeInterval(40))
        XCTAssertEqual(settled?.dwell ?? 0, 40, accuracy: 0.001)
        XCTAssertEqual(tracker.dwell(now: t0.addingTimeInterval(40)), 0, accuracy: 0.001)
    }
}
