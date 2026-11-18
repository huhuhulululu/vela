package vela.core.base.play

/**
 * 中继拉上游失败的原因：只含状态码与错误域码，不含地址。
 * Swift 把其它错误桥成 NSError。JVM 没有 NSError，[classify] 用异常类名当域、码为 0。
 */
sealed class RelayFailure : Exception() {
    val label: String
        get() = when (this) {
            is Http -> "http-$status"
            is Transport -> "$domain/$code"
            BadBody -> "bad-body"
            NoSegments -> "no-segments"
        }

    data class Http(val status: Int) : RelayFailure()
    data class Transport(val domain: String, val code: Int) : RelayFailure()
    data object BadBody : RelayFailure()
    data object NoSegments : RelayFailure()

    companion object {
        fun classify(error: Throwable): RelayFailure {
            if (error is RelayFailure) return error
            return Transport(error.javaClass.name, 0)
        }
    }
}
