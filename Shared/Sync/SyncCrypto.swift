#if canImport(CryptoKit)
import CryptoKit
import Foundation
import Security
#if SWIFT_PACKAGE
import VelaCore
#endif

enum SyncCryptoError: Error, Equatable {
    /// 系统随机源报错：中止，不退化（第 4.1、8 节）。
    case randomUnavailable
    /// 256 个计数器都没有得到合法的私钥标量（第 4.1 节）。
    case noValidScalar
    case invalidKey
}

/// 由主密钥 `K` 派生出的一个空间的全部材料（docs/plans/sync.md 第 4.1 节，写死）。
struct SyncKeyMaterial: Equatable {
    let blobID: String
    let encKey: [UInt8]
    let signCounter: Int
    /// P-256 私钥标量 `d` 的 32 字节大端定长编码。
    let d32: [UInt8]
    /// 未压缩 X9.63 公钥，65 字节。
    let publicKey: [UInt8]

    var publicKeyText: String { SyncEncoding.base64url(publicKey) }
    var fingerprint: String { SyncCode.fingerprint(blobID: blobID) }
}

/// 第 4、9 节的密码原语：HKDF、P-256 派生与签名、SHA-256、随机数。只用 CryptoKit 与 Security。
enum SyncCrypto {
    /// P-256 的阶 `n`，大端。
    static let order: [UInt8] = SyncEncoding.decodeHex("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")!
    static let idInfo = Array("vela-sync/v2/id".utf8)
    static let encInfo = Array("vela-sync/v2/enc".utf8)
    static let signInfo = Array("vela-sync/v2/sign".utf8)

    typealias HKDFFunction = (_ ikm: [UInt8], _ info: [UInt8]) -> [UInt8]

    /// HKDF-SHA256：salt 为 32 个 0x00（显式传入），L = 32。
    static func hkdf(_ ikm: [UInt8], info: [UInt8]) -> [UInt8] {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm), salt: Data(count: 32), info: Data(info), outputByteCount: 32
        )
        return key.withUnsafeBytes { Array($0) }
    }

    /// 派生 blobID、encKey 与签名私钥。`hkdf` 可注入，只为覆盖计数器重试路径（自然样本找不到 `c > 0`）。
    static func derive(_ k: [UInt8], hkdf: HKDFFunction = SyncCrypto.hkdf) throws -> SyncKeyMaterial {
        guard k.count == 32 else { throw SyncCryptoError.invalidKey }
        let blobID = SyncEncoding.hex(hkdf(k, idInfo))
        let encKey = hkdf(k, encInfo)
        for c in 0...255 {
            let t = hkdf(k, signInfo + [UInt8(c)])
            guard isValidScalar(t) else { continue }
            let pub = try publicKey(d32: t)
            return SyncKeyMaterial(blobID: blobID, encKey: encKey, signCounter: c, d32: t, publicKey: pub)
        }
        throw SyncCryptoError.noValidScalar
    }

    /// `1 ≤ d < n`（32 字节大端）。
    static func isValidScalar(_ t: [UInt8]) -> Bool {
        guard t.count == 32, t.contains(where: { $0 != 0 }) else { return false }
        return lessThanOrder(t)
    }

    static func lessThanOrder(_ v: [UInt8]) -> Bool {
        v.count == 32 && v.lexicographicallyPrecedes(order)
    }

    static func publicKey(d32: [UInt8]) throws -> [UInt8] {
        guard let key = try? P256.Signing.PrivateKey(rawRepresentation: d32) else { throw SyncCryptoError.invalidKey }
        return Array(key.publicKey.x963Representation)
    }

    /// ECDSA P-256 SHA-256，随机 `k` 由 CryptoKit 提供；输出 `r ‖ s` 各 32 字节。调用方不预先哈希。
    static func sign(_ message: [UInt8], d32: [UInt8]) throws -> [UInt8] {
        guard let key = try? P256.Signing.PrivateKey(rawRepresentation: d32) else { throw SyncCryptoError.invalidKey }
        guard let signature = try? key.signature(for: Data(message)) else { throw SyncCryptoError.randomUnavailable }
        return Array(signature.rawRepresentation)
    }

    /// 验签前的格式检查（第 9 节）：签名恰 64 字节、`r`、`s` 都在 `[1, n−1]`；公钥恰 65 字节、首字节 0x04、在曲线上。
    static func verify(signature: [UInt8], message: [UInt8], publicKey: [UInt8]) -> Bool {
        guard signature.count == 64 else { return false }
        let r = Array(signature[0..<32]), s = Array(signature[32..<64])
        guard isValidScalar(r), isValidScalar(s) else { return false }
        guard publicKey.count == 65, publicKey[0] == 0x04,
              let pub = try? P256.Signing.PublicKey(x963Representation: Data(publicKey)),
              let sig = try? P256.Signing.ECDSASignature(rawRepresentation: Data(signature)) else { return false }
        return pub.isValidSignature(sig, for: Data(message))
    }

    /// 公钥核对消息：ASCII `vela-sync/v2/pubcheck` + 0x0A + blobID。
    static func pubcheckMessage(blobID: String) -> [UInt8] {
        Array("vela-sync/v2/pubcheck\n\(blobID)".utf8)
    }

    /// 被签消息（第 9 节）：6 行 ASCII 以 0x0A 连接，末尾不加换行。put 时第 6 行为信封哈希，delete 时为 `-`。
    static func writeMessage(op: String, blobID: String, epoch: String, base: UInt64, envelopeHash: String?) -> [UInt8] {
        let lines = ["vela-sync/v2/\(op)", blobID, epoch, String(base), String(base + 1), envelopeHash ?? "-"]
        return Array(lines.joined(separator: "\n").utf8)
    }

    static func sha256(_ bytes: [UInt8]) -> [UInt8] { Array(SHA256.hash(data: Data(bytes))) }

    static func sha256Hex(_ text: String) -> String { SyncEncoding.hex(sha256(Array(text.utf8))) }

    /// 系统 CSPRNG；报错即抛出，调用方中止。
    static func random(_ count: Int) throws -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        guard status == errSecSuccess else { throw SyncCryptoError.randomUnavailable }
        return bytes
    }

    /// 同步码的校验和用 SHA-256。
    static func parseCode(_ text: String) -> SyncCode? { SyncCode.parse(text, sha256: sha256) }

    static func formatCode(key: [UInt8], origin: String) -> String? { SyncCode.format(key: key, origin: origin, sha256: sha256) }
}
#endif
