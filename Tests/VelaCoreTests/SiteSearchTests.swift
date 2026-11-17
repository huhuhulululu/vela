import XCTest
@testable import VelaCore

/// 全站搜索：请求构造、回车与打字的触发规则、结果整理、停留归属。
final class SiteSearchTests: XCTestCase {
    private func values(_ url: URL) -> [String: String] {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
    }

    func testQueryCarriesKeywordsAndOnePageOnly() {
        let url = SiteSearch.query("big  smile").url()
        XCTAssertTrue(url.absoluteString.hasPrefix("https://chaturbate.com/api/ts/roomlist/room-list/?"))
        XCTAssertEqual(values(url), ["limit": "90", "offset": "0", "keywords": "big smile"])
    }

    func testBlankKeywordsAreNotSent() {
        XCTAssertNil(values(CatalogQuery(keywords: "   ").url())["keywords"])
        XCTAssertNil(values(CatalogQuery().url())["keywords"])
    }

    func testNormalizationTrimsCollapsesAndClips() {
        XCTAssertEqual(SiteSearch.normalized("  a \n b  "), "a b")
        XCTAssertNil(SiteSearch.normalized(" \t "))
        XCTAssertNil(SiteSearch.normalized(nil))
        XCTAssertEqual(SiteSearch.normalized(String(repeating: "x", count: 100))?.count, SiteSearch.maxLength)
    }

    func testSubmitUsesLocalWhenSomethingMatches() {
        XCTAssertEqual(SiteSearch.onSubmit(text: "jazz", localMatches: 3, active: nil), .local)
    }

    func testSubmitQueriesSiteWhenNothingMatchesLocally() {
        XCTAssertEqual(SiteSearch.onSubmit(text: " jazz ", localMatches: 0, active: nil), .remote("jazz"))
    }

    func testSubmitIgnoresBlankAndSameActiveKeyword() {
        XCTAssertEqual(SiteSearch.onSubmit(text: "  ", localMatches: 0, active: nil), .ignore)
        XCTAssertEqual(SiteSearch.onSubmit(text: "Jazz", localMatches: 0, active: "jazz"), .ignore)
    }

    func testSubmitWithNewKeywordDuringSearchQueriesAgain() {
        XCTAssertEqual(SiteSearch.onSubmit(text: "piano", localMatches: 5, active: "jazz"), .remote("piano"))
    }

    func testTypingNeverQueries() {
        XCTAssertEqual(SiteSearch.onEdit(text: "ja", active: nil), .filter)
        XCTAssertEqual(SiteSearch.onEdit(text: "", active: nil), .filter)
        XCTAssertEqual(SiteSearch.onEdit(text: "pia", active: "jazz"), .keep)
    }

    func testClearingDuringSearchExits() {
        XCTAssertEqual(SiteSearch.onEdit(text: "  ", active: "jazz"), .exit)
    }

    func testPageDedupesDropsBlockedAndHasNoNextPage() {
        let rooms = [Room(username: "a"), Room(username: "B"), Room(username: "A"), Room(username: "c")]
        let page = SiteSearch.page(rooms, blocked: ["b"])
        XCTAssertEqual(page.rooms.map(\.username), ["a", "c"])
        XCTAssertEqual(page.total, 2)
        XCTAssertEqual(page.nextOffset, 2)
        var store = FeedStore()
        store.replace(with: page)
        XCTAssertFalse(store.shouldLoadMore())
    }

    func testDwellDuringSearchIsNotCountedAsRecommendation() {
        XCTAssertEqual(SiteSearch.dwellSource(loaded: .guess, searching: true), .popular)
        XCTAssertEqual(SiteSearch.dwellSource(loaded: .guess, searching: false), .guess)
        XCTAssertEqual(SiteSearch.dwellSource(loaded: .fresh, searching: true), .fresh)
    }
}
