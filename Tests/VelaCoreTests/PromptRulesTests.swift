import XCTest
@testable import VelaCore

final class PromptRulesTests: XCTestCase {
    // A14：只有点了收藏或明确关闭才记为已提示。
    func testOnlyAcceptOrCloseCountsAsAsked() {
        XCTAssertTrue(SuggestRules.countsAsAsked(.accepted))
        XCTAssertTrue(SuggestRules.countsAsAsked(.closed))
        XCTAssertFalse(SuggestRules.countsAsAsked(.timedOut), "超时消失不算问过")
        XCTAssertFalse(SuggestRules.countsAsAsked(.withdrawn), "换房或被替换也不算")
    }

    // A14：穿透期间不显示、不开始计时；超时过的本次停留里不再弹。
    func testPresentGate() {
        XCTAssertTrue(SuggestRules.canPresent(pointerCanClick: true, snoozed: false))
        XCTAssertFalse(SuggestRules.canPresent(pointerCanClick: false, snoozed: false))
        XCTAssertFalse(SuggestRules.canPresent(pointerCanClick: true, snoozed: true))
    }

    // A14：超时消失后账本不记，下次条件仍满足时仍会提示。
    func testTimeoutLeavesLedgerUnmarked() {
        let t0 = 1_000_000.0
        var ledger = WatchLedger()
        ledger = ledger.recording(username: "Amy", seconds: 700, start: t0, now: t0 + 700)
        XCTAssertTrue(ledger.shouldSuggest(username: "Amy", favorited: false, blocked: false, liveSeconds: 0, liveStart: nil))
        if SuggestRules.countsAsAsked(.timedOut) { ledger = ledger.markingSuggested("Amy", now: t0 + 710) }
        XCTAssertTrue(ledger.shouldSuggest(username: "Amy", favorited: false, blocked: false, liveSeconds: 0, liveStart: nil))
        if SuggestRules.countsAsAsked(.closed) { ledger = ledger.markingSuggested("Amy", now: t0 + 720) }
        XCTAssertFalse(ledger.shouldSuggest(username: "Amy", favorited: false, blocked: false, liveSeconds: 0, liveStart: nil))
    }

    private func room(_ name: String, tags: [String] = ["x"], show: ShowState = .publicShow) -> Room {
        Room(username: name, viewers: 5, gender: .female, tags: tags, show: show)
    }

    private func message(_ query: RoomQuery, _ catalog: [Room], source: FeedSource = .guess, favorites: Int = 0, error: String? = nil) -> String {
        EmptyListCopy.message(catalogError: error, query: query, catalog: catalog, source: source, favoriteCount: favorites)
    }

    // B2：空列表文案按原因区分。
    func testEmptyMessagesByReason() {
        let rooms = [room("a"), room("b")]
        XCTAssertEqual(message(RoomQuery(), rooms, error: "网络不通，稍后再试"), "网络不通，稍后再试")
        XCTAssertEqual(message(RoomQuery(blocked: ["a", "b"]), rooms), "目录里的房间都在屏蔽名单里")
        XCTAssertEqual(
            message(RoomQuery(hidden: [Taste.tagKey("x")]), rooms),
            "目录里的房间都被「我的口味」里不想看的标签过滤掉了"
        )
        XCTAssertEqual(message(RoomQuery(text: "zzz"), rooms), "没有匹配「zzz」的房间。回车搜索全站")
        XCTAssertEqual(
            message(RoomQuery(), [room("a", show: .privateShow)]),
            "目录里没有公开的房间，可以在筛选里关掉「仅公开」"
        )
        XCTAssertEqual(message(RoomQuery(), []), "目录里现在没有房间，可以稍后重试")
    }

    func testEmptyFavoritesMessages() {
        XCTAssertEqual(message(RoomQuery(only: []), [], source: .favorites, favorites: 0), "还没有收藏，在房间里按 ⌘D 收藏")
        XCTAssertEqual(message(RoomQuery(only: ["q"]), [], source: .favorites, favorites: 1), "收藏的人现在都不在线")
        XCTAssertEqual(message(RoomQuery(only: ["q"]), [room("a")], source: .favorites, favorites: 1), "收藏的人现在都不在线")
    }

    func testMixedCauseFallsBackToGenericMessage() {
        let rooms = [room("a"), room("b", tags: ["y"])]
        let query = RoomQuery(blocked: ["a"], hidden: [Taste.tagKey("y")])
        XCTAssertEqual(message(query, rooms), "目录里的房间都被「我的口味」里不想看的标签过滤掉了")
        XCTAssertEqual(message(RoomQuery(publicOnly: true, blocked: ["a"]), [room("a"), room("b", show: .privateShow)]), "目录里没有公开的房间，可以在筛选里关掉「仅公开」")
    }

    // B3：确认页高度随窗口收缩，底部按钮始终在窗口内。
    func testChallengeSheetHeightFollowsWindow() {
        XCTAssertEqual(SheetLayout.challengeHeight(windowHeight: 360), 336)
        XCTAssertEqual(SheetLayout.challengeHeight(windowHeight: 630), 606)
        XCTAssertEqual(SheetLayout.challengeHeight(windowHeight: 1200), 640)
        XCTAssertEqual(SheetLayout.challengeHeight(windowHeight: 200), 300, "再矮也留出能用的网页区")
    }

    /// 盲审 8：Cam4 不支持筛选的文案不能盖过收藏来源和全站搜索态，且收藏数按当前模式口径。
    func testEmptyMessageCam4UnsupportedFilterRespectsFavoritesAndSearch() {
        XCTAssertEqual(
            EmptyListCopy.message(
                catalogError: nil, query: RoomQuery(), catalog: [], source: .guess, favoriteCount: 0,
                siteMode: .cam4, hasUnsupportedSiteFilter: true, searching: false
            ),
            "Cam4 不支持按地区、标签或新开播筛选"
        )
        XCTAssertEqual(
            EmptyListCopy.message(
                catalogError: nil, query: RoomQuery(), catalog: [], source: .fresh, favoriteCount: 0,
                siteMode: .cam4, hasUnsupportedSiteFilter: false, searching: false
            ),
            "Cam4 不支持按地区、标签或新开播筛选"
        )
        XCTAssertEqual(
            EmptyListCopy.message(
                catalogError: nil, query: RoomQuery(), catalog: [], source: .favorites, favoriteCount: 0,
                siteMode: .cam4, hasUnsupportedSiteFilter: true, searching: false
            ),
            EmptyListCopy.noFavoritesYet
        )
        XCTAssertEqual(
            EmptyListCopy.message(
                catalogError: nil, query: RoomQuery(), catalog: [], source: .fresh, favoriteCount: 0,
                siteMode: .cam4, hasUnsupportedSiteFilter: true, searching: true
            ),
            "目录里现在没有房间，可以稍后重试"
        )
    }
}
