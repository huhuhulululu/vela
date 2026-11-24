package vela.core.hls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackErrorTests {
    private fun chain(vararg pairs: Pair<String, Int>): List<FailureCode> =
        pairs.map { FailureCode(domain = it.first, code = it.second) }

    @Test
    fun testOfflineFromUnderlyingURLError() {
        val sample = chain("AVFoundationErrorDomain" to -11800, UrlErrors.DOMAIN to -1009)
        assertEquals(PlaybackErrorKind.OFFLINE, PlaybackErrorCopy.kind(sample))
        assertTrue(PlaybackErrorCopy.failureMessage(sample).contains("网络"))
    }

    @Test
    fun testTimeout() {
        assertEquals(PlaybackErrorKind.TIMEOUT, PlaybackErrorCopy.kind(chain(UrlErrors.DOMAIN to -1001)))
    }

    @Test
    fun testUnavailableFromAVFoundation() {
        assertEquals(PlaybackErrorKind.UNAVAILABLE, PlaybackErrorCopy.kind(chain("AVFoundationErrorDomain" to -11850)))
        assertEquals(PlaybackErrorKind.UNAVAILABLE, PlaybackErrorCopy.kind(chain("CoreMediaErrorDomain" to -12938)))
    }

    @Test
    fun testUnknownAndEmptyChainFallBackToGeneric() {
        assertEquals(PlaybackErrorKind.OTHER, PlaybackErrorCopy.kind(emptyList()))
        assertEquals(PlaybackErrorKind.OTHER, PlaybackErrorCopy.kind(chain("X" to 1)))
    }

    @Test
    fun testEveryMessageIsNonEmptyChinese() {
        for (kind in listOf(PlaybackErrorKind.OFFLINE, PlaybackErrorKind.TIMEOUT, PlaybackErrorKind.UNAVAILABLE, PlaybackErrorKind.OTHER)) {
            val text = PlaybackErrorCopy.message(kind)
            assertFalse(text.isEmpty())
            assertTrue(text.any { it.code > 0x4E00 })
        }
        assertFalse(PlaybackErrorCopy.startTimeoutMessage(emptyList()).isEmpty())
        assertTrue(PlaybackErrorCopy.startTimeoutMessage(chain(UrlErrors.DOMAIN to -1009)).contains("网络"))
    }
}
