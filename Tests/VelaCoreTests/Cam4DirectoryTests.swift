import XCTest
@testable import VelaCore

final class Cam4DirectoryTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_700_000_000)

    func testURLShapeStartsAtPageOneAndClampsSize() {
        let first = Cam4Directory.url(offset: 0, limit: 100)
        var items = query(first)
        XCTAssertEqual(items["directoryJson"], "true")
        XCTAssertEqual(items["online"], "true")
        XCTAssertEqual(items["url"], "true")
        XCTAssertEqual(items["page"], "1")
        XCTAssertEqual(items["resultsPerPage"], "90")
        XCTAssertEqual(first.host, "www.cam4.com")
        XCTAssertEqual(first.path, "/directoryCams")
        XCTAssertFalse(first.absoluteString.contains("affiliate"))
        XCTAssertEqual(query(Cam4Directory.url(offset: 90, limit: 90))["page"], "2")
        XCTAssertEqual(query(Cam4Directory.url(page: 0, resultsPerPage: 0))["page"], "1")
        XCTAssertEqual(query(Cam4Directory.url(page: 3, resultsPerPage: 10))["resultsPerPage"], "10")
    }

    func testFixtureMapsEveryFieldAndIgnoresDefaultImage() throws {
        let page = try Cam4Directory.decode(fixture(), requestLimit: 50, requestOffset: 0, now: now, viewerRegions: [])
        XCTAssertEqual(page.total, 40)
        XCTAssertEqual(page.nextOffset, 3)
        XCTAssertEqual(page.rooms.count, 3)

        let full = page.rooms[0]
        XCTAssertEqual(full.username, "sample_one")
        XCTAssertEqual(full.displayName, "sample_one")
        XCTAssertEqual(full.site, .cam4)
        XCTAssertEqual(full.id, "c4:sample_one")
        XCTAssertEqual(full.gender, .female)
        XCTAssertEqual(full.age, 24)
        XCTAssertEqual(full.location, "US")
        XCTAssertEqual(full.show, .publicShow)
        XCTAssertEqual(full.stream?.absoluteString, "https://preview.example/sample_one/master.m3u8?token=abc")
        XCTAssertEqual(full.thumbnailURL?.absoluteString, "https://snapshots.example/sample_one.jpg?sign=s1")
        XCTAssertFalse(full.thumbnailURL?.absoluteString.contains("default") ?? true)
        XCTAssertEqual(full.thumbnailExpires, Date(timeIntervalSince1970: 1_700_003_600))
        XCTAssertEqual(full.blockedRegions, ["RU", "UA", "AF"])
        XCTAssertEqual(full.viewers, 128)
        XCTAssertEqual(full.tags, ["talk", "dance"])

        let priv = page.rooms[1]
        XCTAssertEqual(priv.show, .privateShow)
        XCTAssertNil(priv.stream, "非 https 流地址丢掉")
        XCTAssertNil(priv.age, "17 岁不展示")
        XCTAssertEqual(priv.location, "")
        XCTAssertEqual(priv.gender, .trans)
        XCTAssertEqual(priv.viewers, 7)
        XCTAssertEqual(priv.blockedRegions, ["US"])
        XCTAssertEqual(priv.thumbnailExpires, now.addingTimeInterval(Cam4Directory.snapshotTTL))

        let bare = page.rooms[2]
        XCTAssertEqual(bare.username, "bare_min")
        XCTAssertEqual(bare.show, .publicShow)
        XCTAssertNil(bare.gender)
        XCTAssertNil(bare.stream)
        XCTAssertNil(bare.thumbnailURL)
        XCTAssertNil(bare.thumbnailExpires)
        XCTAssertEqual(bare.viewers, 0)
        XCTAssertTrue(bare.tags.isEmpty)
        XCTAssertTrue(bare.blockedRegions.isEmpty)
    }

    func testGenderWordsAndMillisecondValidtoFallsBackToTTL() throws {
        let body: [String: Any] = [
            "users": [
                ["username": "aa_male", "gender": "male"],
                ["username": "aa_couple", "gender": "couple"],
                ["username": "aa_trans", "gender": "transgender"],
                ["username": "aa_other", "gender": "unknown"],
                [
                    "username": "aa_ms",
                    "snapshotImageLink": "https://snapshots.example/aa_ms.jpg",
                    "profileImageLink": "https://images.example/a.jpg?validto=1700003600000"
                ]
            ],
            "totalCount": 9
        ]
        let page = try Cam4Directory.decode(
            JSONSerialization.data(withJSONObject: body), requestLimit: 10, requestOffset: 20, now: now, viewerRegions: []
        )
        XCTAssertEqual(page.rooms.map(\.gender), [.male, .couple, .trans, nil, nil])
        XCTAssertEqual(page.nextOffset, 25)
        XCTAssertEqual(page.rooms[4].thumbnailExpires, now.addingTimeInterval(Cam4Directory.snapshotTTL))
    }

    func testBlockedViewerStripsStreamButEmptyViewerDoesNot() throws {
        let blocked = try Cam4Directory.decode(fixture(), requestLimit: 90, requestOffset: 0, now: now, viewerRegions: ["RU"])
        XCTAssertEqual(blocked.rooms[0].show, .publicShow)
        XCTAssertNil(blocked.rooms[0].stream)
        XCTAssertEqual(blocked.rooms[1].show, .privateShow)
        let clear = try Cam4Directory.decode(fixture(), requestLimit: 90, requestOffset: 0, now: now, viewerRegions: ["DE"])
        XCTAssertNotNil(clear.rooms[0].stream)
        let empty = try Cam4Directory.decode(fixture(), requestLimit: 90, requestOffset: 0, now: now, viewerRegions: [])
        XCTAssertNotNil(empty.rooms[0].stream)
    }

    func testRegionsParseCommaArrayCaseWhitespaceAndUnknown() {
        XCTAssertGreaterThanOrEqual(ISO3166.alpha2ToNumeric.count, 240)
        let comma = Cam4Regions.parse(Cam4RegionField(parts: [" 643, 804 , 004 , nope , USA , us "]))
        XCTAssertEqual(comma, ["RU", "UA", "AF", "US"])
        let list = Cam4Regions.parse(Cam4RegionField(parts: ["643", " 804 ", "4", "xx", "de"]))
        XCTAssertEqual(list, ["RU", "UA", "AF", "DE"])
        XCTAssertTrue(Cam4Regions.parse(nil).isEmpty)
        XCTAssertTrue(Cam4Regions.isBlocked(["643", "US"], viewer: ["ru"]))
        XCTAssertTrue(Cam4Regions.isBlocked(["RU"], viewer: ["643"]))
        XCTAssertFalse(Cam4Regions.isBlocked(["643"], viewer: ["US"]))
        XCTAssertFalse(Cam4Regions.isBlocked(["643"], viewer: []))
        XCTAssertFalse(Cam4Regions.isBlocked([], viewer: ["RU"]))
    }

    func testHTMLAndNonJSONAreMalformedNotChallenge() throws {
        XCTAssertThrowsError(try Cam4Directory.decode(Data("<html>Just a moment</html>".utf8), requestLimit: 10, requestOffset: 0, now: now, viewerRegions: [])) { error in
            XCTAssertEqual(error as? CatalogFailure, .malformed)
        }
        let empty = try Cam4Directory.decode(Data("[]".utf8), requestLimit: 10, requestOffset: 0, now: now, viewerRegions: [])
        XCTAssertTrue(empty.rooms.isEmpty)
        XCTAssertThrowsError(try Cam4Directory.decode(Data("{".utf8), requestLimit: 10, requestOffset: 0, now: now, viewerRegions: [])) { error in
            XCTAssertEqual(error as? CatalogFailure, .malformed)
            XCTAssertFalse((error as? CatalogFailure)?.isChallenge ?? true)
        }
    }

    private func fixture() throws -> Data {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent("Fixtures/cam4-directory.json")
        return try Data(contentsOf: url)
    }

    private func query(_ url: URL) -> [String: String] {
        Dictionary(uniqueKeysWithValues: URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.compactMap { item in
            item.value.map { (item.name, $0) }
        } ?? [])
    }
}
