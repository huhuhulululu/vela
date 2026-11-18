package vela.core.feed

/** 房间信息块（名字、标签、推荐理由与底部渐变）是否显示。加载与失败的提示不走这里。 */
object InfoVisibility {
    /** 邻页始终带信息；当前页只在控制栏显示、或用户选了「始终显示」时显示。 */
    fun visible(isCurrent: Boolean, alwaysShow: Boolean, chromeOn: Boolean): Boolean =
        !isCurrent || alwaysShow || chromeOn
}
