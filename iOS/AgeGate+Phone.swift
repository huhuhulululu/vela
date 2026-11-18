import SwiftUI

/// iOS 外壳：手机竖屏布局，内容居左、垂直居中；小屏或大字号时可滚动。没有拖动条与窗口按钮，也没有「退出」按钮。
struct AgeGateView: View {
    var enter: () -> Void

    var body: some View {
        GeometryReader { geo in
            ScrollView {
                AgeGateContent(enter: enter)
                    .padding(.horizontal, 28)
                    .padding(.vertical, 32)
                    .frame(maxWidth: 460, alignment: .leading)
                    .frame(maxWidth: .infinity, minHeight: geo.size.height, alignment: .leading)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
    }
}
