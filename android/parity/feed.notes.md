# C5 目录与房间列表

行为按 Swift 原样搬。下面是形状和已知的不对齐点。

## 类型落点

- `RoomListDecoder`、`CatalogAPI`、`CatalogWindow`、`PageMerge` → `vela.core.feed`。`CatalogQuery`、`CatalogPage`、`CatalogFailure`、`looksLikeChallenge` 仍在 L0a。
- `SiteSearch`（Submit、Edit、MissRecovery、MissOutcome、SearchEpoch）→ `vela.core.feed`。`normalized` 转调 L0a `SiteSearchText.normalized`，不另写截断。`maxLength` 等于 `SiteSearchText.maxLength`（64）。
- `FeedStore`、`LoadSlot`、`MoveRules`、`PagingState`、`IdleStart`、`StartupGate`、`InfoVisibility` 同包。
- 锚点四个入口都在：`select`、`step(delta, skipping)`、`shouldLoadMore(username, …)`、`promoteAfter(anchor, …)`。另外有 `previewQuery`、`mergePageAfterCurrent`。

## 与 Swift 的差别

- Swift 这些是值类型。Kotlin 用类，方法就地改同一实例。测试里需要两份独立列表时调用 `FeedStore.copy()`（`step` 对拍、`merge` 无新增时的前后比较）。赋值本身不拷贝。
- `LanePaging` 写在 `LaneSelection.swift`，归属 C8，而 C8 依赖 C5，不能反向引用。落点算法复制为 `FeedPaging.landingIndex`，规则与 Swift 相同：先夹紧，跳过名单沿 delta 方向找，方向上没有别的房间就停在原地。C8 应保持同一套规则。
- `FlexInt.value` 改为 `Long?`，对齐 Swift 64 位 `Int`。小数向 0 截断；字符串只接受整数形式（`"12.5"` 为 null）。非有限或超出 `Long` 的小数收成 null，Swift 的 `Int(Double)` 在超出时会陷阱。年龄等 32 位字段走 `FlexInt.toIntExactOrNull()`，超出 `Int` 范围是 null，不截断。`ChatContext` 取年龄时改用这个方法，否则 `Long?` 赋不进 `Room.age`。`start_timestamp` 在目录解码里按 64 位换算已开播秒数；差值放不进 `Room.secondsOnline`（仍是 `Int?`，L0a 的字段）时为 null。
- L0a 笔记里 FlexInt 仍写着 32 位 `Int`。以这次的 `Long?` 为准。
- `FeedCacheTests` 的随机操作只用同一个种子断言「缓存等于全量过滤」。Kotlin `Random` 不是 Swift 的 SplitMix64，序列不必逐位相同。
- `testCachedIndexIsMuchFasterThanFullFilter` 在计时前先把两条路径各跑 50 次，避开 JVM 第一次测量吃掉 JIT。断言仍是缓存耗时的 2 倍小于全量过滤。
- JSON 用 kotlinx.serialization，`ignoreUnknownKeys = true`，对齐 `JSONDecoder` 忽略未知字段。缺 `rooms` 算格式失败。
