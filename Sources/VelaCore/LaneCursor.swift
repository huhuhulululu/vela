import Foundation

/// 一路的身份。macOS 只有主路；iOS 双窗再加副路。
public enum LaneID: Equatable, Hashable, Sendable {
    case primary
    case secondary
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
