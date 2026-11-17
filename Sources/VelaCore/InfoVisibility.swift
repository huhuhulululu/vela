import Foundation

/// 房间信息块（名字、标签、推荐理由与底部渐变）是否显示的纯判定。
public enum InfoVisibility {
    /// 邻页（拖动切房时露出的上 / 下一页）始终带信息；当前页只在控制栏显示、或用户选了「始终显示」时显示。
    /// 加载与失败的提示不是房间信息，不走这里。
    public static func visible(isCurrent: Bool, alwaysShow: Bool, chromeOn: Bool) -> Bool {
        !isCurrent || alwaysShow || chromeOn
    }
}
