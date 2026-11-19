#if canImport(Security)
import Foundation
import Security
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 钥匙串属性与三类失败（docs/plans/sync.md 第 12.1、13 节）。系统钥匙串由可注入的 `SecItem` 接口隔离。
final class SyncKeychainTests: XCTestCase {
    final class FakeSecItem: SyncSecItem {
        var stored: Data?
        var copyStatus: OSStatus?
        var addStatus: OSStatus?
        var queries: [[String: Any]] = []
        var adds: [[String: Any]] = []

        func add(_ attributes: [String: Any]) -> OSStatus {
            adds.append(attributes)
            if let addStatus { return addStatus }
            if stored != nil { return errSecDuplicateItem }
            stored = attributes[kSecValueData as String] as? Data
            return errSecSuccess
        }

        func update(_ query: [String: Any], _ attributes: [String: Any]) -> OSStatus {
            queries.append(query)
            stored = attributes[kSecValueData as String] as? Data
            return errSecSuccess
        }

        func copy(_ query: [String: Any]) -> (OSStatus, Data?) {
            queries.append(query)
            if let copyStatus { return (copyStatus, nil) }
            return stored.map { (errSecSuccess, $0) } ?? (errSecItemNotFound, nil)
        }

        func delete(_ query: [String: Any]) -> OSStatus {
            queries.append(query)
            guard stored != nil else { return errSecItemNotFound }
            stored = nil
            return errSecSuccess
        }
    }

    let entry = SyncKeychainEntry(key: Array(0..<32).map { UInt8($0) }, dev: 0x0A0B_0C0D, blobID: String(repeating: "ab", count: 32))

    func testWriteUsesDeviceOnlyNonSynchronizableItemInSyncService() throws {
        let fake = FakeSecItem()
        let store = SyncKeychainStore(secItem: fake)
        XCTAssertEqual(store.write(entry), .ok)
        let attrs = try XCTUnwrap(fake.adds.first)
        XCTAssertEqual(attrs[kSecClass as String] as? String, kSecClassGenericPassword as String)
        XCTAssertEqual(attrs[kSecAttrService as String] as? String, "com.vela.sync")
        XCTAssertEqual(attrs[kSecAttrAccount as String] as? String, "space")
        XCTAssertEqual(attrs[kSecAttrAccessible as String] as? String, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
        XCTAssertEqual(attrs[kSecAttrSynchronizable as String] as? Bool, false)
        XCTAssertNil(attrs[kSecAttrAccessGroup as String], "不设共享访问组")
        #if os(macOS)
        XCTAssertEqual(attrs[kSecUseDataProtectionKeychain as String] as? Bool, true)
        #endif
        XCTAssertEqual(store.readEntry(), .present(entry))
        XCTAssertEqual(store.read(), .present(blobID: entry.blobID))
    }

    func testSecondWriteReplacesEntry() {
        let fake = FakeSecItem()
        let store = SyncKeychainStore(secItem: fake)
        XCTAssertEqual(store.write(entry), .ok)
        let other = SyncKeychainEntry(key: [UInt8](repeating: 9, count: 32), dev: 7, blobID: String(repeating: "cd", count: 32))
        XCTAssertEqual(store.write(other), .ok)
        XCTAssertEqual(store.readEntry(), .present(other))
    }

    /// 不存在：按第 12.1 节表格判断。
    func testMissingEntryReadsAsNone() {
        let store = SyncKeychainStore(secItem: FakeSecItem())
        XCTAssertEqual(store.readEntry(), .none)
        XCTAssertEqual(store.read(), .none)
        XCTAssertTrue(store.delete(), "删除不存在的条目视为成功")
    }

    /// 暂时不可用（首次解锁前）：本次跳过同步，不做任何判断。
    func testInteractionNotAllowedIsTemporarilyUnavailable() {
        let fake = FakeSecItem()
        fake.stored = Data("{}".utf8)
        fake.copyStatus = errSecInteractionNotAllowed
        let store = SyncKeychainStore(secItem: fake)
        XCTAssertEqual(store.readEntry(), .unavailable)
        XCTAssertEqual(store.read(), .unavailable)
        fake.addStatus = errSecInteractionNotAllowed
        XCTAssertEqual(store.write(entry), .unavailable)
    }

    /// 永久失效（解包失败、内容损坏）：「同步密钥丢失，请重新输入同步码」，绝不自动生成新 K。
    func testCorruptEntryIsPermanentlyInvalidated() {
        for bad in ["not json", "{\"K\":\"AAAA\",\"dev\":1,\"blobID\":\"x\"}", "{\"K\":\"\(SyncEncoding.base64url(entry.key))\",\"dev\":0,\"blobID\":\"\(entry.blobID)\"}"] {
            let fake = FakeSecItem()
            fake.stored = Data(bad.utf8)
            let store = SyncKeychainStore(secItem: fake)
            XCTAssertEqual(store.readEntry(), .invalidated, bad)
            XCTAssertEqual(store.read(), .invalidated, bad)
            XCTAssertEqual(fake.adds.count, 0, "失效时不得自动写新条目")
        }
        let fake = FakeSecItem()
        fake.copyStatus = errSecDecode
        XCTAssertEqual(SyncKeychainStore(secItem: fake).readEntry(), .invalidated)
    }

    /// 缺少钥匙串授权的构建视为不支持同步，不退化成明文文件；对协调器表现为「本次跳过」。
    func testMissingEntitlementMeansUnsupported() {
        let fake = FakeSecItem()
        fake.copyStatus = errSecMissingEntitlement
        fake.addStatus = errSecMissingEntitlement
        let store = SyncKeychainStore(secItem: fake)
        XCTAssertEqual(store.readEntry(), .unsupported)
        XCTAssertEqual(store.read(), .unavailable)
        XCTAssertEqual(store.write(entry), .unsupported)
    }

    /// 系统钥匙串实测：未签名的测试进程在 macOS 数据保护钥匙串上会缺授权，此时跳过并说明。
    func testSystemKeychainRoundTripWhenEntitled() throws {
        let store = SyncKeychainStore(service: "com.vela.sync.test-\(UUID().uuidString)")
        let written = store.write(entry)
        if written == .unsupported { throw XCTSkip("本进程缺钥匙串授权（errSecMissingEntitlement），系统钥匙串实测跳过") }
        XCTAssertEqual(written, .ok)
        defer { XCTAssertTrue(store.delete()) }
        XCTAssertEqual(store.readEntry(), .present(entry))
    }
}
#endif
