package vela.core.base.lane

/** 一路的身份。单窗只有主路；双窗再加副路。 */
enum class LaneID {
    PRIMARY,
    SECONDARY,
}

/**
 * 一路在可见列表里的位置，以及这路自己的移动方向。
 * 自动跳过沿 [direction]，不借用另一路。
 */
data class LaneCursor(
    var username: String? = null,
    /** +1 向列表后方，-1 向列表前方。构造时收成符号；之后赋值不再夹紧。 */
    var direction: Int = 1,
) {
    init {
        direction = if (direction < 0) -1 else 1
    }

    /** 自动跳过的步长：这路自己的方向。 */
    val autoDelta: Int
        get() = if (direction < 0) -1 else 1
}
