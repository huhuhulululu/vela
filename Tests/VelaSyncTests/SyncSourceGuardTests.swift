import Foundation
import XCTest

/// 源码级守卫：界面与工程配置里不便跑单测、但一旦回退就会泄露或崩溃的几条约束。
final class SyncSourceGuardTests: XCTestCase {
    static let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    func text(_ path: String) throws -> String { try String(contentsOf: Self.root.appendingPathComponent(path), encoding: .utf8) }

    /// 审计 medium：同步码文本不得开系统文本选择，复制只能走带敏感标记的「复制」按钮（第 4.3 节）。
    func testSyncCodeTextIsNotSystemSelectable() throws {
        XCTAssertFalse(try text("Shared/Stage/SyncPanel.swift").contains(".textSelection(.enabled)"))
    }

    /// 审计 medium：「查看同步码」用 deviceOwnerAuthentication，iOS 上会走 Face ID，缺用途声明会被系统直接终止进程。
    /// macOS 的 Touch ID 不需要这项声明（`NSFaceIDUsageDescription` 只在 iOS 生效）。
    func testFaceIDUsageDescriptionIsDeclaredOnIOS() throws {
        let data = try Data(contentsOf: Self.root.appendingPathComponent("iOS/Info.plist"))
        let plist = try XCTUnwrap(try PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any])
        let text = try XCTUnwrap(plist["NSFaceIDUsageDescription"] as? String)
        XCTAssertFalse(text.isEmpty)
    }
}
