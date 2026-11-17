import XCTest
@testable import VelaCore

/// 中继多源表。标志按源 id 分开；超过 `maxSources` 淘汰最先注册的；清空后查询一律为假。
final class RelaySourceTableTests: XCTestCase {
    /// 两路的刷新与静音标志互不影响，音轨循环只问这一路的 MuteAudioPolicy。
    func testTwoSourcesKeepIndependentFlags() {
        var table = RelaySourceTable()
        XCTAssertNil(table.register(id: "a", audioSuppressed: true))
        XCTAssertNil(table.register(id: "b", audioSuppressed: false))

        table.setRefreshing(true, id: "a")
        table.setRefreshing(false, id: "b")
        XCTAssertTrue(table.isRefreshing(id: "a"))
        XCTAssertFalse(table.isRefreshing(id: "b"))
        XCTAssertTrue(table.isAudioSuppressed(id: "a"))
        XCTAssertFalse(table.isAudioSuppressed(id: "b"))
        XCTAssertTrue(table.shouldRunLoop(id: "a", track: .video))
        XCTAssertFalse(table.shouldRunLoop(id: "a", track: .audio))
        XCTAssertFalse(table.shouldRunLoop(id: "b", track: .video))
        XCTAssertFalse(table.shouldRunLoop(id: "b", track: .audio))

        table.setAudioSuppressed(false, id: "a")
        table.setRefreshing(true, id: "b")
        table.setAudioSuppressed(true, id: "b")
        XCTAssertFalse(table.isAudioSuppressed(id: "a"))
        XCTAssertTrue(table.isRefreshing(id: "a"))
        XCTAssertTrue(table.shouldRunLoop(id: "a", track: .audio))
        XCTAssertTrue(table.isAudioSuppressed(id: "b"))
        XCTAssertTrue(table.shouldRunLoop(id: "b", track: .video))
        XCTAssertFalse(table.shouldRunLoop(id: "b", track: .audio))

        XCTAssertEqual(
            table.shouldRunLoop(id: "a", track: .audio),
            MuteAudioPolicy.shouldRefreshAudioPlaylist(
                hasSeparateAudio: true,
                relayRefreshing: table.isRefreshing(id: "a"),
                suppressAudioFetch: table.isAudioSuppressed(id: "a")
            )
        )
        XCTAssertEqual(
            table.shouldRunLoop(id: "b", track: .audio),
            MuteAudioPolicy.shouldRefreshAudioPlaylist(
                hasSeparateAudio: true,
                relayRefreshing: table.isRefreshing(id: "b"),
                suppressAudioFetch: table.isAudioSuppressed(id: "b")
            )
        )

        table.remove(id: "a")
        XCTAssertFalse(table.contains(id: "a"))
        XCTAssertFalse(table.isRefreshing(id: "a"))
        XCTAssertFalse(table.isAudioSuppressed(id: "a"))
        XCTAssertFalse(table.shouldRunLoop(id: "a", track: .video))
        XCTAssertFalse(table.shouldRunLoop(id: "a", track: .audio))
        XCTAssertTrue(table.contains(id: "b"))
        XCTAssertTrue(table.isRefreshing(id: "b"))
        XCTAssertTrue(table.isAudioSuppressed(id: "b"))
    }

    /// 上限 2。第三个新源淘汰最先注册的那个；重复登记不占新名额，也不改先后。
    func testThirdSourceEvictsTheOldest() {
        XCTAssertEqual(RelaySourceTable.maxSources, 2)
        var table = RelaySourceTable()
        XCTAssertNil(table.register(id: "oldest", audioSuppressed: true))
        XCTAssertNil(table.register(id: "middle", audioSuppressed: false))
        table.setRefreshing(true, id: "oldest")
        table.setRefreshing(true, id: "middle")
        // 碰过最旧的源，也不该变成最新。
        table.setAudioSuppressed(false, id: "oldest")
        XCTAssertNil(table.register(id: "middle", audioSuppressed: true))

        XCTAssertEqual(table.register(id: "newest", audioSuppressed: true), "oldest")
        XCTAssertFalse(table.contains(id: "oldest"))
        XCTAssertFalse(table.isRefreshing(id: "oldest"))
        XCTAssertFalse(table.isAudioSuppressed(id: "oldest"))
        XCTAssertFalse(table.shouldRunLoop(id: "oldest", track: .video))
        XCTAssertFalse(table.shouldRunLoop(id: "oldest", track: .audio))

        XCTAssertTrue(table.contains(id: "middle"))
        XCTAssertTrue(table.isRefreshing(id: "middle"))
        XCTAssertTrue(table.isAudioSuppressed(id: "middle"))
        XCTAssertTrue(table.shouldRunLoop(id: "middle", track: .video))
        XCTAssertFalse(table.shouldRunLoop(id: "middle", track: .audio))

        XCTAssertTrue(table.contains(id: "newest"))
        XCTAssertFalse(table.isRefreshing(id: "newest"))
        XCTAssertTrue(table.isAudioSuppressed(id: "newest"))
        XCTAssertFalse(table.shouldRunLoop(id: "newest", track: .video))
        XCTAssertFalse(table.shouldRunLoop(id: "newest", track: .audio))
    }

    /// removeAll 之后，登记、刷新、静音、两条循环全是假。
    func testRemoveAllClearsEveryQuery() {
        var table = RelaySourceTable()
        _ = table.register(id: "a", audioSuppressed: true)
        _ = table.register(id: "b", audioSuppressed: false)
        table.setRefreshing(true, id: "a")
        table.setRefreshing(true, id: "b")

        table.removeAll()
        for id in ["a", "b", "never"] {
            XCTAssertFalse(table.contains(id: id), id)
            XCTAssertFalse(table.isRefreshing(id: id), id)
            XCTAssertFalse(table.isAudioSuppressed(id: id), id)
            XCTAssertFalse(table.shouldRunLoop(id: id, track: .video), id)
            XCTAssertFalse(table.shouldRunLoop(id: id, track: .audio), id)
        }
    }
}
