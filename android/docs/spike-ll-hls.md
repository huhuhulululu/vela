# P1 低延迟 HLS 播放管线对比

测量日期：2026-11-18。Media3 1.11.1，Robolectric 4.18.1，`@Config(sdk = 34)`，编译 JDK 21。播放器在 Robolectric 的 PAUSED 主线程里跑，播放线程和加载线程保持不暂停；Android 时钟按真实 `nanoTime` 以不超过 30 毫秒的步长对齐。伪上游是 JDK `HttpServer`，只绑 `127.0.0.1`、端口由系统分配。分片是合成的 2 秒 MPEG-TS（160×90 H.264 Baseline + AAC），按序号平移 PCR/PTS。不访问真实站点。

缓冲按计划：`DefaultLoadControl` 的 min/max 都是 6000 毫秒，起播 1000 毫秒，再缓冲 2000 毫秒。除「默认偏移」那一列外，直播目标偏移设为 3 秒。静音用 `TRACK_TYPE_AUDIO` 禁用，不是把音量设成 0 之后还留着音轨。

低延迟播放列表带 `CAN-BLOCK-RELOAD`、`PART-TARGET=0.5`、`PART-HOLD-BACK=1.0`，不含 `CAN-SKIP-UNTIL`（没有实现 delta skip，所以下面的请求数不含 `_HLS_skip`）。去标签与 `PlaylistLadder.steadyPlaylist` 相同：丢掉 `EXT-X-SERVER-CONTROL`、`EXT-X-PART`（含 `PART-INF`）、`EXT-X-PRELOAD-HINT`、`EXT-X-SKIP`、`EXT-X-RENDITION-REPORT`，版本改成 7。长连接指服务端挂起不少于 200 毫秒的响应。发布抖动是每个完整分片的间隔在 T 和 1.5×T 之间均匀抽取，T = 2 秒。

## 原始数字

出画之后的墙钟 60 秒（请求按这一窗口计，不是用虚拟时间折算的）：

| 项 | 原生低延迟 | 去掉低延迟标签 |
|---|---:|---:|
| 请求数 | 269 | 61 |
| 每分钟请求数 | 268.6 | 61.0 |
| 长连接数（挂起 ≥ 200 毫秒） | 119 | 0 |
| 峰值并发 | 2 | 1 |
| 视频播放列表请求 | 119 | 32 |
| 媒体分片请求（含 part） | 150 | 29 |

原生侧大约每 0.5 秒一次阻塞刷新（119 次长连接都落在播放列表上）。去掉标签之后播放列表约每 2 秒一次，没有长连接。

静音、主列表带独立音轨，出画之后 60 秒：

| 项 | chunkless 开（默认） | chunkless 关 |
|---|---:|---:|
| audio.m3u8 | 0 | 0 |
| 音频分片 | 0 | 0 |
| 视频播放列表 | 31 | 32 |

403，单档。默认 `DefaultLoadErrorHandlingPolicy` 包了一层计数，分片返回一次 403：

| 项 | 值 |
|---|---|
| `getFallbackSelectionFor` | code=403，locations=1，tracks=1，type=none |
| `getRetryDelayMsFor` | count=1，delayMs=0，dataType=1（媒体） |
| `onPlayerError` | 0 |
| 之后画面 | 仍在渲染（85 帧） |

受控续期：播放列表和分片改为 403，数据源在加载线程上等 1.5 秒、清掉 403 再重试一次（播放器看不到 403）：

| 项 | 值 |
|---|---|
| `onPlayerError` | 0 |
| 最大画面空窗 | 106 毫秒 |
| 加载错误 | 无 |
| 再缓冲次数 | 0 |

R1 / R2 / R3。播放列表地址是 `vela://lane/a/video.m3u8`（续期对播放器不可见）。「真实」= 分片行是绝对 http 地址；「合成」= `vela://lane/a/seg/<序号>`，序号表每次读到列表就更新。R1 在 `PlaylistResetException` 时原地 `stop` + 重新 `prepare`，60 秒内最多 2 次。空窗是标记场景之后 `renderedOutputBufferCount` 的最大间隔。

| 场景 | 地址 | 播放错误 | PlaylistReset | PlaylistStuck | 重新 prepare | 错误后恢复出画 | 最大空窗 |
|---|---|---:|---:|---:|---:|---:|---:|
| R1 序号回退 + DISCONTINUITY | 真实 | 1 | 1 | 0 | 1 | 5563 毫秒 | 5639 毫秒 |
| R1 | 合成 | 1 | 1 | 0 | 1 | 5594 毫秒 | 5668 毫秒 |
| R2 续期后序号不前进 | 真实 | 0 | 0 | 0 | 0 | 无错误 | 410 毫秒 |
| R2 | 合成 | 0 | 0 | 0 | 0 | 无错误 | 413 毫秒 |
| R3 序号相同、最后分片地址变了 | 真实 | 0 | 0 | 0 | 0 | 无错误 | 416 毫秒 |
| R3 | 合成 | 0 | 0 | 0 | 0 | 无错误 | 414 毫秒 |

R1 的恢复时间是从 `onPlayerError` 到下一帧，不是标记后的第一帧（那一帧在错误之前，大约 100 毫秒）。ExoPlayer 的 `HlsMediaPlaylist.isNewerThan` 只看序号和分片个数，不看 `DISCONTINUITY`，也不看分片 URI。序号大幅回退时快照不更新，并记 `PlaylistResetException`，随后变成播放错误。两种分片地址一样。

`NetworkTypeObserver` 单例（`getInstance` 就会去注册连接广播）：

| 播放器 | 是否创建单例 |
|---|---|
| 自定义固定码率 BandwidthMeter，平台诊断开着（默认） | 是 |
| 同上，并且 `setUsePlatformDiagnostics(false)` | 否 |
| `DefaultBandwidthMeter` | 是 |

只换 BandwidthMeter 避不开。API 31+ 的 `MediaMetricsListener` 在上报网络变化时也会 `getInstance`。两个都关，单例不会出现。

直播目标偏移，去标签，发布间隔抖动到 1.5×T，墙钟 60 秒。默认偏移不设置 `LiveConfiguration`，媒体 3 的回退是 3×TARGETDURATION = 6 秒：

| 项 | 目标偏移 3 秒 | 默认 |
|---|---:|---:|
| 再缓冲次数 | 17 | 14 |
| 最大空窗 | 1123 毫秒 | 1129 毫秒 |
| 播放错误 | 0 | 0 |
| 渲染帧 | 493 | 512 |

1.5×T 抖动、去标签、目标偏移 3 秒、墙钟 10 分钟（测试耗时 600.4 秒），不自动重建：

| 项 | 值 |
|---|---:|
| PlaylistStuck | 0 |
| PlaylistReset | 0 |
| `onPlayerError`（记为一次重建） | 0 |
| BehindLiveWindow | 0 |
| 再缓冲 | 205 |
| 最大空窗 | 1136 毫秒 |
| 渲染帧 | 4764 |

## 对照第 1 节

| 判据 | 结果 | 结论 |
|---|---|---|
| R1 两种分片地址都达不到「原地重新 prepare、60 秒内至多 2 次、10 秒内出画」 | 两种都是 1 次重新 prepare，约 5.6 秒恢复出画，播放器回到 READY | 达标 |
| R2 两种都达不到「不报错、序号再前进时采纳、画面不中断」 | 两种都是 0 次播放错误，空窗约 0.4 秒（正常帧间隔量级），之后继续渲染 | 达标 |
| R3 两种都达不到「10 秒内无加载错误、画面持续」 | 两种都是 0 次加载错误、0 次播放错误，空窗约 0.4 秒 | 达标 |
| 受控 403 续期后出现 `onPlayerError`，或画面停滞超过 8 秒 | 播放错误 0，最大空窗 106 毫秒 | 达标 |
| 伪上游按 1.5×T 抖动发布，10 分钟内 PlaylistStuck 或重建多于 1 次 | PlaylistStuck 0，播放错误 0 | 达标 |
| 静音起播后稳态 60 秒 audio.m3u8 不超过 1 次，音频分片为 0 | chunkless 开、关都是 0 和 0（窗口从首帧起算，准备阶段不算进这 60 秒） | 达标 |
| 只换 BandwidthMeter 能否避开 `NetworkTypeObserver` | 不能；再关平台诊断就可以 | 能避开，但要两处一起改 |

回退判据一条都没有成立。不回退到移植中继。A3 的模拟器实测是第二个判定点，这次没有做。

## 给 P2 的结论

- 分片用真实绝对地址即可。R3 不需要合成序号地址：播放器在序号和分片个数不变时不会因为 URI 变了而重新取分片，也不会报错。合成地址没有改变 R1、R2、R3 的结局。
- R1 要由这一路自己处理：`PlaylistResetException` 变成播放错误后原地重新 prepare，回到直播边缘。实测 1 次就够，大约 5.6 秒出画。这不是 403，不应该走续期。
- 静音只关音轨。出画之后音频列表不再刷新，不必在静音时从主列表拿掉音轨，也不必为取消静音重建主列表。chunkless 开或关对这 60 秒没有差别。
- 403：单档时默认策略不排除轨道（没有第二条 location / track），按临时错误重试，第一次延迟是 0。续期放在 `DataSource.open` 里吃掉 403，播放器不会 `onPlayerError`，空窗远小于 8 秒。
- 低延迟标签应去掉。同一上游上，60 秒请求从 269 降到 61，长连接从 119 降到 0。
- BandwidthMeter 用固定估计，并且 `ExoPlayer.Builder.setUsePlatformDiagnostics(false)`，否则 `MediaMetricsListener` 仍会拉起 `NetworkTypeObserver`。
- 直播目标偏移维持 3 秒。和默认 6 秒比，60 秒里再缓冲 17 对 14，最大空窗都是约 1.1 秒，没有播放错误。多出来的次数不大，和 iOS 的 `configuredTimeOffsetFromLive = 3` 对齐。

## 和计划的差别

- 低延迟播放列表没有 `CAN-SKIP-UNTIL`，请求数不含 delta skip。阻塞刷新和 part 已经说明低延迟侧的长连接和请求频率。
- 静音对比的 60 秒从首帧开始。准备阶段那一次如果发生，在窗口之外；判据允许的就是这一次，稳态里没有后续请求。
- 「画面恢复」在 R1 用错误之后的下一帧，不用标记后的第一帧。标记后约 100 毫秒还有错误前已经在飞的帧。
