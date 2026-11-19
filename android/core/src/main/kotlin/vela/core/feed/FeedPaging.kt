package vela.core.feed

import vela.core.base.model.lowerRoot

/**
 * 可见列表上的落点。算法与 Swift `LanePaging.landingIndex` 相同。
 * `LanePaging` 写在 `LaneSelection.swift`，归属 C8；C5 不能依赖 C8，所以复制在这里。
 * C8 移植时保持同一套夹紧与跳过规则，不要改这一份的语义。
 */
internal object FeedPaging {
    fun landingIndex(index: Int, delta: Int, names: List<String>, skipping: Set<String>): Int {
        val count = names.size
        if (count <= 0) return 0
        val origin = index.coerceIn(0, count - 1)
        if (delta == 0) return origin
        val sum = origin.toLong() + delta.toLong()
        var next = sum.coerceIn(0L, (count - 1).toLong()).toInt()
        if (skipping.isEmpty()) return next
        val banned = skipping.mapTo(HashSet()) { it.lowerRoot() }
        val direction = if (delta > 0) 1 else -1
        while (names[next].lowerRoot() in banned) {
            val candidate = next + direction
            if (candidate < 0 || candidate >= count) break
            next = candidate
        }
        if (names[next].lowerRoot() in banned && names[origin].lowerRoot() !in banned) {
            return origin
        }
        return next
    }
}
