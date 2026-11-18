import SwiftUI

/// 年龄确认页的文案。`common` 两端通用；macOS 外壳另有带窗口说法的版本（`App/AgeGateView.swift`）。
struct AgeGateCopy: Equatable {
    var headline: String
    var blurb: String

    static let common = AgeGateCopy(
        headline: "轻巧的\n直播舞台",
        blurb: "原生播放 Chaturbate。画面由硬件解码，像短视频一样上下切换。仅供年满 18 岁的人使用。"
    )
}

/// 年龄确认页的共享内容：文案与按钮。窗口外壳（macOS 的拖动条与窗口按钮、iOS 的手机布局）各自包在外面。
/// `quit` 为 nil 时不显示「退出」按钮（iOS 没有由 App 自己退出的做法）。
struct AgeGateContent: View {
    var copy: AgeGateCopy = .common
    var enter: () -> Void
    var quit: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("VELA")
                .font(.system(size: 12, weight: .semibold))
                .tracking(7)
                .foregroundStyle(Theme.champagne)
            Text(copy.headline)
                .font(.system(size: 32, weight: .semibold))
                .tracking(-0.8)
                .lineSpacing(2)
            Text(copy.blurb)
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecondary)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 16) {
                Button("已满 18 岁", action: enter)
                    .buttonStyle(ChampagneButton())
                if let quit {
                    Button("退出", action: quit)
                        .buttonStyle(.plain)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Theme.textSecondary)
                }
            }
            .padding(.top, Theme.gap)
        }
    }
}
