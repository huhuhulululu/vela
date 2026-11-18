# Vela 安卓版实现计划（第 2 版，覆盖第 1 版全文）

> 预算估算：编排侧约 420 次工具调用。分配如下：
> - 单窗 25 个任务包、双窗 4 个、画中画 2 个、文档 3 个，共 34 个，每包约 8 次（派发、核对 `git ls-remote`、拉分支、读 CI 结果、越界检查、裁决、合入集成分支），约 270 次；
> - CI 失败回修约 30 次；
> - 3 个里程碑 PR，每个约 25 次（审计、盲审、合并、文档），约 75 次；
> - 里程碑 A 收尾时本机装环境、签名、发布、真站冒烟，约 45 次。
>
> 总数超过 150。⏸ COMPACT CHECKPOINT 放在四处：W2 合入后、W4 合入后、里程碑 A 合并后、里程碑 B 合并后。

## 冻结区块（批准后 AI 不得自改；要改先 HALT，请用户授权）

**需求边界**
- 新增安卓应用：Kotlin 原生重写，界面用 Jetpack Compose，播放用 Media3 ExoPlayer，代码放在公开仓的 `android/` 目录下，是独立的 Gradle 工程。只装用户自己的手机，不上应用商店，也不走公开仓的 Release。
- 功能与 macOS、iOS 的核心对齐：
  - 四个来源：猜你喜欢、热门、新开播、收藏；
  - 上下滑切房，切房时用模糊缩略图垫底；
  - 心形收藏与收藏夹、口味面板、搜索（本地过滤加全站搜索）；
  - 默认静音；首次打开须确认已满 18 岁；站点质询在 WebView 里完成。
- 不做：菜单栏、老板键、窗口拖动、指针轮询、离开后降画质、手动铺满开关、登录、打赏、录制、绕过手段、联盟码。
- 收藏、屏蔽名单、口味画像、观看账本只存在本机，不进云备份。
- 交付顺序：A 单窗 → B 竖屏双窗 → C 画中画。
- 安装方式：在本机打包签名 APK，经 tailnet 私有预览链接发到手机。release keystore 只放本机，不入库；CI 只用 debug 签名或不签名构建。
- 公开仓里不得出现个人邮箱、本机绝对路径、签名密钥、私有主机名；测试数据一律用合成数据。
- 全程静音测试；所有验收由程序自证（用户不在电脑前）。CI 不访问真实站点（ADR-004）。

**用户裁决**
1. **与 iOS 对齐**，先单窗，再双窗，再画中画。
2. **默认静音**：设置里没有 `muted` 键时视为静音。静音时不请求音频焦点，不打断别的 App 的声音。取消静音时请求 `AUDIOFOCUS_GAIN_TRANSIENT`，会打断别人；再静音时放弃焦点，让对方恢复（编排者 2026-11-18 已采纳）。
3. **画面保持原始比例，不裁切**：
   - 视频层不得使用任何裁切或拉伸模式（`ContentScale.Crop`、`FillBounds`、`RESIZE_MODE_ZOOM`）；
   - 横向 16:9 画面在竖屏窗格里上下留边，留边区露出该房间的模糊缩略图；没有缩略图时用深色渐变，不出现大块纯黑；
   - 竖屏流按比例铺满；
   - 窗格尺寸口径和降档估算都按画面实际显示的矩形，不按窗格尺寸。
4. **后台零开销**：没有画中画时，回到桌面 5 秒内所有播放器释放、网络请求为零、CPU 增量约为零，不留播放线程和 WebView 进程。不使用前台服务、WorkManager、开机广播。
5. **竖屏双窗**：
   - 默认单窗，双窗开关的状态要记住；
   - 上下两个窗格各自独立划动；只有焦点窗出声；
   - 两路都用最高档、不封顶（流量约翻倍，用户接受）；
   - 热状态到 serious 及以上时两路降档，降到画面实际显示高度对应的档位，下限 896 kbps；回落到 fair 及以下并持续 60 秒后回升；
   - 横屏时或关闭双窗时释放非焦点路，回到竖屏双窗后重新起播；
   - **两路永不播放同一个房间**，以下场景逐条成立：
     - 屏蔽主路或副路正在播的房间；
     - 取消收藏导致某路的房间消失；
     - 本地搜索提交；
     - 全站搜索装入和退出；
     - 切换来源；
     - 列表只剩一个房间：焦点路保留它，另一路显示「没有更多房间」；
     - 空列表之后续页恢复：两路重新选房，不重复；
     - 要显示的目标房间正在另一路播放：只切焦点，两路都不换房；
   - 副路有焦点时，收藏、屏蔽、不感兴趣、重试、暂停都只作用于副路，另一路的房间和状态不变；
   - 副路因解码资源不足失败时，只释放副路并提示「双窗暂不可用」，不记失败、不自动跳过；损坏码流仍按房间失败处理。
6. **画中画**：
   - 正在播放时回桌面自动进入画中画，设置里可以关掉「自动进入」（默认开），另有画中画按钮；
   - 画中画里只有焦点路播放和联网，其余路一律释放；
   - 静音的画中画不打断别人的音乐；在画中画里取消静音会打断，再静音让对方恢复；
   - 画中画同样保持原始比例；
   - 回桌面后 3 秒内没能进入画中画，就按没有画中画处理（全停释放），不得留下后台播放或黑色小窗；
   - 关闭画中画后，按裁决 4 归零。
7. **回前台的恢复规则**：每一路在「有房间、没被手动暂停、窗格可见、不在失败态」时重新解析地址后起播；失败态保留失败提示、不自动重播；手动暂停的路保持暂停，只显示垫底缩略图和暂停图标；隐藏的路保持已释放。
8. **音频规则**：转换按顺序执行，不能出现两路同时出声的瞬间；请求焦点失败时保持静音，只改运行时状态，不写进设置；失去焦点或拔耳机时转为静音，且不自动恢复出声。

**验收标准**：见文末 S-A1 至 S-A6、S-B1 至 S-B2、S-C1 至 S-C3，不可自改。

---

## Overview
在 `android/` 下建 `:core`（纯 JVM，对照移植 VelaCore 并逐条对照测试）和 `:app`（Compose 加 Media3）两个模块。默认不移植本地中继，改成每路一个进程内的 `LaneDataSource`；第 1 节写明不达标的判据和回退到「移植中继」的方案。执行全部派给 agy 云端，集成分支用 `android/fleet`，W0 到 W5 的验收以 GitHub CI 为准。不改 macOS、iOS 的源码；只有 T1 以默认关闭的开关扩展共享脚本 `scripts/serve-test-stream.py`。

## Design Reference
- iOS 计划 `docs/plans/ios.md`（第 4 版、修订 A1 到 A3）；
- 本文件 `docs/plans/android.md`；
- ADR 追加到 `docs/decisions.md`：ADR-A1 不用中继、改用进程内数据源；ADR-A2 音频焦点用 GAIN_TRANSIENT；ADR-A3 热状态映射；ADR-A4 采纳语义的处理方式（或回退到中继）。

---

## 1. 中继评估、补测与回退

**中继现在做的事**（依据 `PlaylistRelay.swift`、`RelayFeed.swift`、`UpstreamRenewal.swift` 和 ARCHITECTURE 5.1、5.2、第 10 节）：
- 去掉低延迟标签，并把相对地址改成绝对地址（`steadyPlaylist`）；
- 生成只含一档的本地主列表，带独立音轨；
- 新鲜度判断：宽限 1×T 加有界等待（`RelayWait`）；
- 403、410 时原地续期：视频和音轨共用一次续期，60 秒内最多 3 次、间隔至少 3 秒，最长等 8 秒；
- 静音时停刷音频清单；
- 列表采纳语义（`RelayFeed.finish` 和 `replaces`）：序号回退但带 DISCONTINUITY（重推流）时采纳新列表；刚换过地址时，即使序号没前进也采纳；序号相同但最后一个分片地址变了时采纳，并清掉「已给过」标记。

**默认方案：每路一个进程内 `LaneDataSource`，不开本地套接字。**
- **合成地址**：主列表是 `vela://lane/<id>/master.m3u8`，内容在内存里，只含选中的一档（移植 `StreamRendition` 的本地主列表生成）。子列表是 `vela://lane/<id>/{video,audio}.m3u8`，由映射表转到当前的真实地址。
- **音视频合一**：如果选中的档没有独立音轨，媒体项直接用 `vela://lane/<id>/video.m3u8`，不生成主列表，映射表里只有视频一项。这与 iOS「音频混在视频里时直接给视频媒体列表」一致。
- **`getUri()`**：读列表时返回合成地址，ExoPlayer 只认合成地址，续期对它不可见。读分片时返回真实地址（列表经过改写后都是绝对地址）。
- **请求头**：列表和分片请求一律带 `User-Agent: UserAgent.safari` 和 `Referer: https://chaturbate.com/`，与 `PlayerController.swift:604`、`PlaylistRelay.swift:384` 一致。
- **改写**：DataSource 读完整个列表后，调 `steadyPlaylist(文本, base = 真实地址)`，把改写后的字节交给 ExoPlayer。
- **续期**：`open()` 遇到 403 或 410 时，调 `RenewalCoordinator.renewBlocking`，最长等 `RenewalPolicy.waitBound` 8 秒。等待必须能被中断：线程被中断（ExoPlayer 取消加载时会中断线程）或本路释放时调用 `coordinator.cancel()`，都要在 200 毫秒内返回。续期成功就换映射，再重开一次；失败就抛出。
- **对看门狗暴露「正在续期」**：`isRenewing` 等于 `coordinator.isActive`，包括续期后的那次重取。卡住看门狗遇到正在续期就重新计时（对应 `testStuckRearmsWhileBufferGrowsOrRenewing`）。
- **加载错误策略** `VelaLoadErrorPolicy`：只有一档，所以 403 时不排除轨道；临时错误退避重试。
- **缓冲**：`DefaultLoadControl` 的 min 和 max 缓冲都设为 6000 毫秒，对应 iOS 的 `preferredForwardBufferDuration = 6`。直播目标偏移起始设 3 秒（对应 iOS 的 `configuredTimeOffsetFromLive = 3`），P1 对比 3 秒和默认值的卡顿次数后定。
- **静音**：关掉音频轨道。

**三种采纳语义的期望**（P1 测实际表现，P2 必须做到）：

| 场景（伪上游注入） | 期望 |
|---|---|
| R1：序号回退并带 DISCONTINUITY | 本路原地重新 prepare，回到直播边缘；60 秒内最多 2 次，不耗房间重试预算，不出失败界面；10 秒内恢复出画面 |
| R2：续期后序号没前进 | 不报错；下一次序号前进时被采纳；不中断画面 |
| R3：序号相同但最后一个分片地址变了 | 10 秒内没有任何加载错误传到播放器，画面持续渲染。如果直接用分片的真实地址做不到，P2 改用「分片合成地址」：`vela://lane/<id>/seg/<序号>`，每次取到列表就更新「序号 → 当前真实地址」表，表长不超过列表窗口 |

**静音起播的判据**（与 P1 的结论挂钩）：静音起播后，稳态 60 秒内 audio.m3u8 请求不超过 1 次（只允许准备阶段那一次），音频分片请求为 0。如果 P1 测到关掉音轨后音频列表仍在持续刷新，P2 改为：静音时的本地主列表不含音轨，取消静音时重建主列表并重新 prepare。这一点与 iOS 不同，代价是取消静音时约 1 秒的重新缓冲，写进 ADR-A4。

**P1 不达标的判据**（任一成立，就回退到「移植中继」）：
- R1、R2、R3 中任一项，在两种分片地址方式下都达不到上表的期望；
- 受控 403 续期后出现 `onPlayerError`，或画面停滞超过 8 秒；
- 伪上游按 1.5×T 抖动发布分片时，10 分钟内出现 1 次以上 PlaylistStuck 或重建；
- A3 的模拟器实测推翻了 P1 的 JVM 结论。这是第二个判定点：发生时立即 HALT 交编排者裁决。

**回退方案**（任务包 R1、R2 替换 P2，插在 W3）：
- R1：把 `RelayFeed`、`RelayWait`、`RelaySourceTable` 和 `RelayPolicy` 的其余部分移植到 `core/.../relay/`。RelayFeedTests、RelayWaitTests、RelaySourceTableTests，以及 PlaybackFlowFix 里与中继相关的 6 条测试，从 skip 表移回已移植。
- R2：在 `app/.../relay/` 实现 `PlaylistRelay`：`java.net.ServerSocket` 只绑 127.0.0.1、端口由系统分配，进后台时关闭。`res/xml/network_security_config.xml` 只对 127.0.0.1 放行明文。测试对照 RelayMultiSourceTests 的 9 个场景。
- 回退会多出约 2 个任务包、约 120 分钟。

**C9 和 skip 表怎么处理中继相关测试**：
- 默认方案下，RelayFeedTests、RelayWaitTests、RelaySourceTableTests 逐条登记进 `skip-hls.tsv`，理由是「RelayFeed 不移植」，并在理由列写 `replaced-by:<Kotlin 测试名>`（如果 00 的 skip 表没有单独的替代列，就这样写）。
- PlaybackFlowFixTests 里的 `testParseReadsDiscontinuityAndLastURI` 由 L0b 移植（`MediaPlaylistInfo.parse`）。
- 其余 6 条中继测试（`testRewindWithDiscontinuityAdoptsNewBody`、`testRewindWithoutDiscontinuityStaysOlder`、`testAddressChangedAdoptsEvenWhenSequenceDoesNotAdvance`、`testSameSequenceNewURIAdoptsAndClearsReplay`、`testSameSequenceSameURIStaysUnchanged`、`testSameSequenceWithoutURIsStaysUnchanged`）登记 skip，替代测试是 P2 的 `LaneDataSourceAdoptionTest` 里的 R1 到 R3。
- RelayMultiSourceTests 的 9 条由 P2 的 `LaneDataSourceTest` 替代。

## 2. 工程结构

**00 已派发，按实际落地内容描述：**
- 模块 `:core`（kotlin jvm）和 `:app`；
- 依赖已一次声明：kotlinx-coroutines-core/android、kotlinx-serialization（含插件）、Compose ui 与 foundation（不带 material）、activity-compose、media3-exoplayer、media3-exoplayer-hls；测试侧有 robolectric、media3-test-utils(-robolectric)、compose ui-test 与 ui-test-manifest、androidx.test（runner、rules、ext）、uiautomator；
- 构建类型 debug、perf、release，测试钩子在 `src/hooks`，只编进 debug 和 perf；
- `android.yml` 在 `android/**` 有变动的推送、PR，以及手动触发时运行；
- `check-apk.sh` 检查权限白名单、`allowBackup=false`，并用 R8 mapping 确认 release 里没有 `vela.app.hooks`；
- `parity.py` 逐条核对测试报告 XML，每组一个 `skip-<组>.tsv`。

如果 00 实际落地与此不符（例如缺 hooks 源码集），编排者在 W1 合入后补上。

**其他约定：**
- **minSdk 26**：画中画和 `AudioFocusRequest` 从 API 26 起有；`java.time` 不用脱糖。
  - 画中画自动进入：API 31 及以上用 `setAutoEnterEnabled`，26 到 30 用 `onUserLeaveHint`；
  - 热状态：API 29 及以上读，以下恒为 nominal。
- **时间**：core 里时刻用 `java.time.Instant`，由调用方注入 `Clock`；时长保持 `Double` 秒，和 Swift 的 `TimeInterval` 一样，保证浮点运算逐位一致；按天统计时注入 `ZoneId`，测试里固定成 Swift 测试所用的时区。
- **轻量手段**：
  - 只用 `HlsMediaSource.Factory` 作媒体源，渲染器只要视频和音频；
  - BandwidthMeter 用不做测速的版本（P1 核实能不能避开 `NetworkTypeObserver` 常驻）；
  - 画面用 `SurfaceView`，按 `DisplayFit.fitted` 定尺寸；
  - 模糊用「缩到 16 像素再放大」；
  - 网络用 HttpURLConnection；存储用 SharedPreferences 加 `filesDir/Vela/*.json` 原子写；
  - `allowBackup="false"`，再加全部排除的 `dataExtractionRules`；
  - 资源文件按任务包分开：`res/values/strings_<包>.xml`，Android 会自动合并。
- **依赖冻结与出口**：执行者不得修改 `gradle/libs.versions.toml`、任何 `build.gradle.kts`、`proguard-rules.pro`。需要新依赖或新的混淆规则时，停下来在交付说明里写清楚：要加什么、为什么、能不能用 `@Keep` 代替。编排者在两波之间统一修改、推到 `android/fleet`，下一波从新的集成分支开工。

## 3. 真实类型依赖（依据 grep，文件:行号）
跨组的引用，以及它们对拆组的影响：
- `Models.swift:185-186` 用了 `Taste.tagKey` 和 `genderKey`，而 `Taste.swift:61-62` 又转调 `Preferences.tagKey` 与 `maxNameLength`。处理：把 key 函数和常量下沉到叶子（`TasteKeys`、`Prefs`），Kotlin 版 Taste 转调它们。
- `Preferences.*` 常量被这些文件引用：Feedback:29-39、Curation:74/101、TasteSummary:85、FavoriteStatus:374、TagRarity:18、Taste:86-301、HotTags:63、Recommender:136/163、WatchLedger:30-165、DwellTracker:113、LaneSettlement:46/52。`PreferenceStore.fitsLimit` 被 PreferenceQuarantine:31 用，`defaultURL` 被三个存储用。处理：常量进 L0a，`fitsLimit` 进 L0b，`defaultURL` 改成注入目录。旧版 `Preferences` 结构体与迁移不移植。
- `PlaybackLaneRole`（LaneSettlement:4）被 LifecycleEpoch:15、PiPPolicy:26、PhoneLifecycle、AudioTransition 用；`LaneSettlement.EndReason` 被 PhoneLifecycle:55 用；`LaneID`（LaneCursor:4）被 RoomTarget:6 和 LaneSelection 用。处理：三者都进 L0b。
- UpstreamRenewal:7 用了 `RelayFailure`（RelayFeed:61），:107 用了 `StreamRendition`；PlaybackPolicy:141-158 用了 `StreamRendition` 和 `RenewalPolicy`。处理：`RelayFailure`、`MediaPlaylistInfo`、`RelayPolicy.defaultTargetDuration` 和整个 `StreamRendition.swift` 进 L0b。
- `AudioPolicy.swift:110` 调了 `PlaybackPolicy.audioCheckStep`，`AudioTransition.swift:138` 用了 `MuteAudioPolicy`。处理：AudioPolicy 与 PlaybackPolicy 同在 C2；AudioTransition 的 Kotlin 版放到 A2，排在 C2 之后。
- StreamSourcing:44 扩展了 `ChatContext`（FavoriteStatus:24-111），ChatContext 用了 `CatalogFailure`（CatalogAPI:87）、`HTTPHeaders`、`ShowState`、`FlexInt`。StreamSourcing:68 还用了 `RenewalPolicy`。处理：`FavoriteStatus` 枚举、`ChatContext`、`CatalogFailure` 进 L0a；C4 依赖 C2。
- `LoadCommit`（FavoriteStatus:401）用了 `CatalogPage`、`FeedSource` 和 `KnownCatalog`。处理：LoadCommit 和它的测试放 C7。
- `CatalogQuery.url()`（CatalogAPI:55）调了 `SiteSearch.normalized`；Recommender:20 和 TasteTargeting:22-41 用了 `CatalogQuery`；`looksLikeChallenge`（CatalogAPI:282）被 EndpointBackoff:31、FavoriteStatus、HotTags、LastBroadcast、StreamAPI、StreamSourcing 用。处理：`CatalogQuery`、`CatalogPage`、`CatalogFailure`、`looksLikeChallenge`、`SiteSearch.normalized` 都进 L0a。
- `FeedSource`（Recommender:3）被 SiteSearch:92、DwellTracker:129、PromptRules:32、FavoriteStatus:406 用。处理：进 L0a。
- `Curation.key` 被 AwayRetry:35/42/80 和 StreamerAffinity:15 用。处理：进 L0a；Curation 的其余部分在 C7。
- WatchLedger 被 Recommender:54、StreamerAffinity:13、PromptRules:16、LaneSettlement:109 用；Taste、TagRarity、Feedback 被 Recommender、LaneSettlement 用；HotTags:129 扩展了 Taste；LaneSelection:3-298 用了 FeedStore。

**组间依赖（箭头表示「依赖于」）：**
```
00
├─ L0a 站点与模型叶子 ─┬─ C5 feed ─────────────┬─ C6c 推荐 ─┐
│                      ├─ C6a 口味 ────────────┤            │
│                      ├─ C6b 账本、停留 ──────┴─ C8 选房与结算（还依赖 C5、L0b）
│                      ├─ C7 收藏（还依赖 L0b）
│                      ├─ C3 生命周期（还依赖 L0b）
│                      ├─ A1 应用基础设施、A4 界面契约
│                      └─ C4 stream（还依赖 C2）
├─ L0b 播放与路叶子 ── C2 播放策略 ─┬─ C4
│                                    ├─ P2 LaneDataSource（还依赖 P1）
│                                    └─ A2 音频焦点（core 加 app）
├─ P1 对比实验（只依赖 00）  T1 伪上游与模拟器框架（只依赖 00）
C9 跨组测试 ← C2…C8
A3 单路播放 ← P2、C4、A1、T1
A5a 舞台：目录、收藏、口味 ← C4、C5、C6c、C7、C8、A1、A4
A5b 起播链 ← A5a、A3、C3
A6 界面 ← A3、A4
A7 生命周期接线 ← A5b、A6、A2、C3、C8
E1 ← A7
```
C8 单窗也要用：macOS 单窗的 `updateFeed` 经过 `LaneSelection.resolve`，停留结算用 `LaneSettlement`。所以 A5a 和 A7 都依赖 C8。

## 4. 移植清单与测试归属

**Swift 符号 → 组**（同一个 Swift 文件拆到多组时，各组在自己的 `parity/split-<组>.tsv` 登记）：

| 组 | 移植内容 |
|---|---|
| L0a | Models.swift 全部；Preferences 的常量与 `tagKey`/`genderKey`（Kotlin 名 `Prefs`、`TasteKeys`）；`Curation.key`；`FeedSource`；HTTP.swift 全部；`looksLikeChallenge`；`CatalogQuery`、`CatalogPage`、`CatalogFailure`；`SiteSearch.normalized`；EndpointBackoff.swift 全部；`FavoriteStatus` 枚举和 `ChatContext`；`Instant` 转秒的辅助函数 |
| L0b | StreamRendition.swift 全部（`HLSAttribute`、本地主列表）；`RelayFailure`、`MediaPlaylistInfo`、`RelayPolicy.defaultTargetDuration`；LaneCursor.swift 全部（`LaneID`）；RoomTarget；`PlaybackLaneRole`、`LaneSettlement.EndReason`；PreferenceQuarantine 和 `fitsLimit`；DeferredSaver；TimedFanOut |
| C2 | PlaylistLadder；UpstreamRenewal；PlaybackPolicy；AudioPolicy（`MuteAudioPolicy` 改为「关音轨」的语义）；PlaybackRefresh；PlaybackError；DisplayFit；LaneQuality；PlaybackFailureClassifier（白名单换成 Media3 的解码器初始化失败、解码资源被回收）；SkipTracker |
| C3 | LifecycleEpoch；PhoneLifecycle；PiPPolicy；ChallengeGate；AwayRetry |
| C4 | StreamAPI；StreamSourcing |
| C5 | CatalogAPI 的其余部分（RoomListDecoder、fetchPages、CatalogWindow、PageMerge）；SiteSearch 的其余部分；FeedStore（含 iOS 新增的 4 个接口）；LoadSlot；InfoVisibility |
| C6a | Feedback；TagRarity；BetaSampler；Taste（不含旧数据迁移）；TasteSummary；TasteStore（不含迁移）；TasteTargeting；HotTags |
| C6b | WatchLedger；StreamerAffinity；DwellTracker；PromptRules（不含 SheetLayout） |
| C6c | Recommender（含 `SplitMix64`，按 `ULong` 逐位复现）|
| C7 | Curation 的其余部分；FavoriteStatus.swift 的其余部分（含 LoadCommit、SupersedingRunner）；FavoritesBoard；LastBroadcast |
| C8 | LaneSelection；LaneSettlement 的其余部分 |
| A2 | AudioTransition 改写成 `vela.core.audio.AudioFocusPlan` |
| 不移植 | MenuBarVisibility、WindowDragPolicy、ShortcutGate、Pager、Presence、QualityGovernor、PortraitFit、AssignIf、SheetLayout、Preferences 结构体与 PreferenceStore 的迁移；RelayFeed、RelayWait、RelaySourceTable（回退时由 R1 移植） |

**测试 → 组**（文件里混有多组内容时逐条登记，不允许整文件跳过）：
- L0a：BOMShapeTests、EndpointBackoffTests；VelaCoreTests 的 `testFormattingAndTokens`。
- L0b：StreamRenditionTests、PreferenceQuarantineTests、DeferredSaverTests；PlaybackFlowFix 的 `testParseReadsDiscontinuityAndLastURI`。
- C2：UpstreamRenewalTests、RenewalCoordinatorTests、PlaybackPolicyTests、MuteAudioTests、AudioNetworkTests、PlaybackRefreshTests、PlaybackErrorTests、DisplayFitTests、LaneQualityTests、PlaybackFailureClassifierTests、SkipTrackerTests；VelaCoreTests 的 `testPlaylistLadderPicksRestingRung`；PlaybackFlowFix 的 `testStuck*`（3 条）、`testBufferAdvanced`、`testEndOfList*`（2 条）、`testMovedHasNoNotice`、`testLimitOnlyAfterRealConsecutiveSkips`；中继相关的 6 条登记进 `skip-hls.tsv`（见第 1 节）。
- C3：LifecycleEpochTests、PhoneLifecycleTests、PiPPolicyTests、ChallengeGateTests、AwayRetryTests。
- C4：StreamSourcingTests、ChatContextProbeTests、GeoRestrictedTests；VelaCoreTests 的 `testStreamTickets`、`testStreamRequestDoesNotAskForHighestBandwidth`。
- C5：CatalogTests、SiteSearchTests、FeedAnchorTests、FeedCacheTests、StartupCatalogTests、TargetedMergeTests、LoadSlotTests、InfoVisibilityTests、StageFlowTests；ShortcutSearchTests 的 5 条预览与搜索测试（`testPlainKeysOnlyWhenNothingElseOwnsTheKeyboard` 逐条登记 skip，理由 ShortcutGate 不移植）；VelaCoreTests 的 `testCatalogFetchRejectsChallenge`、`testFeedKeepsPlaceFiltersAndPrefetches`（`testWheelCommitsOneRoom`、`testPresenceIsConditional` 逐条登记 skip）。
  - 测试里的 `Taste.tagKey` 改调 `TasteKeys.tagKey`，二者等价。
  - FeedCacheTests 里用随机数的属性测试只断言不变量；Kotlin 版用同一个种子，不要求随机序列逐位相同，这一点写进 notes。
- C6a：TasteTests、TasteRecordingOverloadTests、HotTagsTests。涉及旧数据迁移的条目逐条登记 skip。
- C6b：WatchLedgerTests、WatchAccountingTests、PromptRulesTests（SheetLayout 那一条逐条登记 skip）。
- C6c：FeedRankingTests、RecommendTuningTests、RecommendScenarioTests、FreshCamsTests、FeedReorderTests（它用到 `Recommender.newest`）。
- C7：FavoriteStatusTests、FavoritesBoardTests、LastBroadcastTests、LoadCommitTests。
- C8：LaneSelectionTests（17 条）、LaneSettlementTests。
- A2：AudioTransitionTests，断言改成音频焦点的语义，逐条映射。
- C9：ReviewFixTests、DwellTests、CurationTests、VetoVisibilityTests、FileLimitTests、SearchFix13Tests（同时涉及 C3 与 C5）。
- 整文件 skip：AssignIfTests、MenuBarVisibilityTests、WindowDragPolicyTests、PointerPollingTests、PortraitFitTests、QualityGovernorTests、RelayFeedTests、RelayWaitTests、RelaySourceTableTests。每条都要写进 skip 表并附理由，parity 工具逐条核对。
- VelaRelayTests 的 RelayMultiSourceTests：由 P2 的 `LaneDataSourceTest` 替代，逐条写 `replaced-by`。

**移植规则**：
- 测试数据与断言照抄 Swift；
- Swift 行为有疑点时照原样移植，记进 `parity/<组>.notes.md`，不擅自修；
- BetaSampler 或其他浮点运算出现逐位差异时，先记进 notes，由编排者裁决是放宽成容差还是改实现；
- 分组归属不对导致编译不过时，停下报告，由编排者把该条目移到 C9。

## 5. 波次、并行与写入范围

| 波次 | 任务包 | 并行路数 | 开工前提 |
|---|---|---|---|
| W0 | 00 | 1（已派发） | — |
| W1 | L0a、L0b、P1、T1 | 4 | 00 合入 |
| W2 | C2、C3、C5、C6a、C6b、C7、A1、A4 | 8 | W1 合入，编排者看过 P1 报告 |
| W3 | C4、C6c、C8、P2（回退时换成 R1 加 R2）、A2 | 5（回退时 6） | W2 合入 |
| W4 | C9、A3、A5a | 3 | W3 合入 |
| W5 | A5b、A6 | 2 | W4 合入 |
| W6 | A7 | 1 | W5 合入 |
| W7 | E1，然后 D-A，然后 PR-A | 1 | — |
| B | B0，然后 B1 与 B2 并行，然后 E2、D-B | 最多 2 | PR-A 已合并 |
| C | PiP1，然后 E3、D-C | 1 | PR-B 已合并 |

**合并门**：`git diff --name-only android/fleet...android/<名>` 必须是「该包声明的写入范围 ∪ 下表中轮到该包的串行重叠文件」的子集，超出即拒绝。此外要求该分支 CI 全绿，再对照规格。

**允许的串行重叠**（只能按箭头顺序修改，前一个包合入后，后一个包才能开工）：

| 文件 | 顺序 |
|---|---|
| `app/src/main/kotlin/vela/app/MainActivity.kt` | 00 → A7 → B2 → PiP1 |
| `app/src/main/kotlin/vela/app/stage/StageViewModel.kt` | A5a → A5b → A7 → B1 → PiP1 |
| `app/src/main/kotlin/vela/app/stage/StageContract.kt` | A4 → B0 → PiP1 |
| `app/src/main/kotlin/vela/app/stage/LifecycleWiring.kt` | A7 → B1 → PiP1 |
| `app/src/main/kotlin/vela/app/ui/StageScreen.kt` | A6 → B2 |
| `app/src/main/kotlin/vela/app/lane/PlaybackLane.kt` | A3 → B1 |
| `app/src/main/AndroidManifest.xml` | 00 → PiP1 |
| `.github/workflows/android.yml` | 00 → T1 |
| `android/tools/emulator-run.sh` | 只有 T1 写；各模式在 `android/tools/emu-modes/<模式>.sh` 里，归使用它的包所有 |
| `gradle/libs.versions.toml`、所有 `build.gradle.kts`、`proguard-rules.pro` | 只有编排者在两波之间改 |

B1 和 B2 并行时写入范围不相交：B1 写 StageViewModel、LifecycleWiring、PlaybackLane；B2 写 StageScreen、MainActivity 和新文件。

## 6. CI 与验收环境
- **W0 到 W5 一律以 GitHub CI 为准**，本机不需要 JDK 和安卓 SDK。
  - 云端执行者自验单元测试和 `:app:assemblePerfAndroidTest`。云端没有 KVM 时，模拟器验收交给 CI 的 `android-emulator` 作业：agy 推送分支后，CI 会在该分支上自动跑。
  - **CI 失败的回修**：交回同一个执行者、同一个模型，最多 2 轮。连续两轮失败数不下降，或全是同一类问题，就停下，由编排者裁决：换模型、拆包或回到计划。
- **`android-unit`**（00 已建）：
  - `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`；
  - `python3 -I android/tools/parity.py --all`；
  - `check-apk.sh <release.apk>`：体积 ≤ 5 MB；权限只有 INTERNET 和 ACCESS_NETWORK_STATE；没有 service 和开机 receiver；`allowBackup=false`；R8 mapping 里没有 `vela.app.hooks`。
- **`android-emulator`**（T1 新增）：
  - 开 KVM，`apt-get install ffmpeg`，跑 `scripts/make-test-stream.sh`；
  - 伪上游用扩展后的 `scripts/serve-test-stream.py`，只绑 127.0.0.1、端口由系统分配；
  - 用 `reactivecircus/android-emulator-runner@v2`（api 34、x86_64、google_apis，参数 `-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`，关动画）；
  - 只调用 `android/tools/emulator-run.sh all`，由它依次执行 `emu-modes/` 下已有的模式脚本，伪上游地址经 `-Pvela.upstream=http://10.0.2.2:<端口>` 传入。
- **本机**（只在里程碑 A 收尾时做，编排者 2026-11-18 裁决）：装 JDK 17、cmdline-tools、build-tools、platform-tools，可选 arm64 模拟器镜像；签名；经 `ssh localhost` 回环跑真站冒烟 30 分钟；发布到 `preview/android/`（tailnet 私有，附 sha256）。

## 7. 实跑与自证（perf 构建，模拟器，静音）
- **起播**：`PerfProbe` 记录 `onRenderedFirstFrame` 的时刻，首帧 ≤ 8 秒；`DecoderCounters.renderedOutputBufferCount` 持续增长。
- **比例**：
  - logcat 打 `display-check lane= gravity=fit presentation= bounds= videoRect= uncropped=`；
  - 测试断言 `DisplayFit.isUncropped` 为真；
  - 在设备内用 `UiAutomation.takeScreenshot()` 统计留边区像素，纯黑占比不得 ≥ 95%（位图只在设备上判，不取回）；
  - 用 grep 禁止视频层出现裁切或拉伸模式。
- **后台**：按 HOME（画中画关）5 秒后检查：
  - 之后 30 秒伪上游请求日志为 0 条；
  - `/proc/<pid>/stat` 的 utime+stime 增量 ≤ 1 tick；
  - 没有 `ExoPlayer:Playback` 线程，没有 WebView 渲染进程。
- **资源**：`dumpsys meminfo` 的 TOTAL PSS 与 `dumpsys cpuinfo` 每 5 秒采一次，输出一行 JSON。里程碑 A 首测作为基线写进 `docs/PERF.md`，之后按「基线 +10%」做门。
- **网络**：伪上游发出的字节数 ÷（所选档位带宽 × 时长）≤ 1.15。
- **静音**：默认 `volume == 0`，音频轨道已关，并满足第 1 节的静音起播判据。
- **APK 体积**：release ≤ 5 MB 是硬门。
- **P1 的计时**一律用墙钟：伪上游真实计时，测量窗口按墙钟 60 秒；不用 Robolectric 的虚拟时间换算请求频率。

## 8. 公开方案调研（本代理无法联网，以下出处须由执行者打开核对并在 PORTING.md 记录核对日期；00 负责前两项，PiP1 负责画中画项）

| 出处 | 借什么 | 不借什么 |
|---|---|---|
| Media3 HLS、直播、自定义文档（developer.android.com/media/media3/exoplayer/hls、/live-streaming、/customization、/network-stacks） | 低延迟 HLS 行为；`LiveConfiguration.targetOffsetMs`；在 `HlsMediaSource.Factory` 注入 DataSource 与 LoadErrorHandlingPolicy；`DefaultLoadControl` 缓冲；`TrackSelectionParameters` 关音轨与 `setMaxVideoBitrate` | 自适应切档、media3-ui、MediaSession |
| 音频焦点文档（media/optimize/audio-focus） | 用 GAIN_TRANSIENT，让对方能恢复；静音时不请求焦点；`ACTION_AUDIO_BECOMING_NOISY` 时转静音 | ExoPlayer 内置的 `handleAudioFocus`（它请求永久 GAIN，对方不会恢复） |
| 画中画文档（develop/ui/views/picture-in-picture） | `setAutoEnterEnabled` 与 `onUserLeaveHint` 兜底；宽高比夹在 1:2.39 到 2.39:1；`RemoteAction`；`onPictureInPictureModeChanged` | 前台服务、后台音频 |

## Risks & Mitigations
- **不用中继后采纳语义不一致** → 第 1 节的 R1 到 R3 有明确期望；P1 和 A3 是两个判定点；有完整的回退方案 R1、R2。
- **类型依赖归组错误** → 第 3 节依据 grep 定归属；编译不过即停下报告，条目移到 C9。
- **合成地址下续期阻塞卡住释放** → 线程中断和 `cancel()` 都要在 200 毫秒内返回，有专门测试。
- **共享脚本 `serve-test-stream.py` 被改坏** → 新增开关全部默认关闭；macOS CI 照常跑 `test_serve_test_stream.py`；脚本保持 macOS 自带 Python 和 bash 3.2 都兼容。
- **云端是 Linux，部分产物在 macOS 上用** → 脚本禁止只写 GNU 写法；编排者在本机后台抽查。
- **测试空成功** → parity 逐条核对 XML，执行数为 0 即失败；00 已验证负样本。
- **依赖冻结导致执行者卡住** → 第 2 节的出口：停下报告，编排者在两波之间改。
- **签名密钥丢失后无法覆盖安装** → keystore 只放本机，权限 0600，另做离线备份；仓库里只出现环境变量名 `VELA_KEYSTORE*`。
- **热状态映射主观** → 暂定 MODERATE 对应 serious、SEVERE 及以上对应 critical，写进 ADR-A3，用调试钩子注入验证。

## Success Criteria
- [ ] **S-A1**：`android-unit` 全绿；parity `--all` 通过，每个 Swift 测试要么有对应的 Kotlin 测试、要么逐条登记了 skip 和理由；执行数大于 0，失败为 0。
- [ ] **S-A2**：模拟器端到端跑通：年龄确认 → 目录 → 首帧 ≤ 8 秒 → 划动换房 → 收藏和口味在杀进程后仍在；默认静音并满足静音起播判据。
- [ ] **S-A3**：横屏流在竖屏单窗下 `uncropped=true`，留边区不是大块纯黑。
- [ ] **S-A4**：受控 403 续期以及 R1、R2、R3 三个场景都达到第 1 节的期望（或者已回退到中继，且 RelayMultiSource 的 9 个场景通过）。
- [ ] **S-A5**：暂停后零请求；后台 5 秒内零请求、零 tick 增量、没有播放线程；回前台按冻结裁决 7 恢复。
- [ ] **S-A6**：质询走 WebView 的路径跑通；release ≤ 5 MB 且不含钩子；本机签名 APK 已发布到 tailnet 链接并附 sha256；真站冒烟 30 分钟没有播放错误。
- [ ] **S-B1**：冻结裁决 5 列出的「两路不同房」场景全部成立；副路有焦点时操作只作用于副路；只有焦点路开着音频轨道；两路都是最高档；双窗开关被记住。
- [ ] **S-B2**：注入热状态后降档，第 59 秒不回升、第 60 秒回升；注入解码资源错误时副路降级，损坏码流仍按房间失败；两路同时出画面的时长 ≥ 测试窗口的 90%。
- [ ] **S-C1**：按 HOME 后 3 秒内进入画中画，否则全停；画中画里只有焦点路联网；比例正确、不裁切。
- [ ] **S-C2**：静音时 `dumpsys audio` 的焦点栈里没有本包；取消静音后有 GAIN_TRANSIENT；再静音即放弃焦点。
- [ ] **S-C3**：关掉「自动进入」后回桌面即全停；关闭画中画后满足 S-A5 的后台标准。

---

## 附录：任务包

**统一约束**（每个任务包都附上）：
- 只写列出的路径，加上第 5 节里轮到本包的串行重叠文件；
- 不改依赖和构建文件（出口见第 2 节）；
- 不碰 `App/`、`iOS/`、`Shared/`、`Sources/`、`Tests/`；
- 不建 worktree，不切分支、不合并、不变基；从 `android/fleet` 拉出 `android/<名>` 分支，推送后贴出 `git ls-remote origin android/<名>`；
- 提交作者用编排者给的 noreply 地址；不写邮箱、本机路径、密钥，测试数据只用合成数据；
- 不访问真站点；
- 先写会失败的测试再实现；
- 脚本不能只用 GNU 写法；
- 事实与前提不符时停下报告。

下面 Verify 里的 parity 命令参数以 00 落地后 `parity.py --help` 为准。

### W0
**00 脚手架**（已派发） · `android/scaffold` · claude-sonnet-4-6。内容见第 2 节「00 已派发」。

### W1
**L0a 站点与模型叶子** · `android/core-l0a` · gemini-4-carbon-high（类型多，Codable 换成 serialization 要细心）
- Files：`android/core/src/{main,test}/kotlin/vela/core/base/{model,site,net}/**`、`android/parity/{skip-l0a.tsv,split-l0a.tsv,l0a.notes.md}`
- Changes：按第 4 节 L0a 一行移植；Kotlin 版 `Taste.tagKey` 将来转调 `TasteKeys`。
- Tests：BOMShape、EndpointBackoff、VelaCore 的 `testFormattingAndTokens`。
- Verify：`cd android && ./gradlew :core:test --tests 'vela.core.base.*'` 且 `python3 -I tools/parity.py --group l0a` 退出码为 0。
- Estimate：约 60 分钟。Depends：00。

**L0b 播放与路叶子** · `android/core-l0b` · gemini-4-argon-high（规模中等，偏字符串与值类型）
- Files：`core/src/{main,test}/kotlin/vela/core/base/{play,lane,io}/**`、`parity/{skip-l0b,split-l0b}.tsv`
- Tests：StreamRendition、PreferenceQuarantine、DeferredSaver、PlaybackFlowFix 的 `testParse…`。
- Verify：`--tests 'vela.core.base.play.*' --tests 'vela.core.base.lane.*' --tests 'vela.core.base.io.*'`，parity `--group l0b`。
- Estimate：约 50 分钟。Depends：00。

**P1 播放管线对比实验** · `android/player-spike` · gemini-4-hydrogen-xhigh（要读 Media3 内部代码，难度最高）
- Files：`app/src/test/kotlin/vela/app/spike/**`、`android/docs/spike-ll-hls.md`
- Changes：在 Robolectric 里跑 ExoPlayer，对接 JDK HttpServer 写的伪上游（只绑 127.0.0.1、端口 0），全部按墙钟测量。对比：
  - 原生低延迟与去标签：每分钟请求数、长连接数；
  - 静音时 audio.m3u8 的请求次数，以及开关 chunkless 准备的差别；
  - 403 时加载错误策略走哪条路；
  - R1、R2、R3 三个场景分别在「分片真实地址」和「分片合成地址」下的结局：有没有错误传到播放器、多久恢复出画面；
  - 自定义 BandwidthMeter 能不能避开 `NetworkTypeObserver`；
  - 直播目标偏移 3 秒与默认值的卡顿次数。
- Verify：`./gradlew :app:testDebugUnitTest --tests 'vela.app.spike.*'` 执行数大于 0；报告里有原始数字表，并逐条对照第 1 节判据写出「达标」或「不达标」。
- Estimate：约 75 分钟。Depends：00。

**T1 伪上游与模拟器框架** · `android/test-upstream` · claude-sonnet-4-6（Python、CI 配置和兼容性约束）
- Files：`scripts/serve-test-stream.py`、`scripts/test_serve_test_stream.py`、`android/tools/emulator-run.sh`、`android/tools/fixtures/*.json`、`.github/workflows/android.yml`（新增 emulator 作业）
- Changes：给 `serve-test-stream.py` 加开关，全部默认关闭：`--catalog-fixtures`、`--ll-parts`、`--expire-idle 35`、`--rewind`、`--rotate-uri`、`--challenge`、`--request-log <jsonl>`。`emulator-run.sh` 负责：确认模拟器已启动、启停伪上游、传端口、抓 logcat、汇总结果，并分发到 `emu-modes/<模式>.sh`；`all` 依次执行已有的模式。
- Verify：`python3 -I scripts/test_serve_test_stream.py`（原有测试全部保留并通过，新开关各有测试）；`bash -n android/tools/emulator-run.sh`；`grep -nE 'stat -c|readlink -f|date -d|sed -i |grep -P' android/tools/*.sh` 无输出。
- Estimate：约 50 分钟。Depends：00。

### W2
**C2 播放策略** · `android/core-c2` · gemini-4-argon-high
- Files：`core/.../vela/core/hls/**` 及对应测试、`parity/*-hls.*`
- Changes：`RenewalCoordinator` 用 `Mutex` 加共享的 `Deferred` 实现，另提供可中断的 `renewBlocking(timeoutMs)`，有测试。
- Verify：`--tests 'vela.core.hls.*'`，parity `--group hls`；中继相关的 6 条测试已登记 skip 并写了 `replaced-by`。
- Estimate：约 60 分钟。Depends：L0b。

**C3 生命周期** · `android/core-c3` · claude-opus-4-6-thinking（状态矩阵和语义要准确）
- Files：`core/.../vela/core/lifecycle/**` 及对应测试
- Verify：`--tests 'vela.core.lifecycle.*'`，parity `--group lifecycle`。
- Estimate：约 50 分钟。Depends：L0a、L0b。

**C5 feed** · `android/core-c5` · gemini-4-hydrogen-high（FeedStore 状态多，锚点规则细）
- Files：`core/.../vela/core/feed/**` 及对应测试
- Verify：`--tests 'vela.core.feed.*'`，parity `--group feed`；VelaCore 和 ShortcutSearch 的条目逐条映射或登记 skip。
- Estimate：约 65 分钟。Depends：L0a。

**C6a 口味** · `android/core-c6a` · gemini-4-carbon-high（数值逻辑）
- Files：`core/.../vela/core/taste/**` 及对应测试
- Verify：`--tests 'vela.core.taste.*'`，parity `--group taste`；浮点差异写进 notes。
- Estimate：约 60 分钟。Depends：L0a。

**C6b 账本与停留** · `android/core-c6b` · gemini-3.8-flash-high（基本是直译）
- Files：`core/.../vela/core/ledger/**` 及对应测试
- Verify：`--tests 'vela.core.ledger.*'`，parity `--group ledger`。
- Estimate：约 45 分钟。Depends：L0a、L0b。

**C7 收藏** · `android/core-c7` · gemini-3.8-flash-high
- Files：`core/.../vela/core/curation/**` 及对应测试
- Verify：`--tests 'vela.core.curation.*'`，parity `--group curation`。
- Estimate：约 55 分钟。Depends：L0a、L0b。

**A1 应用基础设施** · `android/app-infra` · claude-sonnet-4-6
- Files：
  - `app/src/main/kotlin/vela/app/{infra,ui/theme,ui/agegate}/**`
  - `app/src/hooks/kotlin/vela/app/hooks/**`（`PerfProbe` 和站点根地址覆盖 `Endpoints` 归本包）
  - `app/src/main/res/values/strings_infra.xml`
  - `app/src/test/kotlin/vela/app/infra/**`
- Changes：
  - main 里提供 `Probe` 接口，默认空实现；hooks 源码集在启动时装上记录器。
  - `HttpClientImpl`：不存 cookie，超时 12 秒；
  - `Settings`：`muted` 不存在时视为静音；
  - 原子写文件；`ThumbnailCache`；年龄确认页。
- Verify：`:app:testDebugUnitTest --tests 'vela.app.infra.*'`。
- Estimate：约 55 分钟。Depends：L0a。

**A4 界面契约** · `android/app-contract` · claude-sonnet-4-6
- Files：`app/.../stage/StageContract.kt`、`app/src/test/.../stage/FakeStage.kt`
- Verify：`:app:testDebugUnitTest --tests '*StageContract*'`。
- Estimate：约 25 分钟。Depends：L0a。

### W3
**C4 stream** · `android/core-c4` · gemini-4-argon-high
- Files：`core/.../vela/core/stream/**` 及对应测试
- Verify：`--tests 'vela.core.stream.*'`，parity `--group stream`。
- Estimate：约 45 分钟。Depends：L0a、C2。

**C6c 推荐** · `android/core-c6c` · gemini-4-carbon-high（随机数要按 `ULong` 逐位复现）
- Files：`core/.../vela/core/recommend/**` 及对应测试
- Verify：`--tests 'vela.core.recommend.*'`，parity `--group recommend`；带种子的排序结果必须与 Swift 测试的期望逐个相等。
- Estimate：约 55 分钟。Depends：C5、C6a、C6b。

**C8 选房与结算** · `android/core-c8` · gemini-4-hydrogen-xhigh（全仓最复杂的纯逻辑）
- Files：`core/.../vela/core/selection/**` 及对应测试
- Verify：`--tests 'vela.core.selection.*'`，parity `--group selection`；LaneSelection 的 17 条全部对应上。
- Estimate：约 65 分钟。Depends：C5、C6a、C6b、L0b。

**P2 LaneDataSource** · `android/player` · claude-opus-4-6-thinking（并发加续期，正确性要求高）
- Files：`app/src/main/kotlin/vela/app/playback/**`、`app/src/test/kotlin/vela/app/playback/**`
- Changes：按第 1 节默认方案实现，并按 P1 的结论决定分片地址方式和静音主列表的形式。
- Tests：
  - `LaneDataSourceTest`：对照 RelayMultiSource 的 9 个场景；
  - 请求头：UA 和 Referer；
  - 音视频合一时的映射；
  - `getUri` 返回什么；
  - 中断与 `cancel()` 在 200 毫秒内返回；
  - `isRenewing` 的可见性；
  - 静音起播判据；
  - `LaneDataSourceAdoptionTest`：R1、R2、R3。
- Verify：`:app:testDebugUnitTest --tests 'vela.app.playback.*'` 执行数 ≥ 18。
- Estimate：约 80 分钟。Depends：C2、P1。
- 回退时改派：**R1** `android/core-relay`（gemini-4-argon-high，约 60 分钟）和 **R2** `android/app-relay`（claude-opus-4-6-thinking，约 70 分钟），内容见第 1 节。

**A2 音频焦点** · `android/app-audio` · claude-opus-4-6-thinking（语义改写，不是直译）
- Files：`core/.../vela/core/audio/**` 及对应测试、`app/src/main/kotlin/vela/app/audio/**` 及对应测试
- Changes：`AudioFocusPlan` 实现冻结裁决 2 和 8；`AudioFocusController` 用 `AudioFocusRequest` 执行它。
- Verify：`:core:test --tests 'vela.core.audio.*'` 加 `:app:testDebugUnitTest --tests 'vela.app.audio.*'`（Robolectric 的 ShadowAudioManager）；AudioTransitionTests 逐条映射。
- Estimate：约 50 分钟。Depends：C2、L0b。

### W4
**C9 跨组测试** · `android/core-c9` · claude-sonnet-4-6
- Files：`core/src/test/kotlin/vela/core/cross/**`、`parity/*-cross.*`
- Verify：`python3 -I tools/parity.py --all` 通过。
- Estimate：约 50 分钟。Depends：C2 到 C8。

**A3 单路播放与画面** · `android/app-lane` · claude-opus-4-6-thinking
- Files：`app/.../lane/**`、`app/src/androidTest/kotlin/vela/app/lane/**`、`android/tools/emu-modes/lane.sh`
- Changes：
  - `PlaybackLane`：暂停时 `stop()` 做到零网络；恢复时按 `resumeAction` 走 15 秒、30 秒两档；看门狗读 `isRenewing`；R1 场景原地重新 prepare 的预算；静默重试一次；失败分类。
  - `LaneSurface`：按 DisplayFit 定尺寸，模糊缩略图垫底，打 display-check 日志。
- Verify：云端跑 `:app:assemblePerfAndroidTest :app:testDebugUnitTest`；CI 的 `emulator-run.sh lane` 验首帧、`uncropped`、留边像素、R1 到 R3。如果模拟器结果推翻 P1 的结论，立即 HALT。
- Estimate：约 80 分钟。Depends：P2、C4、A1、T1。

**A5a 舞台：目录、收藏、口味** · `android/app-stage` · claude-opus-4-6-thinking
- Files：`app/.../stage/{StageViewModel.kt,Stage+Catalog.kt,Stage+Curation.kt,Stage+Taste.kt,Stage+Feed.kt}` 及对应测试、`res/values/strings_stage.xml`
- Changes：`updateFeed` 先在副本上变更，再交 `LaneSelection.resolve`（单窗时副路为空）；停留结算用 `LaneSettlement`。
- Verify：用假 HTTPClient 跑 JVM 测试：冷启动只发一次目录请求；未确认年龄不发请求；续页并入当前项之后；单窗选房结果与 FeedStore 一致。
- Estimate：约 80 分钟。Depends：C4、C5、C6c、C7、C8、A1、A4。

### W5
**A5b 起播链、侧车、质询** · `android/app-playchain` · claude-opus-4-6-thinking
- Files：`app/.../stage/{Stage+Playback.kt,Sidecar.kt,Challenge.kt}`（加串行重叠的 StageViewModel）、`app/src/androidTest/kotlin/vela/app/playchain/**`、`emu-modes/challenge.sh`、`res/values/strings_playchain.xml`
- Verify：JVM 测试；CI 的 `emulator-run.sh challenge`。
- Estimate：约 85 分钟。Depends：A5a、A3、C3。

**A6 单窗界面** · `android/app-ui` · gemini-4-carbon-high
- Files：`app/.../ui/{StageScreen,Chrome,FavoritesSheet,TasteSheet,ChallengeSheet}.kt` 及对应测试、`res/values/strings_ui.xml`
- Changes：垂直分页只渲染当前页和上下各一页，播放器只挂在当前页。
- Verify：Robolectric 的 Compose 测试；`grep -rnE 'ContentScale\.Crop|FillBounds|RESIZE_MODE_ZOOM' app/src/main` 无输出。
- Estimate：约 70 分钟。Depends：A3、A4。

### W6、W7
**A7 生命周期接线** · `android/app-lifecycle` · claude-opus-4-6-thinking
- Files：`app/.../stage/LifecycleWiring.kt`（新建）、串行重叠的 MainActivity 和 StageViewModel、`app/src/androidTest/kotlin/vela/app/lifecycle/**`、`emu-modes/background.sh`
- Changes：后台按 `PhoneLifecycle.actions` 依次执行：作废代次、结算停留、释放各路、销毁侧车、放弃音频焦点；回前台按冻结裁决 7 恢复。
- Verify：CI 的 `emulator-run.sh background` 满足 S-A5。
- Estimate：约 55 分钟。Depends：A5b、A6、A2、C3、C8。

**E1 里程碑 A 端到端** · `android/e2e-a` · claude-sonnet-4-6
- Files：`app/src/androidTest/kotlin/vela/app/e2e/a/**`、`emu-modes/e2e-a.sh`
- Verify：CI 全绿，资源 JSON 已产出。
- Estimate：约 40 分钟。Depends：A7。

**D-A 文档** · `android/docs-a` · gpt-oss-120b-medium（文档同步，编排者复核语义）
- Files：`docs/ARCHITECTURE.md`（新增安卓一节）、`docs/PERF.md`（新增安卓一节）、`README.md`（新增安卓一段）
- Verify：`git diff` 只动这三个文件；写到的每个模块路径都存在。
- Estimate：约 30 分钟。
- ADR 正文由编排者自己写。

### B 双窗
- **B0 契约扩展** · `android/dual-contract` · claude-sonnet-4-6 · 约 20 分钟。写串行重叠的 StageContract。
- **B1 双路舞台** · `android/dual-stage` · claude-opus-4-6-thinking · 约 85 分钟
  - Files：`stage/Stage+Lanes.kt`、`lane/ThermalMonitor.kt`，加串行重叠的 StageViewModel、LifecycleWiring、PlaybackLane。
  - Changes：接入第二路；所有操作带 RoomTarget；接入 LaneQuality 热降档、分类器降级、LaneSettlement 双路结算。
- **B2 双窗界面** · `android/dual-ui` · gemini-4-carbon-high · 约 55 分钟
  - Files：`ui/DualStageScreen.kt`，加串行重叠的 StageScreen、MainActivity。
- **E2 双窗端到端** · `android/e2e-b` · claude-sonnet-4-6 · 约 45 分钟
  - Files：`androidTest/.../e2e/b/**`、`emu-modes/e2e-b.sh`。
  - 覆盖 S-B1、S-B2。
- **D-B 文档** · gpt-oss-120b-medium。

### C 画中画
- **PiP1** · `android/pip` · claude-opus-4-6-thinking · 约 75 分钟
  - Files：`pip/**`、`res/drawable/ic_pip_*.xml`、`res/values/strings_pip.xml`、`androidTest/.../pip/**`、`emu-modes/pip.sh`，加串行重叠的 Manifest、MainActivity、StageViewModel、StageContract、LifecycleWiring。
  - Changes：比例夹紧；用 `RemoteAction` 切换静音；`LifecycleEpoch` 只放行焦点路；3 秒内没进入画中画就全停。
- **E3 画中画端到端** · `android/e2e-c` · claude-sonnet-4-6 · 约 40 分钟
  - Files：`androidTest/.../e2e/c/**`、`emu-modes/e2e-c.sh`。
  - 覆盖 S-C1 到 S-C3。
- **D-C 文档** · gpt-oss-120b-medium。

Ready for execution



---

## 修订 2.1（编排者，2026-11-18，按第 2 轮审查的 3 个阻塞项；与上文冲突时以本节为准）

**2.1-1 测试与 PiPPolicy 的跨波依赖**
- 执行者规则补充：某条 Swift 测试引用了排在后面组的类型时，**不停下**，在 `android/parity/split-<组>.tsv` 登记 `deferred:C9` 并继续；源码本身编译不过才停下报告。
- 预先登记为 deferred:C9 的条目：EndpointBackoffTests 中引用 StreamDecoder、CatalogAPI.fetch、LastBroadcastFetcher 的条目（L0a）；PreferenceQuarantineTests 中引用 TasteStore、CurationStore 的条目（L0b；`Preferences.maxFileBytes` 改为引用 L0b 自己的常量副本或等 L0a 合入后由 C9 补）；PlaybackRefreshTests 中引用 FeedStore 的条目（C2）；ChallengeGateTests 中引用 StreamDecoder、CatalogAPI 的条目（C3）；PiPPolicyTests 中断言音频会话配置的条目（C3）。C9 的依赖补上 A2。
- PiPPolicy 的音频输出在安卓上改为平台无关的 `AudioIntent`（例如 `holdsFocus: Boolean`、`mixWithOthers: Boolean`、`releaseNotifyingOthers: Boolean`），定义在 C3 自己的范围 `vela.core.lifecycle.AudioIntent`；A2 的 `AudioFocusPlan` 只消费它。C3 不依赖 A2。

**2.1-2 不能跨文件拆分的 Kotlin 类型**
拆开的符号一律落成独立的顶层类型，由后面组的类型引用，并在 parity 映射里注明改名：
- `SiteSearch.normalized` → L0a `vela.core.base.site.SiteSearchText.normalized`；C5 的 `SiteSearch`（含 Submit、Edit、MissRecovery、MissOutcome）调用它。
- `Curation.key` → L0a `vela.core.base.model.CurationKeys.key`；C7 的 `Curation` 调用它。
- `LaneSettlement.EndReason` → L0b `vela.core.base.lane.LaneEndReason`；C8 的 `LaneSettlement` 用它（typealias 也可）。
- `RelayPolicy.defaultTargetDuration` → L0b `vela.core.base.play.RelayDefaults.defaultTargetDuration`；回退包 R1 的 `RelayPolicy` 引用它，不改 L0b 文件。

**2.1-3 验收编号**：冻结区块的编号已更正为 S-A1–S-A6、S-B1–S-B2、S-C1–S-C3。

**采纳建议**
- 第 6 节权限检查以白名单为准（INTERNET、ACCESS_NETWORK_STATE、androidx 自动合并的 `<包名>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`），与第 2 节一致。
- P2 的 `LaneDataSourceAdoptionTest` 对 R1 只断言：序号回退带 DISCONTINUITY 时，错误以可识别类型交给加载错误策略（或列表被采纳），不产生续期；「原地重新 prepare、60 秒内最多 2 次」由 A3 断言。
- `android.yml` 的路径过滤加上 `scripts/serve-test-stream.py`、`scripts/make-test-stream.sh`（00 已派发；由 T1 在改该工作流时补）。
- 00 合入后编排者按第 2 节逐项核对 parity 与 check-apk 的实际行为。
