package vela.app.spike

import android.os.Looper
import java.lang.reflect.Method
import java.time.Duration
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowSystemClock

/**
 * Robolectric 暂停模式下 [System.nanoTime] 会被改写，不随真实时间走。
 * 反射调用 JDK 原生方法拿到墙钟；播放器时钟再用同样的增量推进，避免用虚拟时间换算请求频率。
 */
internal object WallClock {
    private val nanoTime: Method = System::class.java.getMethod("nanoTime")

    fun nanos(): Long = nanoTime.invoke(null) as Long

    fun millis(): Long = nanos() / 1_000_000L
}

/**
 * 把 Android [android.os.SystemClock] 以最多 30ms 的步长对齐到墙钟，并抽干主线程已到期的消息。
 * ExoPlayer 的播放线程、加载线程在 PAUSED 模式下默认不暂停，阻塞的 HTTP 发生在加载线程上。
 */
internal class WallClockPump {
    private var accounted = WallClock.nanos()

    fun pump(durationMs: Long) {
        val end = WallClock.nanos() + durationMs * 1_000_000L
        while (WallClock.nanos() < end) {
            val now = WallClock.nanos()
            var pendingMs = (now - accounted) / 1_000_000L
            if (pendingMs < 10L) {
                Thread.sleep(5)
                continue
            }
            while (pendingMs >= 10L && WallClock.nanos() < end + 30_000_000L) {
                val step = pendingMs.coerceAtMost(30L)
                ShadowSystemClock.advanceBy(Duration.ofMillis(step))
                accounted += step * 1_000_000L
                pendingMs -= step
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }
}
