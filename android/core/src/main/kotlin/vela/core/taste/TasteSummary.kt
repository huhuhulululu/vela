package vela.core.taste

/** 口味面板里的一行：标签（或性别）、后验均值、命中次数。 */
data class TasteRow(
    val key: String,
    val name: String,
    val mean: Double,
    val hits: Int,
    val vetoed: Boolean,
) {
    val id: String
        get() = key
}

/** 最近一天的命中与落空。`weekday` 取 1（周日）到 7，对齐 `Calendar` 的 Gregorian。 */
data class DayBar(
    val weekday: Int,
    val hit: Int,
    val miss: Int,
) {
    val total: Int
        get() = hit + miss
}

data class TasteSummary(
    val liked: List<TasteRow>,
    val disliked: List<TasteRow>,
    val genders: List<TasteRow>,
    val tagCount: Int,
    val week: List<DayBar>,
    val hitRate: Double?,
)
