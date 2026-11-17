import XCTest
@testable import VelaCore

/// 推荐优化四项：反馈分级、常见标签降权、主播亲和度、按口味定向拉候选。
final class RecommendTuningTests: XCTestCase {
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    func room(_ name: String, viewers: Int = 100, tags: [String] = ["x"], gender: Gender? = nil) -> Room {
        Room(username: name, viewers: viewers, gender: gender, tags: tags, show: .publicShow)
    }

    func key(_ tag: String) -> String { Taste.tagKey(tag) }

    func meanRank(_ out: [Room], _ prefix: String) -> Double {
        let ranks = out.enumerated().filter { $0.element.username.hasPrefix(prefix) }.map { Double($0.offset) }
        return ranks.reduce(0, +) / Double(ranks.count)
    }

    // MARK: 1. 反馈分级

    func testGradeTiers() {
        for s in [0.0, 1, 2.99] { XCTAssertEqual(Feedback.grade(watched: s), Feedback(hit: 0, miss: 0.3), "\(s)") }
        for s in [3.0, 10, 19.99] { XCTAssertEqual(Feedback.grade(watched: s), .none, "\(s)") }
        XCTAssertEqual(Feedback.grade(watched: 20).hit, 1, accuracy: 1e-9)
        XCTAssertEqual(Feedback.grade(watched: 60).hit, 1.5, accuracy: 1e-9)
        XCTAssertEqual(Feedback.grade(watched: 180).hit, 2, accuracy: 1e-9)
        XCTAssertEqual(Feedback.grade(watched: 3_600).hit, 2, accuracy: 1e-9)
        XCTAssertEqual(Feedback.grade(watched: .nan), .none)
        XCTAssertEqual(Feedback.grade(watched: 60).miss, 0)
        // 20 秒以上连续、单调不减。
        var last = 0.0
        for s in stride(from: 20.0, through: 400, by: 7) {
            let h = Feedback.grade(watched: s).hit
            XCTAssertGreaterThanOrEqual(h, last)
            last = h
        }
        XCTAssertGreaterThan(Feedback.followHit, Feedback.maxHit)
        XCTAssertEqual(Taste.followStep, 3)
    }

    func testDailyOutcomeKeepsTwentyAndThreeSecondCutoffs() {
        XCTAssertEqual(Feedback.dailyOutcome(watched: 1), false)
        XCTAssertNil(Feedback.dailyOutcome(watched: 3))
        XCTAssertNil(Feedback.dailyOutcome(watched: 19.9))
        XCTAssertEqual(Feedback.dailyOutcome(watched: 20), true)
    }

    func testGradedRecordingWeightsAndDaily() {
        let r = room("a", tags: ["x"], gender: .female)
        let skip = Taste().recording(room: r, watched: 1, source: .guess, now: now)
        XCTAssertEqual(skip.posterior(key("x"), now: now).beta, 1.3, accuracy: 1e-9)
        XCTAssertEqual(skip.posterior(Taste.genderKey(.female), now: now).beta, 1.15, accuracy: 1e-9)
        XCTAssertEqual(skip.beliefs[key("x")]?.misses, 1)
        XCTAssertEqual(skip.daily[Taste.dayKey(now)], DayTally(hit: 0, miss: 1))
        let browse = Taste().recording(room: r, watched: 10, source: .guess, now: now)
        XCTAssertTrue(browse.beliefs.isEmpty)
        XCTAssertTrue(browse.daily.isEmpty)
        XCTAssertNotNil(browse.lastSeen("a"))
        let long = Taste().recording(room: r, watched: 60, source: .guess, now: now)
        XCTAssertEqual(long.posterior(key("x"), now: now).alpha, 2.5, accuracy: 1e-9)
        XCTAssertEqual(long.daily[Taste.dayKey(now)], DayTally(hit: 1, miss: 0))
        let other = Taste().recording(room: r, watched: 180, source: .popular, now: now)
        XCTAssertEqual(other.posterior(key("x"), now: now).alpha, 2, accuracy: 1e-9)
        XCTAssertTrue(other.daily.isEmpty)
        let paused = Taste().settingPaused(true).recording(room: r, watched: 180, source: .guess, now: now)
        XCTAssertTrue(paused.beliefs.isEmpty)
        XCTAssertNotNil(paused.lastSeen("a"))
    }

    func testFollowAndRejectSteps() {
        let r = room("a")
        XCTAssertEqual(Taste().following(room: r, now: now).posterior(key("x"), now: now).alpha, 4, accuracy: 1e-9)
        XCTAssertEqual(Taste().rejecting(room: r, source: .guess, now: now).posterior(key("x"), now: now).beta, 4, accuracy: 1e-9)
        let long = Taste().recording(room: r, watched: 10_000, source: .guess, now: now).posterior(key("x"), now: now).alpha
        XCTAssertGreaterThan(Taste().following(room: r, now: now).posterior(key("x"), now: now).alpha, long)
    }

    // MARK: 2. 常见标签降权

    func testRarityWeights() {
        let rooms = (0..<100).map { room("r\($0)", tags: ["teen"] + ($0 < 5 ? ["piano"] : []) + ["u\($0)"]) }
        let rarity = TagRarity(rooms: rooms)
        XCTAssertEqual(rarity.documentFrequency(key("teen")), 100)
        XCTAssertEqual(rarity.weight(key("teen")), TagRarity.floor, accuracy: 1e-9)
        XCTAssertEqual(rarity.weight(key("u3")), 1, accuracy: 1e-9)
        XCTAssertEqual(rarity.weight(key("never")), 1, accuracy: 1e-9)
        XCTAssertGreaterThan(rarity.weight(key("piano")), 0.5)
        XCTAssertLessThan(rarity.weight(key("piano")), 1)
        XCTAssertEqual(TagRarity(rooms: []).weight(key("x")), 1)
        XCTAssertEqual(TagRarity(rooms: [room("a")]).weight(key("x")), 1)
        // 同房间重复标签只计一次。
        XCTAssertEqual(TagRarity(rooms: [room("a", tags: ["X", "x"]), room("b", tags: ["y"])]).documentFrequency(key("x")), 1)
    }

    func testRecordingSplitsEvidenceByRarity() {
        let rooms = (0..<100).map { room("r\($0)", tags: ["teen"] + ($0 < 5 ? ["piano"] : [])) }
        let rarity = TagRarity(rooms: rooms)
        let t = Taste().recording(room: rooms[0], watched: 180, source: .guess, now: now, rarity: rarity)
        let teen = t.posterior(key("teen"), now: now).alpha - 1
        let piano = t.posterior(key("piano"), now: now).alpha - 1
        XCTAssertEqual(teen, 2 * TagRarity.floor, accuracy: 1e-9)
        XCTAssertEqual(piano, 2 * rarity.weight(key("piano")), accuracy: 1e-9)
        XCTAssertGreaterThan(piano, teen * 4)
        let skip = Taste().recording(room: rooms[50], watched: 1, source: .guess, now: now, rarity: rarity)
        XCTAssertEqual(skip.posterior(key("teen"), now: now).beta, 1 + 0.3 * TagRarity.floor, accuracy: 1e-9)
    }

    func testRarityShrinksCommonTagInfluenceOnRanking() {
        // teen 与 piano 的口味证据一样强；teen 出现在 40% 的候选里，piano 只在 10%。
        var taste = Taste()
        for _ in 0..<6 {
            taste = taste.recording(room: room("x", tags: ["teen"]), watched: 30, source: .guess, now: now)
            taste = taste.recording(room: room("x", tags: ["piano"]), watched: 30, source: .guess, now: now)
        }
        let rooms = (0..<40).map { room("A\($0)", tags: ["teen", "a\($0)"]) }
            + (0..<10).map { room("B\($0)", tags: ["piano", "b\($0)"]) }
            + (0..<50).map { room("C\($0)", tags: ["c\($0)"]) }
        let signals = RankSignals(rarity: TagRarity(rooms: rooms))
        var gapPlain = 0.0, gapRare = 0.0
        for seed: UInt64 in 1...20 {
            let plain = Recommender.recommend(rooms, taste: taste, now: now, seed: seed)
            let rare = Recommender.recommend(rooms, taste: taste, now: now, seed: seed, signals: signals)
            gapPlain += meanRank(plain, "A") - meanRank(plain, "B")
            gapRare += meanRank(rare, "A") - meanRank(rare, "B")
            XCTAssertEqual(rare.count, rooms.count)
        }
        // 不降权时 A、B 不分先后；降权后稀有的 piano 房间明显在前。
        XCTAssertLessThan(abs(gapPlain / 20), 6)
        XCTAssertGreaterThan(gapRare / 20, 10)
    }

    func testEmptySignalsKeepLegacyOrder() {
        let rooms = (0..<30).map { room("r\($0)", viewers: $0, tags: ["t\($0 % 4)"]) }
        let taste = Taste().recording(room: room("x", tags: ["t1"]), watched: 30, source: .guess, now: now)
        XCTAssertEqual(
            Recommender.recommend(rooms, taste: taste, now: now, seed: 7),
            Recommender.recommend(rooms, taste: taste, now: now, seed: 7, signals: .none)
        )
    }

    // MARK: 3. 主播亲和度

    func ledger(_ name: String, seconds: TimeInterval, visits: Int) -> WatchLedger {
        var l = WatchLedger()
        for i in 0..<max(visits, 1) {
            let each = visits > 0 ? seconds / Double(visits) : seconds
            l = l.recording(username: name, seconds: each, start: Double(i) * 7_200, now: Double(i) * 7_200 + each)
        }
        return l
    }

    func testAffinityBoundsAndZeroCases() {
        let heavy = ledger("Star", seconds: 100_000, visits: 40)
        XCTAssertEqual(StreamerAffinity.score(ledger: heavy, username: "star"), StreamerAffinity.cap, accuracy: 1e-9)
        XCTAssertLessThan(StreamerAffinity.cap, Recommender.favoriteBoost)
        XCTAssertEqual(StreamerAffinity.score(ledger: heavy, username: "nobody"), 0)
        XCTAssertEqual(StreamerAffinity.score(ledger: heavy, username: "STAR", blocked: ["star"]), 0)
        XCTAssertEqual(StreamerAffinity.score(ledger: WatchLedger(), username: "star"), 0)
        let light = StreamerAffinity.score(ledger: ledger("s", seconds: 120, visits: 1), username: "s")
        let more = StreamerAffinity.score(ledger: ledger("s", seconds: 1_200, visits: 3), username: "s")
        XCTAssertGreaterThan(light, 0)
        XCTAssertGreaterThan(more, light)
        XCTAssertLessThan(more, StreamerAffinity.cap)
    }

    func testAffinityRanksBelowFavoritesAndSkipsBlocked() {
        let l = ledger("loved", seconds: 100_000, visits: 40).recording(username: "banned", seconds: 100_000, start: 0, now: 1)
        let rooms = [room("plain"), room("loved"), room("fav"), room("banned")]
        for seed: UInt64 in 1...10 {
            let signals = RankSignals(ledger: l, blocked: ["banned"])
            let out = Recommender.recommend(rooms, taste: Taste(), now: now, seed: seed, favorites: ["fav"], signals: signals)
            // 同一个标签同一个样本，只差加分：收藏 > 亲和度 > 无记录与屏蔽（后两者按名字定序）。
            XCTAssertEqual(out.map(\.username), ["fav", "loved", "banned", "plain"], "seed \(seed)")
        }
    }

    // MARK: 4. 定向候选

    func trained(_ hits: [String: Int], misses: [String: Int] = [:]) -> Taste {
        var t = Taste()
        for (tag, n) in hits { for _ in 0..<n { t = t.recording(room: room("x", tags: [tag]), watched: 30, source: .guess, now: now) } }
        for (tag, n) in misses { for _ in 0..<n { t = t.recording(room: room("x", tags: [tag]), watched: 1, source: .guess, now: now) } }
        return t
    }

    func testFavoriteTagsSelection() {
        let taste = trained(
            ["piano": 8, "cosplay": 5, "asmr": 4, "yoga": 3, "teen": 10, "once": 1, "mixed": 3, "nope": 9],
            misses: ["mixed": 6]
        ).vetoing(key("nope"), now: now)
        let rooms = (0..<50).map { room("r\($0)", tags: ["teen"] + ($0 < 3 ? ["piano"] : [])) }
        let rarity = TagRarity(rooms: rooms)
        XCTAssertEqual(TasteTargeting.favoriteTags(taste: taste, now: now, rarity: rarity), ["piano", "cosplay", "asmr"])
        XCTAssertEqual(TasteTargeting.favoriteTags(taste: taste, now: now), ["teen", "piano", "cosplay"])
        XCTAssertEqual(TasteTargeting.favoriteTags(taste: taste, now: now, rarity: rarity, limit: 2), ["piano", "cosplay"])
        XCTAssertEqual(TasteTargeting.favoriteTags(taste: trained(["once": 1]), now: now), [])
        XCTAssertEqual(TasteTargeting.favoriteTags(taste: Taste(), now: now), [])
    }

    func testTargetedQueries() {
        let base = CatalogQuery(gender: .female, region: .northAmerica)
        let qs = TasteTargeting.queries(for: ["piano", "#Cosplay", "piano"], base: base)
        XCTAssertEqual(qs.map(\.tag), ["piano", "cosplay"])
        XCTAssertTrue(qs.allSatisfy { $0.limit == CatalogQuery.maxPageSize && $0.offset == 0 && $0.gender == .female && $0.region == .northAmerica })
        XCTAssertTrue(qs[0].url().absoluteString.contains("hashtags=piano"))
        XCTAssertEqual(TasteTargeting.queries(for: ["piano"], base: CatalogQuery(tag: "user")), [])
        XCTAssertEqual(TasteTargeting.queries(for: []), [])
    }

    func testMergingKeepsRegularOrderAndDedupes() {
        let regular = [room("a"), room("B"), room("c")]
        let pages = [[room("b"), room("d")], [room("D"), room("e"), room("A")]]
        XCTAssertEqual(TasteTargeting.merging(regular, extra: pages).map(\.username), ["a", "B", "c", "d", "e"])
        XCTAssertEqual(TasteTargeting.merging(regular, extra: []).map(\.username), ["a", "B", "c"])
        XCTAssertEqual(TasteTargeting.merging([], extra: pages).map(\.username), ["b", "d", "e", "A"])
    }
}
