package vela.core.hls

/**
 * Swift `URLError` / `NSError` 的域和码。
 * JVM 没有 `NSURLErrorDomain`，传输失败用 [TransportError] 带上同一套数字，测试才能照抄断言。
 */
object UrlErrors {
    const val DOMAIN: String = "NSURLErrorDomain"

    const val CANCELLED: Int = -999
    const val TIMED_OUT: Int = -1001
    const val CANNOT_FIND_HOST: Int = -1003
    const val CANNOT_CONNECT_TO_HOST: Int = -1004
    const val NETWORK_CONNECTION_LOST: Int = -1005
    const val DNS_LOOKUP_FAILED: Int = -1006
    const val NOT_CONNECTED_TO_INTERNET: Int = -1009
    const val BAD_SERVER_RESPONSE: Int = -1011
    const val INTERNATIONAL_ROAMING_OFF: Int = -1018
    const val DATA_NOT_ALLOWED: Int = -1020
}

/** 带域和码的传输失败。默认域是 [UrlErrors.DOMAIN]，对应 Swift `URLError`。 */
class TransportError(
    val domain: String,
    val code: Int,
) : Exception("$domain/$code") {
    constructor(code: Int) : this(UrlErrors.DOMAIN, code)
}

/** 错误链上的一环。Swift 用 `(domain, code)` 元组，这里落成独立类型。 */
data class FailureCode(val domain: String, val code: Int)
