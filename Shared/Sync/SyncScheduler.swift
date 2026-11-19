import Foundation
#if SWIFT_PACKAGE
import VelaCore
#endif

/// 触发时机与退避（docs/plans/sync.md 第 11.3 节）：没有轮询与常驻连接，只有离散的触发点。
struct SyncScheduler: Equatable {
    enum Trigger: Equatable {
        /// 启动、iOS 回到前台：一个周期。
        case launch, foreground
        /// macOS 应用激活：与面板同一口径（距上次联系服务端超过 60 秒），免得每次点回窗口都请求。
        case activate
        /// 打开收藏面板、口味面板、同步设置页：距上次联系服务端超过 60 秒才跑。
        case panel
        /// 本地操作去抖之后。
        case localEdit
        /// 设置页「立即同步」：不受普通退避限制，仍遵守 429 与 507。
        case manual
    }

    static let debounceMillis: UInt64 = 5_000
    static let minPutGapMillis: UInt64 = 10_000
    static let panelGapMillis: UInt64 = 60_000
    static let backoffBaseMillis: UInt64 = 30_000
    static let backoffMaxMillis: UInt64 = 3_600_000

    private(set) var lastContactAt: UInt64?
    private(set) var lastPutAt: UInt64?
    /// 普通失败退避的截止时刻。
    private(set) var backoffUntil: UInt64 = 0
    /// 429 与 507 的截止时刻：连「立即同步」也要等。
    private(set) var hardUntil: UInt64 = 0
    private(set) var failures = 0

    /// 饱和加法：截止时刻到顶就停在顶，不溢出。
    static func later(_ now: UInt64, _ delta: UInt64) -> UInt64 {
        let (sum, overflow) = now.addingReportingOverflow(delta)
        return overflow ? .max : sum
    }

    func allows(_ trigger: Trigger, now: UInt64) -> Bool {
        guard now >= hardUntil else { return false }
        if trigger == .manual { return true }
        guard now >= backoffUntil else { return false }
        switch trigger {
        case .panel, .activate:
            guard let last = lastContactAt else { return true }
            return now > Self.later(last, Self.panelGapMillis)
        default:
            return true
        }
    }

    /// 启动恢复因钥匙串暂不可用或网络暂时故障没完成时，哪些离散触发要重新跑一次（不轮询）。
    static func retriesStartup(unavailable: Bool, on trigger: Trigger) -> Bool {
        guard unavailable else { return false }
        switch trigger {
        case .foreground, .activate, .panel, .manual, .launch: return true
        case .localEdit: return false
        }
    }

    /// 进后台或退出：有未推送的改动才尽力推一次。
    func allowsBackground(gen: UInt64, pushedGen: UInt64, now: UInt64) -> Bool {
        gen > pushedGen && now >= hardUntil
    }

    /// 本地操作之后等多久再跑：去抖 5 秒，且距上次 PUT 至少 10 秒。
    func editDelay(now: UInt64) -> UInt64 {
        var delay = Self.debounceMillis
        if let last = lastPutAt, Self.later(last, Self.minPutGapMillis) > now { delay = max(delay, Self.later(last, Self.minPutGapMillis) - now) }
        return delay
    }

    mutating func record(_ result: SyncCycleResult, now: UInt64) {
        switch result {
        case .pushed, .created:
            lastPutAt = now
            fallthrough
        case .upToDate:
            lastContactAt = now
            failures = 0
            backoffUntil = 0
        case .transient, .conflictLimit, .decryptFailed, .storageFailed:
            failures += 1
            let step = Self.backoffBaseMillis << UInt64(min(failures - 1, 20))
            backoffUntil = Self.later(now, min(step, Self.backoffMaxMillis))
        case .rateLimited(let seconds):
            lastContactAt = now
            let clamped = UInt64(min(86_400, max(1, seconds)))
            hardUntil = Self.later(now, clamped * 1000)
        case .quotaFull:
            lastContactAt = now
            hardUntil = Self.later(now, Self.backoffMaxMillis)
        case .halted, .readOnly, .invalidRemote, .clockSkew, .oversize, .payloadTooLarge:
            lastContactAt = now
        case .cancelled:
            break
        }
    }
}
