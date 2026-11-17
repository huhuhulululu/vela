import XCTest
@testable import VelaCore

/// 冷启动：首页先入选并保持当前项，后页再并入；目录请求与 bootstrap 各只做一次。
final class StartupCatalogTests: XCTestCase {
    private func room(_ name: String) -> Room {
        Room(username: name, viewers: 10, gender: .female, tags: ["x"], show: .publicShow)
    }

    private func names(_ rooms: [Room]) -> [String] { rooms.map(\.username) }

    // MARK: 续页条件

    func testContinuesOnlyWhilePageIsFullAndCatalogRemains() {
        XCTAssertTrue(CatalogWindow.shouldContinue(
            fetched: 1, pages: 3, lastCount: 90, pageSize: 90, nextOffset: 90, total: 1000
        ))
        XCTAssertFalse(CatalogWindow.shouldContinue(
            fetched: 3, pages: 3, lastCount: 90, pageSize: 90, nextOffset: 270, total: 1000
        ), "三页取满就停")
        XCTAssertFalse(CatalogWindow.shouldContinue(
            fetched: 1, pages: 3, lastCount: 30, pageSize: 90, nextOffset: 30, total: 1000
        ), "短页就停")
        XCTAssertFalse(CatalogWindow.shouldContinue(
            fetched: 1, pages: 3, lastCount: 90, pageSize: 90, nextOffset: 90, total: 90
        ), "到底就停")
    }

    func testPageSizeIsClampedToTheCatalogCap() {
        XCTAssertTrue(CatalogWindow.shouldContinue(
            fetched: 1, pages: 3, lastCount: 90, pageSize: 500, nextOffset: 90, total: 1000
        ), "请求条数超过接口上限时，按 90 判断满页")
    }

    // MARK: 首页先用，后页并入且保持当前项

    func testLaterPageKeepsTheRoomChosenFromTheFirstPage() {
        let first = [room("a"), room("b"), room("c")]
        let folded = PageMerge.keepingCurrent(
            existing: first, incoming: [room("c"), room("z"), room("Z")], current: "a"
        ) { rooms in
            rooms.sorted { $0.username < $1.username }
        }
        XCTAssertEqual(folded.added, 1, "c / Z 与已有房间重复，只新增 z")
        XCTAssertEqual(names(folded.rooms), ["a", "b", "c", "z"])
        XCTAssertEqual(folded.rooms.first?.username, "a", "首个房间只从首页候选里选，后页不能把它换掉")
    }

    func testLaterPageKeepsASelectionThatIsNotFirst() {
        let folded = PageMerge.keepingCurrent(
            existing: [room("a"), room("b"), room("c")],
            incoming: [room("d")],
            current: "b"
        ) { rooms in
            rooms.sorted { $0.username > $1.username }
        }
        XCTAssertEqual(names(folded.rooms), ["a", "b", "d", "c"])
        XCTAssertEqual(folded.added, 1)
    }

    func testNothingNewLeavesTheListUntouched() {
        var ordered = false
        let existing = [room("a"), room("b")]
        let folded = PageMerge.keepingCurrent(existing: existing, incoming: [room("A")], current: "a") { rooms in
            ordered = true
            return rooms.reversed()
        }
        XCTAssertFalse(ordered, "没有新增就不重排，避免把正在看的列表抖一下")
        XCTAssertEqual(folded.added, 0)
        XCTAssertEqual(names(folded.rooms), ["a", "b"])
    }

    func testNoCurrentOrdersTheWholePool() {
        let folded = PageMerge.keepingCurrent(
            existing: [room("b")], incoming: [room("a")], current: nil
        ) { rooms in
            rooms.sorted { $0.username < $1.username }
        }
        XCTAssertEqual(names(folded.rooms), ["a", "b"])
        XCTAssertEqual(folded.added, 1)
    }

    func testAppendedPageDedupesAndKeepsTheLaterCursor() {
        let base = CatalogPage(rooms: [room("a"), room("b")], total: 10, nextOffset: 2)
        let next = CatalogPage(rooms: [room("B"), room("c")], total: 12, nextOffset: 4)
        let merged = PageMerge.appended(base, next)
        XCTAssertEqual(names(merged.rooms), ["a", "b", "c"])
        XCTAssertEqual(merged.total, 12)
        XCTAssertEqual(merged.nextOffset, 4)
    }

    func testFeedKeepsPlayingRoomWhenRestPagesArrive() {
        var feed = FeedStore()
        feed.replace(with: CatalogPage(rooms: [room("a"), room("m")], total: 10, nextOffset: 2))
        let playing = feed.currentUsername
        XCTAssertEqual(playing, "a")
        feed.mergePageAfterCurrent(
            CatalogPage(rooms: [room("m"), room("z")], total: 10, nextOffset: 4), from: 2
        ) { rooms in
            rooms.sorted { $0.username < $1.username }
        }
        feed.mergePageAfterCurrent(
            CatalogPage(rooms: [room("y")], total: 10, nextOffset: 6), from: 4
        ) { rooms in
            rooms.sorted { $0.username < $1.username }
        }
        XCTAssertEqual(feed.currentUsername, playing, "后页并入不改当前选中项")
        XCTAssertEqual(feed.catalog.first?.username, "a")
        XCTAssertEqual(names(feed.catalog), ["a", "m", "y", "z"])
        XCTAssertEqual(feed.total, 10)
        XCTAssertFalse(feed.reachedEnd)
    }

    func testRestPageAtTheEndDoesNotMoveTheCurrentRoom() {
        var feed = FeedStore()
        feed.replace(with: CatalogPage(rooms: [room("a"), room("b")], total: 4, nextOffset: 2))
        feed.mergePageAfterCurrent(
            CatalogPage(rooms: [room("c")], total: 4, nextOffset: 4), from: 2
        ) { $0 }
        XCTAssertTrue(feed.reachedEnd)
        XCTAssertEqual(feed.currentUsername, "a")
        XCTAssertEqual(names(feed.catalog), ["a", "b", "c"])
    }

    func testEmptyRestPageMarksTheEndWithoutReordering() {
        var feed = FeedStore()
        feed.replace(with: CatalogPage(rooms: [room("a")], total: 9, nextOffset: 1))
        var ordered = false
        feed.mergePageAfterCurrent(CatalogPage(rooms: [], total: 9, nextOffset: 1), from: 1) { rooms in
            ordered = true
            return rooms.reversed()
        }
        XCTAssertFalse(ordered)
        XCTAssertTrue(feed.reachedEnd)
        XCTAssertEqual(feed.currentUsername, "a")
        XCTAssertEqual(names(feed.catalog), ["a"])
    }

    // MARK: bootstrap 与目录请求只做一次

    func testBootstrapEntersOnce() {
        var gate = StartupGate()
        XCTAssertTrue(gate.enterBootstrap())
        XCTAssertFalse(gate.enterBootstrap(), "onAppear 再次触发不再进入")
        XCTAssertTrue(gate.bootstrapped)
    }

    func testCatalogClaimBeforeBootstrapDoesNotRepeat() {
        var gate = StartupGate()
        XCTAssertTrue(gate.claimCatalog(ageConfirmed: true), "初始化时已确认年龄，先发目录")
        XCTAssertTrue(gate.enterBootstrap(), "视图出现仍做一次启动副作用")
        XCTAssertFalse(gate.claimCatalog(ageConfirmed: true), "bootstrap 不再发第二次目录请求")
        XCTAssertFalse(gate.enterBootstrap())
    }

    func testUnconfirmedAgeDoesNotClaim() {
        var gate = StartupGate()
        XCTAssertFalse(gate.claimCatalog(ageConfirmed: false))
        XCTAssertFalse(gate.catalogRequested, "未确认时不领取，确认前保持不加载")
        XCTAssertTrue(gate.enterBootstrap())
        XCTAssertFalse(gate.claimCatalog(ageConfirmed: false))
        XCTAssertFalse(gate.catalogRequested)
        XCTAssertTrue(gate.claimCatalog(ageConfirmed: true), "确认之后可以领唯一一次")
        XCTAssertFalse(gate.claimCatalog(ageConfirmed: true))
    }

    /// confirmAge 与 bootstrap 顺序可能互换：先 bootstrap 路径领过，confirmAge 不再发；反之亦然。
    func testConfirmAgeAndBootstrapShareOneClaim() {
        var gate = StartupGate()
        XCTAssertTrue(gate.claimCatalog(ageConfirmed: true), "先到的一方领取")
        XCTAssertFalse(gate.claimCatalog(ageConfirmed: true), "后到的一方（confirmAge 或 bootstrap）不重复")
        XCTAssertTrue(gate.catalogRequested)
    }
}
