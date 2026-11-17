import XCTest
@testable import VelaCore

/// 新开播改用站点的 `new_cams=true` 筛选：只有新开播来源带它，可与其他筛选叠加，排序仍按开播时长。
final class FreshCamsTests: XCTestCase {
    private func values(_ url: URL) -> [String: String] {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
    }

    func testOnlyFreshSourceUsesNewCams() {
        XCTAssertTrue(FeedSource.fresh.usesNewCams)
        for source in [FeedSource.guess, .popular, .favorites] { XCTAssertFalse(source.usesNewCams) }
    }

    func testNewCamsParameterIsAddedAndStacksWithFilters() {
        let query = CatalogQuery(gender: .female, region: .asia, tag: "#Jazz", limit: 90, offset: 180, newCams: true)
        XCTAssertEqual(values(query.url()), [
            "limit": "90", "offset": "180", "genders": "f", "regions": "AS", "hashtags": "jazz", "new_cams": "true"
        ])
    }

    func testNewCamsIsOffByDefault() {
        XCTAssertNil(values(CatalogQuery(limit: 40).url())["new_cams"])
    }

    func testFreshStillSortsShortestBroadcastFirst() {
        let rooms = [
            Room(username: "late", secondsOnline: 900),
            Room(username: "none"),
            Room(username: "early", secondsOnline: 60)
        ]
        let sorted = Recommender.arrange(rooms, source: .fresh, taste: Taste(), now: Date(), seed: 1)
        XCTAssertEqual(sorted.map(\.username), ["early", "late", "none"])
    }
}
