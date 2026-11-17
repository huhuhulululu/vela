import XCTest
@testable import VelaCore

private func room(_ name: String, viewers: Int = 5, show: ShowState = .publicShow, thumb: String? = nil, seconds: Int? = nil) -> Room {
    Room(username: name, viewers: viewers, show: show, thumbnailURL: thumb.flatMap { URL(string: $0) }, secondsOnline: seconds)
}

final class FavoritesBoardTests: XCTestCase {
    func testFourStatesClassified() {
        let board = FavoritesBoard.make(
            favorites: ["on", "off", "gone", "unk"],
            statuses: ["on": .online(room("on")), "off": .offline, "gone": .notFound],
            catalog: []
        )
        XCTAssertEqual(board.online.map(\.username), ["on"])
        XCTAssertEqual(board.online.map(\.state), [.online])
        XCTAssertEqual(board.others.map(\.username), ["off", "unk", "gone"])
        XCTAssertEqual(board.others.map(\.state), [.offline, .unknown, .missing])
        XCTAssertEqual(board.onlineCount, 1)
        XCTAssertFalse(board.isEmpty)
        XCTAssertNotNil(board.online[0].room)
        XCTAssertTrue(board.others.allSatisfy { $0.room == nil && $0.thumbnailURL == nil })
    }

    func testStatusesWinOverCatalog() {
        let board = FavoritesBoard.make(
            favorites: ["alice", "bob"],
            statuses: ["alice": .offline, "bob": .online(room("bob", viewers: 7))],
            catalog: [room("alice", viewers: 99), room("bob", viewers: 1)]
        )
        XCTAssertEqual(board.online.map(\.username), ["bob"])
        XCTAssertEqual(board.online[0].room?.viewers, 7)
        XCTAssertEqual(board.others.map(\.state), [.offline])
    }

    func testCatalogFillsOnlyPlayableRooms() {
        let board = FavoritesBoard.make(
            favorites: ["pub", "priv", "away"],
            statuses: [:],
            catalog: [room("PUB", viewers: 3), room("priv", show: .privateShow), room("away", show: .away)]
        )
        XCTAssertEqual(board.online.map(\.username), ["pub"])
        XCTAssertEqual(board.online[0].room?.viewers, 3)
        XCTAssertEqual(board.others.map(\.username), ["priv", "away"])
        XCTAssertEqual(board.others.map(\.state), [.unknown, .unknown])
    }

    func testOnlineSortedByViewersStableOnTies() {
        let board = FavoritesBoard.make(
            favorites: ["a", "b", "c", "d"],
            statuses: [
                "a": .online(room("a", viewers: 10)),
                "b": .online(room("b", viewers: 50)),
                "c": .online(room("c", viewers: 10)),
                "d": .online(room("d", viewers: 50)),
            ],
            catalog: []
        )
        XCTAssertEqual(board.online.map(\.username), ["b", "d", "a", "c"])
    }

    func testOthersSegmentOrder() {
        let board = FavoritesBoard.make(
            favorites: ["m1", "u1", "o1", "m2", "o2", "u2"],
            statuses: ["m1": .notFound, "m2": .notFound, "o1": .offline, "o2": .offline],
            catalog: []
        )
        XCTAssertEqual(board.others.map(\.username), ["o1", "o2", "u1", "u2", "m1", "m2"])
    }

    func testThumbnailSource() {
        let board = FavoritesBoard.make(
            favorites: ["HasThumb", "NoThumb"],
            statuses: [
                "hasthumb": .online(room("HasThumb", viewers: 2, thumb: "https://example.com/x.jpg")),
                "nothumb": .online(room("NoThumb", viewers: 1)),
            ],
            catalog: []
        )
        XCTAssertEqual(board.online[0].thumbnailURL, URL(string: "https://example.com/x.jpg"))
        XCTAssertEqual(board.online[1].thumbnailURL, URL(string: "https://thumb.live.mmcdn.com/riw/nothumb.jpg"))
    }

    func testDetailText() {
        let board = FavoritesBoard.make(
            favorites: ["long", "short", "fresh", "off", "gone", "unk"],
            statuses: [
                "long": .online(room("long", viewers: 12_000, seconds: 3_900)),
                "short": .online(room("short", viewers: 300, seconds: 600)),
                "fresh": .online(room("fresh", viewers: 5)),
                "off": .offline,
                "gone": .notFound,
            ],
            catalog: []
        )
        XCTAssertEqual(board.online.map(\.detail), [
            "1.2万人在看 · 已开播 1 小时 5 分",
            "300人在看 · 已开播 10 分钟",
            "5人在看",
        ])
        XCTAssertEqual(board.others.map(\.detail), ["离线", "未查询", "账号不存在"])
        for entry in board.online + board.others {
            XCTAssertFalse(entry.detail.contains("—") || entry.detail.contains("–"))
        }
    }

    func testDedupeCaseInsensitiveAndBlankIgnored() {
        let board = FavoritesBoard.make(
            favorites: ["Alice", "  ", "", "alice", "ALICE", "Bob", "bob"],
            statuses: ["ALICE": .offline, "BoB": .online(room("bob"))],
            catalog: []
        )
        XCTAssertEqual(board.others.map(\.username), ["Alice"])
        XCTAssertEqual(board.others.map(\.state), [.offline])
        XCTAssertEqual(board.online.map(\.username), ["Bob"])
        XCTAssertEqual(board.online.map(\.id), ["Bob"])
    }

    func testEmptyFavorites() {
        let board = FavoritesBoard.make(favorites: [], statuses: ["x": .offline], catalog: [room("y")])
        XCTAssertTrue(board.isEmpty)
        XCTAssertEqual(board.onlineCount, 0)
        XCTAssertEqual(board, FavoritesBoard(online: [], others: []))
    }

    func testMenuSummary() {
        let none = FavoritesBoard.make(favorites: [], statuses: [:], catalog: [])
        XCTAssertEqual(none.menuSummary(checking: false), "还没有收藏")
        let on = FavoritesBoard.make(favorites: ["a", "b"], statuses: ["a": .online(room("a")), "b": .online(room("b"))], catalog: [])
        XCTAssertEqual(on.menuSummary(checking: true), "2 位在线")
        let off = FavoritesBoard.make(favorites: ["a"], statuses: ["a": .offline], catalog: [])
        XCTAssertEqual(off.menuSummary(checking: false), "收藏的人现在都不在线")
        XCTAssertEqual(off.menuSummary(checking: true), "正在查询…")
        let unknown = FavoritesBoard.make(favorites: ["a"], statuses: [:], catalog: [])
        XCTAssertEqual(unknown.menuSummary(checking: false), "还没有查询状态")
    }
}
