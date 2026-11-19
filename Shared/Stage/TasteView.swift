import SwiftUI

/// 口味面板的状态装配：把 StageModel 的画像整理成显示数据，接上回调。
struct TasteView: View {
    @ObservedObject var model: StageModel
    /// 透传给 `TastePanel.width`：macOS 用默认固定宽，iOS 传 nil 随屏宽。
    var width: CGFloat? = TastePanel.preferredWidth

    var body: some View {
        TastePanel(
            summary: model.tasteSummary,
            suggestions: model.tasteSuggestions,
            paused: model.taste.paused,
            boost: { model.boostTaste($0) },
            veto: { model.vetoTaste($0) },
            restore: { model.restoreTaste($0) },
            remove: { model.removeTaste($0) },
            setPaused: { model.setLearningPaused($0) },
            resetProfile: { model.resetTasteProfile() },
            clearAll: { model.clearAllPreferences() },
            syncOn: model.sync.isOn,
            width: width
        )
        .task {
            // 只有冷启动整理会用到热门标签。
            guard model.tasteSummary.tagCount < Taste.coldStartTags else { return }
            await model.loadHotTagsIfStale()
        }
    }
}
