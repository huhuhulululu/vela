package vela.core.hls

import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioNetworkTests {
    @Test
    fun testFirstLaunchIsMutedAndStoredChoiceIsKept() {
        assertTrue(AudioPolicy.initialMuted(stored = null))
        assertFalse(AudioPolicy.initialMuted(stored = false), "显式关过静音的用户保留")
        assertTrue(AudioPolicy.initialMuted(stored = true))
    }

    @Test
    fun testUnmuteAtZeroVolumeRestoresSmallVolume() {
        assertEquals(0.2, AudioPolicy.volumeOnUnmute(0.0))
        assertEquals(0.2, AudioPolicy.volumeOnUnmute(-0.1))
        assertEquals(0.6, AudioPolicy.volumeOnUnmute(0.6))
        assertEquals(0.01, AudioPolicy.volumeOnUnmute(0.01))
    }

    @Test
    fun testOfflineClassification() {
        assertTrue(NetworkFailure.isOffline(TransportError(UrlErrors.NOT_CONNECTED_TO_INTERNET)))
        assertTrue(NetworkFailure.isOffline(TransportError(UrlErrors.TIMED_OUT)))
        assertTrue(NetworkFailure.isOffline(TransportError(UrlErrors.CANNOT_FIND_HOST)))
        assertFalse(NetworkFailure.isOffline(TransportError(UrlErrors.CANCELLED)))
        assertFalse(NetworkFailure.isOffline(TransportError(UrlErrors.BAD_SERVER_RESPONSE)))
        assertFalse(NetworkFailure.isOffline(CancellationException()))
        assertTrue(NetworkFailure.isDisconnected(TransportError(UrlErrors.NOT_CONNECTED_TO_INTERNET)))
        assertFalse(NetworkFailure.isDisconnected(TransportError(UrlErrors.TIMED_OUT)), "超时仍可能是线路问题，保留侧车备路")
    }
}
