package vela.core.base.lane

/** 竖屏多路的一路。单窗只用 [PRIMARY]。与 [LaneID] 一一对应。 */
enum class PlaybackLaneRole {
    PRIMARY,
    SECONDARY,
    TERTIARY,
    ;

    val laneID: LaneID
        get() = when (this) {
            PRIMARY -> LaneID.PRIMARY
            SECONDARY -> LaneID.SECONDARY
            TERTIARY -> LaneID.TERTIARY
        }

    /** 日志里的路标签。 */
    val label: String
        get() = when (this) {
            PRIMARY -> "primary"
            SECONDARY -> "secondary"
            TERTIARY -> "tertiary"
        }
}
