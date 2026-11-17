import XCTest
@testable import VelaCore

final class FeedRankingTests: XCTestCase {
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    func room(_ name: String, viewers: Int = 100, tags: [String] = [], gender: Gender? = .female, secs: Int? = nil) -> Room {
        Room(username: name, viewers: viewers, gender: gender, tags: tags, show: .publicShow, secondsOnline: secs)
    }

    func names(_ rooms: [Room]) -> [String] { rooms.map(\.username) }

    // MARK: 来源

    func testPopularKeepsOrderAndNewestSortsWithMissingLast() {
        let rooms = [room("a", secs: 500), room("b", secs: nil), room("c", secs: 60), room("d", secs: 500), room("e", secs: nil)]
        XCTAssertEqual(names(Recommender.arrange(rooms, source: .popular, taste: Taste(), now: now, seed: 1)), ["a", "b", "c", "d", "e"])
        XCTAssertEqual(names(Recommender.arrange(rooms, source: .popular, taste: Taste(), now: now, seed: 1)), ["a", "b", "c", "d", "e"])
        XCTAssertEqual(names(Recommender.arrange(rooms, source: .fresh, taste: Taste(), now: now, seed: 1)), ["c", "a", "d", "b", "e"])
    }

    func testPageLimits() {
        XCTAssertEqual(FeedSource.popular.pageLimit, 40)
        XCTAssertEqual(FeedSource.guess.pageLimit, 90)
        XCTAssertEqual(FeedSource.fresh.pageLimit, 90)
        XCTAssertEqual(FeedSource.popular.pageCount, 1)
        XCTAssertEqual(FeedSource.guess.pageCount, 3)
        XCTAssertEqual(FeedSource.fresh.pageCount, 3)
        XCTAssertLessThanOrEqual(FeedSource.allCases.map(\.pageLimit).max() ?? 0, CatalogQuery.maxPageSize)
    }

    // MARK: 打分

    func testHotIsLogarithmicAndCapped() {
        XCTAssertLessThan(Recommender.hot(room("x", viewers: 10_000)) - Recommender.hot(room("y", viewers: 1_000)), 0.25)
        XCTAssertEqual(Recommender.hot(room("z", viewers: 5_000_000)), 1, accuracy: 1e-9)
        XCTAssertEqual(Recommender.hot(room("w", viewers: 0)), 0)
    }

    func testSeenPenaltyFadesAfterSixHours() {
        let r = room("a")
        let recent = Taste().marking(seen: "A", now: now.addingTimeInterval(-3_600))
        let old = Taste().marking(seen: "a", now: now.addingTimeInterval(-7 * 3_600))
        XCTAssertGreaterThan(Recommender.seenPenalty(r, taste: recent, now: now), 0.3)
        XCTAssertEqual(Recommender.seenPenalty(r, taste: old, now: now), 0)
    }

    // MARK: 汤普森采样排序

    /// 命中 / 落空若干次的画像。
    func trained(_ hits: [String: Int] = [:], misses: [String: Int] = [:]) -> Taste {
        var t = Taste()
        for (tag, n) in hits { for _ in 0..<n { t = t.recording(room: room("x", tags: [tag], gender: nil), watched: 30, source: .guess, now: now) } }
        for (tag, n) in misses { for _ in 0..<n { t = t.recording(room: room("x", tags: [tag], gender: nil), watched: 1, source: .guess, now: now) } }
        return t
    }

    func testBetaSamplerStaysInRangeAndMatchesMean() {
        var rng = SplitMix64(seed: 5)
        let draws = (0..<4_000).map { _ in BetaSampler.sample(alpha: 9, beta: 3, using: &rng) }
        XCTAssertTrue(draws.allSatisfy { $0 > 0 && $0 < 1 })
        XCTAssertEqual(draws.reduce(0, +) / Double(draws.count), 0.75, accuracy: 0.02)
    }

    func testRecommendIsDeterministicForSeedAndKeepsAllRooms() {
        let rooms = (0..<60).map { room("r\($0)", viewers: $0 * 10, tags: ["t\($0 % 5)"]) }
        let a = Recommender.recommend(rooms, taste: Taste(), now: now, seed: 42)
        let b = Recommender.recommend(rooms, taste: Taste(), now: now, seed: 42)
        let c = Recommender.recommend(rooms, taste: Taste(), now: now, seed: 43)
        XCTAssertEqual(a, b)
        XCTAssertNotEqual(names(a), names(c))
        XCTAssertEqual(Set(names(a)), Set(names(rooms)))
        XCTAssertEqual(a.count, rooms.count)
    }

    func testLikedTagsRankAheadAndDislikedBehind() {
        let taste = trained(["fav": 12], misses: ["meh": 12])
        let rooms = (0..<20).map { room("L\($0)", tags: ["fav"]) }
            + (0..<20).map { room("M\($0)", tags: ["meh"]) }
            + (0..<20).map { room("N\($0)", tags: ["new\($0)"]) }
        // 新标签有探索机会，所以不要求绝对分层；看平均名次：喜欢的最靠前，落空的最靠后。
        func meanRank(_ out: [Room], _ prefix: String) -> Double {
            let ranks = out.enumerated().filter { $0.element.username.hasPrefix(prefix) }.map { Double($0.offset) }
            return ranks.reduce(0, +) / Double(ranks.count)
        }
        for seed: UInt64 in 1...10 {
            let out = Recommender.recommend(rooms, taste: taste, now: now, seed: seed)
            XCTAssertLessThan(meanRank(out, "L"), 20, "seed \(seed)")
            XCTAssertGreaterThan(meanRank(out, "M"), 40, "seed \(seed)")
            XCTAssertGreaterThan(out.prefix(10).filter { $0.username.hasPrefix("L") }.count, 3, "seed \(seed)")
        }
    }

    func testUnknownTagsStillSurfaceAmongUndecidedOnes() {
        // 没有任何证据时每个标签的样本都来自同一个先验，各房间有同等机会排第一。
        let rooms = (0..<8).map { room("r\($0)", tags: ["t\($0)"]) }
        let firsts = Set((1...60).map { Recommender.recommend(rooms, taste: Taste(), now: now, seed: UInt64($0)).first!.username })
        XCTAssertGreaterThan(firsts.count, 5)
    }

    func testRecommendKeepsVetoedRoomsVisibilityIsTheQueryJob() {
        let taste = Taste().vetoing(Taste.tagKey("nope"), now: now)
        let rooms = [room("a", tags: ["ok"]), room("b", tags: ["Nope", "ok"]), room("c", tags: ["ok"])]
        XCTAssertEqual(Set(names(Recommender.recommend(rooms, taste: taste, now: now, seed: 1))), ["a", "b", "c"])
        let query = RoomQuery(hidden: taste.vetoedKeys)
        XCTAssertEqual(rooms.filter(query.matches).map(\.username), ["a", "c"])
    }

    func testSeenRoomsMoveBackButNeverDisappear() {
        let rooms = (0..<10).map { room("r\($0)", viewers: 1_000 - $0) }
        var taste = Taste()
        for r in rooms { taste = taste.marking(seen: r.username, now: now.addingTimeInterval(-60)) }
        XCTAssertEqual(Recommender.recommend(rooms, taste: taste, now: now, seed: 3).count, 10)
        let one = Taste().marking(seen: "r0", now: now.addingTimeInterval(-60))
        var lastCount = 0
        for seed: UInt64 in 1...50 where Recommender.recommend(Array(rooms.prefix(4)), taste: one, now: now, seed: seed).last?.username == "r0" { lastCount += 1 }
        XCTAssertGreaterThan(lastCount, 25)
    }

    func testHotnessOnlyBreaksTies() {
        // 同一个标签样本相同，热度才起作用；口味证据明显不同时热度压不过。
        let same = [room("quiet", viewers: 10, tags: ["x"], gender: nil), room("busy", viewers: 20_000, tags: ["x"], gender: nil)]
        XCTAssertEqual(Recommender.recommend(same, taste: Taste(), now: now, seed: 2).first?.username, "busy")
        let taste = trained(["liked": 10])
        let mixed = [room("busy", viewers: 20_000, tags: ["other"], gender: nil), room("quiet", viewers: 10, tags: ["liked"], gender: nil)]
        let wins = (1...40).filter { Recommender.recommend(mixed, taste: taste, now: now, seed: UInt64($0)).first?.username == "quiet" }.count
        XCTAssertGreaterThan(wins, 30)
    }

    func testSmallListsNeverCrash() {
        for n in 0..<6 {
            let rooms = (0..<n).map { room("r\($0)", viewers: 100 - $0) }
            XCTAssertEqual(Set(names(Recommender.recommend(rooms, taste: Taste(), now: now, seed: 9))), Set(names(rooms)), "n=\(n)")
        }
    }

    func testArrangeDispatchesBySource() {
        let rooms = [room("a", secs: 500), room("b", secs: 60)]
        XCTAssertEqual(names(Recommender.arrange(rooms, source: .fresh, taste: Taste(), now: now, seed: 1)), ["b", "a"])
        XCTAssertEqual(names(Recommender.arrange(rooms, source: .favorites, taste: Taste(), now: now, seed: 1)), ["a", "b"])
    }

    func testStoredSourceMapsLegacyRecommended() {
        XCTAssertEqual(FeedSource.restore("recommended"), .guess)
        XCTAssertEqual(FeedSource.restore("guess"), .guess)
        XCTAssertEqual(FeedSource.restore("favorites"), .favorites)
        XCTAssertNil(FeedSource.restore("bogus"))
        XCTAssertNil(FeedSource.restore(nil))
    }

    // MARK: 推荐理由

    func testReasonNamesBestTagsFavoritesAndFallback() {
        let taste = trained(["blonde": 6, "latina": 3, "asmr": 1], misses: ["bad": 8])
        let r = room("a", tags: ["asmr", "latina", "blonde", "bad"])
        XCTAssertEqual(Recommender.reason(for: r, taste: taste, favorites: [], now: now), "因为你常看 blonde、latina")
        XCTAssertEqual(Recommender.reason(for: room("a", tags: ["bad", "zzz"]), taste: taste, favorites: [], now: now), "试试新口味")
        XCTAssertEqual(Recommender.reason(for: room("A", tags: []), taste: taste, favorites: ["a"], now: now), "你收藏的主播")
        let veto = taste.vetoing(Taste.tagKey("blonde"), now: now)
        XCTAssertEqual(Recommender.reason(for: r, taste: veto, favorites: [], now: now), "因为你常看 latina、asmr")
    }

    // MARK: 热门关联

    func testRelatedPicksUnseenSimilarRooms() {
        let cur = room("cur", tags: ["a", "b", "c"])
        let pool = [
            room("near1", tags: ["a", "b", "c"]),
            room("near2", tags: ["a", "b"]),
            room("far", tags: ["a", "x", "y", "z"]),
            room("none", tags: ["q"]),
            room("seen", tags: ["a", "b", "c"]),
            room("cur", tags: ["a", "b", "c"])
        ]
        let prefs = Taste().marking(seen: "seen", now: now)
        let out = Recommender.related(to: cur, among: pool, taste: prefs, limit: 3)
        XCTAssertEqual(out, ["near1", "near2", "far"])
        XCTAssertEqual(Recommender.related(to: room("t", tags: []), among: pool, taste: prefs), [])
    }

    func testPromoteAfterCurrentKeepsHistoryAndCurrent() {
        let rooms = (0..<8).map { room("r\($0)") }
        var store = FeedStore(catalog: rooms, currentUsername: "r2")
        let before = store.catalog
        store.promoteAfterCurrent(["R6", "r4", "r1", "r2", "ghost", "r6"])
        XCTAssertEqual(names(store.catalog), ["r0", "r1", "r2", "r6", "r4", "r3", "r5", "r7"])
        XCTAssertEqual(store.currentUsername, "r2")
        XCTAssertEqual(store.index, 2)
        XCTAssertEqual(Array(store.catalog.prefix(3)), Array(before.prefix(3)))
        XCTAssertEqual(Set(names(store.catalog)), Set(names(before)))
        store.step(-1)
        XCTAssertEqual(store.current?.username, "r1")
    }

    func testPromoteWithoutCurrentOrMatchesIsNoop() {
        var store = FeedStore(catalog: (0..<3).map { room("r\($0)") }, currentUsername: nil)
        store.promoteAfterCurrent(["r2"])
        XCTAssertEqual(names(store.catalog), ["r0", "r1", "r2"])
        var last = FeedStore(catalog: (0..<3).map { room("r\($0)") }, currentUsername: "r2")
        last.promoteAfterCurrent(["r0"])
        XCTAssertEqual(names(last.catalog), ["r0", "r1", "r2"])
    }

    // MARK: 翻页去重

    func testAppendDedupesAndSeenDoesNotEmptyTheFeed() {
        var prefs = Taste()
        let first = (0..<5).map { room("r\($0)") }
        let second = (3..<9).map { room("r\($0)") }
        for r in first + second { prefs = prefs.marking(seen: r.username, now: now) }
        var store = FeedStore()
        store.replace(with: CatalogPage(rooms: Recommender.recommend(first, taste: prefs, now: now, seed: 1), total: 9, nextOffset: 5))
        store.append(CatalogPage(rooms: Recommender.recommend(second, taste: prefs, now: now, seed: 2), total: 9, nextOffset: 11))
        XCTAssertEqual(store.catalog.count, 9)
        XCTAssertEqual(Set(names(store.catalog)).count, 9)
        XCTAssertFalse(store.rooms.isEmpty)
    }

    // MARK: 持久化

    func tempURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("vela-feed-test-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent("prefs.json")
    }

    /// 旧版偏好文件只读：测试里直接写文件。
    private func writeLegacy(_ prefs: Preferences, to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(prefs).write(to: url)
    }

    func testStoreReadsLegacyFileAndMissingFile() throws {
        let url = tempURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = PreferenceStore(url: url)
        XCTAssertEqual(store.load(now: now), Preferences())
        let p = Preferences(weights: ["t:x": Affinity(value: 0.5, at: now.timeIntervalSince1970)], seen: ["a": now.timeIntervalSince1970])
        try writeLegacy(p, to: url)
        XCTAssertEqual(store.load(now: now), p)
    }

    func testCorruptFileFallsBackToEmpty() throws {
        let url = tempURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("{not json".utf8).write(to: url)
        XCTAssertEqual(PreferenceStore(url: url).load(now: now), Preferences())
    }

    func testCapsEvictWeakestWeightsAndOldestSeen() {
        let stamp = now.timeIntervalSince1970
        var weights: [String: Affinity] = [:]
        for i in 0..<700 { weights["t:tag\(i)"] = Affinity(value: Double(i + 1) / 100, at: stamp) }
        var seen: [String: TimeInterval] = [:]
        for i in 0..<1_300 { seen["u\(i)"] = stamp + Double(i) / 10 }
        let p = Preferences(weights: weights, seen: seen).capped(now: now.addingTimeInterval(130))
        XCTAssertEqual(p.weights.count, Preferences.maxWeights)
        XCTAssertNotNil(p.weights["t:tag699"])
        XCTAssertNil(p.weights["t:tag0"])
        XCTAssertEqual(p.seen.count, Preferences.maxSeen)
        XCTAssertNotNil(p.seen["u1299"])
        XCTAssertNil(p.seen["u0"])
        let reloaded = p.capped(now: now.addingTimeInterval(130 + Preferences.seenRetention))
        XCTAssertTrue(reloaded.seen.isEmpty)
    }

    func testLegacyFileSizeStaysBoundedOnLoad() throws {
        let url = tempURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let stamp = now.timeIntervalSince1970
        let weights = Dictionary(uniqueKeysWithValues: (0..<400).map { ("t:tag-\($0)", Affinity(value: 1, at: stamp)) })
        let seen = Dictionary(uniqueKeysWithValues: (0..<3_000).map { ("someusername\($0)", stamp) })
        try writeLegacy(Preferences(weights: weights, seen: seen), to: url)
        let loaded = PreferenceStore(url: url).load(now: now)
        XCTAssertEqual(loaded.weights.count, 400)
        XCTAssertEqual(loaded.seen.count, Preferences.maxSeen)
    }
}
