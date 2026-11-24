# 安卓竖屏双窗（里程碑 B）

语义来源：iOS `PhoneDualStage.swift`、`StageModel+Lanes.swift`、`PhoneStageView.swift`，以及计划 `docs/plans/android.md` 冻结裁决 5。代码落点在 B0 契约（`StageContract.kt`）、B1 舞台（`Stage+Lanes.kt`、`LifecycleWiring.kt`、`ThermalMonitor.kt`）、B2 界面（`DualStageScreen.kt`、`StageScreen.kt`、`MainActivity.kt`）。

## 路数与契约

- 默认单路（`laneCount == 1`）。用户用顶栏路数选择器或双窗开关可开到 2 路；`Settings.dualWindow` 与 `rememberedLaneCount` 记住选择，冷启动由 `restoreDualIfRemembered()` 恢复。
- `StageUiState.dualWindow` 是 `laneCount > 1` 的派生镜像，界面与测试只读契约，不直接改设置键。
- 第三路（`LaneID.TERTIARY`）在契约与 `DualStageScreen` 里预留格位，**本里程碑不创建第三路 `PlaybackLane`**；`setLaneCount(3)` 只更新 UI 状态，起播仍限主路 + 副路。
- 画中画启动中或进行中 `focusLocked` / `laneCountLocked` 为真时，路数选择与显式切焦点被忽略（由 `PiPCoordinator` 经 `syncLaneCountLock` 与 `focusLocked` 写入，`WillStop` 后路数锁保持到 `DidStop`，与 iOS 一致）。

## 舞台：`StageViewModel` + `Stage+Lanes.kt`

| 主题 | 行为 |
|---|---|
| 联合选房 | `updateFeedLanes` 在副本上变更后调 `LaneSelection.resolve`，`secondaryCursor` 非空；主路结果与 FeedStore 不一致时记日志。双路两两不同房，规则对齐计划 S-B1。 |
| 焦点 | `setFocus` 更新 `focusLane`、停留焦点段 `FocusSegments`、音量推送与 `onLaneFocusChanged`；非焦点路 ExoPlayer 音轨关闭。 |
| 目标路操作 | 收藏、屏蔽、不感兴趣、重试、暂停经 `RoomTarget` / `matchedLane`，只作用于点到的那一路。 |
| 热降档 | `ThermalMonitor` 订阅 `PowerManager`（API 29+）；`noteThermal` + `LaneQuality`：未发热不封顶；`SERIOUS` 及以上按 `estimatedDisplayHeight` 降档，下限 896 kbps；回到 `FAIR` 及以下连续满 60 秒回升。起播经 `ThermalCapSink` 带上限。 |
| 副路资源不足 | `PlaybackFailureClassifier.Outcome.RESOURCE_DEGRADE` 仅副路：`degradeLane` 停播并显示「双窗暂不可用」，不记房间失败、不自动跳过。 |
| 最近划走 | 尚未移植：`LaneSelection` 与 `Stage+Lanes` 当前无 `recentSkips` 记录与过滤（见 `skip-selection.tsv` 与 `skip.tsv` 待裁决项）。 |

日志 category：`vela.stage`，关键字 `lanes`、`focus`、`thermal limit|recover`、`degrade secondary`、`lane-rooms distinct=`。

## 界面

- **竖屏双窗**：`StageScreen` 在 `dualWindow && !landscape` 时整块交给 `DualStageScreen`：上下等高窗格，间距 `LaneLayout.gap`（2dp），格高 `(H − (N−1)×gap) / N`。
- **横屏或单路显示模式**：同一 `StageScreen` 只渲染焦点路的 `LaneRoomPager`，另一路逻辑状态保留但不画双格。
- 每格：`LaneRoomPager` 独立上下滑；点格或在该格划动触发 `SetFocus`；焦点路香槟色细边（`Theme.champagne`）；副路可带 `PaneBar`（房间信息、心形、更多菜单），动作带 `target(showing:)`。
- 画面仍由 `LaneSurface` + `DisplayFit` 原比例摆放；禁止 `ContentScale.Crop` / `FillBounds` / `RESIZE_MODE_ZOOM`。

## 会话层：`LifecycleWiring.kt` 与减路释放

`AppPlaybackSession` 持有主路 `PlaybackLane` 与可选副路。舞台通过回调接线：

- `ensureSecondary` → `ensureSecondary()` 创建副路并 `bindLane`。
- `releaseSecondaryLane` → 用户关双窗或 `dropSecondary` 时**完整释放**副路 ExoPlayer 与会话引用（`release()`），不是仅 `stop()`。

### 减路 / 关双窗（持久释放）

`setLaneCount(1)` 或 `closeLanes`：`dropSecondary` 结算副路停留 → `releaseSecondaryLane?.invoke()` → 清空 `secondaryCursor` / `secondaryFace`。若关窗前焦点在副路，焦点房间可合并回主路并 `Reveal`。

### 横屏（临时释放，可恢复）

`SetLandscape(true)` → `syncLaneVisibility`：非焦点路 `visible = false`，`PlaybackSink.stop()`，**保留** `secondaryCursor`、房间与 `LaneFace` 状态。回竖屏后可见路按 `PhoneLifecycle.shouldPlay` 再 `requestLane`。

### 画中画（里程碑 C，已接）

回桌面在画中画继续路径上只放行焦点路：`setPipSolo(true)` 与横屏共用 `syncLaneVisibility`，非焦点路只是停播并隐藏（不释放实例）；播放器实例的释放由进后台序列里的 `ReleaseLane` 经 `AppPlaybackSession.releaseLane` 完成。逻辑路状态全保留，`laneCount` 不变，退出后恢复双格。启动中与显示中锁焦点与路数；3 秒内未进入则全停。细节见 `android/docs/pip.md`。

### 后台（没有画中画继续时全停，回前台按裁决 7 恢复）

没有画中画（不支持、自动进入关且未在画中画、焦点路未在播等 `PiPPolicy` 不给出继续）时进后台：`suspendLanes` 拆掉主副 `PlaybackLane` 实例；`secondaryCursor` 等舞台状态仍在 ViewModel。回前台 `ensureLane` / `ensureSecondary` 重建播放器，满足「有房间、未手动暂停、可见、非失败/降级」再起播。

## 声音

双路时 `AudioFocusController` 经 `StageAudioSinks` 只听焦点路；切焦点走 `AudioFocusPlan`，不得两路同时开音轨。仪器测试用 `PlaybackLane.audioEnabledCountForTest()` 断言互斥（见下节）。

## 测试与正式钩子

| 钩子 | 位置 | 用途 |
|---|---|---|
| `AppPlaybackSession.secondaryLaneForTest` | `LifecycleWiring.kt` | 读副路播放器 |
| `StageViewModel.injectThermalForTest` | `StageViewModel.kt` | 注入热状态（可 `scheduleRecovery`） |
| `isQualityLimitedForTest` / `laneCapForTest` | 同上 | 热降档断言 |
| `degradeLaneForTest` | 同上 | 副路降级 |
| `PlaybackLane.audioEnabledCountForTest` / `playerVolumeForTest` | `PlaybackLane.kt` | 出声与音量 |

单元测试：`E2eBTestHooksTest`、`SecondaryLaneReleaseTest`、`StageContractDualTest`、`DualStageTest`、`DualDwellParityTest`、`DualWindowParityTest`、`DualAudioParityTest`。仪器：`MilestoneBTest` + `E2eBSupport`，CI 模式 `android/tools/emu-modes/e2e-b.sh`。

**反射待清理**

- `MilestoneATest` 已改为 `playerVolumeForTest` / `audioEnabledCountForTest`（不再使用 `getDeclaredField`）。
- `PlaybackFaceObserveTest` 反射 `observe`；`BandwidthMeterIsolationTest` 反射 Media3 内部字段，仅限 JVM，可保留或另做 test 替身。
- `MainActivity` 对 `Hooks` 的反射是 release 无 hooks 类的既定启动路径，不是测试债务。

## 与 iOS 的差异（本里程碑有意）

- 不做三窗播放；界面可显示第三格占位。
- 双路画质：安卓两路最高档、热状态才降；iOS 三窗另按格尺寸封顶（ADR-008），安卓未移植三窗 cap。
- 热状态映射：`PhoneThermalState` ← `PowerManager`，与 ADR-A3 一致；模拟器常为 `NOMINAL`。