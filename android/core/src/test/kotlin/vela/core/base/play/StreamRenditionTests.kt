package vela.core.base.play

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamRenditionTests {
    private val master = URI("https://edge.example/live/llhls.m3u8?token=abc")

    /** 上游真实形态：音频是独立音轨，视频子列表不含声音。 */
    private val masterText = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio_aac_96",NAME="Audio_200_4",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2",URI="chunklist_4_audio_x_llhls.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio_aac_128",NAME="Audio_200_5",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2",URI="chunklist_5_audio_x_llhls.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=896000,RESOLUTION=640x360,CODECS="avc1.42c01e,mp4a.40.2",FRAME-RATE=30.000,AUDIO="audio_aac_96"
        chunklist_0_video_x_llhls.m3u8?session=s1
        #EXT-X-STREAM-INF:BANDWIDTH=5128000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",FRAME-RATE=30.000,AUDIO="audio_aac_128"
        chunklist_3_video_x_llhls.m3u8?session=s1
        """.trimIndent()

    @Test
    fun testSelectsVideoAndItsAudioGroup() {
        val low = StreamRendition.select(masterText, master, 900_000.0)
        assertEquals(
            "https://edge.example/live/chunklist_0_video_x_llhls.m3u8?session=s1",
            low?.video?.toString(),
        )
        assertEquals(
            "https://edge.example/live/chunklist_4_audio_x_llhls.m3u8",
            low?.audio?.toString(),
        )
        val top = StreamRendition.select(masterText, master, Double.POSITIVE_INFINITY)
        assertEquals(
            "https://edge.example/live/chunklist_5_audio_x_llhls.m3u8",
            top?.audio?.toString(),
        )
        // 档位上限低于所有档时取最低档。
        assertEquals(low?.video, StreamRendition.select(masterText, master, 100.0)?.video)
    }

    @Test
    fun testLocalMasterCarriesAudioGroupAndLocalAddresses() {
        val top = StreamRendition.select(masterText, master, Double.POSITIVE_INFINITY)
        val text = top?.localMaster("http://127.0.0.1:9/abc")
        check(text != null)
        assertTrue(text.contains("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio_aac_128\""))
        assertTrue(text.contains("URI=\"http://127.0.0.1:9/abc/audio.m3u8\""))
        assertTrue(text.contains("AUDIO=\"audio_aac_128\""))
        assertTrue(text.contains("RESOLUTION=1920x1080"))
        assertTrue(text.contains("CODECS=\"avc1.640028,mp4a.40.2\""))
        assertTrue(text.contains("FRAME-RATE=30.000"))
        assertTrue(text.contains("\nhttp://127.0.0.1:9/abc/video.m3u8\n"))
        // 只含选中的一档，上游地址不外露。
        assertEquals(2, text.split("#EXT-X-STREAM-INF").size)
        assertFalse(text.contains("edge.example"))
        assertFalse(text.contains("audio_aac_96"))
    }

    @Test
    fun testMuxedSourceNeedsNoLocalMaster() {
        val muxed = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=896000,RESOLUTION=640x360,CODECS="avc1.42c01e,mp4a.40.2"
            low.m3u8
            """.trimIndent()
        val rendition = StreamRendition.select(muxed, master, Double.POSITIVE_INFINITY)
        assertNull(rendition?.audio)
        assertNull(rendition?.localMaster("http://127.0.0.1:9/abc"))
    }

    @Test
    fun testMissingAudioGroupDropsAudioReference() {
        val broken = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=896000,CODECS="avc1.42c01e",AUDIO="gone"
            low.m3u8
            """.trimIndent()
        val rendition = StreamRendition.select(broken, master, Double.POSITIVE_INFINITY)
        check(rendition != null)
        assertNull(rendition.audio)
        assertNull(HLSAttribute.value("AUDIO", rendition.videoAttributes))
    }

    @Test
    fun testAttributeParserKeepsQuotedCommas() {
        val attributes = HLSAttribute.parse("A=1,CODECS=\"x,y\",B=\"z\"")
        assertEquals(listOf("A", "CODECS", "B"), attributes.map { it.key })
        assertEquals("x,y", HLSAttribute.value("CODECS", attributes))
    }

    @Test
    fun testUnselectedKeepsMasterAddress() {
        val rendition = StreamRendition.unselected(master)
        assertEquals(master.toString(), rendition.video.toString())
        assertNull(rendition.audio)
        assertTrue(rendition.isUnselected)
        assertNull(rendition.localMaster("http://127.0.0.1:9/abc"))
    }
}
