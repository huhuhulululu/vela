# L0a 移植笔记

行为按 Swift 原样搬，没有自行改语义。下面是 Kotlin 形状和已知的不对齐点。

## 类型落点

- `Models.swift` → `vela.core.base.model`（`Gender`、`Region`、`ShowState`、`Room`、`ViewerCount`、`BroadcastClock`、`RoomQuery`、`FlexInt`、`secureImageURL`、`dedupeRooms`）。
- `Preferences` 的常量 → `Prefs`。`maxTagsPerRoom` 在 Swift 里是模块内可见，这里公开，后面各组都在 `:core` 里用。
- `Taste.tagKey` / `genderKey`（以及它们转调的 `Preferences.tagKey` / `genderKey`）→ `TasteKeys`。`tagKey` 先按 `Prefs.maxNameLength` 截断再加 `t:` 前缀并小写，与 `Taste.tagKey` 等价，而不是未截断的 `Preferences.tagKey`。C6a 的 `Taste.tagKey` 应转调 `TasteKeys.tagKey`。
- `Curation.key` → `CurationKeys.key`。C7 的 `Curation` 转调它。
- `FeedSource` 留在 `vela.core.base.model`，`pageLimit` 读 `CatalogQuery.maxPageSize`。
- `HTTP.swift` 与 `EndpointBackoff.swift` → `vela.core.base.net`。没有 `URLRequest`，用 `HTTPRequest`（url、method、headers、body、`timeoutSeconds`）。`HTTPHeaders.applyBrowser` 返回新请求，不改入参。
- `looksLikeChallenge`、`CatalogQuery`、`CatalogPage`、`CatalogFailure`、`FavoriteStatus`、`ChatContext` → `vela.core.base.site`。
- `SiteSearch.normalized` → `SiteSearchText.normalized`。截断长度 `SiteSearchText.maxLength = 64`，与 Swift `SiteSearch.maxLength` 相同。C5 的 `SiteSearch` 应转调 `SiteSearchText`，不要另写一套截断。
- `Instant.timeIntervalSince1970()` 在 `vela.core.base.model`，对应 Swift `Date.timeIntervalSince1970`：`epochSecond + nano/1e9`，负时刻也成立（`Instant` 的 nano 恒为非负）。

不在本包：`Preferences` 结构体、`Affinity`、`PreferenceStore` 与迁移；`RoomListDecoder` / `CatalogAPI` / `CatalogWindow` / `PageMerge`（C5）；`SiteSearch` 除 `normalized` 以外的部分（C5）；`FavoriteCache`、`KnownCatalog`、`FavoritePlan`、`FavoriteFetcher`（C7）。

## Codable → serialization

- `Gender`、`Region` 用 kotlinx.serialization。线上的值是 raw value（`f`/`m`/`t`/`c`，`asia`/`europe_russia`/`northamerica`/`southamerica`/`other`），不是枚举常量名。
- `FlexInt` 只做解码：JSON null、布尔、对象、数组 → `value = null`；整数用 `Int`；小数截断向 0；字符串只接受 Kotlin `toIntOrNull` 能过的形式（`"12.5"` 为 null，不把字符串当浮点再截断）。Swift `Int` 是 64 位，这里是 32 位 `Int`；超出 `Int` 范围的数没有逐位对齐。
- `ChatContext` 的 JSON 用 `ignoreUnknownKeys = true`，对齐 `JSONDecoder` 忽略未知字段。`age` 走 `FlexInt`，`start_timestamp` 是 `Double`。

## 其它差异

- 时长常量是 `Double` 秒。时刻参数用 `java.time.Instant`。
- `Room` 的次级构造器做 `displayName` 空白折叠和 `displayableAge`。主构造器是 private，并标了 `@ConsistentCopyVisibility`，生成的 `copy` 同样是 private，外面改字段请重新构造。
- 字符串小写用 `Locale.ROOT`。空白用 `Char.isWhitespace()` 或 `Character.isSpaceChar`（补上 Java `isWhitespace` 排除的不换行空格）。Swift 按字素簇计数，这里 `take` / `length` 按 UTF-16 码元；用户名校验先数码点再查标量，BMP 且无组合字符时与 Swift 一致。
- `ResponseShape` 的空白只认 `0x20/0x09/0x0A/0x0D`，完整 UTF-8 BOM 只在前导空白之后剥一次。
- 查询串按 RFC 3986 非保留字符之外全部百分号编码（空格是 `%20`）。测试比对的是解码后的查询项。
- `EndpointBackoff` 在 Swift 是值类型，这里是类，只有 `EndpointBackoffBox` 持有并在锁里改。锁的范围与 Swift 一样：先读时钟，再进锁。
- `ViewerCount` 的一位小数用 `Locale.US` + `HALF_UP`，避免默认语言环境把小数点印成逗号。整数档用 `kotlin.math.round`（非负时与 Swift `rounded()` 一致）。

## 构建

`:core` 的 Kotlin 目标是 17，Java 编译任务跟着工具链落到 21。Gradle 9 上 `kotlin.jvm.target.validation.mode` 默认是 error，所以不加参数时 `:core:compileKotlin` 直接失败，测不到本包。这是脚手架的目标没对齐，本包按约束没有改 `build.gradle.kts`。把 `:core` 的 Java `sourceCompatibility` / `targetCompatibility` 设成 17 之后，官方测试命令才能跑。

## 测试归属

已跑：`BOMShapeTests` 三条、`EndpointBackoffTests` 的 `testWindowIsPerEndpointAndThirtySeconds`、`testShapeClassification`、`testChallengeMarkers`，以及 `VelaCoreTests.testFormattingAndTokens`。

`skip-l0a.tsv` 里 8 条是 `deferred:C9`（计划点名的 5 条，加上引用 `FavoriteFetcher` 的 2 条和引用 `AwayRetry` 的 1 条）。`--group l0a` 不计它们。`--all` 在 C9 消化之前会失败，这是 `PORTING.md` 已写明的衔接，不是本包漏测。
