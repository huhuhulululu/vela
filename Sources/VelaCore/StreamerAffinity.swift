import Foundation

/// 主播亲和度：按观看账本里该主播的累计观看时长与有效次数算一个有上限的加分（排序用）。
/// 时长部分对数饱和（约 60 分钟到顶），次数部分线性饱和（5 次到顶），按 7:3 混合后乘上限 `cap`。
/// 上限低于收藏加分（`Recommender.favoriteBoost`），收藏的主播仍排更前；屏蔽的、没有记录的为 0。
public enum StreamerAffinity {
    public static let cap = 0.5
    static let saturationSeconds: TimeInterval = 3_600
    static let saturationVisits = 5
    static let timeShare = 0.7

    /// `blocked` 为 `Curation.blockedSet`（`Curation.key` 规范化的用户名）。
    public static func score(ledger: WatchLedger, username: String, blocked: Set<String> = []) -> Double {
        let key = WatchLedger.key(username)
        guard !key.isEmpty, !blocked.contains(Curation.key(username)), let entry = ledger.entries[key] else { return 0 }
        let minutes = max(0, entry.totalSeconds) / 60
        let time = min(1, log1p(minutes) / log1p(saturationSeconds / 60))
        let visits = min(1, Double(max(0, entry.visitCount)) / Double(saturationVisits))
        return cap * (timeShare * time + (1 - timeShare) * visits)
    }
}
