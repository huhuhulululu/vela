package vela.app

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import vela.app.infra.Endpoints
import vela.app.infra.ThumbnailCache
import vela.app.lane.FakeLanePlayer
import vela.app.lane.LaneSurface
import vela.app.lane.PlaybackLane
import vela.app.playback.LaneHub
import vela.core.base.lane.LaneID
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult

/**
 * 留边缩略图必须接上：有用户名且缓存能返回图时，传给 LaneSurface 的不是 null；
 * 没有用户名时是 null。还没取到时保持 null，不挡首帧，下载不在主线程。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityThumbnailTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun testRoomWithCachedImagePassesBitmapToSurface() {
        val urls = CopyOnWriteArrayList<String>()
        val cache = cache(urls) { HTTPResult(status = 200, data = byteArrayOf(7)) }
        val url = Endpoints.thumbnail("room-a")
        runBlocking { assertNotNull(cache.imageOrFetch(url)) }
        val lane = lane()
        var passed: Bitmap? = null
        var composed = false
        compose.setContent {
            val thumbnail = laneThumbnail("room-a", cache)
            passed = thumbnail
            composed = true
            LaneSurface(lane = lane, thumbnail = thumbnail)
        }
        compose.waitForIdle()
        assertTrue(composed)
        assertNotNull("有用户名且缓存有图时，传给画面的不能是 null", passed)
        assertTrue(passed === cache.peek(url))
        assertEquals(listOf(url), urls.toList())
    }

    @Test
    fun testNoUsernamePassesNullAndFetchesNothing() {
        val urls = CopyOnWriteArrayList<String>()
        val cache = cache(urls) { HTTPResult(status = 200, data = byteArrayOf(7)) }
        val username = mutableStateOf<String?>(null)
        val lane = lane()
        var passed: Bitmap? = null
        var composed = false
        compose.setContent {
            val thumbnail = laneThumbnail(username.value, cache)
            passed = thumbnail
            composed = true
            LaneSurface(lane = lane, thumbnail = thumbnail)
        }
        compose.waitForIdle()
        assertTrue(composed)
        assertNull("没有用户名时必须是 null", passed)
        username.value = ""
        compose.waitForIdle()
        assertNull(passed)
        assertTrue("没有用户名不发图片请求", urls.isEmpty())
        username.value = "room-a"
        awaitThumbnail { passed }
        assertNotNull(passed)
        assertEquals(listOf(Endpoints.thumbnail("room-a")), urls.toList())
    }

    @Test
    fun testPendingFetchKeepsNullWithoutBlockingFirstFrame() {
        val urls = CopyOnWriteArrayList<String>()
        val fetchThreads = CopyOnWriteArrayList<Thread>()
        val gate = CompletableDeferred<Unit>()
        val cache = cache(urls) {
            fetchThreads.add(Thread.currentThread())
            gate.await()
            HTTPResult(status = 200, data = byteArrayOf(7))
        }
        val lane = lane()
        var passed: Bitmap? = null
        var composed = false
        compose.setContent {
            val thumbnail = laneThumbnail("room-b", cache)
            passed = thumbnail
            composed = true
            LaneSurface(lane = lane, thumbnail = thumbnail)
        }
        compose.waitForIdle()
        assertTrue("下载没回来也要先完成组合", composed)
        assertNull("还没取到时保持 null，不挡首帧", passed)
        gate.complete(Unit)
        awaitThumbnail { passed }
        assertNotNull(passed)
        assertTrue(fetchThreads.isNotEmpty())
        val main = Looper.getMainLooper().thread
        assertTrue("图片下载不能在主线程", fetchThreads.none { it === main })
    }

    @Test
    fun testShowStageWiresRoomThumbnailInsteadOfNull() {
        val dir = listOf(
            File("src/main/kotlin/vela/app"),
            File("app/src/main/kotlin/vela/app"),
        ).firstOrNull { it.isDirectory }
        assertNotNull("找不到源码目录，测试不能假装通过", dir)
        val source = File(dir!!, "MainActivity.kt")
        assertTrue("找不到 MainActivity.kt，测试不能假装通过", source.isFile)
        val text = source.readText()
        assertFalse("showStage 不能再把 thumbnail 写死成 null", text.contains("thumbnail = null"))
        assertTrue("showStage 要按这一路的房间名取缩略图", text.contains("laneThumbnail("))
        assertTrue(text.contains("state.face(laneId)?.username"))
    }

    private fun awaitThumbnail(get: () -> Bitmap?) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (get() == null && System.nanoTime() < deadline) {
            compose.waitForIdle()
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        assertNotNull("缩略图取到后要交给画面", get())
    }

    private fun lane(): PlaybackLane = PlaybackLane(
        id = LaneID.PRIMARY,
        hub = LaneHub(),
        player = FakeLanePlayer(),
    )

    private fun cache(
        urls: MutableList<String>,
        handler: suspend (HTTPRequest) -> HTTPResult,
    ): ThumbnailCache = ThumbnailCache(
        client = object : HTTPClient {
            override suspend fun send(request: HTTPRequest): HTTPResult {
                urls.add(request.url)
                return handler(request)
            }
        },
        decode = { Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888) },
    )
}
