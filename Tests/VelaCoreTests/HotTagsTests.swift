import XCTest
@testable import VelaCore

/// 全站热门标签：请求构造、解码、6 小时缓存与失败冷却、冷启动与筛选面板的回退。
final class HotTagsTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func values(_ url: URL) -> [String: String] {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
    }

    private func data(_ text: String) -> Data { Data(text.utf8) }

    func testURLMatchesSiteTable() {
        let url = HotTags.url()
        XCTAssertTrue(url.absoluteString.hasPrefix("https://chaturbate.com/api/ts/hashtags/tag-table-data/?"))
        XCTAssertEqual(values(url), ["sort": "-rc", "page": "1", "g": "", "limit": "20"])
        XCTAssertEqual(HotTags.request().value(forHTTPHeaderField: "X-Requested-With"), "XMLHttpRequest")
    }

    func testDecodeNormalizesAndSkipsBadItems() throws {
        let body = #"""
        {"hashtags":[{"hashtag":"#Asian","room_count":812},{"hashtag":"asian","room_count":3},
         {"room_count":9},{"hashtag":"  ","room_count":1},{"hashtag":"bigboobs","room_count":"77"},{"hashtag":"new"}],
         "total":5000}
        """#
        let tags = try HotTags.decode(status: 200, data: data(body))
        XCTAssertEqual(tags, [HotTag(name: "asian", rooms: 812), HotTag(name: "bigboobs", rooms: 77), HotTag(name: "new", rooms: 0)])
    }

    func testDecodeFailures() {
        XCTAssertThrowsError(try HotTags.decode(status: 200, data: data("<html>Just a moment</html>"))) {
            XCTAssertEqual($0 as? CatalogFailure, .challenge)
        }
        XCTAssertThrowsError(try HotTags.decode(status: 403, data: data("{}"))) { XCTAssertEqual($0 as? CatalogFailure, .challenge) }
        XCTAssertThrowsError(try HotTags.decode(status: 500, data: data("{}"))) { XCTAssertEqual($0 as? CatalogFailure, .status(500)) }
        XCTAssertThrowsError(try HotTags.decode(status: 200, data: data(#"{"total":1}"#))) { XCTAssertEqual($0 as? CatalogFailure, .malformed) }
    }

    func testCacheLastsSixHours() {
        let cache = HotTagCache().storing([HotTag(name: "a", rooms: 1)], at: now)
        XCTAssertEqual(cache.fresh(now: now.addingTimeInterval(3600))?.map(\.name), ["a"])
        XCTAssertFalse(cache.needsFetch(now: now.addingTimeInterval(3600)))
        XCTAssertNil(cache.fresh(now: now.addingTimeInterval(HotTagCache.ttl + 1)))
        XCTAssertTrue(cache.needsFetch(now: now.addingTimeInterval(HotTagCache.ttl + 1)))
    }

    func testFailureCoolsDownAndEmptyCountsAsFailure() {
        XCTAssertTrue(HotTagCache().needsFetch(now: now))
        let failed = HotTagCache().recordingFailure(at: now)
        XCTAssertFalse(failed.needsFetch(now: now.addingTimeInterval(30)))
        XCTAssertTrue(failed.needsFetch(now: now.addingTimeInterval(HotTagCache.retryAfter)))
        let empty = HotTagCache().storing([], at: now)
        XCTAssertNil(empty.fresh(now: now))
        XCTAssertFalse(empty.needsFetch(now: now.addingTimeInterval(1)))
    }

    func testColdStartPrefersHotTagsNotInProfile() {
        let taste = Taste().boosting(Taste.tagKey("asian"), now: now)
        let hot = ["asian", "milf", "new", "latina"].map { HotTag(name: $0, rooms: 1) }
        let rooms = [Room(username: "r", tags: ["local"])]
        XCTAssertEqual(taste.suggestedTags(hot: hot, fallback: rooms, limit: 2), ["milf", "new"])
    }

    func testColdStartFallsBackToOnlineRoomTags() {
        let rooms = [Room(username: "a", tags: ["x", "y"]), Room(username: "b", tags: ["x"])]
        XCTAssertEqual(Taste().suggestedTags(hot: nil, fallback: rooms), ["x", "y"])
        let known = Taste().boosting(Taste.tagKey("hot"), now: now)
        XCTAssertEqual(known.suggestedTags(hot: [HotTag(name: "hot", rooms: 9)], fallback: rooms), ["x", "y"])
    }

    func testFilterHintsUseHotTagsOrFallBack() {
        let hot = (0..<12).map { HotTag(name: "t\($0)", rooms: 12 - $0) }
        XCTAssertEqual(HotTags.filterHints(hot: hot, rooms: []), (0..<8).map { "t\($0)" })
        let rooms = [Room(username: "a", tags: ["x", "y"]), Room(username: "b", tags: ["y"])]
        XCTAssertEqual(HotTags.filterHints(hot: [], rooms: rooms), ["y", "x"])
        XCTAssertEqual(HotTags.filterHints(hot: nil, rooms: []), [])
    }
}
