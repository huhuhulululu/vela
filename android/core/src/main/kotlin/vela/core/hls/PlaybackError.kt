package vela.core.hls

/** 播放失败归类。系统原文只进日志，界面用这里的中文。 */
enum class PlaybackErrorKind {
    OFFLINE,
    TIMEOUT,
    UNAVAILABLE,
    OTHER,
}

/**
 * 按错误链归类。链从外到内，是错误本身和各层 underlying 的域与码。
 * 数字仍是 Swift 的 NSURL / AVFoundation / CoreMedia 码，和测试逐条对应。
 * Media3 的 `PlaybackException.errorCode` 不在这里换算，见 `hls.notes.md`。
 */
object PlaybackErrorCopy {
    private val offlineCodes = setOf(-1009, -1005, -1004, -1003, -1006, -1018, -1020)
    private val timeoutCodes = setOf(-1001)
    private val unavailableCodes = setOf(
        -11828, -11850, -11800, -11819,
        -12660, -12661, -12662, -12667,
        -12938, -12939,
        -12888, -12885, -12883,
    )

    fun kind(chain: List<FailureCode>): PlaybackErrorKind {
        for (entry in chain) {
            if (entry.domain != UrlErrors.DOMAIN) continue
            if (entry.code in timeoutCodes) return PlaybackErrorKind.TIMEOUT
            if (entry.code in offlineCodes) return PlaybackErrorKind.OFFLINE
        }
        for (entry in chain) {
            if (entry.code in unavailableCodes) return PlaybackErrorKind.UNAVAILABLE
        }
        return PlaybackErrorKind.OTHER
    }

    fun message(kind: PlaybackErrorKind): String = when (kind) {
        PlaybackErrorKind.OFFLINE -> "网络连接中断了，请检查网络后重试"
        PlaybackErrorKind.TIMEOUT -> "连接超时，这路画面暂时没有响应"
        PlaybackErrorKind.UNAVAILABLE -> "这路画面暂时无法播放，可能已下播"
        PlaybackErrorKind.OTHER -> "播放失败，请重试或换一个房间"
    }

    /** 起播迟迟没有开始：网络类原因用对应文案，否则说「迟迟没有开始」。 */
    fun startTimeoutMessage(chain: List<FailureCode>): String = when (val resolved = kind(chain)) {
        PlaybackErrorKind.OFFLINE, PlaybackErrorKind.TIMEOUT -> message(resolved)
        PlaybackErrorKind.UNAVAILABLE, PlaybackErrorKind.OTHER -> "这路画面迟迟没有开始，请重试或换一个房间"
    }

    fun failureMessage(chain: List<FailureCode>): String = message(kind(chain))
}
