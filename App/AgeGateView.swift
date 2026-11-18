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
            AgeGateContent(enter: enter, quit: quit)
                .padding(.horizontal, 36)
                .padding(.vertical, 28)
                .padding(.top, Theme.gap)
                .frame(maxWidth: 460, maxHeight: .infinity, alignment: .leading)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
    }
}
