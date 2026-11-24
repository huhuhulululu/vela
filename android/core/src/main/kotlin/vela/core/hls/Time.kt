package vela.core.hls

import java.time.Instant
import vela.core.base.model.timeIntervalSince1970

/** 与 Swift `Date.timeIntervalSince` 一样，用 `Double` 秒。 */
internal fun Instant.secondsSince(earlier: Instant): Double =
    timeIntervalSince1970() - earlier.timeIntervalSince1970()

/** 正时长按秒加纳秒推进。测试里的间隔都是非负的。 */
internal fun Instant.plusSeconds(seconds: Double): Instant {
    val whole = seconds.toLong()
    val nanos = ((seconds - whole.toDouble()) * 1_000_000_000.0).toLong()
    return plusSeconds(whole).plusNanos(nanos)
}
