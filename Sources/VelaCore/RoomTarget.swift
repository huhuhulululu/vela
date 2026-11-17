import Foundation

/// 点下去的那一路、那一间、那一次播放。异步完成时核对，对不上就放弃。
/// macOS 上恒为主路。
public struct RoomTarget: Equatable, Sendable {
    public var laneID: LaneID
    public var username: String
    public var generation: Int

    public init(laneID: LaneID, username: String, generation: Int) {
        self.laneID = laneID
        self.username = username
        self.generation = generation
    }

    public static func primary(username: String, generation: Int) -> RoomTarget {
        RoomTarget(laneID: .primary, username: username, generation: generation)
    }

    /// 用户名不分大小写；路或代次不同都算对不上。
    public func matches(laneID: LaneID, username: String, generation: Int) -> Bool {
        self.laneID == laneID
            && self.username.lowercased() == username.lowercased()
            && self.generation == generation
    }
}
