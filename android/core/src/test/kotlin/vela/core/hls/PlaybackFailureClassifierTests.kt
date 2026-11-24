package vela.core.hls

import kotlin.test.Test
import kotlin.test.assertEquals

/** 播放失败分类。损坏码流按房间重试；只有双窗副路的解码器初始化失败或资源被回收才降级。 */
class PlaybackFailureClassifierTests {
    private fun chain(vararg pairs: Pair<String, Int>): List<FailureCode> =
        pairs.map { FailureCode(domain = it.first, code = it.second) }

    private fun secondaryDual(
        otherIsPlaying: Boolean = true,
        hasPlayed: Boolean = false,
    ): PlaybackFailureClassifier.Context = PlaybackFailureClassifier.Context(
        isDualWindow = true,
        isSecondary = true,
        otherIsPlaying = otherIsPlaying,
        hasPlayed = hasPlayed,
    )

    /** 解码失败和找不到解码器，在副路双窗下仍是房间失败。 */
    @Test
    fun testCorruptStreamIsRoomRetry() {
        val decodeFailed = chain(PlaybackFailureClassifier.media3ErrorDomain to PlaybackFailureClassifier.decodingFailedCode)
        val decoderNotFound = chain(PlaybackFailureClassifier.media3ErrorDomain to PlaybackFailureClassifier.decoderQueryFailedCode)
        val wrapped = chain(
            PlaybackFailureClassifier.media3ErrorDomain to 1000,
            PlaybackFailureClassifier.media3ErrorDomain to PlaybackFailureClassifier.decodingFailedCode,
        )
        for (sample in listOf(decodeFailed, decoderNotFound, wrapped)) {
            assertEquals(
                PlaybackFailureClassifier.Outcome.ROOM_RETRY,
                PlaybackFailureClassifier.classify(sample, secondaryDual()),
            )
            assertEquals(
                PlaybackFailureClassifier.Outcome.ROOM_RETRY,
                PlaybackFailureClassifier.classify(sample, secondaryDual(hasPlayed = true)),
            )
        }
    }

    /**
     * 副路、双窗，错误链里有解码器初始化失败（4001）或资源被回收（4006）才降级。
     * 包在外层错误里也认。Swift 的 -11839 已换掉，不再降级。
     */
    @Test
    fun testSecondaryDualWindowDecoderTemporarilyUnavailableIsResourceDegrade() {
        assertEquals(4001, PlaybackFailureClassifier.decoderInitFailedCode)
        assertEquals(4006, PlaybackFailureClassifier.decodingResourcesReclaimedCode)
        assertEquals("androidx.media3.common.PlaybackException", PlaybackFailureClassifier.media3ErrorDomain)
        val domain = PlaybackFailureClassifier.media3ErrorDomain
        val direct = chain(domain to PlaybackFailureClassifier.decoderInitFailedCode)
        val wrapped = chain(domain to 1000, domain to PlaybackFailureClassifier.decoderInitFailedCode)
        val reclaimed = chain(domain to PlaybackFailureClassifier.decodingResourcesReclaimedCode)
        for (sample in listOf(direct, wrapped, reclaimed)) {
            assertEquals(
                PlaybackFailureClassifier.Outcome.RESOURCE_DEGRADE,
                PlaybackFailureClassifier.classify(sample, secondaryDual()),
            )
            assertEquals(
                PlaybackFailureClassifier.Outcome.RESOURCE_DEGRADE,
                PlaybackFailureClassifier.classify(sample, secondaryDual(hasPlayed = true)),
            )
        }
        val foreignDomain = chain("OtherErrorDomain" to PlaybackFailureClassifier.decoderInitFailedCode)
        val decodingFailed = chain(domain to PlaybackFailureClassifier.decodingFailedCode)
        assertEquals(
            PlaybackFailureClassifier.Outcome.ROOM_RETRY,
            PlaybackFailureClassifier.classify(foreignDomain, secondaryDual()),
        )
        assertEquals(
            PlaybackFailureClassifier.Outcome.ROOM_RETRY,
            PlaybackFailureClassifier.classify(decodingFailed, secondaryDual()),
        )
        val legacy = chain(
            PlaybackFailureClassifier.legacyAvFoundationErrorDomain to
                PlaybackFailureClassifier.legacyDecoderTemporarilyUnavailableCode,
        )
        assertEquals(
            PlaybackFailureClassifier.Outcome.ROOM_RETRY,
            PlaybackFailureClassifier.classify(legacy, secondaryDual()),
        )
    }

    /** 主路撞上同一个初始化失败，仍是房间重试。 */
    @Test
    fun testPrimaryDecoderTemporarilyUnavailableIsRoomRetry() {
        val sample = chain(
            PlaybackFailureClassifier.media3ErrorDomain to PlaybackFailureClassifier.decoderInitFailedCode,
        )
        val primary = PlaybackFailureClassifier.Context(
            isDualWindow = true,
            isSecondary = false,
            otherIsPlaying = true,
            hasPlayed = false,
        )
        assertEquals(PlaybackFailureClassifier.Outcome.ROOM_RETRY, PlaybackFailureClassifier.classify(sample, primary))
        primary.hasPlayed = true
        assertEquals(PlaybackFailureClassifier.Outcome.ROOM_RETRY, PlaybackFailureClassifier.classify(sample, primary))
    }

    /** 单窗永不判资源降级，哪怕标志被写成副路。 */
    @Test
    fun testSingleWindowDecoderTemporarilyUnavailableIsRoomRetry() {
        val sample = chain(
            PlaybackFailureClassifier.media3ErrorDomain to PlaybackFailureClassifier.decodingResourcesReclaimedCode,
        )
        val single = PlaybackFailureClassifier.Context(
            isDualWindow = false,
            isSecondary = false,
            otherIsPlaying = false,
            hasPlayed = false,
        )
        assertEquals(PlaybackFailureClassifier.Outcome.ROOM_RETRY, PlaybackFailureClassifier.classify(sample, single))
        single.isSecondary = true
        single.otherIsPlaying = true
        assertEquals(PlaybackFailureClassifier.Outcome.ROOM_RETRY, PlaybackFailureClassifier.classify(sample, single))
    }
}
