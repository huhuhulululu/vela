package vela.core.recommend

import vela.core.taste.RandomSource

/**
 * 可注入种子的随机数发生器，测试因此可确定。
 * 与 Swift `SplitMix64` 相同：`ULong` 加法、乘法都按 64 位回绕，右移是逻辑移位。
 */
class SplitMix64(seed: ULong) : RandomSource {
    private var state: ULong = seed

    override fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }
}
