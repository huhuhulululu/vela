package vela.app.pip

import android.util.Rational

/**
 * 画中画宽高比夹在 1:2.39 到 2.39:1，与平台文档一致。
 */
object PipAspect {
    private val MIN = Rational(100, 239)
    private val MAX = Rational(239, 100)
    private val DEFAULT = Rational(16, 9)

    fun clamp(videoWidth: Int, videoHeight: Int): Rational {
        if (videoWidth <= 0 || videoHeight <= 0) return DEFAULT
        val ratio = videoWidth.toDouble() / videoHeight.toDouble()
        val min = MIN.numerator.toDouble() / MIN.denominator
        val max = MAX.numerator.toDouble() / MAX.denominator
        when {
            ratio >= max -> return MAX
            ratio <= min -> return MIN
        }
        return simplify(videoWidth, videoHeight)
    }

    private fun simplify(width: Int, height: Int): Rational {
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val g = gcd(width, height)
        return Rational(width / g, height / g)
    }
}