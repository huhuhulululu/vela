import XCTest
@testable import VelaCore

/// 第 4.3 节：同步码结构解析（校验和注入测试用 SHA-256），以及同步状态文件的读写与隔离。
final class SyncCodeTests: XCTestCase {
    let key = (0..<32).map { UInt8($0 * 7 & 0xFF) }

    func code(_ origin: String = "https://sync.example.org") -> String {
        SyncCode.format(key: key, origin: origin, sha256: TestSHA256.hash)!
    }

    func testSHA256KnownAnswer() {
        XCTAssertEqual(TestSHA256.hex(""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertEqual(TestSHA256.hex("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    func testRoundTrip() {
        let text = code()
        XCTAssertTrue(text.hasPrefix("vela2:"))
        let parsed = SyncCode.parse(text, sha256: TestSHA256.hash)
        XCTAssertEqual(parsed?.key, key)
        XCTAssertEqual(parsed?.origin, "https://sync.example.org")
        XCTAssertEqual(parsed?.host, "sync.example.org")
        XCTAssertNotNil(SyncCode.parse(code("https://a.b:8443"), sha256: TestSHA256.hash))
    }

    func testRejectsTamperingAndNonCanonicalForms() {
        let text = code()
        var flipped = Array(text)
        flipped[8] = flipped[8] == "A" ? "B" : "A"
        let bad = [
            String(flipped),
            text.replacingOccurrences(of: "sync.example.org", with: "sync.example.com"),
            text.uppercased(),
            text + "/",
            " " + text,
            text.replacingOccurrences(of: "vela2:", with: "vela3:"),
            String(text.dropLast()),
        ]
        for b in bad { XCTAssertNil(SyncCode.parse(b, sha256: TestSHA256.hash), b) }
    }

    func testOriginRules() {
        for ok in ["https://a.b", "https://sync.example.org", "https://x-y.example.org:1", "https://a.b:65535", "https://xn--fsq.example"] {
            XCTAssertTrue(SyncCode.isCanonicalOrigin(ok), ok)
        }
        for bad in [
            "http://a.b", "https://localhost", "https://A.b", "https://a.b/", "https://a.b/x", "https://a.b?q", "https://a.b#f",
            "https://u@a.b", "https://1.2.3.4", "https://[::1]", "https://a.b:443", "https://a.b:0", "https://a.b:65536",
            "https://a.b:080", "https://-a.b", "https://a-.b", "https://a..b", "https://ä.b", "https://a.b:",
            "https://" + String(repeating: "a", count: 64) + ".b",
        ] {
            XCTAssertFalse(SyncCode.isCanonicalOrigin(bad), bad)
        }
    }

    /// 安全审查 L1：数字或 0x 十六进制标签可被解析成 IP（0x7f.0x1、0177.0.0.0x1），顶级标签首字符必须是字母。
    func testOriginRejectsNumericLookingLabels() {
        for bad in ["https://0x7f.0x1", "https://0177.0.0.0x1", "https://sync.0x1", "https://10.example", "https://a.1b", "https://sync.123"] {
            XCTAssertFalse(SyncCode.isCanonicalOrigin(bad), bad)
        }
        for ok in ["https://sync.example", "https://a1.b2c", "https://sync.xn--fiqs8s", "https://0xbox.example", "https://s3.example"] {
            XCTAssertTrue(SyncCode.isCanonicalOrigin(ok), ok)
        }
    }

    func testFingerprint() {
        XCTAssertEqual(SyncCode.fingerprint(blobID: "3f9a0c21" + String(repeating: "0", count: 56)), "3F9A-0C21")
    }

    // MARK: sync-state.json

    func testStateFileRoundTripAndQuarantine() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sync-state-\(UUID().uuidString)")
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("sync-state.json")
        let store = SyncStateFileStore(url: url)
        XCTAssertEqual(store.load(), .none)
        var s = SyncState(phase: .active, origin: "https://sync.example.org", blobID: String(repeating: "a", count: 64), clock: SyncClock(l: 5, c: 1, dev: 9))
        s.doc.rooms["cb:alice"] = SyncRoom(f: SyncFixture.reg(5, "+"))
        s.inflight = SyncInflight(base: 1, next: 2, envelopeHash: "ab", sentGen: 3)
        s.halt = .gone(deletedAt: 42)
        XCTAssertTrue(store.save(s))
        XCTAssertEqual(SyncStateFileStore(url: url).load(), .present(s))
        try Data("{\"phase\":\"active\"}".utf8).write(to: url)
        XCTAssertEqual(SyncStateFileStore(url: url).load(), .corrupted)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path), "坏文件改名备份")
        XCTAssertTrue(store.delete())
    }

    func testStateFileRejectsInvalidEmbeddedDocument() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sync-state-\(UUID().uuidString)")
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("sync-state.json")
        let store = SyncStateFileStore(url: url)
        XCTAssertTrue(store.save(SyncState(phase: .active, origin: "https://a.b", blobID: "x", clock: SyncClock(dev: 1))))
        let text = try String(contentsOf: url, encoding: .utf8).replacingOccurrences(of: "\\\"schema\\\":2", with: "\\\"schema\\\":1")
        try Data(text.utf8).write(to: url)
        let bad = SyncStateFileStore(url: url)
        bad.moveItem = { _, _ in throw CocoaError(.fileWriteNoPermission) }
        XCTAssertEqual(bad.load(), .corrupted)
        XCTAssertFalse(bad.save(SyncState(phase: .active, origin: "https://a.b", blobID: "x", clock: SyncClock(dev: 1))), "改名也失败时只读")
    }
}
