import AppKit
import Carbon.HIToolbox

/// macOS 平台钩子：老板键、系统活跃度、应用隐藏 / 终止通知与 `NSApp` 调用都在这里，模型层不直接碰 AppKit。
/// iOS 版同名类型在 `iOS/PlatformHooks+Phone.swift`，各自只登记进本平台目标。
@MainActor
final class PlatformHooks {
    /// macOS 支持「铺满」：`effectiveFill` 照旧由 `fitFill` 与竖屏自动完整画面决定。
    let allowsCrop = true

    private let bossKey = GlobalHotKey()
    private var activity: SystemActivity?
    private var unhideObserver: NSObjectProtocol?
    private var hideObserver: NSObjectProtocol?
    private var terminationObserver: NSObjectProtocol?

    nonisolated init() {}

    /// macOS 按设置：离开后淡出与点击穿透都可开。
    func adjustedPresence(_ presence: Presence) -> Presence { presence }

    /// macOS 窗口可见即在看，停留照常计时。
    var attentive: Bool { true }

    /// 没有音频转换执行者：macOS 的音量与静音由 `StageModel` 直接设给播放器。
    func attachAudio(to stage: StageModel) {}

    /// App 是否被隐藏（老板键或 ⌘H）。
    var appHidden: Bool { NSApp.isHidden }

    /// 订阅取消隐藏、隐藏、即将终止三个应用通知。回调在主线程。
    func observeApp(
        unhidden: @escaping @MainActor () -> Void,
        hidden: @escaping @MainActor () -> Void,
        terminating: @escaping @MainActor () -> Void
    ) {
        let center = NotificationCenter.default
        unhideObserver = center.addObserver(forName: NSApplication.didUnhideNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { unhidden() }
        }
        hideObserver = center.addObserver(forName: NSApplication.didHideNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { hidden() }
        }
        // 系统 ⌘Q 不经过 quit()：终止通知里同步写出未落盘的数据。
        terminationObserver = center.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { terminating() }
        }
    }

    /// 注册老板键（⌃⌥⌘V）并开始监听锁屏、睡眠、切换用户。老板键注册失败返回 false。
    func startSystemHooks(bossKey action: @escaping () -> Void, inactiveChanged: @escaping (Bool) -> Void) -> Bool {
        let registered = bossKey.register(
            keyCode: kVK_ANSI_V,
            modifiers: controlKey | optionKey | cmdKey,
            action: action
        )
        activity = SystemActivity(onChange: inactiveChanged)
        return registered
    }

    #if DEBUG
    /// 自测：模拟锁屏 / 解锁。
    func selftestSet(inactive: Bool) { activity?.selftestSet(inactive: inactive) }
    #endif

    func terminate() {
        NSApp.terminate(nil)
    }

    func hide() {
        NSApp.hide(nil)
    }

    func unhide() {
        NSApp.unhide(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    /// 取消应用隐藏、还原最小化并把主窗口置前。
    func revealMain() {
        NSApp.unhide(nil)
        NSApp.activate(ignoringOtherApps: true)
        guard let window = NSApp.windows.first(where: { $0.title == "Vela" && !($0 is NSPanel) }) else { return }
        if window.isMiniaturized { window.deminiaturize(nil) }
        window.makeKeyAndOrderFront(nil)
    }
}
