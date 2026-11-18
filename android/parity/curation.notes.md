# C7 收藏移植笔记

行为按 Swift 原样搬。下面是形状和已知的不对齐点。

## 类型落点

- `Curation` / `CurationStore` → `vela.core.curation`。`Curation.key` 转调 L0a `CurationKeys.key`。
- `FavoriteStatus` 与 `ChatContext` 留在 L0a。本包是缓存、计划、拉取、导入导出、`LoadCommit`、`SupersedingRunner`、`FavoriteRetryGate`。
- `FavoritesBoard` / `FavoriteEntry`、`LastBroadcast` / `BioContext` / `BroadcastAgo` / `LastBroadcastCache` / `LastBroadcastFetcher` 同包。
- 时刻用 `java.time.Instant`。时长比较走 `timeIntervalSince1970()` 的 `Double` 秒，和 Swift `Date` 同一套。

## 与 Swift 的差别

- 用户名截断用 `String.take(Prefs.maxNameLength)`，按 UTF-16 码元，和 L0a 一样。Swift `prefix` 按字素簇。ASCII 用户名一致。
- `CurationStore` 不提供 `defaultURL()`。计划要求目录注入：`CurationStore.fileIn(directory)` 得到 `feed-curation.json`。读写失败仍不抛；坏文件走 L0b `PreferenceQuarantine`，上限用 L0a `Prefs.maxFileBytes`。
- JSON 用 kotlinx.serialization。解码 `ignoreUnknownKeys`，对齐 `JSONDecoder` 忽略未知字段；缺 `favorites` 或 `blocked` 算坏文件。写出是紧凑 JSON，键顺序 `favorites`、`blocked`。
- `FavoriteExchange.encode` 开了 pretty print。测试只断言再解码回来的用户名，不比较字节。
- `SupersedingRunner` 不是 MainActor。登记（序号、取消上一批、挂上当前批）在同一把锁里做完，工作跑在独立的 `SupervisorJob`，调用方取消不会拆掉正在跑的那批，对应 Swift 的非结构化 `Task`。同一批仍然先等上一批收尾再开工。
- `FavoriteRetryGate` 在 Swift 里是值类型。这里是可原地修改的类：测试都改同一实例；赋值不会拷贝。
- `LoadCommitTests.testDwellOriginFollowsLoadedListNotSelection` 只断言 `ListOrigin`（`DwellTracker`，C6b）。本分支没有这个类型，登记 `deferred:C9`，没有把 `ListOrigin` 抄进收藏包。

## 测试

已跑：`FavoriteStatusTests`、`FavoritesBoardTests`、`LastBroadcastTests`、`LoadCommitTests` 里除上面那条以外的用例。另外在同包加了收藏值、落盘和 `SupersedingRunner` 的直接测试（Swift 里这些落在 C9 的 `CurationTests` / `ReviewFixTests`）。`CurationTests` 里依赖 `FeedStore` / `Recommender` 的三条仍留给 C9。
