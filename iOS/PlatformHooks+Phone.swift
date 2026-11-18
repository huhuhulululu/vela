import Foundation

/// iOS 平台钩子（占位）：没有老板键、菜单栏与应用隐藏；画面不裁切（裁决 5），`allowsCrop` 恒为 false。
/// 前后台与场景阶段的接线在后续任务里由 `PhoneLifecycle` 写入。
@MainActor
final class PlatformHooks {
    let allowsCrop = false

    nonisolated init() {}

    var appHidden: Bool { false }

    func observeApp(
        unhidden: @escaping @MainActor () -> Void,
        hidden: @escaping @MainActor () -> Void,
        terminating: @escaping @MainActor () -> Void
    ) {}

    /// iOS 没有全局热键与锁屏通知，视为注册成功，不打「boss key unavailable」。
    func startSystemHooks(bossKey action: @escaping () -> Void, inactiveChanged: @escaping (Bool) -> Void) -> Bool {
        true
    }

    func terminate() {}

    func hide() {}

    func unhide() {}

    func revealMain() {}
}
