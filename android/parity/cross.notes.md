# C9 跨组测试笔记

行为按 Swift 原样搬。下面是形状差异，不是自行改过的语义。

## 组 cross 的用例

类名与 Swift 文件名一致，方法同名，`split-cross.tsv` 不重复登记：

- `ReviewFixTests`：收藏目录时效、屏蔽与允许名单、reveal、撤销不想看、当前位置之后重排、同名换源结算。
- `DwellTests`、`VetoVisibilityTests`、`FileLimitTests`、`SearchFix13Tests`。
- `CurationTests` 里依赖 `FeedStore` / `Recommender` 的四条写在 `vela.core.cross`。其余十一条本来就在 `vela.core.curation.CurationTests`，类名后缀相同，parity 按方法名对上。

`ReviewFixTests.swift` 开头的 `SupersedingRunnerTests` 两条（并发只有一批、在途标记）C7 已用同名方法落在 `vela.core.curation.SupersedingRunnerTests`。本包不再复制。没有 `ReviewFixTests` 类承载这两条时，parity 按方法名回落到那两个用例。

## deferred:C9

`split-cross.tsv` 把各组留下的 `deferred:C9` 指到本包真正执行的用例：

- L0a `EndpointBackoffTests` 八条：流地址、目录、收藏、上次开播、取消等待文案。
- L0b `PreferenceQuarantineTests` 七条：`TasteStore`、`CurationStore`、`WatchLedgerStore` 的隔离与只读。
- C2 `PlaybackRefreshTests` 三条：`PagingState` 回滚、`FeedStore.maxBlankPages`。
- C3 `ChallengeGateTests` 五条：`StreamDecoder.decode` 与 `CatalogAPI.fetch`。
- C7 `LoadCommitTests.testDwellOriginFollowsLoadedListNotSelection`：`ListOrigin.dwell`。

`PiPPolicyTests` 的三条音频断言 C3 已经改到 `PipAudioOutput`，没有 deferred，本包不动。

## 与 Swift 的差别

- `Preferences` 结构体和 `PreferenceStore` 不移植。`FileLimitTests.testOversizedFilesLoadAsEmpty` 的偏好文件半段改走 `TasteStore`（同一 `Prefs.maxFileBytes` 上限，超限当空画像）。收藏半段仍是 `CurationStore`。
- `testOversizedKeysDroppedOnLoad` 对 `Taste.capped`：信念键长于 `maxNameLength + 2`、看过键长于 `maxNameLength` 的丢掉。Swift 测的是不移植的 `Preferences.capped`。
- `TasteStore` 没有 `legacyURL`。`testCorruptTasteDoesNotFallBackToLegacy` 断言坏文件被隔离、旁边的 `legacy.json` 原样留着、读出来是空画像。
- `maxFileBytes` 收成 L0a `Prefs.maxFileBytes`。`vela.core.base.io.maxFileBytes` 直接引用它。`testMaxFileBytesMatchesL0aPrefs` 断言两边是同一个值。这不是 Swift 用例。
- 时刻用 `Instant`。`FavoriteCache.ttl + 10` 秒用毫秒相加，避免 `Double` 秒再换算。

## 中继 skip

`RelayFeedTests`、`RelayWaitTests`、`RelaySourceTableTests` 和 `PlaybackFlowFixTests` 的 6 条采纳，C2 已逐条写进 `skip-hls.tsv`，理由含 `replaced-by:`。`RelayMultiSourceTests` 9 条在全局 `skip.tsv`，`replaced-by` 指向 `LaneDataSourceTest` 的同名方法。`testParseReadsDiscontinuityAndLastURI` 在 L0b。本包没有改这些表。P2 的 `LaneDataSource` 不在本包依赖里，表里的替代测试名保持 C2 / 00 已写的那样。
