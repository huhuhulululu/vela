package vela.core.sync

/**
 * 混合逻辑时钟（第 5.1 节）：`l` 48 位毫秒、`c` 16 位计数、`dev` 32 位设备号。
 * 线格式为 24 个小写 hex，字符串字节序等于元组 `(l, c, dev)` 的字典序。
 */
data class Hlc(val l: Long, val c: Int, val dev: Long) : Comparable<Hlc> {
    init {
        require(l >= 0 && c in 0..0xFFFF && dev >= 0)
    }

    val hex: String
        get() = l.toString(16).padStart(12, '0') +
            c.toString(16).padStart(4, '0') +
            dev.toString(16).padStart(8, '0')

    /** 与接收方当前时间无关的静态界限：`1 ≤ l ≤ 0xFFFF00000000`，`dev ≠ 0`。 */
    val isStaticallyValid: Boolean get() = l >= 1 && l <= MAX_L && dev != 0L

    override fun compareTo(other: Hlc): Int {
        if (l != other.l) return if (l < other.l) -1 else 1
        if (c != other.c) return c - other.c
        return if (dev < other.dev) -1 else if (dev > other.dev) 1 else 0
    }

    fun physicalPrecedes(other: Hlc): Boolean = l < other.l || (l == other.l && c < other.c)

    companion object {
        const val MAX_L: Long = 0xFFFF_0000_0000L
        val ZERO = Hlc(0, 0, 0)

        fun parse(text: String): Hlc? {
            if (text.length != 24) return null
            var l = 0L
            var c = 0
            var dev = 0L
            for (i in 0 until 12) {
                val n = SyncEncoding.nibble(text[i].code) ?: return null
                l = l shl 4 or n.toLong()
            }
            for (i in 12 until 16) {
                val n = SyncEncoding.nibble(text[i].code) ?: return null
                c = c shl 4 or n
            }
            for (i in 16 until 24) {
                val n = SyncEncoding.nibble(text[i].code) ?: return null
                dev = dev shl 4 or n.toLong()
            }
            return Hlc(l, c, dev)
        }
    }
}

/** 本设备在某个空间里的时钟状态 `(l, c, dev)`。 */
class SyncClock(l: Long = 0, c: Int = 0, val dev: Long) {
    var l: Long = l
        private set
    var c: Int = c
        private set

    val current: Hlc get() = Hlc(l, c, dev)

    /** 本地事件：一次用户操作取一个 tick。 */
    fun tick(pt: Long): Hlc {
        if (pt > l) {
            l = pt
            c = 0
        } else if (c < 0xFFFF) {
            c += 1
        } else {
            l += 1
            c = 0
        }
        return current
    }

    /** 收到已通过校验的远端文档：`(l', c')` 更大时取之，不 tick。 */
    fun receive(remote: Hlc?) {
        if (remote == null) return
        if (remote.l > l || (remote.l == l && remote.c > c)) {
            l = remote.l
            c = remote.c
        }
    }

    /** 重新盖章前把时钟重置到给定值（第 5.2 节）。 */
    fun reset(to: Hlc) {
        l = to.l
        c = to.c
    }

    override fun equals(other: Any?): Boolean {
        val o = other as? SyncClock ?: return false
        return l == o.l && c == o.c && dev == o.dev
    }

    override fun hashCode(): Int = (l xor (l ushr 32) xor c.toLong() xor dev).toInt()

    override fun toString(): String = "SyncClock(l=$l,c=$c,dev=$dev)"
}
