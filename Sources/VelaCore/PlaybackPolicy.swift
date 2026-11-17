import Foundation

/// 播放相关的纯判定：谁说了算（门控）、暂停多久后怎么恢复、起播看门狗何时该报、停留何时计时。
public enum PlaybackPolicy {
    /// 起播后多久还没出画面就报「迟迟没有开始」。
    public static let startupTimeout: TimeInterval = 16
    /// 暂停超过这么久，直播的旧画面已不可用，恢复时要重建播放项回到直播点；不超过则直接 `play()` 续播
    /// （播放项开着 `automaticallyPreservesTimeOffsetFromLive`，续播会自动贴回直播偏移，不必手动 seek）。
    /// 依据：子列表地址闲置约 35 秒才过期（无绝对寿命），15 秒内中继一恢复刷新就能接上，
    /// 整项重建反而要多等约 2 到 3 秒（m5p 实测暂停 5 到 22 秒都走重建，恢复慢）。
    public static let liveRejoinAfter: TimeInterval = 15
    /// 暂停超过这么久，流地址都可能过期，恢复时要重新解析。暂停期间中继不刷新，子列表地址按闲置计，
    /// 实测约 32 到 35 秒起 403，所以取 30 秒（中继另有 403 续期兜底）。
    public static let reresolveAfter: TimeInterval = 30
    /// 停播且窗口不可见达到这一时长才释放播放项，并让中继丢掉该源的状态。与 `reresolveAfter` 相同：
    /// 更早释放会拆掉 15 秒内续播、未满 30 秒重建都还要用的播放项。恢复时不再读旧项。
    /// 窗口可见时不释放（用户手动暂停要保留最后一帧，释放会黑屏）。
    public static let releasePausedItemAfter: TimeInterval = reresolveAfter
    /// 已开播的播放项持续等待（未播放）超过这么久，当作卡死。依据：前向缓冲目标 6 秒、
    /// 直播偏移 3 秒，正常的重新缓冲与中继 503 触发的失败都在几秒内有结果；取 10 秒避开这些，
    /// 又明显短于起播看门狗（16 秒）。
    public static let stuckAfter: TimeInterval = 10

    /// 唯一的「允许播放」门控：窗口可见、没手动暂停、没被老板键隐藏、系统不处于休眠或锁屏。
    /// 起播、重试、换档、恢复都以它为准，不各自再判。
    public static func allowed(windowVisible: Bool, manuallyPaused: Bool, bossHidden: Bool, systemInactive: Bool) -> Bool {
        windowVisible && !manuallyPaused && !bossHidden && !systemInactive
    }

    public enum ResumeAction: Equatable, Sendable {
        /// 暂停很短：直接继续。
        case resume
        /// 中等时长：地址仍有效，重建播放项回到直播点。
        case rejoin
        /// 太久：流地址可能已过期，重新解析当前房间。
        case reresolve
    }

    public static func resumeAction(pausedFor: TimeInterval) -> ResumeAction {
        if pausedFor <= liveRejoinAfter { return .resume }
        return pausedFor < reresolveAfter ? .rejoin : .reresolve
    }

    /// 窗口是否不可见（用户看不到画面）：被遮挡或最小化、老板键隐藏、系统锁屏休眠、App 被隐藏，任一成立。
    public static func windowInvisible(windowVisible: Bool, bossHidden: Bool, systemInactive: Bool, appHidden: Bool) -> Bool {
        !windowVisible || bossHidden || systemInactive || appHidden
    }

    /// 停播与窗口不可见都满 `releasePausedItemAfter`（刚好等于也算）才释放播放项与中继源。
    /// `hiddenFor` 为窗口已连续不可见的时长，nil 表示窗口可见：永不释放。
    /// 手动暂停期间窗口才变不可见时，`hiddenFor` 从变为不可见起算，所以取两者较小值。
    public static func shouldReleasePausedItem(pausedFor: TimeInterval, hiddenFor: TimeInterval?) -> Bool {
        guard let hiddenFor else { return false }
        return min(pausedFor, hiddenFor) >= releasePausedItemAfter
    }

    /// 起播看门狗到点时是否该报失败：仍是同一个播放项、此刻应当播放、这个项从未真正开播且现在也没在播。
    /// 「保留上一帧画面」（readyUsername）与「这个项开过播」是两回事，换档的新项卡住也要报。
    /// 正在重新解析地址（待刷新）时旧项不算卡住，由刷新结果报告。
    public static func startupStalled(sameItem: Bool, allowed: Bool, itemPlayed: Bool, isPlaying: Bool, refreshing: Bool = false) -> Bool {
        sameItem && allowed && !itemPlayed && !isPlaying && !refreshing
    }

    /// 起播后首次检查音轨的延迟（HLS 的 `item.tracks` 刚开播时可能还没填好）。
    public static let audioCheckDelay: TimeInterval = 2
    /// 首次检查没有可听音轨时，再等这么久复查；复查仍没有才算错误（2 秒那次可能只是检测过早）。
    public static let audioRecheckDelay: TimeInterval = 3

    public enum AudioCheckStep: Equatable, Sendable {
        /// 有可听音轨：记 info。
        case ok
        /// 首次没有可听音轨：不记错，等 `audioRecheckDelay` 后复查。
        case recheck
        /// 复查仍没有：记 error。
        case fail
    }

    /// 音轨检测判定：`enabledTracks` 为已启用的音频轨数；`isRecheck` 表示这是复查。
    public static func audioCheckStep(enabledTracks: Int, isRecheck: Bool) -> AudioCheckStep {
        if enabledTracks > 0 { return .ok }
        return isRecheck ? .fail : .recheck
    }

    public enum StuckAction: Equatable, Sendable {
        case ignore
        /// 看似卡住但仍有进展（缓冲在涨或续期进行中）：重新计时，不消耗重试。
        case rearm
        /// 走「重新解析并重建」恢复（占用该房间唯一一次自动重试）。
        case recover
        /// 该恢复但预算已用完：不再循环，交给界面显示失败与重试。
        case giveUp
    }

    /// 已开播后的卡住看门狗到点时怎么办：仍是同一个项、此刻应当播放、这个项开过播、现在没在播、
    /// 没在等新地址才算卡住；此时缓冲仍在增长（`bufferAdvancing`）或续期进行中（`renewing`）就重新计时；
    /// 真的不动时有预算就恢复，没预算就放弃（不循环）。
    public static func stuckAction(
        sameItem: Bool, allowed: Bool, itemPlayed: Bool, isPlaying: Bool, refreshing: Bool, retryAvailable: Bool,
        bufferAdvancing: Bool = false, renewing: Bool = false
    ) -> StuckAction {
        guard sameItem, allowed, itemPlayed, !isPlaying, !refreshing else { return .ignore }
        if bufferAdvancing || renewing { return .rearm }
        return retryAvailable ? .recover : .giveUp
    }

    /// 已加载时长的末端至少前进这么多秒才算「缓冲在涨」（滤掉浮点抖动）。
    public static let bufferAdvanceEpsilon: TimeInterval = 0.1

    /// 缓冲是否在增长：本次检查时已加载区间的末端（秒）比上次计时开始时前进了。上次未知（nil）不算增长。
    public static func bufferAdvanced(previousEnd: TimeInterval?, currentEnd: TimeInterval?) -> Bool {
        guard let previousEnd, let currentEnd else { return false }
        return currentEnd - previousEnd >= bufferAdvanceEpsilon
    }

    /// 中继是否该在后台刷新当前源：该播放，且没有处于终态失败（看门狗放弃、播放项失败、失败界面）。
    /// 失败后不再无限拉流；用户点重试或切房会重新起播，那时 `failed` 已清。
    public static func relayShouldRefresh(allowed: Bool, failed: Bool) -> Bool {
        allowed && !failed
    }

    /// 停留计时只在「允许播放，且当前房间对应的播放项此刻真的在播」时走。
    /// 解析中、缓冲中、起播超时、播放失败都不算。
    public static func dwellActive(allowed: Bool, playing: String?, current: String?) -> Bool {
        guard allowed, let playing, let current else { return false }
        return playing.lowercased() == current.lowercased()
    }
}

/// 侧车网页视图空闲多久销毁。故意不跟播放的重新解析窗口绑在一起。
/// 短于 403 / 质询冷却（120 秒、600 秒）：冷却还没结束时，空闲一到就拆掉视图，下次用再重建。
/// 通关 cookie 在默认 `WKWebsiteDataStore` 里，不随视图销毁。
public enum SidecarIdlePolicy {
    public static let seconds: TimeInterval = 30

    /// 空闲时长达到 `seconds`（刚好等于也算）时销毁。
    public static func shouldTearDown(idleFor: TimeInterval) -> Bool {
        idleFor >= seconds
    }
}

/// 预取缓存：保存已选好档位的视频子列表与独立音轨子列表地址（`StreamRendition`）。主列表 30 秒就 403，不存。
/// 子列表地址带 `session=`，按「闲置」过期：2026-11 在 m5p 上实测，持续每 10 秒请求 6 分钟全 200（没有绝对寿命），
/// 闲置 32 秒仍 200、35 到 38 秒起 403（取档时的主列表请求起算）。预取的地址在切房前没人请求，
/// 所以有效期取 20 秒，明显短于最低实测值，过期就重新解析；不去「保活」预取地址（多一路定时请求）。
/// 档位上限不同的条目不能复用。万一用到时仍 403，由中继续期（`RenewalPolicy`）。
public struct PreparedCache: Sendable {
    public static let maxAge: TimeInterval = 20

    private var entries: [String: (rendition: StreamRendition, cap: Double, at: Date)] = [:]

    public init() {}

    public mutating func set(_ rendition: StreamRendition, cap: Double, for username: String, now: Date) {
        entries = entries.filter { now.timeIntervalSince($0.value.at) < Self.maxAge }
        entries[username] = (rendition, cap, now)
    }

    public func fresh(_ username: String, cap: Double, now: Date) -> StreamRendition? {
        guard let entry = entries[username], entry.cap == cap,
              now.timeIntervalSince(entry.at) < Self.maxAge else { return nil }
        return entry.rendition
    }

    public mutating func remove(_ username: String) {
        entries.removeValue(forKey: username)
    }
}
