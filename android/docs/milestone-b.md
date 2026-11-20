# 安卓里程碑 B（双窗）记录

基线：`android/fleet`（含 A、B0 契约、B1 双路舞台、B2 双窗界面、E2）。本文只记结构与待办，不改代码。对照：`docs/ARCHITECTURE.md` §11.8、`docs/PERF.md`「安卓（里程碑 B）」、`android/PORTING.md`「milestone-B 跳过项」、iOS §5.10。

核对日期：2026-11-20。

## 1. 双窗舞台与界面结构

```text
MainActivity
  └─ StageScreen（竖屏且 laneCount≥2 → DualStageScreen；否则焦点路单窗）
       ├─ DualStageScreen：N 个等高 DualPane（N=laneCount，本里程碑播放接线到 2）
       └─ 每格 LaneSurface ← AppPlaybackSession 的主路 / 副路 PlaybackLane

StageViewModel
  ├─ Stage+Lanes：开闭路、联合选房、副路起播/结算、热降档、degradeLane
  ├─ StageContract / LaneWindow：路数、焦点、横屏可见性纯函数
  └─ Settings.dualWindow：记住是否双窗

AppPlaybackSession（LifecycleWiring）
  ├─ lane（主路 StateFlow）
  ├─ secondaryLane + ensureSecondary / releaseSecondaryLane
  └─ AudioFocusController：按焦点路出声
```

| 层 | 路径 | 职责 |
|---|---|---|
| 契约 | `app/.../stage/StageContract.kt` | `laneCount`、`focusLane`、`secondary`、`SetDual` / `SetLaneCount` / `SetFocus` / `SetLandscape`、带 `RoomTarget` 的操作 |
| 舞台 | `app/.../stage/Stage+Lanes.kt`、`StageViewModel.kt` | 第二路游标与 `LaneFace`、选房、热状态、降级 |
| 会话 | `app/.../stage/LifecycleWiring.kt` | 创建 / 释放副路 ExoPlayer；前后台挂起 |
| 播放 | `app/.../lane/PlaybackLane.kt`、`ThermalMonitor.kt` | 单路播放；热状态监视 |
| 界面 | `app/.../ui/DualStageScreen.kt`、`StageScreen.kt` | 上下格、焦点边、空路文案、格内操作 |
| 入口 | `app/.../MainActivity.kt` | 按 `LaneID` 绑定 `LaneSurface` |

格高公式与 iOS 一致：`(H − (N−1)×gap) / N`（`LaneLayout`）。焦点路香槟色细边；空路「没有更多房间」。

## 2. 减少路数 vs 临时释放

**永久减少路数**（用户关双窗 / `setLaneCount(1)`，`remember` 可写设置）：

1. 若焦点在副路，先把声音与焦点收到主路，再把焦点房间 reveal 到主路。
2. `dropSecondary(DUAL_OFF)`：副路停留结算 → 停 sink → 调 `releaseSecondaryLane`（会话层释放副路播放器）→ 丢掉副路游标与 `LaneFace`。
3. `laneCount = 1`，`settings.dualWindow = false`（若 remember）。

这是会话层「释放第二路」的完整路径：逻辑状态与播放器都没有可恢复的副路对象。

**临时释放（可恢复）**——逻辑路（房间、游标、暂停/失败态）保留，只停掉播放资源：

| 场景 | 行为 | 恢复 |
|---|---|---|
| 横屏 | `syncLaneVisibility`：非焦点路 `visible=false` 并 `stop` sink；焦点路保持 | 回竖屏后对可见且未手动暂停、非失败的路 `requestPlay` / `requestLane` |
| 进后台（无画中画） | `suspendLanes`：主副路都挂起并 `release`，会话引用清空；舞台上的双窗与房间仍在 | 回前台按裁决 7 重建路并解析起播 |
| 画中画 | **尚未接线**（PiP1）。计划与冻结裁决 6 一致：只留焦点路联网，其余路临时释放；3 秒内未进入则全停 | 退出画中画后按原路数恢复 |

横屏时改路数仍按永久减少 / 增加处理；回竖屏按新的 `laneCount` 恢复可见性。

## 3. parity 中仍保留的 `milestone-B` 跳过项

`android/parity/skip.tsv` 现有 **121** 行理由以 `milestone-B:` 开头（2026-11-20 计数）。分组与原因：

| 组 | 条数 | 原因 |
|---|---|---|
| `TripleWindowTests` + `TripleLaneMediaTests` | 51 | 安卓 B 不做三窗播放；界面可摆 3 格但不建第三路播放器 |
| `DualWindowTests` | 27 | 语义多由 `DualStageTest` / `MilestoneBTest` 覆盖；未写 `replaced-by` |
| `DualDwellTests` + `DwellFocusTests` | 19 | 部分结算 / 焦点秒有 JVM 覆盖；逐条映射未收尾 |
| `DualQualityTests` | 5 | 热降档语义已有测试；方法名未对齐 |
| `AudioTransitionRunnerTests` + `DualAudioFocusTests` | 19 | 安卓用 `AudioFocusPlan` / `AudioFocusController`，不是 iOS Runner；理由列未改成不移植或 replaced-by |

这些行继续让 `--all` 把对应 Swift 用例算作已登记跳过。收尾工作（改理由 / 加 replaced-by / 删行）归编排者后续包，D-B 不改表。

## 4. 仪器测试反射 → 正式测试接口（待办）

已有正式钩子（`@VisibleForTesting` 或会话只读属性），E2 已使用：

- `PlaybackLane.audioEnabledCountForTest()` / `playerVolumeForTest()`
- `AppPlaybackSession.secondaryLaneForTest`
- `StageViewModel.injectThermalForTest` / `laneCapForTest` / `degradeLaneForTest` / `isQualityLimitedForTest`

仍用反射读内部字段的路径（应改为上面的钩子，**不在 D-B 改代码**）：

- `android/app/src/androidTest/kotlin/vela/app/e2e/a/MilestoneATest.kt`：`playerVolume` / `audioEnabled` 经 `getDeclaredField("player")` 深入 `PlaybackLane` → `LanePlayer` → ExoPlayer。里程碑 A 仪器在 B 之前写成；B 的 `E2eBSupport` 已示范正式接口。

单元测试里对 Media3 / 框架类型的反射（如 spike 清 `NetworkTypeObserver` 单例）不在本待办范围。

## 5. 验收入口（文档包）

- `python3 -I android/tools/parity.py --all`（无新增问题；既有 milestone-B skip 仍在）
- `cd android && ./gradlew --no-daemon :app:assembleRelease`
- 模拟器双窗旅程由编排者机器跑 `emu-modes/e2e-b.sh`，本云端环境可不跑仪器。
