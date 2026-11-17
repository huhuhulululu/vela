import XCTest
@testable import VelaCore

final class TasteTests: XCTestCase {
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    var cal: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }

    func room(_ name: String, tags: [String] = ["x"], gender: Gender? = .female) -> Room {
        Room(username: name, viewers: 10, gender: gender, tags: tags, show: .publicShow)
    }

    func key(_ tag: String) -> String { Taste.tagKey(tag) }

    // MARK: 命中与落空

    func testHitMissAndTheDeadZoneBetweenThreeAndTwentySeconds() {
        let r = room("a", tags: ["x", "y"])
        let hit = Taste().recording(room: r, watched: 25, source: .guess, now: now)
        let hitEvidence = Feedback.grade(watched: 25).hit
        XCTAssertEqual(hit.posterior(key("x"), now: now).alpha, 1 + hitEvidence, accuracy: 1e-9)
        XCTAssertEqual(hit.beliefs[key("x")]?.hits, 1)
        XCTAssertEqual(hit.posterior(Taste.genderKey(.female), now: now).alpha, 1 + hitEvidence / 2, accuracy: 1e-9)
        let miss = Taste().recording(room: r, watched: 1, source: .guess, now: now)
        XCTAssertEqual(miss.posterior(key("x"), now: now).beta, 1 + Feedback.skipMiss, accuracy: 1e-9)
        XCTAssertEqual(miss.beliefs[key("x")]?.misses, 1)
        for dwell in [3.0, 10, 19.9] {
            let mid = Taste().recording(room: r, watched: dwell, source: .guess, now: now)
            XCTAssertTrue(mid.beliefs.isEmpty, "dwell \(dwell)")
            XCTAssertTrue(mid.daily.isEmpty)
            XCTAssertNotNil(mid.lastSeen("A"))
        }
        XCTAssertNotNil(Taste().recording(room: r, watched: 20, source: .guess, now: now).beliefs[key("x")])
    }

    func testOtherSourcesLearnAtHalfWeightAndStayOutOfDailyStats() {
        let r = room("a")
        let t = Taste().recording(room: r, watched: 30, source: .popular, now: now)
        XCTAssertEqual(t.posterior(key("x"), now: now).alpha, 1 + Feedback.grade(watched: 30).hit / 2, accuracy: 1e-9)
        XCTAssertTrue(t.daily.isEmpty)
    }

    func testRecordingDoesNotMutateInput() {
        let t = Taste()
        _ = t.recording(room: room("a"), watched: 30, source: .guess, now: now)
        XCTAssertEqual(t, Taste())
    }

    func testNotInterestedIsAStrongMissAndCountsInDaily() {
        let t = Taste().rejecting(room: room("a"), source: .guess, now: now)
        XCTAssertEqual(t.posterior(key("x"), now: now).beta, 1 + Taste.rejectStep, accuracy: 1e-9)
        XCTAssertEqual(t.daily[Taste.dayKey(now)], DayTally(hit: 0, miss: 1))
        XCTAssertNotNil(t.lastSeen("a"))
        XCTAssertTrue(Taste().rejecting(room: room("a"), source: .popular, now: now).daily.isEmpty)
    }

    func testFollowingIsStrongerThanOneHit() {
        let f = Taste().following(room: room("a"), now: now).posterior(key("x"), now: now).alpha
        let h = Taste().recording(room: room("a"), watched: 90, source: .guess, now: now).posterior(key("x"), now: now).alpha
        XCTAssertGreaterThan(f, h)
    }

    // MARK: 暂停学习

    func testPausedStopsLearningButKeepsSeen() {
        let p = Taste().settingPaused(true)
        let r = room("a")
        for t in [p.recording(room: r, watched: 60, source: .guess, now: now), p.rejecting(room: r, source: .guess, now: now), p.following(room: r, now: now)] {
            XCTAssertTrue(t.beliefs.isEmpty)
            XCTAssertTrue(t.daily.isEmpty)
            XCTAssertTrue(t.paused)
        }
        XCTAssertNotNil(p.recording(room: r, watched: 60, source: .guess, now: now).lastSeen("a"))
        let trained = Taste().recording(room: r, watched: 60, source: .guess, now: now).settingPaused(true)
        XCTAssertGreaterThan(trained.mean(key("x"), now: now), 0.5)
    }

    // MARK: 衰减

    func testEvidenceHalvesEverySevenDaysTowardThePrior() {
        let t = Taste().boosting(key("x"), now: now)
        XCTAssertEqual(t.posterior(key("x"), now: now).alpha, 1 + Taste.boostStep, accuracy: 1e-9)
        XCTAssertEqual(t.posterior(key("x"), now: now.addingTimeInterval(Taste.halfLife)).alpha, 1 + Taste.boostStep / 2, accuracy: 1e-9)
        let old = t.posterior(key("x"), now: now.addingTimeInterval(70 * 86_400))
        XCTAssertEqual(old.alpha, 1, accuracy: 0.01)
        XCTAssertEqual(old.beta, 1, accuracy: 1e-9)
    }

    func testNewEvidenceDecaysOldBeforeAdding() {
        let t = Taste().boosting(key("x"), now: now)
        let later = now.addingTimeInterval(Taste.halfLife)
        let next = t.recording(room: room("a"), watched: 30, source: .guess, now: later)
        XCTAssertEqual(next.posterior(key("x"), now: later).alpha, 1 + Taste.boostStep / 2 + Feedback.grade(watched: 30).hit, accuracy: 1e-9)
    }

    // MARK: 面板操作

    func testBoostVetoRemove() {
        var t = Taste().vetoing(key("x"), now: now)
        XCTAssertTrue(t.isVetoed(key("x")))
        XCTAssertTrue(t.isVetoed(room("a", tags: ["X"])))
        t = t.boosting(key("x"), now: now)
        XCTAssertFalse(t.isVetoed(key("x")))
        XCTAssertGreaterThan(t.mean(key("x"), now: now), 0.7)
        XCTAssertNil(t.removing(key("x")).beliefs[key("x")])
        // 不想看的不衰减。
        let v = Taste().vetoing(key("y"), now: now)
        XCTAssertTrue(v.isVetoed(key("y")))
        XCTAssertTrue(v.capped(now: now.addingTimeInterval(400 * 86_400)).isVetoed(key("y")))
    }

    func testResetAndEraseKeepOnlyWhatTheyShould() {
        let t = Taste().recording(room: room("a"), watched: 30, source: .guess, now: now).settingPaused(true)
        let reset = t.resettingProfile()
        XCTAssertTrue(reset.beliefs.isEmpty)
        XCTAssertTrue(reset.daily.isEmpty)
        XCTAssertNotNil(reset.lastSeen("a"))
        XCTAssertTrue(reset.paused)
        let erased = t.erased()
        XCTAssertEqual(erased, Taste(paused: true))
        XCTAssertNil(erased.lastSeen("a"))
    }

    // MARK: 迁移

    func testMigrationKeepsOldProfileAndSeen() {
        let old = Preferences(
            weights: [
                "t:blonde": Affinity(value: 2, at: now.timeIntervalSince1970),
                "t:meh": Affinity(value: -1, at: now.timeIntervalSince1970),
                "g:f": Affinity(value: 0.9, at: now.timeIntervalSince1970)
            ],
            seen: ["abc": now.timeIntervalSince1970]
        )
        let t = Taste(migrating: old)
        XCTAssertEqual(t.posterior("t:blonde", now: now).alpha, 5, accuracy: 1e-9)
        XCTAssertGreaterThan(t.mean("t:blonde", now: now), 0.75)
        XCTAssertLessThan(t.mean("t:meh", now: now), 0.2)
        XCTAssertEqual(t.posterior("g:f", now: now).alpha, 1 + 0.9 / 0.3 * 0.5, accuracy: 1e-9)
        XCTAssertNotNil(t.lastSeen("abc"))
    }

    func testStoreMigratesOnceThenOwnsItsFile() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("vela-taste-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let legacy = dir.appendingPathComponent("feed-prefs.json")
        let url = dir.appendingPathComponent("feed-taste.json")
        let prefs = Preferences(weights: ["t:blonde": Affinity(value: 2, at: now.timeIntervalSince1970)])
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try JSONEncoder().encode(prefs).write(to: legacy)
        let store = TasteStore(url: url, legacyURL: legacy)
        let migrated = store.load(now: now)
        XCTAssertGreaterThan(migrated.mean("t:blonde", now: now), 0.7)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        XCTAssertTrue(store.save(Taste(), now: now))
        XCTAssertTrue(store.load(now: now).beliefs.isEmpty, "已有新文件后不再迁移")
        XCTAssertTrue(store.eraseLegacy())
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacy.path))
        let t = Taste().recording(room: room("a"), watched: 30, source: .guess, now: now)
        XCTAssertTrue(store.save(t, now: now))
        XCTAssertEqual(store.load(now: now), t.capped(now: now))
    }

    func testCorruptOversizedAndUnwritable() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("vela-taste-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("t.json")
        try Data("{nope".utf8).write(to: url)
        XCTAssertEqual(TasteStore(url: url).load(now: now), Taste())
        try Data(repeating: 0x20, count: Preferences.maxFileBytes + 1).write(to: url)
        XCTAssertEqual(TasteStore(url: url).load(now: now), Taste())
        XCTAssertFalse(TasteStore(url: URL(fileURLWithPath: "/dev/null/x/t.json")).save(Taste()))
    }

    // MARK: 上限

    func testBeliefCapKeepsStrongestAndVetoed() {
        var t = Taste().vetoing(key("keepme"), now: now)
        for i in 0..<700 {
            t = t.recording(room: room("r\(i)", tags: ["tag\(i)"], gender: nil), watched: 30, source: .guess, now: now)
        }
        t = t.boosting(key("tag699"), now: now)
        XCTAssertLessThanOrEqual(t.beliefs.count, Taste.maxBeliefs)
        XCTAssertTrue(t.isVetoed(key("keepme")))
        XCTAssertNotNil(t.beliefs[key("tag699")])
    }

    func testLongNamesClippedAndFileStaysSmall() throws {
        let long = String(repeating: "x", count: 500)
        let t = Taste().recording(room: Room(username: long, viewers: 1, tags: [long], show: .publicShow), watched: 30, source: .guess, now: now)
        XCTAssertTrue(t.beliefs.keys.allSatisfy { $0.count <= Preferences.maxNameLength + 2 })
        var big = Taste()
        for i in 0..<2_000 { big = big.vetoing("t:some-fairly-long-tag-name-number-\(i)", now: now) }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("vela-taste-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertTrue(TasteStore(url: url).save(big, now: now))
        XCTAssertLessThan(try Data(contentsOf: url).count, 150_000)
        XCTAssertEqual(TasteStore(url: url).load(now: now).beliefs.count, Taste.maxBeliefs)
    }

    // MARK: 每日命中率

    func testDailyCountsCapAtThirtyDays() {
        var t = Taste()
        for day in 0..<45 {
            t = t.recording(room: room("a"), watched: 30, source: .guess, now: now.addingTimeInterval(Double(day) * 86_400))
        }
        XCTAssertEqual(t.daily.count, Taste.maxDays)
        XCTAssertNotNil(t.daily[Taste.dayKey(now.addingTimeInterval(44 * 86_400))])
        XCTAssertNil(t.daily[Taste.dayKey(now)])
        let two = t.recording(room: room("a"), watched: 1, source: .guess, now: now.addingTimeInterval(44 * 86_400))
        XCTAssertEqual(two.daily[Taste.dayKey(now.addingTimeInterval(44 * 86_400))], DayTally(hit: 1, miss: 1))
    }

    func testSummaryHitRateNeedsEnoughData() {
        var t = Taste()
        for _ in 0..<4 { t = t.recording(room: room("a"), watched: 30, source: .guess, now: now) }
        for _ in 0..<5 { t = t.recording(room: room("a"), watched: 1, source: .guess, now: now) }
        XCTAssertNil(t.summary(now: now, calendar: cal).hitRate)
        t = t.recording(room: room("a"), watched: 1, source: .guess, now: now.addingTimeInterval(-86_400))
        let s = t.summary(now: now, calendar: cal)
        XCTAssertEqual(s.hitRate ?? -1, 0.4, accuracy: 1e-9)
        XCTAssertEqual(s.week.count, 7)
        XCTAssertEqual(s.week.last?.hit, 4)
        XCTAssertEqual(s.week[5].miss, 1)
        // 8 天前的不进 7 天窗口。
        let old = Taste().recording(room: room("a"), watched: 30, source: .guess, now: now.addingTimeInterval(-8 * 86_400))
        XCTAssertEqual(old.summary(now: now, calendar: cal).week.reduce(0) { $0 + $1.total }, 0)
    }

    // MARK: 面板数据

    func testSummarySplitsLikedDislikedAndGender() {
        var t = Taste()
        for _ in 0..<5 { t = t.recording(room: room("a", tags: ["loved"]), watched: 30, source: .guess, now: now) }
        for _ in 0..<15 { t = t.recording(room: room("b", tags: ["hated"]), watched: 1, source: .guess, now: now) }
        t = t.vetoing(key("vetoed"), now: now).recording(room: room("c", tags: ["ok"], gender: nil), watched: 30, source: .guess, now: now)
        let s = t.summary(now: now, calendar: cal)
        XCTAssertEqual(s.liked.map(\.name), ["loved", "ok"])
        XCTAssertEqual(s.liked.first?.hits, 5)
        XCTAssertEqual(Set(s.disliked.map(\.name)), ["hated", "vetoed"])
        XCTAssertEqual(s.genders.first?.key, Taste.genderKey(.female))
        XCTAssertEqual(s.tagCount, 4)
    }

    func testSuggestedTagsAreCommonAndNew() {
        let rooms = [room("a", tags: ["Blonde", "latina"]), room("b", tags: ["blonde", "milf"]), room("c", tags: ["blonde", "latina", "known"])]
        let t = Taste().boosting(key("known"), now: now)
        XCTAssertEqual(t.suggestedTags(from: rooms), ["blonde", "latina", "milf"])
        XCTAssertEqual(t.suggestedTags(from: rooms, limit: 2), ["blonde", "latina"])
    }
}
