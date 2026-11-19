import XCTest
@testable import VelaPhone

/// 站点接线（docs/plans/sites.md Phase B）：站点模式的默认与记住、启动迁移、按 RoomKey 选房两两不同、
/// Cam4 房间经适配器解析、站点请求头与主列表判定、直达输入。年龄未确认：不发目录与起播请求；适配器用桩，不访问真实站点。
@MainActor
final class SiteWiringTests: XCTestCase {
    private var suites: [String] = []

    override func setUp() {
        super.setUp()
        TestPrefs.reset()
    }

    override func tearDown() {
        TestPrefs.reset()
        for name in suites { UserDefaults().removePersistentDomain(forName: name) }
        suites = []
        PlaylistRelay.shared.allowStart = true
        super.tearDown()
    }

    private func defaults() -> UserDefaults {
        let name = "vela-phone-sites-\(UUID().uuidString)"
        suites.append(name)
        return UserDefaults(suiteName: name)!
    }

    private func c4(_ name: String) -> Room { Room.direct(key: RoomKey(site: .cam4, name: name)!) }
    private func cb(_ name: String) -> Room { Room.direct(username: name)! }

    private func install(_ stage: StageModel, _ rooms: [Room]) {
        stage.updateFeed(intent: .reload) {
            $0.replace(with: CatalogPage(rooms: rooms, total: rooms.count, nextOffset: rooms.count))
        }
    }

    private func laneNames(_ stage: StageModel) -> [String] {
        stage.lanes.compactMap { stage.name(of: $0) }
    }

    // MARK: 站点模式

    func testSiteModeDefaultsToChaturbateAndIsRemembered() {
        let store = defaults()
        XCTAssertEqual(SettingsStore(defaults: store).siteMode, .chaturbate, "全新安装默认 Chaturbate")
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: store))
        XCTAssertEqual(stage.siteMode, .chaturbate)
        stage.chooseSite(.cam4)
        XCTAssertEqual(stage.siteMode, .cam4)
        XCTAssertEqual(stage.directory.mode, .cam4)
        XCTAssertEqual(SettingsStore(defaults: store).siteMode, .cam4, "选择落盘")
        let again = TestPrefs.stage(settings: SettingsStore(defaults: store))
        XCTAssertEqual(again.siteMode, .cam4, "重启后恢复")

        let bogus = defaults()
        bogus.set("myspace", forKey: "siteMode")
        XCTAssertEqual(SettingsStore(defaults: bogus).siteMode, .chaturbate, "认不出的值按 Chaturbate")
    }

    // MARK: 启动迁移

    func testStartupMigratesStoredKeysOnceAndKeepsData() throws {
        let url = CurationStore.defaultURL()
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(#"{"favorites":["Sample_One"],"blocked":["sample_two"]}"#.utf8).write(to: url)
        let store = defaults()
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: store))
        XCTAssertTrue(stage.curation.isFavorite("sample_one"))
        XCTAssertTrue(stage.curation.isBlocked("cb:sample_two"))
        let text = String(decoding: try Data(contentsOf: url), as: UTF8.self)
        XCTAssertTrue(text.contains("cb:Sample_One"), text)
        XCTAssertTrue(text.contains("keyVersion"), text)
        XCTAssertTrue(FileManager.default.fileExists(atPath: SiteKeyMigration.backupURL(for: url).path), "迁移前留备份")
        XCTAssertEqual(SettingsStore(defaults: store).siteKeyVersion, SiteKeyMigration.version)
    }

    // MARK: 选房按 RoomKey

    func testSameNameOnTwoSitesCanShareTheStageButNeverOneRoomTwice() {
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: defaults()))
        install(stage, [cb("alice"), c4("alice"), c4("bob"), cb("carol")])
        stage.setLaneCount(3)
        let names = laneNames(stage)
        XCTAssertEqual(names.count, 3)
        XCTAssertEqual(Set(names.map { RoomKey.normalizeRef($0) }).count, 3, "三路两两不同 \(names)")
        XCTAssertTrue(stage.lanesDistinct)
        XCTAssertTrue(names.contains("alice"))
        XCTAssertTrue(names.contains("c4:alice"), "同名的 Cam4 房间是另一间 \(names)")
    }

    func testBlockingCam4RoomLeavesTheChaturbateNamesake() {
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: defaults()))
        install(stage, [c4("alice"), cb("alice"), cb("carol"), c4("dave")])
        XCTAssertEqual(stage.feed.currentUsername, "c4:alice")
        stage.blockCurrent(stage.target(.primary))
        XCTAssertTrue(stage.curation.isBlocked("c4:alice"))
        XCTAssertFalse(stage.curation.isBlocked("alice"))
        XCTAssertFalse(stage.feed.rooms.contains { $0.ref == "c4:alice" })
        XCTAssertTrue(stage.feed.rooms.contains { $0.ref == "alice" })
        XCTAssertNotEqual(stage.feed.currentUsername, "c4:alice")
    }

    // MARK: 解析与媒体

    func testCam4RoomResolvesThroughItsAdapter() async throws {
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: defaults()))
        let master = URL(string: "https://media.example/sample_one/master.m3u8?t=1")!
        stage.sites = SiteRegistry(adapters: [StubCam4(master: master)])
        install(stage, [c4("sample_one")])
        let sourced = try await stage.resolveSourced("c4:sample_one")
        XCTAssertEqual(sourced.origin, .cam4Directory)
        XCTAssertEqual(sourced.ticket.url, master)
        XCTAssertTrue(sourced.ticket.isPlayable)
    }

    func testMediaHeadersAndStrictMasterFollowTheRoomSite() {
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: defaults()))
        XCTAssertEqual(stage.mediaHeaders(forRef: "c4:sample_one")["Referer"], "https://www.cam4.com/")
        XCTAssertEqual(stage.mediaHeaders(forRef: "alice")["Referer"], "https://chaturbate.com/")
        XCTAssertEqual(stage.mediaHeaders(forRef: "alice"), PlaylistRelay.defaultHeaders, "cb 与站点抽象之前写死的头相同")
        XCTAssertEqual(stage.masterFailure(forRef: "c4:sample_one", status: 504), .unavailable, "Cam4 个别 CDN 504 直接跳过")
        XCTAssertNil(stage.masterFailure(forRef: "c4:sample_one", status: 200))
        XCTAssertNil(stage.masterFailure(forRef: "alice", status: 504), "cb 仍交给中继，行为不变")
    }

    // MARK: 直达输入

    func testDirectRoomFollowsPrefixThenSiteMode() {
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: defaults()))
        XCTAssertEqual(stage.directRoom("c4:Sample_One")?.ref, "c4:sample_one")
        XCTAssertEqual(stage.directRoom("sample_one")?.ref, "sample_one")
        stage.chooseSite(.mixed)
        XCTAssertEqual(stage.directRoom("sample_one")?.site, .chaturbate, "混合模式裸名按 cb")
        stage.chooseSite(.cam4)
        XCTAssertEqual(stage.directRoom("sample_one")?.ref, "c4:sample_one", "Cam4 模式裸名按 c4")
        XCTAssertNil(stage.directRoom("c4:x"))
        XCTAssertNil(stage.directRoom("zz:sample_one"))
    }
}

/// 只回固定主列表的 Cam4 桩。
private struct StubCam4: SiteAdapter {
    let master: URL
    var site: SiteID { .cam4 }
    var capabilities: SiteCapabilities { .strictMaster }
    var challengeHome: URL? { nil }
    func fetchDirectory(_ query: CatalogQuery) async throws -> CatalogPage { CatalogPage(rooms: [], total: 0, nextOffset: 0) }
    func resolveStream(_ room: Room) async throws -> SourcedTicket {
        SourcedTicket(ticket: StreamTicket(success: true, url: master, status: .publicShow), origin: .cam4Directory)
    }
    func status(of keys: [RoomKey], now: Date) async -> [RoomKey: FavoriteStatus] { [:] }
    func thumbnail(for room: Room, now: Date) -> URL? { nil }
    func mediaHeaders(for key: RoomKey) -> [String: String] { [:] }
    func masterFailure(status: Int) -> StreamFailure? { nil }
}
