import Foundation

/// 一个标签（或性别）的命中与落空证据。Beta-Bernoulli：后验是 Beta(1 + hit, 1 + miss)。
/// `hit` / `miss` 是超出先验的证据量，读取时按半衰期向 0 衰减；`hits` / `misses` 是不衰减的原始次数，只用于显示。
public struct Belief: Codable, Equatable, Sendable {
    public var hit = 0.0
    public var miss = 0.0
    public var at: TimeInterval = 0
    public var hits = 0
    public var misses = 0
    /// 用户明确标了「不想看」：不衰减，排序时直接过滤。
    public var vetoed = false
}

/// 某一天「猜你喜欢」里的命中与落空次数。
public struct DayTally: Codable, Equatable, Sendable {
    public var hit = 0
    public var miss = 0
}

/// 本机口味画像：按标签与性别的命中 / 落空统计、最近看过、每日命中率。
/// 纯值类型，所有更新返回新值，不上传任何数据。
public struct Taste: Codable, Equatable, Sendable {
    public static let halfLife: TimeInterval = 7 * 86_400
    public static let maxBeliefs = 500
    public static let maxDays = 30
    /// 手动加强一次相当于多少次命中。
    public static let boostStep = 3.0
    /// 「不感兴趣」相当于多少次落空。
    public static let rejectStep = 3.0
    /// 收藏相当于多少次命中（与反馈分级的档位一致，见 `Feedback.followHit`）。
    public static let followStep = Feedback.followHit
    /// 性别是粗粒度维度，证据按此比例折算，免得压过标签。
    static let genderScale = 0.5
    /// 非「猜你喜欢」来源的停留也学习，但证据减半（那里的选择不是画像推荐出来的）。
    static let otherSourceScale = 0.5

    public private(set) var beliefs: [String: Belief]
    public private(set) var seen: [String: TimeInterval]
    public private(set) var daily: [String: DayTally]
    public private(set) var paused: Bool

    public init(
        beliefs: [String: Belief] = [:], seen: [String: TimeInterval] = [:],
        daily: [String: DayTally] = [:], paused: Bool = false
    ) {
        self.beliefs = beliefs
        self.seen = seen
        self.daily = daily
        self.paused = paused
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        beliefs = try c.decodeIfPresent([String: Belief].self, forKey: .beliefs) ?? [:]
        seen = try c.decodeIfPresent([String: TimeInterval].self, forKey: .seen) ?? [:]
        daily = try c.decodeIfPresent([String: DayTally].self, forKey: .daily) ?? [:]
        paused = try c.decodeIfPresent(Bool.self, forKey: .paused) ?? false
    }

    public static func tagKey(_ tag: String) -> String { Preferences.tagKey(String(tag.prefix(Preferences.maxNameLength))) }
    public static func genderKey(_ gender: Gender) -> String { Preferences.genderKey(gender) }

    // MARK: 读取

    private static func decay(_ age: TimeInterval) -> Double { pow(0.5, max(0, age) / halfLife) }

    /// 衰减到 now 之后的 Beta 参数（先验 α=β=1）。
    public func posterior(_ key: String, now: Date) -> (alpha: Double, beta: Double) {
        guard let b = beliefs[key] else { return (1, 1) }
        let f = b.vetoed ? 1 : Self.decay(now.timeIntervalSince1970 - b.at)
        return (1 + b.hit * f, 1 + b.miss * f)
    }

    public func mean(_ key: String, now: Date) -> Double {
        let p = posterior(key, now: now)
        return p.alpha / (p.alpha + p.beta)
    }

    public func isVetoed(_ key: String) -> Bool { beliefs[key]?.vetoed ?? false }

    /// 所有「不想看」的口味键，供列表可见性过滤使用。
    public var vetoedKeys: Set<String> { Set(beliefs.filter { $0.value.vetoed }.keys) }

    public func isVetoed(_ room: Room) -> Bool {
        room.tags.prefix(Preferences.maxTagsPerRoom).contains { isVetoed(Self.tagKey($0)) }
            || (room.gender.map { isVetoed(Self.genderKey($0)) } ?? false)
    }

    public func lastSeen(_ username: String) -> Date? {
        seen[Self.clipped(username)].map(Date.init(timeIntervalSince1970:))
    }

    private static func clipped(_ name: String) -> String { String(name.lowercased().prefix(Preferences.maxNameLength)) }

    // MARK: 更新

    public func marking(seen username: String, now: Date) -> Taste {
        var next = self
        next.seen[Self.clipped(username)] = now.timeIntervalSince1970
        next.seen = Self.trimmed(next.seen, now: now)
        return next
    }

    /// 离开房间时记账（分级）：由有效停留秒数经 `Feedback.grade` 得到命中 / 落空权重，
    /// 3 秒内划走是弱落空（0.3），20 秒 1、60 秒 1.5、180 秒及以上 2，3 到 20 秒不计。
    /// `rarity` 为当前候选集合的标签稀有度：每个标签的证据乘以它的稀有度权重，几乎每个房间都有的标签只得到一小份。
    /// 每日命中率口径不变：命中以 ≥20 秒计、落空以 <3 秒计，只统计「猜你喜欢」。
    /// 学习与每日统计仍共用这一个秒数。要分开时用 `recording(learnSeconds:daily:)`。
    public func recording(
        room: Room, watched seconds: TimeInterval, source: FeedSource, now: Date, rarity: TagRarity? = nil
    ) -> Taste {
        recording(room: room, learnSeconds: seconds, daily: seconds, source: source, now: now, rarity: rarity)
    }

    /// 学习秒数与每日统计分开记。双窗里非焦点时间折进 `learnSeconds`，每日命中率只看 `daily`。
    /// 某一边传 nil 表示这一边不记（仍标记已看过）。两边传入同一个秒数时，结果与 `recording(room:watched:source:now:)` 相同。
    public func recording(
        room: Room, learnSeconds: TimeInterval?, daily dailySeconds: TimeInterval?,
        source: FeedSource, now: Date, rarity: TagRarity? = nil
    ) -> Taste {
        settling(
            room: room, feedback: learnSeconds.map { Feedback.grade(watched: $0) } ?? .none, dwell: dailySeconds,
            source: source, now: now, rarity: rarity
        )
    }

    private func settling(
        room: Room, feedback: Feedback, dwell: TimeInterval?, source: FeedSource, now: Date, rarity: TagRarity?
    ) -> Taste {
        let base = marking(seen: room.username, now: now)
        guard !paused else { return base }
        var next = base
        if feedback != .none {
            let scale = source == .guess ? 1.0 : Self.otherSourceScale
            next = base.applying(room, hit: feedback.hit * scale, miss: feedback.miss * scale, now: now, rarity: rarity)
        }
        if source == .guess, let dwell, let isHit = Feedback.dailyOutcome(watched: dwell) { next.tally(isHit: isHit, now: now) }
        return next
    }

    /// 收藏：比停留更强的命中信号（3 次命中）；`rarity` 同 `recording(room:watched:)`。
    public func following(room: Room, now: Date, rarity: TagRarity? = nil) -> Taste {
        paused ? self : applying(room, hit: Self.followStep, miss: 0, now: now, rarity: rarity)
    }

    /// 对当前房间不感兴趣：强落空，也标记看过。
    public func rejecting(room: Room, source: FeedSource, now: Date) -> Taste {
        let base = marking(seen: room.username, now: now)
        guard !paused else { return base }
        var next = base.applying(room, hit: 0, miss: Self.rejectStep, now: now)
        if source == .guess { next.tally(isHit: false, now: now) }
        return next
    }

    /// 房间的标签与性别各按自己的比例加证据，并累计显示用的次数。给了 `rarity` 时标签证据再乘稀有度权重。
    private func applying(_ room: Room, hit: Double, miss: Double, now: Date, rarity: TagRarity? = nil) -> Taste {
        var next = self
        for tag in room.tags.prefix(Preferences.maxTagsPerRoom) {
            let key = Self.tagKey(tag)
            let w = rarity?.weight(key) ?? 1
            next.bump(key, hit: hit * w, miss: miss * w, counts: true, now: now)
        }
        if let gender = room.gender {
            next.bump(Self.genderKey(gender), hit: hit * Self.genderScale, miss: miss * Self.genderScale, counts: true, now: now)
        }
        next.beliefs = Self.trimmed(next.beliefs, now: now)
        return next
    }

    /// 先把旧证据衰减到 now 再累加（「不想看」的不衰减）；`counts` 决定是否累计原始命中 / 落空次数。
    private mutating func bump(_ key: String, hit: Double, miss: Double, counts: Bool, now: Date) {
        var b = beliefs[key] ?? Belief()
        if !b.vetoed, beliefs[key] != nil {
            let f = Self.decay(now.timeIntervalSince1970 - b.at)
            b.hit *= f
            b.miss *= f
        }
        b.hit += hit
        b.miss += miss
        b.at = now.timeIntervalSince1970
        if counts, hit > 0 { b.hits += 1 }
        if counts, miss > 0 { b.misses += 1 }
        beliefs[key] = b
    }

    private mutating func tally(isHit: Bool, now: Date) {
        let key = Self.dayKey(now)
        var t = daily[key] ?? DayTally()
        if isHit { t.hit += 1 } else { t.miss += 1 }
        daily[key] = t
        if daily.count > Self.maxDays {
            for old in daily.keys.sorted().dropLast(Self.maxDays) { daily[old] = nil }
        }
    }

    // MARK: 面板操作

    /// 手动加强：先撤销「不想看」，再衰减并加一个固定值。
    public func boosting(_ key: String, now: Date) -> Taste {
        var next = self
        next.beliefs[key]?.vetoed = false
        next.bump(key, hit: Self.boostStep, miss: 0, counts: false, now: now)
        next.beliefs = Self.trimmed(next.beliefs, now: now)
        return next
    }

    public func vetoing(_ key: String, now: Date) -> Taste {
        var next = self
        var b = beliefs[key] ?? Belief()
        b.vetoed = true
        b.at = now.timeIntervalSince1970
        next.beliefs[key] = b
        next.beliefs = Self.trimmed(next.beliefs, now: now)
        return next
    }

    /// 恢复：撤销「不想看」。明确标的取消过滤；学出来的不想看去掉落空证据。命中证据保留，
    /// 之前的强弱不丢；没有命中证据时画像里不留空条目。
    public func restoring(_ key: String, now: Date) -> Taste {
        guard var b = beliefs[key] else { return self }
        let f = b.vetoed ? 1 : Self.decay(now.timeIntervalSince1970 - b.at)
        b.hit *= f
        b.miss = 0
        b.misses = 0
        b.vetoed = false
        b.at = now.timeIntervalSince1970
        var next = self
        next.beliefs[key] = b.hit < 0.05 && b.hits == 0 ? nil : b
        return next
    }

    public func removing(_ key: String) -> Taste {
        var next = self
        next.beliefs[key] = nil
        return next
    }

    /// 同步投影专用（docs/plans/sync.md 第 6.5 节）：只改 `vetoed`，不动证据、`at` 与计数，不触发学习。
    /// 设为 false 且证据全为 0 时删除条目；对同一文档重复投影结果不变。
    public func settingSyncedVeto(_ key: String, _ vetoed: Bool) -> Taste {
        var next = self
        if vetoed {
            var b = beliefs[key] ?? Belief()
            b.vetoed = true
            next.beliefs[key] = b
        } else if var b = beliefs[key] {
            b.vetoed = false
            let empty = b.hit == 0 && b.miss == 0 && b.hits == 0 && b.misses == 0
            next.beliefs[key] = empty ? nil : b
        }
        return next
    }

    public func settingPaused(_ value: Bool) -> Taste {
        var next = self
        next.paused = value
        return next
    }

    /// 重置画像：清掉全部标签与性别统计和每日命中率，保留最近看过与暂停设置。
    public func resettingProfile() -> Taste { Taste(seen: seen, paused: paused) }

    /// 清除全部偏好数据：连最近看过一起清，只保留暂停设置。
    public func erased() -> Taste { Taste(paused: paused) }

    // MARK: 迁移与上限

    /// 从旧版 `Preferences` 迁移：正权重折成命中、负权重折成落空，旧画像不清零。
    /// 旧版每次命中给标签 +0.5（性别 +0.3）、落空给一半，这里按相同比例反推证据量。
    public init(migrating old: Preferences) {
        var out: [String: Belief] = [:]
        for (key, entry) in old.weights {
            let gender = key.hasPrefix("g:")
            let unit = gender ? 0.3 : 0.5
            let scale = gender ? Self.genderScale : 1
            var b = Belief()
            b.at = entry.at
            if entry.value > 0 {
                b.hit = entry.value / unit * scale
                b.hits = Int(b.hit.rounded())
            } else {
                b.miss = -entry.value / (unit / 2) * scale
                b.misses = Int(b.miss.rounded())
            }
            out[key] = b
        }
        self.init(beliefs: out, seen: old.seen)
    }

    public func capped(now: Date) -> Taste {
        let keyLimit = Preferences.maxNameLength + 2
        var next = self
        next.beliefs = Self.trimmed(beliefs.filter { $0.key.count <= keyLimit }, now: now)
        next.seen = Self.trimmed(seen.filter { $0.key.count <= Preferences.maxNameLength }, now: now)
        if daily.count > Self.maxDays {
            let kept = daily.sorted { $0.key > $1.key }.prefix(Self.maxDays)
            next.daily = Dictionary(uniqueKeysWithValues: kept.map { ($0.key, $0.value) })
        }
        return next
    }

    /// 超出上限时保留证据最强的标签（用户标过「不想看」的优先保留）；强度相同按键的 UTF-8 字节升序，结果确定。
    private static func trimmed(_ beliefs: [String: Belief], now: Date) -> [String: Belief] {
        guard beliefs.count > maxBeliefs else { return beliefs }
        func strength(_ b: Belief) -> Double {
            b.vetoed ? .infinity : (b.hit + b.miss) * decay(now.timeIntervalSince1970 - b.at)
        }
        let ranked = beliefs.map { (key: $0.key, value: $0.value, strength: strength($0.value)) }
        let kept = ranked.sorted {
            $0.strength != $1.strength ? $0.strength > $1.strength : $0.key.utf8.lexicographicallyPrecedes($1.key.utf8)
        }.prefix(maxBeliefs)
        return Dictionary(uniqueKeysWithValues: kept.map { ($0.key, $0.value) })
    }

    private static func trimmed(_ seen: [String: TimeInterval], now: Date) -> [String: TimeInterval] {
        let cutoff = now.timeIntervalSince1970 - Preferences.seenRetention
        var live = seen.filter { $0.value >= cutoff }
        if live.count > Preferences.maxSeen {
            live = Dictionary(uniqueKeysWithValues: live.sorted { $0.value > $1.value }.prefix(Preferences.maxSeen).map { ($0.key, $0.value) })
        }
        return live
    }

    public static func dayKey(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }
}
