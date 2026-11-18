package vela.core.base.net

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vela.core.base.site.looksLikeChallenge

class EndpointBackoffTests {
    private val t0: Instant = Instant.ofEpochSecond(1_000)

    @Test
    fun testWindowIsPerEndpointAndThirtySeconds() {
        val backoff = EndpointBackoff()
        assertTrue(backoff.allows(SiteEndpoint.streamURL, t0))
        backoff.record(SiteEndpoint.streamURL, t0)
        assertFalse(backoff.allows(SiteEndpoint.streamURL, t0.plusMillis(29_900)))
        assertTrue(backoff.allows(SiteEndpoint.streamURL, t0.plusSeconds(EndpointBackoff.window.toLong())))
        assertEquals(30.0, EndpointBackoff.window)
        for (other in SiteEndpoint.entries) {
            if (other == SiteEndpoint.streamURL) continue
            assertTrue(backoff.allows(other, t0), "$other 不受影响")
        }
        backoff.reset()
        assertTrue(backoff.allows(SiteEndpoint.streamURL, t0))
    }

    @Test
    fun testShapeClassification() {
        assertTrue(ResponseShape.looksLikeJSON(" \n{\"a\":1}".encodeToByteArray()))
        assertTrue(ResponseShape.looksLikeJSON("[1]".encodeToByteArray()))
        assertTrue(ResponseShape.looksLikeJSON(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{}".encodeToByteArray()))
        assertFalse(ResponseShape.looksLikeJSON(ByteArray(0)))
        assertFalse(ResponseShape.looksLikeJSON("<html>".encodeToByteArray()))

        assertTrue(ResponseShape.shouldBackOff(200, "<html>busy</html>".encodeToByteArray()))
        assertTrue(ResponseShape.shouldBackOff(502, ByteArray(0)))
        assertFalse(ResponseShape.shouldBackOff(200, "{}".encodeToByteArray()))
        assertFalse(
            ResponseShape.shouldBackOff(403, "<title>Just a moment...</title>".encodeToByteArray()),
            "质询页走确认流程",
        )
        assertFalse(ResponseShape.shouldBackOff(404, "<html>404</html>".encodeToByteArray(), setOf(404)))
    }

    @Test
    fun testChallengeMarkers() {
        assertTrue(looksLikeChallenge("<html><title>Just a moment...</title></html>".encodeToByteArray()))
        assertTrue(looksLikeChallenge("<html><h1>Verify your age</h1></html>".encodeToByteArray()))
        val padded =
            "<html><head>" + "<meta name=\"x\" content=\"y\">".repeat(40) + "</head><body>VERIFY YOUR AGE</body>"
        assertTrue(looksLikeChallenge(padded.encodeToByteArray()))
        assertFalse(looksLikeChallenge("""{"room_title":"just a moment, verify your age"}""".encodeToByteArray()))
        assertFalse(looksLikeChallenge("<html>busy</html>".encodeToByteArray()))
        assertTrue(looksLikeChallenge("<html>cf-mitigated challenges.cloudflare.com</html>".encodeToByteArray()))
    }

    @Test
    fun testPendingNoteMatchesPlaybackGate() {
        assertEquals("站点暂时限流，稍后自动重试", EndpointBackoff.waitingNote)
        assertEquals("站点暂时限流，可稍后手动重试", EndpointBackoff.heldNote)
        assertEquals("站点暂时限流，请稍后再试", EndpointBackoff.catalogNote)
        assertEquals(EndpointBackoff.waitingNote, EndpointBackoff.pendingNote(playbackAllowed = true))
        assertEquals(EndpointBackoff.heldNote, EndpointBackoff.pendingNote(playbackAllowed = false))
    }

    @Test
    fun testBoxNoteRecordsOnlyNonJson() {
        var now = Instant.ofEpochSecond(50_000)
        val box = EndpointBackoffBox(now = { now })
        assertFalse(box.note(SiteEndpoint.catalog, 200, "{}".encodeToByteArray()))
        assertTrue(box.allows(SiteEndpoint.catalog))
        assertFalse(box.note(SiteEndpoint.catalog, 403, "<title>Just a moment...</title>".encodeToByteArray()))
        assertTrue(box.allows(SiteEndpoint.catalog))
        assertFalse(box.note(SiteEndpoint.chatContext, 404, "<html>Not Found</html>".encodeToByteArray(), setOf(401, 404)))
        assertTrue(box.allows(SiteEndpoint.chatContext))
        assertTrue(box.note(SiteEndpoint.streamURL, 429, "<html>Too many requests</html>".encodeToByteArray()))
        assertFalse(box.allows(SiteEndpoint.streamURL))
        assertTrue(box.allows(SiteEndpoint.catalog))
        now = now.plusSeconds(30)
        assertTrue(box.allows(SiteEndpoint.streamURL))
    }
}
