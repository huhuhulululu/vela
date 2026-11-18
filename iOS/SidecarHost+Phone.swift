import UIKit
import WebKit

/// 侧车的 iOS 宿主（占位）：弱持有场景窗口，把离屏网页视图挂进去。窗口在 iOS 界面接入时设置。
@MainActor
final class SidecarHost {
    weak var window: UIWindow?

    nonisolated init() {}

    func attach(_ view: WKWebView) {
        window?.addSubview(view)
    }
}
