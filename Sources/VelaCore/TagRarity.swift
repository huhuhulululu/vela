import Foundation

/// 标签在一组候选房间里的稀有度权重（类似 IDF）：
/// `idf = log((N + 1) / (df + 1))`，再除以「只出现在 1 个房间」时的 idf `log((N + 1) / 2)` 归一化，
/// 夹在 `[floor, 1]`。几乎每个房间都有的标签（teen、18、new）权重接近下限，稀有标签接近 1；
/// 候选里没出现的标签按最稀有处理（权重 1）。候选少于 2 个时没有区分度，全部为 1。
public struct TagRarity: Equatable, Sendable {
    /// 下限：常见标签的影响变小，但不归零（仍保留一点证据与排序作用）。
    public static let floor = 0.1

    public let roomCount: Int
    private let counts: [String: Int]

    /// 用房间列表计算。同一房间里重复的标签只计一次；每房间最多取 `Preferences.maxTagsPerRoom` 个标签。
    public init(rooms: [Room]) {
        var counts: [String: Int] = [:]
        for room in rooms {
            for key in Set(room.tags.prefix(Preferences.maxTagsPerRoom).map(Taste.tagKey)) {
                counts[key, default: 0] += 1
            }
        }
        self.roomCount = rooms.count
        self.counts = counts
    }

    /// 含该标签的候选房间数（`key` 为 `Taste.tagKey`）。
    public func documentFrequency(_ key: String) -> Int { counts[key] ?? 0 }

    /// 归一化后的稀有度权重，`key` 为 `Taste.tagKey`。
    public func weight(_ key: String) -> Double {
        let n = Double(roomCount)
        let top = log((n + 1) / 2)
        guard top > 0 else { return 1 }
        let idf = log((n + 1) / (Double(documentFrequency(key)) + 1))
        return min(1, max(Self.floor, idf / top))
    }
}
