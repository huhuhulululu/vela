#if canImport(Combine)
import Combine
#endif
import XCTest
@testable import VelaCore

/// 回归：`MenuBarExtra(isInserted:)` 每次场景更新都把同一个值写回绑定；相同值若也发通知，就会在视图更新期间无限刷新。
/// Linux 上的 XCTest 发现器不能直接调用 `@MainActor` 测试方法，断言改在 `MainActor.run` 里跑，内容不变。
final class MenuBarVisibilityTests: XCTestCase {
    func testWritingSameValueNeitherNotifiesNorSaves() async {
        await MainActor.run {
            var saved: [Bool] = []
            let prefs = MenuBarVisibility(initial: true) { saved.append($0) }
            var notified = 0
            let token = prefs.objectWillChange.sink { notified += 1 }
            for _ in 0..<100 { prefs.isShown = true }
            token.cancel()
            XCTAssertEqual(notified, 0)
            XCTAssertEqual(saved, [])
            XCTAssertTrue(prefs.isShown)
        }
    }

    func testRealChangeNotifiesOnceAndSaves() async {
        await MainActor.run {
            var saved: [Bool] = []
            let prefs = MenuBarVisibility(initial: true) { saved.append($0) }
            var notified = 0
            let token = prefs.objectWillChange.sink { notified += 1 }
            prefs.isShown = false
            prefs.isShown = false
            prefs.isShown = true
            token.cancel()
            XCTAssertEqual(notified, 2)
            XCTAssertEqual(saved, [false, true])
            XCTAssertTrue(prefs.isShown)
        }
    }
}
