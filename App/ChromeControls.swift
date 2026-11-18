import AppKit
import SwiftUI

/// 来源切换：四项最小等宽、不折行，选中项有底色，点击区域高度不小于 `Theme.tapHeight`。
struct SourceSwitch: View {
    var selected: FeedSource
    var select: (FeedSource) -> Void

    var body: some View {
        HStack(spacing: 2) {
            ForEach(FeedSource.allCases, id: \.self) { source in
                let on = source == selected
                Button { select(source) } label: {
                    Text(Copy.source(source))
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(1)
                        .fixedSize()
                        .padding(.horizontal, 9)
                        .frame(minWidth: 57, minHeight: Theme.tapHeight)
                        .background(on ? Theme.selectedFill : Color.clear, in: Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(QuietButtonStyle())
                .foregroundStyle(on ? Theme.champagne : Theme.textSecondary)
                .help(Copy.sourceHelp(source))
                .accessibilityLabel(Copy.source(source))
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(2)
        .background(Theme.chip, in: Capsule())
        .layoutPriority(1)
    }
}

/// 控制栏圆形图标的外观（图标按钮与心形按钮共用）。
struct ChromeIconFace: View {
    var symbol: String
    var active = false
    /// 选中态着色；nil 时沿用香槟色。
    var activeTint: Color? = nil
    var size: CGFloat = Theme.iconSize

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size > 28 ? 13 : 11, weight: .semibold))
            .foregroundStyle(active ? (activeTint ?? Theme.champagne) : Theme.textPrimary)
            .frame(width: size, height: size)
            .background(.ultraThinMaterial, in: Circle())
            .overlay(Circle().strokeBorder(.white.opacity(0.14), lineWidth: 0.6))
            .frame(minWidth: Theme.tapHeight, minHeight: Theme.tapHeight)
            .contentShape(Circle())
    }
}

struct ChromeIconButton: View {
    var symbol: String
    var active = false
    var help: String
    var label: String
    var size: CGFloat = Theme.iconSize
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            ChromeIconFace(symbol: symbol, active: active, size: size)
        }
        .buttonStyle(QuietButtonStyle())
        .help(help)
        .accessibilityLabel(label)
    }
}

/// 自绘的关闭与最小化：系统红绿灯已隐藏。走响应链，与菜单里的 ⌘W、⌘M 同一路径。
struct WindowButtons: View {
    var body: some View {
        HStack(spacing: 4) {
            ChromeIconButton(symbol: "xmark", help: "关闭窗口（⌘W）", label: "关闭窗口", size: Theme.windowButtonSize) {
                NSApp.sendAction(#selector(NSWindow.performClose(_:)), to: nil, from: nil)
            }
            ChromeIconButton(symbol: "minus", help: "最小化（⌘M）", label: "最小化", size: Theme.windowButtonSize) {
                NSApp.sendAction(#selector(NSWindow.miniaturize(_:)), to: nil, from: nil)
            }
        }
    }
}
