#if canImport(CryptoKit)
import Foundation
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

final class SyncCryptoTests: XCTestCase {
    let k = Array(0..<32).map { UInt8($0) }

    /// 计数器重试（第 4.1 节）：注入的 HKDF 让前两个计数器给出 0 与 n，第三个才合法。
    func testSignCounterRetriesUntilScalarInRange() throws {
        let real = SyncCrypto.hkdf
        var seen: [UInt8] = []
        let keys = try SyncCrypto.derive(k) { ikm, info in
            guard info.starts(with: SyncCrypto.signInfo), info.count == SyncCrypto.signInfo.count + 1 else { return real(ikm, info) }
            seen.append(info.last!)
            switch info.last! {
            case 0: return [UInt8](repeating: 0, count: 32)
            case 1: return SyncCrypto.order
            default: return real(ikm, info)
            }
        }
        XCTAssertEqual(seen, [0, 1, 2])
        XCTAssertEqual(keys.signCounter, 2)
        XCTAssertEqual(keys.d32, real(k, SyncCrypto.signInfo + [2]))
        XCTAssertEqual(keys.publicKey.count, 65)
    }

    func testAllCountersFailingAbortsWithoutKey() {
        XCTAssertThrowsError(try SyncCrypto.derive(k) { _, info in
            info.starts(with: SyncCrypto.signInfo) ? [UInt8](repeating: 0xFF, count: 32) : [UInt8](repeating: 1, count: 32)
        }) { XCTAssertEqual($0 as? SyncCryptoError, .noValidScalar) }
    }

    /// 随机 `k`：同一消息两次签名字节不同，验签都通过；篡改一位不通过。
    func testSignatureRoundTripWithRandomK() throws {
        let keys = try SyncCrypto.derive(k)
        let message = SyncCrypto.writeMessage(op: "put", blobID: keys.blobID, epoch: String(repeating: "a", count: 32), base: 3, envelopeHash: String(repeating: "b", count: 64))
        let a = try SyncCrypto.sign(message, d32: keys.d32)
        let b = try SyncCrypto.sign(message, d32: keys.d32)
        XCTAssertEqual(a.count, 64)
        XCTAssertNotEqual(a, b)
        XCTAssertTrue(SyncCrypto.verify(signature: a, message: message, publicKey: keys.publicKey))
        XCTAssertTrue(SyncCrypto.verify(signature: b, message: message, publicKey: keys.publicKey))
        var flipped = message
        flipped[0] ^= 1
        XCTAssertFalse(SyncCrypto.verify(signature: a, message: flipped, publicKey: keys.publicKey))
    }

    /// 金样本里回填的 CryptoKit 签名（第 17 节）：本测试打印一组，由 S0 生成器收录后三端互验。
    func testPrintsCryptoKitSignatureSample() throws {
        let keys = try SyncCrypto.derive(k)
        let message = SyncCrypto.writeMessage(op: "put", blobID: keys.blobID, epoch: "0123456789abcdeffedcba9876543210", base: 7, envelopeHash: SyncCrypto.sha256Hex("e1.cryptokit-sample"))
        let sig = try SyncCrypto.sign(message, d32: keys.d32)
        XCTAssertTrue(SyncCrypto.verify(signature: sig, message: message, publicKey: keys.publicKey))
        print("cryptokit-sign message_hex=\(SyncEncoding.hex(message)) sig_b64url=\(SyncEncoding.base64url(sig))")
    }

    func testCodecSealOpenRoundTripAndAADBindsVersion() throws {
        let codec = SyncCryptoCodec(keys: try SyncCrypto.derive(k))
        let json = Array("{\"rooms\":{},\"schema\":2,\"settings\":{},\"veto\":{}}".utf8)
        let a = try XCTUnwrap(codec.seal(json, ver: 5))
        let b = try XCTUnwrap(codec.seal(json, ver: 5))
        XCTAssertNotEqual(a, b, "每次封装换新 nonce")
        XCTAssertEqual(codec.open(a, ver: 5), .plaintext(json))
        XCTAssertEqual(codec.open(a, ver: 6), .failed)
        XCTAssertEqual(codec.open("e2.AAAA.BBBB", ver: 5), .unsupported)
        XCTAssertEqual(codec.digest(a), SyncEncoding.hex(SyncCrypto.sha256(Array(a.utf8))))
    }

    func testRandomFailureAbortsSeal() throws {
        let codec = SyncCryptoCodec(keys: try SyncCrypto.derive(k)) { _ in throw SyncCryptoError.randomUnavailable }
        XCTAssertNil(codec.seal(Array("{}".utf8), ver: 1))
    }

    func testPayloadCompressesOnlyWhenShorter() {
        let small = Array("{}".utf8)
        XCTAssertEqual(SyncPayload.encode(small), [0] + small)
        let big = Array(String(repeating: "{\"rooms\":{}}", count: 200).utf8)
        let wire = SyncPayload.encode(big)
        XCTAssertEqual(wire.first, 1)
        XCTAssertLessThan(wire.count, big.count)
        XCTAssertEqual(SyncPayload.decode(wire), big)
        XCTAssertNil(SyncPayload.decode(wire, limit: big.count - 1), "超过 P 中止")
    }
}
#endif
