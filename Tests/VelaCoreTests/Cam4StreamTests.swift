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

    private func user(
        _ name: String, hls: String? = nil, privateRoom: Bool = false, blocked: [String] = []
    ) -> [String: Any] {
        var item: [String: Any] = [
            "username": name,
            "privateRoom": privateRoom,
            "gender": "female",
            "viewers": 3,
            "snapshotImageLink": "https://snapshots.example/\(name).jpg?sign=1"
        ]
        if let hls { item["hlsPreviewUrl"] = hls }
        if !blocked.isEmpty { item["blockedRegions"] = blocked }
        return item
    }

    private func page(users: [[String: Any]]) -> HTTPResult {
        let data = try! JSONSerialization.data(withJSONObject: ["users": users, "totalCount": 100])
        return HTTPResult(status: 200, data: data)
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
