import SwiftUI

/// macOS 外壳：拖动条、窗口按钮，内容居左。
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
            AgeGateContent(copy: .mac, enter: enter, quit: quit)
                .padding(.horizontal, 36)
                .padding(.vertical, 28)
                .padding(.top, Theme.gap)
                .frame(maxWidth: 460, maxHeight: .infinity, alignment: .leading)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
    }
}

extension AgeGateCopy {
    /// macOS 版文案：保留窗口置顶、变淡的说法。
    static let mac = AgeGateCopy(
        headline: "浮在桌面上的\n直播舞台",
        blurb: "原生播放 Chaturbate。画面由硬件解码，窗口可以置顶、变淡，并像短视频一样上下切换。仅供年满 18 岁的人使用。"
    )
}
