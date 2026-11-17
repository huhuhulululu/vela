# 性能基线与预算

## 测量方法

`scripts/perf-probe.sh [--duration 秒] [--app 路径] [--build]`。先 `defaults write` 打开年龄确认（`ageConfirmed`）并静音（`muted`），记下原状，结束后原样恢复；用 `open -g -j -n` 后台隐藏启动，不抢前台、不截屏、不发输入。启动前先开 `log stream --level info`（只看 Vela 进程），以 PlayerController 在 Release 下也会打的 `audio tracks=N enabled=1` 为「开始播放」标志计算启动耗时（`first-frame` 日志只在 DEBUG 构建里有，不可用）。稳态再等 5 秒后，每 5 秒采样：`ps` 取 RSS，`footprint <pid>` 取 Physical footprint，`top -l 2 -s 2` 取第二次采样的 %CPU、线程数，空闲唤醒（idle wakeups）用两次采样的累计值之差除以间隔。子进程归属判定：`launchctl procinfo` 需要 root，改用 libSystem 的 `responsibility_get_pid_responsible_for_pid`（python3 ctypes 调用），ppid 为 1 或本 App 的进程里 responsible pid 等于本 App 的才算子进程；其中 `VTDecoderXPCService`（硬解 XPC）单列为 `vtdecoder_cpu_avg`，不计入 `children_*`（那是 WebKit 等其他子进程）。统计 `Publishing changes from within view updates` 与日志行数。中继刷新只能数 `relay` 分类下的 `renew ok/failed`（URL 过期后的换票）；清单抓取本身只在慢或失败时才打日志，无法按次数统计，所以 `relay_refreshes_per_min` 目前恒为换票频率，不代表清单轮询频率。

注意：`log stream` 本身会放大系统框架的日志量与少量 CPU；`app_log_lines` 含 CFNetwork、CoreMedia 等系统子系统的行，Vela 自己（`com.vela.stage`）的日志在 180 秒内只有个位数到几十行。

### 新增维度（网络、请求数、空闲场景）

`--scenario play|idle`（默认 play）；`--idle-method lock|hide`（默认 lock）。

- 网络：每个采样周期末尾抓一份 `nettop -x -n -L 1 -p <pid> -J bytes_in,bytes_out` 累计快照。不能让 nettop 常驻并发：实测 nettop 在跑时 `top` 的 IDLEW 列冻结，唤醒数会变成 0。nettop 的进程行只是存活连接之和，连接关闭后会回落，不能做首末差；因此按连接计：每条连接取最后一次出现的累计值减去首个快照里的值，连接在快照间关闭时最后不足一个采样间隔的字节会漏记，总量是下限。输出 `net_in_mb_per_min`、`net_out_kb_per_min`，并按 tcp、udp（QUIC）、回环分列，附前 5 个远端地址。nettop 只有字节与远端 IP，没有 URL，清单、分片、缩略图、目录接口无法按请求类型细分，PTR 反查也无结果（CDN 地址无反向域名）。
- 请求次数：`log stream` 里 CFNetwork 在每个 URLSession 任务结束时打一行 `Task ... summary for task ... response_bytes=N`，窗口内的条数即请求数，得到 `http_requests_per_min` 与 `http_resp_mb_per_min`；再按响应大小粗分：小（小于 4 KB，清单、接口）、中（4 KB 到 256 KB，目录 JSON、缩略图）、大（256 KB 以上，分片），属启发式，不是按 URL 分类。AVPlayer 的 access log 在稳态下基本不更新（40 秒内只有起播时一条），不可用于估算。数值与 nettop 字节互相印证（同一次 120 秒：响应合计 23.2 MB/min，nettop 22.7 MB/min）。
- 空闲场景：先正常起播并等稳态，再让 App 进入非播放态，等 10 秒后采样。代码里没有 URL scheme、AppleScript、`defaults` 开关可触发暂停（暂停、老板键只有 UI 与全局热键）。`hide` 方式（`osascript` 经 System Events 设 `visible` 为 false）对本探针无效：`open -j` 启动的 App 本来就是 `visible=false`，窗口遮挡状态没有变化，实测命令成功返回但网络与 CPU 毫无变化；要让它生效得先把窗口显示出来，会打扰屏幕，故不用作默认。默认的 `lock` 方式：向分布式通知中心投递 `com.apple.screenIsLocked`（`python3` + PyObjC），Vela 的 `SystemActivity` 监听它并置 `systemInactive`，经 `PlaybackPolicy.allowed` 走和老板键、窗口遮挡同一个播放闸门（`applyPower`），不碰窗口、不需任何权限、无界面副作用；结束前投递 `screenIsUnlocked`。它验证的是「闸门关闭后是否停干净」，没有覆盖 `didChangeOcclusionState` 这条触发路径本身。

环境：m424，Release 构建，直播静音播放，2026-11-17，基线 commit b701881。

## 基线（180 秒 x 2 轮）

| 指标 | 第 1 轮 | 第 2 轮 | 均值 |
|---|---|---|---|
| cpu_avg (%) | 7.26 | 6.46 | 6.86 |
| cpu_p95 (%) | 8.50 | 7.90 | 8.20 |
| cpu_max (%) | 8.70 | 8.00 | 8.35 |
| rss_avg_mb | 114.0 | 119.6 | 116.8 |
| footprint_avg_mb | 45.2 | 46.9 | 46.1 |
| wakeups_avg (次/秒) | 56.5 | 50.1 | 53.3 |
| threads | 23 | 25 | 24 |
| children_rss_mb（不含 VT） | 0.0 | 0.0 | 0.0 |
| children_cpu_avg (%) | 0.00 | 0.00 | 0.00 |
| vtdecoder_cpu_avg (%)（参考） | 2.01 | 1.86 | 1.94 |
| runtime_issues | 0 | 0 | 0 |
| startup_to_play_s | 3.89 | 3.89 | 3.89 |
| relay_refreshes_per_min（仅换票） | 0 | 0 | 0 |
| app_size_mb | 10.7 | 10.7 | 10.7 |

### 新增维度基线（HEAD a5b1ed3，应用代码同 b701881）

| 指标 | play 120 秒 | idle 60 秒（lock） |
|---|---|---|
| cpu_avg (%) | 6.27 | 2.11 |
| cpu_p95 (%) | 6.80 | 3.20 |
| wakeups_avg (次/秒) | 61.8 | 41.0 |
| footprint_avg_mb | 41.7 | 42.8 |
| threads | 23 | 13 |
| vtdecoder_cpu_avg (%) | 2.00 | 0.00 |
| net_in_mb_per_min | 22.65 | 0.00 |
| net_out_kb_per_min | 24.1 | 0.0 |
| net_tcp_in / udp_in (MB/min) | 22.65 / 0.00 | 0.00 / 0.00 |
| net_loopback_kb_per_min | 0.0 | 0.0 |
| http_requests_per_min | 215.6 | 0.0 |
| http 小 / 中 / 大 (次/分) | 138.1 / 38.7 / 38.7 | 0 / 0 / 0 |
| http_resp_mb_per_min | 23.16 | 0.00 |
| runtime_issues | 0 | 0 |

要点：播放态流量几乎全部来自一个远端（IPv6 CDN 前缀 2607:6003:2305，TCP 443，占 99% 以上，约 23 MB/min，折合约 3 Mbps）；大响应（分片）约 39 次/分，约 22 MB/min，其余约 177 次/分的小、中请求只占约 1 MB/min，请求次数主要由小请求（清单、接口）贡献。QUIC（UDP 443，Cloudflare）连接存在但稳态零字节。回环流量为 0。码率会随自适应档位变化：同日 40 秒的两次短测为约 37 MB/min，120 秒这次为约 23 MB/min，作预算时需看区间。

空闲结论：停播后网络、HTTP 请求、硬解（VTDecoder）确实归零，线程数从 23 降到 13；但 CPU 没有归零，仍有约 2.1%（`ps` 的 cputime 在 30 秒内增加 0.73 秒可交叉印证），唤醒约 41 次/秒，比草案目标（CPU 0.0%、唤醒不高于 1 次/秒）差得远。对空闲态主进程做 `sample`（5 秒）显示所有线程几乎都在 `semaphore_wait` / `semaphore_timedwait` 里，非空闲样本仅 7 个，与 `top` 的 2% 不一致，原因未查明（可能是内核态或 sample 看不到的突发唤醒，也可能是 AVPlayer 项目未释放导致 CoreMedia 与音频队列线程仍有定时唤醒；两者都是推断）。下一步应查：停播时是否释放 AVPlayerItem、是否有 CADisplayLink 或 Timer 仍在跑。

第 1 轮中途跑了一次 `sample`（约 5 秒），可能略微抬高该轮 CPU。播放稳态下没有 WebKit 子进程。

## 预算目标（草案，基于上面基线，待评审）

| 场景 | 指标 | 草案目标 | 基线 |
|---|---|---|---|
| 播放态 | cpu_avg | 不高于 5% | 6.9% |
| 播放态 | footprint_avg_mb | 不高于 50 MB（防回退） | 46 MB |
| 播放态 | children_rss_mb（WebKit 等，不含 VT） | 稳态 0；出现侧车时不高于 60 MB | 0 |
| 播放态 | wakeups_avg | 不高于 40 次/秒 | 53 |
| 空闲态（无播放，lock 触发） | cpu | 0.0% | 2.1%（未达标） |
| 空闲态 | wakeups | 约 0（不高于 1 次/秒） | 41（未达标） |
| 空闲态 | 网络 / HTTP 请求 | 0 | 0 / 0（达标） |
| 播放态 | net_in_mb_per_min | 不高于 30 MB（防回退，随档位浮动） | 22.7 到 37 |

## 热点清单（按预期收益排序）

依据：播放稳态下对主进程 `sample <pid> 5`（5 秒、约 3600 次/线程），非空闲叶子样本合计约 294，与约 8% 单核吻合。Vela 自己的 Swift 代码占比极小（PlaylistRelay、BridgeView 等合计个位数样本）。

| # | 热点 | 模块 | 依据 | 备注 |
|---|---|---|---|---|
| 1 | 主线程 SwiftUI/AppKit 布局：`NSHostingView.layout` 下 `ViewGraphRootValueUpdater.render`、`AppKit _findAnySubviewNeedingAutoLayoutEngine` 递归遍历子视图，主线程非空闲样本 97，是最热线程 | SwiftUI / AppKit | 实测（样本）；「播放期间视图被反复标脏」是推断 | 排查播放中哪些 @Published / 动画在持续触发布局，视图树层级可压缩 |
| 2 | 视频显示路径：coremedia imagequeue 线程 45、CVPixelBuffer 释放与 IOSurface、`vtdecoder-callback-queue` 10，外加系统 `VTDecoderXPCService` 约 1.9% | AVPlayer / VideoToolbox | 实测 | 绝大部分是解码与上屏必需开销；收益主要来自降低码率或分辨率（现有 quality 分档的 low 档），属权衡而非优化 |
| 3 | 网络栈：`com.apple.network.connections` 31、`networkbuffering` 21、NSURLSession 10、CFNetwork LoaderQ，含 QUIC 解密（`libquic` gcm）与本地回环 `__recvmsg/__sendmsg` | PlaylistRelay / NWListener / URLSession | 实测（样本）；回环转发占比为推断 | 中继每个分片和清单都过一次本地 socket，可评估减少拷贝与轮询次数 |
| 4 | 音频：`audio.IOThread.client` 18（AudioUnitRender、TimePitch） | AVPlayer 音频 | 实测 | 静音下仍在渲染；播放速率若恒为 1，可确认没有走 TimePitch 路径（推断） |
| 5 | 日志：`os_log` 约 16 样本，日志约 160 行/秒（主要是系统框架，探针的 `log stream` 会放大） | os_log | 实测（比例）；放大效应为推断 | 非探针场景下大概率可忽略 |
| 6 | 指针轮询 Timer（0.4 秒，tolerance 0.2）`BridgeView.updatePolling`，仅在「离开后淡出」开启且窗口可见时存在 | 窗口桥接 | 代码 + 1 个样本 | 约 2.5 次/秒唤醒，占总唤醒不足 5%；可改事件驱动，收益小 |
| 7 | 缩略图、清单改写、WebKit 侧车 | 对应模块 | 本次稳态样本中均未出现 | 稳态下不是热点；侧车与缩略图的启动期、切换房间时开销本次没有测，需另测 |

未测：切换房间瞬态、侧车启动期；`didChangeOcclusionState` 触发路径（需要窗口真实被遮挡）。


## r1 静音不取音频

默认就是静音。基线 sample 里音频 `IOThread` 仍有约 18 个非空闲样本（AudioUnitRender / TimePitch），中继也会后台刷新 `audio.m3u8`，AVPlayer 继续下音频分片。这一轮让静音时不再做这三件事。

做法：本地主列表仍然带独立音轨（`EXT-X-MEDIA` 不删；删过一次之后取消静音就没有声音）。静音起播把 `appliesMediaSelectionCriteriaAutomatically` 关掉。可听组若 `allowsEmptySelection`，就不选中该组（已经选中的再 `select(nil, in:)`），中继停掉这一路的后台刷新。播放器若仍来要 `audio.m3u8`，按需取还在。取消静音时同步选回默认音轨、关掉 `isMuted`；只有「音轨确实被拿掉过且正在播放」才 `seek` 到直播边缘。不允许空选择，或读不到可听组：重新打开自动选择，静音只靠 `isMuted`，不重载播放项，这时没有性能收益。

预计收益（待 macOS 实测）：音频渲染线程、音频分片下载、音频清单轮询。视频路径不动。若组不允许空选择，数字应与基线持平。

探针：静音且音轨被主动拿掉时，开始播放日志仍是 `audio tracks=N enabled=N`，末尾多一个 `held`（N 是可选项个数，不是正在渲染的轨）。`perf-probe.sh` 的正则不用改。一开始就没选中时不会另打 `clear`，看 `held` 即可。播放中才静音、当时有选中项，会打 `audio selection clear`。`audio selection pick` 是重新选上，`audio selection automatic` 是退回 `isMuted`，这时不要期待 CPU 下降。

风险：空选择若让画面不起播，16 秒起播看门狗会报「迟迟没有开始」，这次改动应整段丢弃。取消静音时若走了直播边缘 seek，画面会短暂停一下再对齐（偏移仍是播放项上的 3 秒）；暂停中取消静音不 seek。音频清单停刷之后子列表地址会闲置过期，取消静音的第一次 `audio.m3u8` 可能 403，再走现有的续期。组在起播后几秒还没读到时，音轨日志会先复查再记 `held`，`startup_to_play_s` 可能比基线多约 3 秒，不代表画面更晚。

### r1 静音不取音频 实测

环境：Release 构建（合并 bc3f5db 后，含 b567d06 编译修复），2026-11-17，play 120 秒 x 2 轮，idle（lock）120 秒 x 1 轮，静音。同时只有一个 Vela 实例。

| 指标 | 基线 play | r1 play 第 1 轮 | r1 play 第 2 轮 | 基线 idle | r1 idle |
|---|---|---|---|---|---|
| cpu_avg % | 6.3 到 6.9 | 5.12 | 5.10 | 2.11 | 2.10 |
| wakeups_avg /s | 53 到 62 | 61.8 | 67.1 | 无 | 25.2 |
| threads | 23 到 24 | 19 | 19 | 无 | 12 |
| http_requests_per_min | 215.6 | 107.9 | 107.3 | 无 | 0 |
| net_in_mb_per_min | 22.7 | 36.4 | 21.7 | 无 | 0 |
| startup_to_play_s | 3.89 | 4.37 | 3.91 | 无 | 3.88 |
| runtime_issues | 0 | 0 | 0 | 0 | 0 |

结论：
- CPU 约降 20%（6.3 到 6.9 降到 5.1），线程少 4 到 5 个，HTTP 请求数减半（audio.m3u8 轮询与音频分片没了）。
- 网络字节没有降：net_in 取决于直播当时的视频码率档（第 1 轮 36.4、第 2 轮 21.7），音频本身只占很小一部分，不能据此判优劣。
- 唤醒数持平（61.8、67.1，基线 53 到 62），未见下降；idle 与基线持平，符合预期（idle 本就不播放）。
- 起播：每轮日志均有 `audio selection clear` 和 `audio tracks=1 enabled=1 held`，画面起播（startup_to_play_s 有值），无 16 秒看门狗失败。
- 日志每轮有一条 `error-log -12640 CoreMediaErrorDomain`，本次未对基线版本复测，不能确认是否由本改动引入；runtime_issues 为 0。

## r1 重绘

播放稳态（无操作）下主线程那 97 个布局样本，对应的是 SwiftUI 视图图被反复标脏，不是解码本身。这一轮只动会在稳态里反复发生的失效。

- LIVE 圆点原来用 SwiftUI `repeatForever`（1.05 秒 ease-in-out，不透明度 0.4↔1）。停播、信息块只是透明度变成 0、App 被隐藏时动画都不停，每帧驱动 `NSHostingView` 整帧布局。macOS 实测：只关掉它，停播后 CPU 从 2.47% 降到 0.10%，唤醒从约 45 次/秒降到约 2 次/秒；播放时约省 1.5%。现只在「当前页 && 这一间正在播 && 信息可见」时脉冲，并用这个条件做 `.id`，条件变假时拆掉旧视图（只把动画设成 nil 时，已在跑的 `repeatForever` 可能不停）。条件为假时圆点静止。仍在播且信息可见时，脉冲改走同色同时长的 `CABasicAnimation`，不再每帧标脏 SwiftUI。减弱动态效果时仍是静止实心圆。
- 指针轮询 Timer（0.4 秒）停播后照常跑。启动条件加上不在 `systemInactive`、`bossHidden`、`NSApp.isHidden`。手动暂停且窗口可见时保留轮询，指针移入控制栏才能淡入。`applyPower` 重算播放闸门后会再判一次；恢复轮询时立刻对一次指针位置。
- `timeControlStatus` 每次变成或停在 playing，都会把同一个 `readyUsername` / `failed = false` 写进 `@Published`（Release 没有 10 秒周期时间观察者，那只在 DEBUG）。`RoomCanvas` 以前直接观察播放器，同值发布也会整页重绘。画面改观察 `PlaybackFace`，只有就绪房间或失败标志变了才通知。`notePlaying` 在房间没变时不再重排停留和画质（播放成功该清的跳过计数和失败标记仍清）。同值赋值走 `AssignIf`：不用 `inout`，因为 `inout` 返回时总会写回。
- `WindowBridge.apply`、`PlayerNSView.layout`、播放层 `updateNSView` 只在窗口层级、集合行为、点击穿透、layer frame、视频重力真正变化时写入。

预期：主线程布局样本明显下降，`cpu_avg` 随之下降。唤醒、解码、网络、音频不在这次范围。控制栏上的材质如果自己跟着画面每帧合成，探针里还会留下一部分主线程时间，这次没有改材质。

行为：圆点仍以同样节奏脉冲；视频淡入、失败重试、静音默认、取消静音、收藏、搜索、控制栏显隐的判定没改。风险：图层曲线用的是标准 ease-in-ease-out（控制点与 SwiftUI `.easeInOut` 相同），起拍从 0.4 走向 1，相位可能和旧动画差半拍；若稳态下 `timeControlStatus` 几乎不重复回调，播放状态镜像省下的是抖动时的重绘，圆点才是这块样本的大头。

## r1 重绘 实测

构建：`verify/redraw`（基线 96ab581 + 交付 822e972、466cb9e），Release，`scripts/perf-probe.sh --duration 120`，静音，本机后台隐藏运行。

| 场景 | cpu_avg | cpu_p95 | wakeups/s | threads | vtdecoder cpu | runtime_issues | 起播 |
|---|---|---|---|---|---|---|---|
| play 第 1 轮 | 3.86% | 4.80% | 30.9 | 24 | 1.95% | 0 | 4.49 秒 |
| play 第 2 轮 | 3.99% | 4.30% | 32.5 | 23 | 1.94% | 0 | 3.92 秒 |
| idle（lock） | 0.02% | 0.20% | 1.5 | 12 | 0 | 0 | 3.92 秒 |
| 基线 play | 6.3–6.9% | | 53–62 | 23–24 | | | |
| 基线 idle | 2.11% | | 41 | | | | |

- play：cpu 约降 40%（6.6% 到 3.9%），唤醒约降 45%（57 到 32/s）。idle：cpu 2.11% 降到 0.02%，唤醒 41 到 1.5/s，圆点动画和指针轮询的唤醒都消失。
- 播放态 `sample <pid> 5`：主线程 3658 个样本全部停在 `mach_msg2_trap`（RunLoop 空闲），非空闲样本 0，基线约 97。剩余非空闲热点（样本数）：`coremedia.imagequeue.coreanimation.common` 74、`vtdecoder-callback-queue`（XPC 回调进 VideoToolbox）32、`audio.IOThread.client`（AudioToolbox `IO_PerformOutput`）24、`coremedia.videomediaconverter` 14、`libdispatch` 协作线程各 1 到 3。全是解码、上屏、音频必需路径，App 自己的代码已不在热点里。
- 另一轮带 `sample` 的 play（60 秒）cpu 5.28%、vtdecoder 3.90%，是码流和 sample 自身开销导致，不作对比口径。

