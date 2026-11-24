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

    // MARK: 筛除只认实测确认的状态

    func testDelistStateNeedsConfirmedNonPublicStatusPerSite() {
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: defaults()))
        let offline = StreamTicket(success: true, url: nil, status: .offline)
        let secret = StreamTicket(success: true, url: nil, status: .privateShow)
        XCTAssertEqual(stage.delistState(offline, forRef: "alice"), .offline, "cb 的离线是接口实测")
        XCTAssertEqual(stage.delistState(secret, forRef: "alice"), .privateShow)
        XCTAssertNil(stage.delistState(offline, forRef: "c4:alice"), "Cam4 的离线是「这页没找到」推断出来的，不筛只跳过")
        XCTAssertEqual(stage.delistState(secret, forRef: "c4:alice"), .privateShow, "Cam4 目录明确标了私密")
        XCTAssertNil(stage.delistState(StreamTicket(success: true, url: nil, status: .unknown), forRef: "c4:alice"))
    }

    // MARK: 站点键迁移与同步加入快照的先后

    func testSiteKeyMigrationWaitsForJoinSnapshotRepair() {
        let v = SiteKeyMigration.version
        XCTAssertTrue(StageModel.shouldMigrateSiteKeys(version: 0, journal: .none, localWriteAllowed: true))
        XCTAssertFalse(StageModel.shouldMigrateSiteKeys(version: v, journal: .none, localWriteAllowed: true), "已迁移不再跑")
        for status in [SyncJoinJournalStatus.pending, .committed, .incomplete] {
            XCTAssertFalse(StageModel.shouldMigrateSiteKeys(version: 0, journal: status, localWriteAllowed: true),
                           "快照还在（\(status)）：先等启动恢复处理")
        }
        XCTAssertFalse(StageModel.shouldMigrateSiteKeys(version: 0, journal: .none, localWriteAllowed: false), "提交锁或待修复时不写偏好文件")
    }

    func testDeferredMigrationRecordsNoVersion() {
        let store = defaults()
        let settings = SettingsStore(defaults: store)
        StageModel.migrateSiteKeysIfAllowed(settings, journal: .pending, localWriteAllowed: true)
        XCTAssertEqual(settings.siteKeyVersion, 0, "推迟时不记版本")
        StageModel.migrateSiteKeysIfAllowed(settings, journal: .none, localWriteAllowed: true)
        XCTAssertEqual(settings.siteKeyVersion, SiteKeyMigration.version)
    }

    func testSyncProjectionKeepsCam4Entries() {
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: defaults()))
        stage.commit(Curation(favorites: ["Sample_One", "c4:sample_one"], blocked: ["c4:sample_two"]), nil)
        // 开启同步时由本机数据盖章出文档，之后合并与启动都由文档重建本机视图。
        let doc = SyncMigration.stampCreator(SyncLocalData(curation: stage.curation), clock: SyncClock(dev: 7), pt: 1_000).replica.doc
        stage.applySyncProjection(doc)
        XCTAssertTrue(stage.curation.isFavorite("c4:sample_one"), "投影不抹掉本机 Cam4 收藏")
        XCTAssertTrue(stage.curation.isFavorite("Sample_One"))
        XCTAssertTrue(stage.curation.isBlocked("c4:sample_two"))
        XCTAssertFalse(stage.curation.isBlocked("sample_two"), "同名 cb 房间不受影响")
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

    func testCam4FavoritesPageOnlyQueriesCam4AndSkipsChaturbate() async throws {
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: defaults()))
        let master = URL(string: "https://media.example/sample_one/master.m3u8?t=1")!
        let online = Room(username: "sample_one", viewers: 9, show: .publicShow, site: .cam4, stream: master)
        stage.sites = SiteRegistry(adapters: [StubCam4(master: master, online: online)])
        stage.chooseSite(.cam4)
        stage.commit(Curation(favorites: ["alice", "c4:sample_one"], blocked: []), nil)
        let page = try await stage.fetchFavoritesPage()
        XCTAssertEqual(page.rooms.map(\.ref), ["c4:sample_one"])
    }

    /// 盲审 3：Cam4 模式输入裸名提交搜索命中已在列表里的房间时，保留原房间元数据且多路只切焦点。
    func testCam4BareSearchFocusesExistingRoomAndPreservesMetadata() {
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: defaults()))
        stage.chooseSite(.cam4)
        let master = URL(string: "https://media.example/alice/master.m3u8")!
        let rich = Room(username: "alice", viewers: 88, show: .publicShow, site: .cam4, stream: master)
        install(stage, [c4("bob"), rich, c4("carol")])
        stage.setLaneCount(2)
        XCTAssertEqual(stage.name(of: stage.primary), "c4:bob")
        XCTAssertEqual(stage.name(of: stage.secondary!), "c4:alice")

        stage.searchText = "alice"
        stage.submitSearch()
        XCTAssertEqual(stage.focusLane, .secondary, "目标已在副路播时只切焦点，不重排主路")
        XCTAssertEqual(stage.feed.catalog.first { $0.ref == "c4:alice" }?.viewers, 88, "保留目录元数据不被空 direct 覆写")
        XCTAssertEqual(stage.feed.catalog.first { $0.ref == "c4:alice" }?.stream, master)
    }

    /// 盲审 4：质询通过后重跑全站搜索沿用当前模式的 directRoom 回退，不退回 Chaturbate。
    func testRetryAfterChallengeSiteSearchUsesModeDirectFallback() {
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: defaults()))
        stage.chooseSite(.cam4)
        stage.challengeResume = .siteSearch("sample_one")
        stage.retryAfterChallenge()
        XCTAssertEqual(stage.wiring.lastSiteSearch?.keyword, "sample_one")
        XCTAssertEqual(stage.wiring.lastSiteSearch?.fallback?.ref, "c4:sample_one")
        stage.cancelSiteSearch()
    }

    /// 盲审 8：Cam4 模式下收藏来源与全站搜索态空列表不被不支持筛选提示覆盖，且收藏数按当前模式计数。
    func testEmptyMessageCam4FavoritesAndSearchNotOverridden() {
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: defaults()))
        stage.chooseSite(.cam4)
        stage.region = .asia
        stage.source = .favorites
        stage.commit(Curation(favorites: ["cb_only_user"], blocked: []), nil)
        XCTAssertTrue(stage.emptyMessage.hasPrefix("还没有收藏"), stage.emptyMessage)

        stage.source = .fresh
        stage.siteSearch = "keyword"
        XCTAssertEqual(stage.emptyMessage, "目录里现在没有房间，可以稍后重试")
    }

    /// 复审 3：已有可见列表时切换站点失败，应回滚 siteMode、settings.siteMode 与 directory。
    func testChooseSiteFailureRollsBackModeAndDirectory() async {
        let store = defaults()
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: store))
        stage.ageConfirmed = true
        let failingCam4 = StubCam4(master: URL(string: "https://media.example/m.m3u8")!, failDirectory: true)
        stage.sites = SiteRegistry(adapters: [failingCam4])
        let beforeDirectory = stage.directory
        install(stage, [cb("alice")])

        stage.siteMode = .cam4
        stage.settings.siteMode = .cam4
        stage.directory = SiteDirectory(mode: .cam4, registry: stage.sites, chaturbate: stage.catalogAPI)
        await stage.reload()

        XCTAssertEqual(stage.siteMode, .chaturbate)
        XCTAssertEqual(SettingsStore(defaults: store).siteMode, .chaturbate)
        XCTAssertEqual(stage.directory.mode, .chaturbate)
        XCTAssertTrue(stage.directory === beforeDirectory, "切站失败后应恢复原 SiteDirectory 实例以保留游标")
    }
}

/// 只回固定主列表的 Cam4 桩。
private struct StubCam4: SiteAdapter {
    let master: URL
    var online: Room? = nil
    var failDirectory: Bool = false
    var site: SiteID { .cam4 }
    var capabilities: SiteCapabilities { .strictMaster }
    var challengeHome: URL? { nil }
    func fetchDirectory(_ query: CatalogQuery) async throws -> CatalogPage {
        if failDirectory { throw CatalogFailure.status(503) }
        return CatalogPage(rooms: [], total: 0, nextOffset: 0)
    }
    func resolveStream(_ room: Room) async throws -> SourcedTicket {
        SourcedTicket(ticket: StreamTicket(success: true, url: master, status: .publicShow), origin: .cam4Directory)
    }
    func status(of keys: [RoomKey], now: Date) async -> [RoomKey: FavoriteStatus] {
        guard let online else { return [:] }
        var out: [RoomKey: FavoriteStatus] = [:]
        for key in keys where key == online.key { out[key] = .online(online) }
        return out
    }
    func thumbnail(for room: Room, now: Date) -> URL? { nil }
    func mediaHeaders(for key: RoomKey) -> [String: String] { [:] }
    func masterFailure(status: Int) -> StreamFailure? { nil }
}
