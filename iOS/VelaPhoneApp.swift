import SwiftUI

@main
struct VelaPhoneApp: App {
    @StateObject private var model = StageModel()
    @Environment(\.scenePhase) private var scenePhase

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
