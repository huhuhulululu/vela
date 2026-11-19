package vela.app.infra

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThumbnailCacheTest {
    @Test
    fun testNullUrlAndNonSuccessAreNotCached() {
        val calls = AtomicInteger()
        val cache = cache(calls) { HTTPResult(status = 404, data = byteArrayOf(1)) }
        runBlocking {
            assertNull(cache.imageOrFetch(null))
            assertNull(cache.imageOrFetch(""))
            assertNull(cache.imageOrFetch("http://127.0.0.1/missing.jpg"))
        }
        assertEquals(1, calls.get())
        assertNull(cache.peek("http://127.0.0.1/missing.jpg"))
    }

    @Test
    fun testInflightFetchIsShared() {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val cache = cache(calls) {
            gate.await()
            HTTPResult(status = 200, data = byteArrayOf(9))
        }
        val url = "http://127.0.0.1/room.jpg"
        runBlocking {
            val first = async { cache.imageOrFetch(url) }
            val second = async { cache.imageOrFetch(url) }
            delay(50)
            cache.prefetch(url)
            delay(50)
            assertEquals(1, calls.get())
            gate.complete(Unit)
            val a = first.await()
            val b = second.await()
            assertNotNull(a)
            assertTrue(a === b)
            assertTrue(a === cache.peek(url))
            cache.prefetch(url)
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun testCountAndByteBudgetEvictOldest() {
        val calls = AtomicInteger()
        val cache = ThumbnailCache(
            client = object : HTTPClient {
                override suspend fun send(request: HTTPRequest): HTTPResult {
                    calls.incrementAndGet()
                    return HTTPResult(status = 200, data = request.url.encodeToByteArray())
                }
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            decode = { data -> Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888) },
            countLimit = 2,
            byteBudget = 10 * 10 * 4 * 2,
        )
        runBlocking {
            val first = "http://127.0.0.1/a.jpg"
            val second = "http://127.0.0.1/b.jpg"
            val third = "http://127.0.0.1/c.jpg"
            assertNotNull(cache.imageOrFetch(first))
            assertNotNull(cache.imageOrFetch(second))
            assertNotNull(cache.imageOrFetch(third))
            assertNull(cache.peek(first))
            assertNotNull(cache.peek(second))
            assertNotNull(cache.peek(third))
        }
    }

    @Test
    fun testDecodeKeepsLongEdgeWithinCap() {
        val source = Bitmap.createBitmap(400, 80, Bitmap.Config.ARGB_8888)
        val png = java.io.ByteArrayOutputStream().use { out ->
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        }
        val decoded = decodeThumbnail(png, ThumbnailCache.maxPixelSize)
        assertNotNull(decoded)
        assertTrue(decoded!!.width > 0 && decoded.height > 0)
        assertTrue(maxOf(decoded.width, decoded.height) <= ThumbnailCache.maxPixelSize)
    }

    private fun cache(calls: AtomicInteger, handler: suspend (HTTPRequest) -> HTTPResult): ThumbnailCache {
        return ThumbnailCache(
            client = object : HTTPClient {
                override suspend fun send(request: HTTPRequest): HTTPResult {
                    calls.incrementAndGet()
                    return handler(request)
                }
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            decode = { Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888) },
            countLimit = 20,
            byteBudget = 2 * 1024 * 1024,
        )
    }
}
