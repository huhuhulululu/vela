# C6c 推荐

行为按 Swift 原样搬。带种子的排序断言与 Swift 测试逐条相同，没有放宽容差。

## 类型落点

- `SplitMix64`、`RankSignals`、`Recommender` → `vela.core.recommend`。
- `Taste.tagKey` / `genderKey` 仍转调 L0a `TasteKeys`。抽样用 C6a `BetaSampler`（`RandomSource.next(): ULong`）。稀有度、账本、亲和度、定向候选分别用已有的 `TagRarity`、`WatchLedger`、`StreamerAffinity`、`TasteTargeting`。
- 时刻参数用 `java.time.Instant`。`seenPenalty` 的时间差是 `Double` 秒，对齐 `Date.timeIntervalSince`。

## 随机数

- `SplitMix64` 用 `ULong`：`state += 0x9E3779B97F4A7C15`，两次 `(z xor (z shr k)) * 常数`，再 `z xor (z shr 31)`。加法和乘法都按 64 位回绕，右移是逻辑移位，与 Swift `UInt64` 的 `&+=` / `&*` / `>>` 逐位相同。
- `testSplitMix64MatchesCanonicalWords` 钉死种子 0、1、42 的前四个输出。这是 Swift 算法本身的输出，不是另一套生成器。
- 均匀数仍走 C6a 的低 53 位乘 `2^-53`。`ln` / `sqrt` / `cos` 是 JVM libm，不保证与 Darwin 逐位相同。本组带种子、并且写死了顺序的断言（热度打破平局、收藏高于亲和度、新开播按时长、关联房间）都不依赖样本的具体浮点值：同一标签只抽一次，平局由热度或用户名决定。统计断言保持 Swift 的原阈值（均值 ±0.02、名次不等式）。本次在 JDK 21 上这些断言全部通过，没有需要编排者放宽的差异。

## 不移植

`FeedRankingTests` 末尾四条只碰旧 `Preferences` / `Affinity` / `PreferenceStore`，计划不移植该结构体和迁移。登记在 `skip-recommend.tsv`：

- `testStoreReadsLegacyFileAndMissingFile`
- `testCorruptFileFallsBackToEmpty`
- `testCapsEvictWeakestWeightsAndOldestSeen`
- `testLegacyFileSizeStaysBoundedOnLoad`

画像上限在 C6a `Taste.capped`，不在这里重做一份。

## 其它

- `FeedReorderTests.swift` 里的 `FeedBlockTests` 整文件归 recommend，三条都对着已有的 `FeedStore` 跑，没有改 feed 的代码。
- `FeedStore` 是类，`promoteAfterCurrent` / `reorderAfterCurrent` 就地修改，和 C5 的笔记一致。
- 收藏集合只把房间用户名做 `Locale.ROOT` 小写再查找，不改集合里的原字符串，对齐 Swift `favorites.contains(username.lowercased())`。
- 排序比较用 `!=` 再比大小，相等时用户名按 UTF-16 码元升序。测试用户名都在 BMP，与 Swift `String.<` 一致。
