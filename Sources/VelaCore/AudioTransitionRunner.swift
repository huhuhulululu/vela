import Foundation

/// 会话执行器要用的系统调用。iOS 由 `AVAudioSession` 实现，测试用桩。Core 不引用 AVFoundation。
public protocol AudioSessionDriving {
    func setCategory(_ category: AudioSessionCategory, mode: AudioSessionMode?, options: AudioSessionOptions) throws
    func setActive(_ active: Bool, notifyOthers: Bool) throws
}

/// 各路播放器的静音入口。实现方只改运行时状态，不写设置。
public protocol AudioLaneControlling {
    func setMuted(_ muted: Bool, lane: PlaybackLaneRole)
}

/// 一次转换执行完之后，调用方要落实的结果。
public struct AudioOutcome: Equatable, Sendable {
    /// 运行时 `muted` 的新值；nil 表示不改（忽略的打断、打断结束、无关路由）。
    public var mutedAfter: Bool?
    public var audibleLane: PlaybackLaneRole?
    /// 为假时调用方不得把 `mutedAfter` 写进设置。
    public var writesSettings: Bool
    public var notice: String?
    public var pausesPlayback: Bool
    public var ignored: Bool
    public var failed: Bool
    /// 计划里的 `setActive(false)` 失败了（他人没收到恢复通知），调用方要重试或补做。
    public var deactivationFailed: Bool
    /// 这次成功执行的 `setActive` 的结果：true 已激活，false 已停用；nil 表示没有这一步或这一步失败。
    public var sessionActiveAfter: Bool?

    public init(
        mutedAfter: Bool?, audibleLane: PlaybackLaneRole?, writesSettings: Bool,
        notice: String?, pausesPlayback: Bool, ignored: Bool, failed: Bool,
        deactivationFailed: Bool = false, sessionActiveAfter: Bool? = nil
    ) {
        self.mutedAfter = mutedAfter
        self.audibleLane = audibleLane
        self.writesSettings = writesSettings
        self.notice = notice
        self.pausesPlayback = pausesPlayback
        self.ignored = ignored
        self.failed = failed
        self.deactivationFailed = deactivationFailed
        self.sessionActiveAfter = sessionActiveAfter
    }
}

/// 按 `AudioTransition.plan` 排好的步骤有序执行（§3.3）：先静音旧路，再 `setCategory`、`setActive`，
/// 两步都成功之后才给目标路取消静音。想出声时任一步失败：不再往下走，全部保持静音。
public enum AudioTransitionRunner {
    public static func run(
        _ plan: AudioTransitionPlan,
        lanes: [PlaybackLaneRole],
        driver: AudioSessionDriving,
        sink: AudioLaneControlling,
        log: (String) -> Void = { _ in }
    ) -> AudioOutcome {
        for role in plan.detachAudio {
            sink.setMuted(true, lane: role)
        }
        var categoryOK = true
        var activeOK = true
        var deactivationFailed = false
        var sessionActiveAfter: Bool?
        var index = 0
        steps: while index < plan.steps.count {
            let step = plan.steps[index]
            index += 1
            switch step {
            case .muteLane(let role):
                sink.setMuted(true, lane: role)
                log("audio step mute lane=\(label(role))")
            case .muteAllLanes:
                for role in lanes { sink.setMuted(true, lane: role) }
                log("audio step mute all")
            case .setCategory(let category, let mode):
                // 紧跟的选项步骤并进同一次 setCategory，免得中间闪过一次不混音的会话。
                var options: AudioSessionOptions = []
                if index < plan.steps.count, case .setSessionOptions(let next) = plan.steps[index] {
                    options = next
                    index += 1
                }
                do {
                    try driver.setCategory(category, mode: mode, options: options)
                    log("audio step category=\(category) mix=\(options.contains(.mixWithOthers))")
                } catch {
                    categoryOK = false
                    log("audio step category failed")
                    if plan.attemptsAudible { break steps }
                }
            case .setSessionOptions(let options):
                // 单独出现（前面不是 setCategory）时没有类别可带，保持原样记一笔。
                log("audio step options orphan mix=\(options.contains(.mixWithOthers))")
            case .setActive(let active, let notify):
                do {
                    try driver.setActive(active, notifyOthers: notify)
                    sessionActiveAfter = active
                    log("audio step active=\(active) notify=\(notify)")
                } catch {
                    activeOK = false
                    if !active { deactivationFailed = true }
                    log("audio step active failed")
                    if plan.attemptsAudible { break steps }
                }
            case .unmuteLane(let role):
                sink.setMuted(false, lane: role)
                log("audio step unmute lane=\(label(role))")
            }
        }
        // 想出声但失败：去掉取消静音。步骤在 unmute 之前就中断了，这里再把全部路压回静音，保险。
        let final = AudioTransition.finished(plan, categorySucceeded: categoryOK, activationSucceeded: activeOK)
        let failed = plan.attemptsAudible && !final.attemptsAudible
        if failed {
            for role in lanes { sink.setMuted(true, lane: role) }
            log("audio failed stay muted")
        }
        return AudioOutcome(
            mutedAfter: final.preservesAudibleLane ? nil : (final.audibleLane == nil),
            audibleLane: final.audibleLane,
            writesSettings: final.writesSettings,
            notice: final.notice,
            pausesPlayback: final.pausesPlayback,
            ignored: final.ignored,
            failed: failed,
            deactivationFailed: deactivationFailed,
            sessionActiveAfter: sessionActiveAfter
        )
    }

    private static func label(_ role: PlaybackLaneRole) -> String {
        role == .primary ? "primary" : "secondary"
    }
}
