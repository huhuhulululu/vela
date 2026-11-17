import XCTest
@testable import VelaCore

final class VetoVisibilityTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 3_000_000)

    private func room(_ name: String, _ tags: [String], gender: Gender? = .female) -> Room {
        Room(username: name, viewers: 5, gender: gender, tags: tags, show: .publicShow)
    }

    // Q4：过滤只影响可见性，房间仍在目录里；恢复后重新出现在当前位置之后。
    func testVetoHidesWithoutDeletingAndRestoreBringsRoomsBack() {
        var taste = Taste().vetoing(Taste.tagKey("nope"), now: now)
        var feed = FeedStore()
        feed.replace(with: CatalogPage(
            rooms: [room("cur", ["ok"]), room("a", ["ok"]), room("b", ["Nope"]), room("c", ["ok"])],
            total: 4, nextOffset: 4
        ))
        feed.setFilters(blocked: [], only: nil, hidden: taste.vetoedKeys)
        XCTAssertEqual(feed.rooms.map(\.username), ["cur", "a", "c"])
        XCTAssertEqual(feed.catalog.count, 4, "不从目录删除")

        taste = taste.restoring(Taste.tagKey("nope"), now: now)
        feed.setFilters(blocked: [], only: nil, hidden: taste.vetoedKeys)
        feed.reorderAfterCurrent {
            Recommender.arrange($0, source: .guess, taste: taste, now: self.now, seed: 1)
        }
        XCTAssertEqual(feed.currentUsername, "cur")
        XCTAssertTrue(feed.rooms.map(\.username).contains("b"), "恢复后房间回来")
        XCTAssertEqual(feed.rooms.first?.username, "cur")
    }

    func testVetoedGenderIsHiddenAndAllVetoedLeavesEmptyVisibleList() {
        let taste = Taste().vetoing(Taste.genderKey(.male), now: now)
        var feed = FeedStore()
        feed.replace(with: CatalogPage(rooms: [room("m", ["x"], gender: .male)], total: 1, nextOffset: 1))
        feed.setFilters(blocked: [], only: nil, hidden: taste.vetoedKeys)
        XCTAssertTrue(feed.rooms.isEmpty)
        XCTAssertEqual(feed.catalog.count, 1)
    }

    func testSetFiltersWithoutHiddenKeepsExistingHiddenKeys() {
        var feed = FeedStore()
        feed.replace(with: CatalogPage(rooms: [room("a", ["nope"]), room("b", ["ok"])], total: 2, nextOffset: 2))
        feed.setFilters(blocked: [], only: nil, hidden: [Taste.tagKey("nope")])
        feed.setFilters(blocked: ["zzz"], only: nil)
        XCTAssertEqual(feed.rooms.map(\.username), ["b"])
    }
}
