import Foundation
@testable import VelaPhone

/// 偏好文件隔离：`StageModel` 的口味、收藏、观看记录写在模拟器沙盒的固定位置，各用例之间会互相累加
/// （上一个用例屏蔽或收藏的房间，下一个用例一启动就读到）。双窗用例经这里造模型，用例前后先把待写的内容落盘、再删文件。
@MainActor
enum TestPrefs {
    private static var stages: [StageModel] = []

    static func stage(settings: SettingsStore) -> StageModel {
        let stage = StageModel(settings: settings)
        stages.append(stage)
        return stage
    }

    static func reset() {
        for stage in stages { stage.flushSavers() }
        stages = []
        let dir = PreferenceStore.defaultURL().deletingLastPathComponent()
        for name in ["feed-prefs.json", "feed-taste.json", "feed-curation.json", "watch-ledger.json"] {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(name))
        }
    }
}
