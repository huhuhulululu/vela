import XCTest
@testable import VelaCore

/// `FeedStore.rooms` 改为缓存后，必须始终等于「重新全量过滤」的结果。
final class FeedCacheTests: XCTestCase {
    /// 固定种子的 SplitMix64，保证对拍序列可复现。
    private struct SeededRNG: RandomNumberGenerator {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
    }

    private static let names = (0..<40).map { "user\($0)" }
    private static let tags = ["Asian", "latina", "Nope", "cosplay", "music", "chat"]
    private static let shows: [ShowState] = [.publicShow, .publicShow, .publicShow, .privateShow, .away]

    private func randomRoom(_ rng: inout SeededRNG) -> Room {
        var name = Self.names.randomElement(using: &rng)!
        if Bool.random(using: &rng) { name = name.uppercased() } // 大小写混用，覆盖去重/匹配的小写逻辑
        let tagCount = Int.random(in: 0...3, using: &rng)
        return Room(
            username: name,
            subject: Bool.random(using: &rng) ? "hello \(name)" : "",
            viewers: Int.random(in: 0...500, using: &rng),
            gender: Gender.allCases.randomElement(using: &rng),
            tags: (0..<tagCount).map { _ in Self.tags.randomElement(using: &rng)! },
            show: Self.shows.randomElement(using: &rng)!
        )
    }

    private func randomRooms(_ rng: inout SeededRNG) -> [Room] {
        (0..<Int.random(in: 0...15, using: &rng)).map { _ in randomRoom(&rng) }
    }

    private func randomNames(_ rng: inout SeededRNG) -> Set<String> {
        Set((0..<Int.random(in: 0...6, using: &rng)).map { _ in Self.names.randomElement(using: &rng)! })
    }

    private func randomHidden(_ rng: inout SeededRNG) -> Set<String> {
        var keys = Set<String>()
        if Bool.random(using: &rng) { keys.insert(Taste.tagKey(Self.tags.randomElement(using: &rng)!)) }
        if Int.random(in: 0..<4, using: &rng) == 0 { keys.insert(Taste.genderKey(Gender.allCases.randomElement(using: &rng)!)) }
        return keys
    }

    private func randomQuery(_ rng: inout SeededRNG) -> RoomQuery {
        RoomQuery(
            text: ["", "", "user1", " Asian ", "hello", "zzz"].randomElement(using: &rng)!,
            publicOnly: Bool.random(using: &rng),
            blocked: randomNames(&rng),
            only: Bool.random(using: &rng) ? nil : randomNames(&rng),
            hidden: randomHidden(&rng)
        )
    }

    private func randomPage(_ rng: inout SeededRNG) -> CatalogPage {
        let rooms = randomRooms(&rng)
        let total = Int.random(in: 0...60, using: &rng)
        return CatalogPage(rooms: rooms, total: total, nextOffset: Int.random(in: 0...70, using: &rng))
    }

    private func assertCacheMatches(_ feed: FeedStore, _ op: String, file: StaticString = #filePath, line: UInt = #line) {
        let expected = feed.catalog.filter(feed.query.matches)
        XCTAssertEqual(feed.rooms, expected, "缓存与全量过滤不一致：\(op)", file: file, line: line)

        // 依赖可见列表的派生值也与参考实现一致。
        let refIndex: Int = {
            guard !expected.isEmpty, let cur = feed.currentUsername else { return 0 }
            return expected.firstIndex { $0.username == cur } ?? 0
        }()
        XCTAssertEqual(feed.index, refIndex, "index：\(op)", file: file, line: line)
        XCTAssertEqual(feed.current, expected.isEmpty ? nil : expected[min(refIndex, expected.count - 1)], "current：\(op)", file: file, line: line)

        // 用同样的状态重新构造的 FeedStore 应与之相等，且可见列表一致（相等比较不受缓存影响）。
        // init 会去重，而 append 允许同页内重复（既有行为），所以只在目录无重复时比较。
        guard dedupeRooms(feed.catalog) == feed.catalog else { return }
        let rebuilt = FeedStore(
            catalog: feed.catalog, query: feed.query, currentUsername: feed.currentUsername,
            reachedEnd: feed.reachedEnd, total: feed.total
        )
        XCTAssertEqual(rebuilt, feed, "重建后应相等：\(op)", file: file, line: line)
        XCTAssertEqual(rebuilt.rooms, feed.rooms, "重建后可见列表：\(op)", file: file, line: line)
    }

    func testCachedRoomsMatchFullFilterAcrossRandomOperations() {
        for seed in UInt64(1)...UInt64(40) {
            var rng = SeededRNG(state: seed)
            var feed = FeedStore(catalog: randomRooms(&rng), query: randomQuery(&rng))
            assertCacheMatches(feed, "seed \(seed) init")
            for step in 0..<150 {
                let op: String
                switch Int.random(in: 0..<11, using: &rng) {
                case 0:
                    op = "replace"
                    feed.replace(with: randomPage(&rng))
                case 1:
                    op = "append"
                    feed.append(randomPage(&rng))
                case 2:
                    op = "step"
                    feed.step(Int.random(in: -3...3, using: &rng))
                case 3:
                    op = "focus"
                    let name = Self.names.randomElement(using: &rng)!
                    feed.focus(Bool.random(using: &rng) ? name.uppercased() : name)
                case 4:
                    op = "insertDirect"
                    feed.insertDirect(randomRoom(&rng))
                case 5:
                    op = "promoteAfterCurrent"
                    feed.promoteAfterCurrent(Array(randomNames(&rng)).sorted().shuffled(using: &rng))
                case 6:
                    op = "reorderAfterCurrent"
                    var local = SeededRNG(state: rng.next())
                    feed.reorderAfterCurrent { $0.shuffled(using: &local) }
                case 7:
                    op = "setQuery"
                    feed.setQuery(randomQuery(&rng))
                case 8:
                    op = "setBlocked"
                    feed.setBlocked(randomNames(&rng))
                case 9:
                    op = "setFilters"
                    let blocked = randomNames(&rng)
                    let only: Set<String>? = Bool.random(using: &rng) ? nil : randomNames(&rng)
                    let hidden: Set<String>? = Bool.random(using: &rng) ? nil : randomHidden(&rng)
                    feed.setFilters(blocked: blocked, only: only, hidden: hidden)
                default:
                    op = "reveal"
                    feed.reveal(randomRoom(&rng))
                }
                assertCacheMatches(feed, "seed \(seed) step \(step) \(op)")
            }
        }
    }

    func testEachMutatingMethodKeepsCacheInSync() {
        func r(_ name: String, _ tags: [String] = [], show: ShowState = .publicShow) -> Room {
            Room(username: name, subject: "s-\(name)", gender: .female, tags: tags, show: show)
        }
        var feed = FeedStore(catalog: [r("a"), r("b", show: .away), r("c"), r("d", ["nope"])])
        assertCacheMatches(feed, "init")
        XCTAssertEqual(feed.rooms.map(\.username), ["a", "c", "d"])

        feed.replace(with: CatalogPage(rooms: [r("a"), r("b"), r("c"), r("A")], total: 10, nextOffset: 3))
        assertCacheMatches(feed, "replace")
        feed.append(CatalogPage(rooms: [r("e"), r("f", show: .privateShow), r("g", ["nope"])], total: 10, nextOffset: 6))
        assertCacheMatches(feed, "append")
        feed.step(2)
        assertCacheMatches(feed, "step")
        feed.focus("E")
        assertCacheMatches(feed, "focus")
        feed.insertDirect(r("z"))
        assertCacheMatches(feed, "insertDirect")
        feed.promoteAfterCurrent(["g", "a"])
        assertCacheMatches(feed, "promoteAfterCurrent")
        feed.reorderAfterCurrent { $0.reversed() }
        assertCacheMatches(feed, "reorderAfterCurrent")
        feed.setQuery(RoomQuery(text: "s-", publicOnly: false))
        assertCacheMatches(feed, "setQuery")
        XCTAssertTrue(feed.rooms.contains { $0.username == "f" })
        feed.setBlocked(["z", "e"])
        assertCacheMatches(feed, "setBlocked")
        XCTAssertFalse(feed.rooms.contains { $0.username == "z" })
        feed.setFilters(blocked: [], only: ["a", "g", "c"], hidden: [Taste.tagKey("nope")])
        assertCacheMatches(feed, "setFilters")
        XCTAssertEqual(Set(feed.rooms.map(\.username)), ["a", "c"])
        feed.setFilters(blocked: [], only: nil)
        assertCacheMatches(feed, "setFilters hidden nil")
        feed.setQuery(RoomQuery(text: "nobody-matches"))
        assertCacheMatches(feed, "setQuery empty")
        XCTAssertTrue(feed.rooms.isEmpty)
        XCTAssertTrue(feed.reveal(r("q")))
        assertCacheMatches(feed, "reveal")
    }

    func testEqualityIgnoresCache() {
        let rooms = [Room(username: "a", show: .publicShow), Room(username: "b", show: .away)]
        var lhs = FeedStore(catalog: rooms)
        var rhs = FeedStore(catalog: rooms, query: RoomQuery(publicOnly: false))
        XCTAssertNotEqual(lhs, rhs)
        rhs.setQuery(RoomQuery())
        lhs.setQuery(RoomQuery(publicOnly: false))
        lhs.setQuery(RoomQuery())
        XCTAssertEqual(lhs, rhs)
        XCTAssertEqual(lhs.rooms, rhs.rooms)
    }

    /// 2000 个房间连续读 1000 次 `index`：缓存版应明显快于每次全量过滤。阈值放宽，避免环境抖动。
    func testCachedIndexIsMuchFasterThanFullFilter() {
        let rooms = (0..<2000).map { i in
            Room(username: "room\(i)", subject: "subject \(i)", gender: .female, tags: ["tag\(i % 7)", "Music"], show: i % 5 == 0 ? .away : .publicShow)
        }
        var feed = FeedStore(catalog: rooms, query: RoomQuery(blocked: ["room3", "room9"], hidden: [Taste.tagKey("tag6")]))
        XCTAssertTrue(feed.focus("room1001"))
        let iterations = 1000

        func measure(_ body: () -> Int) -> (TimeInterval, Int) {
            let start = Date()
            var sum = 0
            for _ in 0..<iterations { sum &+= body() }
            return (Date().timeIntervalSince(start), sum)
        }

        let (cached, cachedSum) = measure { feed.index }
        let (full, fullSum) = measure {
            let list = feed.catalog.filter(feed.query.matches)
            guard let cur = feed.currentUsername else { return 0 }
            return list.firstIndex { $0.username == cur } ?? 0
        }
        XCTAssertEqual(cachedSum, fullSum)
        XCTAssertLessThan(cached * 2, full, "缓存 \(cached)s 应明显快于全量过滤 \(full)s")
    }
}
