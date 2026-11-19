import Foundation

/// 按口味定向拉候选：从画像里挑最喜欢的几个标签，各拉一页 `hashtags` 定向目录，再与常规候选合并去重。
public enum TasteTargeting {
    public static let maxTags = 3
    /// 证据足够：命中证据（α - 1，已衰减）至少相当于 2 次满 20 秒的停留。
    static let minHitEvidence = 2.0
    /// 后验均值下限（先验均值 0.5）。
    static let minMean = 0.55
    /// 稀有度下限：降权后几乎没有区分度的常见标签不选（定向拉它等于拉普通目录）。
    static let minRarity = 0.3

    /// 选出最喜欢的至多 `limit` 个标签（小写、不带 #）：未标「不想看」、证据足够、均值够高，
    /// 给了 `rarity`（常规候选算出的）时排除常见标签；按均值、再按命中证据、再按名字排序。不够条件时可能少于 2 个甚至为空。
    public static func favoriteTags(taste: Taste, now: Date, rarity: TagRarity? = nil, limit: Int = maxTags) -> [String] {
        var picked: [(name: String, mean: Double, evidence: Double)] = []
        for (key, belief) in taste.beliefs where key.hasPrefix("t:") && !belief.vetoed {
            let p = taste.posterior(key, now: now)
            let mean = p.alpha / (p.alpha + p.beta)
            guard p.alpha - 1 >= minHitEvidence, mean >= minMean,
                  (rarity?.weight(key) ?? 1) >= minRarity,
                  let name = CatalogQuery.normalizedHashtag(String(key.dropFirst(2))),
                  Taste.tagKey(name) == key else { continue }
            picked.append((name, mean, p.alpha - 1))
        }
        let ranked = picked.sorted {
            $0.mean != $1.mean ? $0.mean > $1.mean : ($0.evidence != $1.evidence ? $0.evidence > $1.evidence : $0.name < $1.name)
        }
        return Array(ranked.prefix(max(0, limit)).map(\.name))
    }

    /// 每个标签一页定向查询（满页 90、offset 0），沿用 `base` 的性别与地区筛选。
    /// `base` 已经按标签筛选时返回空：用户自己选了标签，不再另拉。
    public static func queries(for tags: [String], base: CatalogQuery = CatalogQuery()) -> [CatalogQuery] {
        guard base.tag == nil else { return [] }
        var seen = Set<String>()
        return tags.compactMap { raw in
            guard let tag = CatalogQuery.normalizedHashtag(raw), seen.insert(tag).inserted else { return nil }
            var query = base
            query.tag = tag
            query.limit = CatalogQuery.maxPageSize
            query.offset = 0
            return query
        }
    }

    /// 合并：常规候选按原顺序在前（重复的只留第一次），定向页里新出现的房间按页序追加在后。
    /// 用户名不区分大小写去重。排序（`Recommender.recommend`）对整个结果重新打分，新加的房间一起参与。
    public static func merging(_ regular: [Room], extra pages: [[Room]]) -> [Room] {
        var seen = Set<String>()
        var out: [Room] = []
        for room in regular + pages.joined() where seen.insert(room.identity).inserted {
            out.append(room)
        }
        return out
    }
}
