package vela.core.base.lane

/**
 * `LaneSettlement.EndReason` 的独立落点。C8 的 `LaneSettlement` 用它（也可以 typealias）。
 */
enum class LaneEndReason {
    SWIPE,
    AUTO_SKIP,
    BLOCK,
    BACKGROUND,
    DUAL_OFF,
    SOURCE_CHANGE,
    FAILED,
    NOT_INTERESTED,
}
