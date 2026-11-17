import AppKit
import SwiftUI

/// 控制栏心形按钮：左键单击收藏或取消收藏当前房间，右键打开收藏夹。
/// 手势由一个覆盖在图标上的 AppKit 视图统一接管（`PressSurface`）：左键无论按多久，松开时指针仍在按钮内才算单击；
/// 右键没有 SwiftUI 手势可用，所以也由它接管。
struct HeartButton: View {
    var isFavorite: Bool
    /// 没有当前房间时为 false：图标变暗，左键不做事；右键仍可打开收藏夹。
    var canToggle: Bool
    var toggle: () -> Void
    var openList: () -> Void

    @State private var pressed = false

    var body: some View {
        ChromeIconFace(
            symbol: isFavorite ? "heart.fill" : "heart",
            active: isFavorite,
            activeTint: isFavorite ? Theme.favorite : nil
        )
            .opacity(canToggle ? 1 : 0.45)
            .opacity(pressed ? 0.62 : 1)
            .scaleEffect(pressed ? 0.96 : 1)
            .overlay { pressSurface }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(isFavorite ? "取消收藏当前房间" : "收藏当前房间")
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { if canToggle { toggle() } }
            .accessibilityAction(named: "打开收藏夹", openList)
    }

    private var pressSurface: some View {
        PressSurface(help: help, pressed: $pressed,
                     tap: { if canToggle { toggle() } }, secondary: openList)
    }

    var help: String {
        guard canToggle else { return "没有当前房间。右键打开收藏夹" }
        return "左键\(isFavorite ? "取消收藏" : "收藏")当前房间，右键打开收藏夹"
    }
}

/// 透明的鼠标接管层：左键在按钮内松开触发 `tap`，右键按下触发 `secondary`。
struct PressSurface: NSViewRepresentable {
    var help: String
    @Binding var pressed: Bool
    var tap: () -> Void
    var secondary: () -> Void

    func makeNSView(context: Context) -> PressView { PressView() }

    func updateNSView(_ view: PressView, context: Context) {
        // 父视图每次刷新都会进来。提示没变不要重设；手势闭包仍要更新，收藏状态变了要点新的动作。
        if view.toolTip != help { view.toolTip = help }
        view.onPressChange = { value in pressed = value }
        view.onTap = tap
        view.onSecondary = secondary
    }
}

final class PressView: NSView {
    var onPressChange: (Bool) -> Void = { _ in }
    var onTap: () -> Void = {}
    var onSecondary: () -> Void = {}

    override var mouseDownCanMoveWindow: Bool { false }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func mouseDown(with event: NSEvent) {
        onPressChange(true)
    }

    override func mouseUp(with event: NSEvent) {
        onPressChange(false)
        // 双击时第二下也会松开；只认一次点击序列的第一下，避免「收藏后立刻又取消」。
        guard event.clickCount <= 1 else { return }
        if bounds.contains(convert(event.locationInWindow, from: nil)) { onTap() }
    }

    override func rightMouseDown(with event: NSEvent) {
        onPressChange(false)
        onSecondary()
    }
}
