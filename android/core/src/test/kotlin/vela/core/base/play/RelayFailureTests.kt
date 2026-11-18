package vela.core.base.play

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayFailureTests {
    @Test
    fun testLabelsOmitAddresses() {
        assertEquals("http-410", RelayFailure.classify(RelayFailure.Http(410)).label)
        assertEquals("bad-body", RelayFailure.BadBody.label)
        assertEquals("no-segments", RelayFailure.NoSegments.label)
        val transport = RelayFailure.Transport("Example", 7)
        assertEquals("Example/7", transport.label)
        assertEquals(transport, RelayFailure.classify(transport))
        val other = IllegalStateException("https://secret.example/path")
        val classified = RelayFailure.classify(other)
        assertTrue(classified is RelayFailure.Transport)
        assertFalse(classified.label.contains("secret.example"))
    }
}
