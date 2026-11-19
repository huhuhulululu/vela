#if canImport(CryptoKit)
import CryptoKit
import Foundation
#if SWIFT_PACKAGE
import VelaCore
#endif

/// 信封 = `e1.` + base64url(nonce) + `.` + base64url(ciphertext ‖ tag)（docs/plans/sync.md 第 8 节，写死）。
enum SyncEnvelope {
    static let prefix = "e1."
    /// 信封字节上限（由载荷上限精确推出后取整）。
    static let maxBytes = 800_100

    enum Opened: Equatable {
        case payload([UInt8])
        /// 未知前缀：只读，提示更新 App。
        case unsupported
        case failed
    }

    /// AAD = `vela-sync/e1|` + blobID + `|` + 该信封将要存放的版本号。
    static func aad(blobID: String, ver: UInt64) -> [UInt8] {
        Array("vela-sync/e1|\(blobID)|\(ver)".utf8)
    }

    static func seal(_ payload: [UInt8], encKey: [UInt8], blobID: String, ver: UInt64, nonce: [UInt8]) -> String? {
        guard nonce.count == 12, let n = try? AES.GCM.Nonce(data: nonce),
              let box = try? AES.GCM.seal(Data(payload), using: SymmetricKey(data: encKey), nonce: n, authenticating: Data(aad(blobID: blobID, ver: ver)))
        else { return nil }
        // 不用 `.combined`（它含 nonce）。
        return prefix + SyncEncoding.base64url(nonce) + "." + SyncEncoding.base64url(Array(box.ciphertext) + Array(box.tag))
    }

    static func open(_ envelope: String, encKey: [UInt8], blobID: String, ver: UInt64) -> Opened {
        guard envelope.utf8.starts(with: prefix.utf8) else { return .unsupported }
        guard envelope.utf8.count <= maxBytes else { return .failed }
        let parts = envelope.utf8.dropFirst(prefix.utf8.count).split(separator: UInt8(ascii: "."), omittingEmptySubsequences: false)
        guard parts.count == 2,
              let nonce = SyncEncoding.decodeBase64url(String(decoding: parts[0], as: UTF8.self)), nonce.count == 12,
              let sealed = SyncEncoding.decodeBase64url(String(decoding: parts[1], as: UTF8.self)), sealed.count >= 16,
              let n = try? AES.GCM.Nonce(data: nonce),
              let box = try? AES.GCM.SealedBox(nonce: n, ciphertext: Data(sealed.dropLast(16)), tag: Data(sealed.suffix(16))),
              let plain = try? AES.GCM.open(box, using: SymmetricKey(data: encKey), authenticating: Data(aad(blobID: blobID, ver: ver)))
        else { return .failed }
        return .payload(Array(plain))
    }
}

/// 协调器用的加密封装：CryptoKit AES-256-GCM + DEFLATE 载荷，nonce 每次封装由系统 CSPRNG 新生成。
final class SyncCryptoCodec: SyncEnvelopeCodec {
    let keys: SyncKeyMaterial
    let sizeLimit: Int
    private let random: (Int) throws -> [UInt8]

    init(keys: SyncKeyMaterial, sizeLimit: Int = SyncLimits.plaintextBytes, random: @escaping (Int) throws -> [UInt8] = SyncCrypto.random) {
        self.keys = keys
        self.sizeLimit = sizeLimit
        self.random = random
    }

    var publicKey: String { keys.publicKeyText }

    func seal(_ plaintext: [UInt8], ver: UInt64) -> String? {
        guard plaintext.count <= sizeLimit, let nonce = try? random(12) else { return nil }
        return SyncEnvelope.seal(SyncPayload.encode(plaintext), encKey: keys.encKey, blobID: keys.blobID, ver: ver, nonce: nonce)
    }

    func open(_ envelope: String, ver: UInt64) -> SyncOpenResult {
        switch SyncEnvelope.open(envelope, encKey: keys.encKey, blobID: keys.blobID, ver: ver) {
        case .unsupported: return .unsupported
        case .failed: return .failed
        case .payload(let payload):
            guard let json = SyncPayload.decode(payload, limit: sizeLimit) else { return .failed }
            return .plaintext(json)
        }
    }

    func digest(_ envelope: String) -> String { SyncCrypto.sha256Hex(envelope) }
}
#endif
