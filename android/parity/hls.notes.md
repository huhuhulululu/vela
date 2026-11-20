# C2 播放策略移植笔记

行为按 Swift 原样搬，除了计划点名的两处。下面是形状差异和这两处改动。

## 类型落点

都在 `vela.core.hls`：

- `PlaylistLadder`、`LadderVariant`、`PowerPolicy`
- `RenewalPolicy`、`RenewalBudget`、`RenewalFreshness`、`RenewalCoordinator`
- `PlaybackPolicy`、`SidecarIdlePolicy`、`PreparedCache`、`DegradedHold`
- `AudioPolicy`、`MuteAudioPolicy`、`NetworkFailure`
- `RefreshTracker`、`PlaybackNoteRule`、`LoadFollowUp`
- `PlaybackErrorKind`、`PlaybackErrorCopy`
- `DisplayFit`（`Size`、`Rect` 嵌在里面，对应 `CGSize` / `CGRect`）
- `PhoneThermalState`、`LaneQuality`
- `PlaybackFailureClassifier`
- `SkipTracker`

Swift 的值类型这里是类，方法就地改同一实例。时刻用 `Instant`，时长仍是 `Double` 秒。`dwellActive` 的房间名用 `Locale.ROOT` 小写，不用默认语言。

`URLError` / `NSError` 没有对应类型。传输失败是 `TransportError`，域默认 `NSURLErrorDomain`，码与 Swift 相同。错误链是 `FailureCode`。

## RenewalCoordinator

互斥是 `Mutex`。同一次续期是共享的 `CompletableDeferred`：加入方 `await` 返回时 `apply` 已经跑完。`cancel()` 增加代次、拒绝新注册，并取消在途任务；没写完的那次退回预算。`resume()` 只恢复接受注册，不复活旧代次。

`renewBlocking(timeoutMs)` 给加载线程用：

- 成功返回 true，并且 `apply` 已执行。
- 线程在进入前或等待中被中断：200 毫秒内返回 false，中断标志保留。不取消共享续期。
- `cancel()`：200 毫秒内返回 false，不等 `fetch` 自己结束，并且不会再 `apply`。
- 超时：返回 false，共享续期继续。调用方（P2）应传入 `RenewalPolicy.waitBound` 的 8 秒。

轮询片是 50 毫秒，所以取消和超时的察觉上限是一片，低于计划要求的 200 毫秒。

## 关音轨

`MuteAudioPolicy` 不再沿用「不允许空选择就留着音轨、只靠 isMuted」。静音时：

- 有选中项就 `Clear`，没有就 `Leave`，不看 `allowsEmptySelection`。
- `shouldSuppressAudioFetch` 在静音时恒为 true。
- 已加载、有可选项、选中下标为空，就记 `held`，同样不看是否允许空选择。

未静音的选择、默认项、seek 直播边缘、以及「轨还开着 / 组还没读到」的复查，仍与 Swift 一致。

因此这三条测试的断言和 Swift 不同，方法名没改：

- `testDisallowingEmptySelectionNeverClears`
- `testAudioFetchSuppression`（`allowsEmptySelection == false` 且静音时改为抑制）
- `testStillSelectedOrUnknownGroupDoesNotPretendAudioIsHeld`（选中下标已空、但不允许空选择，改为 held）

静音时主列表是否去掉音轨，等 P1 的结论，不在本包。

## 失败分类白名单

换成 Media3 1.11.1 `PlaybackException` 的两个码（当天对过 androidx/media 的 1.11.1 源码）：

- `ERROR_CODE_DECODER_INIT_FAILED = 4001`
- `ERROR_CODE_DECODING_RESOURCES_RECLAIMED = 4006`

域是 `androidx.media3.common.PlaybackException`。`ExoPlaybackException` 是子类，调用方要填这个域加 `errorCode`，不要填子类类名。

不进白名单、仍是房间重试的：`ERROR_CODE_DECODING_FAILED = 4003`、`ERROR_CODE_DECODER_QUERY_FAILED = 4002`。Swift 的 `AVFoundationErrorDomain / -11839` 也不再降级。单窗和主路的规则不变。

`PlaybackErrorCopy` 仍按 Swift 的 NSURL / AVFoundation / CoreMedia 码归类，测试逐条照抄。Media3 的 IO 码（2001、2002）没有映射进来；直接把 `errorCode` 送进来会落到 `other`。要换算的话另开一条，并改测试。

## 跳过

- `PlaybackRefreshTests` 的 `testFillStopsAtBlankPageCap` 引用 `FeedStore`，`deferred:C9`。
- 同文件的 `testOverlappingReloadsRollBackToLoadedCursor`、`testCommitMovesSnapshotOnlyOnSuccess` 引用 `PagingState`（`LoadSlot`，C5），同样 `deferred:C9`。计划只点了 FeedStore 那条；这两条不搬 `PagingState` 也编译不过。
- `PlaybackFlowFixTests` 的 6 条中继采纳登记 skip，`replaced-by` 指向 P2 的 `LaneDataSourceAdoptionTest` 的 r1、r2、r3。
- `RelayFeedTests`、`RelayWaitTests`、`RelaySourceTableTests` 整文件逐条 skip，理由是不移植，`replaced-by:vela.app.playback.LaneDataSourceTest`。

`testParseReadsDiscontinuityAndLastURI` 属于 L0b，这里不重复。
