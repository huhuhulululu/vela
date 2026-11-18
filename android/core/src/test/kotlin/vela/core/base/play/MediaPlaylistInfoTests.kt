package vela.core.base.play

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaPlaylistInfoTests {
    @Test
    fun testNoCompleteSegmentReturnsNull() {
        assertNull(MediaPlaylistInfo.parse("#EXTM3U\n#EXT-X-TARGETDURATION:2\n"))
        assertNull(MediaPlaylistInfo.parse("#EXTM3U\n#EXT-X-PART:DURATION=0.5,URI=\"p.ts\"\n"))
    }

    @Test
    fun testMissingTargetDurationFallsBack() {
        assertEquals(
            RelayDefaults.defaultTargetDuration,
            MediaPlaylistInfo.parse("#EXTM3U\n#EXTINF:2,\na.ts")?.targetDuration,
        )
    }

    @Test
    fun testZeroOrNegativeTargetDurationFallsBack() {
        assertEquals(
            RelayDefaults.defaultTargetDuration,
            MediaPlaylistInfo.parse("#EXTM3U\n#EXT-X-TARGETDURATION:0\n#EXTINF:2,\na.ts")?.targetDuration,
        )
        assertEquals(
            RelayDefaults.defaultTargetDuration,
            MediaPlaylistInfo.parse("#EXTM3U\n#EXT-X-TARGETDURATION:-3\n#EXTINF:2,\na.ts")?.targetDuration,
        )
    }

    @Test
    fun testPartsDoNotCountAsSegments() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:100
            #EXTINF:1.600,
            seg.ts
            #EXTINF:1.600,
            seg.ts
            #EXTINF:1.600,
            seg.ts
            #EXTINF:1.600,
            seg.ts
            #EXT-X-PART:DURATION=0.5,URI="p.ts"
            """.trimIndent()
        val parsed = MediaPlaylistInfo.parse(text)
        assertEquals(103, parsed?.lastSequence)
        assertEquals(2.0, parsed?.targetDuration)
        assertTrue(abs((parsed?.lastSegmentDuration ?: 0.0) - 1.6) < 0.001)
        assertEquals("seg.ts", parsed?.lastSegmentURI)
        assertEquals(false, parsed?.hasDiscontinuity)
    }
}
