package vela.core.selection

import vela.core.base.model.lowerRoot

/**
 * 可见列表上的落点。规则与 Swift `LanePaging.landingIndex` 相同：
 * `skipping` 为空时与 `FeedStore.step` 的夹紧公式相同；落在被跳过的房间上时，
 * 沿 delta 方向继续找，找不到就停在原地。
 *
 * C5 在 `FeedPaging` 里留了一份同样的副本（当时 C8 还不存在，feed 不能依赖选房）。
 * 这里按那一份逐行对齐，包括用 `Long` 做加法，避免 Kotlin `Int` 回绕和 Swift 的陷阱不一致。
 * 用户名比较用 [lowerRoot]，和本仓库其它移植一样，不是 Swift 的当前区域小写。
 */
object LanePaging {
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
