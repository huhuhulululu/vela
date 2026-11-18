import SwiftUI

/// 面板与控制栏通用的安静按钮：按下变淡、略缩小。可点范围登记见 `platformClickRegion()`。
struct QuietButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .platformClickRegion()
            .opacity(configuration.isPressed ? 0.62 : 1)
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// 香槟色主按钮（年龄确认、质询完成、重试）。
struct ChampagneButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(Color.black.opacity(0.86))
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .frame(minHeight: Theme.tapHeight)
            .lineLimit(1)
            .background(Theme.champagne, in: Capsule())
            .platformClickRegion()
            .opacity(configuration.isPressed ? 0.78 : 1)
    }
}
