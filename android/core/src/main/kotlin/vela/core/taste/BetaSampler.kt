package vela.core.taste

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Swift `RandomNumberGenerator.next() -> UInt64`。
 * 每次调用消耗一个 64 位无符号整数，和 `inout` 发生器同一顺序。
 */
fun interface RandomSource {
    fun next(): ULong
}

/**
 * 从 Beta 分布取样（汤普森采样用）。Beta(α, β) = X / (X + Y)，X ~ Gamma(α)，Y ~ Gamma(β)。
 * 参数下限钳到 1，因此只需 Marsaglia-Tsang 的 shape ≥ 1 分支。
 *
 * `Double.random(in: 0..<1)` 按 Swift 标准库：取 `next()` 的低 53 位，乘 `2^-53`，结果落在 `[0, 1)`。
 * `ln` / `sqrt` / `cos` 走 JVM libm，不保证与 Darwin 逐位相同，见 parity 笔记。
 */
object BetaSampler {
    fun sample(alpha: Double, beta: Double, rng: RandomSource): Double {
        val x = gamma(max(alpha, 1.0), rng)
        val y = gamma(max(beta, 1.0), rng)
        return x / (x + y)
    }

    private fun gamma(shape: Double, rng: RandomSource): Double {
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            val x = normal(rng)
            val t = 1.0 + c * x
            if (t <= 0.0) continue
            val v = t * t * t
            val u = unitOpen(rng)
            if (u == 0.0 || ln(u) < 0.5 * x * x + d - d * v + d * ln(v)) return d * v
        }
    }

    /** Box-Muller。`u1` 用 `1 - U`，避免对 0 取对数。 */
    private fun normal(rng: RandomSource): Double {
        val u1 = 1.0 - unitOpen(rng)
        val u2 = unitOpen(rng)
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    /** Swift `Double.random(in: 0..<1, using:)`。低 53 位，乘 `2^-53`。 */
    private fun unitOpen(rng: RandomSource): Double {
        val mask = (1UL shl 53) - 1UL
        val rand = rng.next() and mask
        return rand.toDouble() / TWO_TO_53
    }

    private val TWO_TO_53: Double = (1L shl 53).toDouble()
}
