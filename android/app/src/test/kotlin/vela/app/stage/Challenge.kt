package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.R

/** 质询文案和地址收口。不加载网页。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChallengeCopyTest {
    @Test
    fun testChallengeStringsMatchTheSheet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("确认访问", context.getString(R.string.playchain_challenge_title))
        assertEquals("已完成，继续播放", context.getString(R.string.playchain_challenge_done))
        assertEquals("关闭", context.getString(R.string.playchain_challenge_close))
        assertTrue(context.getString(R.string.playchain_challenge_body).contains("不会在这个窗口里播放"))
        assertEquals(needsConfirmMessage(), context.getString(R.string.playchain_needs_confirm))
        assertEquals(challengeSkipMessage(), context.getString(R.string.playchain_challenge_skip))
        assertEquals(connectFailedMessage(), context.getString(R.string.playchain_connect_failed))
        assertEquals("当前地区不可观看", context.getString(R.string.playchain_failure_geo))
        assertEquals(streamFailureMessage(vela.core.stream.StreamFailure.geo), context.getString(R.string.playchain_failure_geo))
    }

    @Test
    fun testPermissiveUrlKeepsHttpsAndLoopbackOnly() {
        assertEquals("https://cdn.example/a.m3u8", permissiveStreamUrl("https://cdn.example/a.m3u8").toString())
        assertEquals("http://10.0.2.2:9/hls/a.m3u8", permissiveStreamUrl(" http://10.0.2.2:9/hls/a.m3u8 ").toString())
        assertEquals("http://127.0.0.1/a.m3u8", permissiveStreamUrl("http://127.0.0.1/a.m3u8").toString())
        assertNull(permissiveStreamUrl("http://evil.example/a.m3u8"))
        assertNull(permissiveStreamUrl("notaurl"))
        assertEquals(
            "http://10.0.2.2:9/api/chatvideocontext/ann/",
            UpstreamRewrite.apply("https://chaturbate.com/api/chatvideocontext/ann/", "http://10.0.2.2:9"),
        )
        assertEquals(
            "https://cdn.example/a.m3u8",
            UpstreamRewrite.apply("https://cdn.example/a.m3u8", "http://10.0.2.2:9"),
        )
    }
}
