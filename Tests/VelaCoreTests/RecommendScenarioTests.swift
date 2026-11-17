import XCTest
@testable import VelaCore

/// 综合场景：大量快速划走 + 少量长停留，长停留房间共有的稀有标签对应的房间应明显上升。
final class RecommendScenarioTests: XCTestCase {
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    let seed: UInt64 = 2_026

    /// 240 个候选：teen / 18 / new 几乎每个房间都有，中频标签三选一，piano 只在 10 个房间（P0…P9）。
    func candidates() -> [Room] {
        let mids = ["blonde", "latina", "asian"]
        func tags(_ i: Int, rare: Bool) -> [String] {
            var t: [String] = []
            if i % 10 != 0 { t.append("teen") }
            if i % 7 != 0 { t.append("18") }
            if i % 5 != 0 { t.append("new") }
            t.append(mids[i % 3])
            if rare { t.append("piano") }
            t.append("own\(i)")
            return t
        }
        let piano = (0..<10).map { Room(username: "P\($0)", viewers: 50, gender: .female, tags: tags($0, rare: true), show: .publicShow) }
        let rest = (0..<230).map { Room(username: "R\($0)", viewers: 50 + $0, gender: .female, tags: tags($0 + 10, rare: false), show: .publicShow) }
        return piano + rest
    }

    func rank(of names: Set<String>, in out: [Room]) -> Double {
        let ranks = out.enumerated().filter { names.contains($0.element.username) }.map { Double($0.offset) }
        return ranks.reduce(0, +) / Double(ranks.count)
    }

    func testLongStaysLiftRoomsSharingTheirRareTag() {
        let rooms = candidates()
        let rarity = TagRarity(rooms: rooms)
        let signals = RankSignals(rarity: rarity)
        let unwatched = Set((3..<10).map { "P\($0)" })
        let before = rank(of: unwatched, in: Recommender.recommend(rooms, taste: Taste(), now: now, seed: seed, signals: signals))

        var taste = Taste()
        var clock = now.addingTimeInterval(-3_600)
        for r in rooms.filter({ $0.username.hasPrefix("R") }).prefix(120) {
            clock.addTimeInterval(2)
            taste = taste.recording(room: r, watched: 1.5, source: .guess, now: clock, rarity: rarity)
        }
        for name in ["P0", "P1", "P2"] {
            clock.addTimeInterval(240)
            taste = taste.recording(room: rooms.first { $0.username == name }!, watched: 240, source: .guess, now: clock, rarity: rarity)
        }
        let out = Recommender.recommend(rooms, taste: taste, now: now, seed: seed, signals: signals)
        let after = rank(of: unwatched, in: out)

        XCTAssertEqual(out.count, rooms.count)
        XCTAssertLessThan(after, 25, "before \(before) after \(after)")
        XCTAssertLessThan(after * 3, before, "before \(before) after \(after)")
        // 常见标签证据很小：120 次快速划走的落空只积了一点点，不至于把所有房间压到底。
        XCTAssertLessThan(taste.posterior(Taste.tagKey("teen"), now: now).beta - 1, 120 * 0.3 * 0.2)
        // 仍保留探索：前 30 名里不全是 piano 房间。
        XCTAssertGreaterThan(out.prefix(30).filter { !$0.tags.contains("piano") }.count, 10)
        // 定向拉候选会选 piano，而不是常见标签。
        XCTAssertEqual(TasteTargeting.favoriteTags(taste: taste, now: now, rarity: rarity).first, "piano")
    }
}
