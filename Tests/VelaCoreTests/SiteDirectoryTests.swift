import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import VelaCore

/// 舞台用的目录入口：按站点模式分发，混合模式把「各站游标之和」映射回各站游标。全部本地伪数据。
final class SiteDirectoryTests: XCTestCase {
    private func cbBody(_ names: [String], total: Int) -> Data {
        let rooms = names.map { #"{"username":"\#($0)","current_show":"public","num_users":1}"# }.joined(separator: ",")
        return Data(#"{"rooms":[\#(rooms)],"total_count":\#(total)}"#.utf8)
    }

    private func c4Body(_ names: [String], total: Int) -> Data {
        let users = names.map { #"{"username":"\#($0)","hlsPreviewUrl":"https://media.example/\#($0)/master.m3u8"}"# }
            .joined(separator: ",")
        return Data(#"{"users":[\#(users)],"totalCount":\#(total)}"#.utf8)
    }

    private func directory(_ mode: SiteMode, _ client: HostClient) -> SiteDirectory {
        let registry = SiteRegistry(adapters: [
            ChaturbateAdapter(client: client, backoff: nil),
            Cam4Adapter(client: client, backoff: nil, viewerRegions: [])
        ])
        return SiteDirectory(mode: mode, registry: registry, chaturbate: CatalogAPI(client: client, backoff: nil))
    }

    func testChaturbateModeIsTheExistingCatalogPath() async throws {
        let body = cbBody(["sample_one", "sample_two"], total: 9)
        let direct = HostClient(cb: [body])
        let staged = HostClient(cb: [body])
        let query = CatalogQuery(gender: .female, limit: 2, offset: 4)
        let expected = try await CatalogAPI(client: direct, backoff: nil).fetch(query)
        let page = try await directory(.chaturbate, staged).fetch(query)
        XCTAssertEqual(page, expected)
        XCTAssertEqual(staged.urls, direct.urls)
        XCTAssertTrue(staged.urls.allSatisfy { $0.host == "chaturbate.com" })
    }

    func testCam4ModeAsksOnlyCam4() async throws {
        let client = HostClient(c4: [c4Body(["sample_one"], total: 1)])
        let page = try await directory(.cam4, client).fetch(CatalogQuery(limit: 30))
        XCTAssertEqual(page.rooms.map(\.id), ["c4:sample_one"])
        XCTAssertEqual(page.rooms.first?.site, .cam4)
        XCTAssertEqual(client.urls.map(\.host), ["www.cam4.com"])
    }

    func testMixedNextPageResumesEachSiteFromItsOwnCursor() async throws {
        let client = HostClient(
            cb: [cbBody(["cb_a", "cb_b"], total: 10), cbBody(["cb_c", "cb_d"], total: 10)],
            c4: [c4Body(["c4_a", "c4_b"], total: 6), c4Body(["c4_c", "c4_d"], total: 6)]
        )
        let dir = directory(.mixed, client)
        let first = try await dir.fetch(CatalogQuery(limit: 2, offset: 0))
        XCTAssertEqual(first.rooms.map(\.id), ["cb_a", "c4:c4_a", "cb_b", "c4:c4_b"])
        XCTAssertEqual(first.nextOffset, 4)
        XCTAssertEqual(first.total, 16)

        let second = try await dir.fetch(CatalogQuery(limit: 2, offset: first.nextOffset))
        XCTAssertEqual(second.rooms.map(\.id), ["cb_c", "c4:c4_c", "cb_d", "c4:c4_d"])
        XCTAssertEqual(second.nextOffset, 8)
        let cbOffsets = client.urls.filter { $0.host == "chaturbate.com" }.map { $0.query(named: "offset") }
        let c4Pages = client.urls.filter { $0.host == "www.cam4.com" }.map { $0.query(named: "page") }
        XCTAssertEqual(cbOffsets, ["0", "2"])
        XCTAssertEqual(c4Pages, ["1", "2"])
    }

    func testMixedFetchPagesWalksTheRememberedCursors() async throws {
        let client = HostClient(
            cb: [cbBody(["cb_a", "cb_b"], total: 10), cbBody(["cb_c", "cb_d"], total: 10)],
            c4: [c4Body(["c4_a", "c4_b"], total: 6), c4Body(["c4_c", "c4_d"], total: 6)]
        )
        let merged = try await directory(.mixed, client).fetchPages(CatalogQuery(limit: 2), pages: 2)
        XCTAssertEqual(merged.rooms.count, 8)
        XCTAssertEqual(merged.nextOffset, 8)
        XCTAssertEqual(Set(merged.rooms.map(\.site)), [.chaturbate, .cam4])
    }

    func testMixedUnknownOffsetRestartsInsteadOfThrowing() async throws {
        let client = HostClient(cb: [cbBody(["cb_a"], total: 1)], c4: [c4Body(["c4_a"], total: 1)])
        let page = try await directory(.mixed, client).fetch(CatalogQuery(limit: 2, offset: 37))
        XCTAssertEqual(Set(page.rooms.map(\.id)), ["cb_a", "c4:c4_a"])
    }

    func testMixedOneSiteDownStillServesTheOther() async throws {
        let client = HostClient(cb: [cbBody(["cb_a"], total: 1)], c4: [], c4Status: 504)
        let dir = directory(.mixed, client)
        let page = try await dir.fetch(CatalogQuery(limit: 2))
        XCTAssertEqual(page.rooms.map(\.id), ["cb_a"])
        XCTAssertEqual(dir.lastFailedSites, [.cam4])
    }

    func testUsableThumbnailDropsSoonToExpireLinks() {
        let url = URL(string: "https://images.example/a.jpg")!
        let now = Date(timeIntervalSince1970: 1_000)
        XCTAssertEqual(Room(username: "cb_a", thumbnailURL: url).usableThumbnail(now: now), url, "没有过期时刻照用")
        let fresh = Room(username: "c4_a", thumbnailURL: url, site: .cam4, thumbnailExpires: now.addingTimeInterval(600))
        XCTAssertEqual(fresh.usableThumbnail(now: now), url)
        let stale = Room(username: "c4_a", thumbnailURL: url, site: .cam4, thumbnailExpires: now.addingTimeInterval(30))
        XCTAssertNil(stale.usableThumbnail(now: now), "离过期不足 60 秒不再用")
    }

    func testSiteOfRef() {
        XCTAssertEqual(RoomKey.site(ofRef: "alice"), .chaturbate)
        XCTAssertEqual(RoomKey.site(ofRef: "c4:sample_one"), .cam4)
        XCTAssertEqual(RoomKey.site(ofRef: "C4:Sample_One"), .cam4)
        XCTAssertEqual(RoomKey.site(ofRef: "cb:alice"), .chaturbate)
        XCTAssertEqual(RoomKey.site(ofRef: "vela-perf-room"), .chaturbate)
        XCTAssertEqual(RoomKey.site(ofRef: " c4:alice "), .cam4, "前导空白应先裁剪再识别站点")
        XCTAssertEqual(RoomKey.site(ofRef: "\tC4:bob\n"), .cam4)
        XCTAssertEqual(SiteMode.cam4.filterFavorites([" c4:alice ", "alice"]), [" c4:alice "])
    }

    /// 盲审 3：混合目录 offset=0 重载失败不能提前清空游标表，否则后续翻页回退第 1 页并永久判到底。
    func testMixedReloadFailureKeepsExistingPaginationCursors() async throws {
        let client = HostClient(
            cbResults: [
                HTTPResult(status: 200, data: cbBody(["cb_1", "cb_2"], total: 20)),
                HTTPResult(status: 503, data: Data("{}".utf8)),
                HTTPResult(status: 200, data: cbBody(["cb_3", "cb_4"], total: 20))
            ],
            c4Results: [
                HTTPResult(status: 200, data: c4Body(["c4_1", "c4_2"], total: 20)),
                HTTPResult(status: 503, data: Data("{}".utf8)),
                HTTPResult(status: 200, data: c4Body(["c4_3", "c4_4"], total: 20))
            ]
        )
        let dir = directory(.mixed, client)
        let page1 = try await dir.fetch(CatalogQuery(limit: 2, offset: 0))
        XCTAssertEqual(page1.nextOffset, 4)

        do {
            _ = try await dir.fetch(CatalogQuery(limit: 2, offset: 0))
            XCTFail("模拟的重载应失败")
        } catch {}

        var feed = FeedStore()
        feed.replace(with: page1)
        let page2 = try await dir.fetch(CatalogQuery(limit: 2, offset: page1.nextOffset))
        XCTAssertEqual(page2.nextOffset, 8, "重载失败后继续翻页应沿原游标前进")
        XCTAssertEqual(page2.rooms.map(\.id), ["cb_3", "c4:c4_3", "cb_4", "c4:c4_4"])
        feed.append(page2, from: page1.nextOffset)
        XCTAssertFalse(feed.reachedEnd)

        let cbOffsets = client.urls.filter { $0.host == "chaturbate.com" }.map { $0.query(named: "offset") }
        let c4Pages = client.urls.filter { $0.host == "www.cam4.com" }.map { $0.query(named: "page") }
        XCTAssertEqual(cbOffsets, ["0", "0", "2"])
        XCTAssertEqual(c4Pages, ["1", "1", "2"])
    }

    func testTargetedTagQueryDoesNotClearMixedRegularCursors() async throws {
        let client = HostClient(
            cb: [
                cbBody(["cb_1", "cb_2"], total: 20),
                cbBody(["cb_3", "cb_4"], total: 20),
                cbBody(["cb_5", "cb_6"], total: 20),
                cbBody(["cb_t1"], total: 5),
                cbBody(["cb_t2"], total: 5),
                cbBody(["cb_7", "cb_8"], total: 20)
            ],
            c4: [
                c4Body(["c4_1", "c4_2"], total: 20),
                c4Body(["c4_3", "c4_4"], total: 20),
                c4Body(["c4_5", "c4_6"], total: 20),
                c4Body(["c4_7", "c4_8"], total: 20)
            ]
        )
        let dir = directory(.mixed, client)
        let regular = try await dir.fetchPages(CatalogQuery(limit: 2, offset: 0), pages: 3)
        XCTAssertEqual(regular.nextOffset, 12)

        // 模拟「猜你喜欢」续页结束后并发发出的 offset=0 标签定向查询（Cam4 不支持标签会直接返回空页）
        _ = try await dir.fetchPages(CatalogQuery(tag: "asian", limit: 90, offset: 0), pages: 1)
        _ = try await dir.fetchPages(CatalogQuery(tag: "latina", limit: 90, offset: 0), pages: 1)

        var feed = FeedStore()
        feed.replace(with: regular)
        let page4 = try await dir.fetch(CatalogQuery(limit: 2, offset: regular.nextOffset))
        XCTAssertEqual(page4.nextOffset, 16)
        XCTAssertEqual(page4.rooms.map(\.id), ["cb_7", "c4:c4_7", "cb_8", "c4:c4_8"])
        feed.append(page4, from: regular.nextOffset)
        XCTAssertFalse(feed.reachedEnd)

        let cbRegularOffsets = client.urls
            .filter { $0.host == "chaturbate.com" && $0.query(named: "hashtags") == nil }
            .map { $0.query(named: "offset") }
        let c4Pages = client.urls
            .filter { $0.host == "www.cam4.com" }
            .map { $0.query(named: "page") }
        XCTAssertEqual(cbRegularOffsets, ["0", "2", "4", "6"])
        XCTAssertEqual(c4Pages, ["1", "2", "3", "4"])
    }
}

/// 按主机分别排队应答，记下每次请求的地址。
private final class HostClient: HTTPClient, @unchecked Sendable {
    private let lock = NSLock()
    private var cb: [HTTPResult]
    private var c4: [HTTPResult]
    private let c4Status: Int
    private(set) var urls: [URL] = []

    init(cb: [Data] = [], c4: [Data] = [], c4Status: Int = 200) {
        self.cb = cb.map { HTTPResult(status: 200, data: $0) }
        self.c4 = c4.map { HTTPResult(status: 200, data: $0) }
        self.c4Status = c4Status
    }

    init(cbResults: [HTTPResult], c4Results: [HTTPResult]) {
        self.cb = cbResults
        self.c4 = c4Results
        self.c4Status = 200
    }

    func send(_ request: URLRequest) async throws -> HTTPResult {
        lock.lock()
        defer { lock.unlock() }
        let url = request.url!
        urls.append(url)
        if url.host == "www.cam4.com" {
            guard c4Status == 200, !c4.isEmpty else { return HTTPResult(status: c4Status == 200 ? 404 : c4Status, data: Data("{}".utf8)) }
            return c4.removeFirst()
        }
        guard !cb.isEmpty else { return HTTPResult(status: 404, data: Data("{}".utf8)) }
        return cb.removeFirst()
    }
}

private extension URL {
    func query(named name: String) -> String? {
        URLComponents(url: self, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == name }?.value
    }
}
