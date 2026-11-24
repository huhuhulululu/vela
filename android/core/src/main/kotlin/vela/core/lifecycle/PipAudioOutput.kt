package vela.core.lifecycle

/**
 * 画中画（以及此刻画面）的音频意图，与平台无关。A2 的 `AudioFocusPlan` 只消费它，本包不请求焦点。
 *
 * 对应 iOS `AudioSessionPolicy.configuration` 加 `SessionActivation`，不搬类别和模式：
 * - [holdsFocus]：要申请音频焦点。仅「会话保持活跃且未静音」为 true。静音永远不拿独占焦点，包括静音画中画。
 * - [mixWithOthers]：静音为 true，不打断别人。出声为 false。
 * - [releaseNotifyingOthers]：会话要停用时为 true，放弃焦点并通知对方恢复。
 *   静音画中画为 false：不走放弃，只保持混音。这对应 iOS 画中画再静音不 `setActive(false)`。
 *
 * 三个量里 [holdsFocus] 与 [releaseNotifyingOthers] 不会同时为 true。
 */
data class PipAudioOutput(
    val holdsFocus: Boolean,
    val mixWithOthers: Boolean,
    val releaseNotifyingOthers: Boolean,
) {
    companion object {
        /** 由「是否静音」和「会话是否保持活跃」推出意图。 */
        fun of(muted: Boolean, sessionActive: Boolean): PipAudioOutput = PipAudioOutput(
            holdsFocus = sessionActive && !muted,
            mixWithOthers = muted,
            releaseNotifyingOthers = !sessionActive,
        )

        /**
         * 再静音时，若调用方认定画中画仍在播，就保持会话，只改成混音。
         * 对应 `AudioSessionPolicy.keepsSessionActiveWhenMuting`，原样转发这个布尔。
         * 与 [PiPPolicy.Decision.keepsSessionActiveOnMute] 不是同一个量：后者把「即将自动进入」也算上。
         */
        fun keepsSessionWhenMuting(pictureInPicturePlaying: Boolean): Boolean = pictureInPicturePlaying
    }
}
