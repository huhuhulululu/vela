# 站点抽象层与 Cam4 计划

> 状态：设计 v1（2026-11-19）。分工：Phase A（VelaCore 纯逻辑与测试）派云端在 Linux 上实现；Phase B（Shared / App / iOS 接线、站点切换界面、模拟器实跑、G-mac）在本机做。并行分支 sync/s2 的同步格式用 `<站点>:<用户名>`，本计划的 `RoomKey.raw` 与它一致。本分支不建、不改 `Sources/VelaCore/Sync/**`、`Shared/Sync/**`、`Shared/Stage/StageModel+Sync.swift`、`SyncPanel.swift`。

## 冻结区块（需求边界与验收，批准后不可自改）

**需求边界**
- 新增站点抽象 `SiteAdapter`，Chaturbate 迁到协议下，行为不变（请求、来源选择、质询、退避、侧车备路都不变）。
- 接入 Cam4，只用访客接口：不登录、不付费、不打赏、不录制、不绕过访问控制，不带任何推广参数。
- 房间身份是 `RoomKey`（`<站点>:<用户名>`，站点 id 满足 `^[a-z][a-z0-9]{1,7}$`，现有 `cb`、`c4`）。
- 本机持久化的用户名键一次性迁移为带前缀键：幂等，有备份，失败不丢数据。
- 站点切换：Chaturbate / Cam4 / 混合，默认 Chaturbate，选择记住。
- 单窗、双窗、三窗都能播 Cam4 房间；各路房间两两不同，按 `RoomKey` 比较（`cb:alice` 与 `c4:alice` 是两间房）。
- 轻量：不加常驻定时器，不在后台轮询，不加第三方依赖。

**验收标准**
- S-1：`swift test` 全绿（macOS 与 Linux）；VelaPhoneTests 全绿且数量不少于合入前基线。
- S-2：Phase A 测试清单（本文末尾）全部存在且通过；测试只用本地伪数据，不访问真实站点。
- S-3：`Vela`、`VelaPhone` 两个目标构建通过。
- S-4：G-mac 播放 ×3 + 空闲 ×1 合格（固定测试流）；`app_size` 不超过基线 3.7 + 0.3 MB。
- S-5：iPhone 模拟器静音实跑，Chaturbate 房间与 Cam4 房间各起播一次（固定测试流或本机伪上游）。
- S-6：CI 五项全绿。

## 1 现状（2026-11-19 main @ 72d2350，已核实）

- `CatalogAPI` / `CatalogQuery.url()` 写死 Chaturbate 列表接口；`ChatContext`、`StreamDecoder`、`StreamSourcing` 围绕 chatvideocontext 与 get_edge_hls_url_ajax。
- `Room.id == username`；`FeedStore`、`LaneSelection`、`RoomQuery`、`dedupeRooms`、`PageMerge` 都按 `username`（多数处小写）比较。
- 持久化的用户名键只有三处：`feed-curation.json`（收藏 / 屏蔽）、`feed-taste.json` 的 `seen`、`watch-ledger.json` 的 `entries`。`feed-prefs.json` 是旧版文件，只经 `Taste(migrating:)` 读，不改。
- 预取缓存（`prepared`）、`FavoriteCache`、`KnownCatalog`、`LastBroadcast` 缓存、`RoomThumbnailCache` 都只在内存，不需要迁移，只要改成按 ref 作键。
- `SettingsStore`（UserDefaults）里没有房间键；只新增 `siteMode` 一项。
- 写死 Chaturbate 头的地方：`HTTPHeaders.applyBrowser`（Origin）、`PlaylistRelay.fetch`、`PlayerController.makeItem`、`StageModel.selectRendition`；侧车（WebKit）在 `Shared/Playback/SiteSidecar.swift`，Core 调不到。
- 子列表相对路径已由 `StreamRendition` / `PlaylistLadder` 用 `URL(string:relativeTo:)` 解析（RFC 3986：基址的查询参数不带到子路径）。

## 2 RoomKey 与内存简写

```swift
public enum SiteID: String, CaseIterable, Codable, Sendable {
    case chaturbate = "cb"
    case cam4 = "c4"
    public static let idPattern = "^[a-z][a-z0-9]{1,7}$"
    public static func isValidID(_ raw: String) -> Bool          // 只校验格式，不要求已知
    public var displayName: String                               // "Chaturbate" / "Cam4"
    public func isValidName(_ name: String) -> Bool              // cb 沿用 Room.isValidUsername；c4 同规则另放行 "-" 与 "."，长度 2...40
}

public struct RoomKey: Hashable, Codable, Sendable, CustomStringConvertible {
    public let site: SiteID
    public let name: String                       // 去首尾空白，保留大小写（显示与请求用）
    public init?(site: SiteID, name: String)      // 名字不合法返回 nil
    public init?(parsing raw: String)             // "cb:Alice"、"c4:bob"、裸名 "alice"（视为 cb）；
                                                  // 站点段不合 idPattern、未知站点、名字不合法返回 nil
    public var raw: String                        // 永远带前缀："cb:Alice"（磁盘与同步格式）
    public var ref: String                        // 内存简写：cb 省略前缀 "Alice"，其他站点 "c4:bob"
    public var normalized: String                 // ref 的小写，集合与比较用
    // == 与 hash 按 site + name.lowercased()；Codable 编码为 raw 字符串，解码接受 raw 与裸名
}
```

**为什么内存用 ref 而不是 raw**：现有约 200 处身份比较、72 个测试文件都用裸名；规则「无前缀 = cb」与同步格式的映射一致，ref 与 RoomKey 一一对应，所以按 ref 比较就是按 RoomKey 比较，Chaturbate 的现有测试一行不改仍然成立。磁盘与同步一律写 raw。

`Room` 增加（都带默认值，现有调用点不改）：

```swift
public var site: SiteID                 // init 参数 site: SiteID = .chaturbate，放在参数表末尾
public var stream: URL?                 // 目录直接带来的播放主列表（Cam4 hlsPreviewUrl）；cb 为 nil
public var thumbnailExpires: Date?      // 缩略图过期时刻（Cam4 defaultImageLink 的 validto）；cb 为 nil
public var blockedRegions: Set<String>  // 大写 ISO 3166-1 alpha-2；cb 为空
public var key: RoomKey { get }         // 名字不合法时也要有值：内部不校验构造，保证非可选
public var ref: String { get }          // key.ref
public var id: String { ref }           // 原来是 username；cb 房间值不变
public static func direct(key: RoomKey) -> Room   // 现有 direct(username:) 保留，等价于 cb
```

身份比较一律改成 `ref`（集合用 `ref.lowercased()`）：`RoomQuery.blocked / only`、`dedupeRooms`、`PageMerge`、`FeedStore` 全部方法、`LaneSelection`（`rooms`、`recent`、排除集）、`RecentSkips`、`LanePaging`、`Recommender` 里的「最近看过 / 收藏 / 常看」、`FavoritesBoard`、`FavoriteCache`、`KnownCatalog`、`WatchLedger.key`、`Taste.seen`、`AwayRetry`。参数名（`username:`、`currentUsername`）不改，文档注释说明「存 ref」。`Curation.key(_:)` 改为 `RoomKey.normalizeRef`：任意形式（裸名、`cb:x`、`c4:x`）都归一到小写 ref，所以 `isFavorite("cb:alice") == isFavorite("alice")`。

```swift
extension RoomKey {
    public static func normalizeRef(_ any: String) -> String   // 认不出的形式退回 trim + lowercased（与旧 Curation.key 相同）
}
```

## 3 SiteAdapter 协议

```swift
public struct SiteCapabilities: OptionSet, Sendable {
    public static let serverGender   // 列表接口能按性别筛
    public static let serverRegion   // 能按地区筛
    public static let serverTag      // 能按标签筛
    public static let freshCams      // 有「新开播」筛选
    public static let keywordSearch  // 有全站搜索
    public static let hotTags        // 有热门标签接口
    public static let lastBroadcast  // 能查离线收藏的上次开播
    public static let challengePage  // 会出质询页，需要侧车确认（只有 cb）
    public static let strictMaster   // 主列表取不到（5xx / 403 / 404）即判这间不可播（只有 c4，见 §5）
}

public protocol SiteAdapter: Sendable {
    var site: SiteID { get }
    var capabilities: SiteCapabilities { get }
    /// 质询确认页的入口（侧车与确认页打开它）；没有质询能力为 nil。
    var challengeHome: URL? { get }

    /// 目录分页。复用 CatalogQuery（offset / limit / gender / region / tag / newCams / keywords）。
    /// 能力里没有的筛选项：能在本地按字段过滤的就地过滤（性别、关键词按用户名），
    /// 过滤不了的（地区、标签、新开播）这一站返回空页且 nextOffset == total（不发请求）。
    func fetchDirectory(_ query: CatalogQuery) async throws -> CatalogPage

    /// 房间 → 可播放流。room 里已有的 stream 提示可直接用（是否新鲜由适配器判断）。
    /// 质询抛 StreamDecodeError.challenge（调用方决定是否走侧车）；普通不可用抛 .unavailable(status:)；
    /// 退避中抛 EndpointBackedOff；网络错误原样抛出；取消原样抛出。
    func resolveStream(_ room: Room) async throws -> SourcedTicket

    /// 在线状态（收藏夹、菜单栏、收藏来源）。查不到的键不放进结果（界面显示「未查询」）。
    func status(of keys: [RoomKey], now: Date) async -> [RoomKey: FavoriteStatus]

    /// 此刻可用的缩略图地址：已过期（距 thumbnailExpires 不足 60 秒）返回 nil，界面用模糊占位，不为它单独发请求；
    /// 下次目录刷新带来新地址。
    func thumbnail(for room: Room, now: Date) -> URL?

    /// 中继取主列表 / 子列表、AVURLAsset 直连时要带的头（Referer、Origin）。
    func mediaHeaders(for key: RoomKey) -> [String: String]

    /// 主列表请求的状态码是否已能定论为不可播（仅 strictMaster 站点返回非 nil）。
    func masterFailure(status: Int) -> StreamFailure?
}

public enum SiteError: Error, Equatable, Sendable {
    case unknownSite(String)   // 键里的站点没有注册适配器
    case unsupported(SiteID)   // 调了该站没有的能力（hotTags、lastBroadcast 等）
}

public struct SiteRegistry: Sendable {
    public init(adapters: [any SiteAdapter])
    public func adapter(for site: SiteID) -> (any SiteAdapter)?
    public static func live(client: any HTTPClient, backoff: EndpointBackoffBox? = .shared,
                            viewerRegions: Set<String>) -> SiteRegistry
}
```

错误类型沿用现有 `CatalogFailure`、`StreamDecodeError`、`EndpointBackedOff`，Shared 层的失败分流不改。`StreamOrigin` 新增 `case cam4Directory = "c4-directory"`；`EndpointBackoff` 的端点枚举新增 `.cam4Directory`。

### 3.1 ChaturbateAdapter（行为不变的迁移）

```swift
public struct ChaturbateAdapter: SiteAdapter {
    public init(client: any HTTPClient, backoff: EndpointBackoffBox? = .shared)
    // capabilities = [.serverGender, .serverRegion, .serverTag, .freshCams, .keywordSearch,
    //                 .hotTags, .lastBroadcast, .challengePage]
    // challengeHome = https://chaturbate.com/
}
```

- `fetchDirectory` = 现在的 `CatalogAPI.fetch`（`CatalogAPI` 保留为它的内部实现，现有测试照旧）。
- `resolveStream` = 现在 `StageModel.resolveSourced` 去掉侧车两段：`ChatContext.probe` → `StreamSourcing.first` → `StreamDecoder.fetch` → `StreamSourcing.settle`。侧车（冷却期直走侧车、质询或网络故障改走侧车）留在 Shared，只对带 `.challengePage` 的站点生效，判断顺序与现在逐条相同。
- `status` = 现在的 `FavoriteFetcher`（chatvideocontext，并发 4）；`mediaHeaders` = 现在写死的 Referer / Origin；`masterFailure` 恒为 nil（主列表取不到时仍交给中继自己解析，与现在相同）。

### 3.2 Cam4Adapter

```swift
public final class Cam4Adapter: SiteAdapter, @unchecked Sendable {
    public init(client: any HTTPClient, backoff: EndpointBackoffBox? = .shared,
                viewerRegions: Set<String>, clock: @escaping @Sendable () -> Date = Date.init)
    // capabilities = [.strictMaster]；challengeHome = nil
}

public enum Cam4Directory {
    public static let maxPageSize = 90
    /// GET https://www.cam4.com/directoryCams?directoryJson=true&online=true&url=true&page=<n>&resultsPerPage=<k>
    /// page 从 1 开始：page = offset / k + 1。
    public static func url(page: Int, resultsPerPage: Int) -> URL
    public static func decode(_ data: Data, requestLimit: Int, requestOffset: Int, now: Date,
                              viewerRegions: Set<String>) throws -> CatalogPage   // 抛 CatalogFailure
}

public enum Cam4Regions {
    /// 接受逗号分隔字符串或字符串数组；去空白、转大写、丢掉不是两位字母的项。
    public static func parse(_ raw: Cam4RegionField?) -> Set<String>
    public static func isBlocked(_ blocked: Set<String>, viewer: Set<String>) -> Bool
}
```

字段映射：

| Cam4 | Room |
|---|---|
| username | username（displayName 同名），site = .cam4 |
| gender | female → .female；male → .male；couple → .couple；shemale / trans / transgender → .trans；其余 nil |
| age | age（经 `displayableAge`） |
| countryCode | location（大写两位） |
| privateRoom == true | show = .privateShow；否则 .publicShow |
| hlsPreviewUrl | stream（只收 https，经 `secureImageURL` 同样的规范化） |
| defaultImageLink | thumbnailURL；`validto` 查询参数 → thumbnailExpires（>1e12 按毫秒，否则按秒；缺失为 nil） |
| blockedRegions | blockedRegions |
| viewers（若有，FlexInt） | viewers；没有为 0 |
| languages、broadcastType | 不用 |
| totalCount | CatalogPage.total；nextOffset = offset + min(本页条数, 请求页大小) |

- 正文像 HTML / 不是 JSON：登记 `.cam4Directory` 退避，按 `CatalogFailure.status` / `.malformed` 失败；Cam4 没有质询能力，不弹确认页。
- 适配器内部有一份按小写用户名记录的目录索引（流地址、所在 offset、取得时刻、私密与地区屏蔽），每次解码目录页时更新，上限 2000 条，超出丢最旧；只在内存。
- `resolveStream(room)`：room.stream 存在且索引里这间取得不超过 10 分钟 → 直接给可播放票据（origin `.cam4Directory`，不发请求）；否则重取它上次所在的那一页（一次请求）再查；仍找不到 → 票据 status `.offline`；私密 → `.privateShow`；被地区屏蔽 → status `.publicShow`、url nil（即现有的 `.geo`「当前地区不可观看」）。从没在目录里见过的键（手动输入 `c4:name`）先取第 1 页查，找不到按离线。
- `status(of:)`：只查索引（3 分钟内的条目）；在线给 `.online(room)`，屏蔽给 `.restricted`，查不到的不放进结果。不为收藏逐个请求。
- `mediaHeaders` = Referer `https://www.cam4.com/`、Origin `https://www.cam4.com`；`masterFailure(status:)`：500...599、403、404 → `.unavailable`，其余 nil。

### 3.3 blockedRegions 规则

- 观看地区 `viewerRegions` 由 Shared 注入（设备地区 `Locale.current.region`，取不到为空集）；Core 不读 Locale，Linux 上也能测。
- 房间的 `blockedRegions` 与观看地区有交集 → 目录解码时把这间标成 `.publicShow` + 无流地址，列表照常按现有「仅公开可播」过滤掉，误入时显示「当前地区不可观看」并自动跳过。
- 观看地区为空：不过滤，起播失败时走现有失败自动跳过。
- 理由：这是主播自己设的访问控制，按设备所在地区遵守，不借代理出口去绕。**待用户确认**：是否改为按代理出口地区判断（那要额外一次出口地区查询，不轻量）。

## 4 本机存储迁移（Phase A 实现，Shared 启动时调一次）

```swift
public enum SiteKeyMigration {
    public static let version = 2
    public struct Report: Equatable, Sendable {
        public var migrated: [URL]; public var skipped: [URL]; public var failed: [URL]
    }
    /// 依次处理 curation、taste、watch-ledger 三个文件。
    public static func run(curation: URL, taste: URL, ledger: URL,
                           fileManager: FileManager = .default) -> Report
    /// 用 `.pre-sites` 备份覆盖回原文件（调试与测试用；正常流程不调用）。
    public static func rollback(curation: URL, taste: URL, ledger: URL,
                                fileManager: FileManager = .default) -> Report
}
```

- 磁盘格式：三个文件顶层新增 `"keyVersion": 2`，键全部写 raw（`cb:alice`）。`Curation` / `Taste` / `WatchLedger` 的解码同时接受旧格式（裸名 → cb）与新格式，编码一律写新格式，所以即使迁移没跑，读写也正确，迁移只是把旧文件一次落成新格式。
- 单个文件的步骤：不存在 → skipped；`keyVersion >= 2` → skipped（幂等）；读失败或超 1MB → 交给现有 `PreferenceQuarantine`，迁移不碰它 → skipped；否则先把原文件复制为 `<名>.pre-sites`（已有备份就不覆盖），复制失败 → failed 且不改原文件；再宽松解码、按新格式 atomic 写回，写失败 → failed，原文件与备份都还在。
- 失败不丢数据：任何一步失败原文件原样保留，App 照常宽松读取，下次启动再试。
- 「清除全部偏好数据」时一并删除 `.pre-sites` 备份。
- 同名合并：旧文件里同一用户名不同大小写已由各自的 clean 去重；迁移后不可能产生 cb / c4 冲突（旧数据全是 cb）。
- 收藏导出（`FavoriteExchange.encode`）写 raw；导入接受 raw 与裸名。
- 设置：`SettingsStore.siteMode`（UserDefaults 键 `siteMode`，值 `chaturbate` / `cam4` / `mixed`），缺省与认不出的值都是 `.chaturbate`；无旧值需要迁移。

## 5 混合目录、选房与中继复用

```swift
public enum SiteMode: String, CaseIterable, Codable, Sendable {
    case chaturbate, cam4, mixed
    public var sites: [SiteID]                          // mixed = [.chaturbate, .cam4]
    public static func restore(_ raw: String?) -> SiteMode
}

public struct MixedCursor: Equatable, Sendable {
    public var offsets: [SiteID: Int]; public var totals: [SiteID: Int]; public var ended: Set<SiteID>
    public init(sites: [SiteID])
    public var isFinished: Bool
}

public enum SiteCatalog {
    /// 按 mode 向各站取一页（并发），合并成一个 CatalogPage：各站保持自己的顺序，轮流各取一间交错；
    /// 按 ref 去重；total = 各站 total 之和、nextOffset = 各站 offset 之和（让 FeedStore 的到底判断照旧成立）。
    /// 混合模式一站失败另一站成功：返回成功那站的结果并在 failures 里记下失败站；全失败抛第一站的错误。
    public static func fetch(mode: SiteMode, query: CatalogQuery, cursor: MixedCursor,
                             registry: SiteRegistry) async throws -> (page: CatalogPage, cursor: MixedCursor, failures: [SiteID: Error])
}
```

- 单站模式下 `SiteCatalog.fetch` 与直接调那一站的 `fetchDirectory` 结果相同（cb 模式行为不变）。
- 选房：`LaneSelection`、`FeedStore` 已按 ref 比较，两两不同自然按 RoomKey 成立；新增测试证明 `alice` 与 `c4:alice` 可同时在两路，`c4:alice` 不能同时在两路。
- 推荐：`Recommender` 不改算法；Cam4 房间没有标签，只按性别、热度与常看主播参与排序。
- 中继：Cam4 的主列表就是普通 HLS，沿用现有 `StreamRendition.select` 选档与中继的稳态子列表路径，不另写中继。改动只有两处，都在 Phase B：`PlaylistRelay` 每个源登记时带上 `mediaHeaders`（取代写死的 Referer / Origin），`PlayerController.makeItem` 同样按站点取头。相对子列表按 RFC 3986 解析（主列表的查询参数不带到子路径，子路径自带的查询参数保留），Phase A 补测试。单档主列表（实测 720p 一档）走同一条选档路径。
- 主列表取不到：`strictMaster` 站点（c4）在选档时按 `masterFailure(status:)` 判不可播，直接走现有 `noteUnplayable(.unavailable)` 自动跳过（个别房间 CDN 504）；cb 仍退回未选档主列表交给中继，行为不变。

## 6 界面（Phase B）

- 筛选面板顶部加「站点」三段选择：Chaturbate / Cam4 / 混合；切换等同切来源：重取目录，各路按 `LaneSelection.resolveFeedChange` 保持两两不同。
- 非 cb 房间的信息行显示站点名（Cam4），cb 房间不显示（Phase B 实现时简化：不分单站或混合模式，信息行本身不知道模式）。收藏夹行对非 cb 的收藏直接显示 ref（`c4:名字`）。
- 当前模式不支持的来源或筛选（Cam4 的新开播、地区、标签）：该站贡献空页；Cam4 单站模式下列表为空时空状态说明「Cam4 不支持这个筛选」。
- 搜索框回车直达：输入 `c4:name` 直达 Cam4 房间；裸名在 Cam4 单站模式下按 c4，其余按 cb。
- 质询确认页、侧车、热门标签、上次开播只对有对应能力的站点启用。
- 固定测试流（PerfStream）加一个 Cam4 形状的房间（同一本机测试流），供 G-mac 与模拟器实跑，不访问真实站点。

## 7 任务拆分

| 阶段 | 范围 | 执行 | 验证 |
|---|---|---|---|
| A | `Sources/VelaCore/Sites/`（SiteID、RoomKey、SiteAdapter、SiteRegistry、ChaturbateAdapter、Cam4Adapter、Cam4Directory、Cam4Regions、SiteMode、SiteCatalog、SiteKeyMigration）、`Models.swift` 等身份比较改 ref、三个存储的宽松解码与新格式编码、`Tests/VelaCoreTests/` 新测试；新文件登记 `project.pbxproj`（`Vela` 与 `VelaPhone` 各一次，见 ARCHITECTURE「Xcode 工程」） | 云端（Linux） | `swift test`；CI mac / ios 构建证明 pbxproj 登记无误 |
| B | `Shared/Stage`（resolveSourced 改走适配器、侧车只对 cb、选档 masterFailure、SettingsStore.siteMode、启动跑迁移、站点切换、空状态文案）、`Shared/Relay` 与 `PlayerController` 的按站点头、`App/`、`iOS/` 接线、PerfStream 加 Cam4 房间、VelaPhoneTests 补 Cam4 两两不同与站点切换用例、`docs/ARCHITECTURE.md` | 本机 | VelaPhoneTests、两目标构建、模拟器实跑、G-mac、CI |

Phase A 不得改 Shared / App / iOS 的源码（只登记 pbxproj）；若 Core 的公开签名改动使 Shared 编译不过，在 Core 留兼容入口（例如 `CatalogAPI`、`StreamDecoder.fetch`、`ChatContext.probe` 原签名保留），由 Phase B 再切换。

## 8 Phase A 测试清单（全部用本地伪数据，伪用户名如 `sample_one`）

- RoomKey：raw / ref 互转；裸名视为 cb；大小写不敏感相等；站点 id 正则（`a1` 合法、`1a`、`A1`、`a`、九位非法）；未知站点、空名、非法字符返回 nil；Codable 往返；`normalizeRef` 对三种写法归一。
- Room：默认 site 为 cb 时 `id == username`；c4 房间 `id == "c4:<名>"`；`dedupeRooms` 不把 cb 与 c4 同名合并。
- ChaturbateAdapter：用现有目录与流地址伪数据证明结果与 `CatalogAPI` / `StreamSourcing` 现路径完全相同（含质询、403 / 503、非 JSON 退避、退避中不发请求、公开无地址判 geo）。
- Cam4Directory：完整伪页解析（字段映射表每一行）；缺字段；`privateRoom`；非 https 流地址丢弃；`validto` 秒与毫秒；HTML 正文登记退避且不当质询；非 2xx；URL 形状（page 从 1 起、页大小截断到 90）。
- Cam4 流解析：新鲜提示零请求；过期重取原页；重取后找不到判离线；私密；地区屏蔽判 geo；没见过的键取第 1 页；取消原样抛出。
- 相对路径拼接：主列表带查询参数、子路径相对 / 绝对路径 / `../` / 自带查询参数，`StreamRendition.select` 与 `PlaylistLadder.mediaURL` 都按 RFC 3986 得到预期地址；单档主列表能选中。
- 504 跳过：`Cam4Adapter.masterFailure(504) == .unavailable`，403 / 404 / 500 同；200、301 为 nil；`ChaturbateAdapter.masterFailure` 恒 nil。
- blockedRegions：逗号串与数组两种写法；大小写、空白、非两位码；观看地区命中 / 不命中 / 为空。
- SiteCatalog：单站等价；混合交错顺序；按 ref 去重；一站失败返回另一站并记 failures；全失败抛错；游标与 total 之和；不支持的筛选该站零请求。
- 选房：`FeedStore` 与 `LaneSelection` 中 `alice` 与 `c4:alice` 两路共存；三路两两不同（屏蔽、搜索、划动、reveal）在含 c4 房间的列表上成立；`RoomQuery.blocked` 按 ref 只挡对应站。
- Curation / Taste / WatchLedger：旧格式解码、新格式编码、往返；`isFavorite` 对三种写法一致；导出写 raw、导入接受两种。
- SiteKeyMigration：无文件；旧格式迁移后内容等价且带 `keyVersion`；连跑两次第二次 skipped 且文件字节不变；备份只建一次；备份失败不改原文件；写失败原文件与备份都在；坏文件走 quarantine 不被迁移；rollback 恢复原字节；混有新旧键的文件。
- SiteMode：restore 缺省、旧值、认不出的值。
- 现有测试：只允许修改断言磁盘 JSON 文本里键格式的用例，每处在提交说明里列出。

## 9 安卓需要跟随的点（vela-wt-android，Kotlin）

Core（`android/core/src/main/kotlin/vela/core/`）：`feed/CatalogAPI.kt`、`feed/FeedStore.kt`、`feed/SiteSearch.kt`、`stream/StreamAPI.kt`、`stream/StreamSourcing.kt`、`curation/Curation.kt`、`curation/FavoriteExchange.kt`、`curation/FavoriteFetcher.kt`、`curation/FavoriteCache.kt`、`curation/FavoritesBoard.kt`、`curation/LastBroadcast.kt`、`curation/LoadCommit.kt`、`ledger/WatchLedger.kt`、`ledger/WatchLedgerStore.kt`、`ledger/StreamerAffinity.kt`、`ledger/PromptRules.kt`、`ledger/DwellTracker.kt`、`recommend/Recommender.kt`、`recommend/RankSignals.kt`、`lifecycle/AwayRetry.kt`、`hls/PlaybackPolicy.kt`；新增与本计划对应的 SiteID / RoomKey / SiteAdapter / Cam4 / 迁移。

App（`android/app/src/main/kotlin/vela/app/`）：`infra/Endpoints.kt`、`playback/LaneHttp.kt`（按站点头）、`lane/PlaybackLane.kt`、`stage/Stage+Catalog.kt`、`stage/Stage+Feed.kt`、`stage/Stage+Playback.kt`、`stage/Stage+Curation.kt`、`stage/Stage+Taste.kt`、`stage/Sidecar.kt`（只对 cb）、`stage/StageContract.kt`、`stage/StageViewModel.kt`、`stage/LifecycleWiring.kt`、`ui/StageScreen.kt`、`ui/Chrome.kt`、`ui/FavoritesSheet.kt`、`ui/TasteSheet.kt`（站点切换与站点小字）。

注意：本计划合入后，苹果端导出的收藏是 raw 格式（`cb:alice`），安卓导入前要先支持 raw，否则会把带前缀的名字判为非法。

## 10 公开方案调研

- yt-dlp `cam4.py`、StreaMonitor `cam4.py`：只读学习 Cam4 接口形状（访客目录与 HLS 地址），不复制代码。
- RFC 3986 §5.2：相对引用解析；Foundation 的 `URL(string:relativeTo:)` 已按它实现，不自写拼接。
- 同步分支的 `<站点>:<用户名>` 格式：直接沿用，不另造。
