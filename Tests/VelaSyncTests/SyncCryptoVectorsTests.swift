#if canImport(CryptoKit)
import Foundation
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 金样本加密相关段（docs/plans/sync.md 第 17、18 节 S2 ①）。每段都必须出现、至少通过 1 条、失败为 0。
final class SyncCryptoVectorsTests: XCTestCase {
    static var fixtureURL: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Fixtures/sync/vectors.json")
    }

    static let sections = [
        "kdf", "pubkey", "pubcheck", "code", "codeInvalid", "payload", "payloadInvalid",
        "envelope", "envelopeInvalid", "sign", "signInvalid",
    ]

    var counts: [String: (pass: Int, fail: Int)] = [:]
    var failures: [String] = []

    func testCryptoVectors() throws {
        let data = try Data(contentsOf: Self.fixtureURL)
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        for section in Self.sections {
            let items = root[section] as? [[String: Any]] ?? []
            for (i, item) in items.enumerated() {
                let ok: Bool
                do { ok = try check(section, item) } catch { ok = false }
                counts[section, default: (0, 0)].pass += ok ? 1 : 0
                counts[section, default: (0, 0)].fail += ok ? 0 : 1
                if !ok { failures.append("\(section)[\(i)] \(item["reason"] ?? "")") }
            }
        }
        for section in Self.sections {
            let c = counts[section] ?? (0, 0)
            print("金样本 \(section)：通过 \(c.pass)，失败 \(c.fail)")
            XCTAssertGreaterThan(c.pass, 0, "金样本段 \(section) 没有被比对")
            XCTAssertEqual(c.fail, 0, "金样本段 \(section) 有失败")
        }
        for f in failures { XCTFail(f) }
    }

    private func hex(_ d: [String: Any], _ k: String) throws -> [UInt8] {
        try XCTUnwrap(SyncEncoding.decodeHex(try XCTUnwrap(d[k] as? String)))
    }

    private func b64(_ d: [String: Any], _ k: String) throws -> [UInt8] {
        try XCTUnwrap(SyncEncoding.decodeBase64url(try XCTUnwrap(d[k] as? String)))
    }

    private func str(_ d: [String: Any], _ k: String) throws -> String { try XCTUnwrap(d[k] as? String) }

    private func ver(_ d: [String: Any], _ k: String = "ver") -> UInt64 { (d[k] as? NSNumber)?.uint64Value ?? 1 }

    // swiftlint:disable:next cyclomatic_complexity
    private func check(_ section: String, _ d: [String: Any]) throws -> Bool {
        switch section {
        case "kdf":
            let keys = try SyncCrypto.derive(try hex(d, "K_hex"))
            let salt = try hex(d, "salt_hex")
            let infos = d["info"] as? [String] ?? []
            let blobID = try str(d, "blobID"), encKey = try hex(d, "encKey_hex"), d32 = try hex(d, "d_hex")
            let pub = try str(d, "pub_b64url"), fp = try str(d, "fingerprint"), pubcheck = try hex(d, "pubcheck_message_hex")
            return salt == [UInt8](repeating: 0, count: 32)
                && infos == ["vela-sync/v2/id", "vela-sync/v2/enc", "vela-sync/v2/sign"]
                && keys.blobID == blobID && keys.encKey == encKey && keys.signCounter == (d["signCounter"] as? Int)
                && keys.d32 == d32 && keys.publicKeyText == pub && keys.fingerprint == fp
                && SyncCrypto.pubcheckMessage(blobID: keys.blobID) == pubcheck
        case "pubkey":
            let expected = try b64(d, "pub_b64url")
            return try SyncCrypto.publicKey(d32: try hex(d, "d_hex")) == expected
        case "pubcheck":
            let message = try hex(d, "message_hex")
            let valid = d["valid"] as? Bool
            let built = SyncCrypto.pubcheckMessage(blobID: try str(d, "blobID"))
            let sig = try b64(d, "sig_b64url"), pub = try b64(d, "pub_b64url")
            return message == built && SyncCrypto.verify(signature: sig, message: message, publicKey: pub) == valid
        case "code":
            let text = try str(d, "code"), key = try hex(d, "K_hex"), origin = try str(d, "origin")
            let parsed = try XCTUnwrap(SyncCrypto.parseCode(text))
            return parsed.key == key && parsed.origin == origin && SyncCrypto.formatCode(key: parsed.key, origin: parsed.origin) == text
        case "codeInvalid":
            return SyncCrypto.parseCode(try str(d, "code")) == nil
        case "payload":
            let json = Array(try str(d, "json_utf8").utf8)
            let wire = [UInt8(try XCTUnwrap(d["flag"] as? Int))] + (try b64(d, "body_b64url"))
            // 压缩字节不要求相同：解别人的、再解自己写的，都要还原原文。
            return SyncPayload.decode(wire) == json && SyncPayload.decode(SyncPayload.encode(json)) == json
        case "payloadInvalid":
            let wire = [UInt8(try XCTUnwrap(d["flag"] as? Int))] + (try b64(d, "body_b64url"))
            return SyncPayload.decode(wire) == nil
        case "envelope":
            let encKey = try hex(d, "encKey_hex"), blobID = try str(d, "blobID"), v = ver(d)
            let payload = try hex(d, "payload_hex")
            let expected = try str(d, "envelope"), aad = try hex(d, "aad_hex"), nonce = try hex(d, "nonce_hex")
            return SyncEnvelope.aad(blobID: blobID, ver: v) == aad
                && SyncEnvelope.seal(payload, encKey: encKey, blobID: blobID, ver: v, nonce: nonce) == expected
                && SyncEnvelope.open(expected, encKey: encKey, blobID: blobID, ver: v) == .payload(payload)
        case "envelopeInvalid":
            // 只带信封的负例按第一条正例的密钥与版本解封。
            let first = try XCTUnwrap((try JSONSerialization.jsonObject(with: Data(contentsOf: Self.fixtureURL)) as? [String: Any])?["envelope"] as? [[String: Any]]).first!
            let source = d["encKey_hex"] == nil ? first : d
            let opened = SyncEnvelope.open(try str(d, "envelope"), encKey: try hex(source, "encKey_hex"), blobID: try str(source, "blobID"), ver: ver(d))
            if case .payload = opened { return false }
            return true
        case "sign", "signInvalid":
            let message = try hex(d, "message_hex")
            let op = try str(d, "op")
            let hash = try str(d, "envelope_sha256_hex")
            let built = SyncCrypto.writeMessage(
                op: op, blobID: try str(d, "blobID"), epoch: try str(d, "epoch"),
                base: (d["base"] as? NSNumber)?.uint64Value ?? 0, envelopeHash: op == "put" ? hash : nil
            )
            let sig = SyncEncoding.decodeBase64url(try str(d, "sig_b64url")) ?? []
            let pub = SyncEncoding.decodeBase64url(try str(d, "pub_b64url")) ?? []
            let valid = try XCTUnwrap(d["valid"] as? Bool)
            // 正例的消息必须与本端按第 9 节拼出的逐字节相同；负例里「消息篡改」的消息本来就不同。
            let messageMatches = section == "signInvalid" || built == message
            return messageMatches && SyncCrypto.verify(signature: sig, message: message, publicKey: pub) == valid
        default:
            return false
        }
    }
}
#endif
