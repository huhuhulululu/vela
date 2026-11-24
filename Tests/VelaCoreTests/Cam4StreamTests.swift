import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import VelaCore

final class Cam4StreamTests: XCTestCase {
    private let hls = "https://preview.example/sample_one/master.m3u8?token=abc"

    func testFreshHintMakesNoRequest() async throws {
        let script = ScriptClient(results: [page(users: [user("sample_one", hls: hls)])])
        let clock = Clock(Date(timeIntervalSince1970: 1_000))
        let adapter = Cam4Adapter(client: script, backoff: EndpointBackoffBox(), viewerRegions: [], clock: { clock.date })
        let listed = try await adapter.fetchDirectory(CatalogQuery())
        let room = try XCTUnwrap(listed.rooms.first)
        let before = script.requests.count
        let ticket = try await adapter.resolveStream(room)
        XCTAssertEqual(script.requests.count, before)
        XCTAssertEqual(ticket.origin, .cam4Directory)
        XCTAssertEqual(ticket.ticket.url, room.stream)
        XCTAssertNil(ticket.failure)
        let status = await adapter.status(of: [room.key], now: clock.date)
        XCTAssertEqual(status[room.key]?.room?.ref, "c4:sample_one")
    }

    func testExpiredPageMissIsOfflineAndPrivateIsPrivate() async throws {
        let script = ScriptClient(results: [
            page(users: [user("sample_one", hls: hls), user("hidden_one", hls: hls, privateRoom: true)]),
            page(users: [user("other_name", hls: hls)])
        ])
        let clock = Clock(Date(timeIntervalSince1970: 1_000))
        let adapter = Cam4Adapter(client: script, backoff: EndpointBackoffBox(), viewerRegions: [], clock: { clock.date })
        _ = try await adapter.fetchDirectory(CatalogQuery(limit: 90, offset: 0))
        let room = Room(username: "sample_one", show: .publicShow, site: .cam4, stream: URL(string: hls))
        clock.date = clock.date.addingTimeInterval(Cam4Adapter.streamTTL + 1)
        let offline = try await adapter.resolveStream(room)
        XCTAssertEqual(offline.ticket.failure, .offline)
        XCTAssertEqual(script.requests.count, 2)
        XCTAssertEqual(URLComponents(url: script.requests[1].url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "page" }?.value, "1")

        let again = ScriptClient(results: [page(users: [user("hidden_one", hls: hls, privateRoom: true)])])
        let fresh = Cam4Adapter(client: again, backoff: EndpointBackoffBox(), viewerRegions: [], clock: { Date() })
        _ = try await fresh.fetchDirectory(CatalogQuery())
        let priv = try await fresh.resolveStream(Room(username: "hidden_one", site: .cam4))
        XCTAssertEqual(again.requests.count, 1, "索引还新鲜，私密不再请求")
        XCTAssertEqual(priv.ticket.failure, .privateShow)
    }

    func testRefetchUsesThePageItWasSeenOn() async throws {
        let script = ScriptClient(results: [
            page(users: [user("sample_one", hls: hls)]),
            page(users: [user("sample_one", hls: hls)])
        ])
        let clock = Clock(Date(timeIntervalSince1970: 5_000))
        let adapter = Cam4Adapter(client: script, backoff: EndpointBackoffBox(), viewerRegions: [], clock: { clock.date })
        _ = try await adapter.fetchDirectory(CatalogQuery(limit: 90, offset: 90))
        clock.date = clock.date.addingTimeInterval(Cam4Adapter.streamTTL + 5)
        let ticket = try await adapter.resolveStream(Room(username: "sample_one", site: .cam4))
        XCTAssertNil(ticket.failure)
        XCTAssertEqual(URLComponents(url: script.requests[1].url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "page" }?.value, "2")
    }

    func testGeoAndUnknownKeyAndCancellation() async throws {
        let script = ScriptClient(results: [
            page(users: [user("sample_one", hls: hls, blocked: ["643"])]),
            page(users: [user("other_name", hls: hls)])
        ])
        let adapter = Cam4Adapter(client: script, backoff: EndpointBackoffBox(), viewerRegions: ["RU"], clock: { Date() })
        let page = try await adapter.fetchDirectory(CatalogQuery())
        XCTAssertTrue(page.rooms.isEmpty, "被地区屏蔽的房间不进目录")
        let geo = try await adapter.resolveStream(Room.direct(key: RoomKey(site: .cam4, name: "sample_one")!))
        XCTAssertEqual(geo.ticket.failure, .geo)
        XCTAssertEqual(script.requests.count, 1)
        let missing = try await adapter.resolveStream(Room(username: "never_seen", site: .cam4))
        XCTAssertEqual(missing.ticket.failure, .offline)
        XCTAssertEqual(URLComponents(url: script.requests.last?.url ?? URL(string: "https://x")!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "page" }?.value, "1")

        let cancel = ScriptClient(error: CancellationError())
        let cancelling = Cam4Adapter(client: cancel, backoff: nil, viewerRegions: [])
        do {
            _ = try await cancelling.resolveStream(Room(username: "sample_one", site: .cam4))
            XCTFail("取消应原样抛出")
        } catch is CancellationError {
        } catch {
            XCTFail("意外错误 \(error)")
        }
    }

    /// 盲审 PR #13：观看地区被主播屏蔽的房间不进入目录（不出现在列表里），索引仍记着它，
    /// 收藏状态给「当前地区不可观看」、直达起播给 geo。观看地区为空时不过滤。
    func testGeoBlockedRoomsStayOutOfTheDirectory() async throws {
        let script = ScriptClient(results: [
            page(users: [user("sample_one", hls: hls, blocked: ["643"]), user("sample_two", hls: hls)])
        ])
        let adapter = Cam4Adapter(client: script, backoff: nil, viewerRegions: ["RU"], clock: { Date() })
        let listed = try await adapter.fetchDirectory(CatalogQuery())
        XCTAssertEqual(listed.rooms.map(\.username), ["sample_two"])
        XCTAssertEqual(listed.total, 100, "游标与总数仍按原页计，翻页不受影响")
        let key = RoomKey(site: .cam4, name: "sample_one")!
        let status = await adapter.status(of: [key], now: Date())
        XCTAssertEqual(status[key], .restricted)
        let direct = try await adapter.resolveStream(Room.direct(key: key))
        XCTAssertEqual(direct.ticket.failure, .geo)
        XCTAssertEqual(script.requests.count, 1)

        let open = ScriptClient(results: [page(users: [user("sample_one", hls: hls, blocked: ["643"])])])
        let anywhere = Cam4Adapter(client: open, backoff: nil, viewerRegions: [])
        let all = try await anywhere.fetchDirectory(CatalogQuery())
        XCTAssertEqual(all.rooms.map(\.username), ["sample_one"], "观看地区为空不过滤")
    }

    func testStaleStatusIsOmittedAndBlockedIsRestricted() async throws {
        let script = ScriptClient(results: [page(users: [user("sample_one", hls: hls, blocked: ["840"])])])
        let clock = Clock(Date(timeIntervalSince1970: 8_000))
        let adapter = Cam4Adapter(client: script, backoff: nil, viewerRegions: ["US"], clock: { clock.date })
        let listed = try await adapter.fetchDirectory(CatalogQuery())
        XCTAssertTrue(listed.rooms.isEmpty)
        let key = RoomKey(site: .cam4, name: "sample_one")!
        let fresh = await adapter.status(of: [key], now: clock.date)
        XCTAssertEqual(fresh[key], .restricted)
        let stale = await adapter.status(of: [key], now: clock.date.addingTimeInterval(Cam4Adapter.statusTTL + 1))
        XCTAssertNil(stale[key])
        let unknown = await adapter.status(of: [RoomKey(parsing: "c4:other")!], now: clock.date)
        XCTAssertTrue(unknown.isEmpty)
    }

    func testThumbnailExpiresInsideOneMinute() {
        let adapter = Cam4Adapter(client: ScriptClient(), backoff: nil, viewerRegions: [])
        let url = URL(string: "https://snapshots.example/a.jpg?sign=1")!
        let now = Date(timeIntervalSince1970: 1_000)
        var room = Room(username: "sample_one", thumbnailURL: url, site: .cam4, thumbnailExpires: now.addingTimeInterval(120))
        XCTAssertEqual(adapter.thumbnail(for: room, now: now), url)
        room.thumbnailExpires = now.addingTimeInterval(30)
        XCTAssertNil(adapter.thumbnail(for: room, now: now))
        room.thumbnailExpires = now.addingTimeInterval(-1)
        XCTAssertNil(adapter.thumbnail(for: room, now: now))
        room.thumbnailExpires = nil
        XCTAssertEqual(adapter.thumbnail(for: room, now: now), url)
    }

    func testHTMLBacksOffAndIsNotAChallenge() async {
        let box = EndpointBackoffBox()
        let html = ScriptClient(results: [HTTPResult(status: 200, data: Data("<html>Just a moment</html>".utf8))])
        let adapter = Cam4Adapter(client: html, backoff: box, viewerRegions: [])
        do {
            _ = try await adapter.fetchDirectory(CatalogQuery())
            XCTFail("应失败")
        } catch let error as CatalogFailure {
            XCTAssertEqual(error, .malformed)
            XCTAssertFalse(error.isChallenge)
        } catch {
            XCTFail("\(error)")
        }
        XCTAssertFalse(box.allows(.cam4Directory))
        do {
            _ = try await adapter.fetchDirectory(CatalogQuery())
            XCTFail("退避中不应再请求")
        } catch let error as EndpointBackedOff {
            XCTAssertEqual(error, EndpointBackedOff(.cam4Directory))
        } catch {
            XCTFail("\(error)")
        }
        XCTAssertEqual(html.requests.count, 1)
    }

    func testNon2xxAndUnsupportedFiltersMakeNoExtraRequest() async throws {
        let box = EndpointBackoffBox()
        let script = ScriptClient(results: [HTTPResult(status: 502, data: Data("<html>bad gateway</html>".utf8))])
        let adapter = Cam4Adapter(client: script, backoff: box, viewerRegions: [])
        do {
            _ = try await adapter.fetchDirectory(CatalogQuery())
            XCTFail("应失败")
        } catch let error as CatalogFailure {
            XCTAssertEqual(error, .status(502))
        }
        XCTAssertFalse(box.allows(.cam4Directory))

        let quiet = ScriptClient(results: [
            page(users: [user("sample_one", hls: hls)]),
            page(users: [user("sample_one", hls: hls)])
        ])
        let cam = Cam4Adapter(client: quiet, backoff: EndpointBackoffBox(), viewerRegions: [])
        for query in [
            CatalogQuery(region: .asia),
            CatalogQuery(tag: "talk"),
            CatalogQuery(newCams: true)
        ] {
            let empty = try await cam.fetchDirectory(query)
            XCTAssertEqual(empty, CatalogPage(rooms: [], total: 0, nextOffset: 0))
        }
        XCTAssertTrue(quiet.requests.isEmpty)
        let male = try await cam.fetchDirectory(CatalogQuery(gender: .male, keywords: "nope"))
        XCTAssertTrue(male.rooms.isEmpty)
        XCTAssertEqual(quiet.requests.count, 1)
        let matched = try await cam.fetchDirectory(CatalogQuery(gender: .female, keywords: "sample"))
        XCTAssertEqual(matched.rooms.map(\.username), ["sample_one"])
    }

    /// 审计发现 2：性别或地区本地过滤把首页滤空时，若原页未到底应继续往后取到匹配房间。
    func testClientFilteredEmptyFirstPageContinuesToLaterMatchingPage() async throws {
        let genderClient = ScriptClient(results: [
            page(users: [user("f1", hls: hls, gender: "female"), user("f2", hls: hls, gender: "female")], total: 6),
            page(users: [user("m1", hls: hls, gender: "male"), user("m2", hls: hls, gender: "male")], total: 6)
        ])
        let genderAdapter = Cam4Adapter(client: genderClient, backoff: nil, viewerRegions: [])
        let malePage = try await genderAdapter.fetchDirectory(CatalogQuery(gender: .male, limit: 2, offset: 0))
        XCTAssertEqual(malePage.rooms.map(\.username), ["m1", "m2"])
        XCTAssertEqual(malePage.nextOffset, 4)
        XCTAssertEqual(malePage.total, 6)
        XCTAssertEqual(genderClient.requests.count, 2)

        let geoClient = ScriptClient(results: [
            page(users: [user("b1", hls: hls, blocked: ["US"]), user("b2", hls: hls, blocked: ["US"])], total: 6),
            page(users: [user("ok1", hls: hls), user("ok2", hls: hls)], total: 6)
        ])
        let geoAdapter = Cam4Adapter(client: geoClient, backoff: nil, viewerRegions: ["US"])
        let visiblePage = try await geoAdapter.fetchDirectory(CatalogQuery(limit: 2, offset: 0))
        XCTAssertEqual(visiblePage.rooms.map(\.username), ["ok1", "ok2"])
        XCTAssertEqual(visiblePage.nextOffset, 4)
        XCTAssertEqual(geoClient.requests.count, 2)
    }

    /// 审计发现 6：单站多页拉取时，本地地区/性别过滤造成短页不应让 CatalogWindow 提前停在第 1 页。
    func testSingleSiteMultiPageContinuesThroughFilteredShortPage() async throws {
        let client = ScriptClient(results: [
            page(users: [user("b1", hls: hls, blocked: ["US"]), user("ok1", hls: hls)], total: 12),
            page(users: [user("b2", hls: hls, blocked: ["US"]), user("ok2", hls: hls)], total: 12),
            page(users: [user("b3", hls: hls, blocked: ["US"]), user("ok3", hls: hls)], total: 12),
            page(users: [user("b4", hls: hls, blocked: ["US"]), user("ok4", hls: hls)], total: 12),
            page(users: [user("b5", hls: hls, blocked: ["US"]), user("ok5", hls: hls)], total: 12),
            page(users: [user("b6", hls: hls, blocked: ["US"]), user("ok6", hls: hls)], total: 12)
        ])
        let adapter = Cam4Adapter(client: client, backoff: nil, viewerRegions: ["US"])
        let dir = SiteDirectory(
            mode: .cam4,
            registry: SiteRegistry(adapters: [adapter]),
            chaturbate: CatalogAPI(client: client, backoff: nil)
        )
        let merged = try await dir.fetchPages(CatalogQuery(limit: 2, offset: 0), pages: 3)
        XCTAssertEqual(merged.rooms.map(\.username), ["ok1", "ok2", "ok3", "ok4", "ok5", "ok6"])
        XCTAssertEqual(merged.nextOffset, 12)
    }

    /// 审计发现 3：小页（如热门 limit=40）非首页房间过期刷新时，必须按原页大小与原页码重取。
    func testExpiredRefetchKeepsOriginalSmallPageSizeAndPageNumber() async throws {
        let target = user("sample_100", hls: hls)
        let decoy = user("other_room", hls: hls)
        let page3Data = try! JSONSerialization.data(withJSONObject: ["users": [target], "totalCount": 200])
        let decoyData = try! JSONSerialization.data(withJSONObject: ["users": [decoy], "totalCount": 200])
        let client = QueryRoutingClient { url in
            let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
            let page = items.first { $0.name == "page" }?.value
            let size = items.first { $0.name == "resultsPerPage" }?.value
            if page == "3", size == "40" {
                return HTTPResult(status: 200, data: page3Data)
            }
            return HTTPResult(status: 200, data: decoyData)
        }
        let clock = Clock(Date(timeIntervalSince1970: 5_000))
        let adapter = Cam4Adapter(client: client, backoff: nil, viewerRegions: [], clock: { clock.date })
        let listed = try await adapter.fetchDirectory(CatalogQuery(limit: 40, offset: 80))
        XCTAssertEqual(listed.rooms.map(\.username), ["sample_100"])

        clock.date = clock.date.addingTimeInterval(Cam4Adapter.streamTTL + 5)
        let ticket = try await adapter.resolveStream(Room(username: "sample_100", site: .cam4))
        XCTAssertNil(ticket.failure, "应按原 page=3&resultsPerPage=40 刷新并保持在线")
        XCTAssertEqual(ticket.ticket.url?.absoluteString, hls)
        XCTAssertEqual(client.requests.count, 2)
        let items = URLComponents(url: client.requests[1].url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(items.first { $0.name == "page" }?.value, "3")
        XCTAssertEqual(items.first { $0.name == "resultsPerPage" }?.value, "40")
    }

    /// 盲审 1：第 1 页含不合规用户名被丢弃导致 raw.rooms.count < limit 时，顺延扫描不能在第 1 页提前中止。
    func testFilteredScanContinuesWhenRawPageDropsInvalidUsername() async throws {
        let client = ScriptClient(results: [
            page(users: [user("bad-name", hls: hls, gender: "female"), user("f1", hls: hls, gender: "female")], total: 6),
            page(users: [user("m1", hls: hls, gender: "male"), user("m2", hls: hls, gender: "male")], total: 6)
        ])
        let adapter = Cam4Adapter(client: client, backoff: nil, viewerRegions: [])
        let result = try await adapter.fetchDirectory(CatalogQuery(gender: .male, limit: 2, offset: 0))
        XCTAssertEqual(result.rooms.map(\.username), ["m1", "m2"])
        XCTAssertEqual(result.nextOffset, 4)
        let pages = client.requests.map { URLComponents(url: $0.url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "page" }?.value }
        XCTAssertEqual(pages, ["1", "2"])
    }

    /// 复审 1：前 5 个完整原页（达到 maxFilterScans）只命中 1 间时，fetchPages 仍应继续拉第 6 页。
    func testMultiPageCollectContinuesPastMaxFilterScansShortPage() async throws {
        let client = ScriptClient(results: [
            page(users: [user("b1", hls: hls, blocked: ["US"]), user("b2", hls: hls, blocked: ["US"])], total: 20),
            page(users: [user("b3", hls: hls, blocked: ["US"]), user("b4", hls: hls, blocked: ["US"])], total: 20),
            page(users: [user("ok1", hls: hls), user("b5", hls: hls, blocked: ["US"])], total: 20),
            page(users: [user("b6", hls: hls, blocked: ["US"]), user("b7", hls: hls, blocked: ["US"])], total: 20),
            page(users: [user("b8", hls: hls, blocked: ["US"]), user("b9", hls: hls, blocked: ["US"])], total: 20),
            page(users: [user("ok2", hls: hls), user("ok3", hls: hls)], total: 20),
            page(users: [user("ok4", hls: hls), user("ok5", hls: hls)], total: 20)
        ])
        let adapter = Cam4Adapter(client: client, backoff: nil, viewerRegions: ["US"])
        let dir = SiteDirectory(
            mode: .cam4,
            registry: SiteRegistry(adapters: [adapter]),
            chaturbate: CatalogAPI(client: client, backoff: nil)
        )
        let merged = try await dir.fetchPages(CatalogQuery(limit: 2, offset: 0), pages: 3)
        XCTAssertEqual(merged.rooms.map(\.username), ["ok1", "ok2", "ok3", "ok4", "ok5"])
        XCTAssertEqual(merged.nextOffset, 14)
    }

    /// 复审 1：前 5 个完整原页全被地区屏蔽过滤为空页但游标仍推进且未到底，fetchPages 不能把空页当结束。
    func testMultiPageCollectContinuesPastMaxFilterScansEmptyPage() async throws {
        let client = ScriptClient(results: [
            page(users: [user("b1", hls: hls, blocked: ["US"]), user("b2", hls: hls, blocked: ["US"])], total: 20),
            page(users: [user("b3", hls: hls, blocked: ["US"]), user("b4", hls: hls, blocked: ["US"])], total: 20),
            page(users: [user("b5", hls: hls, blocked: ["US"]), user("b6", hls: hls, blocked: ["US"])], total: 20),
            page(users: [user("b7", hls: hls, blocked: ["US"]), user("b8", hls: hls, blocked: ["US"])], total: 20),
            page(users: [user("b9", hls: hls, blocked: ["US"]), user("b10", hls: hls, blocked: ["US"])], total: 20),
            page(users: [user("ok1", hls: hls), user("ok2", hls: hls)], total: 20)
        ])
        let adapter = Cam4Adapter(client: client, backoff: nil, viewerRegions: ["US"])
        let dir = SiteDirectory(
            mode: .cam4,
            registry: SiteRegistry(adapters: [adapter]),
            chaturbate: CatalogAPI(client: client, backoff: nil)
        )
        let merged = try await dir.fetchPages(CatalogQuery(limit: 2, offset: 0), pages: 2)
        XCTAssertEqual(merged.rooms.map(\.username), ["ok1", "ok2"])
        XCTAssertEqual(merged.nextOffset, 12)
    }

    /// 盲审 5：关键词带 c4: 前缀时 Cam4Adapter.filtered 应识别房间 ref/identity，不白扫 5 页。
    func testKeywordsFilterMatchesCam4Prefix() async throws {
        let client = ScriptClient(results: [
            page(users: [user("sample_one", hls: hls), user("other_two", hls: hls)], total: 4),
            page(users: [user("other_three", hls: hls), user("other_four", hls: hls)], total: 4)
        ])
        let adapter = Cam4Adapter(client: client, backoff: nil, viewerRegions: [])
        let result = try await adapter.fetchDirectory(CatalogQuery(limit: 2, offset: 0, keywords: "c4:sample_one"))
        XCTAssertEqual(result.rooms.map(\.username), ["sample_one"])
        XCTAssertEqual(client.requests.count, 2)
    }

    /// 盲审 5（复审）：TTL 内流地址提前 403/失败经 invalidateStream 失效后，重拉原页取新地址，并受短冷却防抖保护。
    func testInvalidateStreamRefetchesOriginalPageWithinTTLAndHonorsCooldown() async throws {
        let freshHLS = "https://preview.example/sample_one/master.m3u8?token=fresh"
        let newerHLS = "https://preview.example/sample_one/master.m3u8?token=newer"
        let client = ScriptClient(results: [
            page(users: [user("sample_one", hls: hls)], total: 200),
            page(users: [user("sample_one", hls: freshHLS)], total: 200),
            page(users: [user("sample_one", hls: newerHLS)], total: 200)
        ])
        let clock = Clock(Date(timeIntervalSince1970: 10_000))
        let adapter = Cam4Adapter(client: client, backoff: nil, viewerRegions: [], clock: { clock.date })
        _ = try await adapter.fetchDirectory(CatalogQuery(limit: 40, offset: 80))
        let key = RoomKey(site: .cam4, name: "sample_one")!
        let room = Room.direct(key: key)

        // TTL 内失效后立即重拉原页（page=3, resultsPerPage=40）拿到新地址。
        clock.date = clock.date.addingTimeInterval(30)
        adapter.invalidateStream(for: key)
        let refreshed = try await adapter.resolveStream(room)
        XCTAssertEqual(refreshed.ticket.url?.absoluteString, freshHLS)
        XCTAssertEqual(client.requests.count, 2)
        let items = URLComponents(url: client.requests[1].url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(items.first { $0.name == "page" }?.value, "3")
        XCTAssertEqual(items.first { $0.name == "resultsPerPage" }?.value, "40")

        // 冷却窗口内再次失效不重复刷目录。
        clock.date = clock.date.addingTimeInterval(2)
        adapter.invalidateStream(for: key)
        let throttled = try await adapter.resolveStream(room)
        XCTAssertEqual(throttled.ticket.url?.absoluteString, freshHLS)
        XCTAssertEqual(client.requests.count, 2, "短冷却期内不应高频刷目录")

        // 冷却过后再次解析会重新拉取。
        clock.date = clock.date.addingTimeInterval(Cam4Adapter.refreshCooldown)
        let afterCooldown = try await adapter.resolveStream(room)
        XCTAssertEqual(afterCooldown.ticket.url?.absoluteString, newerHLS)
        XCTAssertEqual(client.requests.count, 3)
    }

    private func user(
        _ name: String, hls: String? = nil, privateRoom: Bool = false, blocked: [String] = [], gender: String = "female"
    ) -> [String: Any] {
        var item: [String: Any] = [
            "username": name,
            "privateRoom": privateRoom,
            "gender": gender,
            "viewers": 3,
            "snapshotImageLink": "https://snapshots.example/\(name).jpg?sign=1"
        ]
        if let hls { item["hlsPreviewUrl"] = hls }
        if !blocked.isEmpty { item["blockedRegions"] = blocked }
        return item
    }

    private func page(users: [[String: Any]], total: Int = 100) -> HTTPResult {
        let data = try! JSONSerialization.data(withJSONObject: ["users": users, "totalCount": total])
        return HTTPResult(status: 200, data: data)
    }
}

private final class QueryRoutingClient: HTTPClient, @unchecked Sendable {
    private let handler: @Sendable (URL) -> HTTPResult
    private(set) var requests: [URLRequest] = []

    init(_ handler: @escaping @Sendable (URL) -> HTTPResult) { self.handler = handler }

    func send(_ request: URLRequest) async throws -> HTTPResult {
        requests.append(request)
        return handler(request.url!)
    }
}

private final class ScriptClient: HTTPClient, @unchecked Sendable {
    var results: [HTTPResult]
    var requests: [URLRequest] = []
    var error: Error?
    init(results: [HTTPResult] = [], error: Error? = nil) {
        self.results = results
        self.error = error
    }
    func send(_ request: URLRequest) async throws -> HTTPResult {
        requests.append(request)
        if let error { throw error }
        return results.isEmpty ? HTTPResult(status: 500, data: Data()) : results.removeFirst()
    }
}

private final class Clock: @unchecked Sendable {
    var date: Date
    init(_ date: Date) { self.date = date }
    func now() -> Date { date }
}
