import XCTest
@testable import VelaCore

/// 第 4.2、5.1、7.3 节：严格 JSON、编码规则、HLC 线格式与推进。
final class SyncPrimitivesTests: XCTestCase {
    func bytes(_ s: String) -> [UInt8] { Array(s.utf8) }

    // MARK: 严格 JSON

    func testParsesCompactAndWhitespace() throws {
        let v = try SyncJSON.parse(bytes(" {\"a\" : {\"b\":\"x\"},\n\"n\":2}\t"))
        guard case .object(let members) = v else { return XCTFail() }
        XCTAssertEqual(members.map(\.key), ["a", "n"])
        XCTAssertEqual(members[1].value, .number("2"))
    }

    func testRejectsDuplicateKeysByBytes() {
        XCTAssertThrowsError(try SyncJSON.parse(bytes("{\"a\":\"1\",\"a\":\"2\"}"))) {
            XCTAssertEqual($0 as? SyncJSON.ParseError, .duplicateKey)
        }
        // 规范等价但字节不同：JSON 层不算重复（文档层再按 NFC 拒绝）。
        XCTAssertNoThrow(try SyncJSON.parse(bytes("{\"\u{E9}\":\"1\",\"e\u{301}\":\"2\"}")))
    }

    func testRejectsBOMEscapesControlAndBadUTF8() {
        let cases: [([UInt8], SyncJSON.ParseError)] = [
            ([0xEF, 0xBB, 0xBF] + bytes("{}"), .bom),
            (bytes("{\"a\":\"\\u0041\"}"), .escape),
            (bytes("{\"a\":\"\\n\"}"), .escape),
            (bytes("{\"a\":\"\\/\"}"), .escape),
            (bytes("{\"a\":\"x\ty\"}"), .controlCharacter),
            (bytes("{\"a\":\"") + [0xC0, 0x80] + bytes("\"}"), .invalidUTF8),
            (bytes("{\"a\":\"") + [0xED, 0xA0, 0x80] + bytes("\"}"), .invalidUTF8),
            (bytes("{\"a\":\"") + [0x80] + bytes("\"}"), .invalidUTF8),
            (bytes("{\"a\":\"") + [0xF4, 0x90, 0x80, 0x80] + bytes("\"}"), .invalidUTF8),
        ]
        for (input, error) in cases {
            XCTAssertThrowsError(try SyncJSON.parse(input), "\(input)") { XCTAssertEqual($0 as? SyncJSON.ParseError, error) }
        }
    }

    func testRejectsSyntaxDepthAndSize() {
        for text in ["", "{", "{\"a\":}", "{\"a\":1,}", "{\"a\" \"b\"}", "{} {}", "{\"a\":01}", "{\"a\":1.}", "{'a':1}", "[1,]", "tru"] {
            XCTAssertThrowsError(try SyncJSON.parse(bytes(text)), text)
        }
        XCTAssertThrowsError(try SyncJSON.parse(bytes("{\"a\":{\"b\":{\"c\":{}}}}"))) {
            XCTAssertEqual($0 as? SyncJSON.ParseError, .tooDeep)
        }
        XCTAssertNoThrow(try SyncJSON.parse(bytes("{\"a\":{\"b\":{\"c\":\"d\"}}}")))
        XCTAssertThrowsError(try SyncJSON.parse(bytes("{\"a\":\"xx\"}"), limit: 8)) {
            XCTAssertEqual($0 as? SyncJSON.ParseError, .tooLarge)
        }
    }

    func testParsesLiteralsAndNumbers() throws {
        let v = try SyncJSON.parse(bytes("{\"a\":[true,false,null,-1.5e+3,0]}"))
        guard case .object(let m) = v, case .array(let items) = m[0].value else { return XCTFail() }
        XCTAssertEqual(items, [.bool(true), .bool(false), .null, .number("-1.5e+3"), .number("0")])
    }

    // MARK: 编码规则

    func testBase64URLStrict() {
        XCTAssertEqual(SyncEncoding.base64url([0xFB, 0xFF]), "-_8")
        XCTAssertEqual(SyncEncoding.decodeBase64url("-_8"), [0xFB, 0xFF])
        XCTAssertEqual(SyncEncoding.decodeBase64url(""), [])
        XCTAssertEqual(SyncEncoding.decodeBase64url("AAAA"), [0, 0, 0])
        for bad in ["-_8=", "-_ 8", "+/8", "-_9", "A", "AB=="] {
            XCTAssertNil(SyncEncoding.decodeBase64url(bad), bad)
        }
        let all = (0...255).map(UInt8.init)
        XCTAssertEqual(SyncEncoding.decodeBase64url(SyncEncoding.base64url(all)), all)
    }

    func testHexAndDecimal() {
        XCTAssertEqual(SyncEncoding.hex([0x0a, 0xff]), "0aff")
        XCTAssertEqual(SyncEncoding.decodeHex("0aff"), [0x0a, 0xff])
        XCTAssertNil(SyncEncoding.decodeHex("0AFF"))
        XCTAssertNil(SyncEncoding.decodeHex("abc"))
        XCTAssertEqual(SyncEncoding.parseDecimal("0"), 0)
        XCTAssertEqual(SyncEncoding.parseDecimal("9007199254740991"), 9_007_199_254_740_991)
        for bad in ["", "00", "01", "+1", "-1", "1.0", "1e3", "9007199254740992", " 1", "99999999999999999999"] {
            XCTAssertNil(SyncEncoding.parseDecimal(bad), bad)
        }
    }

    // MARK: HLC

    func testHLCWireFormatOrdersLikeTuple() {
        let a = HLC(l: 0x0193b2c4d5e6, c: 0, dev: 0xa1b2c3d4)
        XCTAssertEqual(a.hex, "0193b2c4d5e60000a1b2c3d4")
        XCTAssertEqual(HLC(hex: a.hex), a)
        XCTAssertNil(HLC(hex: "0193B2C4D5E60000A1B2C3D4"))
        XCTAssertNil(HLC(hex: "0193b2c4d5e60000a1b2c3d"))
        let samples = [HLC(l: 1, c: 0, dev: 9), HLC(l: 1, c: 1, dev: 1), HLC(l: 2, c: 0, dev: 1), HLC(l: 2, c: 0, dev: 2)]
        for x in samples { for y in samples {
            XCTAssertEqual(x < y, x.hex.utf8.lexicographicallyPrecedes(y.hex.utf8))
        } }
    }

    func testHLCStaticBounds() {
        XCTAssertTrue(HLC(l: 1, c: 0, dev: 1).isStaticallyValid)
        XCTAssertTrue(HLC(l: 0xFFFF00000000, c: 0xFFFF, dev: 1).isStaticallyValid)
        XCTAssertFalse(HLC(l: 0, c: 5, dev: 1).isStaticallyValid)
        XCTAssertFalse(HLC(l: 0xFFFF00000001, c: 0, dev: 1).isStaticallyValid)
        XCTAssertFalse(HLC(l: 5, c: 0, dev: 0).isStaticallyValid)
    }

    func testTickAdvancesWithWallClock() {
        var clock = SyncClock(dev: 7)
        XCTAssertEqual(clock.tick(pt: 100), HLC(l: 100, c: 0, dev: 7))
        XCTAssertEqual(clock.tick(pt: 100), HLC(l: 100, c: 1, dev: 7), "同毫秒靠计数分先后")
        XCTAssertEqual(clock.tick(pt: 50), HLC(l: 100, c: 2, dev: 7), "回拨不倒退")
        XCTAssertEqual(clock.tick(pt: 101), HLC(l: 101, c: 0, dev: 7))
    }

    func testTickCounterOverflowCarriesIntoL() {
        var clock = SyncClock(l: 100, c: 0xFFFF, dev: 7)
        XCTAssertEqual(clock.tick(pt: 100), HLC(l: 101, c: 0, dev: 7))
    }

    func testReceiveTakesLargerWithoutTicking() {
        var clock = SyncClock(l: 100, c: 3, dev: 7)
        clock.receive(HLC(l: 100, c: 2, dev: 9))
        XCTAssertEqual(clock, SyncClock(l: 100, c: 3, dev: 7))
        clock.receive(HLC(l: 200, c: 4, dev: 9))
        XCTAssertEqual(clock, SyncClock(l: 200, c: 4, dev: 7))
        XCTAssertEqual(clock.tick(pt: 150), HLC(l: 200, c: 5, dev: 7), "看见远端之后的本地操作排在它后面")
    }
}
