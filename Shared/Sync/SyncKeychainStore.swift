#if canImport(Security)
import Foundation
import Security
#if SWIFT_PACKAGE
import VelaCore
#endif

/// 钥匙串条目 `{K, dev, blobID}`（docs/plans/sync.md 第 12.1 节）：一次写入，原子生效。
struct SyncKeychainEntry: Equatable {
    let key: [UInt8]
    let dev: UInt32
    let blobID: String

    private struct Wire: Codable {
        let K: String
        let dev: UInt32
        let blobID: String
    }

    func encoded() -> Data {
        // 字段都是 base64url、十进制与小写 hex，JSONEncoder 不会转义出歧义。
        (try? JSONEncoder().encode(Wire(K: SyncEncoding.base64url(key), dev: dev, blobID: blobID))) ?? Data()
    }

    static func decode(_ data: Data) -> SyncKeychainEntry? {
        guard let wire = try? JSONDecoder().decode(Wire.self, from: data),
              let key = SyncEncoding.decodeBase64url(wire.K), key.count == 32, wire.dev != 0,
              wire.blobID.utf8.count == 64, SyncEncoding.decodeHex(wire.blobID) != nil else { return nil }
        return SyncKeychainEntry(key: key, dev: wire.dev, blobID: wire.blobID)
    }
}

/// 读取结果分三类（第 13 节），另加「本构建不支持」。
enum SyncKeychainLookup: Equatable {
    case none
    case present(SyncKeychainEntry)
    /// 首次解锁前等暂时性错误：本次跳过同步，稍后再试。
    case unavailable
    /// 内容损坏、解包失败：「同步密钥丢失，请重新输入同步码」。
    case invalidated
    /// 缺钥匙串授权（`errSecMissingEntitlement`）：本构建不支持同步。
    case unsupported
}

enum SyncKeychainWrite: Equatable {
    case ok, unavailable, unsupported, failed
}

/// `SecItem*` 的可注入接口，便于故障注入测试。
protocol SyncSecItem: AnyObject {
    func add(_ attributes: [String: Any]) -> OSStatus
    func update(_ query: [String: Any], _ attributes: [String: Any]) -> OSStatus
    func copy(_ query: [String: Any]) -> (OSStatus, Data?)
    func delete(_ query: [String: Any]) -> OSStatus
}

final class SystemSecItem: SyncSecItem {
    func add(_ attributes: [String: Any]) -> OSStatus { SecItemAdd(attributes as CFDictionary, nil) }
    func update(_ query: [String: Any], _ attributes: [String: Any]) -> OSStatus { SecItemUpdate(query as CFDictionary, attributes as CFDictionary) }
    func copy(_ query: [String: Any]) -> (OSStatus, Data?) {
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        return (status, result as? Data)
    }
    func delete(_ query: [String: Any]) -> OSStatus { SecItemDelete(query as CFDictionary) }
}

/// 通用密码项：service `com.vela.sync`（不用 `com.vela.stage`，`LegacyLoginCleanup` 会清那里），account `space`，
/// 仅本机、首次解锁后可读、不进 iCloud 钥匙串、不设共享访问组（第 13 节）。
final class SyncKeychainStore: SyncKeychain {
    static let service = "com.vela.sync"
    static let account = "space"
    let service: String
    private let secItem: SyncSecItem

    init(service: String = SyncKeychainStore.service, secItem: SyncSecItem = SystemSecItem()) {
        self.service = service
        self.secItem = secItem
    }

    private var baseQuery: [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: Self.account,
            kSecAttrSynchronizable as String: false,
        ]
        #if os(macOS)
        q[kSecUseDataProtectionKeychain as String] = true
        #endif
        return q
    }

    func readEntry() -> SyncKeychainLookup {
        var q = baseQuery
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        let (status, data) = secItem.copy(q)
        switch status {
        case errSecSuccess:
            return data.flatMap(SyncKeychainEntry.decode).map { .present($0) } ?? .invalidated
        case errSecItemNotFound: return .none
        case errSecMissingEntitlement: return .unsupported
        case errSecDecode, errSecAuthFailed: return .invalidated
        default: return .unavailable
        }
    }

    func write(_ entry: SyncKeychainEntry) -> SyncKeychainWrite {
        var attrs = baseQuery
        attrs[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        attrs[kSecValueData as String] = entry.encoded()
        var status = secItem.add(attrs)
        if status == errSecDuplicateItem {
            status = secItem.update(baseQuery, [
                kSecValueData as String: entry.encoded(),
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ])
        }
        switch status {
        case errSecSuccess: return .ok
        case errSecMissingEntitlement: return .unsupported
        case errSecInteractionNotAllowed, errSecNotAvailable: return .unavailable
        default: return .failed
        }
    }

    // MARK: SyncKeychain（S1 协调器与启动检查用）

    func read() -> SyncKeychainRead {
        switch readEntry() {
        case .none: return .none
        case .present(let entry): return .present(blobID: entry.blobID)
        case .unavailable, .unsupported: return .unavailable
        case .invalidated: return .invalidated
        }
    }

    func delete() -> Bool {
        let status = secItem.delete(baseQuery)
        return status == errSecSuccess || status == errSecItemNotFound
    }
}
#endif
