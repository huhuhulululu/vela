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
