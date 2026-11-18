import AppKit
import Carbon.HIToolbox

/// 全局快捷键，用 Carbon RegisterEventHotKey，不需要辅助功能权限。
final class GlobalHotKey {
    private var handler: EventHandlerRef?
    private var hotKey: EventHotKeyRef?
    private static var fire: (() -> Void)?

    /// 注册失败（例如组合键被别的程序占用）返回 false。
    @discardableResult
    func register(keyCode: Int, modifiers: Int, action: @escaping () -> Void) -> Bool {
        Self.fire = action
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        let installed = InstallEventHandler(GetApplicationEventTarget(), { _, _, _ in
            DispatchQueue.main.async { GlobalHotKey.fire?() }
            return noErr
        }, 1, &spec, nil, &handler)
        guard installed == noErr else { return false }
        let id = EventHotKeyID(signature: OSType(0x5645_4C41), id: 1)
        return RegisterEventHotKey(UInt32(keyCode), UInt32(modifiers), id, GetApplicationEventTarget(), 0, &hotKey) == noErr
    }

    deinit {
        if let hotKey { UnregisterEventHotKey(hotKey) }
        if let handler { RemoveEventHandler(handler) }
    }
}

/// 锁屏、屏幕睡眠、系统睡眠、切换用户期间视为「不活跃」。多个原因可能叠加
/// （先睡屏再锁屏），所以按原因记账，全部解除后才回报恢复。
final class SystemActivity {
    private var reasons = Set<String>()
    private var tokens: [(NotificationCenter, NSObjectProtocol)] = []
    private let onChange: (Bool) -> Void

    /// `onChange(true)` 表示进入不活跃，`false` 表示全部恢复。回调在主线程。
    init(onChange: @escaping (Bool) -> Void) {
        self.onChange = onChange
        let workspace = NSWorkspace.shared.notificationCenter
        watch(workspace, NSWorkspace.screensDidSleepNotification, NSWorkspace.screensDidWakeNotification, "screen")
        watch(workspace, NSWorkspace.willSleepNotification, NSWorkspace.didWakeNotification, "sleep")
        watch(workspace, NSWorkspace.sessionDidResignActiveNotification, NSWorkspace.sessionDidBecomeActiveNotification, "session")
        watch(
            DistributedNotificationCenter.default(),
            Notification.Name("com.apple.screenIsLocked"),
            Notification.Name("com.apple.screenIsUnlocked"),
            "lock"
        )
    }

    deinit {
        tokens.forEach { $0.0.removeObserver($0.1) }
    }

    private func watch(_ center: NotificationCenter, _ begin: Notification.Name, _ end: Notification.Name, _ reason: String) {
        tokens.append((center, center.addObserver(forName: begin, object: nil, queue: .main) { [weak self] _ in
            self?.set(reason, active: true)
        }))
        tokens.append((center, center.addObserver(forName: end, object: nil, queue: .main) { [weak self] _ in
            self?.set(reason, active: false)
        }))
    }

    #if DEBUG
    /// 自测：与真实锁屏通知走同一条记账与回调路径。
    func selftestSet(inactive: Bool) { set("selftest", active: inactive) }
    #endif

    private func set(_ reason: String, active: Bool) {
        let wasInactive = !reasons.isEmpty
        if active { reasons.insert(reason) } else { reasons.remove(reason) }
        let isInactive = !reasons.isEmpty
        if isInactive != wasInactive { onChange(isInactive) }
    }
}
