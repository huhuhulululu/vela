package vela.core.base.lane

/**
 * 一路的身份。单窗只有主路；竖屏再按路数加副路、第三路（最多 3 路）。
 * 顺序固定为 primary → secondary → tertiary，替补、压紧与日志都按这个顺序。
 */
enum class LaneID {
    PRIMARY,
    SECONDARY,
    TERTIARY,
    ;

    /** 生命周期、画中画、音频、结算用的另一套路身份（一一对应）。 */
    val role: PlaybackLaneRole
        get() = when (this) {
            PRIMARY -> PlaybackLaneRole.PRIMARY
            SECONDARY -> PlaybackLaneRole.SECONDARY
            TERTIARY -> PlaybackLaneRole.TERTIARY
        }

    /** 日志标签，与 [PlaybackLaneRole.label] 相同。 */
    val label: String
        get() = role.label

    companion object {
        /** 路数上限。 */
        const val maxCount: Int = 3

        /** 按路数取前 N 路（夹紧到 1…3）。 */
        fun active(count: Int): List<LaneID> = entries.take(count.coerceIn(1, maxCount))
    }
}

/**
 * 一路在可见列表里的位置，以及这路自己的移动方向。
 * 自动跳过沿 [direction]，不借用另一路。
 */
data class LaneCursor(
    var username: String? = null,
    /** +1 向列表后方，-1 向列表前方。构造时收成符号；之后赋值不再夹紧。 */
    var direction: Int = 1,
    /** 这一路上一次划走的房间。向后划回它不受「最近划走」限制。选房算法本身仍由后面的包接上。 */
    var previous: String? = null,
) {
    init {
        direction = if (direction < 0) -1 else 1
    }

    /** 自动跳过的步长：这路自己的方向。 */
    val autoDelta: Int
        get() = if (direction < 0) -1 else 1
}
