# Implementation Plan: Vela 安卓版（Kotlin + Compose + Media3）

> 预算估算：编排侧约 300 次工具调用。分配如下：23 个云端任务包，每包约 8 次（派发、核对 `git ls-remote`、拉分支、本机后台验收、裁决、合入集成分支），合计约 185 次；3 个里程碑 PR，每个约 25 次（审计、盲审、CI、合并、文档），合计约 75 次；本机装环境、签名、发布、真站冒烟约 40 次。
> ⏸ COMPACT CHECKPOINT 放在三处：W2 结束后、里程碑 A 合并后、里程碑 B 合并后。

## Overview
在公开仓里新增独立的 `android/` Gradle 工程。`VelaCore` 按文件对照移植成纯 Kotlin JVM 模块 `:core`，测试也逐条对照移植。播放用 Media3 ExoPlayer，**不移植本地中继**，改成进程内的 DataSource 层（理由见 §1）。按 A 单窗、B 双窗、C 画中画三个里程碑交付，每个里程碑一个 PR。不改 macOS、iOS 的源码，只追加文档、ADR，新增一个 CI 工作流文件。

## Design Reference
iOS 计划 `docs/plans/ios.md`（第 4 版、A1 到 A3）。本计划批准后写入 `docs/plans/android.md`，冻结区块照抄上面「用户已裁决」那一段。

---

## 1. 中继评估与结论

**中继现在做的五件事**（依据 `PlaylistRelay.swift`、`RelayFeed.swift`、`UpstreamRenewal.swift`、ARCHITECTURE 5.1、5.2 和第 10 节）：
1. 去掉低延迟标签：`PlaylistLadder.steadyPlaylist` 删 PART、PRELOAD-HINT、SERVER-CONTROL 等，原因是 AVPlayer 在直播边缘追分片会卡；同时把相对地址改成绝对地址。
2. 生成只含选中一档的本地主列表，带独立音轨（`StreamRendition`），因为上游主列表 30 秒就 403。
3. 新鲜度：`RelayFeed` 的宽限是 1×T，`RelayWait` 有界等待后回 503。这是专为 AVPlayer 约 1.5×T 的判定调的。
4. 403 续期：子列表闲置约 35 秒后 403。`RenewalCoordinator` 让视频和音轨共用一次续期，`RenewalBudget` 限 60 秒内 3 次、间隔 3 秒，本地地址不变，AVPlayer 无感。
5. 静音时停刷音频清单（`setAudioSuppressed`）。另外还有 `RelaySourceTable` 多源、监听代次、`allowStart` 和 `stopListener`，这些只是为了在 iOS 后台回收套接字。

**结论：安卓不要本地 HTTP 中继，改成每路一个进程内的 `LaneDataSource`。**

| 中继职责 | 安卓做法 |
|---|---|
| 2 本地主列表 | `LaneDataSource` 认合成地址 `vela://lane/<id>/master.m3u8`，从内存返回只含一档的主列表（移植 `StreamRendition` 的本地主列表生成）。ExoPlayer 只看到一档，不会自己切档，两路最高档天然成立；降档时重建主列表再重新 prepare，对应 `retier`。 |
| 4 续期 | 子列表也用合成地址 `vela://lane/<id>/{video,audio}.m3u8`，由 DataSource 映射到当前真实地址，这一点和中继「本地地址不变、上游地址可换」完全等价。`open()` 遇到 403 或 410 时，在加载线程上阻塞调用 Kotlin 版 `RenewalCoordinator`（视频和音轨共用一个 `Deferred`，最长等 `RenewalPolicy.waitBound` 8 秒）。换上新地址后重开一次；失败就抛出，交给加载错误策略。 |
| 1 去低延迟 | 改写也在 DataSource 里做：读完整个列表，调移植过来的 `steadyPlaylist(text, base = 真实地址)`。只能放这里，因为合成地址做不了相对地址的基准。默认去掉低延迟标签：请求更少，也没有阻塞式重载的长连接，符合轻量要求。原生低延迟留一个开关，P1 实验用数据决定默认值。 |
| 3 新鲜度 | 不移植 `RelayFeed`、`RelayWait`。由 ExoPlayer 自己的 `DefaultHlsPlaylistTracker` 负责刷新节奏和卡住检测（playlist stuck 系数），再加我们移植的卡住看门狗 `PlaybackPolicy`。自定义 `VelaLoadErrorPolicy`：因为只有一档，403 时不排除轨道（`getFallbackSelectionFor` 返回 null）；临时错误退避重试。 |
| 5 静音 | 关掉音频轨道（`setTrackTypeDisabled(AUDIO)`）。不出声的轨道不再拉取，这一点由 P1 用请求计数核实。 |
| 多源、监听代次 | 不需要。每路有自己的播放器和 DataSource，释放播放器就清干净了。 |

额外收益：发行版没有任何明文 HTTP（不需要 cleartext 例外），后台也没有监听套接字。
不移植的文件：`RelayFeed`、`RelaySourceTable`，以及 `PlaylistRelay` 本身。

## 2. 工程结构
- `android/settings.gradle.kts` 只有 `:core` 和 `:app` 两个模块。
- 版本统一放在 `gradle/libs.versions.toml`，AGP 和 Kotlin 用执行时的最新稳定版，JDK 17。
- `:core`：`kotlin("jvm")`，包 `vela.core.*`。依赖只有 kotlinx-coroutines-core（Compose 本来就带）和 kotlinx-serialization-json（对应 Codable）。测试用 JUnit 加 coroutines-test。任何 Linux 上 `./gradlew :core:test` 都能跑。
  - 时间一律用注入的 `Long` 毫秒时钟，不用 `java.time`。
  - `CGRect` 换成自己定义的 `Rect`、`Size`。
- `:app`，applicationId `com.vela.stage.android`：
  - 依赖：activity-compose、compose ui/foundation（**不要 material、material3、icons**）、media3-exoplayer、media3-exoplayer-hls。测试用 robolectric、media3-test-utils(-robolectric)、compose ui-test、androidx.test。
  - **不用** OkHttp、Coil、Hilt、Room、DataStore、Navigation、media3-ui：
    - 网络用 HttpURLConnection；
    - 缩略图用 `LruCache` 约 2 MB，模糊用「缩到 16px 再放大」，因为 `Modifier.blur` 要 API 31；
    - 存储用 SharedPreferences 加 `filesDir/Vela/*.json` 原子写；
    - 画面用 `SurfaceView`（比 TextureView 省电），按 `DisplayFit.fitted` 定尺寸。
- 构建类型三种：
  - `debug`；
  - `perf`：以 release 为底，开 R8，用 debug 签名，后缀 `.perf`，测试钩子放 `src/hooks`，只编进 debug 和 perf；
  - `release`：不含钩子。
- `testBuildType = "perf"`。
- 只用 `HlsMediaSource.Factory` 作媒体源工厂，渲染器只要视频和音频，这样 R8 能剪掉 progressive 和各种 extractor。
- 再给一个不做测速的 BandwidthMeter，避免 `NetworkTypeObserver` 常驻监听网络变化广播（P1 核实）。
- **minSdk 26**，理由：
  - 画中画、`AudioFocusRequest` 都从 API 26 起；
  - 不用为 `java.time` 做脱糖；
  - 覆盖面约 97%。
  - 画中画自动进入在 API 31 及以上用 `setAutoEnterEnabled`，26 到 30 用 `onUserLeaveHint`；热状态在 API 29 及以上读，以下恒为 nominal。
- targetSdk 用执行时的最新版。
- 不进云备份：`allowBackup="false"`，再加一个全部排除的 `dataExtractionRules`。

## 3. 移植清单（共 62 个文件；单窗必需的先做）
| 波次与任务包 | 包 | Swift 文件 | 对照测试 |
|---|---|---|---|
| W1 C1 | model / net / store | Models、HTTP、PlaybackError、AudioPolicy、EndpointBackoff、TimedFanOut、DeferredSaver、PreferenceQuarantine，另把 `FeedSource` 从 Recommender 抽出来 | VelaCore（模型部分）、PlaybackError、AudioNetwork、MuteAudio（改为关音轨的语义）、EndpointBackoff、BOMShape、FileLimit、DeferredSaver、PreferenceQuarantine |
| W1 C2 | hls | PlaylistLadder、UpstreamRenewal、PlaybackFailureClassifier（白名单换成 Media3 的解码器初始化失败、解码资源被回收）、DisplayFit、LaneQuality、PlaybackRefresh | 同名测试 |
| W1 C3 | lifecycle / lane | LaneCursor、RoomTarget、LifecycleEpoch、PhoneLifecycle、PiPPolicy、AudioTransition（改写成 `AudioFocusPlan`）、SkipTracker、AwayRetry、ChallengeGate | 同名测试；AudioTransition 断言改成音频焦点的语义 |
| W2 C4 | stream | StreamAPI、StreamSourcing、StreamRendition、PlaybackPolicy | StreamSourcing、ChatContextProbe、StreamRendition、GeoRestricted、PlaybackPolicy |
| W2 C5 | feed | CatalogAPI、SiteSearch、FeedStore（含 iOS 新增的 4 个接口）、LoadSlot、PromptRules（不含 SheetLayout）、InfoVisibility | Catalog、FreshCams、StartupCatalog、SiteSearch、FeedReorder/Cache/Anchor、LoadSlot、LoadCommit、PromptRules、InfoVisibility、VetoVisibility |
| W2 C6 | taste | Taste、BetaSampler、Feedback、TagRarity、StreamerAffinity、TasteTargeting、TasteSummary、TasteStore、Recommender、WatchLedger、DwellTracker | Taste、FeedRanking、RecommendTuning/Scenario、Dwell、WatchLedger、WatchAccounting、TasteRecordingOverload |
| W3 C7 | curation | Curation、FavoriteStatus、FavoritesBoard、LastBroadcast、HotTags | 同名测试 |
| W3 C8（B 用） | selection | LaneSelection、LaneSettlement | 同名测试 |
| W4 C9 | cross | 只写测试 | StageFlow、ReviewFix、PlaybackFlowFix、SearchFix13、TargetedMerge |
| 不移植 | — | MenuBarVisibility、WindowDragPolicy、ShortcutGate、Pager、Presence（手机上恒为 engaged）、QualityGovernor（按指针离开降档）、PortraitFit（不裁切以后用不到）、RelayFeed、RelaySourceTable、AssignIf（StateFlow 自带同值去重）、Preferences（旧数据迁移，只保留 Taste 用到的常量） | 对应测试写进 `android/parity/skip.tsv` 并注明理由。RelayMultiSource 的 9 个场景改写进 `LaneDataSourceTest`（P2） |

**行为一致的机器门**：每个 Kotlin 测试类头部写 `// swift: <文件>`，每个测试写 `// swift-test: <名字>`。`android/tools/parity.py` 检查三件事：
- 已移植文件里每个 Swift 测试都有对应项（或者登记在 skip 里）；
- 测试报告 XML 里实际执行数大于 0；
- 失败数为 0。

测试数据和断言照抄 Swift 版。Recommender 的带种子随机数要按 `ULong` 逐位复现。Swift 行为有疑点时照原样移植，记进 `parity/<组>.notes.md`，不擅自修。

## 4. 并行波次（任务包全文见附录）
| 波次 | 任务包 | 并行路数 |
|---|---|---|
| W0 | 00 脚手架 | 1 |
| W1 | C1、C2、C3、P1、T1 | 5 |
| W2 | C4、C5、C6、A1、A2、A4、P2 | 7 |
| W3 | C7、C8、A3 | 3 |
| W4 | A5a、A6、C9 | 3 |
| W5 | A5b → A7 → E1 | 串行，之后合并 PR-A |
| B | B0 → B1、B2 并行 → E2 | — |
| C | PiP1 → E3 | 串行 |

## 5. CI（新增 `.github/workflows/android.yml`，只在 `android/**` 变动时触发）
- `android-unit`（ubuntu，Temurin 17，gradle/actions/setup-gradle）：
  - `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`（release 不签名）；
  - `python3 -I android/tools/parity.py --all`；
  - `bash android/tools/check-apk.sh <release.apk>`，检查：体积 ≤ 5 MB；权限只有 INTERNET 和 ACCESS_NETWORK_STATE；`allowBackup=false`；没有 service 和 boot receiver；dex 里没有 `vela.app.hooks`。
- `android-emulator`（依赖上一个作业）：
  - 先开 KVM udev 规则，`apt-get install ffmpeg`，然后 `scripts/make-test-stream.sh`；
  - `android/tools/fake_upstream.py` 只绑 127.0.0.1、端口由系统分配；
  - 用 `reactivecircus/android-emulator-runner@v2`（api 34、x86_64、google_apis，参数 `-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`，关动画）；
  - 只跑一个 `android/tools/emulator-run.sh`，因为该 action 会把 script 的每一行拆成单独的 shell。脚本里执行 `connectedPerfAndroidTest -Pvela.upstream=http://10.0.2.2:<端口>`。
  - 全程不连真站点（ADR-004），用的是合成目录加固定测试流。

## 6. 实跑与自证（模拟器，perf 构建，静音）
- **起播**：测试进程里有 `PerfProbe` 记录 `onRenderedFirstFrame` 的时刻，断言首帧 ≤ 8 秒；`DecoderCounters.renderedOutputBufferCount` 要持续增长。
- **比例**（对应 display-check）：
  - logcat 打 `display-check lane= gravity=fit presentation= bounds= videoRect= uncropped=`；
  - instrumented 测试断言 `DisplayFit.isUncropped` 为真；
  - 在设备内用 `UiAutomation.takeScreenshot()` 统计留边区像素：不能 95% 以上是纯黑。位图只在设备上判，不取回。
  - 用 grep 禁止视频层出现 `ContentScale.Crop`、`RESIZE_MODE_ZOOM`、`FillBounds`。
- **后台零开销**：按 HOME（画中画关）5 秒后检查：
  - `fake_upstream` 的请求日志在之后 30 秒里为 0 条；
  - `adb shell cat /proc/<pid>/stat` 的 utime+stime 增量 ≤ 1 tick；
  - `ps -T -p <pid>` 里没有 `ExoPlayer:Playback` 线程；
  - 没有 WebView 渲染子进程。
- **资源**：`dumpsys meminfo <包名>` 的 TOTAL PSS 与 `dumpsys cpuinfo` 每 5 秒采一次，输出一行 JSON。阈值在里程碑 A 首测后写进 `docs/PERF.md`，之后按「基线 +10%」做门；模拟器的绝对值不当结论。
- **网络**：伪服务器发出的字节数 ÷（所选档位带宽 × 时长）≤ 1.15。
- **静音**：默认 `volume == 0`，并且音频轨道已关，伪服务器收不到 audio.m3u8 请求。
- **真站冒烟**（只在本机做，ADR-004）：m424 上跑无窗口 arm64 模拟器，经 `ssh localhost` 回环，播放 30 分钟。看 `renew ok/failed`、播放错误为 0、卡住重建的次数。
- **APK 体积**：release ≤ 5 MB 是硬门；00 先记下空壳加全部依赖的基线。

## 7. 公开方案调研（未联网核实：本代理没有 WebSearch/WebFetch。00 任务包须逐条打开核对，并在 PORTING.md 记下核对日期）
| 出处 | 借什么 | 不借什么 |
|---|---|---|
| Media3 HLS 与直播文档（developer.android.com/media/media3/exoplayer/hls、/live-streaming、/customization） | 原生低延迟 HLS、`LiveConfiguration.targetOffsetMs`、`HlsMediaSource.Factory` 注入 DataSource 与 LoadErrorHandlingPolicy、`ResolvingDataSource` 的思路、`TrackSelectionParameters` 关音轨与 `setMaxVideoBitrate` | 自适应切档、media3-ui 的 PlayerView、MediaSession |
| Android 画中画文档（develop/ui/views/picture-in-picture） | `setAutoEnterEnabled`（31+）与 `onUserLeaveHint` 兜底；宽高比限制在 1:2.39 到 2.39:1 之间，要夹紧；`RemoteAction` 做静音切换；`onPictureInPictureModeChanged` | 前台服务、后台音频 |
| 音频焦点文档（media/optimize/audio-focus） | 取消静音时请求 `AUDIOFOCUS_GAIN_TRANSIENT`，对方收到的是暂时失去焦点，我们放弃后它会恢复，对应 iOS 的 `notifyOthersOnDeactivation`；静音时不请求焦点，所以不打断他人；`ACTION_AUDIO_BECOMING_NOISY` 时转静音 | ExoPlayer 内置的 `handleAudioFocus`（它请求的是永久 GAIN，对方不会恢复） |

## Risks & Mitigations
- **ExoPlayer 去掉低延迟标签后不稳，或静音后仍拉音频** → P1 用伪服务器做对比实验，数据写进 `android/docs/spike-ll-hls.md`，由编排者裁决默认值。
- **Media3 的 `@UnstableApi` 升级后改名** → 版本锁在 toml 里，升级单独开 PR。
- **云端是 Linux、交付在 macOS** → 本机用的脚本（`emulator-run.sh`、`check-apk.sh`）禁止只写 GNU 写法，也要在 m424 上跑一遍。
- **云端测试空成功** → parity 工具读测试报告 XML，执行数为 0 即失败；00 要证明负样本确实失败。
- **签名密钥丢失后无法覆盖安装** → keystore 只放本机，权限 0600，另做一份离线备份；Gradle 签名只从环境变量 `VELA_KEYSTORE*` 读，仓库里不出现路径。
- **公开仓泄露隐私** → 测试数据一律用合成用户名（room_a 之类），提交作者用 noreply 地址，PR 前 grep 一遍邮箱和本机路径。
- **热状态映射主观** → 暂定 MODERATE 对应 serious、SEVERE 及以上对应 critical，写进 ADR；用调试钩子注入热状态来验证。

## Success Criteria
- [ ] **A 单窗**：
  - parity 全绿，执行数大于 0；
  - 模拟器 E2E 跑通：年龄确认 → 目录 → 首帧 → 划动换房 → 收藏和口味在杀进程后仍在；
  - 默认静音；
  - 横屏流在竖屏下 `uncropped=true`，留边不是纯黑；
  - 受控 403 续期不报播放错误；
  - 暂停后零请求，后台 5 秒内零请求、零 tick、无播放线程；
  - 质询走 WebView 的路径跑通；
  - release ≤ 5 MB 且不含钩子；
  - 本机签名 APK 经 tailnet 链接发布（附 sha256），真站冒烟 30 分钟无错误。
- [ ] **B 双窗**：
  - 默认单窗，双窗开关被记住；
  - 两路各自划动，在 S8 列出的场景里永不同房；
  - 只有焦点路开着音频轨道；
  - 两路都是最高档；
  - 注入热状态后降档，持续 60 秒才回升；
  - 注入解码资源错误时副路降级，损坏码流仍按房间失败；
  - 两路同时出画面的时长 ≥ 测试窗口的 90%。
- [ ] **C 画中画**：
  - 按 HOME 后 3 秒内自动进入，否则退回全停；
  - 画中画里只有焦点路联网；
  - 静音时焦点栈里没有本包（`dumpsys audio` 核对），取消静音后有 GAIN_TRANSIENT，再静音即放弃焦点；
  - 画中画比例正确、不裁切；
  - 关掉「自动进入」后回桌面即全停；
  - 关闭画中画后零开销。

## 需要你决定的事（开工前）
1. **m424 装 JDK 17、Android cmdline-tools、build-tools 和 platform-tools**，约 1.5 GB，本机签名必须有。要不要再装 arm64 模拟器镜像做真站冒烟（总共约 6 GB）？推荐都装。否则真站只能在手机上验，需要你开无线调试。
2. 手机的安卓版本和型号：用来确认 minSdk 26 合适、热状态接口可用。
3. 取消静音时请求 GAIN_TRANSIENT：好处是你再静音后对方能恢复播放；代价是偏离文档对「短时」的用法。推荐采用。
4. 云端模型分工是按名字推测档位排的，W1 跑完后会按通过率调整。

---

## 附录 A：任务包（统一约束见末尾）

**00 脚手架** · 分支 `android/scaffold` · claude-sonnet-4-6（Gradle 和 CI 套路熟、守规范）
- Files：
  - `android/{settings.gradle.kts,build.gradle.kts,gradle.properties,gradlew,gradlew.bat,gradle/**}`
  - `android/core/build.gradle.kts`
  - `android/app/{build.gradle.kts,proguard-rules.pro,src/main/AndroidManifest.xml,src/main/res/xml/data_extraction_rules.xml,src/main/kotlin/vela/app/MainActivity.kt}`
  - `android/{PORTING.md,parity/skip.tsv}`
  - `android/tools/{parity.py,test_parity.py,check-apk.sh}`
  - `.github/workflows/android.yml`（只含 unit 作业）
  - `.gitignore`（追加 `android/**/build`、`.gradle`、`local.properties`、`*.jks`、`*.keystore`、`keystore.properties`、`preview/`）
- Changes：按 §2 搭好两个模块和三种构建类型，**一次声明全部依赖**（后续任务包不得再改依赖）；PORTING.md 写类型映射、测试注释约定，以及 §7 的核对记录。
- Verify：
  - `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` 成功；
  - `python3 -I android/tools/test_parity.py` 通过（含负样本：缺一个映射、执行数为 0，都必须判失败）；
  - `check-apk.sh` 对 release 包通过，并输出体积；加 `--max-kb 1` 时必须失败。
- Estimate：约 45 分钟。Depends：无。

**C1 基础模型** · `android/core-base` · gemini-4-carbon-high（Codable 换成 serialization 是细活，量中等）
- Files：`android/core/src/{main,test}/kotlin/vela/core/{model,net,store}/**`、`android/parity/base.tsv`
- Changes/Tests：见 §3 W1 C1 一行，先让测试跑红再实现。
- Verify：
  - `./gradlew :core:test --tests 'vela.core.model.*' --tests 'vela.core.net.*' --tests 'vela.core.store.*'`；
  - `python3 -I tools/parity.py --group base`（退出码 0）。
- Estimate：约 40 分钟。Depends：00。

**C2 hls 纯逻辑** · `android/core-hls` · gemini-4-argon-high（字符串和并发协调，规模中等）
- Files：`core/src/{main,test}/kotlin/vela/core/hls/**`、`parity/hls.tsv`
- Changes：`RenewalCoordinator` 用 `Mutex` 加共享的 `Deferred`，另提供 `renewBlocking(timeoutMs)` 给加载线程用；`PlaybackFailureClassifier` 的码值写成常量，并注明 Media3 出处。
- Verify：`--tests 'vela.core.hls.*'`，parity 的 `--group hls`。
- Estimate：约 40 分钟。Depends：00。

**C3 生命周期纯逻辑** · `android/core-lifecycle` · claude-opus-4-6-thinking（音频焦点是语义改写，不是直译）
- Files：`core/src/{main,test}/kotlin/vela/core/{lifecycle,lane}/**`、`parity/lifecycle.tsv`
- Changes：`AudioFocusPlan` 的步骤是：静音旧路 → 请求焦点（GAIN_TRANSIENT） → 获准后才取消目标路的静音；失败时保持静音，只改运行时状态；再静音、进后台、最后一路停播时放弃焦点；失去焦点或拔耳机时转静音，且不自动恢复出声。
- Verify：`--tests 'vela.core.lifecycle.*' --tests 'vela.core.lane.*'`，parity。
- Estimate：约 50 分钟。Depends：00。

**P1 播放管线对比实验** · `android/player-spike` · gemini-4-hydrogen-xhigh（Media3 内部接口，要读源码）
- Files：`app/src/test/kotlin/vela/app/spike/**`、`android/docs/spike-ll-hls.md`
- Changes：在 Robolectric 里用 media3 test-utils 跑 ExoPlayer，对接 JDK HttpServer 写的伪低延迟上游（带 PART 和 PRELOAD-HINT），对比原生低延迟和去标签两种方式：
  - 每分钟请求数、长连接数；
  - 静音时有没有 audio.m3u8 请求；
  - 子列表返回 403 时加载错误策略的路径；
  - 自定义 BandwidthMeter 能不能避开 NetworkTypeObserver。
- Verify：`./gradlew :app:testDebugUnitTest --tests 'vela.app.spike.*'` 执行数大于 0；文档里有原始数字表和推荐默认值。
- Estimate：约 60 分钟。Depends：00。

**T1 伪上游与模拟器作业** · `android/test-upstream` · claude-sonnet-4-6
- Files：
  - `android/tools/{fake_upstream.py,test_fake_upstream.py,emulator-run.sh,fixtures/*.json}`（全部是合成数据）
  - `.github/workflows/android.yml`（新增 emulator 作业）
- Changes：`fake_upstream.py` 提供：目录、chatvideocontext（`hls_source` 指向本地固定流）；`--ll-parts`；`--expire-idle 35`（闲置超时就 403，续期后换 `session=`）；`--challenge`（返回 HTML 质询页）；请求日志写 JSONL，带时间戳。
- Verify：`python3 -I android/tools/test_fake_upstream.py` 通过（覆盖闲置 403、续期后 200、请求日志）；`bash -n` 检查脚本，且不含 GNU 专有写法。
- Estimate：约 45 分钟。Depends：00。

**C4 stream** · `android/core-stream` · gemini-4-argon-high
- Files：`core/src/{main,test}/kotlin/vela/core/stream/**`、`parity/stream.tsv`
- Verify：`--tests 'vela.core.stream.*'`，parity。
- Estimate：约 45 分钟。Depends：C1、C2。

**C5 feed** · `android/core-feed` · gemini-4-hydrogen-high（FeedStore 状态多、锚点规则细）
- Files：`core/.../vela/core/feed/**`、`parity/feed.tsv`
- Verify：`--tests 'vela.core.feed.*'`，parity。
- Estimate：约 60 分钟。Depends：C1。

**C6 taste** · `android/core-taste` · gemini-4-carbon-high（数值逻辑，要逐位复现随机数）
- Files：`core/.../vela/core/taste/**`、`parity/taste.tsv`
- Verify：`--tests 'vela.core.taste.*'`，parity；带种子的排序结果必须与 Swift 测试的期望逐个相等。
- Estimate：约 60 分钟。Depends：C1。

**A1 应用基础设施** · `android/app-infra` · claude-sonnet-4-6
- Files：`app/src/main/kotlin/vela/app/{infra,ui/theme,ui/agegate}/**`、`app/src/hooks/kotlin/vela/app/hooks/**`、`app/src/main/res/values/strings.xml`、`app/src/test/kotlin/vela/app/infra/**`
- Changes：
  - `HttpClientImpl`：不存 cookie，超时 12 秒；
  - `Settings`：`muted` 不存在时视为静音；
  - 原子文件写；
  - `ThumbnailCache`；
  - 年龄确认页；
  - 钩子 `Endpoints` 覆盖站点根地址（只在 hooks 源码集里）。
- Verify：`:app:testDebugUnitTest --tests 'vela.app.infra.*'`，伪服务器绑 127.0.0.1、端口用 0。
- Estimate：约 50 分钟。Depends：C1。

**A2 音频焦点控制器** · `android/app-audio` · claude-sonnet-4-6
- Files：`app/src/main/kotlin/vela/app/audio/**`、对应测试
- Verify：Robolectric 的 ShadowAudioManager 断言请求类型、失败时保持静音、BECOMING_NOISY 时转静音。
- Estimate：约 30 分钟。Depends：C3。

**A4 UI 契约** · `android/app-contract` · claude-sonnet-4-6
- Files：`app/src/main/kotlin/vela/app/stage/StageContract.kt`、`app/src/test/.../FakeStage.kt`
- Verify：`:app:testDebugUnitTest --tests '*StageContract*'`。
- Estimate：约 20 分钟。Depends：C1。

**P2 LaneDataSource** · `android/player` · claude-opus-4-6-thinking（并发加续期，正确性要求高）
- Files：`app/src/main/kotlin/vela/app/playback/**`、`app/src/test/kotlin/vela/app/playback/**`
- Changes：按 §1 实现 `LaneDataSource`、`VelaLoadErrorPolicy`、`PlayerFactory`。
- Tests：把 RelayMultiSource 的场景搬过来：两路并发；作废 A 不影响 B；受控 403 只续 A，视频和音轨各自 403 也只续一次；B 静音时不请求音频；预算耗尽后抛出 PlaybackException；释放后迟到的续期结果被丢弃。
- Verify：`:app:testDebugUnitTest --tests 'vela.app.playback.*'` 执行数 ≥ 9。
- Estimate：约 70 分钟。Depends：C2、P1。

**C7 curation** · `android/core-curation` · gemini-3.8-flash-high（基本是直译解码）
- Files：`core/.../vela/core/curation/**`、`parity/curation.tsv`
- Verify：`--tests 'vela.core.curation.*'`，parity。
- Estimate：约 45 分钟。Depends：C5、C6。

**C8 selection** · `android/core-selection` · gemini-4-hydrogen-xhigh（全仓最复杂的纯逻辑）
- Files：`core/.../vela/core/selection/**`、`parity/selection.tsv`
- Verify：`--tests 'vela.core.selection.*'`，parity；LaneSelection 的 17 个用例全部对应上。
- Estimate：约 60 分钟。Depends：C3、C5、C6。

**A3 单路播放与画面** · `android/app-lane` · claude-opus-4-6-thinking
- Files：`app/src/main/kotlin/vela/app/lane/**`、`app/src/androidTest/kotlin/vela/app/lane/**`
- Changes：`PlaybackLane` 负责：
  - 暂停时 `stop()`，做到零网络；
  - 恢复时按 `resumeAction` 走 15 秒、30 秒两档；
  - 看门狗；
  - 静默重试一次；
  - 失败分类。
  `LaneSurface` 负责：SurfaceView 按 DisplayFit 定尺寸，模糊缩略图垫底，打 display-check 日志。
- Verify：本机或 CI 运行 `bash android/tools/emulator-run.sh lane`，断言首帧、`uncropped`、留边像素不全黑。
- Estimate：约 70 分钟。Depends：P2、C4、A1、T1。

**A5a 舞台：目录、收藏、口味** · `android/app-stage` · claude-opus-4-6-thinking
- Files：`app/.../stage/{StageViewModel.kt,Stage+Catalog.kt,Stage+Curation.kt,Stage+Taste.kt}`、对应测试
- Verify：用假 HTTPClient 跑 JVM 测试：冷启动只发一次目录请求；未确认年龄不发请求；续页并入当前项之后。
- Estimate：约 70 分钟。Depends：A1、A4、C4–C7。

**A6 单窗界面** · `android/app-ui` · gemini-4-carbon-high
- Files：`app/.../ui/{StageScreen,Chrome,FavoritesSheet,TasteSheet,ChallengeSheet}.kt`、对应测试
- Changes：垂直分页只渲染当前页和上下各一页，播放器只挂在当前页。
- Verify：Robolectric 的 Compose 测试覆盖划动、静音、心形；视频层禁用词 grep 为空。
- Estimate：约 70 分钟。Depends：A3、A4。

**C9 跨组测试** · `android/core-cross` · claude-sonnet-4-6
- Files：`core/src/test/kotlin/vela/core/cross/**`、`parity/cross.tsv`
- Verify：`python3 -I tools/parity.py --all` 通过。
- Estimate：约 40 分钟。Depends：C4–C7。

**A5b 起播链、侧车、质询** · `android/app-playchain` · claude-opus-4-6-thinking
- Files：`app/.../stage/{Stage+Playback.kt,Sidecar.kt,Challenge.kt}`、对应测试
- Changes：解析起播来源（先 chatvideocontext，再 ajax，再侧车）、自动跳过、暂时离开重试、`ChallengeGate`；侧车用离屏 WebView，闲置 30 秒销毁。
- Verify：JVM 测试，加上 `emulator-run.sh challenge`。
- Estimate：约 80 分钟。Depends：A5a、A3。

**A7 生命周期接线** · `android/app-lifecycle` · claude-opus-4-6-thinking
- Files：`app/.../MainActivity.kt`、`app/.../stage/LifecycleWiring.kt`
- Changes：onStop 时按 `PhoneLifecycle.actions` 执行：作废代次、释放各路、销毁侧车、放弃音频焦点；onStart 时按 `shouldPlay` 恢复。
- Verify：`emulator-run.sh background`：5 秒后请求数为 0、tick 增量 ≤ 1、没有播放线程。
- Estimate：约 50 分钟。Depends：A5b、A6、A2。

**E1 里程碑 A 端到端** · `android/e2e-a` · claude-sonnet-4-6
- Files：`app/src/androidTest/kotlin/vela/app/e2e/SingleWindowE2E.kt`
- Verify：CI 的 emulator 作业全绿；资源 JSON 写进 PERF 草稿。
- Estimate：约 40 分钟。Depends：A7。
- 之后由编排者在本机完成：签名 → 发布 `preview/android/`（tailnet 私有）→ 真站冒烟 → 开 PR-A，按大功能档走 fanout 双路审计加盲审。

**B0 契约扩展**（sonnet，约 15 分钟）→ **B1 双路舞台** `android/dual-stage`（opus，约 80 分钟）
- B1 Files：`Stage+Lanes.kt`、`ThermalMonitor.kt`。
- B1 Changes：接入 LaneSelection、RoomTarget、LaneQuality、分类器降级、LaneSettlement。
- 与 B1 并行：**B2 双窗界面** `android/dual-ui`（carbon-high，约 50 分钟，`DualStageScreen.kt`）。
- 之后：**E2** `android/e2e-b`（sonnet，约 40 分钟，测试同房、焦点出声、档位、热状态注入、解码降级注入、重叠时长）。

**PiP1** `android/pip` · opus · 约 70 分钟
- Files：manifest 的画中画属性、`pip/PipController.kt`、接线到 `LifecycleWiring.kt`。
- Changes：比例夹紧；`RemoteAction` 切换静音；`PiPPolicy` 只放行焦点路；3 秒内没进入画中画就全停。
- 之后：**E3** `android/e2e-c`（sonnet，约 40 分钟，`dumpsys audio` 核对焦点，用 `UiDevice.pressHome`）。

**统一约束**（每个任务包都附上）：
- 只写列出的路径；不改 `gradle/libs.versions.toml` 和依赖；不碰 `App/`、`iOS/`、`Shared/`、`Sources/`。
- 不建 worktree，不切分支、不合并、不变基；只推自己的 `android/<名>` 分支，推完贴出 `git ls-remote origin android/<名>`。
- 提交作者用编排者给的 noreply 地址；不写邮箱、本机路径、密钥。
- 不访问真站点。
- 先写会失败的测试再实现；测试执行数必须大于 0。
- 脚本不能只用 GNU 写法。
- 事实与前提不符时停下来报告，不自行调整范围。

验收一律由编排者在本机拉分支后后台实跑，或以 CI 为准。分支名和推送结果以远端为准，不信执行者自报。

Ready for execution

---

## 编排者裁决（2026-11-18）
- 用户裁决：与 iOS 对齐（单窗→双窗→画中画）；Kotlin 原生重写；签名 APK 经私有 tailnet 链接发给手机；全部由 agy 云端执行，模型由编排者挑选。
- 采纳「取消静音请求 AUDIOFOCUS_GAIN_TRANSIENT」（与 iOS 裁决 2「再静音时让对方恢复」一致）。
- 执行按 worktree-fleet：集成分支 `android/fleet`，各任务推 `android/<名>`，合并门 = `git diff --name-only` 越界检查 + CI + 规格对照；每批合入后再开下一批。
- 本机 JDK/安卓 SDK 安装与真站冒烟、签名放到里程碑 A 收尾时再定。
