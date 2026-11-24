# 安卓画中画（里程碑 C）

语义对齐 iOS `PlatformHooks+Phone` 的画中画段落与 `PiPPolicy`。代码落点：`android/app/src/main/kotlin/vela/app/pip/`（`ActivityPictureInPicture`、`PiPCoordinator`、`PiPAspect`、`PiPFallback`、`PiPEvent`、`PictureInPictureControlling`）、`StageViewModel.setPipSolo` 与焦点/路数锁、`LifecycleWiring`、`MainActivity`。纯函数在 `android/core/src/main/kotlin/vela/core/lifecycle/` 的 `PiPPolicy.kt` 与 `PhoneLifecycle.kt`。本文以代码为准，不记性能数字。

## 接线

- `MainActivity.onCreate` 总是创建 `PiPCoordinator` 与 `ActivityPictureInPicture`，事件经 `LifecycleWiring.onPictureInPictureEvent` 转给协调器并同步 `StageUiState.pictureInPicture`。只有 `pipHost.supported`（`PackageManager.FEATURE_PICTURE_IN_PICTURE`）时才 `attach()` 并赋给 `coordinator.pip`；不支持的设备上 `coordinator.pip` 为 null。
- `LifecycleWiring.attachPictureInPicture` 把 `StageIntent.EnterPictureInPicture` / `TogglePictureInPicture` / `SetAutoEnterPip` 的回调接到 `PiPCoordinator.enterFromUi` / `toggleFromUi` / `setAutoEnter`。这三个意图在 `StageContract.kt` 里已有；`ChromeBar` 派发 `EnterPictureInPicture`（顶栏「画中画」按钮，testTag `enter-pip`）和 `SetAutoEnterPip`（「回桌面自动画中画」开关，testTag `toggle-auto-pip`，默认开）。`TogglePictureInPicture` 仍然没有界面入口；回桌面自动进入不依赖按钮。
- `PictureInPictureControlling` 是生命周期对控制器的最小接口，测试用桩替换；正式实现是 `ActivityPictureInPicture`。

## 进后台：继续焦点路还是全停

`LifecycleWiring.moveTo(BACKGROUND)` 调 `PiPCoordinator.pictureInPictureInput()` 组 `PiPPolicy.Input`，交给 `PhoneLifecycle.actions`：

- `pip == null`（不支持或没挂控制器）时输入为 null，照旧全停。
- `autoEnterEnabled` 取 `controller.canAutoEnter`，即 `autoEnter && supported && bound`。
- `focusPlaying` 由 `PiPCoordinator.focusIsPlaying` 判定：焦点路 `LanePlayback.Playing`、`playingUsername` 非空、未手动暂停、`playerAdvancing`（默认恒真，测试可替换）。`PhoneLifecycle.evaluate` 再用快照 `shouldPlay`（有房间、可见、未手动暂停、非失败）收紧，只收紧不放宽。
- `PiPPolicy.decide`：`engaged` 为 `STARTING` 或 `ACTIVE`；自动进入还要求 PHONE、BACKGROUND、开关开、状态 `NONE`、焦点路正在播。**已经在画中画（含启动中）时与 `autoEnterEnabled` 无关，照样继续焦点路**；开关只决定「还没进画中画时要不要自动进入」。

两条序列都由 `PhoneLifecycle` 给出，`LifecycleWiring` 不重排：

| 路径 | 动作序列 |
|---|---|
| 全停（`backgroundStop`） | `GateOff` → `EpochSuspend` → `SettleDwell(BACKGROUND)` → `FlushSavers` → `SuspendLanes` → `AudioDeactivate` → `StopListener` → `NoteResumeIntent` |
| 继续焦点路（`pictureInPictureBackground`） | `SuspendSharedTasks` → `AllowLaneRequests(焦点)` → `SettleDwell(BACKGROUND)` → `FlushSavers` → 逐条 `ReleaseLane(非焦点)` → `ContinueLane(焦点)` → `RetainRelaySource(焦点)` → `NoteResumeIntent` |

继续路径不发 `GateOff`、`EpochSuspend`、`SuspendLanes`、`AudioDeactivate`、`StopListener`；停留结算与落盘照做。`RetainRelaySource` 在进程内 `LaneHub` 下是空操作（`AppPlaybackSession.retainRelaySource`），非焦点路释放后源表自然只剩焦点路。

### 两层「只留焦点路」，语义不同

- **可见性层**：`StageViewModel.setPipSolo(true)` 走 `syncLaneVisibilityForPip` → `Stage+Lanes.kt` 的 `syncLaneVisibility`，口径 `solo = (landscape || pipSolo) && laneCount > 1`。非焦点路 `visible = false` 并 `PlaybackSink.stop()`、`PlaybackLane.stop()`，只是停播，**不释放实例**；`secondaryCursor`、房间与 `LaneFace` 全保留。与横屏临时释放同类。
- **实例层**：进后台继续路径上，`PhoneLifecycleAction.ReleaseLane` 经 `AppPlaybackSession.releaseLane` 真正 `suspendNow()` + `release()` 非焦点路播放器（副路走 `releaseSecondaryLane`）。这由生命周期动作驱动，不是 `setPipSolo` 做的。

`laneCount` 在画中画期间不被改动；退出后可见性恢复并按门控条件再起播。

## 状态机、锁与 3 秒回退（`PiPCoordinator`）

状态用 `PiPPolicy.State`（`NONE` / `STARTING` / `ACTIVE` / `STOPPING`）。`pictureInPicturePlaying` 只在 `STARTING` 或 `ACTIVE` 为真。

| 事件（`PiPEvent`） | 行为 |
|---|---|
| `WillStart` | 置 `STARTING`；`syncLaneCountLock(true)`、`setPipSolo(true)`、`focusLocked = true`；同步音频钩子；静音时只同步钩子不申请焦点（`keepMutedSession`） |
| `DidStart` | 置 `ACTIVE`；保持锁。若在继续等待中则撤销回退定时；若没在继续且已后台（迟到启动），`pip?.abort()` 关掉小窗 |
| `Failed` | 置 `NONE`；解锁、`setPipSolo(false)`；若在继续且已后台则立即回退全停 |
| `WillStop` | 置 `STOPPING`；`laneCountLocked` 保持为真直到 `DidStop`；`pictureInPicturePlaying` 变假 |
| `DidStop` | 置 `NONE`；解锁、`setPipSolo(false)`。若仍在继续且已后台：收到过 `RestoreRequested` 则重新武装 3 秒回退（restore-timeout），否则直接补做全停 |
| `RestoreRequested` | 只记标志，供 `DidStop` 分支用 |

- 锁的落点：`StageContract.kt` 的 reducer 与 `Stage+Lanes.kt` 的 `setFocus` / `setLaneCount` 在 `focusLocked || laneCountLocked` 时直接忽略意图。`DualStageScreen` 的路数选择器禁用并提示「画中画进行中，结束后可调整路数」。
- `StageViewModel.syncLaneCountLock(false)` 解锁时调 `notePortraitStageSize` 按舞台尺寸补判路数。
- 回退定时在 `onContinued` 且状态非 `ACTIVE` 时武装，时长 `fallbackDelaySeconds`（默认 `PiPFallback.TIMEOUT_SECONDS = 3.0` 秒，测试可缩短）。到时 `PiPFallback.shouldStop`：仍在 BACKGROUND 且状态非 `ACTIVE` 才停，卡在 `STARTING` 也算未进入。
- `fallbackStop`：状态非 `NONE` 时 `pip?.abort()`，清状态、解锁、`setPipSolo(false)`，然后 `stopAllAfterPictureInPicture` 用 `input.copy(pictureInPicture = STOPPING)` 调 `LifecycleWiring.applyPictureInPictureStop`，在已后台的前提下补做上表的全停序列。
- 回前台：`moveTo(ACTIVE)` 且挂起过时先 `onReturningToForeground`（撤销回退、清继续标志；`pictureInPicturePlaying` 时调 `pip?.stop()`，不在画中画则 `setPipSolo(false)`）；`onForegroundIdle` 再兜底清一次 `pipSolo`。小窗展开由系统在回前台时完成，`ActivityPictureInPicture.stop()` 本身是空操作；只有 `abort()` 会 `finish()`。

## Activity 侧（`ActivityPictureInPicture`）

- **比例**：`PiPAspect.clamp` 把宽高比夹在 `MIN = 1/2.39` 到 `MAX = 2.39`（边界直接用精确分数 `100:239` / `239:100`，转 `Rational` 后再兜底校验不跌破下界）；无效尺寸回落 `FALLBACK = 9:16`。`resolveSize` 优先取焦点路 `PlaybackLane.currentVideoSize()`，没有有效尺寸时复用同路已记录尺寸或回退 9:16。参数在进出画中画、静音刷新、开关变化，以及 `attach()` 订阅的 `stage.state` 发生焦点/播放/暂停/静音变化时重建；焦点路尺寸变化经 `noteVideoSize` 刷新比例（无效尺寸忽略，同路同尺寸去重，换焦点清去重）。
- **静音**：工具栏动作用 `RemoteAction`，图标 `ic_pip_mute` / `ic_pip_unmute`；`PendingIntent.getBroadcast` 发 `ACTION_TOGGLE_MUTE`（值 `vela.app.pip.TOGGLE_MUTE`），`setPackage` 限本包，`FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`。接收器在 `attach()` 注册（API 33+ 用 `RECEIVER_NOT_EXPORTED`），收到后派发 `StageIntent.ToggleMute` 并 `refreshMuteAction()` 换图标；`MainActivity.onMuteChanged` 也会刷新。
- **自动进入**：API 31+ 走 `PictureInPictureParams.setAutoEnterEnabled(autoEnter && focusLooksPlayable())`，并 `setSeamlessResizeEnabled(true)`，随 `stage.state` 中焦点路播放状态与手动暂停变化自动刷新系统参数；API 26–30 在 `onUserLeaveHint` 里当 `canAutoEnter` 且 `focusLooksPlayable()` 时 `enter()`。即系统参数与兜底都要求焦点路像在播，不只看开关。
- **进入失败**：`enterPictureInPictureMode` 抛 `IllegalStateException` / `IllegalArgumentException` 时发 `PiPEvent.Failed`。
- `onPictureInPictureModeChanged` 进时发 `WillStart` + `DidStart`，出时发 `WillStop` + `DidStop`。

## 声音

与单窗/双窗同一套：静音不请求音频焦点；取消静音请求 `AUDIOFOCUS_GAIN_TRANSIENT`；再静音放弃焦点。`PiPCoordinator.syncAudioHook` 把 `AudioFocusController.pictureInPictureActive` 置为 `pictureInPicturePlaying`（启动中与显示中为真，`STOPPING` 起为假）；`AudioFocusPlan` 据此在画中画期间的静音降压里保持播放与会话（`keepsPlayback`），静音画中画不抢焦点。

## 设置

`Settings.autoEnterPip`（SharedPreferences `com.vela.stage` 的键 `autoEnterPip`）缺省 true，已读写，不是预留字段。`StageIntent.SetAutoEnterPip` → `StageViewModel` 写设置与状态 → `PiPCoordinator.setAutoEnter` 同步 `pip.autoEnter`（立即重建系统参数）。当前无界面控件派发该意图，见「接线」。

## 与双窗 / 三窗

双窗竖屏可进画中画；进后只留焦点路，退出恢复。三窗仍然只占界面槽位，`LaneID.TERTIARY` 不创建 `PlaybackLane`；画中画只挂焦点路。双窗语义见 `android/docs/dual-window.md`。

## 端到端门（行为断言，不是性能基线）

CI 的 `android-emulator` 跑 `android/tools/emulator-run.sh all`，按文件名顺序执行 `emu-modes/*.sh`（`background`、`challenge`、`e2e-a`、`e2e-b`、`e2e-c`、`lane`、`pip`）。

`android/tools/emu-modes/e2e-c.sh` 安装 perf 应用 + debug `androidTest`，用 `-e class` 逐条跑 `vela.app.e2e.c.MilestoneCTest` 的三个方法，条与条之间 `am force-stop`：

- `test01HomeEntersPipFocusOnlyUncroppedAndRestoresLanes`（S-C1）：双窗、静音、自动进入开。HOME 后 3 秒内进画中画，否则断言全停（门关、`lifecycleEpoch.allowedLane == null`、主路播放器为 null）。进入后：锁生效、`allowedLane` 等于焦点路、副路已释放或不可见、`SetFocus` / `SetLaneCount` 被忽略；比例断言 `display-check` 含 `gravity=fit` 与 `uncropped=true`，日志未刷出时用焦点路 `currentVideoSize` 兜底。回前台后路数（或 `rememberedLaneCount`）恢复到进入前，两把锁为假。
- `test02PipMuteAudioFocusGainTransient`（S-C2）：用 `dumpsys audio` 断言静音（含静音画中画）时焦点栈没有本包、`holdingFocus` 为假；取消静音后拿到焦点且栈里有 `GAIN_TRANSIENT`；再静音放弃焦点。
- `test03AutoEnterOffStopsAndClosingPipIsQuiet`（S-C3）：`autoEnterPip = false` 时 HOME 即全停（主副播放器都为 null）。回前台开开关、进画中画后 `activity.finish()` 关窗，断言全停、5 秒后无 `ExoPlayer:Playback` 线程；随后 30 秒 marker 窗口由脚本按请求日志断言零请求。

脚本侧再按 `VELA_REQUEST_LOG` 核对：S-C1 窗口内媒体请求（`.m3u8` / `.ts`）至少 1 条（焦点路仍联网）；S-C3 关自动进入的后台窗口不要求零请求（含起播），关闭画中画后的窗口必须为零。

包级模式 `android/tools/emu-modes/pip.sh` 逐条跑 `vela.app.pip.PipEmulatorTest`：`testHomeWithoutAutoEnterStopsAllWithinThreeSeconds`、`testHomeWithAutoEnterEitherEntersPipOrStopsAll`、`testAspectClampRejectsExtremeRatios`、`testMuteRemoteActionIntentExists`（只钉 `ACTION_TOGGLE_MUTE` 常量值）。

设备不支持 `FEATURE_PICTURE_IN_PICTURE` 时，S-C1 与 pip 模式期望全停，不算失败；S-C3 跳过关窗段。伪上游与夹具同 A / B，只用合成数据，不访问真实站点。测量口径与「尚未入库」清单见 `docs/PERF.md` 里程碑 C 一节。
