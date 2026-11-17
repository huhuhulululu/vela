import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import VelaCore

/// 站点房间列表接口（/api/ts/roomlist/room-list/）：URL 构造、解码、开播时长与多页候选池。
final class CatalogTests: XCTestCase {
    /// 默认共用的非 JSON 退避记录跨用例清空，免得前一个用例的退避挡住后一个。
    override func setUp() {
        super.setUp()
        EndpointBackoffBox.shared.reset()
    }

    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func values(_ url: URL) -> [String: String] {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
    }

    func testURLWithoutFilters() {
        let url = CatalogQuery(limit: 40, offset: 80).url()
        XCTAssertEqual(url.host, "chaturbate.com")
        XCTAssertTrue(url.absoluteString.hasPrefix("https://chaturbate.com/api/ts/roomlist/room-list/?"))
        XCTAssertEqual(values(url), ["limit": "40", "offset": "80"])
    }

    func testURLWithAllFilters() {
        let query = CatalogQuery(gender: .female, region: .asia, tag: "jazz", limit: 90, offset: 90)
        XCTAssertEqual(values(query.url()), ["limit": "90", "offset": "90", "genders": "f", "regions": "AS", "hashtags": "jazz"])
    }

    func testURLClampsPagingAndNeverCarriesAffiliateParameters() {
        let url = CatalogQuery(gender: .trans, limit: 900, offset: -4).url()
        let v = values(url)
        XCTAssertEqual(v["limit"], "90")
        XCTAssertEqual(v["offset"], "0")
        XCTAssertEqual(v["genders"], "t")
        for key in ["wm", "client_ip", "format", "gender", "region", "hd", "tag"] { XCTAssertNil(v[key]) }
        XCTAssertFalse(url.absoluteString.contains("affiliates"))
    }

    func testRegionSiteCodes() {
        let codes = Region.allCases.map { CatalogQuery(region: $0).url() }.map { values($0)["regions"] }
        XCTAssertEqual(codes, ["AS", "ER", "NA", "SA", "O"])
    }

    func testHashtagNormalization() {
        XCTAssertEqual(CatalogQuery(tag: " #Asian ").url().query?.contains("hashtags=asian"), true)
        XCTAssertEqual(values(CatalogQuery(tag: "big ass").url())["hashtags"], "big")
        XCTAssertNil(values(CatalogQuery(tag: "  # ").url())["hashtags"])
        XCTAssertNil(values(CatalogQuery(tag: nil).url())["hashtags"])
    }

    func testDecodeMapsRoomFields() throws {
        let start = Int(now.timeIntervalSince1970) - 3660
        let page = try RoomListDecoder.decode(
            Self.payload(rooms: [
                Self.room("alpha", gender: "f", users: 12_000, start: start, age: 25),
                Self.room("beta", gender: "t", show: "private", users: 3, start: nil, age: 99, image: "http://images.example/b.jpg")
            ], total: 321),
            requestLimit: 90, requestOffset: 90, now: now
        )
        XCTAssertEqual(page.total, 321)
        XCTAssertEqual(page.nextOffset, 92)
        let first = try XCTUnwrap(page.rooms.first)
        XCTAssertEqual(first.username, "alpha")
        XCTAssertEqual(first.displayName, "alpha")
        XCTAssertEqual(first.gender, .female)
        XCTAssertEqual(first.show, .publicShow)
        XCTAssertFalse(first.isHD)
        XCTAssertEqual(first.viewers, 12_000)
        XCTAssertEqual(first.followers, 50)
        XCTAssertEqual(first.tags, ["a", "b"])
        XCTAssertEqual(first.age, 25)
        XCTAssertEqual(first.secondsOnline, 3660)
        XCTAssertEqual(first.thumbnailURL?.absoluteString, "https://images.example/alpha.jpg")
        let second = page.rooms[1]
        XCTAssertEqual(second.gender, .trans)
        XCTAssertEqual(second.show, .privateShow)
        XCTAssertNil(second.age)
        XCTAssertNil(second.secondsOnline)
        XCTAssertEqual(second.thumbnailURL?.scheme, "https")
    }

    func testPasswordRoomIsNotPlayable() throws {
        let page = try RoomListDecoder.decode(
            Self.payload(rooms: [Self.room("locked", password: true)], total: 1),
            requestLimit: 90, requestOffset: 0, now: now
        )
        XCTAssertEqual(page.rooms.first?.show, .password)
    }

    func testSecondsOnlineConversion() {
        let n = Int(now.timeIntervalSince1970)
        XCTAssertEqual(RoomListRoom.secondsOnline(startTimestamp: n - 90, now: now), 90)
        XCTAssertEqual(RoomListRoom.secondsOnline(startTimestamp: n, now: now), 0)
        XCTAssertNil(RoomListRoom.secondsOnline(startTimestamp: n + 5, now: now))
        XCTAssertNil(RoomListRoom.secondsOnline(startTimestamp: 0, now: now))
        XCTAssertNil(RoomListRoom.secondsOnline(startTimestamp: -3, now: now))
        XCTAssertNil(RoomListRoom.secondsOnline(startTimestamp: nil, now: now))
    }

    func testNextOffsetIgnoresExtraRoom() throws {
        let rooms = (0..<91).map { Self.room("r\($0)") }
        let page = try RoomListDecoder.decode(Self.payload(rooms: rooms, total: 500), requestLimit: 90, requestOffset: 0, now: now)
        XCTAssertEqual(page.rooms.count, 91)
        XCTAssertEqual(page.nextOffset, 90)
    }

    func testDecodeRejectsGarbageAndChallenge() {
        XCTAssertThrowsError(try RoomListDecoder.decode(Data("{\"errors\":{}}".utf8), requestLimit: 90, requestOffset: 0)) {
            XCTAssertEqual($0 as? CatalogFailure, .malformed)
        }
        XCTAssertThrowsError(try RoomListDecoder.decode(Data("<html>Just a moment</html>".utf8), requestLimit: 90, requestOffset: 0)) {
            XCTAssertEqual($0 as? CatalogFailure, .challenge)
        }
    }

    func testFetchSendsBrowserHeadersAndMapsStatus() async throws {
        let client = ScriptedClient([.init(status: 200, data: Self.payload(rooms: [Self.room("a")], total: 1))])
        _ = try await CatalogAPI(client: client).fetch(CatalogQuery())
        XCTAssertEqual(client.requests.first?.value(forHTTPHeaderField: "X-Requested-With"), "XMLHttpRequest")
        let bad = CatalogAPI(client: ScriptedClient([.init(status: 400, data: Data("{}".utf8))]))
        do {
            _ = try await bad.fetch(CatalogQuery())
            XCTFail("expected status")
        } catch {
            XCTAssertEqual(error as? CatalogFailure, .status(400))
        }
    }

    func testFetchPagesMergesDedupesAndAdvances() async throws {
        let p1 = (0..<90).map { Self.room("r\($0)") }
        let p2 = (89..<179).map { Self.room("r\($0)") }   // 与上一页重叠一条
        let p3 = (180..<270).map { Self.room("r\($0)") }
        let client = ScriptedClient([p1, p2, p3].map { .init(status: 200, data: Self.payload(rooms: $0, total: 1000)) })
        let page = try await CatalogAPI(client: client).fetchPages(CatalogQuery(limit: 90, offset: 0), pages: 3)
        XCTAssertEqual(client.requests.compactMap { $0.url }.map { self.values($0)["offset"] }, ["0", "90", "180"])
        XCTAssertEqual(page.rooms.count, 269)
        XCTAssertEqual(Set(page.rooms.map(\.username)).count, 269)
        XCTAssertEqual(page.nextOffset, 270)
        XCTAssertEqual(page.total, 1000)
    }

    func testFetchPagesStopsOnShortPageAndAtEnd() async throws {
        let short = ScriptedClient([.init(status: 200, data: Self.payload(rooms: (0..<30).map { Self.room("s\($0)") }, total: 30))])
        let a = try await CatalogAPI(client: short).fetchPages(CatalogQuery(limit: 90), pages: 3)
        XCTAssertEqual(short.requests.count, 1)
        XCTAssertEqual(a.rooms.count, 30)
        XCTAssertGreaterThanOrEqual(a.nextOffset, a.total)

        let full = (0..<90).map { Self.room("f\($0)") }
        let end = ScriptedClient([.init(status: 200, data: Self.payload(rooms: full, total: 90))])
        let b = try await CatalogAPI(client: end).fetchPages(CatalogQuery(limit: 90), pages: 3)
        XCTAssertEqual(end.requests.count, 1, "到底后不再请求")
        XCTAssertEqual(b.nextOffset, 90)
    }

    func testFetchPagesFailsWhenAnyPageFails() async {
        let full = (0..<90).map { Self.room("f\($0)") }
        let client = ScriptedClient([
            .init(status: 200, data: Self.payload(rooms: full, total: 1000)),
            .init(status: 503, data: Data("{}".utf8))
        ])
        do {
            _ = try await CatalogAPI(client: client).fetchPages(CatalogQuery(limit: 90), pages: 3)
            XCTFail("expected failure")
        } catch {
            // 正文不是质询页的 503 是普通失败，不是质询。
            XCTAssertEqual(error as? CatalogFailure, .status(503))
        }
    }

    // MARK: 夹具（合成数据，字段结构取自实测响应）

    static func room(
        _ name: String, gender: String = "f", show: String = "public", users: Int = 10, start: Int? = nil,
        age: Int? = nil, image: String? = nil, password: Bool = false
    ) -> String {
        let img = image ?? "http://images.example/\(name).jpg"
        let startField = start.map { "\"start_timestamp\":\($0)," } ?? ""
        let ageField = age.map { "\"display_age\":\($0)," } ?? ""
        return """
        {"username":"\(name)","gender":"\(gender)","current_show":"\(show)","num_users":\(users),"num_followers":50,\
        "tags":["a","b"],"room_subject":"hello #a #b","location":"Desk","country":"","is_new":false,\
        \(startField)\(ageField)"has_password":\(password),"label":"\(show)","img":"\(img)"}
        """
    }

    static func payload(rooms: [String], total: Int) -> Data {
        Data("{\"rooms\":[\(rooms.joined(separator: ","))],\"total_count\":\(total)}".utf8)
    }
}

private final class ScriptedClient: HTTPClient, @unchecked Sendable {
    private let lock = NSLock()
    private var script: [HTTPResult]
    private var seen: [URLRequest] = []

    init(_ script: [HTTPResult]) { self.script = script }

    var requests: [URLRequest] { lock.lock(); defer { lock.unlock() }; return seen }

    func send(_ request: URLRequest) async throws -> HTTPResult { next(request) }

    private func next(_ request: URLRequest) -> HTTPResult {
        lock.lock(); defer { lock.unlock() }
        seen.append(request)
        return script.isEmpty ? HTTPResult(status: 500, data: Data()) : script.removeFirst()
    }
}
