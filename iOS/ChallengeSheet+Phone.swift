import SwiftUI

/// iOS 外壳：全高 sheet，深色。内容与 macOS 共用 `ChallengeContent`。
struct ChallengeSheet: View {
    @ObservedObject var model: StageModel

    var body: some View {
        ChallengeContent(model: model)
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .preferredColorScheme(.dark)
    }
}
