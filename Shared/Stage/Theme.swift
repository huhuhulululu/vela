import SwiftUI

enum Theme {
    static let champagne = Color(red: 0.91, green: 0.72, blue: 0.52)
    static let live = Color(red: 0.96, green: 0.27, blue: 0.24)
    /// 已收藏心形：深色控制栏上图形对比度不低于 WCAG 3:1。
    static let favorite = Color(red: 0.98, green: 0.30, blue: 0.36)
    static let online = Color(red: 0.36, green: 0.78, blue: 0.55)
    static let ink = Color(red: 0.045, green: 0.045, blue: 0.05)
    static let radius: CGFloat = 22

    /// 文字层级：主文字、次要文字（未选中标签、元信息）。深色底上均不低于 WCAG AA 4.5:1。
    static let textPrimary = Color.white.opacity(0.92)
    static let textSecondary = Color.white.opacity(0.72)
    /// 浮在视频上的小控件的衬底，保证文字对比度不依赖画面内容。
    static let chip = Color.black.opacity(0.36)
    /// 顶部控制栏的实心底色（叠在极薄材质上）。
    static let barFill = ink.opacity(0.9)
    static let selectedFill = Color.black.opacity(0.4)

    /// 尺寸与间距。
    static let tapHeight: CGFloat = 28
    static let barHeight: CGFloat = 44
    static let iconSize: CGFloat = 32
    static let windowButtonSize: CGFloat = 26
    static let inset: CGFloat = 12
    static let gap: CGFloat = 8
    static let tightGap: CGFloat = 6
    static let infoFade: Double = 0.35

    /// 切房过渡：模糊缩略图垫底与视频淡入。
    static let backdropBlurRadius: CGFloat = 30
    static let backdropScale: CGFloat = 1.06
    static let backdropScrim = Color.black.opacity(0.35)
    /// 浮在画面上的提示气泡的固定深色底：不只靠材质，亮画面上文字对比度也够。
    static let hintFill = Color.black.opacity(0.62)
    static let backdropWarm = Color(red: 0.11, green: 0.07, blue: 0.06)
    static let videoFade: Double = 0.3
    static let favoriteSuggestSeconds: Double = 6
    static let unfavoriteToastSeconds: Double = 4

    /// 收藏夹弹窗：与「我的口味」同宽同底，列表区高度随窗口高度收缩。
    static let panelWidth: CGFloat = 380
    static let panelPadding: CGFloat = inset + 4
    static let thumbSize = CGSize(width: 64, height: 36)
    static let thumbRadius: CGFloat = 6
    static let rowRadius: CGFloat = 8
    static let onlineRowHeight: CGFloat = 52
    static let otherRowHeight: CGFloat = 30
    static let listMaxHeight: CGFloat = 380
    static let listMinHeight: CGFloat = 80
    /// 弹窗里列表之外的固定部分（标题、主按钮、页脚、内边距）的估算高度。
    static let panelFixedHeight: CGFloat = 220
    static let fieldFill = Color.white.opacity(0.08)
    static let hoverFill = Color.white.opacity(0.07)
    static let titleFont = Font.system(size: 13, weight: .semibold)
    static let bodyFont = Font.system(size: 12, weight: .medium)
    static let captionFont = Font.system(size: 11)

    /// 弹窗列表区的高度上限：窗口再矮也不让弹窗伸出窗口（顶栏与上下留白已扣除）。
    static func favoritesListHeight(windowHeight: Double) -> CGFloat {
        let room = CGFloat(windowHeight) - barHeight - gap * 2 - panelFixedHeight
        return min(max(room, listMinHeight), listMaxHeight)
    }
}

enum Copy {
    static func gender(_ gender: Gender?) -> String {
        switch gender {
        case .female: return "女"
        case .male: return "男"
        case .trans: return "跨性别"
        case .couple: return "情侣"
        case nil: return ""
        }
    }

    static func region(_ region: Region) -> String {
        switch region {
        case .asia: return "亚洲"
        case .europeRussia: return "欧洲 / 俄罗斯"
        case .northAmerica: return "北美"
        case .southAmerica: return "南美"
        case .other: return "其他"
        }
    }

    static func source(_ source: FeedSource) -> String {
        switch source {
        case .guess: return "猜你喜欢"
        case .popular: return "热门"
        case .fresh: return "新开播"
        case .favorites: return "收藏"
        }
    }

    static func siteMode(_ mode: SiteMode) -> String {
        switch mode {
        case .chaturbate: return "Chaturbate"
        case .cam4: return "Cam4"
        case .mixed: return "混合"
        }
    }

    static func sourceHelp(_ source: FeedSource) -> String {
        switch source {
        case .fresh: return "在线房间中按开播时间从新到旧排，不是全站最新"
        default: return "切换到「\(Copy.source(source))」"
        }
    }

    static func failure(_ failure: StreamFailure?) -> String {
        switch failure {
        case .geo: return "当前地区不可观看"
        case .privateShow: return "私密表演"
        case .group: return "群组表演"
        case .away: return "暂时离开"
        case .offline: return "已离线"
        case .hidden: return "隐藏中"
        case .password: return "需要房间密码"
        case .unavailable, .none: return "暂时无法播放"
        }
    }
}
