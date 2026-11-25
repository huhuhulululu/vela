import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import VelaCore

final class SiteCatalogTests: XCTestCase {
    func testSingleSiteMatchesDirectFetch() async throws {
        let body = Data(#"{"rooms":[{"username":"sample_one","current_show":"public","num_users":2}],"total_count":8}"#.utf8)
        let result = HTTPResult(status: 200, data: body)
        let directClient = OneShot(result)
        let mixedClient = OneShot(result)
        let query = CatalogQuery(limit: 30, offset: 0)
        let direct = try await ChaturbateAdapter(client: directClient, backoff: nil).fetchDirectory(query)
        let registry = SiteRegistry(adapters: [ChaturbateAdapter(client: mixedClient, backoff: nil)])
        let fetched = try await SiteCatalog.fetch(
            mode: .chaturbate, query: query, cursor: MixedCursor(sites: [.chaturbate]), registry: registry
        )
        XCTAssertEqual(fetched.page, direct)
        XCTAssertTrue(fetched.failures.isEmpty)
        XCTAssertEqual(fetched.cursor.totals[.chaturbate], direct.total)
        XCTAssertEqual(fetched.cursor.offsets[.chaturbate], direct.nextOffset)
    }

    func testMixedInterleavesDedupesSumsAndRecordsOneFailure() async throws {
        let cb = FakeAdapter(site: .chaturbate, rooms: [
            Room(username: "alice", show: .publicShow),
            Room(username: "bella", show: .publicShow)
        ], total: 10, step: 2)
        let c4 = FakeAdapter(site: .cam4, rooms: [
            Room(username: "alice", show: .publicShow),
            Room(username: "alice", show: .publicShow, site: .cam4)
        ], total: 4, step: 2)
        let registry = SiteRegistry(adapters: [cb, c4])
        let fetched = try await SiteCatalog.fetch(
            mode: .mixed, query: CatalogQuery(limit: 2), cursor: MixedCursor(sites: [.chaturbate, .cam4]), registry: registry
        )
        XCTAssertEqual(fetched.page.rooms.map(\.id), ["alice", "bella", "c4:alice"])
        XCTAssertEqual(fetched.page.total, 14)
        XCTAssertEqual(fetched.page.nextOffset, 4)
        XCTAssertFalse(fetched.cursor.isFinished)

        let failing = FakeAdapter(site: .cam4, rooms: [], total: 0, step: 0, error: SiteError.unsupported(.cam4))
        let partial = try await SiteCatalog.fetch(
            mode: .mixed, query: CatalogQuery(), cursor: MixedCursor(sites: [.chaturbate, .cam4]),
            registry: SiteRegistry(adapters: [cb, failing])
        )
        XCTAssertEqual(partial.page.rooms.map(\.id), ["alice", "bella"])
        XCTAssertEqual(partial.failures.keys.sorted { $0.rawValue < $1.rawValue }, [.cam4])
        XCTAssertEqual(partial.failures[.cam4] as? SiteError, .unsupported(.cam4))

        do {
            _ = try await SiteCatalog.fetch(
                mode: .mixed, query: CatalogQuery(), cursor: MixedCursor(sites: [.chaturbate, .cam4]),
                registry: SiteRegistry(adapters: [
                    FakeAdapter(site: .chaturbate, rooms: [], total: 0, step: 0, error: SiteError.unsupported(.chaturbate)),
                    failing
                ])
            )
            XCTFail("全失败应抛第一站")
        } catch let error as SiteError {
            XCTAssertEqual(error, .unsupported(.chaturbate))
        }
    }

    func testUnsupportedFilterDoesNotRequestCam4() async throws {
        let cb = FakeAdapter(site: .chaturbate, rooms: [Room(username: "alice", show: .publicShow)], total: 1, step: 1)
        let c4 = FakeAdapter(site: .cam4, rooms: [Room(username: "zz_cam", show: .publicShow, site: .cam4)], total: 1, step: 1, rejectFilters: true)
        let fetched = try await SiteCatalog.fetch(
            mode: .mixed, query: CatalogQuery(tag: "talk"), cursor: MixedCursor(sites: [.chaturbate, .cam4]),
            registry: SiteRegistry(adapters: [cb, c4])
        )
        XCTAssertEqual(cb.calls, 1)
        XCTAssertEqual(c4.calls, 0, "混合模式下不支持该查询的站点直接跳过，不计入成功")
        XCTAssertEqual(c4.networkCalls, 0)
        XCTAssertEqual(fetched.page.rooms.map(\.id), ["alice"])
        XCTAssertTrue(fetched.cursor.ended.contains(.cam4))
    }

    /// 盲审 1：混合模式下携带 Cam4 不支持的筛选条件时，Chaturbate 的质询与错误不能被 Cam4 空页吞掉。
    func testMixedUnsupportedFilterPropagatesSupportedSiteError() async throws {
        let queries = [
            CatalogQuery(region: .asia),
            CatalogQuery(tag: "talk"),
            CatalogQuery(newCams: true)
        ]
        for query in queries {
            let cb = FakeAdapter(site: .chaturbate, rooms: [], total: 0, step: 0, error: CatalogFailure.challenge)
            let c4 = FakeAdapter(site: .cam4, rooms: [], total: 0, step: 0, rejectFilters: true)
            do {
                _ = try await SiteCatalog.fetch(
                    mode: .mixed, query: query, cursor: MixedCursor(sites: [.chaturbate, .cam4]),
                    registry: SiteRegistry(adapters: [cb, c4])
                )
                XCTFail("应抛出 Chaturbate 的质询错误: \(query)")
            } catch let error as CatalogFailure {
                XCTAssertEqual(error, .challenge)
            }
            XCTAssertEqual(c4.calls, 0)
        }

        // 单站 Cam4 模式遇到不支持的筛选条件仍返回空页而不报错。
        let c4Only = FakeAdapter(site: .cam4, rooms: [], total: 0, step: 0, rejectFilters: true)
        let empty = try await SiteCatalog.fetch(
            mode: .cam4, query: CatalogQuery(region: .asia), cursor: MixedCursor(sites: [.cam4]),
            registry: SiteRegistry(adapters: [c4Only])
        )
        XCTAssertTrue(empty.page.rooms.isEmpty)
        XCTAssertTrue(empty.failures.isEmpty)
    }

    /// 盲审 6：单站 nextOffset 超出缩水 total 或一站已尽另一站失败时，聚合 total 不应提前判到底。
    func testMixedTotalDoesNotEndEarlyWhenOneSiteShrinksOrFails() async throws {
        // 情形 A：c4 缩水导致 nextOffset (180) > total (120) 且已尽，cb 仍有余量 (460 / 500)。
        let cbA = ScriptedAdapter(site: .chaturbate, pages: [
            CatalogPage(rooms: [Room(username: "cb_1", show: .publicShow)], total: 500, nextOffset: 460)
        ])
        let c4A = ScriptedAdapter(site: .cam4, pages: [
            CatalogPage(rooms: [Room(username: "c4_1", show: .publicShow, site: .cam4)], total: 120, nextOffset: 180)
        ])
        let resA = try await SiteCatalog.fetch(
            mode: .mixed, query: CatalogQuery(limit: 90), cursor: MixedCursor(sites: [.chaturbate, .cam4]),
            registry: SiteRegistry(adapters: [cbA, c4A])
        )
        XCTAssertFalse(resA.cursor.isFinished)
        XCTAssertEqual(resA.page.nextOffset, 640)
        XCTAssertGreaterThan(resA.page.total, resA.page.nextOffset, "已尽站点的超额游标不能侵占另一站余量")
        XCTAssertEqual(resA.page.total, 680)

        // 情形 B：cb 首页已尽 (20/20)，c4 首次失败 (0/0 未结束)。
        let cbB = FakeAdapter(site: .chaturbate, rooms: [Room(username: "cb_1", show: .publicShow)], total: 20, step: 20)
        let c4B = FakeAdapter(site: .cam4, rooms: [], total: 0, step: 0, error: CatalogFailure.status(503))
        let resB = try await SiteCatalog.fetch(
            mode: .mixed, query: CatalogQuery(limit: 20), cursor: MixedCursor(sites: [.chaturbate, .cam4]),
            registry: SiteRegistry(adapters: [cbB, c4B])
        )
        XCTAssertFalse(resB.cursor.isFinished)
        XCTAssertEqual(resB.page.nextOffset, 20)
        XCTAssertGreaterThan(resB.page.total, resB.page.nextOffset, "仍有未结束站点时 total 必须大于 nextOffset")
    }

    /// 盲审 7：并发拉取时若一侧抛出 CancellationError，必须重抛而非按单站成功返回。
    func testMixedCancellationRethrowsInsteadOfReturningPartial() async {
        let cb = FakeAdapter(site: .chaturbate, rooms: [Room(username: "cb_1", show: .publicShow)], total: 10, step: 1)
        let c4 = FakeAdapter(site: .cam4, rooms: [], total: 0, step: 0, error: CancellationError())
        do {
            _ = try await SiteCatalog.fetch(
                mode: .mixed, query: CatalogQuery(limit: 2), cursor: MixedCursor(sites: [.chaturbate, .cam4]),
                registry: SiteRegistry(adapters: [cb, c4])
            )
            XCTFail("应重抛 CancellationError")
        } catch is CancellationError {
            // 预期
        } catch {
            XCTFail("意外错误: \(error)")
        }
    }

    /// 审计发现 5：Cam4 某页被本地过滤为空但游标仍前进且未到 total 时，混合目录不能把它标为 ended。
    func testMixedFilteredEmptyPageWithAdvancingCursorDoesNotEndSite() async throws {
        let cb = ScriptedAdapter(site: .chaturbate, pages: [
            CatalogPage(rooms: [Room(username: "cb_1", show: .publicShow)], total: 10, nextOffset: 2),
            CatalogPage(rooms: [Room(username: "cb_2", show: .publicShow)], total: 10, nextOffset: 4)
        ])
        let c4 = ScriptedAdapter(site: .cam4, pages: [
            CatalogPage(rooms: [], total: 10, nextOffset: 2),
            CatalogPage(rooms: [Room(username: "c4_found", show: .publicShow, site: .cam4)], total: 10, nextOffset: 4)
        ])
        let registry = SiteRegistry(adapters: [cb, c4])
        let first = try await SiteCatalog.fetch(
            mode: .mixed, query: CatalogQuery(limit: 2), cursor: MixedCursor(sites: [.chaturbate, .cam4]), registry: registry
        )
        XCTAssertFalse(first.cursor.ended.contains(.cam4), "游标仍在前进，不能永久跳过 Cam4")
        XCTAssertEqual(first.cursor.offsets[.cam4], 2)

        let second = try await SiteCatalog.fetch(
            mode: .mixed, query: CatalogQuery(limit: 2), cursor: first.cursor, registry: registry
        )
        XCTAssertEqual(c4.requestedOffsets, [0, 2])
        XCTAssertEqual(second.page.rooms.map(\.id), ["cb_2", "c4:c4_found"])
    }
}

private final class ScriptedAdapter: SiteAdapter, @unchecked Sendable {
    let site: SiteID
    private var pages: [CatalogPage]
    private(set) var requestedOffsets: [Int] = []

    init(site: SiteID, pages: [CatalogPage]) {
        self.site = site
        self.pages = pages
    }

    var capabilities: SiteCapabilities {
        site == .chaturbate ? [.serverGender, .serverRegion, .serverTag, .freshCams, .keywordSearch] : [.strictMaster]
    }
    var challengeHome: URL? { nil }

    func fetchDirectory(_ query: CatalogQuery) async throws -> CatalogPage {
        requestedOffsets.append(query.offset)
        return pages.isEmpty ? CatalogPage(rooms: [], total: 0, nextOffset: query.offset) : pages.removeFirst()
    }

    func resolveStream(_ room: Room) async throws -> SourcedTicket {
        SourcedTicket(ticket: StreamTicket(success: false, url: nil, status: .offline), origin: .edge)
    }
    func status(of keys: [RoomKey], now: Date) async -> [RoomKey: FavoriteStatus] { [:] }
    func thumbnail(for room: Room, now: Date) -> URL? { nil }
    func mediaHeaders(for key: RoomKey) -> [String: String] { [:] }
    func masterFailure(status: Int) -> StreamFailure? { nil }
}

private final class OneShot: HTTPClient, @unchecked Sendable {
    let result: HTTPResult
    init(_ result: HTTPResult) { self.result = result }
    func send(_ request: URLRequest) async throws -> HTTPResult { result }
}

private final class FakeAdapter: SiteAdapter, @unchecked Sendable {
    let site: SiteID
    let rooms: [Room]
    let total: Int
    let step: Int
    let error: Error?
    let rejectFilters: Bool
    private(set) var calls = 0
    private(set) var networkCalls = 0

    init(site: SiteID, rooms: [Room], total: Int, step: Int, error: Error? = nil, rejectFilters: Bool = false) {
        self.site = site
        self.rooms = rooms
        self.total = total
        self.step = step
        self.error = error
        self.rejectFilters = rejectFilters
    }

    var capabilities: SiteCapabilities {
        site == .chaturbate ? [.serverGender, .serverRegion, .serverTag, .freshCams, .keywordSearch] : [.strictMaster]
    }
    var challengeHome: URL? { nil }

    func fetchDirectory(_ query: CatalogQuery) async throws -> CatalogPage {
        calls += 1
        if rejectFilters, query.region != nil || query.tag != nil || query.newCams {
            return CatalogPage(rooms: [], total: 0, nextOffset: 0)
        }
        networkCalls += 1
        if let error { throw error }
        return CatalogPage(rooms: rooms, total: total, nextOffset: query.offset + step)
    }

    func resolveStream(_ room: Room) async throws -> SourcedTicket {
        SourcedTicket(ticket: StreamTicket(success: false, url: nil, status: .offline), origin: .edge)
    }
    func status(of keys: [RoomKey], now: Date) async -> [RoomKey: FavoriteStatus] { [:] }
    func thumbnail(for room: Room, now: Date) -> URL? { nil }
    func mediaHeaders(for key: RoomKey) -> [String: String] { [:] }
    func masterFailure(status: Int) -> StreamFailure? { nil }
}
