import Foundation

/// 性能探针的启动入口。只在 `Perf` 配置（定义了 `PERF_PROBE`）里存在；
/// Release 里整段不编译，`scripts/check-ios-release-no-perf.sh` 会检查产物里搜不到下面的标识串。
#if PERF_PROBE
enum PerfEntry {
    static let autoplayArgument = "--perf-autoplay"
    /// 只在内存里开双窗（不写设置），配合 `--perf-stream` 的两个测试房间。
    static let dualArgument = "--perf-dual"
    /// 注入热状态：`--perf-thermal serious@40,fair@70` 表示启动后 40 秒注入 `.serious`、70 秒注入 `.fair`。
    /// 模拟器没有真实的热状态，靠它验证降档与回升的逻辑（回升仍要等真实的 60 秒）。
    static let thermalArgument = "--perf-thermal"
    /// 画中画实验开关（T18b 排查 AVKitErrorDomain -1001 用，只在 Perf 构建里）：
    /// `--perf-no-pip-solo` 进入画中画时不先释放另一路；`--perf-pip-all-lanes` 让每一路的播放层都挂画中画控制器。
    static let noPipSolo = CommandLine.arguments.contains("--perf-no-pip-solo")
    static let pipOnAllLanes = CommandLine.arguments.contains("--perf-pip-all-lanes")
    /// `--perf-pip-start <秒>`：启动后这么多秒在前台手动调一次画中画（等于点了控制栏的画中画按钮），日志里看 `pip started` 或失败码。
    static let pipStartArgument = "--perf-pip-start"
    /// `--perf-open-sync`：启动 3 秒后打开口味面板并展开「跨设备同步」（模拟器上验打开同步入口不崩）。
    static let openSyncArgument = "--perf-open-sync"
    static let openSync = CommandLine.arguments.contains(openSyncArgument)
    /// 长标识串：进二进制的日志文本，供安全门在 Release 产物里查找（短字符串可能被内联而漏检）。
    static let marker = "vela-perf-entry-armed-8c41d7e2b9a0"

    /// 带 `--perf-autoplay` 启动时：只在内存里视为已确认年龄并强制静音，不写设置。
    /// 在 `VelaPhoneApp` 创建模型之后、首次 `bootstrap()` 之前调用；返回是否生效。
    @MainActor @discardableResult
    static func applyLaunchArguments(
        to stage: StageModel, arguments: [String] = ProcessInfo.processInfo.arguments
    ) -> Bool {
        if arguments.contains(openSyncArgument) {
            Task { @MainActor [weak stage] in
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                stage?.showTaste = true
            }
        }
        guard arguments.contains(autoplayArgument) else { return false }
        stage.ageConfirmed = true
        stage.muted = true
        for lane in stage.lanes { stage.pushVolume(to: lane) }
        if arguments.contains(dualArgument) { stage.setDual(true, remember: false) }
        injectThermal(into: stage, arguments: arguments)
        startPictureInPictureLater(stage: stage, arguments: arguments)
        AudioFocusController.log.info("\(marker, privacy: .public)")
        return true
    }

    @MainActor
    private static func startPictureInPictureLater(stage: StageModel, arguments: [String]) {
        guard let i = arguments.firstIndex(of: pipStartArgument), i + 1 < arguments.count, let seconds = Double(arguments[i + 1]) else { return }
        Task { @MainActor [weak stage] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            stage?.platform.pipCoordinator?.toggle()
        }
    }

    @MainActor
    private static func injectThermal(into stage: StageModel, arguments: [String]) {
        guard let i = arguments.firstIndex(of: thermalArgument), i + 1 < arguments.count else { return }
        let states: [String: PhoneThermalState] = ["nominal": .nominal, "fair": .fair, "serious": .serious, "critical": .critical]
        for token in arguments[i + 1].split(separator: ",") {
            let parts = token.split(separator: "@")
            guard parts.count == 2, let state = states[String(parts[0])], let seconds = Double(parts[1]) else { continue }
            Task { @MainActor [weak stage] in
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                stage?.noteThermal(state)
            }
        }
    }
}
#else
enum PerfEntry {
    static let noPipSolo = false
    static let pipOnAllLanes = false
    static let openSync = false
    /// Release 与 Debug：没有入口，恒为假。
    @MainActor @discardableResult
    static func applyLaunchArguments(to stage: StageModel, arguments: [String] = []) -> Bool { false }
}
#endif
