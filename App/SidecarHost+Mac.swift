import AppKit
import WebKit

/// 侧车的 macOS 宿主：弱持有主窗口，把离屏网页视图挂到它的内容视图里（屏外坐标，不可见）。
@MainActor
final class SidecarHost {
    weak var window: NSWindow?

    nonisolated init() {}

    /// 没有窗口时什么也不做；窗口挂上后由 `SiteSidecar.hostDidAttach()` 补挂。
    func attach(_ view: WKWebView) {
        window?.contentView?.addSubview(view)
    }
}

extension SiteSidecar {
    /// 主窗口就绪时由窗口桥接调用。
    func hold(window: NSWindow) {
        host.window = window
        hostDidAttach()
    }
}
