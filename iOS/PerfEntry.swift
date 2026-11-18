import Foundation

/// 性能探针的启动入口。只在 `Perf` 配置（定义了 `PERF_PROBE`）里存在；
/// Release 里整段不编译，`scripts/check-ios-release-no-perf.sh` 会检查产物里搜不到下面的标识串。
#if PERF_PROBE
enum PerfEntry {
    static let autoplayArgument = "--perf-autoplay"
    /// 长标识串：进二进制的日志文本，供安全门在 Release 产物里查找（短字符串可能被内联而漏检）。
    static let marker = "vela-perf-entry-armed-8c41d7e2b9a0"

    /// 带 `--perf-autoplay` 启动时：只在内存里视为已确认年龄并强制静音，不写设置。
    /// 在 `VelaPhoneApp` 创建模型之后、首次 `bootstrap()` 之前调用；返回是否生效。
    @MainActor @discardableResult
    static func applyLaunchArguments(
        to stage: StageModel, arguments: [String] = ProcessInfo.processInfo.arguments
    ) -> Bool {
        guard arguments.contains(autoplayArgument) else { return false }
        stage.ageConfirmed = true
        stage.muted = true
        for lane in stage.lanes { stage.pushVolume(to: lane) }
        AudioFocusController.log.info("\(marker, privacy: .public)")
        return true
    }
}
#else
enum PerfEntry {
    /// Release 与 Debug：没有入口，恒为假。
    @MainActor @discardableResult
    static func applyLaunchArguments(to stage: StageModel, arguments: [String] = []) -> Bool { false }
}
#endif
