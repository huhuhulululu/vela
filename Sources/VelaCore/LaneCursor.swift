import Foundation

/// 一路的身份。macOS 只有主路；iOS 竖屏再按路数加副路、第三路（最多 3 路）。
/// 顺序固定为 primary → secondary → tertiary，替补、压紧与日志都按这个顺序。
public enum LaneID: Equatable, Hashable, Sendable, CaseIterable {
    case primary
    case secondary
    case tertiary

    /// 路数上限。
    public static let maxCount = 3

    /// 按路数取前 N 路（夹紧到 1...3）。
    public static func active(count: Int) -> [LaneID] {
        Array(allCases.prefix(min(max(count, 1), maxCount)))
    }

    /// 生命周期、画中画、音频、结算用的另一套路身份（一一对应）。
    public var role: PlaybackLaneRole {
        switch self {
        case .primary: return .primary
        case .secondary: return .secondary
        case .tertiary: return .tertiary
        }
    }

    /// 日志标签（与 `PlaybackLaneRole.label` 相同）。
    public var label: String { role.label }
}

/// 一路在可见列表里的位置，以及这路自己的移动方向。
/// 自动跳过沿 `direction`，不借用另一路。
public struct LaneCursor: Equatable, Sendable {
    public var username: String?
    /// +1 向列表后方，-1 向列表前方。
    public var direction: Int
    /// 这一路上一次划走的房间。向后划回它不受「最近划走」限制。
    public var previous: String?

    public init(username: String? = nil, direction: Int = 1, previous: String? = nil) {
        self.username = username
        self.previous = previous
        self.direction = direction < 0 ? -1 : 1
    }

    /// 自动跳过的步长：这路自己的方向。
    public var autoDelta: Int { direction < 0 ? -1 : 1 }
}

/// 竖屏多格的布局（ios-triple 计划 §视图、修订 v2 M12）：N 格上下等高叠放，格间距与双窗一致。
public enum LaneLayout {
    public static let gap: Double = 2
    /// 三格时每格的最低高度（点）；放不下就最多开 2 路。
    public static let minimumPaneHeight: Double = 160

    /// `(H − (N−1)×间距) / N`，至少 1 点。
    public static func paneHeight(stage height: Double, lanes: Int) -> Double {
        let count = Double(max(lanes, 1))
        return max((height - (count - 1) * gap) / count, 1)
    }

    public static func fitsThreeLanes(stageHeight: Double) -> Bool {
        paneHeight(stage: stageHeight, lanes: 3) >= minimumPaneHeight
    }
}
