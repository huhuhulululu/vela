import XCTest
@testable import VelaCore

/// 第 6.1–6.3、7.3 节：键规则、文档形状与合法性。
final class SyncDocumentTests: XCTestCase {
    typealias F = SyncFixture
    let h = "0193b2c4d5e60000a1b2c3d4"

    func decode(_ s: String) -> SyncDecodeResult { SyncDocument.decode(Array(s.utf8)) }

    func wrap(rooms: String = "{}", veto: String = "{}", settings: String = "{}") -> String {
        "{\"schema\":2,\"rooms\":\(rooms),\"veto\":\(veto),\"settings\":\(settings)}"
    }

    // MARK: 房间键

    func testRoomKeyRules() {
        for ok in ["cb:alice", "cb:a_1", "c4:bob", "abcdefgh:xx", "cb:" + String(repeating: "a", count: 30)] {
            XCTAssertTrue(SyncKeys.isRoomKey(ok), ok)
        }
        for bad in ["alice", "cb:a", "cb:Alice", "Cb:alice", "4c:alice", "c:alice", "abcdefghi:xx", "cb:al-ice", "cb:" + String(repeating: "a", count: 31), "cb:alice:x", "cb: alice"] {
            XCTAssertFalse(SyncKeys.isRoomKey(bad), bad)
        }
    }

    func testLocalNameMapsToCBPrefix() {
        XCTAssertEqual(SyncKeys.roomKey(localName: " Alice "), "cb:alice")
        XCTAssertNil(SyncKeys.roomKey(localName: "a"))
        XCTAssertNil(SyncKeys.roomKey(localName: "al ice"))
        XCTAssertNil(SyncKeys.roomKey(localName: "élise"))
    }

    func testDisplayNameRule() {
        XCTAssertTrue(SyncKeys.isDisplayName("Alice", forRoomKey: "cb:alice"))
        XCTAssertFalse(SyncKeys.isDisplayName("Alicia", forRoomKey: "cb:alice"))
        XCTAssertFalse(SyncKeys.isDisplayName("\u{212A}im", forRoomKey: "cb:kim"), "开尔文符号小写后是 k，但不是 ASCII")
        XCTAssertEqual(SyncKeys.displayName(original: "\u{212A}im", roomKey: "cb:kim"), "kim")
        XCTAssertEqual(SyncKeys.displayName(original: " Kim ", roomKey: "cb:kim"), "Kim")
    }

    // MARK: 标签键（第 6.3 节金样本列出的情形）

    func testTagKeyNormalization() {
        let cases: [(String, String?)] = [
            ("Asian", "t:asian"),
            ("#Tag", "t:#tag"),
            ("\u{E9}", "t:\u{E9}"),
            ("e\u{301}", "t:\u{E9}"),
            ("\u{3A3}\u{391}\u{3A3}", "t:\u{3C3}\u{3B1}\u{3C3}"),
            ("\u{130}", "t:i\u{307}"),
            ("\u{DF}", "t:\u{DF}"),
            ("\u{1C5}", "t:\u{1C6}"),
            (String(repeating: "a", count: 64), "t:" + String(repeating: "a", count: 64)),
            (String(repeating: "a", count: 65), nil),
            ("", nil),
            ("\u{1F600}", nil),
            ("\u{870}", nil),
            ("\u{E000}", nil),
            ("\u{FFFE}", nil),
            ("big tits", nil),
            ("a\u{3000}b", nil),
            ("say\"hi", nil),
            ("back\\slash", nil),
            ("tab\u{7}", nil),
            (String(repeating: "\u{23A}", count: 64), nil),
        ]
        for (raw, expected) in cases {
            let key = SyncKeys.syncTagKey(raw: raw)
            XCTAssertEqual(key.map { Array($0.utf8) }, expected.map { Array($0.utf8) }, "raw=\(raw.unicodeScalars.map { String($0.value, radix: 16) })")
            if let key {
                XCTAssertTrue(SyncKeys.isSyncTagKey(key))
                XCTAssertEqual(Taste.tagKey(raw), key, "本机键与同步键在 String 身份上相等")
            }
        }
    }

    func testReceiverTagKeyChecks() {
        XCTAssertFalse(SyncKeys.isSyncTagKey("t:e\u{301}"), "非 NFC")
        XCTAssertFalse(SyncKeys.isSyncTagKey("t:Asian"), "非小写，幂等检查不过")
        XCTAssertFalse(SyncKeys.isSyncTagKey("t:"), "空标签")
        XCTAssertFalse(SyncKeys.isSyncTagKey("t:" + String(repeating: "\u{E9}", count: 65)), "超过 128 字节")
        XCTAssertTrue(SyncKeys.isSyncTagKey("t:" + String(repeating: "\u{E9}", count: 64)))
        XCTAssertTrue(SyncKeys.isVetoKey("g:m"))
        XCTAssertFalse(SyncKeys.isVetoKey("g:x"))
        XCTAssertFalse(SyncKeys.isVetoKey("x:m"))
    }

    func testLocalVetoKeyMapping() {
        XCTAssertEqual(SyncKeys.syncVetoKey(localKey: "g:f"), "g:f")
        XCTAssertEqual(SyncKeys.syncVetoKey(localKey: Taste.tagKey("Asian")), "t:asian")
        XCTAssertNil(SyncKeys.syncVetoKey(localKey: Taste.tagKey("big tits")))
        XCTAssertNil(SyncKeys.syncVetoKey(localKey: "g:z"))
    }

    // MARK: 文档合法性

    func testMinimalAndExampleDocumentsAreValid() {
        XCTAssertEqual(decode(wrap()), .valid(.empty))
        let example = wrap(
            rooms: "{\"cb:alice\":{\"f\":\"\(h)+\",\"b\":\"0193b2c4d5e60000a1b2c3d4-\",\"n\":\"\(h)Alice\"},\"c4:bob_x\":{\"b\":\"0193b2c4d5e70001a1b2c3d4+\"}}",
            veto: "{\"t:asian\":\"0193b2c4d5e80000a1b2c3d4+\",\"g:m\":\"0193b2c4d5e90000a1b2c3d4-\"}",
            settings: "{\"publicOnly\":\"0193b2c4d5ea0000a1b2c3d41\"}"
        )
        guard case .valid(let doc) = decode(example) else { return XCTFail() }
        XCTAssertEqual(doc.rooms.count, 2)
        XCTAssertEqual(doc.rooms["cb:alice"]?.n?.payload, "Alice")
        XCTAssertEqual(decode(F.text(doc)), .valid(doc), "写出再读回不变")
    }

    func testEncodingIsCompactAndSorted() {
        var d = SyncDocument()
        d.veto["t:b"] = F.reg(2, "+")
        d.veto["g:m"] = F.reg(1, "-")
        d.rooms["cb:zed"] = SyncRoom(f: F.reg(3, "+"), n: F.reg(3, "Zed"))
        func x(_ l: UInt64) -> String { HLC(l: l, c: 0, dev: 1).hex }
        XCTAssertEqual(x(3), "000000000003000000000001")
        XCTAssertEqual(F.text(d), "{\"rooms\":{\"cb:zed\":{\"f\":\"\(x(3))+\",\"n\":\"\(x(3))Zed\"}},\"schema\":2,\"settings\":{},\"veto\":{\"g:m\":\"\(x(1))-\",\"t:b\":\"\(x(2))+\"}}")
    }

    func testSchemaVersions() {
        XCTAssertEqual(decode("{\"schema\":3,\"whatever\":[]}"), .newerSchema)
        XCTAssertEqual(decode(wrap().replacingOccurrences(of: ":2", with: ":12")), .newerSchema)
        for bad in [
            wrap().replacingOccurrences(of: ":2", with: ":1"),
            wrap().replacingOccurrences(of: ":2", with: ":\"2\""),
            wrap().replacingOccurrences(of: ":2", with: ":2.0"),
            wrap().replacingOccurrences(of: ":2", with: ":02"),
            wrap().replacingOccurrences(of: ":2", with: ":-2"),
        ] {
            guard case .invalid = decode(bad) else { return XCTFail(bad) }
        }
    }

    func testShapeViolationsAreInvalid() {
        let bad = [
            "{\"schema\":2,\"rooms\":{},\"veto\":{}}",
            "{\"schema\":2,\"rooms\":{},\"veto\":{},\"settings\":{},\"x\":{}}",
            "[]",
            wrap(rooms: "[]"),
            wrap(rooms: "{\"cb:alice\":{}}"),
            wrap(rooms: "{\"cb:alice\":{\"x\":\"\(h)+\"}}"),
            wrap(rooms: "{\"cb:alice\":{\"f\":\"\(h)x\"}}"),
            wrap(rooms: "{\"cb:alice\":{\"f\":\"\(h)\"}}"),
            wrap(rooms: "{\"cb:alice\":{\"f\":\"\(h.uppercased())+\"}}"),
            wrap(rooms: "{\"cb:alice\":{\"f\":\"000000000000000000000001+\"}}"),
            wrap(rooms: "{\"cb:alice\":{\"f\":\"000000000001000000000000+\"}}"),
            wrap(rooms: "{\"cb:alice\":{\"f\":\"ffff00000001000000000001+\"}}"),
            wrap(rooms: "{\"cb:alice\":{\"n\":\"\(h)Alicia\"}}"),
            wrap(rooms: "{\"cb:alice\":{\"f\":1}}"),
            wrap(rooms: "{\"alice\":{\"f\":\"\(h)+\"}}"),
            wrap(veto: "{\"t:Asian\":\"\(h)+\"}"),
            wrap(veto: "{\"t:e\u{301}\":\"\(h)+\"}"),
            wrap(veto: "{\"t:asian\":\"\(h)1\"}"),
            wrap(veto: "{\"t:asian\":2}"),
            wrap(settings: "{\"muted\":\"\(h)1\"}"),
            wrap(settings: "{\"publicOnly\":\"\(h)+\"}"),
            wrap(settings: "{\"publicOnly\":true}"),
        ]
        for text in bad {
            guard case .invalid = decode(text) else { return XCTFail(text) }
        }
    }

    func testCountsOverLimitAreInvalid() {
        var d = SyncDocument()
        for i in 0..<301 { d.rooms[String(format: "cb:u%03d", i)] = SyncRoom(f: F.reg(UInt64(i + 1), "+")) }
        guard case .invalid = decode(F.text(d)) else { return XCTFail() }
        XCTAssertEqual(decode(F.text(SyncMerge.trim(d))), .valid(SyncMerge.trim(d)))
    }

    func testOversizePlaintextIsInvalid() {
        let pad = String(repeating: " ", count: SyncLimits.plaintextBytes)
        guard case .invalid = decode(wrap() + pad) else { return XCTFail() }
    }

    func testJSONParseErrorInvalidReasonsPinExactNames() {
        let oversize = [UInt8](repeating: UInt8(ascii: " "), count: SyncLimits.plaintextBytes + 1)
        let cases: [([UInt8], String)] = [
            (oversize, "json:tooLarge"),
            ([0xEF, 0xBB, 0xBF, UInt8(ascii: "{")], "json:bom"),
            ([0xFF], "json:invalidUTF8"),
            (Array("{".utf8), "json:syntax"),
            (Array("{\"a\":1,\"a\":2}".utf8), "json:duplicateKey"),
            (Array("{\"a\":\"\\n\"}".utf8), "json:escape"),
            (Array("{\"a\":\"\u{01}\"}".utf8), "json:controlCharacter"),
            (Array("[[[[]]]]".utf8), "json:tooDeep"),
        ]
        for (bytes, expected) in cases {
            XCTAssertEqual(SyncDocument.decode(bytes), .invalid(expected))
        }
    }
}
