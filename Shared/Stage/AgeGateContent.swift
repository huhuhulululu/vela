import SwiftUI

/// 年龄确认页的共享内容：文案与两个按钮。窗口外壳（macOS 的拖动条与窗口按钮、iOS 的手机布局）各自包在外面。
struct AgeGateContent: View {
    var enter: () -> Void
    var quit: () -> Void

    var body: some View {
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
    }
}
