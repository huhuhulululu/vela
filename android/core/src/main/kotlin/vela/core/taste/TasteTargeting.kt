package vela.core.taste

import java.time.Instant
import kotlin.math.max
import vela.core.base.model.Room
import vela.core.base.model.lowerRoot
import vela.core.base.site.CatalogQuery

/**
 * 按口味定向拉候选：从画像里挑最喜欢的几个标签，各拉一页 hashtags 定向目录，再与常规候选合并去重。
 */
object TasteTargeting {
    const val maxTags: Int = 3

    /** 证据足够：命中证据（α - 1，已衰减）至少相当于 2 次满 20 秒的停留。 */
    internal const val minHitEvidence: Double = 2.0

    /** 后验均值下限（先验均值 0.5）。 */
    internal const val minMean: Double = 0.55

    /** 稀有度下限：降权后几乎没有区分度的常见标签不选。 */
    internal const val minRarity: Double = 0.3

    /**
     * 最喜欢的至多 [limit] 个标签（小写、不带 #）。
     * 未标「不想看」、证据足够、均值够高；给了 [rarity] 时排除常见标签。
     * 按均值、再按命中证据、再按名字排序。
     */
    fun favoriteTags(
        taste: Taste,
        now: Instant,
        rarity: TagRarity? = null,
        limit: Int = maxTags,
    ): List<String> {
        val picked = ArrayList<Pick>()
        for ((key, belief) in taste.beliefs) {
            if (!key.startsWith("t:") || belief.vetoed) continue
            val posterior = taste.posterior(key, now)
            val mean = posterior.alpha / (posterior.alpha + posterior.beta)
            val evidence = posterior.alpha - 1.0
            if (evidence < minHitEvidence || mean < minMean) continue
            if ((rarity?.weight(key) ?: 1.0) < minRarity) continue
            val name = CatalogQuery.normalizedHashtag(key.drop(2)) ?: continue
            if (Taste.tagKey(name) != key) continue
            picked.add(Pick(name, mean, evidence))
        }
        val ranked = picked.sortedWith { left, right ->
            if (left.mean != right.mean) {
                if (left.mean > right.mean) -1 else 1
            } else if (left.evidence != right.evidence) {
                if (left.evidence > right.evidence) -1 else 1
            } else {
                left.name.compareTo(right.name)
            }
        }
        return ranked.take(max(limit, 0)).map { it.name }
    }

    /**
     * 每个标签一页定向查询（满页 90、offset 0），沿用 [base] 的性别与地区筛选。
     * [base] 已经按标签筛选时返回空。
     */
    fun queries(tags: List<String>, base: CatalogQuery = CatalogQuery()): List<CatalogQuery> {
        if (base.tag != null) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<CatalogQuery>()
        for (raw in tags) {
            val tag = CatalogQuery.normalizedHashtag(raw) ?: continue
            if (!seen.add(tag)) continue
            out.add(
                CatalogQuery(
                    gender = base.gender,
                    region = base.region,
                    tag = tag,
                    limit = CatalogQuery.maxPageSize,
                    offset = 0,
                    newCams = base.newCams,
                    keywords = base.keywords,
                ),
            )
        }
        return out
    }

    /**
     * 常规候选按原顺序在前（重复的只留第一次），定向页里新出现的房间按页序追加在后。
     * 用户名不区分大小写去重。
     */
    fun merging(regular: List<Room>, extra: List<List<Room>>): List<Room> {
        val seen = HashSet<String>()
        val out = ArrayList<Room>()
        for (room in regular) {
            if (seen.add(room.username.lowerRoot())) out.add(room)
        }
        for (page in extra) {
            for (room in page) {
                if (seen.add(room.username.lowerRoot())) out.add(room)
            }
        }
        return out
    }

    private data class Pick(val name: String, val mean: Double, val evidence: Double)
}
