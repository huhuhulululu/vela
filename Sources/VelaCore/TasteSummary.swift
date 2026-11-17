import Foundation

/// 口味面板里的一行：标签（或性别）、后验均值、命中次数。
public struct TasteRow: Identifiable, Equatable, Sendable {
    public var key: String
    public var name: String
    public var mean: Double
    public var hits: Int
    public var vetoed: Bool
    public var id: String { key }
}

/// 最近一天的命中与落空，`weekday` 取 Calendar 的 1（周日）到 7。
public struct DayBar: Equatable, Sendable {
    public var weekday: Int
    public var hit: Int
    public var miss: Int
    public var total: Int { hit + miss }
}

public struct TasteSummary: Equatable, Sendable {
    public var liked: [TasteRow]
    public var disliked: [TasteRow]
    public var genders: [TasteRow]
    /// 画像里标签的个数（含被标成「不想看」的）：太少时面板顶部显示冷启动整理。
    public var tagCount: Int
    public var week: [DayBar]
    /// 最近 7 天命中率；样本不足 `Taste.minRateEvents` 时为 nil。
    public var hitRate: Double?
}

extension Taste {
    public static let minRateEvents = 10
    public static let coldStartTags = 5
    static let maxLikedRows = 30
    /// 学到的「不想看」：证据够多且命中率很低。
    static let learnedDislikeEvidence = 3.0
    static let learnedDislikeMean = 0.25

    public func summary(now: Date, calendar: Calendar = .current) -> TasteSummary {
        var liked: [TasteRow] = [], disliked: [TasteRow] = [], genders: [TasteRow] = []
        var tagCount = 0
        for (key, belief) in beliefs {
            let p = posterior(key, now: now)
            let row = TasteRow(
                key: key, name: String(key.dropFirst(2)), mean: p.alpha / (p.alpha + p.beta),
                hits: belief.hits, vetoed: belief.vetoed
            )
            if key.hasPrefix("g:") { genders.append(row); continue }
            tagCount += 1
            if belief.vetoed || (p.beta - 1 >= Self.learnedDislikeEvidence && row.mean <= Self.learnedDislikeMean) {
                disliked.append(row)
            } else if p.alpha - 1 >= 0.5, row.mean >= 0.5 {
                liked.append(row)
            }
        }
        let byMean: (TasteRow, TasteRow) -> Bool = {
            $0.mean != $1.mean ? $0.mean > $1.mean : ($0.hits != $1.hits ? $0.hits > $1.hits : $0.name < $1.name)
        }
        let week = Self.week(daily, now: now, calendar: calendar)
        let hit = week.reduce(0) { $0 + $1.hit }, total = week.reduce(0) { $0 + $1.total }
        return TasteSummary(
            liked: Array(liked.sorted(by: byMean).prefix(Self.maxLikedRows)),
            disliked: disliked.sorted { $0.name < $1.name },
            genders: genders.sorted(by: byMean),
            tagCount: tagCount,
            week: week,
            hitRate: total >= Self.minRateEvents ? Double(hit) / Double(total) : nil
        )
    }

    /// 含今天的最近 7 天，旧的在前。
    static func week(_ daily: [String: DayTally], now: Date, calendar: Calendar) -> [DayBar] {
        (0..<7).reversed().compactMap { back in
            guard let day = calendar.date(byAdding: .day, value: -back, to: now) else { return nil }
            let t = daily[dayKey(day, calendar: calendar)] ?? DayTally()
            return DayBar(weekday: calendar.component(.weekday, from: day), hit: t.hit, miss: t.miss)
        }
    }

    /// 冷启动整理用：候选房间里最常见、画像里还没有的标签（按出现次数，同数按字母）。
    public func suggestedTags(from rooms: [Room], limit: Int = 12) -> [String] {
        var counts: [String: (name: String, count: Int)] = [:]
        for room in rooms {
            for tag in Set(room.tags.prefix(Preferences.maxTagsPerRoom).map { $0.lowercased() }) where !tag.isEmpty {
                guard tag.count <= Preferences.maxNameLength, beliefs[Self.tagKey(tag)] == nil else { continue }
                counts[tag, default: (tag, 0)].count += 1
            }
        }
        let ranked = counts.values.sorted { $0.count != $1.count ? $0.count > $1.count : $0.name < $1.name }
        return ranked.prefix(limit).map(\.name)
    }
}
