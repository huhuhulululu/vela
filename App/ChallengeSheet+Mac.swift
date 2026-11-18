import SwiftUI

/// macOS 外壳：固定宽度，高度随窗口高度（`SheetLayout`），深色。
struct ChallengeSheet: View {
    @ObservedObject var model: StageModel

    var body: some View {
        ChallengeContent(model: model)
            .frame(width: 480, height: CGFloat(SheetLayout.challengeHeight(windowHeight: model.pointHeight)))
            .preferredColorScheme(.dark)
    }
}
