package vela.core.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VelaCoreTests {
    @Test
    fun testStreamTickets() {
        val playing = StreamDecoder.decode(200, Fixtures.streamPublic)
        assertTrue(playing.isPlayable)
        assertEquals("edge.example", playing.url?.host)

        val legacy = StreamDecoder.decode(200, Fixtures.streamLegacySuccess)
        assertTrue(legacy.isPlayable)

        val priv = StreamDecoder.decode(200, Fixtures.streamPrivate)
        assertEquals(StreamFailure.privateShow, priv.failure)

        val offline = StreamDecoder.decode(200, Fixtures.streamOffline)
        assertEquals(StreamFailure.offline, offline.failure)

        val geo = StreamDecoder.decode(200, Fixtures.streamGeo)
        assertEquals(StreamFailure.geo, geo.failure)

        val error = assertFailsWith<StreamDecodeError> {
            StreamDecoder.decode(403, "<html>Just a moment</html>".toByteArray())
        }
        assertEquals(StreamDecodeError.Challenge, error)
    }

    @Test
    fun testStreamRequestDoesNotAskForHighestBandwidth() {
        val request = StreamRequest.make("alpha")
        val body = request.body?.toString(Charsets.UTF_8)
        assertEquals("room_slug=alpha", body)
        assertNull(request.header("Cookie"))
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
    }
}

private object Fixtures {
    val streamPublic =
        """{"success":true,"url":"https://edge.example/live/llhls.m3u8?token=abc","room_status":"public","hidden_message":""}"""
            .toByteArray()

    val streamLegacySuccess =
        """{"success":1,"url":"https://edge.example/legacy.m3u8","room_status":"public","hidden_message":""}"""
            .toByteArray()

    val streamPrivate =
        """{"success":true,"url":"","room_status":"private","hidden_message":""}"""
            .toByteArray()

    val streamOffline =
        """{"success":false,"url":"","room_status":"offline","hidden_message":""}"""
            .toByteArray()

    val streamGeo =
        """{"success":true,"url":"","room_status":"public","hidden_message":""}"""
            .toByteArray()
}
