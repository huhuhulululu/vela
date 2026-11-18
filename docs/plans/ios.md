# Vela iOS 版实现计划（第 4 版，已加入裁决 5「画面不裁切」）

> 预算估算：约 505 次工具调用，其中本机约 460 次、云端约 45 次（T4–T6）。总数超过 150，已在 PR0 后、T9 后、PR1 后、T15 后、PR2 后各插一个 COMPACT CHECKPOINT。

本版要点：在 PR1 大重构前先做 PR0 真机验证，用实测数据定 iOS 资源预算；共享方式改成「两个 Xcode 目标直接编译共享源码」，SwiftPM 只新增一个中继测试目标。sol 的 11 条和 R3-1 至 R3-6 全部并入正文。裁决 5 落在冻结区块、§2、§4.4、§4.5、T2b、T5、T14、T16、T19 和 S13。

## 冻结区块（批准后 AI 不得自改；要改先 HALT，请用户授权）

**需求边界**
- 新增 iOS 应用目标 `VelaPhone`（iOS 17.0+），只装到用户自己的 iPhone：经 m5p 用开发签名安装，或走 TestFlight 内部测试，不上 App Store。
- 功能对齐 macOS 核心：
  - 四个来源：猜你喜欢、热门、新开播、收藏；
  - 上下滑切房，模糊缩略图垫底；
  - 心形收藏与收藏夹、口味面板、搜索；
  - 默认静音、年龄确认、站点质询走 WKWebView。
- 不做：菜单栏、老板键、窗口拖动、指针轮询、登录、打赏、录制、绕过手段、联盟码、快照模糊。
- 收藏与口味数据只留在本机。

**用户裁决**
1. 竖屏默认单窗，双窗开关的状态要记住。
2. 取消静音时用 `.playback`，会打断其他 App 的声音；再静音时调 `setActive(false, .notifyOthersOnDeactivation)`，让对方恢复。
3. 双窗两路都用最高档，不封顶。保护规则：热状态到 `.serious` 及以上时两路降档，回落到 `.fair` 及以下并持续 60 秒后回升。这是用户的明确选择，流量约翻倍。
4. 不做快照模糊。
5. **画面保持原始比例，不裁切。**
   - iOS 播放层固定用 `videoGravity = .resizeAspect`，禁用 `.resizeAspectFill`。
   - 横向 16:9 画面在竖屏的单窗或双窗窗格里上下留边，留边区露出该房间的模糊缩略图；没有缩略图时用深色渐变，不出现大块纯黑。
   - 竖屏流自然按比例铺满。
   - macOS 共享过来的 `fitFill`、`portraitFit`、`effectiveFill` 在 iOS 上恒为「不裁切」。
   - 窗格的尺寸口径和画质档位估算，一律按画面实际显示的矩形（`videoRect`），不按窗格尺寸。

**预算口径**：iOS 资源预算以 PR0 真机实测为准，不得为了达到预算而封顶画质。网络只考核「实际流量 ÷ 所选档位带宽之和」。

**验收标准**：文末 S1–S13，不可自改。

---

## Overview

分四个 PR 交付：
- **PR0 真机纵向验证**：用实验目标在真机上测清 ATS、两路并发、声音、后台恢复、画面比例和指标导出。
- **PR1 macOS 重构**：中继多源、按目标分文件写平台适配、抽出 `PlaybackLane`。iOS 骨架目标在这一阶段就开始做编译检查。
- **PR2 iOS 单窗**。
- **PR3 竖屏双窗**。

共享方式：两个 Xcode 目标直接编译 `Shared/` 和 VelaCore；SwiftPM 只新增 `VelaRelay` 做回环测试。

## Design Reference

N/A。批准后写入 `docs/plans/ios.md`，ADR 写入 `docs/decisions.md`。

---

## 审计发现处理表

| # | 发现 | 处理 | 落点 |
|---|---|---|---|
| 1 | `LaneReconcile` 只修副路 | 采纳：改为 `LaneSelection` 联合提交两路房间与焦点 | §4.1、T5、T18、S8 |
| 2 | 恢复依赖 `lastPlay` | 采纳：恢复由 Stage 层按 `lane.username` 与「该路应播放」决定 | §3.2、T13、S10 |
| 3 | 共享任务在后台重开监听 | 采纳：`LifecycleEpoch` 在四处校验 | §3.1、T6、T13 |
| 4 | 旧监听回调覆盖新状态 | 采纳：监听代次、连接登记、地址统一由一个函数生成 | §1.3、T7 |
| 5 | 迁移清单不闭合 | 采纳：先用 iOS 骨架收集编译依赖，再按目标分文件适配 | §1.2、T8、T9 |
| 6 | 业务操作没绑定到某一路 | 采纳：引入 `RoomTarget`，`manuallyPaused` 改为每路一份 | §4.2、T10、T18、S9 |
| 7 | 解码降级缺入口 | 采纳：结构化错误加分类器；R3-6 的「10 秒降级」作废 | §4.4、T4、T10、T19 |
| 8 | 真机验证太晚，流量上限与最高档冲突 | 采纳：先做 PR0；网络按比值考核；热状态写明测试条件 | PR0、§6 |
| 9 | 实跑门有空成功 | 采纳：拒绝无效结果；加首帧、并发重叠、资源计数、受控 403 测试 | §6、T3、T7 |
| 10 | 音频没有转换合同 | 采纳：`AudioFocusController` 按序转换，失败时保持静音，后台释放会话 | §3.3、T6、T15、S11 |
| 11 | `DualDwell` 只用一个秒数 | 采纳：结算同时带原始秒、加权秒、焦点秒、结束原因、每路来源 | §4.3、T6、T19 |
| R3-1 至 R3-6 | 编排者补充 | 并入 T7、§3.2、T12、T3/§6、公开方案调研、T2b E1；R3-6 的 10 秒规则被第 7 条替代 | |
| 裁决 5 | 不裁切 | 采纳 | 见上方本版要点 |

---

## 读代码核实的事实

1. **中继只有一个源。**
   - `PlaylistRelay.swift:49-57` 只有一份 `activeID`、`entry`、`loops`、`refreshing`、`audioSuppressed`；`:76` 注册新源时先作废旧源。
   - `:459-468` 的监听回调不核对身份就清零 `port`。
   - `:81` 和 `:412` 各自硬编码 `127.0.0.1`。
   - `:85` 的 `waitUntilListening` 先 `start()` 再检查取消。
2. **PlayerController 有 10 处调用中继**（`:75/119/173/306/313/376/423/458/667/718`，其中 `:718` 的 `isRenewing` 是属性）。另外：
   - `lastPlay` 到 `:104` 才写入；
   - 错误链 `errorChain` 是私有的（`:596`）；
   - 播放项 `.failed` 后立即进入 `itemFailed`（`:614`、`:646`）。
3. **StageModel 依赖 macOS。**
   - 文件导入 `AppKit` 和 `Carbon.HIToolbox`；`:147-148` 有存储属性 `bossKey`、`activity`；`bootstrap()` 里用 `kVK_ANSI_V`（`:321-331`）。
   - 直接调 `NSApp`（`:365/626-629/790`），订阅三个 `NSApplication` 通知（`:242-263`）。
   - `+Favorites.swift:197/216` 用 `NSSavePanel` 和 `NSOpenPanel`。
   - 六个扩展文件都 `import AppKit`。
   - 目录加载用独立 `Task { await reload() }`（`:342/443`），完成后接着起播。
4. **界面依赖。**
   - `ChampagneButton` 定义在 `AgeGateView.swift:51`，`QuietButtonStyle` 定义在 `ChromeControls.swift:4`，两者都调用 `.clickableRegion()`（`ClickableRegion.swift:30`）。
   - `AgeGateView:9/12` 用了 `DragStrip`（`WindowBridge.swift:378`）和 `WindowButtons`（`ChromeControls.swift:85`）。
   - `QuietButtonStyle` 在各面板里被用了 20 多处。
5. **FeedStore 的列表操作都以主路为锚。** `replace`、`setQuery`、`setFilters`、`focus`、`insertDirect`、`step`、`merge*`、`promote*`、`reorder*`、`shouldLoadMore`、`prefetchUsernames` 都以 `currentUsername` 为锚；`setFilters` 会自动换选中的房间（`:206-218`）。
6. **`Presence.engaged`**：`conditionalOpacity` 为假时恒为真（`Presence.swift:27-30`）。
7. **perf-probe.sh 有三处空成功。** 起播超时照样输出 `-1`（`:196`）；idle 被跳过时 `exit 0`（`:206/210`）；`runtime_issues` 只统计一种警告（`:286`）。
8. **停留与学习用的是同一个秒数。** `Taste.recording` 学习和每日统计共用一个 `seconds`（`Taste.swift:109/122`）；`Feedback.grade` 把 3 秒以内一律判为弱落空；`DwellTracker.finish()` 会清空房间（`:51`）；`dwellSource` 是全局只有一份（`StageModel.swift:140`）。
9. **画面铺满逻辑。**
   - `RoomCanvas.swift:97` 把 `model.effectiveFill` 传给 `PlayerLayerView(fill:)`。
   - `effectiveFill` 由 `portraitFit.fill(global: fitFill, current:)` 算出（`StageModel.swift:569`）。
   - `notePortrait` 读 `presentationSize`（`:572-578`）。
   - 垫底缩略图 `SwapBackdrop` 放在播放层下面（`RoomCanvas.swift:95`），没图时显示柔和渐变。所以只要播放层背景透明，留边区自然露出模糊缩略图。

---

## 1. 代码共享

### 1.1 方案选择：C′（简化混合）

| 方案 | 结论 |
|---|---|
| A：只靠 `#if os` | 不选，平台判断会散到各处 |
| B：全部做成 SwiftPM 包 | 不选，要改 macOS 的编译方式，还要给大量成员加 `public` |
| **C′（推荐）** | 两个 Xcode 目标直接编译 `Sources/VelaCore` 和 `Shared/`。SwiftPM 只新增 `VelaRelay`（路径 `Shared/Relay`，只含 `PlaylistRelay.swift`，依赖 VelaCore）。`PlayerController` 和 `SiteSidecar` 放 `Shared/Playback`，暂不进 SwiftPM |

细节：
- `VelaRelay` 的源码和测试整个文件包在 `#if canImport(Network)` 里，Linux 上能编译通过。
- 源码头部写 `#if SWIFT_PACKAGE import VelaCore #endif`。
- 测试头部按 `SWIFT_PACKAGE` 切换 `@testable import`：SwiftPM 下导入 `VelaRelay` 或 `VelaCore`，Xcode 下导入 `VelaPhone`（R3-3、R3-6）。

### 1.2 平台适配：同名符号按目标分文件实现

做法：同一个符号在 `App/*+Mac.swift` 和 `iOS/*+Phone.swift` 各写一份，每份只登记进自己的目标。这样不需要 `#if`，也不需要协议。

- **`PlatformHooks`**：`StageModel` 持有 `let platform = PlatformHooks()`。
  - macOS 版负责热键、`SystemActivity`、三个通知、`appHidden`、`terminate`、`revealMain`、`hide`、`unhide`。
  - iOS 版负责写 §2 里的各个值。
  - 两版都提供 `allowsCrop: Bool`：macOS 为 `true`，iOS 为 `false`。
- **`platformClickRegion()`**：macOS 版就是 `ClickableRegion`，iOS 版原样返回视图。`ChampagneButton` 和 `QuietButtonStyle` 移到 `Shared/Stage/ButtonStyles.swift`。
- **年龄确认页**：共享 `AgeGateContent`；macOS 外壳带 `DragStrip` 和 `WindowButtons`；iOS 外壳另做手机布局。
- **收藏导出导入**：模型只提供 `exportFavoritesData()` 和 `importFavorites(_:)`。macOS 的文件面板放到 `App/StageModel+Mac.swift`，iOS 用 `.fileExporter` 和 `.fileImporter`。`CurationPanel` 通过回调接入。
- **`PulseDotMark`、`SiteWebView`、`SidecarHost`、`PlayerLayerView`**：两个目标各写一份。iOS 版的 `PlayerLayerView` 不接收 `fill` 参数，见 §4.5。
- **工作流程**：T8 先用 iOS 骨架目标收集编译报错，整理成依赖清单写进 PR 描述；T9 按清单拆分。每移动一个文件，两个目标都要重新构建。

### 1.3 中继改动（T7）

- **多源**：用 Core 里新的 `RelaySourceTable`，`feeds`、`loops`、`inflight` 都按源 id 存。`localURL(...audioSuppressed:)` 返回 `(id, url)`。`setRefreshing`、`setAudioSuppressed`、`deactivate` 加 `source:` 参数；`isRenewing(source:)` 从属性改成方法。
- **监听代次**：
  - 每次 `start()` 把代次加一，回调先核对代次再改状态。
  - `stopListener()` 依次做：代次加一 → 取消监听 → 取消所有登记过的连接和应答 `Task` → 清空源表。
- **`allowStart` 开关**：挂起期间 `start()` 和 `waitUntilListening()` 都直接返回 false。
- **本地地址统一生成**：`localBase(port:)`，主机名用常量 `RelayHost.name`。
- **测试注入**：新增 internal 的 `init(session:)`。

## 2. iOS 上的状态值定义

| 值 | 定义 | 由谁写入 |
|---|---|---|
| `pointVisible` | `.active` 或 `.inactive` 时为真，`.background` 时为假 | `PhoneLifecycle` |
| `systemInactive` | 恒为假 | 不写 |
| `screenScale` | `@Environment(\.displayScale)` | `PhoneStageView` |
| `lane.displaySize`、`pointHeight` | 该路 `AVPlayerLayer.videoRect` 的尺寸，即画面实际显示的大小（裁决 5）。还没有画面时，按 16:9 放进窗格估算。`pointHeight` 取焦点路的值 | `PhonePlayerLayer`：在 `layoutSubviews` 和 `presentationSize` 变化时更新 |
| `fitFill`、`portraitFit`、`effectiveFill` | `effectiveFill = platform.allowsCrop && portraitFit.fill(...)`，iOS 上恒为假。iOS 不提供「铺满」开关 | `PlatformHooks+Phone` |
| `Presence` | `conditionalOpacity = false`，`clickThrough = false`，因此 `engaged` 恒为真 | `PlatformHooks+Phone`，不落盘 |
| `restSavesPower` | 恒为假 | 同上 |
| 控制栏 | 单击调 `noteActivity()`，2.4 秒后自动收起 | 沿用现有逻辑 |
| `attentive` | 只在 `.active` 时为真；`.inactive` 时画面照常播放，但停留不计时 | `PhoneLifecycle` |

## 3. 生命周期与音频

### 3.1 统一挂起：`LifecycleEpoch`（Core）

字段是 `suspended` 和 `epoch`。异步工作开始时记下当时的 epoch，以下四处都要校验：
1. 发请求前；
2. 提交结果前（epoch 不一致就丢弃结果）；
3. 注册中继源前；
4. 中继 `start()` 前（通过 `allowStart`）。

覆盖范围：
- 每一路的起播、预取、续期；
- 共享的 `reload`、`catalogRestTask`、`targetedTask`、`siteSearchTask`；
- 收藏查询、上次开播时间、热门标签；
- 缩略图下载；
- 侧车：新增 `suspendAll()`，停止加载、取消挂起中的 continuation、拆掉 webView。

macOS 上不会进入挂起，这些代码在 mac 上是空操作，但仍要过 G-mac。

### 3.2 前后台切换：`PhoneLifecycle.actions`（纯函数）

**进入 `.background` 时按顺序执行：**
1. `gateOff`
2. `epoch.suspend`：取消 §3.1 列出的全部任务
3. `settleDwell`：停留结算，结束原因记 `.background`
4. `flushSavers`
5. `suspendLanes`：每路调 `suspendNow()`，释放播放项、作废中继源；若 `pausedAt` 为空就补写，并置 `allowed = false`（R3-2）
6. `audio.deactivate`
7. `stopListener`
8. 记下 `resumeIntent`：后台前正在做的 reload 或搜索

**从 `.background` 回到 `.active` 时**（只看 `wasSuspended`，中间经过 `.inactive` 也一样）：
1. `epoch.resume`，允许中继重新启动；
2. 重做 `resumeIntent`；
3. 对每一路判断 `shouldPlay`，条件是：`lane.username != nil`、`!lane.manuallyPaused`、`lane.visible`、`!lane.failed`。满足则 `requestPlay(lane:)` 重新解析地址，不读 `lastPlay`。

各种状态回前台后的表现：
- 首次解析中、切房解析中、刷新中的路：重新起播；
- 失败态：保留失败提示，不自动重播；
- 手动暂停的路：保持暂停，只显示垫底缩略图和暂停图标；
- 隐藏的路：保持已释放。

### 3.3 音频：Core 的 `AudioTransition.plan` 与 `AudioSessionPolicy`，iOS 由唯一的 `AudioFocusController` 执行

转换步骤固定为：
1. 静音旧的出声路；
2. `setCategory`：静音时用 `.ambient`，出声时用 `.playback` 加 `.moviePlayback`；
3. `setActive`；
4. 第 2、3 步都成功后，才给目标路取消静音。

规则：
- **第 2 或第 3 步失败**：所有路保持静音，运行时 `muted = true`（不写进设置），提示「暂时无法出声」。
- **再静音、进后台、最后一路停播**：调 `setActive(false, .notifyOthersOnDeactivation)`。
- **打断开始**：所有路静音（只改运行时状态）。`wasSuspended == true` 的打断开始忽略。打断结束后不自动恢复出声。
- **拔耳机**（`.oldDeviceUnavailable`）：转为静音，不暂停（写进 ADR）。
- 路由变化通知切回主线程处理。
- **非焦点路**：一律 `isMuted`，并按 `MuteAudioPolicy` 拿掉音轨。
- **入口收口**：`PlayerController.setVolume` 只能由控制器调用。

其他：
- `Application Support/Vela` 目录设置 `isExcludedFromBackup`，保证数据不进 iCloud 备份。
- 收到内存警告时清空缩略图缓存和 `PreparedCache`。

## 4. 双窗设计

### 4.1 联合选房：`LaneSelection`（Core）

**输入**：
- 变更前的快照：`rooms`、`primary`、`secondary`、`focus`，以及各路的移动方向；
- 变更后的可见列表；
- 意图：`.filter`、`.reload`、`.searchInstall`、`.searchExit`、`.merge`、`.reveal(target)`、`.move(lane, delta)`、`.blankResume`。

**输出**：新的 `{primary, secondary, focus}`，以及每一路的结算原因。

**执行方式**：`updateFeed` 先在副本上执行变更，再调用 `LaneSelection`，然后用 `FeedStore.select(_:)` 写回主路、同时写回副路，最后统一结算停留和起播。单窗时输出必须与 `FeedStore` 自己的选择完全一致（macOS 回归测试保证）。

**规则**：
- **两路永不重复**：主路被动落到副路的房间时，主路沿自己的方向另找一个。
- **列表只剩一个房间**：焦点路保留它，另一路显示「没有更多房间」。
- **列表为空**：两路都空；续页恢复时两路重新选房。
- **「不想看」或取消收藏导致副路房间消失**：副路沿原方向找下一个，找不到就反方向找，再没有就置空。
- **整表重载、全站搜索装入或退出**：副路取新列表中主路之后的第一个房间。
- **合并、重排、前置**：房间还在就不动。
- **`.reveal(target)` 的目标正在另一路播放**：只切焦点，两路游标都不动。
- **自动跳过**：`move(lane:)` 按该路自己的 `skips` 方向。

`FeedStore` 新增四个接口：`select(_:)`、`step(_:skipping:)`、`shouldLoadMore(at:threshold:blankPages:)`、`promoteAfter(anchor:_:)`。原有方法改为转调这些新接口，macOS 行为不变。

### 4.2 每路状态与目标路操作

**`PlaybackLane` 自己持有的状态**：
- 播放与画面：`player`、`playback`、`readyName`、`playingName`、`playerNote`、`playbackNote`、`portraitFit`；
- 起播流程：`playGeneration`、`playTask`、`skipTask`、`awayTask`、`awayRetry`、`awayTimer`、`skips`；
- 停留与学习：`dwell`、`dwellSource`、`relatedTask`；
- 画质：`quality`、`qualityTask`、`qualitySignature`、`currentMaster`、`currentPlayable`、`currentCap`；
- 身份与状态：`username`、`manuallyPaused`、`visible`、`state`（`.idle`、`.playing`、`.failed`、`.degraded`、`.empty`）；
- 尺寸：`paneSize`、`displaySize`。

**两路共享**：`feed`、`taste`、`curation`、`watchLedger`、`PreparedCache`、侧车、`sidecarUntil`、`challengeGate`、`EndpointBackoffBox`。toast 和收藏提示跟随焦点路。

**`RoomTarget(laneID, username, generation)`**：在点击时就固定下来。收藏、屏蔽、不感兴趣、重试、暂停都接收 `RoomTarget`，异步完成时核对该路的 `username` 和 generation，对不上就放弃。来源、筛选、搜索、口味标签是全局操作，不绑定某一路。macOS 上 `RoomTarget` 恒为主路。

**每路的播放闸门** = 全局闸门 ∧ `!lane.manuallyPaused` ∧ `lane.visible`。

### 4.3 停留结算：`LaneSettlement`（Core）

结算记录的字段：`lane`、`username`、`source`（该路自己的 `dwellSource`）、`rawSeconds`、`weightedSeconds`（焦点段权重 1.0，非焦点段 0.5）、`focusedSeconds`、`endReason`（`.swipe`、`.autoSkip`、`.block`、`.background`、`.dualOff`、`.sourceChange`、`.failed`、`.notInterested`）、`userInitiated`。

规则：
- **学习**：只有 `rawSeconds ≥ 3` 才用 `weightedSeconds` 走 `Feedback.grade`。弱落空只在 `rawSeconds < 3` 且结束原因是 `.swipe` 时记。
- **每日命中率**：按 `focusedSeconds` 统计。
- **WatchLedger**：记 `weightedSeconds`。
- **`.background` 和 `.dualOff` 结束的停留**不产生落空；回前台后开一段新的停留。
- **Taste 接口**：新增 `recording(learnSeconds:daily:...)` 重载，原接口保持不变。
- **热门关联**：只看焦点路。

### 4.4 画质与降级

**`LaneQuality`**：
- 正常时单窗和双窗都选最高档（`.infinity`）。
- 热状态到 `.serious` 时，降到 `PowerPolicy.peakBitrate(pixelHeight: lane.displaySize.height × screenScale)` 对应的档位，下限不低于 896k。按画面实际显示高度算，不按窗格高度（裁决 5）。
- 举例：iPhone 竖屏双窗里的 16:9 画面约 663px 高，降到 1.696M 档。
- 热状态回落到 `.fair` 并持续 60 秒后回升。

**`PlaybackFailureClassifier`**（Core）：
- 输入：错误链 `[(domain, code)]`，以及上下文（是否双窗、是否副路、另一路是否在播、该路是否播放过）。
- 输出：`.roomRetry`、`.resourceDegrade`、`.fatal` 三选一。
- 白名单只收 E3 实测到的码（候选 `decoderTemporarilyUnavailable`）。`decodeFailed` 和 `decoderNotFound` 默认按房间失败处理。
- `PlayerController` 在进入 `itemFailed` 之前先调 `failureFilter(info)`；判为 `.resourceDegrade` 时不消耗重试预算。

**降级动作**：释放副路的播放项和中继源，`state` 置为 `.degraded`，显示「双窗暂不可用」和重试按钮。不污染停留记录，不自动跳过。恢复的触发条件：用户点重试，或回前台时自动尝试一次。

### 4.5 画面比例（裁决 5）

- **播放层**：`iOS/PhonePlayerLayer.swift` 固定 `playerLayer.videoGravity = .resizeAspect`，图层和视图背景透明。没有 `fill` 参数，iOS 代码里不出现 `.resizeAspectFill`（由 grep 检查）。
- **垫底**：沿用共享的 `SwapBackdrop`，即当前房间的模糊缩略图，没有图时用深色渐变，铺满整个窗格、放在播放层下面。留边区因此露出模糊缩略图，不是纯黑。竖屏流按比例自然铺满；在双窗半屏里会左右留边，同样露出垫底。
- **尺寸**：`PhonePlayerLayer` 把 `videoRect` 回报为 `lane.displaySize`，用于 §4.4 的降档和日志。
- **比例校验**：新增纯函数 `DisplayFit`（Core）：
  - `DisplayFit.fitted(presentation:, bounds:) -> CGRect`；
  - `DisplayFit.isUncropped(videoRect:, bounds:, presentation:)`：`videoRect` 完全落在 `bounds` 内，且宽高比与 `presentationSize` 相差不超过 1%。
- **日志**：每路画面首次就绪和尺寸变化时，打一条 Release 也保留的日志：`display-check lane=<id> gravity=<值> presentation=WxH bounds=WxH videoRect=WxH uncropped=<bool>`，同时打 signpost。
- **macOS 不受影响**：`allowsCrop` 为 true，`fitFill` 和 `portraitFit` 的现有行为不变。

## 5. 签名与装机步骤（按顺序；标「手动」的步骤没有 ssh 替代）

1. **手动，需要动手机**：用线把 iPhone 接到 m5p，在手机上点「信任」。要先和用户约好时段。
2. **手动，需要动手机**：在手机的「设置 → 隐私与安全性 → 开发者模式」里打开开关，手机会重启。
3. 签名凭据二选一：
   - App Store Connect API key（需要 Admin 角色），文件权限 0600，放在 `~/.appstoreconnect/`，不入库；
   - **手动，GUI**：在 m5p 的 Xcode 里登录一次开发者账号。
4. 通过 ssh 构建前先执行 `security unlock-keychain ~/Library/Keychains/login.keychain-db`；这条在 ssh 下不行的话，到 m5p 本机终端执行。
5. 构建命令带 `-allowProvisioningUpdates`；用 API key 时再加 `-authenticationKeyPath`、`-authenticationKeyID`、`-authenticationKeyIssuerID`。

Bundle ID：
- 日常版：`com.vela.stage.ios`
- 性能测试版：`.perf`，显示名「Vela Perf」，数据容器独立，不影响日常版
- 实验版：`.spike`

如果这些前缀已被别人注册，改用自有域名的前缀。

## 6. 实跑门

### G-mac：macOS 实跑门（在 m424 后台跑，Release，静音）

1. T3 先在 main 上跑出基线，写进 `docs/PERF.md`（R3-4）。
2. 通过阈值：

   | 指标 | 阈值 |
   |---|---|
   | CPU | `cpu_app = cpu_avg − vtdecoder_cpu_avg ≤ 基线 cpu_app × 1.15 + 0.3` 个百分点；总 `cpu_avg` 只打印不判，缺 `vtdecoder_cpu_avg` 即不合格（2026-11-17 编排者裁决：各房间码率不同，硬解波动会误判退化） |
   | `footprint` | ≤ 基线 + 5 MB |
   | `wakeups` | ≤ 基线 × 1.2 |
   | `app_size` | ≤ 基线 + 0.3 MB |
   | 起播时间 | 大于 0，且 ≤ 基线中位数 + 1.0 s（2026-11-17 编排者裁决采用 A：同一份代码单轮起播波动约 1.1 s，基线取 5 轮中位数，见 `docs/PERF.md`） |
   | idle 场景 | `cpu ≤ 0.1%`，`wakeups ≤ 2/s`，网络为 0 |
   | `runtime_issues` | = 0 |

3. 以下情况一律判为不合格，脚本必须非 0 退出：起播时间为 -1、结果带 `skipped`、`samples = 0`、任一指标缺失或为 NaN、测试期间进程退出。
4. `runtime_issues` 除了现有那一种警告，还要统计 `AttributeGraph: cycle`、`Modifying state during view update`，以及 `com.vela.stage` 的 fault 级日志。
5. 判定命令：`python3 -I scripts/perf_gate.py <基线> <结果>`。实测一律经 `ssh localhost` 回环运行 `perf-probe.sh`（工具 shell 被降优先级，数字失真；脚本会警告）。

### G-ios：iOS 实跑门（在 m5p 跑，Perf 构建，静音，记录设备型号）

- **阈值来源**：全部取自 PR0 的实测。
  - CPU ≤ Spike 实测 × 1.2；
  - footprint ≤ Spike 实测 + 15 MB；
  - 起播时间 ≤ Spike 实测 + 1 s；
  - 网络：`net_in ÷ 所选档位带宽之和 ≤ 1.15`。
- **双窗额外要求**：
  - 两路都打出 `first-frame`，以 `isReadyForDisplay` 为准；
  - 两路同时出画面的重叠时长 ≥ 测试窗口的 90%；
  - 每次换房后 `relay sources=N items=M`，N 和 M 都不超过当前路数。
- **画面比例**（裁决 5）：对横屏流，单窗和双窗下所有 `display-check` 行都满足 `gravity=resizeAspect` 且 `uncropped=true`。
- **热状态**：测试条件为 Wi-Fi、不充电、起始热状态 nominal、亮度固定、连续 10 分钟。要求不出现 `.critical`；到 `.serious` 时正确降档，之后能回升。
- **后台**：用 `devicectl ... launch com.apple.Preferences` 把 Vela 挤到后台。5 秒内 Vela 的网络和 CPU 都归零，并依次出现 `lane suspend`、`audio deactivated`、`relay listener stopped` 三个 signpost。
- **续期独立性**：用回环测试里的受控 403 验证；真实直播只用来测 30 分钟长期稳定性。

---

## Tasks

**云端任务包统一约束**：只改列出的文件；不碰 `App/`、`iOS/` 和 pbxproj；不建 worktree，不切分支、不合并、不变基；推送后贴出 `git ls-remote`；脚本不得只用 GNU 写法。验收在本机拉分支后后台实跑。派发前先确认本仓在 cloud-lane 的允许清单里，不在就改为本机执行。

### PR0 真机纵向验证（分支 `spike/ios`）

只把脚本、文档和 ADR 合进 main；实验目标的代码不合并，打 tag `spike-ios-v1` 留档。

**T1：m5p 工具链盘点**（本机，只读）
- Changes：依次执行：
  - `xcodebuild -version`
  - `xcodebuild -showsdks`
  - `xcrun simctl list runtimes`
  - `security find-identity -v -p codesigning`
  - `xcrun devicectl list devices`
- Verify：有任意 `iphoneos*` 和 `iphonesimulator*` SDK（记下版本号）；至少一个模拟器运行时；记录当前证书和设备状态。
- Estimate：约 10 分钟。Depends：无。

**T2：签名与设备准备**（用户手动）
- Changes：把 §5 第 1–4 步交给用户，约好 m5p 的使用时段。
- Verify：重跑 T1，能看到 Apple Development 证书或可用的 API key，并且有已配对的 iPhone。
- Estimate：约 5 分钟，加上用户操作时间。Depends：T1。

**T2a：Spike 实验目标**（本机）
- Files：
  - `project.pbxproj`：新增目标 `VelaSpike`，`SDKROOT=iphoneos`，`SUPPORTED_PLATFORMS="iphoneos iphonesimulator"`，最低 iOS 17.0，Bundle ID 用 `.spike`；
  - `iOS/Spike/`：复制 `PlaylistRelay` 并改成最小多源版、复制 `PlayerController`、写 `SpikeView`（上下两个播放层，固定 `.resizeAspect`，模糊缩略图垫底，带 `display-check` 日志；按钮：切焦点、静音、开第三路、后台测试）；
  - 目标直接编译 VelaCore；Info.plist 只放 `NSAllowsLocalNetworking`。
- Verify：`xcodebuild -scheme VelaSpike -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build` 成功；macOS 构建不受影响。
- Estimate：约 60 分钟。Depends：T1。

**T2b：真机实验 E1–E7**（本机，在 m5p 上）
- Files：`scripts/perf-probe-ios.sh`。流程：xcodebuild → `devicectl install` 和 `launch` → `xctrace record --template 'Activity Monitor'`（必要时加 Network 模板）→ 导出成一行 JSON，并解析出 `display-check`。无样本或进程退出时脚本非 0 退出。
- Changes：

  | 实验 | 内容 |
  |---|---|
  | E1 | 回环 HLS 起播（ATS）。不通时按顺序兜底：先改 `localhost`（同时绑 `127.0.0.1` 和 `::1`），再加 `NSExceptionDomains` 的 `127.0.0.1`；用 `ForMedia` 之前必须先请用户授权 |
  | E2 | 两路最高档同时播 10 分钟，记录 CPU、footprint、热状态、流量与档位带宽的比值 |
  | E3 | 开第三路，记下解码器报错的 domain 和 code |
  | E4 | 在音乐 App 播放时测取消静音、再静音、进后台 |
  | E5 | 后台再返回，看监听恢复和起播耗时 |
  | E6 | 核对 xctrace 导出的字段是否够用 |
  | E7 | 横屏流在竖屏单窗、双窗下的 `display-check` 记录，并请用户在手机上截图确认没有裁切 |

- Verify：每个实验都有原始数据和结论；构造一个强制无样本的负样本，脚本确实失败。
- Estimate：约 100 分钟。Depends：T2a、T2。

**T2c：PR0 收尾**（本机）
- Files：`docs/PERF.md` 新增「iOS Spike」一节和 G-ios 阈值；`docs/decisions.md` 新增 ADR-i0，记录 ATS 方案、解码器错误码、预算口径、`resizeAspect` 实测结果。
- Verify：只合入这两份文档和 `perf-probe-ios.sh`；走小改动的盲审。
- Estimate：约 20 分钟。Depends：T2b。

> ⏸ COMPACT CHECKPOINT

### PR1 macOS 重构

**T3：修好实跑门，测 main 基线**（本机）
- Files：`scripts/perf-probe.sh`；新增 `scripts/perf_gate.py`；`docs/PERF.md`。
- Changes：按 §6 G-mac 的第 3、4 条修改。
- Verify：
  - 负样本：`PLAY_TIMEOUT=1` 跑一次，必须非 0 退出；
  - 删掉结果里的 `samples` 字段，`perf_gate.py` 必须判为不合格；
  - main 的基线已写入 `docs/PERF.md`。
- Estimate：约 40 分钟。Depends：T2c。

**T4：Core 纯逻辑之一：`RelaySourceTable`、`RelayTrack`、`PlaybackFailureClassifier`**（云端）
- Tests：
  - 两个源的标志互相独立；
  - 第三个源注册时淘汰最旧的；
  - `removeAll` 后状态清空；
  - 损坏码流的错误判为 `.roomRetry`；
  - 先写会失败的测试（RED），再实现。
- Verify：Linux 和 macOS 上 `swift test` 全绿，执行的测试数大于 0。
- Estimate：约 30 分钟。Depends：T2c。

**T5：Core 纯逻辑之二：`LaneSelection`、`LaneCursor`、`RoomTarget`、`DisplayFit`、FeedStore 新接口**（云端）
- Tests：
  - 选房：屏蔽主路、取消主路收藏、提交本地搜索、列表缩成一个、空列表后续页恢复、reveal 的目标在另一路、全站搜索装入和退出、整表重载；
  - 单窗时选房结果与 FeedStore 自身一致；
  - `DisplayFit`：16:9 画面放进 393×852 和 393×410 两种窗格时 `uncropped` 为真；用 aspectFill 尺寸构造的 `videoRect` 判为假；
  - 原有的 `FeedReorderTests`、`FeedCacheTests`、`FeedRankingTests`、`StageFlowTests` 等一行不改、全部通过。
- Verify：`swift test` 全绿。
- Estimate：约 55 分钟。Depends：无。

**T6：Core 纯逻辑之三：`LifecycleEpoch`、`PhoneLifecycle`、`AudioTransition`、`AudioSessionPolicy`、`LaneQuality`、`LaneSettlement`、Taste 新重载**（云端）
- Tests：
  - 进后台的动作顺序：`gateOff` 在 `suspendLanes` 之前，`audio.deactivate` 在 `stopListener` 之前；
  - 回前台的各种状态矩阵；
  - 音频会话失败时保持静音；
  - 忽略 `wasSuspended == true` 的打断开始；
  - 热状态迟滞：第 59 秒不回升，第 60 秒回升；
  - `.serious` 时按 `displaySize`（663px）降到 1.696M 档；
  - 停留结算：后台结束的 5 秒不算落空；非焦点 40 秒不进每日统计；两路来源不串。
- Verify：`swift test` 全绿。
- Estimate：约 50 分钟。Depends：无。

**T7：`VelaRelay` 与多源中继**（本机）
- Files：
  - `Package.swift`：加 `.iOS(.v17)`、`VelaRelay`、`VelaRelayTests`；
  - `git mv App/PlaylistRelay.swift Shared/Relay/`；
  - `App/PlayerController.swift`：10 处调用加 `source:`，新增 `relaySource`，`start()` 先作废自己的旧源再注册（R3-1）；
  - `StageModel+Playback.swift:134`；
  - `project.pbxproj`；
  - 新增 `Tests/VelaRelayTests/RelayMultiSourceTests.swift`。
- Changes：按 §1.3 实现。
- Tests（真实回环端口 + `URLProtocol` 假上游）：
  - 两个源并发都返回 200；
  - 作废 A 不影响 B；
  - 受控 403 时只续期 A；
  - B 停掉音频刷新不影响 A；
  - `stopListener` 后旧地址失效；
  - stop 还没完成时重启，旧回调清不掉新端口；
  - 挂起期间 `start()` 返回 false。
- Verify：
  - macOS 上 `swift test` 全绿，Linux 上能编译；
  - macOS 构建：`xcodebuild -project Vela.xcodeproj -scheme Vela -destination 'platform=macOS' -configuration Debug CODE_SIGNING_ALLOWED=NO build` 成功；
  - `grep -n 'PlaylistRelay.shared' App/PlayerController.swift | grep -v 'source' | grep -v '\.start()'` 输出为空；
  - G-mac 合格。
- Estimate：约 70 分钟。Depends：T3、T4。

**T8：iOS 骨架目标与依赖清单**（本机）
- Files：
  - `project.pbxproj`：新增 `VelaPhone` 目标，目标级设置 `SDKROOT=iphoneos`、`SUPPORTED_PLATFORMS="iphoneos iphonesimulator"`、`TARGETED_DEVICE_FAMILY=1`、`IPHONEOS_DEPLOYMENT_TARGET=17.0`；先只编译 VelaCore、`Shared/Relay` 和一个占位入口；
  - 共享 scheme `VelaPhone.xcscheme`（若 `Vela.xcscheme` 还没共享，一并共享）。
- Changes：把计划要共享的文件临时加入 iOS 目标，收集编译报错，整理成依赖清单写进 PR 描述，然后撤回临时改动。
- Verify：
  - 模拟器构建成功；
  - 依赖清单至少覆盖「事实」第 3、4、9 条；
  - macOS 构建成功。
- Estimate：约 30 分钟。Depends：T7。

**T9：平台适配与文件迁移**（本机）
- Files：
  - 新增 `App/PlatformHooks+Mac.swift`（含 `allowsCrop = true`）、`App/StageModel+Mac.swift`、`iOS/PlatformHooks+Phone.swift`（占位，`allowsCrop = false`）；
  - 新增 `Shared/Stage/ButtonStyles.swift`、`AgeGateContent.swift`；
  - 适配文件 `platformClickRegion`；
  - 按依赖清单用 `git mv` 把已经清理干净的文件移进 `Shared/`；
  - `StageModel.effectiveFill` 改为 `platform.allowsCrop && ...`；
  - `SiteSidecar` 改为注入 `beforeLoad` 和 `SidecarHost`；
  - `project.pbxproj`。
- Verify：
  - `grep -rlE 'import (AppKit|Carbon)|NSApp\b|NSImage|NSView\b|NSSavePanel|NSOpenPanel' Shared/` 输出为空；
  - VelaPhone 模拟器构建成功（此时已编译全部 `Shared/`）；
  - macOS 构建成功；
  - G-mac 合格；
  - `log show --last 5m --predicate 'eventMessage CONTAINS "boss key unavailable"'` 计数为 0；
  - macOS 上按 F 切换铺满的行为保持不变：`grep -n 'allowsCrop' App/PlatformHooks+Mac.swift` 显示为 `true`。
- Estimate：约 90 分钟。Depends：T8。

> ⏸ COMPACT CHECKPOINT

**T10：抽出 PlaybackLane**（本机）
- Files：
  - 新增 `Shared/Stage/PlaybackLane.swift`；
  - `Shared/Stage/StageModel*.swift`、`App/StageView.swift`；
  - `Shared/Playback/PlayerController.swift`：新增 `failureFilter`、结构化错误信息、Release 级 `first-frame`、`suspendNow()`；
  - `project.pbxproj`。
- Changes：按 §4.2 实现；`updateFeed` 接入 `LaneSelection`，此时副路为 nil。
- Verify：
  - `grep -nE '(^|[^.])\b(playGeneration|playTask|skipTask|currentCap|qualitySignature|dwellSource)\b' Shared/Stage/StageModel*.swift` 输出为空；
  - 两个目标都构建成功，`swift test` 全绿；
  - G-mac 合格，且 `first-frame` 有值。
- Estimate：约 100 分钟。Depends：T9、T5、T6。

**T11：PR1 收尾**（本机）
- Files：
  - `docs/ARCHITECTURE.md`：模块表、依赖图、5.1 和 5.2 节、第 9 节；
  - ADR-a：方案 C′ 与按目标分文件的平台适配；
  - ADR-b：两路共享退避和侧车冷却，一路被限流会连带另一路，接受这个代价，理由是同一站点、同一出口 IP；`PreparedCache` 只在降档前后失配，属于预期。
- Verify：
  - `git diff origin/main...HEAD --name-only` 列出的结构改动在文档里都有对应；
  - fanout 审计和盲审通过；
  - CI 全绿后合入 main。
- Estimate：约 30 分钟。Depends：T10。

> ⏸ COMPACT CHECKPOINT

### PR2 iOS 单窗

**T12：VelaPhone 正式配置**（本机）
- Files：
  - `project.pbxproj`；
  - `iOS/Info.plist`：ATS 按 ADR-i0 配置；不声明 `UIBackgroundModes`；支持竖屏和左右横屏；
  - 新增 `VelaPhoneTests`：`TEST_HOST = $(BUILT_PRODUCTS_DIR)/VelaPhone.app/VelaPhone`，`BUNDLE_LOADER = $(TEST_HOST)`；
  - 新增 Perf 配置：macOS 的 Perf 与 Release 相同；iOS 的 Perf 在 Release 基础上加编译条件 `PERF_PROBE`，Bundle ID 用 `.perf`，自动签名，团队 ID 由本地配置提供。
- Verify：`xcodebuild test -scheme VelaPhone -destination 'platform=iOS Simulator,id=<UDID>'` 执行的测试数大于 0、失败为 0；macOS 构建成功。
- Estimate：约 40 分钟。Depends：T11。

**T13：统一挂起与由 Stage 层恢复**（本机）
- Files：`Shared/Stage/StageModel*.swift`；新增 `LifecycleWiring.swift`；`SiteSidecar` 的 `suspendAll`；缩略图缓存；`PlaylistRelay` 的 `allowStart`；`project.pbxproj`。
- Changes：按 §3.1、§3.2 实现。
- Verify：
  - 模拟器状态矩阵：分别在目录加载、搜索、收藏查询、侧车加载、首次解析、切房解析、刷新中、失败态、手动暂停时切到后台，后台期间不出现 relay 和 stage 的请求日志；回前台后表现符合 §3.2；
  - 两个目标都构建成功；
  - G-mac 合格。
- Estimate：约 70 分钟。Depends：T12。

**T14：iOS 单窗视图**（本机）
- Files：
  - `iOS/PhoneStageView.swift`；
  - `LanePager.swift`：自定义拖动分页，只渲染当前页和上下各一页；
  - `PhonePlayerLayer.swift`：`layerClass = AVPlayerLayer`，固定 `.resizeAspect`，背景透明，回报 `videoRect`，输出 `display-check`；
  - `PhoneChrome.swift`：不提供「铺满」开关；
  - `AgeGate+Phone.swift`、`PulseDot+Phone.swift`、`SiteWebView+Phone.swift`；
  - 收藏的导出导入入口；
  - `project.pbxproj`。
- Verify：
  - 模拟器构建成功；
  - `simctl` 启动后，`xcrun simctl spawn <UDID> log show --last 2m --predicate 'subsystem=="com.vela.stage"'` 能看到 `first-frame`，以及 `display-check ... gravity=resizeAspect ... uncropped=true`；
  - `grep -rn 'resizeAspectFill' iOS/` 输出为空。
- Estimate：约 85 分钟。Depends：T13。

**T15：音频控制器、备份排除、Perf 入口**（本机）
- Files：新增 `iOS/AudioFocusController.swift`、`iOS/PhoneLifecycle.swift`；`project.pbxproj`。
- Changes：按 §3.3 实现；`--perf-autoplay` 入口用 `#if PERF_PROBE` 包住，只在内存里视为已确认年龄并强制静音，不写设置。
- Verify：
  - 模拟器切后台，日志依次出现 `lane suspend`、`audio deactivated`、`relay listener stopped`；
  - 备份排除标志可读回；
  - 安全门：Release 配置的编译条件里没有 `PERF_PROBE`；对 `-sdk iphonesimulator -configuration Release CODE_SIGNING_ALLOWED=NO` 的产物执行 `strings <app>/VelaPhone | grep -c perf-autoplay`，结果为 0；
  - G-mac 合格。
- Estimate：约 60 分钟。Depends：T14。

> ⏸ COMPACT CHECKPOINT

**T16：真机单窗验收**（本机，在 m5p 上）
- Verify：
  - G-ios 的单窗和后台两项合格；
  - 在真机上抽 4 种状态跑 §3.2 的恢复矩阵；
  - 复测 E4 的音频场景；
  - 对真机 Release 产物再做一次 `strings` 检查；
  - 日常版和 Perf 版可以同时安装；
  - **画面比例**：横屏流在竖屏单窗下的 `display-check` 全部为 `uncropped=true`；请用户在手机上截图确认，Claude 不能自己截图，图从 m5p 取回或由用户直接发来。
- Estimate：约 60 分钟。Depends：T15、T2。

**T17：PR2 收尾**（本机）
- Files：`docs/ARCHITECTURE.md` 新增 iOS 一节（含 §4.5 画面比例）；`docs/PERF.md`；ADR 记录拔耳机转静音、iOS 不裁切；`.github/workflows/macos.yml` 新增模拟器构建 job。
- Verify：CI 两个 job 都绿；审计和盲审通过；合入 main。
- Estimate：约 30 分钟。Depends：T16。

> ⏸ COMPACT CHECKPOINT

### PR3 双窗

**T18：接入第二路**（本机）
- Files：新增 `Shared/Stage/StageModel+Lanes.swift`；`SettingsStore` 新增 `phoneDual`（默认 false）；`iOS/PhoneStageView.swift`（两个窗格等高，每格都用 `.resizeAspect` 加模糊缩略图垫底）；`project.pbxproj`。
- Changes：
  - 副路接入 `LaneSelection`；
  - 界面操作都带 `RoomTarget`；
  - 切焦点走 `AudioFocusController`；
  - 关闭双窗或转横屏时，释放非焦点路；回到竖屏后用 `requestPlay(lane:)` 恢复。
- Tests：S8、S9 列出的场景。
- Verify：模拟器测试全绿；两个目标都构建成功；G-mac 合格。
- Estimate：约 80 分钟。Depends：T17。

**T19：双窗的停留、热降档、解码降级与画面校验**（本机）
- Files：`Shared/Stage/StageModel+Dwell.swift`、`PlaybackLane.swift`、`iOS/PhoneLifecycle.swift`。
- Changes：接入 `LaneSettlement`、`LaneQuality`（按 `displaySize`）和 `PlaybackFailureClassifier`。
- Verify：
  - `swift test` 全绿，G-mac 合格；
  - G-ios 双窗合格：首帧、重叠时长、资源计数、流量比值、热降档后回升；
  - 用损坏码流的测试样本，确认判为房间失败，而不是「双窗不可用」；
  - **画面比例**：横屏流在双窗两个窗格里的 `display-check` 全部为 `uncropped=true`，并请用户截图确认。
- Estimate：约 75 分钟。Depends：T18。

**T20：PR3 收尾**（本机）
- Files：`docs/ARCHITECTURE.md`；ADR-c 记录双窗停留口径和裁决 3 的流量取舍；`docs/PERF.md`。
- Verify：审计和盲审通过，合入 main。
- Estimate：约 25 分钟。Depends：T19。

---

## 公开方案调研

出处已由编排者在 2026-11-17 联网核对过 Apple 原文（R3-5）。最后一行是本版新增，未联网核实。

| 出处 | 借什么 | 不借什么 |
|---|---|---|
| AVAudioSession 的 `.ambient`、`.playback`、`setActive(_:options:)` | `.ambient` 与其他声音混音，受静音开关影响；`.playback` 默认不混音、会打断别人、不受静音开关影响；`.notifyOthersOnDeactivation` 让对方恢复 | 后台音频、Now Playing、远程控制 |
| 音频打断与路由变化的文档 | `shouldResume` 只是提示；`wasSuspended` 的打断开始要忽略；路由通知不在主线程投递 | 拔耳机时暂停（本计划改为转静音） |
| ATS 的 `NSAllowsLocalNetworking`；iOS 17 起不放行 IP 字面量 | 只放开本地网络，兜底顺序见 E1 | `NSAllowsArbitraryLoads`；`ForMedia` 需先经用户授权 |
| Apple 工程师论坛：回环地址不受本地网络隐私限制 | 不加 `NSLocalNetworkUsageDescription` | 是否成立以 E1 实测为准 |
| TN2277：App 挂起后监听套接字会被回收 | 进后台关监听并清源，回前台重建 | — |
| `AVError.decoderTemporarilyUnavailable`；官方没有给出同时可用的解码器上限 | 以 E3 实测为准；非焦点路要释放，不能只暂停 | 播放器池预加载 |
| AVPlayerLayer 的 `videoGravity`（`.resizeAspect`）与 `videoRect`（本版新增，未联网核实） | `.resizeAspect` 保持比例并在图层内留边；`videoRect` 给出画面实际显示的矩形，用于尺寸口径和不裁切校验 | `.resizeAspectFill` |

## Risks & Mitigations

- **Spike 依赖用户先完成手动步骤** → T2 排在最前；手动步骤没完成，PR1 不开工（S3）。
- **ATS 拦截回环地址** → 按 E1 的顺序兜底，结论写进 ADR-i0。
- **双窗最高档资源超出预算** → 预算本身取自实测，有热降档和迟滞；超出预算时在 PR0 结束时就交给用户取舍。
- **macOS 被改坏** → 每个触及共享代码的任务都过 G-mac，而且门已修复空成功并验证过负样本。前例：PR #2 测试通过，运行时却死循环到 1.47 GB。
- **后台竞态** → `LifecycleEpoch` 加监听代次，用状态矩阵验收。
- **解码错误被误判为资源不足** → 白名单只收实测到的码，再用损坏码流做反向验证。
- **画面被裁切** → iOS 用 `allowsCrop=false` 加固定 `.resizeAspect`；grep 禁止 `resizeAspectFill`；有 `DisplayFit` 单测、`display-check` 日志，并由用户截图确认。
- **`videoRect` 在首帧前为零** → 首帧前按 16:9 放进窗格估算，首帧后改用实测值。
- **ssh 下钥匙串被锁** → 先 `unlock-keychain`，不行就在 m5p 本机终端执行。
- **大陆网络** → 在真机上补测起播时间和续期情况。
- **云端只能跑 Linux** → 只把纯逻辑的 T4–T6 派云端。

## Success Criteria

- [ ] **S1**：`swift test` 在 macOS 全绿、Linux 能编译；T4–T6 的纯逻辑都有测试，执行的测试数大于 0。
- [ ] **S2**：PR1、PR2、PR3 都过 G-mac，并且证明门会拒绝负起播、跳过、无样本、缺指标这几类无效结果。
- [ ] **S3**：E1–E7 都有实测数据，G-ios 阈值在 PR1 开工前写进文档。
- [ ] **S4**：VelaPhone 模拟器构建成功，`VelaPhoneTests` 失败为 0。
- [ ] **S5**：模拟器和真机的 Release 产物中 `perf-autoplay` 出现 0 次，编译条件里没有 `PERF_PROBE`。
- [ ] **S6**：日常版和 Perf 版可以同时安装在真机上，并且静音起播。
- [ ] **S7**：G-ios 单窗、双窗、后台三项都合格：首帧、并发重叠 ≥ 90%、资源计数不超过路数、流量比值 ≤ 1.15、热降档后能回升。
- [ ] **S8**：以下场景中两路房间永不重复：屏蔽主路或副路、取消收藏、本地搜索、全站搜索装入和退出、切换来源、列表只剩一个、空列表续页恢复、reveal 的目标在另一路。
- [ ] **S9**：副路有焦点时，收藏、屏蔽、不感兴趣、重试、暂停都只作用于副路，另一路的房间和状态不变。
- [ ] **S10**：回前台的恢复矩阵全部符合 §3.2（首次解析、切房解析、刷新中、失败态、手动暂停、隐藏的路、从后台经 inactive 回到 active）。
- [ ] **S11**：音频转换按顺序执行、没有两路同时出声的瞬间；会话失败时保持静音；进后台停用会话，其他 App 的音乐能恢复；打断和拔耳机时转为静音，且不自动恢复出声。
- [ ] **S12**：裁决 1–4 生效：默认单窗且开关被记住；两路都用最高档；过热降档、恢复后回升。
- [ ] **S13（裁决 5）**：横屏流在竖屏单窗和双窗下都能完整看到、没有裁切：所有 `display-check` 行都是 `gravity=resizeAspect` 且 `uncropped=true`，用户截图确认；留边区露出模糊缩略图或深色渐变，没有大块纯黑；竖屏流按比例铺满；iOS 代码里没有 `resizeAspectFill`；macOS 的铺满行为不变。

---

## 附录 A：文件归属（初稿，以 T8 的依赖清单为准）

- **`Shared/Relay/`**：`PlaylistRelay`
- **`Shared/Playback/`**：`PlayerController`、`SiteSidecar`、`URLSessionHTTPClient`
- **`Shared/Stage/`**：
  - 模型与流程：`StageModel` 及各扩展、`PlaybackLane`、`StageModel+Lanes`、`LifecycleWiring`
  - 设置与通用：`SettingsStore`、`PreferenceHealth`、`RoomInfoPrefs`、`LegacyLoginCleanup`、`Theme`、`ButtonStyles`
  - 共享界面：`AgeGateContent`、`RoomCaption`、`ChallengeSheet` 的内容部分、`SwapBackdrop`（从 `RoomCanvas` 拆出）、`RoomThumbnailCache`
  - 面板：`TastePanel`、`TasteParts`、`TasteView`、`FavoritesPanel`、`FavoritesRows`、`CurationPanel`
- **`App/`**（macOS 专用）：
  - 应用与平台：`VelaApp`、`PlatformHooks+Mac`、`StageModel+Mac`、`WindowBridge`、`BossKey`、`ClickableRegion`
  - 界面：`ChromeView`、`ChromeControls`（只剩窗口控件）、`HeartButton`、`MenuBarFavorites`、`StageView`、`PagerView`、`RoomCanvas`
  - 各平台适配的 macOS 版：`AgeGateView` 外壳、`PulseDotMark`、`SiteWebView`、`SidecarHost`、`PlayerLayerView`
- **`iOS/`**：
  - 应用与平台：`VelaPhoneApp`、`PlatformHooks+Phone`、`ClickRegion+Phone`
  - 界面：`PhoneStageView`、`LanePager`、`PhonePlayerLayer`、`PhoneChrome`、`AgeGate+Phone`、`PulseDot+Phone`、`SiteWebView+Phone`
  - 系统集成：`AudioFocusController`、`PhoneLifecycle`、`Info.plist`
  - `iOS/Spike/` 只存在于 `spike/ios` 分支
- **新增 Core**：`RelaySourceTable`、`RelayTrack`、`PlaybackFailureClassifier`、`LaneSelection`、`LaneCursor`、`RoomTarget`、`DisplayFit`、`LifecycleEpoch`、`PhoneLifecycle`、`AudioTransition`、`AudioSessionPolicy`、`LaneQuality`、`LaneSettlement`；FeedStore 新增 4 个接口，Taste 新增 1 个重载
- **`scripts/`**：修改 `perf-probe.sh`；新增 `perf_gate.py`、`perf-probe-ios.sh`

## 附录 B：需要用户介入的地方

- T2 的手动签名与设备步骤，以及约 m5p 的使用时段。
- E7、T16、T19 的手机截图确认（裁决 5）。
- PR0 结束时：如果实测表明双窗最高档无法接受，需要用户重新取舍。在用户决定前先 HALT，不得自行封顶画质。
- E1 如果走到第三步（`NSAllowsArbitraryLoadsForMedia`），需要用户授权。

Ready for execution

---

## 修订 A1（2026-11-17，用户裁决 6：不用真机，全程模拟器）

用户明确要求「直接模拟，别真机」。以下替换计划中所有真机步骤（冻结区块经用户授权修改）：

- **T2（签名与设备准备）取消**；Perf/Spike 构建一律 `-sdk iphonesimulator CODE_SIGNING_ALLOWED=NO`。
- **T2b 改为模拟器实验 E1–E7**：模拟器 App 在 Mac 上是普通进程，`scripts/perf-probe-ios.sh` 改为 `simctl boot`（不开 Simulator.app 窗口）→ `simctl install/launch -- --autoplay-dual` → 在宿主上按进程 pid 采样 `ps`/`footprint`/`top`（CPU、内存、唤醒、线程）与 `nettop`（流量），日志用 `simctl spawn … log show`（先 `log config --mode level:debug,persist:debug`）。负样本照旧必须失败。
  - 模拟器做不到或不具代表性的项（如实记录，不当作真机结论）：真实热状态（改为调试开关注入热状态事件，验证降档/回升逻辑）；硬件解码器并发上限（模拟器用 Mac 解码器，E3 只记录现象，`PlaybackFailureClassifier` 白名单暂只收 `decoderTemporarilyUnavailable`）；手机 CPU/耗电绝对值（只用「双窗 ÷ 单窗」比值和相对基线做门）；大陆网络。
  - 音频：模拟器上不实际出声。E4 改为日志验证会话类别与 active 转换顺序（`AudioFocusController` 打日志），并保持全程静音输出。
- **G-ios 改为 G-sim**：阈值取自模拟器 Spike 实测（同一台 m424、同一 iOS 运行时）；双窗的首帧、重叠时长 ≥ 90%、资源计数、`display-check uncropped=true`、后台 5 秒内网络与 CPU 归零照旧检查。
- **S3、S6、S7、S13 中的「真机」「截图确认」改为模拟器日志与指标证据**；S6 改为「Release 与 Perf 两种模拟器构建可并存安装并静音起播」。
- 真机安装与真机实测移出本计划范围，留作以后用户需要时的独立任务。

---

## 修订 A2（2026-11-17，用户裁决 7：两端都支持画中画）

用户新增需求：macOS 与 iOS 都支持画中画（PiP）。用户已裁决：
- **7a iOS 触发**：正在播放时回桌面自动进入画中画（`canStartPictureInPictureAutomaticallyFromInline = true`），另有画中画按钮；设置里可关闭「自动进入」（默认开）。
- **7b iOS 声音**：画中画要求播放类别。静音时用 `.playback` + `.mixWithOthers`（不打断他人音乐）；取消静音时按裁决 2 用 `.playback`（不混音，打断他人）；再静音回到 `.playback + .mixWithOthers` 并 `setActive(false, .notifyOthersOnDeactivation)` 让对方恢复（若 PiP 仍在播放则保持会话活跃，只切换选项）。原「静音用 `.ambient`」在支持 PiP 后改为上述规则；Spike E8 实测确认 `.mixWithOthers` 下 PiP 可用。
- **7c macOS 隐私**：老板键与锁屏同时关闭画中画并停播（一键全藏）；主窗口被遮挡或最小化时，画中画照常播放。

对冻结区块的修改（经用户授权）：
- 「不声明 `UIBackgroundModes`」改为：声明 `audio`（Audio, AirPlay, and Picture in Picture）。理由：iOS 画中画必需。约束：只有画中画正在显示的那一路允许在后台播放；没有画中画时进后台仍按 §3.2 立即停播释放。
- 「进后台即停播」改为：进后台时若焦点路处于画中画（或将自动进入画中画），该路继续播放，**其余路一律释放**；中继保持监听，但源表只保留画中画那一路的源；`LifecycleEpoch` 对画中画路的请求放行，对其余共享任务（目录、搜索、收藏查询、侧车、缩略图）照常挂起。画中画结束（用户关闭或回到 App）时按 §3.2 处理。
- 双窗：只有焦点路能进画中画；进入时另一路释放，回到 App 后按 `LaneSelection` 恢复。

新增与修改的任务：
- **Core（云端，T6 的后续 T6b）**：`PiPPolicy` 纯函数：输入（平台、场景阶段、画中画状态 active/starting/stopping/none、自动进入开关、静音、焦点路、老板键/锁屏/遮挡/最小化）→ 输出（哪一路允许播放、是否保持中继、会话类别与选项、是否关闭画中画）。`PhoneLifecycle.actions` 增加画中画分支；`AudioSessionPolicy` 改为 7b 规则。测试覆盖：回桌面自动进入时焦点路继续、另一路释放；无画中画进后台照旧全停；PiP 中老板键（mac）关闭 PiP 并停播；mac 最小化/遮挡时 PiP 继续；静音 PiP 用 mixWithOthers；取消静音不混音；再静音通知他人恢复且 PiP 会话保持。
- **Spike E8（模拟器）**：AVPictureInPictureController(playerLayer:) + 自动进入；回桌面后 PiP 是否出现（日志 `pip started`）、中继在后台是否持续供应清单（relay 请求日志、PiP 首帧不黑）、`.playback + .mixWithOthers` 下是否可用；资源：PiP 时 CPU/内存/流量（只一路）；退出 PiP 回 App 恢复。若模拟器不支持某项，如实记录。
- **macOS T10b（本机，PR1 内，T10 之后）**：`PlayerLayerView`（macOS）接入 `AVPictureInPictureController`；控制栏加画中画按钮；播放闸门：PiP 活跃时窗口遮挡/最小化不暂停、不触发停播释放（§3 的 30 秒释放只看「窗口不可见且 PiP 未活跃」）；老板键、锁屏、系统休眠时先 `stopPictureInPicture()` 再停播；退出 PiP 后恢复正常闸门。G-mac：非 PiP 场景数字不退化；另测 PiP 场景 cpu/footprint 记录进 PERF.md。
- **iOS T15b（本机，PR2 内）**：`PhonePlayerLayer` 接入 PiP 与自动进入设置项；`AudioFocusController` 按 7b；`PhoneLifecycle` 接入 `PiPPolicy`；Info.plist 加 `UIBackgroundModes = audio`。
- **iOS T18b（本机，PR3 内）**：双窗只有焦点路可进 PiP，进入时释放另一路，回 App 恢复。

新增验收：
- **S14**：macOS 与 iOS 都能进入、退出画中画；画中画中画面保持原始比例（裁决 5 同样适用）；默认静音；iOS 回桌面自动进入（开关可关）；mac 老板键/锁屏关闭画中画并停播；mac 最小化或遮挡时画中画继续。
- **S15**：画中画期间只有一路在解码和联网；无画中画时进后台 5 秒内网络与 CPU 归零（原 S7 后台项不变）；画中画时 iOS 静音不打断他人音乐（日志核对会话类别与选项）。
