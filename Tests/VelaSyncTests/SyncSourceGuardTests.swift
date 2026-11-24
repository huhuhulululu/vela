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

    /// 安全审查 L7：macOS 复制同步码 120 秒后，若剪贴板没被别的内容替换（changeCount 未变）就清空，与 iOS 的过期一致。
    func testMacPasteboardIsClearedAfter120Seconds() throws {
        let panel = try text("Shared/Stage/SyncPanel.swift")
        XCTAssertTrue(panel.contains("SyncPasteboard.expirySeconds"))
        XCTAssertTrue(panel.contains("SyncPasteboardController"))
        let core = try text("Sources/VelaCore/Sync/SyncLifecycle.swift")
        XCTAssertTrue(core.contains("clipboard.changeCount == stamp"))
    }

    /// 复审 medium：macOS 剪贴板过期不能只靠进程内延时。明文与 ConcealedType 一次写入（同一个 NSPasteboardItem），
    /// 退出时清掉仍属于本次写入的内容，下次启动也检查一次。
    func testMacPasteboardExpirySurvivesProcessExit() throws {
        let panel = try text("Shared/Stage/SyncPanel.swift")
        XCTAssertTrue(panel.contains("NSPasteboardItem()"))
        XCTAssertFalse(panel.contains("board.setString(text, forType: .string)"), "不再先单独写明文")
        XCTAssertTrue(panel.contains("NSApplication.willTerminateNotification"), "退出清理观察者在 SyncPasteboard 独立注册")
        let hub = try text("Shared/Stage/StageModel+Sync.swift")
        XCTAssertTrue(hub.contains("SyncPasteboard.expireLeftover()"), "启动时检查")
    }

    /// 复审 low：每次复制生成新令牌；到期任务只在令牌仍是自己的、剪贴板也没变时才清空，不会提前清掉后一次复制。
    func testPasteboardExpiryUsesPerCopyToken() throws {
        let core = try text("Sources/VelaCore/Sync/SyncLifecycle.swift")
        XCTAssertTrue(core.contains("UUID().uuidString"))
        XCTAssertTrue(core.contains("expire(onlyIf: token)"))
    }

    /// 实验开关默认关；关着时不接线（不读钥匙串、不联网、不注册生命周期观察）。
    func testSyncIsBehindDefaultOffExperimentFlag() throws {
        let settings = try text("Shared/Stage/SettingsStore.swift")
        XCTAssertTrue(settings.contains("defaults.bool(forKey: \"experimentSync\")"), "默认 false")
        let hub = try text("Shared/Stage/StageModel+Sync.swift")
        XCTAssertTrue(hub.contains("guard experimentEnabled else { return }"))
    }

    /// 审计 A/B：加入回滚写回设置后必须返回 `settings.synchronize()` 的刷盘结果，失败时不丢弃快照。
    func testJoinJournalWriteSettingsSynchronizes() throws {
        let hub = try text("Shared/Stage/StageModel+Sync.swift")
        let start = try XCTUnwrap(hub.range(of: "static func journal(settings:")).lowerBound
        let end = try XCTUnwrap(hub.range(of: "static func defaultEnvironment()")).lowerBound
        let body = String(hub[start..<end])
        XCTAssertTrue(body.contains("settings.setExplicit("))
        XCTAssertTrue(body.contains("return settings.synchronize()"), "writeSettings 必须传播 synchronize() 刷盘结果")
    }
}
