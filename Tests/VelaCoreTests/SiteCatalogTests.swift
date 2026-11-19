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
        XCTAssertEqual(c4.calls, 1, "适配器会被问到，但自己不发请求")
        XCTAssertEqual(c4.networkCalls, 0)
        XCTAssertEqual(fetched.page.rooms.map(\.id), ["alice"])
        XCTAssertTrue(fetched.cursor.ended.contains(.cam4))
    }
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

    var capabilities: SiteCapabilities { site == .chaturbate ? [.serverTag] : [.strictMaster] }
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
