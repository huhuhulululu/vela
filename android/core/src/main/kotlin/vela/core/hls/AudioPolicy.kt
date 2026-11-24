package vela.core.hls

/** 静音与音量的默认值。没有存过 `muted` 时视为静音。 */
object AudioPolicy {
    const val restoreVolume: Double = 0.2

    fun initialMuted(stored: Boolean?): Boolean = stored ?: true

    /** 当前音量大于 0 就保持，否则回到 [restoreVolume]。 */
    fun volumeOnUnmute(current: Double): Double = if (current > 0.0) current else restoreVolume
}

/**
 * 静音时关掉音频轨，而不是只把 `isMuted` 打上。
 * Swift 在播放器不允许空选择时只能留着音轨、靠静音标志。安卓可以禁用音轨，
 * 所以静音一律清空当前选择，并停掉音频清单的后台刷新，不再看 `allowsEmptySelection`。
 * 参数仍留着，调用形状和 Swift 一样。
 */
object MuteAudioPolicy {
    data class AudibleOption(val isDefault: Boolean)

    sealed class AudibleChoice {
        data object Leave : AudibleChoice()
        data object Clear : AudibleChoice()
        data class Pick(val index: Int) : AudibleChoice()
    }

    /** 播放器读到的可听组快照。`loaded == false` 还不能据此报「没有声音」。 */
    data class AudibleGroupState(
        val loaded: Boolean,
        val optionCount: Int,
        val selectedIndex: Int?,
        val allowsEmptySelection: Boolean,
    ) {
        companion object {
            val unknown = AudibleGroupState(
                loaded = false,
                optionCount = 0,
                selectedIndex = null,
                allowsEmptySelection = false,
            )
        }
    }

    sealed class AudioReport {
        data class Ok(val enabled: Int, val held: Boolean) : AudioReport()
        data object Recheck : AudioReport()
        data object Missing : AudioReport()
    }

    /**
     * 静音：有选中项就清空（关音轨），已经是空的就不动。
     * 未静音：没有选中项就选默认（或第一个）；已经选中就不动。
     */
    fun audibleChoice(
        muted: Boolean,
        @Suppress("UNUSED_PARAMETER") allowsEmptySelection: Boolean,
        options: List<AudibleOption>,
        selectedIndex: Int?,
    ): AudibleChoice {
        if (options.isEmpty()) return AudibleChoice.Leave
        // 静音即关音轨。allowsEmptySelection 只在 AVPlayer 上会挡住 clear，这里不看它。
        if (muted) return if (selectedIndex == null) AudibleChoice.Leave else AudibleChoice.Clear
        return if (selectedIndex == null) AudibleChoice.Pick(preferredIndex(options)) else AudibleChoice.Leave
    }

    /** 静音就停掉音频清单的后台刷新。未静音继续刷。不再因为不允许空选择而继续刷。 */
    fun shouldSuppressAudioFetch(
        muted: Boolean,
        @Suppress("UNUSED_PARAMETER") allowsEmptySelection: Boolean?,
    ): Boolean = muted

    /** 播放器来要 audio.m3u8 的按需取不归这里管。 */
    fun shouldRefreshAudioPlaylist(
        hasSeparateAudio: Boolean,
        relayRefreshing: Boolean,
        suppressAudioFetch: Boolean,
    ): Boolean = hasSeparateAudio && relayRefreshing && !suppressAudioFetch

    /** 音轨刚才被拿掉、现在又要出声、而且正在播，才 seek 到直播边缘。 */
    fun shouldSeekLiveEdge(audioWasDetached: Boolean, playing: Boolean): Boolean =
        audioWasDetached && playing

    /**
     * 启用轨数大于 0 照旧。静音且已经确认「有可选项但故意没选」记为 held，
     * 不要求允许空选择：关音轨之后选中下标就是空的。
     */
    fun audioReport(
        muted: Boolean,
        isRecheck: Boolean,
        enabledTrackCount: Int,
        group: AudibleGroupState,
    ): AudioReport {
        if (enabledTrackCount > 0) return AudioReport.Ok(enabled = enabledTrackCount, held = false)
        if (muted && group.loaded && group.optionCount > 0 && group.selectedIndex == null) {
            return AudioReport.Ok(enabled = group.optionCount, held = true)
        }
        return when (PlaybackPolicy.audioCheckStep(enabledTracks = 0, isRecheck = isRecheck)) {
            PlaybackPolicy.AudioCheckStep.OK -> AudioReport.Ok(enabled = 0, held = false)
            PlaybackPolicy.AudioCheckStep.RECHECK -> AudioReport.Recheck
            PlaybackPolicy.AudioCheckStep.FAIL -> AudioReport.Missing
        }
    }

    private fun preferredIndex(options: List<AudibleOption>): Int {
        val index = options.indexOfFirst { it.isDefault }
        return if (index < 0) 0 else index
    }
}

/** 用错误码归类「网络不通」，不依赖具体平台异常类型。 */
object NetworkFailure {
    const val message: String = "网络不通，稍后再试"

    private val offlineCodes = setOf(
        UrlErrors.NOT_CONNECTED_TO_INTERNET,
        UrlErrors.NETWORK_CONNECTION_LOST,
        UrlErrors.TIMED_OUT,
        UrlErrors.CANNOT_FIND_HOST,
        UrlErrors.CANNOT_CONNECT_TO_HOST,
        UrlErrors.DNS_LOOKUP_FAILED,
        UrlErrors.DATA_NOT_ALLOWED,
        UrlErrors.INTERNATIONAL_ROAMING_OFF,
    )

    fun isOffline(code: Int): Boolean = code in offlineCodes

    fun isOffline(error: Throwable): Boolean {
        val transport = error as? TransportError ?: return false
        return transport.domain == UrlErrors.DOMAIN && isOffline(transport.code)
    }

    /** 明确没有网络连接。超时仍可能是线路问题，不算断开。 */
    fun isDisconnected(error: Throwable): Boolean {
        val transport = error as? TransportError ?: return false
        if (transport.domain != UrlErrors.DOMAIN) return false
        return transport.code == UrlErrors.NOT_CONNECTED_TO_INTERNET ||
            transport.code == UrlErrors.DATA_NOT_ALLOWED ||
            transport.code == UrlErrors.INTERNATIONAL_ROAMING_OFF
    }
}
