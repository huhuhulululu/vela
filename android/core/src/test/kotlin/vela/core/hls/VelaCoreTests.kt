package vela.core.hls

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VelaCoreTests {
    @Test
    fun testPlaylistLadderPicksRestingRung() {
        val variants = PlaylistLadder.variants(Fixtures.playlist)
        assertEquals(listOf(896_000, 1_696_000, 3_096_000, 5_128_000), variants.map { it.bandwidth })
        assertEquals(listOf(360, 540, 720, 1080), variants.map { it.height })
        assertEquals(3_096_000.0, PlaylistLadder.peakBitrate(pixelHeight = 800.0, variants = variants, resting = false))
        assertEquals(1_696_000.0, PlaylistLadder.peakBitrate(pixelHeight = 500.0, variants = variants, resting = false))
        assertEquals(896_000.0, PlaylistLadder.peakBitrate(pixelHeight = 1400.0, variants = variants, resting = true))
        val master = URI.create("https://edge.example/live/llhls.m3u8")
        val media = PlaylistLadder.mediaURL(Fixtures.playlist, master, maxBandwidth = 1_700_000.0)
        assertEquals("https://edge.example/live/mid.m3u8", media?.toString())
        val highest = PlaylistLadder.mediaURL(Fixtures.playlist, master, maxBandwidth = Double.POSITIVE_INFINITY)
        assertEquals("https://edge.example/live/source.m3u8", highest?.toString())
        val steady = PlaylistLadder.steadyPlaylist(
            Fixtures.lowLatencyMedia,
            URI.create("https://edge.example/live/source.m3u8?token=abc"),
        )
        assertEquals(Fixtures.steadyMedia + "\n", steady)
        assertNull(PlaylistLadder.steadyPlaylist(Fixtures.playlist, master))
        assertEquals(1_696_000.0, PowerPolicy.peakBitrate(pixelHeight = 500.0, resting = false))
    }
}

private object Fixtures {
    val lowLatencyMedia = """
        #EXTM3U
        #EXT-X-VERSION:9
        #EXT-X-TARGETDURATION:2
        #EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK=1.000
        #EXT-X-PART-INF:PART-TARGET=0.500
        #EXT-X-MEDIA-SEQUENCE:10
        #EXT-X-MAP:URI="init.mp4"
        #EXTINF:2.000,
        seg10.m4s
        #EXT-X-PART:DURATION=0.500,URI="seg11.1.m4s",INDEPENDENT=YES
        #EXT-X-PRELOAD-HINT:TYPE=PART,URI="seg11.2.m4s"
    """.trimIndent()

    val steadyMedia = """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-TARGETDURATION:2
        #EXT-X-MEDIA-SEQUENCE:10
        #EXT-X-MAP:URI="https://edge.example/live/init.mp4"
        #EXTINF:2.000,
        https://edge.example/live/seg10.m4s
    """.trimIndent()

    val playlist = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=896000,RESOLUTION=640x360,CODECS="avc1.42c01e,mp4a.40.2"
        low.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1696000,RESOLUTION=960x540
        mid.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=3096000,RESOLUTION=1280x720
        high.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5128000,RESOLUTION=1920x1080
        source.m3u8
    """.trimIndent()
}
