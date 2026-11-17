import SwiftUI

struct AgeGateView: View {
    var enter: () -> Void
    var quit: () -> Void

    var body: some View {
        ZStack(alignment: .topLeading) {
            DragStrip()
                .frame(height: Theme.barHeight)
                .frame(maxHeight: .infinity, alignment: .top)
            WindowButtons()
                .padding(Theme.inset)
            content
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("VELA")
                .font(.system(size: 12, weight: .semibold))
                .tracking(7)
                .foregroundStyle(Theme.champagne)
            Text("浮在桌面上的\n直播舞台")
                .font(.system(size: 32, weight: .semibold))
                .tracking(-0.8)
                .lineSpacing(2)
            Text("原生播放 Chaturbate。画面由硬件解码，窗口可以置顶、变淡，并像短视频一样上下切换。仅供年满 18 岁的人使用。")
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecondary)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 16) {
                Button("已满 18 岁", action: enter)
                    .buttonStyle(ChampagneButton())
                Button("退出", action: quit)
                    .buttonStyle(.plain)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Theme.textSecondary)
            }
            .padding(.top, Theme.gap)
        }
        .padding(.horizontal, 36)
        .padding(.vertical, 28)
        .padding(.top, Theme.gap)
        .frame(maxWidth: 460, maxHeight: .infinity, alignment: .leading)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}

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
            .clickableRegion()
            .opacity(configuration.isPressed ? 0.78 : 1)
    }
}
