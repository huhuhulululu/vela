# 安卓画中画（里程碑 C）

语义来源：iOS `PlatformHooks+Phone.swift` 的画中画段落、`Sources/VelaCore/PiPPolicy.swift`，以及计划里 S-C1、S-C2、S-C3。代码在 `android/app/src/main/kotlin/vela/app/pip/` 与 `LifecycleWiring.kt`、`StageViewModel.setPipSolo`。

## 接线总览

`MainActivity` 创建 `ActivityPictureInPicture` 与 `PiPCoordinator`，经 `LifecycleWiring.attachPictureInPicture` 接到 `StageViewModel` 与 `PhoneLifecycle.actions`。进后台时 `LifecycleWiring.moveTo` 把 `PiPCoordinator.pictureInPictureInput()` 交给纯函数 `PiPPolicy.decide`；`pip == null`（没有控制器或设备不支持）时仍走全停 `suspendLanes`。

`PictureInPictureControlling` 为测试桩接口；正式实现是 `ActivityPictureInPicture`。

## 进后台：全停 vs 只留焦点路

| 条件 | 行为 |
|---|---|
| 无画中画控制器，或 `PiPPolicy` 未裁定继续 | 与里程碑 A 相同：`GateOff` → … → `SuspendLanes` → `AudioDeactivate` → `StopListener` |
| 焦点路满足继续（含自动进入且正在播放） | 不发全局 `GateOff`；`SuspendSharedTasks` + `AllowLaneRequests(焦点)`；非焦点路 `ReleaseLane`；焦点路 `ContinueLane` + `RetainRelaySource` |
| 继续后 3 秒内未进入 `ACTIVE` | `PiPFallback` + `PiPCoordinator` 关小窗并 `applyPictureInPictureStop`，补做全停 |
| `autoEnterPip == false` | 不自动进入，裁定不继续，走全停 |

非焦点路在继续路径上是**释放播放器实例**（与减路不同），逻辑路状态（cursor、房间、Face）保留；退出画中画后按进入前路数恢复可见性与起播。

## 舞台锁与 `setPipSolo`

- `PiPEvent.WillStart` / `DidStart`：`syncLaneCountLock(true)`、`setPipSolo(true)`、`focusLocked = true`。启动中与显示中忽略路数选择与显式切焦点（与 iOS `laneCountLocked` 一致）。
- 结束、失败或回退：`syncLaneCountLock(false)`、`setPipSolo(false)`、`focusLocked = false`；解锁时 `notePortraitStageSize` 补判路数。
- `setPipSolo` 与横屏共用 `syncLaneVisibility` 口径：只留焦点路可见并起播，非焦点路 `stop()`，状态不丢。

**三窗**：界面仍可摆第三格，**不创建第三路 `PlaybackLane`**；画中画只挂焦点路，与里程碑 B 占位策略不变。

## Activity 侧

- 比例：`PiPAspect.clamp` 把宽高比夹在文档允许的约 1:2.39…2.39:1，无效尺寸用 9:16。
- 静音：画中画工具栏动作用 `RemoteAction` + `BroadcastReceiver`，点按发 `StageIntent.ToggleMute` 并刷新动作图标。
- 自动进入：`Settings.autoEnterPip` 读写（缺省开）；API 31+ `PictureInPictureParams.setAutoEnterEnabled`；API 26–30 在 `onUserLeaveHint` 里当 `canAutoEnter` 且焦点路像在播时 `enter()`。
- 回前台：`PiPCoordinator.onReturningToForeground` 先 `pip?.stop()` 展开小窗；若已不在画中画则清 `pipSolo`。

## 声音

与单窗/双窗相同：静音不请求音频焦点；取消静音 `AUDIOFOCUS_GAIN_TRANSIENT`；`AudioFocusController.pictureInPictureActive` 在画中画启动中与进行中为真。画中画里静音仍不抢焦点。

## 设置

`Settings.autoEnterPip`（键 `autoEnterPip`）经 `StageIntent.SetAutoEnterPip` 与顶栏画中画控件写入 SharedPreferences，并同步到 `ActivityPictureInPicture.autoEnter`。不是仅预留字段。

## 测试与 CI 模式

| 门 | 方法 | 脚本 |
|---|---|---|
| S-C1 进 PiP / 全停 / 焦点路 / 比例 / 恢复路数 | `MilestoneCTest.test01HomeEntersPipFocusOnlyUncroppedAndRestoresLanes` | `android/tools/emu-modes/e2e-c.sh` |
| S-C2 静音与 `GAIN_TRANSIENT` | `test02PipMuteAudioFocusGainTransient` | 同上 |
| S-C3 关自动进入全停、关 PiP 后后台静默 | `test03AutoEnterOffStopsAndClosingPipIsQuiet` | 同上 |

另有 `android/tools/emu-modes/pip.sh` 登记到 `emulator-run.sh all`。上述是**行为验收门**，不是 PSS/CPU 性能基线（见 `docs/PERF.md` 里程碑 C 一节）。

伪上游与 `MilestoneATest` / `MilestoneBTest` 相同；不支持画中画的模拟器/设备上 S-C1 期望全停，不访问真实站点。