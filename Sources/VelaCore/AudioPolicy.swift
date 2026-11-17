import Foundation

/// 静音与音量的默认值与恢复规则。
public enum AudioPolicy {
    /// 从静音恢复时音量若是 0，回到这个较小的非零值（0 音量下取消静音等于没有声音）。
    public static let restoreVolume = 0.2

    /// 首次启动（还没有存过 `muted`）默认静音：置顶浮在工作窗口上的播放器不该一打开就出声。
    /// 存过的值（用户显式设过）原样保留。
    public static func initialMuted(stored: Bool?) -> Bool { stored ?? true }

    /// 取消静音时应使用的音量：当前音量为 0（或更小）回到 `restoreVolume`，否则不变。
    public static func volumeOnUnmute(current: Double) -> Double {
        current > 0 ? current : restoreVolume
    }
}

/// 静音时要不要把独立音轨从播放里拿掉，以及中继还要不要刷新音频清单。
///
/// 本地主列表始终保留 `EXT-X-MEDIA` 音轨。曾经把音轨从主列表拿掉之后，取消静音就再也没有声音。
/// 这里只决定「选不选」：组允许空选择才 `select(nil)`，AVPlayer 不再请求 audio.m3u8 和音频分片；
/// 不允许就保持选中，静音只靠 `isMuted`，不重载播放器。
public enum MuteAudioPolicy {
    public struct AudibleOption: Equatable, Sendable {
        public var isDefault: Bool

        public init(isDefault: Bool) { self.isDefault = isDefault }
    }

    public enum AudibleChoice: Equatable, Sendable {
        /// 不改当前选择。
        case leave
        /// `select(nil, in:)`。只有允许空选择才会返回这个。
        case clear
        /// 选中 `options` 的这个下标（默认项优先，否则第一个）。
        case pick(Int)
    }

    /// 播放器读到的可听组快照。`loaded == false` 表示还不知道有没有音轨，不能据此报「没有声音」。
    public struct AudibleGroupState: Equatable, Sendable {
        public var loaded: Bool
        public var optionCount: Int
        public var selectedIndex: Int?
        public var allowsEmptySelection: Bool

        public init(loaded: Bool, optionCount: Int, selectedIndex: Int?, allowsEmptySelection: Bool) {
            self.loaded = loaded
            self.optionCount = optionCount
            self.selectedIndex = selectedIndex
            self.allowsEmptySelection = allowsEmptySelection
        }

        public static let unknown = AudibleGroupState(
            loaded: false, optionCount: 0, selectedIndex: nil, allowsEmptySelection: false
        )
    }

    public enum AudioReport: Equatable, Sendable {
        /// `held`：静音主动没选音轨，但可选项还在。`enabled` 用选项个数，
        /// 「开始播放」日志仍是 `audio tracks=N enabled=N`，后面带 `held`。
        case ok(enabled: Int, held: Bool)
        case recheck
        case missing
    }

    /// 静音且允许空选择：有选中项就清空，已经是空的就不动。
    /// 静音但不允许空选择：必须留着一个选项（没有就选默认），否则播放器可能不起播，取消静音也没声。
    /// 未静音：没有选中项就选默认（或第一个）；已经选中就不动，避免重复 `select` 引起重缓冲。
    public static func audibleChoice(
        muted: Bool,
        allowsEmptySelection: Bool,
        options: [AudibleOption],
        selectedIndex: Int?
    ) -> AudibleChoice {
        guard !options.isEmpty else { return .leave }
        if muted, allowsEmptySelection {
            return selectedIndex == nil ? .leave : .clear
        }
        // 未静音，或静音但不允许空选择：没有选中项就补上默认项。已选中则不动。
        return selectedIndex == nil ? .pick(preferredIndex(in: options)) : .leave
    }

    /// 还不知道允不允许空选择（nil）时，静音先停掉音频清单刷新。确认不允许后必须继续刷新。
    public static func shouldSuppressAudioFetch(muted: Bool, allowsEmptySelection: Bool?) -> Bool {
        muted && allowsEmptySelection != false
    }

    /// 中继要不要在后台刷新独立音轨清单。播放器来要 audio.m3u8 时的按需取不归这里管。
    public static func shouldRefreshAudioPlaylist(
        hasSeparateAudio: Bool, relayRefreshing: Bool, suppressAudioFetch: Bool
    ) -> Bool {
        hasSeparateAudio && relayRefreshing && !suppressAudioFetch
    }

    /// 音轨刚才被拿掉、现在又要出声、而且正在播：seek 到直播边缘，让新音轨对齐当前直播点。
    /// 音轨一直选着（只靠 isMuted），或人在暂停，都不 seek，画面不动。
    public static func shouldSeekLiveEdge(audioWasDetached: Bool, playing: Bool) -> Bool {
        audioWasDetached && playing
    }

    /// 音轨日志怎么记。启用轨数大于 0 照旧。静音且已经确认「有可选项但故意没选」记为 held，
    /// 不报「这路没有声音」。组还没读到，或轨还没填上，沿用 `PlaybackPolicy.audioCheckStep` 的复查。
    public static func audioReport(
        muted: Bool, isRecheck: Bool, enabledTrackCount: Int, group: AudibleGroupState
    ) -> AudioReport {
        if enabledTrackCount > 0 { return .ok(enabled: enabledTrackCount, held: false) }
        if muted, group.loaded, group.allowsEmptySelection, group.optionCount > 0, group.selectedIndex == nil {
            return .ok(enabled: group.optionCount, held: true)
        }
        switch PlaybackPolicy.audioCheckStep(enabledTracks: 0, isRecheck: isRecheck) {
        case .ok:
            return .ok(enabled: 0, held: false)
        case .recheck:
            return .recheck
        case .fail:
            return .missing
        }
    }

    private static func preferredIndex(in options: [AudibleOption]) -> Int {
        options.firstIndex(where: \.isDefault) ?? 0
    }
}

/// 把底层网络错误码归类成「网络不通」。用错误码而不是错误对象，便于测试，也不依赖具体错误类型。
public enum NetworkFailure {
    public static func isOffline(code: Int) -> Bool {
        let codes: Set<Int> = [
            NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost, NSURLErrorTimedOut,
            NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost, NSURLErrorDNSLookupFailed,
            NSURLErrorDataNotAllowed, NSURLErrorInternationalRoamingOff
        ]
        return codes.contains(code)
    }

    /// 明确没有网络连接（不是线路抖动或超时）：此时换侧车重试没有意义，也不该拉长侧车冷却。
    public static func isDisconnected(_ error: Error) -> Bool {
        let ns = error as NSError
        guard ns.domain == NSURLErrorDomain else { return false }
        return [NSURLErrorNotConnectedToInternet, NSURLErrorDataNotAllowed, NSURLErrorInternationalRoamingOff].contains(ns.code)
    }

    /// 错误对象的版本：只认 URL 错误域。
    public static func isOffline(_ error: Error) -> Bool {
        let ns = error as NSError
        return ns.domain == NSURLErrorDomain && isOffline(code: ns.code)
    }

    public static let message = "网络不通，稍后再试"
}
