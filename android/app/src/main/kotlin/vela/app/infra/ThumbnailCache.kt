package vela.app.infra

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.UserAgent

/**
 * 房间缩略图内存缓存。长边最多 192 像素，按位图字节计费（宽 × 高 × 4），总量约 2 MB，最多 20 张。
 * 同一个地址的在途下载只发一次。
 */
class ThumbnailCache(
    private val client: HTTPClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val decode: (ByteArray) -> Bitmap? = { bytes -> decodeThumbnail(bytes, maxPixelSize) },
    private val countLimit: Int = COUNT_LIMIT,
    private val byteBudget: Int = BYTE_BUDGET,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private val inflight = HashMap<String, Deferred<Bitmap?>>()

    fun peek(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return synchronized(lock) { entries[url] }
    }

    fun prefetch(url: String?) {
        if (url.isNullOrBlank() || peek(url) != null) return
        scope.async { imageOrFetch(url) }
    }

    suspend fun imageOrFetch(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        peek(url)?.let { return it }
        val pending = synchronized(lock) {
            entries[url]?.let { cached -> return@synchronized CompletableDeferred(cached) }
            inflight[url] ?: scope.async { load(url) }.also { inflight[url] = it }
        }
        return pending.await()
    }

    private suspend fun load(url: String): Bitmap? {
        try {
            val result = try {
                client.send(
                    HTTPRequest(
                        url = url,
                        headers = mapOf(
                            "User-Agent" to UserAgent.safari,
                            "Accept" to "image/avif,image/webp,image/*,*/*;q=0.8",
                            "Referer" to "https://chaturbate.com/",
                        ),
                        timeoutSeconds = 12.0,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return null
            }
            if (result.status !in 200..299) return null
            val bitmap = decode(result.data) ?: return null
            synchronized(lock) {
                entries[url] = bitmap
                evict()
            }
            return bitmap
        } finally {
            synchronized(lock) { inflight.remove(url) }
        }
    }

    private fun evict() {
        while (entries.size > 1 && (entries.size > countLimit || cost() > byteBudget)) {
            val eldest = entries.entries.first()
            entries.remove(eldest.key)
        }
    }

    private fun cost(): Int {
        var total = 0L
        for (bitmap in entries.values) {
            total += bitmap.width.toLong() * bitmap.height.toLong() * 4L
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        const val maxPixelSize: Int = 192
        const val COUNT_LIMIT: Int = 20
        const val BYTE_BUDGET: Int = 2 * 1024 * 1024
    }
}

/** 把图片解成小位图。长边超过 [maxPixelSize] 时缩小；解不出来返回 null。 */
fun decodeThumbnail(data: ByteArray, maxPixelSize: Int): Bitmap? {
    if (data.isEmpty() || maxPixelSize <= 0) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val longEdge = max(bounds.outWidth, bounds.outHeight)
    while (sample < longEdge && longEdge / (sample * 2) >= maxPixelSize) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val edge = max(bitmap.width, bitmap.height)
    if (edge <= maxPixelSize) return bitmap
    val scale = maxPixelSize.toFloat() / edge.toFloat()
    val width = max(1, (bitmap.width * scale).toInt())
    val height = max(1, (bitmap.height * scale).toInt())
    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
    if (scaled != bitmap) bitmap.recycle()
    return scaled
}
