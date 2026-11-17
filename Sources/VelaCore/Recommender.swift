import Foundation

public enum FeedSource: String, CaseIterable, Sendable {
    /// 猜你喜欢（旧版叫「推荐」，存下的设置值是 `recommended`，读取时由 `restore` 映射）。
    case guess
    case popular
    case fresh
    case favorites

    /// 读取存下的来源名：旧版的 `recommended` 映射到「猜你喜欢」，认不出的返回 nil。
    public static func restore(_ raw: String?) -> FeedSource? {
        guard let raw else { return nil }
        return raw == "recommended" ? .guess : FeedSource(rawValue: raw)
    }

    /// 单次请求的条数（站点接口上限 90）：热门沿用小页，推荐与新开播取满一页。
    public var pageLimit: Int {
        switch self {
        case .popular, .favorites: return 40
        case .guess, .fresh: return CatalogQuery.maxPageSize
        }
    }

    /// 新开播改用站点自己的「新开播」筛选（`new_cams=true`），排序仍按开播时长从短到长。
    public var usesNewCams: Bool { self == .fresh }

    /// 一次加载连续取几页：推荐与新开播要更大的候选池（3 页约 270 条）。
    public var pageCount: Int {
        switch self {
        case .popular, .favorites: return 1
        case .guess, .fresh: return 3
        }
    }
}

/// 可注入种子的随机数发生器，测试因此可确定。
public struct SplitMix64: RandomNumberGenerator {
    private var state: UInt64
    public init(seed: UInt64) { state = seed }
    public mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// 排序的附加信号，默认全空（与旧排序一致）。
/// `rarity` 用常规候选集合计算（`TagRarity(rooms:)`），不要把按标签定向拉来的额外页算进去，
/// 否则被定向的标签在候选里变「常见」而被降权；`ledger` 与 `blocked` 用于主播亲和度。
public struct RankSignals: Sendable {
    public var rarity: TagRarity?
    public var ledger: WatchLedger?
    public var blocked: Set<String>

    public init(rarity: TagRarity? = nil, ledger: WatchLedger? = nil, blocked: Set<String> = []) {
        self.rarity = rarity
        self.ledger = ledger
        self.blocked = blocked
    }

    public static let none = RankSignals()
}

/// 本机「猜你喜欢」：按标签的 Beta-Bernoulli 汤普森采样排序。
/// 每个标签（与性别）从自己的后验里抽一个样本当「这次它有多对味」，房间取其标签样本里最高的三个求均值
/// （再混入 25% 的性别样本），加上很小的热度项做平局区分，减去看过降权，收藏加分。
/// 探索来自后验本身的不确定性：证据少的标签方差大、偶尔抽到高值，证据多的标签稳定，因此不再另加随机位。
public enum Recommender {
    static let viewerCeiling = 20_000.0
    static let seenWindow: TimeInterval = 6 * 3_600
    /// 热度权重很小：只在口味样本相近时分先后。
    static let hotWeight = 0.04
    static let seenWeight = 0.6
    /// 收藏的用户加分：大于看过降权的上限，收藏过的主播刚看过也不沉底。
    static let favoriteBoost = 1.0
    static let topTags = 3
    static let genderShare = 0.25
    static let reasonMinHit = 1.0
    static let reasonMinMean = 0.6

    public static func hot(_ room: Room) -> Double {
        min(1, log(1 + Double(max(room.viewers, 0))) / log(1 + viewerCeiling))
    }

    public static func seenPenalty(_ room: Room, taste: Taste, now: Date) -> Double {
        guard let last = taste.lastSeen(room.username) else { return 0 }
        let age = now.timeIntervalSince(last)
        guard age >= 0, age < seenWindow else { return 0 }
        return seenWeight * (1 - age / seenWindow)
    }

    public static func arrange(
        _ rooms: [Room], source: FeedSource, taste: Taste, now: Date, seed: UInt64, favorites: Set<String> = [],
        signals: RankSignals = .none
    ) -> [Room] {
        switch source {
        case .guess: return recommend(rooms, taste: taste, now: now, seed: seed, favorites: favorites, signals: signals)
        case .fresh: return newest(rooms)
        case .popular, .favorites: return rooms
        }
    }

    /// 开播时长从短到长；没有时长字段的排在后面，同值保持原顺序。
    public static func newest(_ rooms: [Room]) -> [Room] {
        rooms.enumerated().sorted { lhs, rhs in
            switch (lhs.element.secondsOnline, rhs.element.secondsOnline) {
            case let (a?, b?): return a != b ? a < b : lhs.offset < rhs.offset
            case (nil, nil): return lhs.offset < rhs.offset
            case (_?, nil): return true
            case (nil, _?): return false
            }
        }.map(\.element)
    }

    /// 同一个种子与输入给出同一个顺序。同一标签在一次排序里只抽一次样本（每个臂一个样本）。
    /// 「不想看」不在这里删房间：可见性由 `RoomQuery.hidden` 管，房间留在目录里，恢复后才排得回来。
    /// `signals` 为空（默认）时与旧排序完全一致；给了稀有度时每个标签样本按权重 w 向 0.5 收缩
    /// （`0.5 + w * (样本 - 0.5)`，常见标签几乎不再区分房间）；给了观看账本时非收藏主播加亲和度（有上限）。
    public static func recommend(
        _ rooms: [Room], taste: Taste, now: Date, seed: UInt64, favorites: Set<String> = [],
        signals: RankSignals = .none
    ) -> [Room] {
        var rng = SplitMix64(seed: seed)
        var draws: [String: Double] = [:]
        func draw(_ key: String) -> Double {
            if let known = draws[key] { return known }
            let p = taste.posterior(key, now: now)
            var value = BetaSampler.sample(alpha: p.alpha, beta: p.beta, using: &rng)
            if let rarity = signals.rarity { value = 0.5 + rarity.weight(key) * (value - 0.5) }
            draws[key] = value
            return value
        }
        func theta(_ room: Room) -> Double {
            let samples = room.tags.prefix(Preferences.maxTagsPerRoom).map { draw(Taste.tagKey($0)) }
            let top = samples.sorted(by: >).prefix(topTags)
            let tagPart = top.isEmpty
                ? BetaSampler.sample(alpha: 1, beta: 1, using: &rng)
                : top.reduce(0, +) / Double(top.count)
            guard let gender = room.gender else { return tagPart }
            return (1 - genderShare) * tagPart + genderShare * draw(Taste.genderKey(gender))
        }
        let scored = rooms.map { room -> (room: Room, score: Double) in
            let boost = bonus(room, favorites: favorites, signals: signals)
            let score = theta(room) + hotWeight * hot(room) - seenPenalty(room, taste: taste, now: now) + boost
            return (room, score)
        }
        return scored.sorted { $0.score != $1.score ? $0.score > $1.score : $0.room.username < $1.room.username }.map(\.room)
    }

    /// 收藏加分与主播亲和度不叠加：收藏的取 `favoriteBoost`，其余取亲和度（上限 `StreamerAffinity.cap` 小于收藏加分）。
    static func bonus(_ room: Room, favorites: Set<String>, signals: RankSignals) -> Double {
        if favorites.contains(room.username.lowercased()) { return favoriteBoost }
        guard let ledger = signals.ledger else { return 0 }
        return StreamerAffinity.score(ledger: ledger, username: room.username, blocked: signals.blocked)
    }

    /// 推荐理由，单行短句：收藏的主播；有足够依据时取该房间命中率最高的 1 到 2 个标签；否则「试试新口味」。
    public static func reason(for room: Room, taste: Taste, favorites: Set<String>, now: Date) -> String {
        if favorites.contains(room.username.lowercased()) { return "你收藏的主播" }
        var liked: [(name: String, mean: Double)] = []
        for tag in room.tags.prefix(Preferences.maxTagsPerRoom) {
            let key = Taste.tagKey(tag)
            guard let belief = taste.beliefs[key], !belief.vetoed else { continue }
            let p = taste.posterior(key, now: now)
            let mean = p.alpha / (p.alpha + p.beta)
            if p.alpha - 1 >= reasonMinHit, mean >= reasonMinMean { liked.append((tag, mean)) }
        }
        let best = liked.sorted { $0.mean != $1.mean ? $0.mean > $1.mean : $0.name < $1.name }.prefix(2)
        guard !best.isEmpty else { return "试试新口味" }
        return "因为你常看 " + best.map(\.name).joined(separator: "、")
    }

    /// 在候选里找尚未看过、标签相近的房间，按相似度取前 limit 个用户名。
    public static func related(to room: Room, among candidates: [Room], taste: Taste, limit: Int = 3) -> [String] {
        let mine = Set(room.tags.map { $0.lowercased() })
        guard !mine.isEmpty, limit > 0 else { return [] }
        let scored: [(room: Room, sim: Double)] = candidates.compactMap { other in
            guard other.username.lowercased() != room.username.lowercased(),
                  taste.lastSeen(other.username) == nil else { return nil }
            let theirs = Set(other.tags.map { $0.lowercased() })
            let shared = mine.intersection(theirs).count
            guard shared > 0 else { return nil }
            let jaccard = Double(shared) / Double(mine.union(theirs).count)
            return (other, jaccard + (other.gender == room.gender ? 0.1 : 0))
        }
        return scored
            .sorted { $0.sim != $1.sim ? $0.sim > $1.sim : ($0.room.viewers, $1.room.username) > ($1.room.viewers, $0.room.username) }
            .prefix(limit)
            .map(\.room.username)
    }
}
