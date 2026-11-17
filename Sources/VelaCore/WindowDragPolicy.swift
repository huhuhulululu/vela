#if canImport(CoreGraphics)
import CoreGraphics
#endif
import Foundation

/// 左键按下的位置命中了什么，由 App 层根据视图层级判定后交给 `WindowDragPolicy`。
public enum DragHit: Equatable, Sendable {
    /// AppKit 控件、文本框，或自绘的按压面（心形按钮）。
    case control
    /// 顶栏的拖动条（自己负责拖窗）。
    case dragSurface
    /// 视频画面本体。
    case video
    /// 其余：SwiftUI 宿主视图（空白、垫底、文字，以及自定义样式的按钮）。
    case background
}

/// 画面左键拖窗的判定：凡是可点的控件，按下后无论怎么移动都不拖窗；
/// 只有画面空白、垫底、视频本体和顶栏空白可以拖。纯几何判定，坐标是窗口坐标（原点在左下）。
public enum WindowDragPolicy {
    /// `clickable`：窗口内所有登记过的可点区域（SwiftUI 自定义样式按钮用标记视图登记）。
    /// `contentHeight` 与 `barHeight`：顶栏占内容区顶部这么高，顶栏内的空白由拖动条自己处理，不走这里。
    public static func canStartDrag(
        hit: DragHit, point: CGPoint, clickable: [CGRect], contentHeight: CGFloat, barHeight: CGFloat
    ) -> Bool {
        switch hit {
        case .control, .dragSurface:
            return false
        case .video, .background:
            break
        }
        if clickable.contains(where: { $0.contains(point) }) { return false }
        if hit == .video { return true }
        return point.y < contentHeight - barHeight
    }
}
