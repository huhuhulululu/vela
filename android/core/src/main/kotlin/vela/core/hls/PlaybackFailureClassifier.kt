package vela.core.hls

/**
 * 把播放失败分成房间重试、双窗资源降级，或不可恢复。
 *
 * 资源降级只发生在双窗副路，且错误链命中 Media3 白名单。
 * 白名单是解码器初始化失败和
 * 解码资源被回收，对应 media3 1.11.1 `PlaybackException` 的 4001 与 4006。
 * 损坏码流、解码失败、找不到解码器仍是房间重试。
 * 单窗和主路永远不降级。
 *
 * 调用方看到 `PlaybackException`（含 `ExoPlaybackException`）时，域填 [media3ErrorDomain]，
 * 码填 `errorCode`。不要填子类的类名，否则对不上白名单。
 */
object PlaybackFailureClassifier {
    enum class Outcome {
        ROOM_RETRY,
        RESOURCE_DEGRADE,
        /** 目前没有这样的码。白名单以外一律按房间重试，这个值先留着。 */
        FATAL,
    }

    class Context(
        var isDualWindow: Boolean,
        var isSecondary: Boolean,
        var otherIsPlaying: Boolean,
        var hasPlayed: Boolean,
    )

    const val media3ErrorDomain: String = "androidx.media3.common.PlaybackException"

    /** `PlaybackException.ERROR_CODE_DECODER_INIT_FAILED`。 */
    const val decoderInitFailedCode: Int = 4001

    /** `PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED`。 */
    const val decodingResourcesReclaimedCode: Int = 4006

    /** `PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED`。故意不进白名单。 */
    const val decoderQueryFailedCode: Int = 4002

    /** `PlaybackException.ERROR_CODE_DECODING_FAILED`。码流解不开，按房间失败。 */
    const val decodingFailedCode: Int = 4003

    /**
     * Swift 的 `AVErrorDecoderTemporarilyUnavailable`（-11839）不再进白名单。
     * 留着常数是为了测试能锁住「旧码不算资源降级」。
     */
    const val legacyAvFoundationErrorDomain: String = "AVFoundationErrorDomain"
    const val legacyDecoderTemporarilyUnavailableCode: Int = -11839

    val resourceDegradeWhitelist: List<FailureCode> = listOf(
        FailureCode(media3ErrorDomain, decoderInitFailedCode),
        FailureCode(media3ErrorDomain, decodingResourcesReclaimedCode),
    )

    /** 错误链任一环命中白名单，且现场是双窗副路，才降级。 */
    fun classify(chain: List<FailureCode>, context: Context): Outcome {
        val whitelisted = chain.any { entry ->
            resourceDegradeWhitelist.any { item ->
                item.domain == entry.domain && item.code == entry.code
            }
        }
        if (whitelisted && context.isDualWindow && context.isSecondary) {
            return Outcome.RESOURCE_DEGRADE
        }
        return Outcome.ROOM_RETRY
    }
}
