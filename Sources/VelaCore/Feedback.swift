import Foundation

/// 一次反馈折算成的命中 / 落空证据量（Beta 后验的 α、β 增量，未乘来源比例与标签稀有度）。
public struct Feedback: Equatable, Sendable {
    public var hit: Double
    public var miss: Double

    public init(hit: Double, miss: Double) {
        self.hit = hit
        self.miss = miss
    }

    public static let none = Feedback(hit: 0, miss: 0)

    /// 3 秒内划走：大多是快速浏览，只算弱落空。
    public static let skipMiss = 0.3
    /// 停留满 20 秒的命中权重；之后按停留时长的对数增长，每 3 倍时长 +0.5。
    public static let baseHit = 1.0
    static let hitPerTripling = 0.5
    /// 长停留的命中上限（180 秒及以上）。
    public static let maxHit = 2.0
    /// 收藏相当于多少次命中，高于任何停留。
    public static let followHit = 3.0

    /// 由有效停留秒数（实际可见播放时长）得到证据：
    /// <3 秒弱落空 0.3；3 到 20 秒不计；20 秒 1、60 秒 1.5、180 秒及以上 2（对数连续，封顶）。
    public static func grade(watched seconds: TimeInterval) -> Feedback {
        guard seconds.isFinite else { return .none }
        if seconds < Preferences.skipBelow { return Feedback(hit: 0, miss: skipMiss) }
        guard seconds >= Preferences.likeAfter else { return .none }
        let triplings = log(seconds / Preferences.likeAfter) / log(3)
        return Feedback(hit: min(maxHit, baseHit + hitPerTripling * triplings), miss: 0)
    }

    /// 每日命中率的口径：≥20 秒记命中，<3 秒记落空，之间不进统计（nil）。
    /// 权重分级只改变学习强度，不改变「这次算不算对味」的计数。
    public static func dailyOutcome(watched seconds: TimeInterval) -> Bool? {
        if seconds >= Preferences.likeAfter { return true }
        if seconds < Preferences.skipBelow { return false }
        return nil
    }
}
