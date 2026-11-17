# Vela 架构目录

以代码为准；与代码不一致时先改代码再改本文。结构、接口、依赖、数据流、存储、安全边界有变，同一次提交内同步本文。

## 1. 概述

- 用途：面向 Chaturbate 的 macOS 原生播放器。窗口可浮在所有桌面之上，指针离开时变淡并让出点击；直播用 AVPlayer 硬件解码，不内嵌浏览器播放。
- 平台：macOS 14+（`MACOSX_DEPLOYMENT_TARGET = 14.0`，Swift 5 语言模式，`swift-tools-version: 5.9`）。应用未开沙盒（`ENABLE_APP_SANDBOX = NO`），Bundle ID `com.vela.stage`。
- 技术栈：SwiftUI（界面）、AVPlayer / AVPlayerLayer（播放）、Network.framework（本地中继 `NWListener`）、WKWebView（侧车与确认页）、Carbon `RegisterEventHotKey`（老板键）。没有账号体系，也不使用钥匙串。
- 两层结构：
  - `Sources/VelaCore/`：纯逻辑（模型、接口编解码、HLS 改写、推荐、策略、存储），不依赖 AppKit，`swift test` 可测。SwiftPM 库产品 `VelaCore`。
  - `App/`：界面与系统集成（SwiftUI、AppKit、AVFoundation、WebKit、Carbon），只能用 `xcodebuild` 编译验证，没有自动化测试。
- Xcode 工程（`Vela.xcodeproj`）显式列出每个源文件：`App/` 与 `Sources/VelaCore/` 下新增、重命名、删除 Swift 文件都必须同步登记 `project.pbxproj`（每个文件在 PBXBuildFile 与 PBXSourcesBuildPhase 各出现一次）。`Tests/` 不在 Xcode 工程里，只属于 SwiftPM。
- 目录：`App/`（37 个源文件）、`Sources/VelaCore/`（50 个源文件）、`Tests/VelaCoreTests/`（57 个文件）、`.github/workflows/macos.yml`。

## 2. 模块表

<!-- AUTO-MANAGED:module-table -->
| 文件 | 层 | 职责 |
|---|---|---|
| `App/VelaApp.swift` | App | 入口、窗口场景、菜单栏图标场景（`MenuBarExtra`，开关在「播放」菜单）、「播放」菜单与快捷键（含「重试当前房间」），`AppDelegate` 固定深色外观 |
| `App/StageModel.swift` | App | 主状态机：来源 / 筛选 / 播放门控 / 指针与窗口状态 / 设置读写，持有各子系统；冷启动闸门 `startupGate`（`StartupGate`）：初始化里年龄已确认就先领目录请求，`bootstrap` 再进时不重发；画面订阅的播放镜像 `playback`（`PlaybackFace`）；同值不写（`AssignIf`），`presence` / `chromeVisible` / `playbackNote` 不变不通知；`syncPlayerHidden` 把窗口不可见同步给播放器，`refreshPointerPolling` 让窗口桥接重判指针轮询 |
| `App/StageModel+Catalog.swift` | App | 目录加载：重新加载、翻页、补拉到出现可见房间、确认页；记录最近取回的目录供收藏比对；常规目录先安装并起播，按口味定向的候选并发拉取（各自 6 秒超时）、完成后并入当前位置之后；全站搜索的临时列表（`searchSite`，回车且本地无匹配时触发，重新加载时结束搜索）；新开播用站点的新开播筛选；冷启动只等首页（`fetchOpening`）：猜你喜欢 / 新开播的后续页由 `catalogRestTask` 在后台顺序续拉（`CatalogRest`），经 `mergePageAfterCurrent` 并入当前项之后，失败只记日志、不换房不起播；定向候选等续页并完后再发；换来源、重新加载、全站搜索时取消续页 |
| `App/StageModel+Playback.swift` | App | 起播链：解析流地址、取档、重试与重新解析、预取下一个、失败自动跳过（先请求房间状态取 `hls_source`、拿不到再走 ajax 与侧车，见 `StreamSourcing`，并记录起播来源日志；暂时离开停留 60 秒只重试一次（`AwayRetry`）；到列表尽头时保留原失败原因并说明，不占连续次数）；普通 403 / 503 与质询分开，只有质询才弹确认页（先停掉当前播放；等待期间被换房取代时恢复侧车），连续质询经 `ChallengeGate` 限次，达到上限改为提示并跳过；起播同一时间只保留一个任务（`playTask`），新的划动 / 选择 / 重试取消旧的解析，取消按取消处理，不当网络故障；起播前等本地中继监听就绪，不就绪则提示并停下，不把低延迟主列表交给 AVPlayer |
| `App/StageModel+Quality.swift` | App | 离开后降画质：计时、取档、重建播放项 |
| `App/StageModel+Dwell.swift` | App | 停留结算（带来源）、口味画像写入、热门关联前置、常看收藏提示（点「收藏」或关闭才记账，超时不记，本次停留不重复弹，指针回到窗口才显示） |
| `App/StageModel+Taste.swift` | App | 推荐理由查询、口味面板操作（加强 / 不想看 / 恢复 / 移除 / 暂停 / 重置 / 清除；改标签后立即对当前位置之后重排并更新推荐理由）、对当前房间不感兴趣 |
| `App/StageModel+Curation.swift` | App | 收藏、屏蔽、名单提交与换房 |
| `App/StageModel+Favorites.swift` | App | 收藏来源：解析全部收藏的在线状态（目录与缓存优先，其余逐个查；同一时间只有一批，直连被拦后经侧车重查）、刷新、离线收藏的上次开播时间按需补查、点击切换（清掉会挡住目标的搜索条件）、手动添加、导出导入 |
| `App/PlayerController.swift` | App | AVPlayer 封装（起播看门狗、卡住看门狗：到点时若缓冲末端仍在增长或续期进行中则重新计时，不消耗重试；重试、暂停恢复、开播音轨日志）；静音时不选独立音轨并通知中继停刷音频清单（`MuteAudioPolicy`）；停播且窗口不可见满 30 秒释放播放项与中继源（`setHidden` / `releasePausedSource`，日志 `paused-release`）；每个媒体项首次真正播放记一条 `playing-started`（Release 也有，探针以它为起播时刻） |
| `App/PlaylistRelay.swift` | App | 本地 HLS 中继：起播选一次档、视频与音轨各自后台刷新、按完整分片进展判新鲜（超宽限先有界挂起等待再 503）、本地主列表；续期换过子列表地址后，每一路下一次成功取回直接采纳新列表（`addressChanged`）；从不 302 到上游；`waitUntilListening` 供起播前等监听就绪；静音且播放器能空选音轨时（`setAudioSuppressed`）停掉音频清单的后台刷新，不取消在途、不清已缓存列表，播放器来要 `audio.m3u8` 仍按需取；取消静音立即恢复刷新；`deactivate` 丢掉当前源状态 |
| `App/SiteSidecar.swift` | App | 离屏 WKWebView（闲置 30 秒销毁，`SidecarIdlePolicy`；只拆视图，默认数据仓库里的通关 cookie 保留，下次使用重建）：站点要求确认时代为请求流地址与收藏状态（`SidecarHTTPClient`）；清理旧会话期间先等清理完成 |
| `App/URLSessionHTTPClient.swift` | App | `HTTPClient` 的 URLSession 实现（ephemeral，不收发 cookie，请求超时 12 秒；不等待网络恢复，断网立刻失败，界面给出「网络不通」与重试） |
| `App/LegacyLoginCleanup.swift` | App | 启动时一次性清掉旧版本遗留的钥匙串会话与网页 sessionid cookie（cookie 删除完成后才写完成标记） |
| `App/SettingsStore.swift` | App | UserDefaults（suite `com.vela.stage`）设置项（`muted` 键不存在时视为静音，存过的值保留） |
| `App/BossKey.swift` | App | `GlobalHotKey` 全局热键；`SystemActivity` 锁屏 / 睡眠 / 切换用户监听 |
| `App/WindowBridge.swift` | App | 窗口配置（层级、透明度、穿透；减弱动态效果时淡入淡出直接到位；窗口底色始终透明，22pt 自定义圆角之外不露黑底）、指针轮询、滚轮与键盘切房（无修饰键快捷键有弹层、在输入、年龄确认前一律不触发，与「播放」菜单的无修饰键等效键共用 `ShortcutGate`）、画面左键拖动移窗（按下后移动超过 3.5 点才开始拖；控件、自定义样式按钮上按下一律不拖，判定见 `WindowDragPolicy`）、顶栏拖动条；指针轮询是否开由 `PointerPolling.needed` 判定（休眠、老板键、App 隐藏时停）；`apply` 只在窗口层级、集合行为、点击穿透、缩放比真正变化时写入 |
| `App/ClickableRegion.swift` | App | 可点区域登记：按钮样式把背后垫一个不接收鼠标事件的标记视图（`ClickableRegionView`），拖窗判定收集它们在窗口里的区域，排除 SwiftUI 自定义样式按钮（`ChampagneButton`、`QuietButtonStyle`） |
| `App/PreferenceHealth.swift` | App | 启动读偏好文件的结局翻成提示：坏文件已另存为备份，或备份失败进入只读（保存被跳过，日志区分于普通写盘失败） |
| `App/StageView.swift` | App | 根视图：舞台、状态提示（空列表按原因区分）、首次操作提示（三行、可关闭）、顶栏显隐、弹层装配 |
| `App/PagerView.swift` | App | 当前房间画面容器（滚轮 / 键盘切房，不再拖动翻页） |
| `App/RoomCanvas.swift` | App | 单个房间画面（铺满与否取 `StageModel.effectiveFill`）：模糊缩略图垫底（切房过渡；下载结果带房间标识，旧图不盖新图，未到时显示渐变）、播放层淡入、加载线、暂停标记与失败重试；`RoomThumbnailCache` 内存预取缩略图（`CGImageSource` 解成长边约 192 像素的小位图，不留原始 JPEG，按位图字节计费、上限约 2 MB、最多 20 张）；画面观察 `PlaybackFace` 而不是播放器，同值回调不整页重绘 |
| `App/RoomCaption.swift` | App | 房间信息块（名字、标签、推荐理由）与 LIVE 圆点 `PulseDot`：只在「当前页、这一间正在播、信息可见」时脉冲，条件用作 `.id`，变假即拆掉旧视图；脉冲走同色同时长的 `CABasicAnimation`（`PulseDotView`），不再每帧标脏 SwiftUI；减弱动态效果时静止实心圆 |
| `App/ChromeView.swift` | App | 顶栏与筛选、透明度、音量面板；筛选面板标签输入下方的热门标签提示（`TagHints`） |
| `App/TastePanel.swift` | App | 「我的口味」面板（只依赖显示数据与回调）：冷启动整理、标签列表、性别、页脚与二次确认 |
| `App/TasteParts.swift` | App | 口味面板的行、强弱条、7 天命中率卡片 |
| `App/TasteView.swift` | App | 口味面板的状态装配：画像汇总与回调；冷启动时按需取全站热门标签 |
| `App/HeartButton.swift` | App | 顶栏心形按钮：左键收藏 / 取消收藏当前房间，右键打开收藏夹；`PressSurface` / `PressView` 是覆盖在图标上的 AppKit 鼠标接管层 |
| `App/CurationPanel.swift` | App | 收藏夹弹窗的状态装配：把收藏、状态缓存、上次开播时间、当前目录交给 `FavoritesBoard.make`，接上回调；打开时缓存过期则补查一次在线状态，再补查离线收藏的上次开播时间 |
| `App/FavoritesPanel.swift` | App | 收藏夹弹窗（只依赖显示数据与回调）：标题与在线摘要、刷新、「收藏当前房间」主按钮、在线 / 其余分组、撤销移除、添加与「更多」菜单（导入、导出、屏蔽当前房间）、屏蔽名单入口 |
| `App/FavoritesRows.swift` | App | 收藏夹的行：缩略图、在线行（整行点击观看，行尾常驻弱化的「移除」，悬停加强）、其余行（弱化、不可播放，状态文字始终保留、行尾同样常驻「移除」）、屏蔽名单二级页 |
| `App/MenuBarFavorites.swift` | App | 菜单栏图标的弹出内容：在线收藏列表（数据同收藏夹，来自 `StageModel.favoritesBoard`）、摘要（`FavoritesBoard.menuSummary`）、刷新、打开收藏夹；缩略图走 `RoomThumbnailCache`；菜单窗口成为关键窗口时调用 `refreshFavoritesIfStale`（沿用冷却与单批查询，菜单关着不发请求）；`MenuBarVisibility.shared` 管图标开关（`SettingsStore.showMenuBar`，默认开） |
| `App/ChallengeSheet.swift` | App | 站点确认访问的 WKWebView 弹层（高度随窗口收缩，`SheetLayout.challengeHeight`，网页区滚动、底部按钮常驻）、`WebKitWarmup` |
| `App/AgeGateView.swift` | App | 首次 18 岁确认页与按钮样式 |
| `App/Theme.swift` | App | 配色与界面中文文案 `Copy` |
| `Sources/VelaCore/Models.swift` | Core | `Room`、`Gender`、`Region`、`ShowState`、`RoomQuery`、人数与开播时长格式 |
| `Sources/VelaCore/CatalogAPI.swift` | Core | 目录请求构造与解码（站点房间列表接口，`RoomListDecoder`；`new_cams`、`keywords` 参数）、多页候选池 `fetchPages`、质询识别；`CatalogWindow.shouldContinue`（还要不要续下一页，`fetchPages` 与后台续页共用）、`PageMerge`（`appended` 顺序翻页合并、`keepingCurrent` 后页并入时当前项及其之前保持原位） |
| `Sources/VelaCore/SiteSearch.swift` | Core | 全站搜索：搜索词规整、回车与打字的触发规则（只在回车且本地无匹配时查）、一页结果整理成临时列表、搜索期间的停留归属 |
| `Sources/VelaCore/StreamAPI.swift` | Core | 流地址请求构造与解码、`StreamTicket`、失败归类、直连请求 `StreamDecoder.fetch`（接退避） |
| `Sources/VelaCore/StreamSourcing.swift` | Core | 起播来源选择：`chatvideocontext` 起播解码 `ChatContext.probe`（`ContextProbe`）、`StreamSourcing.first` / `settle`（先 `hls_source`，退回 ajax，双空归地区受限）、来源 `StreamOrigin` 与 `SourcedTicket` |
| `Sources/VelaCore/EndpointBackoff.swift` | Core | 非 JSON 退避：`ResponseShape`、按接口 30 秒窗口 `EndpointBackoff`、进程内共用记录 `EndpointBackoffBox` |
| `Sources/VelaCore/HTTP.swift` | Core | `HTTPClient` 协议、浏览器头、固定 User-Agent |
| `Sources/VelaCore/PlaylistLadder.swift` | Core | 主列表选档（仅视频）、LL-HLS 改写成普通 HLS、`PowerPolicy` |
| `Sources/VelaCore/StreamRendition.swift` | Core | `StreamRendition`（选中档的视频 + 独立音轨子列表地址）、`HLSAttribute` 解析、本地主列表生成 |
| `Sources/VelaCore/RelayFeed.swift` | Core | `RelayFeed`（单路列表状态：分片进展、单次在途、取消、宽限）、`MediaPlaylistInfo`、`RelayFailure`、`RelayPolicy`、`RelayWait`（超宽限后的有界挂起等待，时钟与刷新由调用方注入） |
| `Sources/VelaCore/PlaybackPolicy.swift` | Core | 播放门控、暂停恢复分级、起播与卡住看门狗判定、停留计时判定、`PreparedCache`；`releasePausedItemAfter`（30 秒）与 `windowInvisible` / `shouldReleasePausedItem`（停播与窗口不可见两个时长取较小值，达到才释放，窗口可见永不释放）；`SidecarIdlePolicy`（侧车空闲 30 秒销毁） |
| `Sources/VelaCore/UpstreamRenewal.swift` | Core | 上游地址过期续期：`RenewalPolicy.isExpiry`（403 / 410 才算）、`RenewalBudget`（60 秒窗口内最多 3 次、相邻至少隔 3 秒）、`RenewalCoordinator`（视频与音轨共用一次续期；新地址在共享任务内写入，加入方醒来必已写入；`recovering` 把续期后的重取计入「续期中」；`cancel()` 随源作废、暂停、隐藏取消在途续期）、`RenewalFreshness`（续期结果须仍归当前代次与房间，否则丢弃） |
| `Sources/VelaCore/PlaybackRefresh.swift` | Core | `RefreshTracker` 待刷新状态、失败文案生命周期、补拉判定 |
| `Sources/VelaCore/PlaybackError.swift` | Core | 播放错误归类与中文文案 |
| `Sources/VelaCore/QualityGovernor.swift` | Core | 离开后降画质的时机判定 |
| `Sources/VelaCore/PortraitFit.swift` | Core | 竖屏判定（高比宽多 2% 以上）与当前房间的自动完整画面：只对该房间生效、离开恢复全局铺满、房间内手动撤销 |
| `Sources/VelaCore/SkipTracker.swift` | Core | 自动跳过方向与连续上限 |
| `Sources/VelaCore/AwayRetry.swift` | Core | 暂时离开：不自动跳过，同一房间最多延迟 60 秒自动重试一次，文案与到点校验 |
| `Sources/VelaCore/FeedStore.swift` | Core | 房间列表、当前位置、搜索打字预览（`previewQuery`：只改可见列表，当前房间保留不换，回车 `setQuery` 才提交）、翻页判定（是否到底只看常规分页的游标、总数、空页与游标不前进，不因整页重复提前停止）、屏蔽生效、关联前移、后到候选并入当前位置之后（`mergeAfterCurrent`）、预取名单；`mergePageAfterCurrent`（冷启动后台续页：更新总量与是否到底，不改选中项，不再起播） |
| `Sources/VelaCore/AssignIf.swift` | Core | `AssignIf.changed`：只在值变化时写入（不用 `inout`，`inout` 返回时总会写回）；`PlaybackFace`（画面订阅的就绪房间 / 在播房间 / 失败标志镜像，非 `@Published`，同值不发 `objectWillChange`）；无 Combine 的平台（Linux）下补一份最小 `ObservableObject` 以便 `swift test` |
| `Sources/VelaCore/AudioPolicy.swift` | Core | 静音默认值（键不存在即静音）、从静音恢复时音量为 0 则回到 0.2、断网类错误归类（`NetworkFailure`）；`MuteAudioPolicy`：静音时是否清空独立音轨选择（组允许空选择才清，否则保持选中、只靠 `isMuted`）、中继是否刷新音频清单、取消静音是否 seek 到直播边缘、音轨日志 `held` 判定 |
| `Sources/VelaCore/PromptRules.swift` | Core | 常看收藏提示的记账与显示规则（`SuggestRules`：只有点「收藏」或关闭才算问过，超时消失不算；点击穿透期间不显示）、空列表按原因区分的文案（`EmptyListCopy`）、确认页高度随窗口收缩（`SheetLayout`） |
| `Sources/VelaCore/ShortcutGate.swift` | Core | 无修饰键快捷键何时可触发：年龄确认后、无弹层、不在文本编辑中 |
| `Sources/VelaCore/TimedFanOut.swift` | Core | 并发请求、各自带超时：失败或超时的项为 nil，其余照常返回，顺序与输入一致 |
| `Sources/VelaCore/WindowDragPolicy.swift` | Core | 画面左键拖窗判定：控件、拖动条、已登记的可点区域内按下都不拖，只有空白、垫底、视频本体可拖 |
| `Sources/VelaCore/LoadSlot.swift` | Core | 翻页占位（按代次）、`MoveRules`、`PagingState`、`IdleStart`；`StartupGate`（`bootstrap` 只进一次；年龄已确认时目录请求只领一次，未确认不领） |
| `Sources/VelaCore/Recommender.swift` | Core | `FeedSource`（猜你喜欢 / 热门 / 新开播 / 收藏）、汤普森采样排序、推荐理由、新开播排序、热门关联 |
| `Sources/VelaCore/Feedback.swift` | Core | 反馈分级 `Feedback`：有效停留秒数 → 命中 / 落空证据量，每日命中率口径 |
| `Sources/VelaCore/TagRarity.swift` | Core | 标签稀有度 `TagRarity`（候选集合上的归一化 IDF），排序与记账给常见标签降权 |
| `Sources/VelaCore/StreamerAffinity.swift` | Core | 主播亲和度：按观看账本累计时长与次数的有上限加分 |
| `Sources/VelaCore/TasteTargeting.swift` | Core | 按口味定向拉候选：选最喜欢的标签、生成 hashtags 定向查询、与常规候选合并去重 |
| `Sources/VelaCore/Taste.swift` | Core | 口味画像 `Taste`：按标签与性别的命中 / 落空证据（`Belief`）、衰减、停留记账、收藏 / 不感兴趣、每日命中率、暂停、重置、旧偏好迁移 |
| `Sources/VelaCore/BetaSampler.swift` | Core | Beta 分布取样（Marsaglia-Tsang Gamma，可注入随机数发生器） |
| `Sources/VelaCore/TasteSummary.swift` | Core | 面板汇总 `TasteSummary`（喜欢 / 不想看 / 性别 / 7 天命中率）与冷启动候选标签 |
| `Sources/VelaCore/HotTags.swift` | Core | `tag-table-data` 请求与解码（全站热门标签）、6 小时缓存与失败冷却 `HotTagCache`、冷启动候选与筛选面板提示的回退判定 |
| `Sources/VelaCore/TasteStore.swift` | Core | `TasteStore`：`feed-taste.json` 读写与首次从旧文件迁移 |
| `Sources/VelaCore/WatchLedger.swift` | Core | 实际观看累计与收藏提示判定；`watch-ledger.json` 持久化 |
| `Sources/VelaCore/Preferences.swift` | Core | 旧版兴趣权重与最近看过、`PreferenceStore`（只读，仅用于迁移与共用常量；旧的写入与学习方法已删） |
| `Sources/VelaCore/FavoritesBoard.swift` | Core | 收藏夹弹窗的数据模型：在线（人多的在前）与其余（离线、地区受限、未查询、不存在）分组、缩略图地址、人数与时长说明；离线按上次开播由近到远并显示「上次开播 3 小时前」 |
| `Sources/VelaCore/LastBroadcast.swift` | Core | `biocontext` 请求与解码（上次开播时间）、中文相对时间 `BroadcastAgo`、30 分钟缓存 `LastBroadcastCache`、并发查询 `LastBroadcastFetcher` |
| `Sources/VelaCore/Curation.swift` | Core | 收藏与屏蔽名单（含导入合并）、`CurationStore` |
| `Sources/VelaCore/FavoriteStatus.swift` | Core | `chatvideocontext` 请求与解码、`FavoriteStatus`、状态缓存 `FavoriteCache`、目录快照 `KnownCatalog`（每个房间带自己的取回时间）、查询计划 `FavoriteResolver`、并发查询 `FavoriteFetcher`、单批协调 `SupersedingRunner`、导出导入 `FavoriteExchange` |
| `Sources/VelaCore/DwellTracker.swift` | Core | 停留时长记账与热门关联计划、`shouldSettle`（来源切换时同名房间也结算） |
| `Sources/VelaCore/DeferredSaver.swift` | Core | 后台去抖串行写盘 |
| `Sources/VelaCore/Presence.swift` | Core | 离开淡出与点击穿透的状态与不透明度；`PointerPolling.needed`（指针轮询只在「离开后淡出」开启、窗口可见且有窗口、非老板键 / 休眠 / App 隐藏时开；手动暂停不影响） |
| `Sources/VelaCore/InfoVisibility.swift` | Core | 房间信息显隐判定：当前页只在控制栏显示或「始终显示」时显示，邻页始终显示；顶栏无信息按钮，开关在「播放」菜单与 I 键 |
| `Sources/VelaCore/MenuBarVisibility.swift` | Core | 菜单栏图标开关状态（`MenuBarExtra(isInserted:)` 与「播放」菜单开关共用）：相同值写入是空操作、不发 `objectWillChange`，因为 `MenuBarExtra` 每次场景更新都经 KVO 把可见性写回绑定，若照常发通知会在视图更新期间无限循环 |
| `Sources/VelaCore/Pager.swift` | Core | 滚轮手势解释（`WheelInterpreter`，含冷却） |
<!-- /AUTO-MANAGED:module-table -->

## 3. 依赖关系

<!-- AUTO-MANAGED:dep-dag -->
```
VelaApp ──> StageModel ──> StageView ──> PagerView ──> RoomCanvas ──> PlayerController
                │              └──> ChromeView / CurationPanel ──> FavoritesPanel / TasteView ──> TastePanel / ChallengeSheet / AgeGateView
                │
                ├─ StageModel+Catalog   ──> CatalogAPI, CatalogWindow, PageMerge, SiteSearch, TimedFanOut, FeedStore.mergeAfterCurrent / mergePageAfterCurrent, ChallengeGate (Core)
                │                          FeedStore, PagingState, LoadSlot, IdleStart, StartupGate, Recommender (Core)
                │                          SiteSidecar（质询后的备路）
                ├─ StageModel+Playback  ──> ChatContext.probe / StreamSourcing, StreamRequest / StreamDecoder, PlaylistLadder.mediaURL,
                │                          PreparedCache, SkipTracker, AwayRetry, RefreshTracker (Core)
                │                          PlayerController, SiteSidecar, PlaylistRelay.waitUntilListening
                │                          ChallengeGate, NetworkFailure, SkipTracker.attemptAuto (Core)
                ├─ StageModel+Quality   ──> QualityGovernor, PowerPolicy (Core), PlayerController.retier
                ├─ StageModel+Dwell     ──> DwellTracker, Taste, Recommender.related, DeferredSaver (Core)
                ├─ StageModel+Taste     ──> Taste, TasteSummary, Recommender.reason, HotTags / HotTagCache (Core)
                ├─ StageModel+Curation  ──> Curation, FeedStore.setBlocked, DeferredSaver (Core)
                ├─ StageModel+Favorites ──> FavoriteResolver / FavoriteFetcher / FavoriteCache / KnownCatalog / FavoriteExchange,
                │                          LastBroadcastFetcher / LastBroadcastCache (Core),
                │                          HTTPClient（URLSessionHTTPClient）
                └─ StageModel           ──> PlaybackPolicy, Presence, PlaybackFace / AssignIf / StartupGate, MoveRules, PortraitFit, SiteSearch, TasteStore, PreferenceQuarantine（经三个存储）, ShortcutGate, AudioPolicy, EmptyListCopy,
                                           CurationStore, SettingsStore, URLSessionHTTPClient,
                                           GlobalHotKey / SystemActivity (BossKey)

PlayerController ──> PlaybackPolicy, MuteAudioPolicy, RefreshTracker, PlaybackErrorCopy (Core)
                 ──> PlaylistRelay（App/PlaylistRelay.swift）──> PlaylistLadder, StreamRendition, RelayFeed, RelayPolicy, RenewalPolicy, RenewalBudget, UserAgent (Core)
SiteSidecar      ──> StreamDecoder, UserAgent, SidecarIdlePolicy (Core)
WindowBridge     ──> StageModel, WheelInterpreter, WindowDragPolicy, PointerPolling (Core), SiteSidecar.hold, ClickableRegionView
ChallengeSheet   ──> StageModel.retryAfterChallenge（站点确认完成后继续播放）

Core 内部：CatalogAPI/StreamAPI ──> HTTP, Models, EndpointBackoff；StreamSourcing ──> FavoriteStatus（ChatContext）, StreamAPI, EndpointBackoff, UpstreamRenewal（RenewalPolicy.isCancellation）；
          FavoriteStatus/LastBroadcast ──> EndpointBackoff；Curation ──> Preferences（上限与文件读取）；
          Recommender ──> Models, Taste, BetaSampler；Taste ──> Preferences（常量与迁移）；DwellTracker ──> Models, Preferences；
          PlaybackPolicy ──> PreparedCache（同文件）；MuteAudioPolicy ──> PlaybackPolicy.audioCheckStep；PlaylistRelay ──> MuteAudioPolicy；
          FeedStore.mergeAfterCurrent ──> PageMerge；CatalogAPI.fetchPages ──> CatalogWindow, PageMerge
```
App 引用 Core，Core 不引用 App。`PlayerController` 与 `StageModel` 的联动靠回调 `refreshRequested` 与 StageView 订阅 `readyUsername / playingUsername / failed`，没有循环持有。
<!-- /AUTO-MANAGED:dep-dag -->

## 4. 外部接口

全部主机为 `chaturbate.com`，除缩略图与 HLS 域名外。请求头统一由 `HTTPHeaders.applyBrowser` 加（固定 Safari UA、Referer、Origin、`X-Requested-With`），超时 12 秒。

<!-- AUTO-MANAGED:api-table -->
| 方法 | 路径 | 用途 | 所在文件 |
|---|---|---|---|
| GET | `/api/ts/roomlist/room-list/?limit&offset[&genders&regions&hashtags&new_cams]` | 在线房间目录（推荐 / 热门 / 新开播来源），站点自己的列表接口，不带联盟 / 推广参数。新开播来源带 `new_cams=true`（站点的新开播筛选，实测约 439 个；与性别 / 地区 / 标签参数叠加，叠加效果未在真机核对），`FeedSource.usesNewCams`。全站搜索带 `keywords=<词>`（实测有效），只取一页 90 条、不叠加性别 / 地区 / 标签（`SiteSearch.query`）。响应 `{rooms, total_count}`；单页 limit 上限 90（500 会 400，夹在 1 到 90）；`genders` 取 f/m/c/t 单值；`regions` 单值（逗号拼多个 400），代码实测为 AS / ER / NA / SA / O，对应 `Region` 五个枚举；`hashtags` 单个小写标签不带 #。无高清字段，`hd` 等参数被忽略。房间字段取 `username、gender、num_users、num_followers、tags、room_subject、img、current_show、has_password、display_age、location、start_timestamp`；`start_timestamp`（秒级 Unix 时间）换算 `secondsOnline`（缺失、非正、在未来按缺失）。推荐与新开播一次连续取 3 页（约 270 条，`FeedSource.pageCount`，遇短页或到底即停，去重，任一页失败整体失败），热门每页 40 | `CatalogAPI.swift`（`CatalogQuery.url`、`CatalogAPI.fetch`） |
| GET | `/api/chatvideocontext/<用户名>/` | 起播、预取、403 续期与换档的首选解析来源（`ChatContext.probe`：公开且 `hls_source` 非空直接用；编排者在 macOS 匿名实测 3 个公开房间，`hls_source` 与 ajax 的 `url` 路径相同、只有 `token` 参数、仅边缘主机不同，主列表 4 档加 2 条独立音轨；大陆网络下表现与 token 有效期未验证）；收藏来源按用户名查单个房间，不登录。实测：存在返回 200 + JSON（用 `room_status` 在线公开为 `public`、`hls_source`（公开却为空算地区受限）、`num_viewers`、`room_title`、`broadcaster_username`、`broadcaster_gender`、`age`、`start_timestamp`），约 0.17 秒；不存在 404；已删除 403 + JSON（带 `detail`）；需密码 401；站点质询返回 HTML。单次超时 8 秒，并发上限 4 | `FavoriteStatus.swift`（`ChatContext`、`FavoriteFetcher`），`StageModel+Favorites.swift` 调用 |
| GET | `/api/biocontext/<用户名>/` | 收藏夹里离线收藏的上次开播时间，不登录。响应含 `last_broadcast`、`time_since_last_broadcast`、`follower_count`、`location`、`about_me` 等，只用前两个：`last_broadcast` 按 ISO 8601、`yyyy-MM-dd HH:mm:ss`（UTC）或秒 / 毫秒级 Unix 时间解析，取不到时把 `time_since_last_broadcast` 当秒数（两个字段的实际格式未在真机核对）；未来的时间按现在算。404、401、403 + JSON 算「没有时间」，HTML 质询停止查询。只在打开收藏夹且缓存过期时查，单次超时 8 秒，并发上限 4，结果缓存 30 分钟 | `LastBroadcast.swift`（`BioContext`、`LastBroadcastFetcher`），`StageModel+Favorites.swift` 调用 |
| GET | `/api/ts/hashtags/tag-table-data/?sort=-rc&page=1&g=&limit=20` | 全站热门标签（按房间数降序），用于口味面板冷启动整理与筛选面板的标签提示。响应 `{hashtags:[...], total}`，元素按 `hashtag` / `room_count` 解码（字段名未在真机核对，没有 `hashtag` 的项跳过）。只在打开口味面板（冷启动时）或筛选面板且缓存过期时取，成功缓存 6 小时，失败或被拦截 60 秒内不重试、不弹确认页，期间回退到当前在线房间的常见标签 | `HotTags.swift`（`HotTags`、`HotTagCache`），`StageModel+Taste.swift` 调用 |
| POST | `/get_edge_hls_url_ajax/`（表单 `room_slug=<用户名>`） | 解析 HLS 主列表地址的备路：`chatvideocontext` 公开但 `hls_source` 为空、状态未知、请求失败、被质询、非 JSON 或退避中时才请求 | `StreamAPI.swift`（`StreamRequest`、`StreamDecoder.fetch`），`StageModel+Playback.swift` 调用 |
| 同上（流地址、收藏状态） | 同路径，由侧车 WKWebView 内 `fetch(..., credentials: "include")` 发出（收藏状态为 GET `chatvideocontext`，10 秒超时） | 直连被质询（403 或质询页）后的备路；收藏查询被拦后同样改走侧车，复用确认后的通关状态 | `SiteSidecar.swift` |
| GET | 上一接口返回的 HLS 主列表 URL（`#EXT-X-STREAM-INF`） | 取各档带宽，选出视频子列表与它引用的音轨子列表（只在起播 / 换档 / 重新解析时取，30 秒内过期） | `StageModel+Playback.swift`（`selectRendition`）、`PlaylistRelay.selectedRendition` |
| GET | 媒体列表 URL（LL-HLS） | 视频、音轨子列表由中继在后台各自刷新并改写（地址带 `session=`，按闲置过期，见第 10 节实测；持续请求则一直有效。撞上 403 时中继请上层续期，见第 10 节） | `PlaylistRelay.swift`（`PlaylistRelay.mediaPlaylist`） |
| GET | `https://chaturbate.com/auth/login/?next=/`（侧车初始化页）、`https://chaturbate.com/`（确认访问页） | WKWebView 加载，用于通过站点确认 | `SiteSidecar.swift`、`ChallengeSheet.swift` |
| GET | `https://thumb.live.mmcdn.com/riw/<用户名>.jpg`（直达房间的缩略图）与目录返回的缩略图 URL | 房间缩略图，`AsyncImage` 加载 | `Models.swift`、`RoomCanvas.swift` |
| GET | `http://127.0.0.1:<随机端口>/<uuid>/{master,video,audio}.m3u8` | 本地中继，AVPlayer 的实际播放地址；仅本机 | `PlaylistRelay.swift` |
<!-- /AUTO-MANAGED:api-table -->

非 JSON 退避（`EndpointBackoff.swift`）：流地址（`get_edge_hls_url_ajax`）、目录（`room-list`）、`chatvideocontext`、`biocontext` 的直连响应若正文既不是 JSON（去掉前导空白后不以 `{` / `[` 开头）也不是质询页，按「暂时不可用」处理，并按接口登记退避：同一接口 30 秒内（`EndpointBackoff.window`）不再发请求，各接口互不影响。进程内共用一份记录（`EndpointBackoffBox.shared`，`CatalogAPI`、`FavoriteFetcher`、`LastBroadcastFetcher`、`StreamDecoder.fetch` 默认使用）。`chatvideocontext`、`biocontext` 的 404、401 本身就是答案，不看正文。退避中：流地址抛 `StreamDecodeError.unavailable`（起播显示「暂时无法播放」并按原有规则自动跳过），目录抛 `EndpointBackedOff`（提示「网络不通，稍后再试」），收藏状态与上次开播时间算没拿到（不写缓存，进入原有 60 秒自动补查冷却）。原先「403 且正文不是 JSON 说明」在收藏查询里当拦截处理，现在正文是普通文本时改为退避，只有质询页才走确认。

质询识别（`looksLikeChallenge`，看正文前 4KB）：Cloudflare 的 `Just a moment`、`cf-mitigated`、`challenges.cloudflare.com`，以及年龄确认页 `Verify your age`。年龄页同样需要在网页里由用户确认一次，所以归为质询，走现有侧车与确认页流程（受 `ChallengeGate` 限次），不当普通失败。JSON 正文从不算质询（房间标题里可能恰好有这些字）。

中继响应：成功返回改写后的普通 HLS（200，`Cache-Control: no-store`）；上游无进展时见 5.2。路径只认 `/<id>/<名>.m3u8` 的最后两段；请求头超过 16KB 返回 400，未知 id 或名返回 404。`master.m3u8`：有独立音轨时是只含选中一档的本地主列表（`EXT-X-MEDIA` 音轨 + `EXT-X-STREAM-INF` 保留 BANDWIDTH / RESOLUTION / CODECS / FRAME-RATE / AUDIO），音频混在视频里时直接给视频媒体列表。

## 5. 数据流

### 5.1 起播

冷启动到起播：`StageModel.init` 末尾 `kickoffCatalogIfConfirmed` 在年龄已确认时就向 `StartupGate` 领唯一一次目录请求（不等首个视图出现；视图 `onAppear` 再进 `bootstrap` 时领不到，不重发；未确认则不领，`confirmAge` 后再加载）。猜你喜欢 / 新开播只请求第 1 页（`fetchOpening`），装入列表后立即 `playCurrent`；其余页由 `catalogRestTask` 在后台顺序续拉并并入当前项之后（`PageMerge.keepingCurrent`：当前项及其之前不动，之后整体重排；不换房、不再起播、失败只记日志），猜你喜欢的定向候选等续页并完后再发。首页没有可见房间时先等续页并入再继续翻页。收藏与热门等单页来源一次取完。

`StageModel.playCurrent` → 取主列表地址 → 选档 → `PlayerController.play`：

1. 地址来源：`PreparedCache.fresh`（预取缓存，保存已选档的 `StreamRendition`，即视频与音轨子列表地址，`maxAge` 20 秒，按档位上限区分）命中就直接用；否则 `resolve(username)` 取主列表再选档。缓存过期就重新解析；万一仍用到过期地址，中继遇 403 原地续期（第 10 节），不走播放项失败。
2. `resolve` / `resolveSourced`（起播、预取、403 续期、换档共用）：`sidecarUntil` 未到期期间一律走侧车。否则先 `ChatContext.probe` 请求 `chatvideocontext`，由 `StreamSourcing.first` 选择：公开且 `hls_source` 非空直接用（一次请求同时拿到状态与地址）；离开、私密、群组、离线、隐藏、需密码直接给出原因，不再请求 ajax；公开但 `hls_source` 为空、状态未知、请求失败或超时（8 秒）、被质询、非 JSON、退避中，退回直连 `get_edge_hls_url_ajax`（`StreamDecoder.fetch`）。ajax 解码出质询时 `sidecarUntil` 设为现在加 600 秒并改走侧车；其他网络错误加 120 秒并走侧车；取消原样抛出，不当网络故障。退回后由 `StreamSourcing.settle` 定论：`chatvideocontext` 说公开无地址、ajax 也给不出可播地址（公开无地址或状态说不清）时归为地区受限「当前地区不可观看」，其余以 ajax 为准。每次起播解析记一条事件日志 `start-source <chatvideocontext | ajax | sidecar>`（`com.vela.stage` 的 `renew` 类别，不记地址）。`StreamTicket.isPlayable` 为假时停播、给出中文原因并安排自动跳过（暂时离开除外，见 5.3）；流地址暂时不可用（403 / 503、非 JSON、退避中）显示「暂时无法播放」并自动跳过。
3. 选档：`playbackCap()`，常规为 `.infinity`（取最高档）；开了「离开后降低画质」且指针不在窗口内时取 `PowerPolicy.peakBitrate(resting: true)`，即不超过 896000。`selectRendition` 取主列表（超时 8 秒）后用 `StreamRendition.select`：选不超过上限加 20000 的最高档（没有就取最低档），并取该档 `AUDIO` 组里的默认音轨；上游把音频做成独立 `EXT-X-MEDIA` 音轨，只给视频子列表会没有声音。取不到时回落为未选档的主列表地址，由中继自己选。
4. `PlayerController.start` 经 `PlaylistRelay.localURL` 注册选中档与档位上限，返回 `http://127.0.0.1:<端口>/<uuid>/master.m3u8`（中继尚未绑定端口时返回 nil，直接播视频子列表）。中继只保留当前活跃源，换房 / 换档 / 停止时旧源整体作废。
5. 中继起播时取一次主列表选档（缓存命中时免取），记下视频与音轨子列表地址，之后只刷新这两路（主列表 30 秒就过期，子列表地址只要持续被请求就有效）。每路用 `PlaylistLadder.steadyPlaylist` 去掉 `EXT-X-SERVER-CONTROL / PART / PRELOAD-HINT / SKIP / RENDITION-REPORT`、版本改为 7、相对地址全部改绝对。
6. AVPlayerItem：`preferredPeakBitRate = 0`（档位已由中继选定）、`preferredForwardBufferDuration = 6`、`configuredTimeOffsetFromLive = 3` 秒、暂停时不拉流（`canUseNetworkResourcesForLiveStreamingWhilePaused = false`）。开播时记一条 `audio tracks=N enabled=M` 事件日志；`enabled=0` 记 error。**静音音轨选择**（`MuteAudioPolicy`）：本地主列表始终保留 `EXT-X-MEDIA` 独立音轨（删掉后取消静音就没声音）；静音时关掉 `appliesMediaSelectionCriteriaAutomatically`，可听组 `allowsEmptySelection` 才 `select(nil)`（日志 `audio selection clear`），并 `PlaylistRelay.setAudioSuppressed(true)` 停刷音频清单、AVPlayer 也不再取音频分片；组不允许空选择或读不到时保持选中、只靠 `isMuted`，不重载播放项，也不停刷音频。此时「开始播放」日志为 `audio tracks=N enabled=N held`（N 是可选项个数，不是在渲染的轨），不报「这路没有声音」；组还没读到时先复查再记。取消静音：选回默认音轨（`audio selection pick`）、恢复音频刷新，只有「音轨确实被拿掉过且正在播」才 seek 到直播边缘，暂停中不 seek。每个媒体项首次真正开播记一条 `playing-started <房间>`（Release 也有，房间名标 private），供 `scripts/perf-probe.sh` 算起播耗时。
7. 竖屏自动完整画面（`PortraitFit`）：画面就绪（`readyUsername` 变为该房间，`StageModel.noteReady`）时读 `AVPlayer.currentItem.presentationSize`，竖屏就记下该房间，横屏或尺寸为零时撤掉它的记录。不用 `chatvideocontext` 的 `is_portrait`：起播解析只取 `room_status` 与 `hls_source`，退回 ajax 时也没有该字段，读尺寸更可靠且不多发请求。画面实际是否铺满取 `StageModel.effectiveFill`（全局 `fitFill` 为真且当前房间不是被判为竖屏的那个），`fitFill` 与设置值不变；换到别的房间即恢复。在自动完整画面里按 F（`toggleFit`）只撤掉该房间这次的自动（同一房间重试、换档再出画面不再套用），不改全局设置；其他情况照常切换全局铺满。
8. 起播后异步预取下一个房间（`prefetchAhead`：解析（同第 2 步，先 `chatvideocontext` 再 ajax）、选档、缓存 `StreamRendition`，`FeedStore.prefetchUsernames` 默认前方 1 个）。

### 5.2 中继刷新与新鲜度（`RelayPolicy`、`RelayFeed`）

- 后台刷新：只刷新活跃源，视频与音轨各一个循环；每路同一时刻只有一个在途请求（AVPlayer 的请求与后台刷新共用它），换房 / 暂停 / 换档 / 停止时取消并作废在途请求，晚到的结果丢弃；比已知进度更旧的结果不覆盖。节奏：上次有新完整分片约隔一个分片时长（不超过 TARGETDURATION）再刷，没进展或失败约隔 T/2（下限 0.25 秒）。暂停期间不刷新（与「暂停不拉流」一致）。
- 新鲜度按「最后一个完整分片的媒体序号（`EXT-X-MEDIA-SEQUENCE` 加分片数，LL 的 `EXT-X-PART` 不算）何时前进」判断，每路各自跟踪。AVPlayer 请求时直接给最新已知内容；距上次前进不超过宽限（`graceFactor` 1 × 列表里的 TARGETDURATION，本流 2 秒，低于 AVPlayer 约 1.5 × T 的判定）就回放，超过则不立刻失败，请求挂起并按 `waitPoll`（T/4，下限 0.25 秒）反复刷新，最长等 `waitFactor` 1 × T（`RelayWait`），分片一前进就回新列表；正常发布抖动（间隔至多约 1.5 × T，实测偶尔 2.8 秒 / T=2）因此不再 503。等待总时长严格有界：每次刷新只拿剩余预算（`RelayWait.bounded` 竞速，单次上游请求最长 4 秒也不能拖过上限；超时的刷新自行收尾、结果照常落进状态），预算用完不再发起新刷新。等满仍无前进才返回 503，让 AVPlayer 看到失败并走恢复（上游持续 200 但分片不动同样如此）。上游请求失败按同一宽限与等待。中继生命周期内从不 302 到上游：某一路起播前完全拿不到列表时同样等满后回 503，由播放失败与一次自动重试路径处理。终态失败（播放项失败、看门狗放弃、失败界面）时 `PlayerController` 经 `PlaybackPolicy.relayShouldRefresh` 停止该源的后台刷新；重试、切房重新起播时再开启。
- 观测（`com.vela.stage` 的 `relay` 类别，只在事件发生时写，不含地址）：上游失败原因（`http-<状态码>` 或错误域 / 码）、请求超过 1 秒、分片恢复前进（间隔超过 2 秒）、回放旧内容的年龄、503（含无前进时长与最近上游失败）。

### 5.3 失败重试与重新解析

- 播放项失败：`PlayerController.itemFailed` 对同一房间静默重试一次（`retried`，换房间或非重试起播时重置），经 `refreshRequested` 让 `StageModel.refreshCurrent` 丢弃缓存、重新解析当前房间再起播；第二次失败才进界面（`failed` 加中文文案）。
- 暂停恢复三段式（`PlaybackPolicy.resumeAction`，`PlayerController.resumeIfNeeded`）：暂停不超过 `liveRejoinAfter`（15 秒）直接 `play()` 续播（播放项开着 `automaticallyPreservesTimeOffsetFromLive`，自动贴回直播偏移）；15 秒到 `reresolveAfter`（30 秒）重建播放项回直播点；超过 30 秒重新解析地址。依据：子列表地址闲置约 35 秒才过期。
- 音轨复查：开播后 `audioCheckDelay`（2 秒）检测可听音轨，没有则不立刻报错，再等 `audioRecheckDelay`（3 秒）复查，仍没有才记 error（`audio tracks=… (no audible track)`），通过记 info；判定 `PlaybackPolicy.audioCheckStep`，换项 / 停止时取消（`audioTask`）。
- 起播看门狗：`PlaybackPolicy.startupTimeout` 16 秒内没出画面报「迟迟没有开始」；只在应当播放时计时，暂停或等待新地址（`refresh.pending`）时不报。
- 卡住看门狗：已开播的项持续不在播放超过 `PlaybackPolicy.stuckAfter`（10 秒）且播放门控允许、不在等待新地址时，走上面同一条「重新解析并重建」路径，占用该房间那一次自动重试预算；预算用完不再恢复，改为显示失败与重试（不循环）。暂停 / 老板键 / 休眠 / 换项 / 停止时取消。
- 刷新任务校验 `playGeneration` 与当前房间，被取代就放弃并清 `RefreshTracker`，旧项不会被永远挡住。
- 解析失败（离线、私密、地区等）：显示原因，1.6 秒后（`SkipTracker.noticeSeconds`）沿用户最后一次划动方向自动跳过；连续自动跳过上限 3（`SkipTracker.limit`），到限停下并提示重试。自动跳过不清手动暂停（`MoveRules`）。播放失败不自动跳走，停在原房间显示原因。
- 暂时离开（`away`，`AwayRetry`）：不自动跳过，停在房间显示「暂时离开，稍后自动重试」，并排一个一次性延迟任务（`awayTask`，`AwayRetry.delay` 60 秒）：到点仍是同一房间、`playGeneration` 没变、播放门控允许（`AwayRetry.shouldFire`）才重新起播一次。仍离开就显示「暂时离开，可稍后手动重试或换个房间」，不再自动重试，由用户决定。`playCurrent` 开头取消该任务并在换房时清零（`AwayRetry.enter`），成功起播清零（`AwayRetry.clear`）；暂停等播放门控关闭时立即作废待触发任务（`AwayRetryTimer.gateChanged`，到期前恢复也不再触发，提示改为手动重试文案）。自动重试次数只在到点真正开始解析时记（`AwayRetry.beginRetry`），被取消的排程不消耗。直连取流在退避窗口内不发请求时抛 `EndpointBackedOff`（与真实 403 / 503 的 `.unavailable` 分开）：留在当前房间显示限流提示、窗口结束（`EndpointBackoff.window`）后自动再解析，不跳房、不耗 `SkipTracker`；目录侧同样用退避文案。解析途中已暂停时（门控关闭），away 与退避结果只给手动重试提示、不建自动重试任务（`AwayRetry.gated`、`EndpointBackoff.pendingNote`）。没有常驻定时器。

### 5.4 暂停与恢复回直播点

门控 `PlaybackPolicy.allowed` = 窗口可见 且 非手动暂停 且 非老板键隐藏 且 系统非休眠 / 锁屏，是起播、重试、换档、恢复的唯一开关。恢复时按暂停时长（`resumeAction`）：不超过 15 秒（`liveRejoinAfter`）直接续播；15 秒到 30 秒之间重建播放项回直播点（`rejoin`）；达到 30 秒（`PlaybackPolicy.reresolveAfter`）重新解析地址（`reresolve`）。

停播释放：停播与窗口不可见（`PlaybackPolicy.windowInvisible`：被遮挡 / 最小化、老板键、锁屏休眠、App 隐藏任一成立）都持续满 30 秒（`releasePausedItemAfter`，取两个时长的较小值，刚好等于也算）才由 `PlayerController.releasePausedSource` 释放：取消观察与看门狗，`replaceCurrentItem(nil)`，`PlaylistRelay.deactivate()` 丢掉该源状态，日志 `paused-release`。窗口可见时不释放（手动暂停要保留最后一帧，释放会黑屏）；释放时同时清掉 `readyUsername`，画布回到垫底缩略图（不留黑色播放层）；手动暂停时窗口再显示只见垫底与暂停图标，按播放后才重新解析并出加载线；释放后恢复一律走重新解析，不再读旧项。续页并入后（`followCatalogRest`）与 `loadPage` 一样消费 `idleStart`，空列表停播后有房间即起播。窗口可见性由 `StageModel.syncPlayerHidden` 经 `PlayerController.setHidden` 同步。

### 5.5 离开后降画质

`QualityGovernor`：开关打开且指针离开持续 10 秒（`restDelay`）降到低档，指针回来恢复满档，两次切换至少间隔 8 秒（`minInterval`）。`applyQuality` 换档前重新解析主列表地址（旧地址会 403），取档后 `PlayerController.retier` 重建播放项并保留上一帧；取档期间房间、暂停、指针状态变了就放弃重排。该开关默认关闭。

### 5.6 猜你喜欢与口味画像（全部在本机）

- 来源：顶栏四项为猜你喜欢、热门、新开播、收藏。旧设置值 `recommended` 读取时映射到猜你喜欢（`FeedSource.restore`）。
- 排序（`Recommender.recommend`，按标签的 Beta-Bernoulli 汤普森采样）：每个标签与性别维护命中证据 α 与落空证据 β（先验 α=β=1）。每次排序时每个标签从自己的 Beta(α, β) 抽一个样本（同一标签一次排序只抽一次）。房间的口味分 = 其标签样本里最高 3 个的均值（无标签时抽一次先验），再以 25% 比例混入性别样本；取最高 3 个而不是全部求均值，是为了不让泛标签把真正对味的标签稀释掉。`score = 口味分 + 0.04 * 热度 - 0.6 * 看过降权 + 1.0 * 收藏`：热度只做平局区分；看过降权 6 小时内线性衰减；收藏加分大于降权上限。探索来自后验方差（证据少的标签偶尔抽到高值），不再有固定随机位；随机种子可注入（`SplitMix64`），同种子同输入同顺序。
- 不想看：带「不想看」标签或性别的房间由 `RoomQuery.hidden`（口味键集合）在可见列表里过滤，不从目录 `catalog` 删除、排序也不再删房间（用户明确的意愿，降权会在翻到页尾时冒出来；删除则「恢复」无法找回）；恢复后当前位置之后按新画像重排，房间回来。全部被过滤时可见列表为空（与屏蔽一致）。口味集合变化经 `commitTaste` 同步过滤条件，并重算当前及其后房间的推荐理由（收藏变化同理）。
- 命中与落空（`Taste.recording`）：在房间里实际播放可见时长 ≥20 秒算命中，<3 秒划走算落空，3 到 20 秒不计；自动跳过的失败房间、被屏蔽的房间不产生信号。每个标签（每房间最多 8 个）加 1 份证据，性别加 0.5 份。猜你喜欢以外的来源（热门、新开播、收藏）的停留也学习，但证据减半，也不进每日命中率（那里的选择不是画像推荐出来的）。收藏按 3 次命中（原为 2，见下方反馈分级）；「不感兴趣」按 3 次落空并记一次落空。
- 衰减：证据在读取时按 7 天半衰期向先验折算（`Taste.posterior`），累加前先衰减旧值；用户标的「不想看」不衰减。标签最多 500 个（超出淘汰证据最弱的，「不想看」优先保留）。
- 推荐优化（四项均已在 App 启用：停留结算走 `Taste.recording(watched:rarity:)`，排序传 `RankSignals`（`StageModel.rankSignals`：`TagRarity`、`StreamerAffinity`、屏蔽集合），「猜你喜欢」首次拉取（offset 0）按口味定向补页；`TagRarity` 用 `StageModel.regularCandidates`（常规页，最多 600 个，不含定向页）计算；定向页失败只记日志，不影响常规页；信号为空时与旧排序一致）：
  - 反馈分级（`Feedback.grade`、`Taste.recording(room:watched:source:now:rarity:)`）：按有效停留秒数给权重，<3 秒弱落空 0.3（快速扫过大多是浏览），3 到 20 秒不计，≥20 秒命中 `1 + 0.5 * log3(秒/20)`（20 秒 1、60 秒 1.5、180 秒及以上封顶 2）；收藏 3 次命中；「不感兴趣」仍是 3 次落空。每日命中率口径不变（≥20 秒记命中、<3 秒记落空，`Feedback.dailyOutcome`）：权重只改学习强度，不改「算不算对味」的计数。旧入口 `recording(room:dwell:)` 已删除。
  - 常见标签降权（`TagRarity`）：在常规候选集合上算 `idf = log((N+1)/(df+1))`，除以单房间标签的 idf `log((N+1)/2)` 归一化，夹在 [0.1, 1]；候选里没出现的标签按 1。排序时每个标签样本向 0.5 收缩：`0.5 + w * (样本 - 0.5)`，几乎每个房间都有的标签不再区分房间，探索仍来自后验采样。记账时每个标签的证据乘 w（不再按标签个数平分：平分会让标签多的房间学得更慢、把稀有标签的证据也稀释掉；乘 w 已让常见标签只得一小份、解释这次选择的稀有标签得到接近整份）。性别证据不受影响。
  - 主播亲和度（`StreamerAffinity.score`）：观看账本里该主播的累计时长对数饱和（约 60 分钟到顶）与有效次数线性饱和（5 次到顶）按 7:3 混合，乘上限 0.5；低于收藏加分 1.0，且与收藏加分不叠加（收藏的取收藏加分），屏蔽的与没有记录的为 0。
  - 定向拉候选（`TasteTargeting`）：从画像选至多 3 个标签（未标「不想看」、命中证据 ≥2、后验均值 ≥0.55、常规候选上的稀有度 ≥0.3，按均值排序），每个生成一页 `hashtags` 定向的 `CatalogQuery`（90 条，沿用当前性别 / 地区；用户已选标签时不生成）。`merging` 把额外页接在常规候选之后，按用户名去重，常规候选顺序不变；合并后的整体参与排序。稀有度要用常规候选算，不含定向页（否则被定向的标签在候选里变常见）。
- 每日命中率：只统计猜你喜欢里的命中与落空，按本地日期记两个计数，最多保留 30 天；面板显示最近 7 天命中率与每日条形，样本少于 10 次显示「数据还不够」。
- 推荐理由（`Recommender.reason`）：仅猜你喜欢显示一行。收藏的主播显示「你收藏的主播」；否则取该房间后验均值最高的 1 到 2 个标签（命中证据 ≥1 且均值 ≥0.6）显示「因为你常看 a、b」；没有足够依据显示「试试新口味」。理由与排序在 `StageModel.arranged` 里同时算出。
- 我的口味面板：顶栏闪光图标。显示喜欢的标签（按后验均值，带强弱条与命中次数）、不想看的标签、性别偏好；每项可加强（+3 次命中并撤销不想看）、不想看、移除；画像标签少于 5 个时顶部显示 12 个画像里还没有的标签供一键整理：优先全站热门标签（`Taste.suggestedTags(hot:fallback:)`，缓存过期时打开面板取一次 `tag-table-data`），取不到、冷却中或全都已在画像里时退到当前在线房间最常见的标签；筛选面板标签输入下方同样列出 8 个热门标签（`HotTags.filterHints`，回退规则相同），点一下填入并重新加载；暂停学习（不更新 α / β，排序照用现有画像，最近看过仍记）；重置画像（清标签与每日统计，保留最近看过，二次确认）；清除全部偏好数据（另清最近看过与旧版偏好文件，作废当前房间停留，二次确认）。「播放」菜单：对当前房间不感兴趣（⇧⌘N）、我的口味…。
- 迁移：`feed-taste.json` 不存在时读旧 `feed-prefs.json`，正权重按旧版步长（标签 0.5、性别 0.3）折回命中次数，负权重折回落空次数，保留原时间戳与最近看过；旧文件保留到用户清除全部偏好数据。
- 停留只累计「允许播放且该房间此刻真在播」的时间；播放失败的房间、被屏蔽的房间不产生信号。停留的来源归属取当前列表实际所属的来源（`loadedSource`，列表整体替换时经 `updateFeed(installing:)` 先更新再结算），不是用户刚选中的来源，所以异步重载落地前的划动、搜索、自动跳过仍记在旧来源名下。目录快照（`knownCatalog`）与推荐理由只在翻页响应通过代次检查之后才写（`LoadCommit.apply`），被作废的旧响应不留痕迹。
- 热门关联：同一房间停留满 30 秒（`relatedAfter`）触发一次，仅猜你喜欢来源，`Recommender.related` 按标签 Jaccard 加同性别 0.1 取前 3 个未看过的，`FeedStore.promoteAfterCurrent` 挪到当前之后。
- 全站搜索（`SiteSearch`、`StageModel.searchSite`）：筛选面板搜索框打字只做本地过滤（`SiteSearch.onEdit`），回车时先按用户名定位，再看本地过滤后有没有房间（`SiteSearch.onSubmit`）：有就照旧（合法用户名直达，否则本地过滤），没有才用 `keywords` 查一页。结果去重、去掉屏蔽的，替换当前列表成为临时列表（`siteSearch` 记搜索词）：保持接口顺序、不写推荐理由、不翻页、不按收藏名单限定，搜索框文字不再过滤它；期间继续打字不发请求，换词回车再查一次。停留在「猜你喜欢」下按热门记（证据减半、不进每日命中率，`SiteSearch.dwellSource`）。清空搜索框，或切换来源、性别、地区、标签等触发重新加载时结束搜索、装回原来源。没有结果或请求失败时，搜索词是合法用户名就直达，否则提示；被站点质询时经 `allowChallengePrompt` 限次，放行则登记 `ChallengeResume.siteSearch`，通关后重跑该搜索，被抑制则提示失败。回车的本地匹配数取 `FeedStore.localMatches`（不含因 retained 保留的当前房间）；搜索任务带操作代次（`SearchEpoch`），本地提交、收藏选择、退出搜索、重新加载时作废在途搜索，成功、失败、质询分支返回后先核对代次；清空搜索框在 `previewSearch` 路径即退出。新一次搜索落空或失败而已有上一次搜索时，先重载回原来源再直达或提示（`SiteSearch.missRecovery`），不改写旧临时列表；恢复期间任务所有权不丢（`reload(keepingSearchTask:)`），返回后重新校验代次再直达或提示（`SiteSearch.missOutcome`）。清空搜索框也取消首次搜索在途的请求；取消搜索同时清掉它登记的 `ChallengeResume.siteSearch`；任务开始前先核对代次与取消状态（`SearchEpoch.canStart`），加载中只由认领它的搜索复位。
- 其他来源：热门保持接口顺序；新开播用站点的 `new_cams=true` 筛选取回，仍按开播时长从短到长，每页追加后对当前之后部分整体重排；收藏来源见 5.8。

### 5.7 收藏、屏蔽、翻页

- 屏蔽写入 `RoomQuery.blocked`，所有来源一律过滤；屏蔽当前房间时切到之后的下一个（没有则取之前一个）并作废停留。收藏与屏蔽互斥，各最多 300 个。
- 翻页：当前位置距末尾不足 8 个（`shouldLoadMore` 的 `threshold`）时拉下一页；页大小每页最多 90（接口上限 `CatalogQuery.maxPageSize`），猜你喜欢与新开播每次连续取 3 页（`FeedSource.pageCount`），热门每页 40；收藏来源一次给全，不翻页。可见列表为空（整页被屏蔽或过滤）时继续翻，空页上限 `FeedStore.maxBlankPages` = 10；到末尾（`nextOffset >= total` 或整页重复 / 为空）停止。按列表代次占位（`LoadSlot`），旧来源的残留请求不挡新来源。
- 切换来源失败回滚到原来源；重叠重新加载失败时游标恢复到最后一次成功加载的位置（`PagingState`）。

- 收藏提示（不自动收藏）：停留结算时把有效观看记入 `WatchLedger`：开始时刻取本次观看首次真正播放的时刻（`DwellTracker.startedAt`，实时判定与持久化同一口径），秒数去掉暂停学习的时段（`setLedgerExcluded` / `Settled.ledgerSeconds`）；当前停留被作废（清除偏好、不感兴趣、屏蔽）后不再提示、不再入账。除累计满 10 分钟外，还会在「本次观看满 60 秒且会凑够第三次」时安排一次性唤醒（`WatchLedger.secondsUntilNextSuggestCheck`），不新增常驻定时器。同一主播累计实际观看满 10 分钟，或有效观看满 3 次（每次至少 60 秒且相邻两次开始相隔 30 分钟），且未收藏、未屏蔽、未提示过，则在画面上方弹出 6 秒横幅「常看 xxx，收藏吗？」并记为已提示；点「收藏」与心形同逻辑。数据在 `watch-ledger.json`，清除全部偏好数据时一并删除。

### 5.7a 心形按钮与收藏夹弹窗

- 心形（顶栏右侧）：空心 = 当前房间未收藏，红色实心（`Theme.favorite`）= 已收藏。左键单击 = `toggleFavorite()`；收藏成功不弹提示（红心本身即反馈），取消收藏在画面上方提示「已取消收藏 <用户名>」并带「撤销」；右键 = 打开收藏夹。没有当前房间时图标变暗、左键不做事，右键仍可打开。
- 判定：覆盖在图标上的 `PressView`（AppKit）收 mouseDown / mouseUp / rightMouseDown。左键按多久都一样，mouseUp 时指针仍在按钮内才算单击，移出按钮松开则忽略；右键按下直接打开收藏夹。不用 SwiftUI 的 tap 手势，是因为它没有右键。
- 快捷键：⌘D 收藏 / 取消收藏当前房间，⇧⌘F 打开收藏夹，⇧⌘B 屏蔽当前房间。
- 弹窗：宽 380，列表区高度随窗口高度收缩（`Theme.favoritesListHeight`，最矮窗口 640×360 下弹窗不超出窗口），超出滚动。打开时若有收藏的状态缓存过期就补查一次（`refreshFavoritesIfStale`，沿用单批查询与缓存，被站点拦截时不自动弹确认页）。之后对状态为离线、且没有 30 分钟内上次开播时间的收藏查 `biocontext`（`refreshLastBroadcastsIfStale`：同一时间一批，并发 4，`sidecarUntil` 未到期时经侧车；被拦截或有没拿到的进入 60 秒自动补查冷却，不弹确认页；「刷新状态」后也补查一次）。离线行显示「上次开播 N 分钟 / 小时 / 天 / 个月 / 年前」（不到 1 分钟为「刚刚」，`BroadcastAgo`），离线分组按上次开播由近到远，没有时间的排在离线末尾，仍显示「离线」。「在线」分组整行点击切到该房间并关闭弹窗；移除不弹确认，底部出现「已移除 xxx，撤销」5 秒；屏蔽名单是弹窗里的二级页。

- 菜单栏（`MenuBarFavorites`，`.window` 样式）：点一行 = `playFromMenuBar`，先 `revealMainWindow`（老板键隐藏中走 `toggleBoss` 恢复；否则 `unhide`、还原最小化、置前）再 `playFavorite`；「打开收藏夹」= 置前后 `showCuration = true`。不新增定时器。

### 5.8 收藏来源与在线状态

- 来源「收藏」不登录。名单是本机 `Curation.favorites`；切到该来源、点「重试」、收藏夹里点「刷新状态」时才查，没有后台轮询。
- 查询顺序（`FavoriteResolver.plan`）：先看 `FavoriteCache`（3 分钟内的结果直接用），再看 `KnownCatalog`（最近一次取回的猜你喜欢、热门、新开播目录，每个房间按自己被取回的时间算 3 分钟，追加页不续期早先页面的房间；命中且公开才算在线，命中但非公开算离线），其余才走 `chatvideocontext`，并发上限 4（`FavoriteFetcher`）。同一时间只有一批查询（`SupersedingRunner`）：新请求取消在途批次并等它收尾后再开新批，旧批次的调用方改拿最新一批的结果，写回缓存只由当前批次做且基于写回时的缓存，`favoritesChecking` 在最后一批结束时才变 false。网络失败或超时的不写缓存，状态显示「未查询」。
- 状态：在线（公开可播放）、离线（含私密、群组、离开、需密码）、地区受限（公开但 `hls_source` 字段在而为空，`FavoriteStatus.restricted`，收藏夹显示「当前地区不可观看」，排在离线之后；与其他结果一样缓存 3 分钟，不反复补查；字段缺失或为 null 时不下结论，照旧算在线）、不存在（404 或 403 已删除）。直连被站点拦截（HTML 质询页）时，没答案的部分改经侧车（`SidecarHTTPClient`，浏览器里的会话）重查并让 `sidecarUntil` 延后 600 秒；侧车也被拦才停止发起新请求，走确认流程。确认完成后 `retryAfterChallenge` 重跑被中断的任务（`challengeResume`：目录加载重跑 `reload`，收藏查询重跑 `checkFavorites`；多个发起者取「做得最多」的，`reload` 优先于 `checkFavorites`，确认后若已切到收藏来源则改为 `reload` 以装入列表），首次加载不再停在空白，切来源时也不再继续播旧来源的列表。
- 收藏来源的列表只含在线房间，按人数从多到少、同人数按收藏先后；一次给全，没有后续页。收藏来源只显示当前收藏名单里的房间（`RoomQuery.only`，名单变化时随 `FeedStore.setFilters` 同步，屏蔽后解除屏蔽不会让已移出收藏的房间重新出现）。收藏夹里点在线的收藏：`FeedStore.reveal` 保证播放的就是它，搜索条件会挡住目标时清掉，已在列表里就切过去，否则插到当前房间之后播放。
- 收藏来源下增删收藏会重取列表（命中缓存不发请求）；屏蔽当前房间不触发重取。导出为 `{"app","version","favorites":[...]}` JSON；导入接受该格式或纯字符串数组，格式不合格的丢弃，已有的保持先后、新的接在后面，遵守 300 个上限，屏蔽名单里的不收（`Curation.importing`）。

## 6. 本地存储

目录 `~/Library/Application Support/Vela/`（`PreferenceStore.defaultURL`；取不到时退回临时目录）。

| 文件 | 内容 | 上限 |
|---|---|---|
| `feed-taste.json` | `Taste`：标签 / 性别的命中与落空证据（带时间戳、不想看标记）、最近看过、每日命中率、暂停学习开关 | 标签最多 500 个，看过最多 1000 条且只留 24 小时，每日计数最多 30 天；标签 / 用户名最长 64 字符（写入时截断，读取时丢弃超长键）；读取时文件超过 1,000,000 字节按空画像处理；只存本机，不上传 |
| `feed-prefs.json` | 旧版 `Preferences`，只在 `feed-taste.json` 不存在时读一次用于迁移，不再写入 | 同上限；清除全部偏好数据时删除 |
| `feed-curation.json` | `Curation`：收藏与屏蔽名单（用户名） | 各最多 300 个，单名最长 64 字符，超出丢最旧；读取同样受 1,000,000 字节上限约束 |
| `watch-ledger.json` | `WatchLedger`：各主播累计实际观看秒数、有效观看次数、是否已弹过收藏提示 | 最多 500 个用户名，单名最长 64 字符，按最近使用淘汰；读取超过 1,000,000 字节按空处理；「清除全部偏好数据」时一并删除 |

- 写入：`DeferredSaver` 合并为后台串行队列上的一次写，默认延迟 2 秒，原子写入（`.atomic`）；读写失败不抛出，读退回空值，写失败只记日志。退出时 `flushForTermination`（监听 `willTerminateNotification`）同步结算最后一个房间并落盘。
- 不再使用钥匙串。旧版本遗留的钥匙串条目（service `com.vela.stage`，account `session` 与 `username`）与网页 sessionid cookie 在首次启动时清掉一次（`LegacyLoginCleanup`，标记键 `legacyLoginCleared`）。
- UserDefaults（suite `com.vela.stage`）：`ageConfirmed`、`pinned`、`conditionalOpacity`、`clickThrough`、`restingOpacity`（默认 0.42）、`engagedOpacity`、`restSavesPower`、`publicOnly`、`fitFill`、`volume`（默认 0.85）、`muted`、`hintSeen`、`layoutRevision`、`gender`、`region`、`tag`、`source`（旧值 `following` 读取时忽略，退回猜你喜欢；旧值 `recommended` 映射为猜你喜欢）、`legacyLoginCleared`。旧版遗留的 `hdOnly`、`campaign` 不再读取，留在文件里无副作用。
- WKWebView 站点数据存在系统默认数据存储里（确认访问留下的 cookie 在其中）。
- 全站热门标签只在内存里：`HotTagCache` 缓存 6 小时。
- 收藏的在线状态只在内存里：`FavoriteCache` 缓存 3 分钟，不落盘。离线收藏的上次开播时间同样只在内存里：`LastBroadcastCache` 缓存 30 分钟。

## 7. 安全边界

- 中继只绑 `127.0.0.1`（`requiredLocalEndpoint`，端口由系统分配），不监听其他网卡；路径里的 id 是每次起播新生成的 UUID。
- 不保存任何账号凭据：没有登录，`URLSessionHTTPClient` 与中继的会话配置都关闭 cookie 存取。收藏夹导出文件只含用户名。
- 日志（`os.Logger`）只记事件名、状态码、错误域与码、码率等；不记地址、cookie、令牌。系统错误原文（`localizedDescription`）只以 private 进日志，不进界面，界面用 `PlaybackErrorCopy` 的中文文案。
- 侧车 WKWebView：只在站点质询时代发流地址请求，离屏（y = -4200）挂在主窗口里保持真实布局尺寸；弹出确认页时 `suspend`，关闭后 `resumeIfNeeded`；闲置 600 秒销毁，下次用到重建；页面加载超时 18 秒。播放本身不经过 WebKit。
- 用户名（直达房间、手动添加收藏、导入收藏）限 2 到 30 位，只允许字母、数字、下划线（`Room.isValidUsername`）；导入文件超过 1,000,000 字节拒绝。
- 目录请求不带任何联盟 / 推广 ID（原联盟 ID `j1IHB` 来路不明，每次拉目录都会把浏览记到别人的推广账号下，已移除）。
- 首次启动须确认已满 18 岁，未确认前不加载目录、不播放。

## 8. 省电策略

- 播放门控（5.4）：窗口被遮挡 / 最小化（`occlusionState` 不含 `.visible`）、手动暂停、老板键隐藏、锁屏 / 屏幕睡眠 / 系统睡眠 / 切换用户，任一成立即暂停并不拉流。
- 指针轮询：仅「离开后淡出」开启、窗口可见，且不在老板键隐藏、系统休眠 / 锁屏、App 隐藏（`PointerPolling.needed`）时才有 0.4 秒（容差 0.2 秒）的定时器（穿透时收不到鼠标事件，必须轮询）；否则停掉，改用 `mouseMoved` 事件与跟踪区域，恢复轮询时立刻对一次位置。手动暂停且窗口可见时保留轮询，指针移入控制栏才能淡入。闸门重算（`applyPower`）、老板键、App 隐藏 / 显示后经 `refreshPointerPolling` 重判。
- LIVE 圆点只在「当前页、这一间正在播、信息可见」时脉冲（`RoomCaption.pulse`，条件同时用作 `.id`），停播、信息块透明、App 隐藏时拆掉动画；脉冲走 `CABasicAnimation`；加载线与脉冲遵守「减少动态效果」。实测：停播后 CPU 约 2.5% 降到约 0.1%。
- 同值不写：`StageModel` 的 `readyUsername` / `playingUsername` / `failed` 经 `PlaybackFace` 镜像，`presence`、`chromeVisible`、`playbackNote` 经 `AssignIf`，`WindowBridge.apply` 与播放层 `updateNSView` 只在真正变化时写，避免稳态下 SwiftUI 视图图被反复标脏。
- 静音不取音频（5.1 第 6 步）：音频渲染线程、音频分片与音频清单轮询都停。
- 停播且窗口不可见满 30 秒释放播放项与中继源（5.4）；侧车网页视图空闲 30 秒销毁（`SidecarIdlePolicy`）；`RoomThumbnailCache` 只存长边约 192 像素的小位图、总量约 2 MB。
- 实测数字见 `docs/PERF.md`。
- 离开后降低画质（5.5）；`preventsDisplaySleepDuringVideoPlayback` 仅在「参与中」（指针在窗口内或在操作面板，淡出关闭时恒为是）、允许播放且有播放项时打开。
- 起播看门狗、停留关联任务、降画质计时都按需创建，暂停 / 缓冲 / 隐藏时不排唤醒。
- 老板键 ⌃⌥⌘V：隐藏应用并停止解码，再按恢复；用 Carbon 热键，不需要辅助功能权限；若在 Dock 或 ⌘Tab 再次显示，隐藏状态作废（手动暂停仍保持）。注册失败只记日志。

## 9. 构建与测试

- 逻辑测试（仓库根目录）：`swift test`（`Tests/VelaCoreTests/`，XCTest，只测 `VelaCore`）。
- 构建应用（与 CI 一致）：

```sh
xcodebuild -project Vela.xcodeproj -scheme Vela -destination 'platform=macOS' \
  -configuration Debug CODE_SIGN_IDENTITY=- CODE_SIGNING_ALLOWED=NO build
```

- CI：`.github/workflows/macos.yml`，每次 push 与 pull_request 在 `macos-latest` 上依次跑 `swift test` 与上面的 `xcodebuild`。
- 新增或重命名 Swift 文件后，`swift test` 不会发现 pbxproj 漏登记，必须跑 xcodebuild。
- Release 配置：仅 `ARCHS = arm64`、`DEAD_CODE_STRIPPING = YES`、`DEPLOYMENT_POSTPROCESSING = YES` 加 `STRIP_INSTALLED_PRODUCT = YES`（`STRIP_STYLE = non-global`）剥符号，保留 dSYM（`dwarf-with-dsym`）；安装包约 3.5 MB（原约 10.7 MB，见 `docs/PERF.md`）。Debug 配置不变。
- 本机性能探针：`scripts/perf-probe.sh [--duration 秒] [--app 路径] [--build] [--scenario play|idle] [--idle-method lock|hide]`。后台隐藏启动 Release 包（`open -g -j -n`），静音播放后每 5 秒采样，末尾输出一行 JSON（同时写 `/tmp/vela-perf-result.json`）：`cpu_avg` / `cpu_p95`、`rss` / `footprint`、`wakeups_avg`、`threads`、`children_*` 与 `vtdecoder_cpu_avg`（硬解 XPC 单列）、`net_in_mb_per_min` 及 tcp / udp / 回环分列、`http_requests_per_min`、`startup_to_play_s`（以 `playing-started` 日志为起点）、`runtime_issues`、`app_size_mb`。`play` 场景为稳态播放；`idle` 场景先起播，再投递 `com.apple.screenIsLocked`（默认 `lock`，走 `SystemActivity` 与播放闸门）使 App 停播，10 秒后采样，结束前投递解锁。注意：脚本会临时用 `defaults write` 把 `com.vela.stage` 的 `ageConfirmed` 置为真、`muted` 置为静音，开始前备份整个偏好域，结束时原样恢复；已有 Vela 在运行时拒绝测量。基线、预算草案、热点清单与各轮优化实测在 `docs/PERF.md`。
- 实跑门判定：`python3 -I scripts/perf_gate.py <基线.json> <结果.json> [--scenario play|idle]`（仅标准库）按 G-mac 阈值逐项对比，缺字段、NaN、`skipped`、`samples=0`、起播时间不大于 0 一律不合格并非 0 退出；`scripts/test-perf-gate.sh` 是它与探针起播超时的负样本回归（全部被正确拒绝则退出码 0，改动 `perf-probe.sh` 或 `perf_gate.py` 后必跑）。`perf-probe.sh` 在起播超时、idle 跳过、采样期间进程退出、结果不完整时同样非 0 退出，`runtime_issues` 另计 `AttributeGraph: cycle`、`Modifying state during view update` 与 `com.vela.stage` fault 级日志。
- 应用图标（A2 帆）：资源在 `App/Assets.xcassets/AppIcon.appiconset/`（10 个槽位、7 个像素文件），build setting `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon`；源文件与生成脚本在 `design/icon/vela_icons.swift`（64 及以上完整绘制，32 与 16 为单独像素版）。

## 10. 已知局限与待办

- 大陆网络环境未实测：目录、流地址、HLS 分片、缩略图域名在大陆的可达性与速度都没验证。
- `App/` 界面层没有自动化测试，只有编译验证；播放门控、降画质等状态逻辑的可测部分已下沉到 `VelaCore`。
- 子列表地址寿命（2026-11-17 在 m5p 实测，大陆网络，地址带 `session=`）：寿命按闲置计，不是绝对寿命。每 10 秒请求一次连续 6 分钟（37 次）全 200；从取档起闲置 30 与 32 秒仍 200，35 秒起部分 403，38 与 41 秒全 403，45、60、90 秒全 403；间隔 30 秒的持续请求（含 touch 后再闲置 30 秒、总龄 50 秒）仍 200。取档后一次也没请求过的地址同样按闲置计。所以正在播的房间（中继 1 到 2 秒刷新一次）不会过期，预取好又闲置的房间会。主列表 30 秒就 403（所以不缓存主列表）；换档仍需现取主列表。
- 预取策略：`PreparedCache.maxAge` = 20 秒，明显短于实测最低闲置寿命（约 32 秒），过期就重新解析（多一次解析加取主列表往返）。不对预取地址做定时保活（多一路周期请求，且续期已兜底）。代价：切房晚于 20 秒就失去预取加速。
- 403 续期（`PlaylistRelay.fetchTrack` / `renewRendition`）：某一路取子列表遇 403 / 410 时，经 `PlayerController.renewRendition` 请 `StageModel.renewRendition` 重新解析当前房间（同 5.1 第 2 步：先 `chatvideocontext`，失败再 ajax 与侧车）并按起播时的档位上限选档，中继在同一个活跃源上原地换上新地址并重取一次，本地主列表地址不变，AVPlayer 无感。视频与音轨同时撞上只续一次（共用在途任务）；`RenewalBudget` 限 60 秒内 3 次、间隔 3 秒，耗尽或续期失败才落入原有的 503 与播放失败重试。回答 AVPlayer 的请求遇到正在续期时，在常规有界等待之外最多再等 `RenewalPolicy.waitBound`（8 秒）。续期不碰 `playGeneration` 与播放器状态。
- 中继宽限 1 × T 偏紧：后台轮询与分片发布不同步，实测分片恢复间隔偶尔 2 到 2.8 秒，此时 AVPlayer 的请求会先等一次刷新（约几十毫秒）而不是失败；若真机出现反复重建，先放宽 `graceFactor`。上游 403 / 410 由续期处理（见上），续期预算耗尽后才由 503 与重试路径兜底。
- 中继不可用（端口未绑定）时回落直播视频子列表，没有独立音轨；仅应用启动最初的瞬间可能出现。
- 没有「仅高清」筛选：站点列表接口没有高清字段，实现不了，已移除；房间信息里的高清标记因此恒不显示（`Room.isHD` 目录来源恒为 false）。
- `_fast` 画质档是否存在、命名与带宽是否与选档逻辑相容，未核对（选档只看 `BANDWIDTH`）。
- 日志隐私：全仓 `privacy: .public` 只剩事件名、错误域码、状态码、数值与固定标签；系统错误原文与 `errorComment` 标 private。
