import SwiftUI
import UIKit
import WebKit

/// 侧车的 iOS 宿主：弱持有场景窗口，把离屏网页视图挂进去（屏外坐标，不可见）。
@MainActor
final class SidecarHost {
    weak var window: UIWindow?

    nonisolated init() {}

    /// 没有窗口时什么也不做；窗口挂上后由 `SiteSidecar.hostDidAttach()` 补挂。
    func attach(_ view: WKWebView) {
        window?.addSubview(view)
    }
}

extension SiteSidecar {
    /// 场景窗口就绪时由 `SceneWindowReader` 调用。同一窗口重复调用不做事。
    func hold(window: UIWindow) {
        guard host.window !== window else { return }
        host.window = window
        hostDidAttach()
    }
}

/// 取所在的场景窗口：视图挂进窗口时回报一次（放在 `.background` 里，不占位置、不接收触摸）。
struct SceneWindowReader: UIViewRepresentable {
    var found: (UIWindow) -> Void

    func makeUIView(context: Context) -> WindowProbe {
        let view = WindowProbe()
        view.found = found
        return view
    }

    func updateUIView(_ view: WindowProbe, context: Context) {
        view.found = found
    }

    final class WindowProbe: UIView {
        var found: ((UIWindow) -> Void)?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            backgroundColor = .clear
        }

        required init?(coder: NSCoder) { fatalError() }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let window { found?(window) }
        }
    }
}
