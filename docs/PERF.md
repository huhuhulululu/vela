# 性能基线与预算

## 测量方法

**实测一律经 `ssh localhost` 回环运行**：`ssh localhost "bash <仓库>/scripts/perf-probe.sh --duration 60"`。工具（如 Claude Code）里的 shell 被降优先级只跑能效核，CPU 与 wakeups 会失真（wakeups 可能读成 0）。脚本检测不到 `SSH_CONNECTION` 时在 stderr 打印醒目警告（不拒绝运行）。测量前确认没有 booted 模拟器、`perf-probe-ios.sh`、`xcodebuild` 与其他 Vela 进程。

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


## G-mac 基线（固定测试流，2026-11-17，现行）

**口径变更（ADR-005）**：实跑门改用固定测试流，CPU 直接比较主进程。原因：活直播每轮随机房间，码率不同；`vtdecoder` 是独立进程，用它去扣 `cpu_avg` 会让另一个进程的负载抵消主进程的回退。

固定测试流：`scripts/make-test-stream.sh` 用 ffmpeg 生成 testsrc2 彩条（自带计时），1920x1080 30fps，H.264 固定码率，平均约 3.1 Mbps，2 秒分片、240 秒素材，视频档加静音 AAC 独立音轨（`EXT-X-MEDIA`，与线上主列表结构一致）；`scripts/serve-test-stream.py` 把它按墙钟变成滑动窗口的直播列表（3 个分片，到尾回绕并带 `EXT-X-DISCONTINUITY`），只绑 `127.0.0.1` 的系统分配端口；生成物在 `/tmp`，不入库。`perf-probe.sh --fixed-stream` 自动生成、起服务，并以 `--perf-stream <地址>` 启动 App：钩子（`App/PerfStream+Mac.swift`，仅 DEBUG 或 PERF_PROBE 构建）跳过目录与站点解析，把一个测试房间塞进列表后走正常的 `playCurrent`、本地中继与 `PlayerController`，所以中继开销测得到。加 `--build` 时探针以 `SWIFT_ACTIVE_COMPILATION_CONDITIONS=PERF_PROBE` 构建 Release；Release 产物不含钩子（`scripts/check-release-no-perf-hook.sh`，CI 里跑）。注意 Swift 把 15 字节以内的字符串编进指令，`strings` 搜不到 `--perf-stream`，所以检测的是钩子里的长标识串。

基线来源：origin/main 没有钩子，无法在它上面测，改用 feat/ios 1375e19 合并本分支后的 Release（PERF_PROBE 构建，与 Release 只差测试流钩子），经 `ssh localhost` 回环，`--fixed-stream` play 60 秒 x 3 轮取中位数，idle 60 秒 1 轮（编排者裁决从 120 秒 x 5 轮缩减：固定流本身稳定）。逐轮值在 `docs/perf-baseline-fixed.json`。判定：`python3 -I scripts/perf_gate.py docs/perf-baseline-fixed.json <结果.json> [--scenario idle]`。

| 轮次 | cpu_avg（主进程） | vtdecoder（独立进程，只打印） | footprint | wakeups/s | 起播 |
|---|---|---|---|---|---|
| 1 | 2.71% | 2.30% | 36.5 MB | 1.3 | 1.77 秒 |
| 2 | 2.46% | 1.92% | 35.3 MB | 1.5 | 1.22 秒 |
| 3 | 2.79% | 2.20% | 36.3 MB | 1.5 | 1.20 秒 |
| 中位数（基线） | 2.71% | 2.20% | 36.3 MB | 1.5 | 1.22 秒 |

`runtime_issues` 全 0，`app_size` 3.7 MB，每轮 12 个有效样本、0 个无效。idle（60 秒，锁屏后等 10 秒再采样）：cpu 0.0%、wakeups 0.0/s、网络 0，达标。判定规则：主进程 `cpu_avg ≤ 基线 x 1.15 + 0.3`（基线 2.71 即上限 3.42）；footprint ≤ 基线 + 5 MB；app_size ≤ 基线 + 0.3 MB；起播中位数 + 1 秒；`runtime_issues` = 0；wakeups 只作 idle 门。基线与结果都必须是 `stream_mode=fixed`，模式不同拒绝比较；footprint 或 app_size 为 0、有效样本 < 5、无效样本占比 > 10%、样本数与时长不符一律拒绝。

### 固定流与活直播的离散度对照（同一构建、同为 60 秒 x 3 轮）

| 模式 | cpu_avg 各轮 | 均值 | 标准差（总体 / 样本） | 网络入 MB/min |
|---|---|---|---|---|
| 固定测试流 | 2.71、2.46、2.79 | 2.65% | 0.14 / 0.17 | 0（回环；分片 22.7 到 23.4） |
| 活直播 | 2.83、3.04、3.16 | 3.01% | 0.14 / 0.17 | 11.4、22.0、36.6 |

**结论要如实写：这 3 轮里，固定流并没有让 cpu_avg 的标准差明显变小，两组几乎相同（样本标准差 0.17 对 0.17）。** 能确认的是：活直播这 3 轮的码率相差三倍（11 到 37 MB/min），cpu_avg 随码率单调上升（2.83 到 3.16），说明码率是混杂因素；固定流把它去掉了，且固定流恒定 3.1 Mbps、各轮分片流量一致（22.7 到 23.4 MB/min）。但 3 轮样本太小，噪声（约 0.15 个百分点）压过了码率带来的约 0.3 个百分点的差，不能据此说固定流离散度小。已有的旁证：main 上活直播 5 轮 60 秒的 cpu_avg 为 2.42、4.29、2.03、1.80、2.56（标准差约 1.0），其中第 2 轮疑似断流重连，固定流因为是本机服务，不会出现这类外部离群。要量化「离散度明显更小」，需要更多轮次，本次按裁决缩减未做。门限 `x 1.15 + 0.3`（约 0.7 个百分点）仍大于本次噪声（约 0.15），可用。

## 历史：G-mac 基线（main，活直播，已作废）

下面是 2026-11-17 早先在活直播上取的基线与口径（`cpu_app` 扣除硬解），已由上一节取代；保留作留痕。其中的 `docs/perf-baseline-main.json` 已删除，数据以下表为准。

构建：`origin/main`（6ad572f），Release，经 `ssh localhost` 回环运行 `scripts/perf-probe.sh --duration 60`，静音，play 共 5 轮，各指标取中位数（逐轮值见下表）。

判定口径（2026-11-17 编排者裁决）：
- CPU：`cpu_app = cpu_avg − vtdecoder_cpu_avg`，要求 `cpu_app ≤ 基线 cpu_app × 1.15 + 0.3` 个百分点；总 `cpu_avg` 只打印不判（各房间码率不同，硬解波动会淹没 App 自身变化）。结果缺 `vtdecoder_cpu_avg` 即不合格。
- 起播：`startup_to_play_s > 0` 且 `≤ 基线中位数 + 1.0 秒`（方案 A；同一份代码单轮起播波动约 1.1 秒以上，单值 + 0.5 秒会误判）。基线缺 `startup_median` 时退回单值并打印警告。
- 其余：footprint ≤ 基线 + 5 MB，app_size ≤ 基线 + 0.3 MB，`runtime_issues` = 0。**2026-11-17 编排者裁决：wakeups 只作 idle 门**（idle 保留绝对阈值 ≤ 2/s）；play 的 wakeups 只打印，play 场景 `cpu_avg > 0` 而 wakeups 恰为 0 仅在 stderr 警告，不拒绝。

| 轮次 | cpu_avg | vtdecoder | cpu_app | footprint | wakeups/s | 起播 | 网络入 |
|---|---|---|---|---|---|---|---|
| 1 | 2.42% | 1.77% | 0.65% | 35.5 MB | 18.8 | 1.20 秒 | 11.5 MB/min |
| 2 | 4.29% | 1.59% | 2.70% | 40.7 MB | 2.2 | 4.51 秒 | 36.6 MB/min |
| 3 | 2.03% | 1.22% | 0.81% | 40.4 MB | 0.0 | 1.74 秒 | 35.6 MB/min |
| 4 | 1.80% | 0.98% | 0.82% | 39.1 MB | 1.7 | 1.19 秒 | 35.0 MB/min |
| 5 | 2.56% | 1.83% | 0.73% | 36.9 MB | 24.0 | 1.78 秒 | 11.1 MB/min |
| 中位数（基线） | 2.42% | 1.59% | 0.81% | 39.1 MB | 2.2 | 1.74 秒 | 35.0 MB/min |

`runtime_issues` 全部为 0，`app_size` 3.5 MB。

观察：
- **wakeups 在 main 自身的 5 轮里就是 0.0 到 24.0 的双峰**，不是脚本或 shell 优先级问题：工具 shell 里的 3 轮也是 3.2、16.0、24.9。与码流（对应网络入 11 与 35 MB/min 两档）有关的可能性最大，未做对照。据此 wakeups 不作 play 门（上面的裁决）。
- 第 2 轮 cpu_app 2.70%、起播 4.51 秒，疑似该轮遇到断流重连，单轮离群；中位数基线不受其影响，但同样说明 5 轮的单次判定会有误报。
- idle：main 因 `LoadingLine` 在播放器释放（`paused-release`，约 31 秒）后空转（`repeatForever` 动画让 `NSHostingView` 每帧重排）不达标，热修分支 `fix/idle-loading-line`（PR #1）处理中；idle 绝对阈值不放宽，待合入后重测 idle 基线。

复核记录：先前一版（play wakeups 0.0、idle 6.0/s）受并行模拟器实验干扰，已作废。改动前后的 `perf-probe.sh` 在同一产物、模拟器全部关闭下对照 60 秒，play 2.93%/28.8 对 2.41%/27.0，idle 3.67%/19.9 对 3.55%/19.4，两版一致，脚本无问题。idle 偏高的证据：锁屏后前约 20 秒正常（cpu 0.0%、wakeups 0.5/s），`paused-release` 之后 cpu 升到 5% 以上、wakeups 约 30/s，`sample` 显示主线程 `NSHostingView.layout()` 每帧重排。

## iOS Spike（模拟器）

PR0 实验（计划修订 A1 / A2：不用真机，全程模拟器）。实验目标 `VelaSpike`（分支 `spike/ios`，代码不合并，打 tag 留档），探针 `scripts/perf-probe-ios.sh`（分析器 `scripts/perf-probe-ios-analyze.py`，负样本 `scripts/test-perf-probe-ios.sh`）。原始指标与关键日志行在 `docs/spike/`（`*.json` 为探针输出，`*.keylines.txt` 为脱敏后的关键日志，房间名一律替换为 `ROOM`，完整日志不入库）。

### 方法与环境

- 设备：iPhone 17 Pro 模拟器（iOS 26.2），PiP 另用 iPad Pro 11 英寸（M5）模拟器；宿主 Mac16,10（10 核）。构建：Release、`-sdk iphonesimulator CODE_SIGNING_ALLOWED=NO`。
- 只 `simctl boot`，不开 Simulator.app 窗口，不截屏不点击；Spike 的 `AVPlayer.volume` 恒为 0，全程无声。
- 采样：宿主上按 App 进程 pid，每 5 秒 `top -l 2`（CPU、线程、IDLEW 增量）、`footprint`、`ps` rss，`nettop` 累计字节（只计非回环连接，窗口基线取窗口起点快照）；日志用 `simctl spawn log config ... level:debug,persist:debug` 后 `log show`。
- 资源竞争：采样每轮检查 `perf-probe.sh` 与 `Contents/MacOS/Vela`，出现即退出码 3、该轮作废；两次因此作废（E2 单窗、E3 首轮），等安静后重跑。
- 直播是真实网络，每次起播的房间不同，所选档位不同。**跨轮比较必须按所选档位带宽归一**，下表同列出 `selected_bw_kbps`。

### 实验结果

| 实验 | 数据 | 结论 |
|---|---|---|
| E1 回环 HLS 与 ATS | 全部 `*.json` 的 `first_frame_s`、`item_failed=[]`；Info.plist 仅 `NSAllowsLocalNetworking` | 通过。`http://127.0.0.1:<端口>/…/master.m3u8` 在只放行本地网络的 ATS 下起播，所有轮次零 `item-failed`，无需 `NSExceptionDomains` 或 `ForMedia`（不触发需用户授权的第三步）。起播：`lane-start` 到首帧 0.5 到 1.0 秒，启动到首帧 1.2 到 1.5 秒（含目录与解析）。 |
| E2 双窗两路 10 分钟 | `e2-dual.json`（601 秒，118 样本）；对照 `e2-single.json`（300 秒，59 样本） | 双窗（3096k + 3096k）：cpu_avg 24.5%（p95 28.2%）、footprint 75.1 MB（峰值 79）、线程 31、wakeups 14.2/s（IDLEW 读数不稳，见 E6）、net_in 45.4 MB/min、**流量比 1.026**、重叠 0.992。单窗（5128k）：cpu 20.3%、footprint 66.9 MB、net_in 37.4 MB/min、流量比 1.019。两轮房间不同，按档位归一：cpu 都是约 3.96% 每 Mbps；footprint 双窗比单窗多 8.2 MB；net_in 比值 1.22 对应带宽和比值 1.21。**双/单 比值：cpu 1.21，footprint +8.2 MB，流量 1.22（按带宽归一为 1.0）。** |
| E2 附带异常 | `e2-dual-run1-renew403.json`（探针改进前的首轮，无健康日志，仅作观察） | 双窗（3096k + 7128k）约 2 分钟后出现持续 `fetch-failed http-403` 与 `renew failed`，cpu 由约 38% 掉到约 11%，流量比 0.454：Spike 的最简续期在换票失败后没有恢复，其中一路停摆。该轮作废，不进阈值。正式实现的续期必须有独立验证（计划已列）。 |
| E3 第三路 | `e3-third.json`（5128k + 7128k + 3096k，91 秒） | 模拟器上三路都起播并持续前进，零 `item-failed`、零解码器错误（模拟器用 Mac 解码器，**不代表真机的硬件解码并发上限**）。cpu 45.6%、footprint 126.7 MB、net_in 106 MB/min，流量比 1.212（窗口只有 91 秒，含新增第三路起播突发）。`PlaybackFailureClassifier` 白名单没有实测样本，继续只收候选码。 |
| E4 音频会话 | `e4-audio.keylines.txt`（`--audio-seq`，全程 volume 0） | 顺序符合 A2/7b：静音 = `playback + mixWithOthers` 且 active=true；取消静音 = 各路先静音，再 `setCategory(playback)`（mixWithOthers=false）、`setActive(true)`，最后取消目标路静音；切焦点先静音旧路再取消目标路；再静音 = 各路先静音，`setCategory(playback+mixWithOthers)`，`setActive(false, notifyOthersOnDeactivation)`，随后为 PiP 就绪重新 `setActive(true)`（混音）。全程无 `audio set-failed`。模拟器没有别的 App 的音频，`otherPlaying` 恒为 false，「不打断他人 / 对方恢复」只验证了调用顺序，未验证效果。 |
| E5 后台与返回 | `e5-background.json`（单窗，`--no-pip`，后台 30 秒） | 挤到后台后依次出现 `suspend release-lanes`、`relay listener stopped`、`audio deactivated`；5 秒后窗口内 cpu 0.13%、非回环流量 0、线程 26→8、footprint 45→30 MB，窗口内零中继请求。切回：`resume after=34s`，首帧 1.29 秒（含重新解析与选档）。 |
| E6 指标齐全性 | 探针输出的字段 | 齐全：cpu_avg/p95/max、footprint（均值与峰值）、rss、线程、wakeups、net_in/out、回环流量、所选档位、流量比、首帧（每路）、重叠、display-check、relay sources、样本数。**缺口**：wakeups 来自 `top` 的 IDLEW 增量，模拟器进程上读数不稳（同配置 0 到 14/s），只作参考，不进门；footprint 与 cpu 只含 App 进程，不含宿主解码与上屏的开销（真机上这部分在进程内外的划分不同）。 |
| E7 画面比例 | 所有 `*.json` 的 `uncropped_all`、`display_samples` | 横屏流单窗（窗格 402x658）、双窗（窗格 402x328），720p 与 1080p 各路 `display-check` 全部 `gravity=ResizeAspect`、`videoRect=402x226`（16:9）、`uncropped=true`。竖屏流：目录前 13 个房间都是横屏，没有专门去找，未验证。 |
| 热状态注入 | `e9-thermal.json`、`e9-thermal.keylines.txt`（启动后 40 秒注入 `.serious`，70 秒注入 `.fair`） | 逻辑正确：`.serious` 时两路按显示高度 226pt x 3 = 678px 取 1696k 档并重启（`lane-start cap=1696000 bw=1696000`），换档后 `relay sources` 不超过 2；`.fair` 持续满 60 秒（15:42.786）才回升到最高档，两路首帧都再次出现。真实热状态模拟器无法产生。 |
| E8 画中画（A2） | `e8-pip-*.json`、`e8-pip-*.keylines.txt` | **iPhone 模拟器不支持**：`isPictureInPictureSupported() = false`，回桌面后走整体挂起（与 E5 一致）。**iPad 模拟器支持**：单窗时回桌面约 1 秒后自动 `pip will-start`、`pip started`（静音 `playback + mixWithOthers` 下可用，`UIBackgroundModes=audio`），其余路释放；PiP 期间 `currentTime` 持续前进（`timeControlStatus=playing`），中继持续供应清单（40 秒内约 50 次），只有一路在联网：net_in 35.4 MB/min（5128k 档对应约 38 MB/min），cpu 15.8%、footprint 66.4 MB、线程 27（对比后台无 PiP 的 0.13%、0 流量、线程 8）；切回 App 时 `pip exit-on-foreground` 后 `pip stopped`，该路不重启（无首帧重来）。**双窗时（iPad）4 次尝试自动进入均未触发**（含 inactive 时先释放非焦点路）；`startPictureInPicture()` 手动调用得到 `AVKitErrorDomain -1001`（该轮日志里每路有重复的视图与控制器，不排除干扰）。该结论只来自模拟器，待真机复核；T18b 设计不能假定「双窗回桌面必能自动进 PiP」，需有失败回退（3 秒内未进入即整体挂起，已在 Spike 验证）。 |

### 哪些不代表真机

- 解码在 Mac 的硬件解码器上，CPU 与功耗绝对值、并发解码上限（E3）、热状态（只能注入）、PiP 的系统行为（尤其 iPhone 与双窗）都不能外推到手机。
- 网络是本机 Wi-Fi 到直播 CDN，不代表大陆网络。
- 可用的是同机同运行时的相对口径：cpu 按每 Mbps 归一、流量 ÷ 所选档位带宽之和、双/单 比值、后台归零与日志顺序。

### G-sim 阈值草案（按计划 §6 口径，待评审）

| 指标 | 实测基准 | 草案阈值 |
|---|---|---|
| cpu（按所选档位归一） | 3.96% 每 Mbps（E2 双窗与单窗一致） | 每 Mbps ≤ 4.75%（x 1.2） |
| cpu 绝对值（参考） | 双窗 3096k x 2 为 24.5%；单窗 5128k 为 20.3% | 随档位按上式折算，不用固定值 |
| footprint | 双窗 75.1 MB（峰值 79）；单窗 66.9 MB | 双窗 ≤ 90 MB；单窗 ≤ 82 MB（实测 + 15 MB） |
| 起播（lane-start 到首帧） | 0.5 到 1.0 秒；启动到首帧 1.2 到 1.5 秒 | ≤ 实测 + 1 秒（即 ≤ 2.0 秒 / 2.5 秒） |
| 流量比 | 1.02 到 1.04（10 分钟与 5 分钟窗口） | ≤ 1.15；窗口 < 2 分钟的轮次不按此判（起播突发） |
| 双窗重叠时长 | 0.992 | ≥ 90% |
| 后台（无 PiP）5 秒后 | cpu 0.13%，流量 0，线程 8 | cpu ≤ 0.5%，非回环流量 = 0，依次出现 suspend、relay listener stopped、audio deactivated |
| 画面比例 | 全部 uncropped=true | 横屏流所有 display-check 为 `ResizeAspect` 且 `uncropped=true` |
| relay sources | 最大值 = 当前路数 | N、M ≤ 当前路数 |
| 热状态注入 | `.serious` 降到 1696k；`.fair` 满 60 秒回升 | 日志序列同上 |

负样本（`docs/spike/negative-tests.txt`）：无样本退出码 6、采样中进程退出 5、起播超时 4、参数错 2、App 不存在 1，全部按预期非 0。

## iOS 单窗（模拟器）

T16 按计划修订 A1 改在模拟器上做（2026-11-17，分支 `feat/ios-pr2`，合入 T14、T15 与 `feat/ios` 第一阶段审计修复之后）。全程静音、只 `simctl boot`、不截屏不点击，所有结论来自日志与宿主进程指标。

### 方法与环境

- 构建：`VelaPhone` 的 Perf 配置（bundle `com.vela.stage.ios.perf`，`PERF_PROBE`），`-sdk iphonesimulator CODE_SIGNING_ALLOWED=NO`；启动参数 `--perf-autoplay`（只在内存里视为已确认年龄并强制静音，`VelaPhoneApp` 在首次 `bootstrap()` 前调用 `PerfEntry.applyLaunchArguments`）。
- 设备：iPhone 17 Pro 模拟器、iPad Pro 11 英寸（M5）模拟器，iOS 26.2，宿主同前。经 `ssh localhost` 回环运行，开跑前等本机无 perf-probe / selftest / 其他 xcodebuild / Vela / 模拟器实验。
- 探针：`scripts/perf-probe-ios.sh --profile vela --scenario background --duration 60 --bg-hold 30`（`--profile vela` 为本次新增：日志子系统 `com.vela.stage`，起播判据 `first-frame lane=<id>`；旧 Spike 用 `--profile spike`）。前台 60 秒采样（每 5 秒），随后 `simctl launch com.apple.Preferences` 挤到后台，5 秒后采 30 秒，再切回测恢复首帧。
- 直播是真实网络，两台设备起播的房间与档位不同（iPhone 这轮 1080p，iPad 这轮 720p）；正式 App 不打所选档位日志，`net_ratio` 无法计算。

### 首轮结果（`cd80e2e`）

| 项 | iPhone 17 Pro | iPad Pro 11（M5） | 结论 |
|---|---|---|---|
| 起播 | 首帧 0.50 秒（新播放项起计）；启动到首帧 2.02 秒 | 0.71 秒；2.42 秒 | 出现 `first-frame lane=primary`；启动到首帧在草案 ≤ 2.5 秒内 |
| 画面比例（日志判定） | `display-check` 2 条，`gravity=resizeAspect`，`presentation=1920x1080 bounds=1554x874 videoRect=1554x874 uncropped=true` | 2 条，`presentation=1280x720 bounds=2151x1210 videoRect=2151x1210 uncropped=true` | 日志判定全部通过，**但实际画面被裁切**，见下「发现的问题」1（已修复，复测见下） |
| 资源：前台单窗 60 秒 | cpu 平均 18.2%（p95 22.0%），footprint 74.2 MB（峰值 75），线程 29，下行 35.7 MB/min，上行 12.9 KB/min | cpu 10.3%（p95 13.7%），footprint 54.0 MB，线程 24，下行 20.7 MB/min | footprint 在单窗草案 ≤ 82 MB 内；cpu 无档位日志，无法按每 Mbps 归一 |
| 后台 5 秒后（30 秒窗口） | cpu 0.2%，非回环与回环流量 0，线程 29→8，footprint 37 MB | cpu 0.35%（峰 0.5%），流量 0，线程 24→8，footprint 38 MB | 通过：cpu ≤ 0.5%、流量 = 0；窗口内零中继请求 |
| 后台日志顺序 | `epoch suspend allowing=none` → `lane suspend lane=primary` → `audio step mute all` → `audio step category=playback mix=true` → `audio step active=false notify=true` → `audio deactivated` → `relay listener stopped` | 同左 | 通过：lane suspend、audio deactivated、relay listener stopped 依次出现，`setActive(false, notifyOthersOnDeactivation)` |
| 恢复 | `epoch resume`、`lane resume lane=primary activity=playing`，新首帧距切回 1.33 秒 | 同左，1.99 秒 | 通过 |
| 音频会话类别 | 前台全程没有任何 `audio step` 行；第一次设类别是在进后台时 | 同左 | **未通过**，见「发现的问题」2 |
| 画中画 | 不适用 | 无 `pip` 日志，回桌面走整体挂起 | iOS 画中画（T15b）尚未接入，本轮只验证了「无画中画时进后台全停」 |

### 发现的问题（首轮，均已修复）

1. **画面实际被裁切（违反裁决 5），且 `display-check` 判不出来。** 竖屏窗格是 402x874（iPhone）与 834x1210（iPad），首轮日志里播放层 `bounds` 却是 1554x874 与 2151x1210：宽度正好等于「按 16:9 铺满高度」。根因：垫底 `SwapBackdrop` 用 `scaledToFill()` 且外层 `.frame(maxWidth: .infinity, maxHeight: .infinity)` 在子视图比提议大时取子视图尺寸，把整个 `ZStack` 撑到缩略图铺满后的大小，播放层跟着变大，再被 `.clipped()` 裁掉两侧。`display-check` 只拿 `videoRect` 和播放层自己的 `bounds` 比，所以照样报 `uncropped=true`。**修复**（`e71d050`）：`SwapBackdrop` 改为 `Color.clear` 打底、图放叠加层再裁掉，只取父视图给的尺寸（两端共用；macOS `RoomCanvas` 同一根因，窗口比例与缩略图不同时同样会撑大，一并消除，铺满开关不变）；`PhoneRoomPage` 把垫底移到 `.background`。`display-check` 改以页面容器为准：`bounds` 是窗格尺寸，新增 `layer=`（播放层 bounds），`videoRect` 换算到窗格坐标；播放层与窗格任一边相差超过 1 点即 `uncropped=false`，分析脚本另出 `layer_matches_all`。回归测试 `PhoneRoomPageLayoutTests` 在旧布局下量到 1554 / 2151 而失败。
2. **前台起播时没有设音频会话类别。** 冷启动静音起播（含 `--perf-autoplay`）不经过 `AudioFocusController`，前台会话停在系统默认类别。**修复**（`daf5890`）：Core 新增 `AudioIntent.prepare(muted:)`，`StageModel.startPlayback` 开头经 `AudioFocusController.prepareSession(muted:)` 执行一次（静音：`playback` + `moviePlayback` + `mixWithOthers` 并激活；出声：`playback` 不混音）。
3. 口味面板页眉「偏好只保存在这台 Mac 上」等共享文案。**修复**（`a7c44e7`）：页眉与访问确认页改为两端通用，收藏夹空状态与收藏来源空列表按平台显示（iOS 不再出现「单击 / ⌘D」）。

### 修复后复测（2026-11-17，`a7c44e7`）

同上方法与设备（Perf 构建、`--perf-autoplay`、`scripts/perf-probe-ios.sh --profile vela --scenario background --duration 60 --bg-hold 30`，`ssh localhost` 回环），每台设备 3 轮。三轮都起到 1080p 房间（直播房间每轮不同）。

| 项 | iPhone 17 Pro（3 轮） | iPad Pro 11（M5）（3 轮） | 结论 |
|---|---|---|---|
| 画面比例 | 每轮 `display-check` 2 条，全部 `gravity=resizeAspect presentation=1920x1080 bounds=402x874 layer=402x874 videoRect=402x226 uncropped=true` | 全部 `bounds=834x1210 layer=834x1210 videoRect=834x469 uncropped=true` | 通过：播放层等于窗格，横屏流上下留边、完整显示；`layer_matches_all=true`、`uncropped_all=true` |
| 前台音频 | 每轮 `audio prepare muted=true` → `audio step mute all` → `audio step category=playback mix=true` → `audio step active=true notify=false`，均在 `display-check` 与 `first-frame` 之前 | 同左 | 通过：首帧前已是静音配置（混音并激活）。`setCategory` 在主线程耗时约 70 到 200 毫秒 |
| 起播 | 新播放项首帧 1.29 / 1.25 / 1.09 秒；启动到首帧 3.50 / 3.18 / 3.19 秒 | 1.03 / 0.62 / 1.03 秒；2.93 / 2.83 / 2.98 秒 | 都比首轮（iPhone 2.02、iPad 2.42 秒）慢，超出草案 ≤ 2.5 秒；对照见下，不归因于本次改动 |
| 资源：前台 60 秒 | cpu 平均 14.4 / 16.2 / 16.8%（p95 ≤ 26.1%），footprint 77.0 / 75.9 / 69.5 MB（峰值 ≤ 78），线程 23 到 25，下行约 34 到 35 MB/min | cpu 26.2 / 17.0 / 15.6%（p95 ≤ 37.3%），footprint 81.3 / 76.2 / 76.2 MB（峰值 82），下行 34.5 到 49.6 MB/min | footprint 在单窗草案 ≤ 82 MB 内，但 iPad 第 1 轮峰值正好贴上限（首轮 iPad 是 720p、54 MB，本轮 1080p） |
| 后台 5 秒后（30 秒窗口） | cpu 0.2%，流量 0，线程 →8 或 9，footprint 38 到 39 MB | cpu 0.18 到 0.28%，流量 0，线程 →8 或 9，footprint 39 到 43 MB | 通过，未退化 |
| 后台日志顺序 | 与首轮相同：`epoch suspend` → `lane suspend` → `audio step mute all` → `category=playback mix=true` → `active=false notify=true` → `audio deactivated` → `relay listener stopped`，`bg_order_ok=true` | 同左 | 通过 |
| 恢复 | `epoch resume`、`lane resume lane=primary activity=playing`，新首帧距切回 1.44 / 1.72 / 1.44 秒 | 2.05 / 1.87 / 1.85 秒 | 通过，与首轮（1.33、1.99 秒）同一水平 |

**起播对照**：为判断启动到首帧变慢是否来自本次改动，同一时段在 iPhone 17 Pro 上交替跑首轮构建（`cd80e2e`）与修复后构建，`--scenario single --duration 20` 各 2 轮：旧构建启动到首帧 3.51 / 2.65 秒（新播放项首帧 0.84 / 0.95 秒），新构建 3.21 / 3.23 秒（0.72 / 0.87 秒）。旧构建此刻同样超过 2.5 秒，差异在轮间波动以内，变慢来自网络时段（直播 CDN），不是本次改动；但样本少，初始转换在主线程的 70 到 200 毫秒是确定的新增开销，若真机上起播紧张，可考虑把会话设置挪出主线程。

### 画中画（T15b，2026-11-18，`c4c7aef`）

按修订 A3 改口径：画中画在 iPad 模拟器上验，iPhone 模拟器只验「不支持时不崩、回桌面照旧全停」。Perf 构建、`--perf-autoplay`（静音）、单窗，`scripts/perf-probe-ios.sh --profile vela`，`ssh localhost` 回环，每台 1 轮（新建设备，跑完删除）。画中画期间另用 `nettop -L 1 -p <宿主 pid>` 每 10 秒取一次逐连接快照。

| 项 | iPad Pro 13-inch（M5）`--scenario pip --duration 30 --bg-hold 40` | iPhone 17 Pro `--scenario background --duration 30 --bg-hold 30` | 结论 |
|---|---|---|---|
| 前台 | 首帧 0.55 秒（启动到首帧 4.06 秒），房间流 960x540，`display-check bounds=1032x1376 layer=1032x1376 videoRect=1032x581 uncropped=true`；cpu 平均 4.5%，footprint 45.8 MB，下行 11.3 MB/min | 首帧 0.89 秒，cpu 11.7%，footprint 76.3 MB，下行 35.3 MB/min（1080p 房间） | 直播房间每轮不同，两台数字不可横比 |
| 回桌面 | `simctl launch com.apple.Preferences` 后：`pip started` → `epoch suspend allowing=primary` → `pip continue lane=primary state=active`；无 `pip failed`、无 `pip fallback-stop` | 日志 `pip unsupported`（控制器未创建，按钮不出现）；`epoch suspend allowing=none` → `lane suspend` → `audio step … active=false notify=true` → `audio deactivated` → `relay listener stopped`，`bg_order_ok=true`，无崩溃 | 通过 |
| 后台期间音频 | 进入画中画时（静音）`audio step mute all` → `category=playback mix=true` → `active=true notify=false`，没有 `audio deactivated`、没有 `relay listener stopped` | 见上 | 通过：静音画中画保持 `playback + mixWithOthers` 会话 |
| 后台资源（5 秒后的窗口） | cpu 平均 7.7%（最高 9.6%），footprint 46.9 MB，线程 25，下行 10.7 MB/min（与前台同一水平，即仍只这一路） | cpu 0.2%，下行 0，footprint 40.2 MB | iPad 画中画里继续播；iPhone 全停 |
| 只一路联网 | 逐连接快照 3 次：持续增长的只有直播 CDN 的两条连接（分片连接 10.3 → 12.3 → 14.2 MB；清单连接 31.7 → 37.8 → 43.7 KB，每 10 秒约 6 KB，即中继按节拍拉子列表），目录与侧车的两条连接字节数不变；本地中继监听 `127.0.0.1` 一直在 | — | 通过：画中画期间只有画中画那一路在联网，中继持续供应清单；全程 `fetch-failed`、`serve-503`、续期失败均为 0 |
| 切回 App | `epoch resume` → `lane resume lane=primary activity=playing` → `pip stopped restore=true`（回前台时主动退出画中画），新首帧距切回 1.59 秒 | 新首帧距切回 1.58 秒 | 通过 |

未在本轮实跑的路径（由 `PictureInPictureWiringTests` 用桩覆盖）：3 秒内未进入画中画的回退全停、启动失败、后台里用户关掉画中画、自动进入开关关闭。模拟器上的画中画小窗本身不代表真机的解码与功耗。

### 第二阶段审计修复后复测（2026-11-18，分支 `fix/pr2-audit`，`5c82665`）

修复三方审计的 11 条发现之后（预取登记与取消、侧车加载前复查、后台与最后一路停播不改静音意图、迟到的画中画 willStart、旧恢复入口、iOS Presence、inactive 停留、收藏夹窄屏、Linux 文案、停用失败重试）的回归实跑。Perf 构建、`--perf-autoplay`（静音）、单窗，`scripts/perf-probe-ios.sh --profile vela --duration 30 --bg-hold 30 --teardown`，`ssh localhost` 回环，每台 1 轮，新建设备跑完删除；开跑前确认本机无其他 perf-probe / selftest / xcodebuild。

| 项 | iPhone 17 Pro `--scenario background` | iPad Pro 11（M5）`--scenario pip` | 结论 |
|---|---|---|---|
| 前台 | 首帧 1.36 秒，cpu 18.2%，footprint 72.3 MB | 首帧 0.52 秒，cpu 7.2%，footprint 55.0 MB | 直播房间每轮不同，数字不横比 |
| 后台 5 秒后（30 秒窗口） | cpu 0.23%，非回环与回环流量 0，footprint 40.2 MB；`bg_order_ok=true`（`epoch suspend allowing=none` → `lane suspend` → `audio step … active=false notify=true` → `audio deactivated` → `relay listener stopped`） | 画中画继续：下行 21.9 MB/min（只这一路），逐连接快照里后台窗口内**没有任何新连接**，字节增长的只有直播 CDN 那一条；目录、侧车连接不动 | 通过：后台无预取请求、无站点解析 |
| 后台侧车与解析 | 日志里 `start-source` 只出现在启动与切回之后，后台窗口内 0 条；无侧车加载 | 同左 | 通过 |
| 回前台 | `epoch resume` 之后先 `audio prepare muted=true` → `category=playback mix=true` → `active=true`（会话在后台已停用，`sessionPrepared` 复位后按当前静音状态重新准备），新首帧距切回 3.74 秒 | `pip stopped restore=true`，新首帧距切回 1.56 秒 | 通过；iPhone 这轮恢复比前几轮（1.4 到 1.7 秒）慢，单样本、活直播，未复测 |

未在本轮实跑的项：
- iPad「关掉自动进入画中画后回桌面全停」：试过启动参数 `-pipAutoEnter NO`，设置存在 `com.vela.stage` 套件里，参数域不生效，iPad 照样进了画中画；全停路径由 iPhone 这轮与 `PictureInPictureWiringTests` 覆盖。
- 出声相关（后台再回前台、最后一路停播、停用失败重试）：Perf 构建强制静音，不点击就无法出声，按要求只做单元测试（`AudioFocusControllerTests`）。
- 控制栏 2.4 秒收起：用状态断言 `LifecycleWiringTests.testChromeHidesAfterActivityTimeout` 验证。

### 哪些不代表真机

同「iOS Spike（模拟器）」一节：解码在 Mac 上，cpu 与功耗绝对值不能外推；网络是本机到直播 CDN。

## iOS 双窗（模拟器，PR3）

T18、T18b、T19 的实跑（2026-11-18，分支 `feat/ios-pr3`）。全程静音、只 `simctl boot`、不截屏不点击，结论来自日志、宿主进程指标与固定流服务端的响应字节。**用固定测试流，不访问真实站点**：`scripts/make-test-stream.sh` 生成合成 HLS（1080p，平均 3.10 Mbps，视频档加独立音轨），`scripts/serve-test-stream.py <目录> --port-file F --stats-file S` 在 127.0.0.1 提供并逐响应记录「时间 字节 文件名」；App 用 `--perf-stream <回环主列表> --perf-dual`（Perf 构建，两个测试房间 `perf-stream`、`perf-stream-2` 共用一个流，两路都走正常的中继与 `PlayerController`）。

### 方法与环境

- 构建：`VelaPhone` 的 Perf 配置，`-sdk iphonesimulator CODE_SIGNING_ALLOWED=NO`；设备为本次新建的 iPhone 17 与 iPad Pro 11 英寸（M5）模拟器（iOS 26.2），跑完删除；探针 `scripts/perf-probe-ios.sh --profile vela`，经 `ssh localhost` 回环运行。
- **联网按服务端统计，不按 nettop**：模拟器里 App 的回环流量不进 `nettop -p`，探针的 `net_in`、`net_loopback` 在这些运行里恒为 0，不能当「没联网」的证据（这是空成功的典型）。前台有流量、回桌面后归零，才说明服务端统计有效；下表的前台行就是正对照。
- 两路所选档位带宽之和（固定流平均 3.10 Mbps × 2）约 44.4 MB/min；流量比 = 实测 ÷ 44.4。分析器的 `overlap_ratio` 依赖 Spike 的健康日志，正式 App 没有这类日志，读数 0.0 不代表重叠为 0，不引用；两路同时在播由两路首帧都出现、服务端流量约为单路两倍佐证。

### 结果

| 项 | iPhone 17（`--scenario background`，前台 40 秒，后台 20 秒） | iPad Pro 11（M5）（`--scenario pip`，前台 30 秒，后台 20 秒） | 结论 |
|---|---|---|---|
| 两路首帧 | 两路都 0.58 秒（`first-frame lane=primary` 与 `lane=secondary`） | 0.25、0.28 秒 | 通过 |
| 两路房间不重复 | `lane-rooms distinct=true` 2 条，`distinct=false` 0 条（`lane_rooms_dup=0`） | 同左 | 通过（选房逻辑另有 `DualWindowTests` 的八类场景） |
| 画面比例 | 每格窗格 402x436，播放层 `layer=402x436`，`videoRect=402x226`，`gravity=resizeAspect`，`display-check` 4 条全部 `uncropped=true`（`layer_matches_all=true`） | 窗格 834x604，`videoRect=834x469`，4 条全部 `uncropped=true` | 通过：横屏流在半屏里上下留边、完整显示，没有裁切 |
| 起播档位 | 两路 `lane-start cap=-1`（不封顶） | 同左，回前台恢复的两路同样 `cap=-1` | 通过（裁决 3，S12） |
| 前台资源（服务端字节） | 45.2 MB/min，流量比 1.02；cpu 27.7%，footprint 115.4 MB（峰值 118），线程 36 | 45.7 MB/min，流量比 1.03；cpu 27.5%，footprint 115.8 MB | 通过：流量比 ≤ 1.15。cpu 是模拟器进程里两路 1080p 软件路径的开销，不外推真机 |
| 回桌面 | 无画中画（iPhone 模拟器不支持）：服务端字节切走后第 1 秒起恒为 0（20 秒窗口 0 请求），bg cpu 0.2%，线程 8，`bg_order_ok=true`（`lane suspend` → 音频停用 → `relay listener stopped`） | 画中画 `pip started`，只留焦点路：服务端 21.5 MB/min（前台的 47%，约一路），bg cpu 15.9%，`pip continue lane=primary state=active`；另一路 `suspend-now lane=secondary` | 通过（S15：只有一路联网） |
| 回前台 | `epoch resume`，两路 `lane resume`，首帧距切回 1.0 秒 | `pip stopped restore=true`，两路恢复，首帧距切回 1.3 秒 | 通过：回 App 恢复双窗 |

另一轮 iPad `--scenario background`（前台 30 秒，后台 20 秒）同样进画中画：服务端 19.4 MB/min（前台 45.9），`pip started`，`lane-rooms` 与 `display-check` 同上；iPad 上没有「无画中画」的全停实跑（关掉自动进入的启动参数不生效，见上节），全停路径由 iPhone 这轮和 `DualPictureInPictureTests` 覆盖。

### 热降档与回升（iPhone，`--perf-thermal serious@40,fair@70`，150 秒）

模拟器没有真实热状态，用启动参数注入；回升等待是真实的 60 秒。

| 时刻 | 日志 | 说明 |
|---|---|---|
| 起播 | `lane-start lane=secondary cap=-1`、`lane-start lane=primary cap=-1` | 两路最高档 |
| +40 秒 | `thermal limit state=2`，`thermal-cap lane=primary cap=1696000`，`thermal-cap lane=secondary cap=1696000`，随后两路 `thermal-retier cap=1696000` | 降档：每路画面 221 点 × 3 = 663 像素，查到 1.696M 档 |
| +70 秒注入 fair | 无 | 回落不立刻升 |
| +130 秒 | `thermal recover state=1`，两路 `thermal-cap cap=-1`，随后 `thermal-retier cap=-1` | 满 60 秒才回升（04:37:02 注入 fair，04:38:02 回升） |

固定流只有一个档位（约 3.5 Mbps），换档后选到的子列表不变，不能在带宽上看到降档效果；这里验证的是逻辑与时序（上限、换档请求、60 秒迟滞），带宽效果由 `LaneQualityTests`（`PowerPolicy` 查档）与 PERF「iOS Spike」的 E9 覆盖。

### 画中画对照实验（ADR-007）

| 组 | 配置 | 结果 |
|---|---|---|
| A（两轮）| 只给焦点路挂一个控制器，回桌面 | `pip started`；后台服务端 21.6、21.5 MB/min |
| B | 同 A，进入时不先释放另一路 | `pip started`；后台前 5 秒 40.6 MB/min（两路），随后 23.0 |
| C | 每一路都挂控制器 | `willStart` 后无 `didStart`、无 `failed`；全停并关小窗，之后 0 请求、cpu 0.3% |
| D1 / D2 | 前台手动启动：单控制器 / 每路一个 | D1 `pip started`；D2 `pip attach` 两次，调用后没有任何回调 |

### G-mac（macOS 主体不退化，固定流，Release，静音）

`scripts/perf-probe.sh --build --fixed-stream --duration 60`（首轮构建）与同参数各轮，`ssh localhost` 回环；idle 为 `--scenario idle --duration 60`。判定 `python3 -I scripts/perf_gate.py docs/perf-baseline-fixed.json <结果> [--scenario idle]`。开跑时本机没有别的 perf-probe、xcodebuild、Vela 与模拟器实验，只有一台别处启动、空闲的模拟器在 Booted 状态（CPU 0%，不是本次所建，未动）。

| 轮 | cpu_avg | footprint 均值 | 起播 | wakeups | runtime_issues | 门 |
|---|---|---|---|---|---|---|
| 1（构建后第一轮，冷启动） | 1.89% | 36.9 MB | 2.34 秒 | 0.0 | 0 | **FAIL**（只有起播一项：2.34 > 基线中位数 1.22 + 1.0；其余项通过） |
| 2 | 1.82% | 35.9 MB | 1.74 秒 | 0.0 | 0 | PASS |
| 3 | 1.82% | 36.3 MB | 1.72 秒 | 0.0 | 0 | PASS |
| 4（补一轮） | 1.77% | 36.1 MB | 1.74 秒 | 0.0 | 0 | PASS |
| 前三轮中位数 | 1.82% | 36.3 MB | 1.74 秒 | | 0 | 全部满足（基线 cpu 2.71%、footprint 36.3 MB、起播 1.22 秒） |
| idle（60 秒） | 0.0% | 31.4 MB | | 0.0 | 0 | PASS（cpu ≤ 0.1%、wakeups ≤ 2/s、流量 0） |

首轮冷启动偏慢与 ADR-005 记过的现象一致，按约定以三轮中位数为准并如实列出。本轮 cpu 低于基线的 2.71%，基线是另一时段、另一机器负载下的 5 轮中位数，这里只说明没有退化，不作改进的结论。

## iOS 三窗（模拟器，ios-triple）

计划 `docs/plans/ios-triple.md` 修订 v2 的 T-3、T-4 实跑（2026-11-18，分支 `feat/ios-triple`，代码 `1808479`）。全程静音、只 `simctl boot`、不截屏不点击；设备为本次新建的 iPhone 17 Pro 与 iPad Pro 11 英寸（M5）模拟器（iOS 26.2），跑完删除。探针 `scripts/perf-probe-ios.sh --profile vela --stream-stats <服务端统计>`，经 `ssh localhost` 回环运行，开跑前确认本机没有别的 perf-probe、xcodebuild 与 Vela。

### 方法

- **多档固定流**：`scripts/make-test-stream.sh <目录> 240 ladder` 生成 1080p 3000k、720p 1200k、540p 600k 三档（主列表 BANDWIDTH 3.45M / 1.48M / 0.73M，共用一条静音音轨），`serve-test-stream.py --stats-file` 在 127.0.0.1 提供并逐响应记录。App 用 `--perf-stream <回环主列表> --perf-lanes 3`（三个测试房间共用一个流，各走正常的中继与 `PlayerController`）。所选档位按服务端收到的分片文件名统计（`seg_0` 1080p、`seg_1` 720p、`seg_2` 540p）。
- **判定**：分析器（`scripts/perf-probe-ios-analyze.py`）读日志与服务端统计，要求三路首帧、`lane-rooms` 无重复且 `lanes=3`、`display-check` 全部 `uncropped=true` 且播放层等于窗格、前台服务端有字节；无画中画时回桌面 5 秒后服务端字节为 0、后台中继源数为 0；画中画时后台中继源数为 1、按视频列表请求率估算的联网路数不超过 1.5。不满足退出码 7。负样本 `scripts/test_perf_probe_ios_analyze.py`（缺第三路首帧、第三路重复、后台留源、后台仍有字节、画中画两路联网、服务端统计为空）全部判失败。

### 结果

| 项 | iPhone 17 Pro（`--scenario background`，前台 40 秒，后台 20 秒） | iPad Pro 11（M5）（`--scenario pip`，前台 40 秒，后台 20 秒） |
|---|---|---|
| 三格首帧 | 0.89、0.92、0.98 秒（primary / secondary / tertiary） | 0.51、0.61、0.61 秒 |
| 各路两两不同 | `lane-rooms distinct=true lanes=3` 2 条，重复 0 条 | 同左 |
| 每格尺寸与画面 | 窗格 402x290 点，`videoRect` 402x226，`gravity=resizeAspect`，`display-check` 6 条全部 `uncropped=true`，播放层等于窗格 | 窗格 834x402 点，`videoRect` 715x402（左右留边），7 条全部 `uncropped=true` |
| 选档（尺寸上限） | 三路 `lane-start cap=1696000`，前台分片全部是 720p，服务端 25.9 MB/min | 三路 `cap=3096000`，前台全部 720p，27.0 MB/min |
| 前台资源 | cpu 14.9%，footprint 95.2 MB | cpu 14.2%，footprint 94.6 MB |
| 回桌面 | 无画中画（iPhone 模拟器不支持）：5 秒后到切回前服务端字节为 0（另一轮测得最后一个请求在切走后 2.1 秒）；`relay sources=0`；bg cpu 0.25%；`bg_order_ok=true` | `pip started`、`pip continue lane=primary`，另两路 `suspend-now`；后台中继源 1 个，联网路数估算 1.08（前台三路 85.5 次视频列表 / 分，后台 30.9 次）；后台 24.9 MB/min，全是 1080p 分片：小窗只显示一路，尺寸上限回到不限 |
| 回前台 | 三路 `lane resume`，首帧距切回 0.88 秒 | `pip stopped restore=true`，三路恢复，首帧距切回 1.36 秒 |

对照（同一多档流、同一 iPhone 17 Pro，`--perf-lanes 2`，前台 30 秒，代码 `b8d82cf`）：两路窗格 402x436、画面 402x226，前台分片全部是 1080p，服务端 44.7 MB/min。即两路维持最高档（裁决 3），三路按格子选到 720p，下行约为两路的 58%。

实跑发现并已修复的两处（各有回归测试）：起播时把有限的上限当成「离开降画质」的低档，约 8 秒后每路无谓重建一次播放项（`22ad45b`）；iPad 冷启动时格子还没布局就起播，三路先按最低档 896k 起播再换档（`1808479`，改按舞台尺寸推算格子）。上表为修复后的数字，两台设备上都不再出现 `quality -> full` 重建与冷启动换档。

### G-mac（macOS 主体不退化，固定流，Release，静音）

`scripts/perf-probe.sh --build --fixed-stream --duration 60`（每轮都带 `--build`，首轮为构建后冷启动），`ssh localhost` 回环；idle 为 `--scenario idle --duration 60`。判定 `python3 -I scripts/perf_gate.py docs/perf-baseline-fixed.json <结果> [--scenario idle]`。G-mac 用默认单档固定流，与基线同口径。代码 `1808479`。

| 轮 | cpu_avg | footprint 均值 | 起播 | wakeups | runtime_issues | 门 |
|---|---|---|---|---|---|---|
| 1（构建后第一轮，冷启动） | 2.13% | 36.7 MB | 2.35 秒 | 0.0 | 0 | **FAIL**（只有起播一项：2.35 > 基线中位数 1.22 + 1.0；其余项通过） |
| 2 | 1.96% | 36.2 MB | 1.74 秒 | 0.0 | 0 | PASS |
| 3 | 2.00% | 36.0 MB | 1.73 秒 | 0.0 | 0 | PASS |
| 三轮中位数 | 2.00% | 36.2 MB | 1.74 秒 | | 0 | 全部满足（基线 cpu 2.71%、footprint 36.3 MB、起播 1.22 秒；app 3.9 MB ≤ 3.7 + 0.3） |
| idle（60 秒） | 0.0% | 31.8 MB | | 0.0 | 0 | PASS（cpu ≤ 0.1%、wakeups ≤ 2/s、流量 0） |

首轮冷启动偏慢与 ADR-005 及 PR3 记过的现象一致，按约定以三轮中位数为准并如实列出。同一天在 `b8d82cf` 上先跑过一组（中位数 cpu 1.33%、footprint 35.9 MB、起播 1.71 秒，首轮同样只有起播一项 FAIL），结论相同。

### 哪些不代表真机

模拟器用宿主机解码，cpu 与功耗绝对值不能外推，也不能证明 iPhone 三路硬解能力（修订 v2 H3 的结论边界）；真机三路持续播放由用户在 TestFlight 上确认。资源不足的降级与恢复路径只由单元测试覆盖，模拟器上触发不到 `decoderTemporarilyUnavailable`。热降档在三窗下的实跑没有做（`--perf-thermal` 注入），由 `LaneQualityTests` 与 `TripleLaneMediaTests` 覆盖。

### PR #8 审计修复后的复测（代码 `999e8cf`）

- **空主路**（iPhone 17 Pro，三路，`--perf-empty-primary 6`）：第 6 秒焦点交给第三路并屏蔽主路的房间，日志 `perf empty-primary primary=empty`；3 秒后副路向前划，`lane-rooms distinct=true lanes=3` 共 5 条、重复 0 条；回桌面 5 秒后服务端字节为 0、中继源 0 个。
- **热降档完整序列**（`--perf-thermal serious@15,critical@25,fair@30`，前台 100 秒）：
  - iPhone 三格（尺寸上限 1.696M）：serious `thermal limit steps=1`，各路换到 896k；critical `thermal step steps=2`，已是最低档不再换；fair 后满 60 秒 `thermal recover`，换回 1.696M（尺寸上限，不是不限）。
  - iPad Pro 11（M5）三格（尺寸上限 3.096M）：serious 换到 1.696M，critical `thermal step` 后三路再换到 896k（修复前这一步不会发生），满 60 秒回升到 3.096M。
- **G-mac**（同口径）：

| 轮 | cpu_avg | footprint 均值 | 起播 | runtime_issues | 门 |
|---|---|---|---|---|---|
| 1（冷启动） | 1.38% | 36.3 MB | 2.29 秒 | 0 | **FAIL**（只有起播一项） |
| 2 | 1.53% | 34.9 MB | 1.77 秒 | 0 | PASS |
| 3 | 1.68% | 34.8 MB | 1.73 秒 | 0 | PASS |
| 三轮中位数 | 1.53% | 34.9 MB | 1.77 秒 | 0 | 全部满足 |
| idle | 0.0% | 31.6 MB | | 0 | PASS |
