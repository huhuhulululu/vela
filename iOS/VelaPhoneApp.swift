import SwiftUI

@main
struct VelaPhoneApp: App {
    @StateObject private var model: StageModel
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Perf 配置的启动参数要在首次 bootstrap（视图出现时）之前生效；Release / Debug 里是空操作。
        _model = StateObject(wrappedValue: {
            let stage = StageModel()
            PerfEntry.applyLaunchArguments(to: stage)
            return stage
        }())
    }

    var body: some Scene {
        WindowGroup {
            PhoneStageView(model: model)
        }
        // 前后台：统一挂起与由 Stage 层恢复（§3.2）。
        .onChange(of: scenePhase) { _, phase in
            model.platform.scenePhaseChanged(PhoneScenePhase(phase), stage: model)
        }
    }
}
