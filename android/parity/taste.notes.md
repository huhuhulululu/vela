# C6a 移植笔记

行为按 Swift 原样搬。下面是 Kotlin 形状，以及这组测试里实际碰到的数值。

## 类型落点

- `Feedback`、`TagRarity`、`BetaSampler`、`Taste`、`Belief`、`DayTally`、`TasteSummary`、`TasteStore`、`TasteTargeting`、`HotTags`、`HotTagCache` → `vela.core.taste`。
- `Taste.tagKey` / `genderKey` 转调 L0a 的 `TasteKeys`。先按 `Prefs.maxNameLength` 截断再小写，与 Swift `Taste.tagKey` 一致。
- `TasteStore.defaultURL(directory)` 要调用方传入目录。文件名仍是 `feed-taste.json`。不读旧版偏好文件，没有 `legacyURL`、`eraseLegacy`、`Taste(migrating:)`。
- 时刻参数用 `java.time.Instant`。证据、停留、`seen` 的值仍是 `Double` 秒。`dayKey` / `summary` 的时区默认是系统时区，对齐 `Calendar.current`；要和 Swift 测试里显式传入的 UTC 日历对齐时传入 `ZoneId`。

## 不移植

- `TasteTests.testMigrationKeepsOldProfileAndSeen`
- `TasteTests.testStoreMigratesOnceThenOwnsItsFile`

两条都在 `skip-taste.tsv`。旧 `Preferences` / `Affinity` / `PreferenceStore` 不在本包。

## 浮点

本包跑过的断言没有出现需要放宽的逐位差异：

- `Feedback.grade` 用 `kotlin.math.ln`，对应 Swift `log`。本组测试拿同一次 `grade` 的结果去比后验，或者比的是能落在二进制里的值（20 秒为 1、60 秒为 1.5、半衰期 `0.5.pow`）。`0.5^10`（70 天）在容差 0.01 内，和 Swift 的 `accuracy` 一样。
- 衰减是 `0.5.pow(max(0, age) / halfLife)`，对应 `pow(0.5, …)`。
- `TagRarity.weight` 用 `kotlin.math.ln`。本组没有单独钉死某个非平凡 idf 的字面量；公式与 Swift 相同。常见标签打满时 idf 为 0，权重就是下限 0.1，这一步是精确的。
- `BetaSampler` 的均匀随机数按 Swift 标准库 `Double.random(in: 0..<1)`：`next()` 的低 53 位乘 `2^-53`，落在 `[0, 1)`。Box-Muller 和 Marsaglia-Tsang 的式子与 Swift 相同，调用 `next()` 的次数和顺序也相同。`ln`、`sqrt`、`cos` 走 JVM libm，不保证与 Darwin libm 逐位相同。本组没有样本向量；C6c 的 `testBetaSamplerStaysInRangeAndMatchesMean` 只要求 4000 次均值落在 0.75 ± 0.02。若那边的带种子排序因 libm 差一两个 ulp 而换序，应记在 recommend 的 notes 里，由编排者决定是放宽容差还是改实现。本包不放宽。

## 其它差异

- 标签和用户名的截断、`key.count` 用 `String.length` / `take`（UTF-16 码元）。Swift `prefix` / `count` 按字素簇。BMP 且无组合字符时一致，和 L0a 的笔记相同。
- `HotTags.normalized` 同样按 UTF-16 长度丢弃超长名字。
- JSON 用 kotlinx.serialization：`encodeDefaults = true`、`explicitNulls = false`、`ignoreUnknownKeys = true`。缺 `beliefs` / `seen` / `daily` / `paused` 时用空画像或 `false`，对齐 `decodeIfPresent`。坏文件和超限文件走 L0b `PreferenceQuarantine`，上限用 L0a `Prefs.maxFileBytes`。
- `CatalogQuery` 在 L0a 是类、字段不可变。`TasteTargeting.queries` 新造一个查询，不改入参。`normalizedHashtag` 仍用 L0a 那一份。
- `HotTags.request()` 返回 `HTTPRequest`。`HTTPHeaders.applyBrowser` 不改入参，Referer 是 `https://chaturbate.com/tags/`。
- 周日的 `DayBar.weekday` 是 1，对齐 Gregorian `Calendar`，不是 `java.time.DayOfWeek` 的周一为 1。
