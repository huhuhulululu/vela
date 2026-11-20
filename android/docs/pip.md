# 安卓画中画（里程碑 C）

语义对齐 iOS `PlatformHooks+Phone` 画中画段落与 `PiPPolicy`。代码落点：`android/app/src/main/kotlin/vela/app/pip/`、`StageViewModel.setPipSolo` / 焦点与路数锁、`LifecycleWiring`、`MainActivity`。

## 行为（以代码为准）

- 回桌面只放行焦点路；其余路释放播放器。逻辑路状态保留，与横屏临时释放同类。
- 没有画中画时进后台仍走 `suspendLanes` 全停（不支持、自动进入关、焦点路未在播等，`PiPPolicy` 不给出继续时）。
- 启动中与显示中锁住焦点与路数（`focusLocked`、`laneCountLocked`）。`PiPFallback.TIMEOUT_SECONDS` 为 3 秒：到时仍非 `ACTIVE` 则全停。
- 退出画中画后 `setPipSolo(false)`，按进入前的路数恢复。
- 比例夹紧在 `PiPAspect`。静音用 `RemoteAction`（`ActivityPictureInPicture.ACTION_TOGGLE_MUTE`）。出声音频焦点是 `AUDIOFOCUS_GAIN_TRANSIENT`；静音不请求焦点。
- `Settings.autoEnterPip` 已读写（默认开）。API 31+ 写进 `PictureInPictureParams.setAutoEnterEnabled`；API 26–30 用 `onUserLeaveHint` 兜底。

## 主要类型

| 类型 | 职责 |
|---|---|
| `ActivityPictureInPicture` | 系统 PiP 参数、比例、静音 RemoteAction、自动进入 |
| `PiPCoordinator` | 状态机、3 秒回退、锁与 `setPipSolo`、补做全停 |
| `PiPFallback` | 后台且未 `ACTIVE` 则应停 |
| `PiPAspect` | 宽高比夹紧 |
| `LifecycleWiring` | 进后台组 `PiPPolicy.Input`；`applyPictureInPictureStop` |
| `StageViewModel.setPipSolo` | 只留焦点路的可见性与停播 |

## 端到端门（不是性能基线）

由 `android/tools/emu-modes/e2e-c.sh` 跑 `MilestoneCTest`：

- `test01HomeEntersPipFocusOnlyUncroppedAndRestoresLanes`
- `test02PipMuteAudioFocusGainTransient`
- `test03AutoEnterOffStopsAndClosingPipIsQuiet`

包级模式 `android/tools/emu-modes/pip.sh` 跑 `PipEmulatorTest`（无自动进入全停、有自动进入则进或停、比例夹紧、静音 Intent 存在）。

## 与双窗 / 三窗

双窗竖屏可进画中画；进 PiP 后只留焦点路。三窗仍然只占界面槽位，不创建第三路播放器。双窗语义见 `android/docs/dual-window.md`。

## 界面注意

契约已有 `StageIntent.EnterPictureInPicture` / `SetAutoEnterPip` / `TogglePictureInPicture`，并由 `LifecycleWiring.attachPictureInPicture` 接到协调器。控制栏 Compose（`Chrome.kt`）尚未画独立画中画胶囊；回桌面自动进入不依赖该按钮。`DualStageScreen` 在路数锁住时提示结束后可再调路数。
