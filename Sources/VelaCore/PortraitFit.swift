import Foundation

/// 竖屏直播自动用「完整画面」：只对当前这个房间生效，离开后恢复用户的全局铺满设置，不改全局设置值。
/// 判定用播放器出画面时的视频尺寸（不另发请求）。
public struct PortraitFit: Equatable, Sendable {
    /// 高至少比宽多这个比例才算竖屏，接近正方形的不算。
    public static let margin = 1.02

    /// 自动用完整画面的房间（小写用户名）。
    public private(set) var room: String?
    /// 用户在这个房间里手动撤掉了自动完整画面：同一房间再出画面（重试、换档）不再自动套用。
    public private(set) var dismissed: String?

    public init() {}

    public static func isPortrait(width: Double, height: Double) -> Bool {
        guard width > 0, height > 0, width.isFinite, height.isFinite else { return false }
        return height > width * margin
    }

    /// 某个房间出画面了：竖屏就记下它，横屏或尺寸未知时撤掉它之前的记录。换到别的房间时清掉手动撤销。
    public mutating func noteReady(_ username: String, width: Double, height: Double) {
        let key = username.lowercased()
        if dismissed != key { dismissed = nil }
        if Self.isPortrait(width: width, height: height) {
            if dismissed != key { room = key }
        } else if room == key {
            room = nil
        }
    }

    /// 当前房间实际用不用铺满：全局铺满且当前房间被判为竖屏时改用完整画面。
    public func fill(global: Bool, current: String?) -> Bool {
        guard global, let room, let current else { return global }
        return current.lowercased() != room
    }

    /// 用户按了「铺满 / 完整画面」切换：正处在自动完整画面里时只撤掉这次自动（返回 true，不动全局设置）；
    /// 否则返回 false，由调用方照常切换全局设置。
    public mutating func dismiss(global: Bool, current: String?) -> Bool {
        guard fill(global: global, current: current) != global, let current else { return false }
        dismissed = current.lowercased()
        room = nil
        return true
    }
}
