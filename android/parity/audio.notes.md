# A2 音频焦点

`AudioTransition` 不按 AVAudioSession 直译。断言改成焦点语义，Swift 方法名留在 `AudioTransitionTests` 上，类名与文件名一致，`split-audio.tsv` 不需要数据行。

## 和 iOS 的对应

| iOS | 安卓 |
|---|---|
| `setCategory(.playback, .moviePlayback)` + `setActive(true)` | `AUDIOFOCUS_GAIN_TRANSIENT`（USAGE_MEDIA、CONTENT_TYPE_MOVIE）。不用永久 GAIN，也不用 MAY_DUCK |
| `mixWithOthers` / 静音不打断别人 | 不调用 `requestAudioFocus` |
| `setActive(false, .notifyOthersOnDeactivation)` | `abandonAudioFocusRequest`，对方可以恢复 |
| 画中画再静音不 `setActive(false)` | 播放器留着（`keepsPlayback`），焦点仍然放弃。静音画中画不占焦点栈 |
| 类别或激活失败 | 唯一的失败点是请求没被授予 |

不用 ExoPlayer 的 `handleAudioFocus`，那个要的是永久 GAIN。

## 裁决

- 没有 `muted` 键：`AudioPolicy.initialMuted` 视为静音。`forStoredMute(null)` 只有 `MuteAllLanes`，不请求、不放弃、不写设置。
- 存过的取消静音：请求 GAIN_TRANSIENT，不把同一个值再写一遍。
- 用户取消静音：先静音旧路，再请求，授予后才 `UnmuteLane`。写设置。
- 请求失败：运行时保持静音，不写设置，提示「暂时无法出声」。没打算出声的计划不被这个失败改写。
- 再静音、进后台、最后一路停、丢焦点、可降音的丢焦点、拔耳机：都放弃焦点。除了用户静音和拔耳机，都不写设置。`resumesAudible` 恒为 false，焦点归还也不取消静音。丢焦点或拔耳机发生在画中画里时 `keepsPlayback` 仍为 true。
- 拔耳机在画中画里同样放弃焦点，但 `keepsPlayback` 为 true，且不暂停。
- 非焦点路不请求焦点。若因此不再有任何一路出声，一并放弃焦点。
- 步骤里同时出声的路不超过 1。已持有短暂焦点时切路不再发第二次请求，避免中间把对方放出来。

`PipAudioOutput` 由 `AudioFocusPlan.fromPip` 消费。`releaseNotifyingOthers == false` 只表示画面继续，不表示可以继续占焦点。C3 不依赖本包。

挂起引起的打断（`wasSuspended`）安卓焦点回调里没有对应标志，计划仍忽略它。控制器不从 `AudioManager` 发出这个意图。
