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
}
