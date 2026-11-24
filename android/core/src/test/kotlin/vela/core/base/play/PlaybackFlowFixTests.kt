package vela.core.base.play

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackFlowFixTests {
    private fun playlist(
        sequence: Int,
        segments: Int = 3,
        discontinuity: Boolean = false,
        name: String = "seg",
    ): String {
        val lines = mutableListOf("#EXTM3U", "#EXT-X-TARGETDURATION:2", "#EXT-X-MEDIA-SEQUENCE:$sequence")
        if (discontinuity) lines.add("#EXT-X-DISCONTINUITY")
        for (index in 0 until segments) {
            lines.add("#EXTINF:2.000,")
            lines.add("$name${sequence + index}.ts")
        }
        return lines.joinToString("\n")
    }

    private fun info(text: String): MediaPlaylistInfo =
        MediaPlaylistInfo.parse(text) ?: error("parse returned null")

    @Test
    fun testParseReadsDiscontinuityAndLastURI() {
        val parsed = info(playlist(sequence = 5, discontinuity = true))
        assertTrue(parsed.hasDiscontinuity)
        assertEquals("seg7.ts", parsed.lastSegmentURI)
        assertEquals(7, parsed.lastSequence)
        val sequenceOnly = "#EXTM3U\n#EXT-X-DISCONTINUITY-SEQUENCE:3\n#EXTINF:2,\na.ts"
        assertFalse(info(sequenceOnly).hasDiscontinuity, "DISCONTINUITY-SEQUENCE 不是不连续标记")
    }
}
