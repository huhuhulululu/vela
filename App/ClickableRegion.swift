import AppKit
import SwiftUI

/// 登记「这里可点」：垫在按钮label背后的透明标记视图，自己不接收任何鼠标事件。
/// SwiftUI 自定义样式的按钮命中的是宿主视图，AppKit 看不出它是控件；拖窗判定靠这些标记的区域排除它们。
struct ClickableRegion: NSViewRepresentable {
    func makeNSView(context: Context) -> ClickableRegionView { ClickableRegionView() }
    func updateNSView(_ nsView: ClickableRegionView, context: Context) {}
}

final class ClickableRegionView: NSView {
    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    /// 内容视图层级里所有可见标记的窗口坐标区域。
    static func regions(in root: NSView) -> [CGRect] {
        var found: [CGRect] = []
        var stack: [NSView] = [root]
        while let view = stack.popLast() {
            if view is ClickableRegionView, !view.isHiddenOrHasHiddenAncestor {
                found.append(view.convert(view.bounds, to: nil))
            }
            stack.append(contentsOf: view.subviews)
        }
        return found
    }
}

extension View {
    /// 把这个视图的范围登记为可点区域（不改变布局与命中）。
    func clickableRegion() -> some View { background(ClickableRegion()) }
}
