# C3 生命周期移植笔记

行为按 Swift 原样搬。下面是形状差异，不是自行改过的语义。

## 类型落点

- `LifecycleEpoch` → `vela.core.lifecycle.LifecycleEpoch`。`Token.Scope` 嵌在 `Token` 里。代次是 `ULong`，`+= 1` 回绕，对应 `UInt64 &+=`。
- `PhoneScenePhase`、`PhoneResumeIntent`、`PhoneLaneActivity`、`PhoneLaneSnapshot`、`PhoneLifecycleAction`、`PhoneLifecycle` 同包。
- `LaneSettlement.EndReason.background` → `LaneEndReason.BACKGROUND`（L0b）。
- `PiPPolicy` 同包。`Decision.session`（`AudioSessionPolicy.Configuration`）换成 `Decision.audio: PipAudioOutput`。`SessionActivation` 仍留在 `PiPPolicy` 上。
- `AwayRetry`、`AwayRetryTimer` 同包。房间比较走 L0a 的 `CurationKeys.key`（去空白、小写），不自己写一套。
- `ChallengeGate`、`ChallengeResume` 同包。时刻用 `Instant`。间隔用 `Duration` 的秒加纳秒转 `Double`，对应 `Date.timeIntervalSince`。

Swift 这些都是值类型。Kotlin 用类，方法就地改同一实例。调用方不要把一个实例交给两处同时改、还指望各自有一份副本。

## PipAudioOutput

不搬 iOS 的 category / mode，也不搬 `AudioTransition` 的步骤。三个字段是平台无关的意图，A2 的 `AudioFocusPlan` 消费：

| 场景 | holdsFocus | mixWithOthers | releaseNotifyingOthers | keepsSessionActiveOnMute |
|---|---|---|---|---|
| 静音画中画（含即将自动进入） | false | true | false | true |
| 出声画中画 | true | false | false | true |
| 前台静音、没在画中画 | false | true | true | false |
| 前台出声、有路可播、没在画中画 | true | false | false | false |
| mac 老板键 / 锁屏（默认静音输入） | false | true | true | false |

- `holdsFocus` 只在「会话保持活跃且未静音」时为 true。静音不申请独占焦点，包括静音画中画。
- `releaseNotifyingOthers` 只在会话要停用时为 true。静音画中画不放弃、不通知对方，对应 iOS 不 `setActive(false)`。
- `Decision.keepsSessionActiveOnMute` 是 `continueFocus`：已在画中画，或手机将自动进入，且没有 mac 隐私隐藏。状态仍是 `none` 但会自动进入时也是 true。
- `PipAudioOutput.keepsSessionWhenMuting` 只转发传入的「画中画正在播」布尔，对应 `AudioSessionPolicy.keepsSessionActiveWhenMuting`。它不看自动进入，和上一行不是同一个量。

`PiPPolicyTests` 的这三条不再调用 `AudioSessionPolicy` / `AudioTransition`，断言改到上表：

- `testMutedPictureInPictureUsesMixWithOthers`
- `testUnmuteDoesNotMixWithOthers`
- `testRemuteNotifiesOthersAndKeepsPictureInPictureSession`

`AudioTransition.plan` 的步骤列表（取消静音的四步、再静音时画中画内外的步骤差）留在组 `audio`（A2）。这里不重写一份，避免 C3 依赖 A2。

遮挡和最小化只作为输入保留，决策不读它们。老板键和锁屏只在 mac 上停播并关画中画。

## 测试归属

类名与 Swift 文件名一致，方法同名，`split-lifecycle.tsv` 没有数据行。

`skip-lifecycle.tsv` 五条 `deferred:C9`，都在 `ChallengeGateTests`：三条 `StreamDecoder`（C4），两条 `CatalogAPI.fetch`（C5）。源码不引用这些类型。

另外两条行为的 Swift 测试不在本组文件里，Kotlin 仍写了同名方法，避免本包类型没有断言：

- `AwayRetry.noteAfterCancel`：`AwayRetryTests.testNoteAfterCancelSwitchesWaitingNotes`。原测试在 `EndpointBackoffTests`，L0a 已标 `deferred:C9`。
- `ChallengeResume.merging` / `droppingSiteSearch`：`ChallengeGateTests` 里的 `testChallengeResumeMergingKeepsSiteSearch`、`testDroppingSiteSearchIntent`。原测试在 `SearchFix13Tests`（组 cross，C9）。

`shouldFire` 的代次是 Kotlin `Int`（32 位）。Swift 是 64 位 `Int`。测试用的数很小。

## 前台恢复顺序

隐藏优先于暂停，暂停优先于失败。用户名为空（或 null）且可见、未暂停、未失败时落到 `stayReleased`，不重新起播。`shouldPlay` 不看 `activity`，所以首次解析、切房、刷新中只要其余条件成立就 `requestPlay`，并把原来的 activity 带回去。
