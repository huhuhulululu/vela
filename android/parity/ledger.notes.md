# C6b 移植笔记

行为按 Swift 原样搬。下面是 Kotlin 形状和已知的不对齐点。

## 类型落点

- `WatchLedger` / `WatchLedgerStore` → `vela.core.ledger`。更新方法返回新值，对应 Swift 值类型。
- `DwellTracker` 是类（测试就地改状态）。`equals` / `hashCode` 比较全部存储字段，对应 `Equatable`。时刻参数用 `Instant`，时长仍是 `Double` 秒。
- `DwellTracker.RelatedPlan` 的 `none` / `idle` / `wait` / `due` 写成 `None` / `Idle` / `Wait` / `Due`。
- `ListOrigin.dwell`、`SuggestEnd`、`SuggestRules`、`EmptyListCopy`、`StreamerAffinity` 同包。
- `SheetLayout` 不移植。`PromptRulesTests.testChallengeSheetHeightFollowsWindow` 在 `skip-ledger.tsv`。
- `WatchLedgerStore.defaultURL(directory)` 要调用方传入目录。`PreferenceStore` 不移植；文件名仍是 `watch-ledger.json`。

## 与 Swift 的差异

- `WatchLedger.key` 先按 `Prefs.maxNameLength` 截断再 `Locale.ROOT` 小写。截断用 `String.take`（UTF-16 码元），和 L0a 一样；Swift `prefix` 按字素簇。BMP 且无组合字符时一致。`capped` 的长度过滤同样用 `String.length`。
- `capped(now)` 的 `now` 不参与筛选，Swift 也没有用它。超过 500 条时按 `lastUsed` 从新到旧保留，排序稳定。
- 空白用 `trimSwiftWhitespace`（Java 空白加上不换行空格）。
- JSON 用 kotlinx.serialization：`encodeDefaults = true`（空 `entries` 也写出，缺这个字段 Swift 会解码失败）、`explicitNulls = false`（空的 `lastVisitStart` 省略，对齐 `encodeIfPresent`）、`ignoreUnknownKeys = true`。`entries` 标了 `@Required`。
- 亲和度用 `kotlin.math.ln1p`。屏蔽集合用 `CurationKeys.key`（只去空白再小写，不截断），账本键用 `WatchLedger.key`（截断），和 Swift 两个函数的差别一致。
- `DwellTests`、`ReviewFixTests` 里的停留用例归 C9，`LoadCommitTests` 的来源归属归 C7。本包把 `shouldSettle`、`relatedPlan`、`ListOrigin` 一并实现了。
