import XCTest
@testable import VelaCore

final class ShortcutSearchTests: XCTestCase {
    private func room(_ name: String, tags: [String] = ["x"]) -> Room {
        Room(username: name, viewers: 10, gender: .female, tags: tags, show: .publicShow)
    }

    // A2：弹层、输入框、年龄确认之前，无修饰键快捷键都不触发。
    func testPlainKeysOnlyWhenNothingElseOwnsTheKeyboard() {
        XCTAssertTrue(ShortcutGate.allowsPlainKeys(ageConfirmed: true, interacting: false, editingText: false))
        XCTAssertFalse(ShortcutGate.allowsPlainKeys(ageConfirmed: true, interacting: true, editingText: false), "有弹层")
        XCTAssertFalse(ShortcutGate.allowsPlainKeys(ageConfirmed: true, interacting: false, editingText: true), "正在输入")
        XCTAssertFalse(ShortcutGate.allowsPlainKeys(ageConfirmed: false, interacting: false, editingText: false), "年龄确认前")
    }

    private func feed() -> FeedStore {
        var feed = FeedStore()
        feed.replace(with: CatalogPage(rooms: [room("amy"), room("bob"), room("mike"), room("sofia")], total: 4, nextOffset: 4))
        feed.focus("bob")
        return feed
    }

    // A13：打字预览只改可见列表，当前房间不换（即使它不匹配输入）。
    func testPreviewKeepsCurrentRoomWhileFilteringOthers() {
        var feed = feed()
        feed.previewQuery(RoomQuery(text: "mi"))
        XCTAssertEqual(feed.currentUsername, "bob")
        XCTAssertEqual(feed.current?.username, "bob")
        XCTAssertEqual(feed.rooms.map(\.username), ["bob", "mike"], "保留的当前房间 + 匹配项")
        feed.previewQuery(RoomQuery(text: "mik"))
        XCTAssertEqual(feed.current?.username, "bob", "继续打字仍不换房")
    }

    // A13：回车提交（setQuery）才换到第一个匹配项。
    func testSubmitSwitchesToFirstMatch() {
        var feed = feed()
        feed.previewQuery(RoomQuery(text: "mi"))
        feed.setQuery(RoomQuery(text: "mi"))
        XCTAssertNil(feed.retained)
        XCTAssertEqual(feed.rooms.map(\.username), ["mike"])
        XCTAssertEqual(feed.currentUsername, "mike")
    }

    // A13：没提交就划走，保留失效，列表只剩匹配项。
    func testSteppingAwayDropsRetainedRoom() {
        var feed = feed()
        feed.previewQuery(RoomQuery(text: "mi"))
        feed.step(1)
        XCTAssertEqual(feed.currentUsername, "mike")
        XCTAssertNil(feed.retained)
        XCTAssertEqual(feed.rooms.map(\.username), ["mike"])
    }

    func testPreviewMatchingCurrentChangesNothingVisible() {
        var feed = feed()
        feed.previewQuery(RoomQuery(text: "bo"))
        XCTAssertEqual(feed.rooms.map(\.username), ["bob"])
        XCTAssertEqual(feed.currentUsername, "bob")
    }

    func testPreviewThenClearTextRestoresAll() {
        var feed = feed()
        feed.previewQuery(RoomQuery(text: "zz"))
        XCTAssertEqual(feed.rooms.map(\.username), ["bob"])
        feed.previewQuery(RoomQuery(text: ""))
        XCTAssertEqual(feed.rooms.map(\.username), ["amy", "bob", "mike", "sofia"])
        XCTAssertEqual(feed.currentUsername, "bob")
    }
}
