# 技术决策记录（ADR，只追加）

格式：背景、决定、理由与代价。已落地的决定不改写，推翻时追加新条目并在旧条目末尾注明被取代。

## ADR-001 代码共享方案 C′：两个 Xcode 目标直接编译共享源码（2026-11-17）

- 背景：iOS 版要复用 macOS 版的模型、播放与界面，同时保持 macOS 版的编译方式与行为不变。
- 备选：A 全靠 `#if os`（平台判断散到各处）；B 全部做成 SwiftPM 包（要改 macOS 的编译方式，还要给大量成员加 `public`）。
- 决定：选 C′。`Vela`（macOS）与 `VelaPhone`（iOS）两个 Xcode 目标直接编译 `Shared/` 与 `Sources/VelaCore/`；SwiftPM 只多一个 `VelaRelay` 目标（路径 `Shared/Relay`，只含 `PlaylistRelay`，依赖 `VelaCore`），用来在 `swift test` 里跑真实回环端口测试。`PlayerController` 与 `SiteSidecar` 放 `Shared/Playback`，不进 SwiftPM。
- 平台差异：同名符号在 `App/`（多数为 `*+Mac.swift`）与 `iOS/*+Phone.swift` 各写一份，每份只登记进自己的目标；共享代码直接用这个名字，不写 `#if os`、不加协议。`Shared/` 不得依赖 AppKit / UIKit / Carbon，有 grep 门禁（见 `docs/ARCHITECTURE.md` 第 1 节）。
- 理由：macOS 的编译方式与成员可见性不变，风险最小；没有为跨模块访问而批量加 `public`；平台差异落在文件边界上，编译期就能发现漏实现。
- 代价：Xcode 工程要显式登记每个源文件（`Shared/` 与 `Sources/VelaCore/` 两个目标各登记一次），新增或重命名后必须跑 `xcodebuild`，`swift test` 发现不了漏登记；`Shared/Stage` 与 `Shared/Playback` 没有 SwiftPM 测试，只能靠 `xcodebuild` 与 `VelaCore` 下沉的纯逻辑保证。`VelaRelay` 的源码与测试整个文件包在 `#if canImport(Network)` 里，使 Linux 上也能编译。

## ADR-002 两路共享站点级状态（2026-11-17）

- 背景：iOS 竖屏双窗有两路播放（`primary`、`secondary`）。两路都要访问同一个站点，有一批状态是否按路隔离需要定下来。
- 决定：以下状态两路共享，不按路拆分：`feed`、`taste`、`curation`、`watchLedger`、`PreparedCache`、侧车、`sidecarUntil`（侧车冷却）、`challengeGate`、`EndpointBackoffBox`。toast 与收藏提示跟随焦点路。
- 代价（接受）：一路被限流，另一路会连带进入退避。具体是 `EndpointBackoffBox` 的 30 秒非 JSON 退避、`sidecarUntil` 的侧车冷却、`challengeGate` 的连续质询次数，都按接口或按站点记，不分路。
- 理由：两路面对的是同一个站点、同一个出口 IP，站点的限流与质询判定本来就不分路。分路记账只会让一路在另一路已被拦住时继续撞同一堵墙，既浪费请求又加重限流。
- `PreparedCache` 的键是用户名加档位上限：只在热状态降档前后，缓存里按旧上限预取的地址与新上限失配，会多一次重新解析；这是预期行为，不当成缺陷。
- 影响：定位「两路同时卡住」时先看这几处共享状态，不要当成两路各自的故障。

## ADR-003 实跑门口径（2026-11-17）

- 背景：App 层改动无法靠单元测试验证性能，合并前须在本机实跑 `scripts/perf-probe.sh` 并用 `scripts/perf_gate.py` 对基线判定。第一版口径在真实数据上误报或漏报，2026-11-17 用同一构建的多轮实测校准。
- 决定：
  1. CPU 看自身：`cpu_app = cpu_avg − vtdecoder_cpu_avg`，要求不超过基线 `cpu_app × 1.15 + 0.3` 个百分点；总 `cpu_avg` 只打印不判。
  2. 起播：`startup_to_play_s > 0` 且不超过基线中位数 + 1 秒；基线缺中位数时退回单值并打印警告。
  3. wakeups 只作 idle 门（绝对阈值，不放宽）；play 场景只打印，`cpu_avg > 0` 而 wakeups 恰为 0 仅在 stderr 警告，不拒绝。
  4. 实测一律经 `ssh localhost` 回环运行；基线取同一构建 play 共 5 轮的中位数。缺字段、NaN、`skipped`、`samples=0`、起播不大于 0 一律不合格，`runtime_issues` 必须为 0。
- 证据摘要（2026-11-17，`origin/main` 构建，Release，静音，各轮 60 秒，详见 `docs/PERF.md`）：
  - 各房间码率不同，硬解 XPC（`vtdecoder`）的 CPU 在 1.6% 到 2.0% 间波动，会淹没 App 自身的变化，所以只比自身 CPU；基线 5 轮中位数 `cpu_avg` 2.42%、`vtdecoder` 1.59%、`cpu_app` 0.81%。
  - 同一份代码单轮起播波动约 1.1 秒以上，其中一轮 4.51 秒疑似断流重连，单值加 0.5 秒会误判；改用 5 轮中位数 1.74 秒加 1 秒。
  - wakeups 在 main 自身 5 轮里是 0.0 到 24.0 的双峰（工具 shell 里的 3 轮是 3.2、16.0、24.9），与码流对应的网络入量（约 11 与 35 MB/min 两档）有关的可能性最大，未做对照，不适合当 play 门；idle 下数值稳定（锁屏后约 20 秒内 0.5/s），保留作门。
  - 工具里的 shell 被降优先级只跑能效核，CPU 只有约 27% 性能，wakeups 可能读成 0；与 `ssh localhost` 回环对比后确认须走回环。并行跑模拟器会污染读数，先前一版（play wakeups 0.0、idle 6.0/s）因此作废。
- 代价：play 场景的唤醒回退不会被拦住，只能靠 idle 门与 `cpu_app` 间接发现。

## ADR-004 仓库迁到公开仓，CI 用 GitHub 运行机，播放实测留本机（2026-11-17）

- 背景：项目从私有仓库迁到公开仓 `huhuhulululu/vela`，要用 GitHub 提供的 macOS 运行机做持续集成。
- 决定：
  1. 迁移用快照历史：公开仓首个提交是迁出时的代码快照，之后的提交作者应使用 GitHub noreply 地址，不暴露个人邮箱（feat/ios 分支上早期提交仍带旧作者，合并前须核对，见 PR 说明）。
  2. 公开仓禁写内容：本机绝对路径、个人邮箱、开发团队 ID、私有网络名与内部主机别名、凭据与令牌、任何真实用户数据都不得进入代码、文档、提交信息与 PR。新文字提交前自查，旧文字发现即清。
  3. CI 交给 GitHub 的 `macos-latest` 运行机，只跑编译与逻辑测试：`swift test`、实跑门负样本回归、macOS Debug 与 Release 构建、iOS 模拟器构建与测试（不签名）。
  4. 播放实测（起播、CPU、唤醒、流量）留在本机，不进 CI。
- 理由：数据中心 IP 访问目标站点会被限流或质询，结果不可复现；且在公共运行机上批量请求第三方直播内容有内容条款风险。编译与纯逻辑测试不触网，适合放 CI。
- 代价：性能与播放回归只能靠本机手动实跑与 ADR-003 的门，CI 全绿不代表播放正常；合并 App 层改动前仍须本机实测。

## ADR-005 实跑门改用固定测试流，CPU 取主进程口径（2026-11-17）

- 背景：审计发现 ADR-003 的口径有三处问题。1) `perf-probe.sh` 的 `cpu_avg` 只统计 Vela 主进程，`VTDecoderXPCService` 是独立进程、单独统计，而门里的 `cpu_app = cpu_avg − vtdecoder_cpu_avg` 把两个进程相减，另一个进程的负载会抵消主进程的回退（错误口径）。2) 采样失败（`top` 找不到进程、`footprint` 失败）补 0 仍计入 `samples`，会空成功；脚本提前退出时不删旧结果文件，调用方可能读到上一轮的结果。3) 每轮随机直播房间，码率不同，主进程 CPU 跟着码率变（本次对照：11 到 37 MB/min 的三轮，cpu_avg 2.83 到 3.16），活直播不适合做回归门。
- 决定：
  1. 撤销 ADR-003 第 1 条里「扣除硬解」的部分：play 的 CPU 直接比较主进程 `cpu_avg`，不超过基线 × 1.15 + 0.3 个百分点；`vtdecoder_cpu_avg` 只打印。
  2. 回归门一律用固定测试流（`perf-probe.sh --fixed-stream`）：本机合成 HLS（1920x1080 30fps，约 3.1 Mbps 固定码率，视频档加独立音轨），由 `serve-test-stream.py` 在 127.0.0.1 提供；App 在 DEBUG 或 PERF_PROBE 构建里识别 `--perf-stream <地址>`，跳过目录与站点解析，其余走正常的中继与 `PlayerController`。基线与结果都必须是固定流模式（JSON 记 `stream_mode`、码率），模式不同拒绝比较。活直播只作观察。
  3. 采样失败记无效样本，不补 0、不计入 `samples`；无效占比 > 10% 或有效样本 < 5 即非 0 退出；门拒绝 footprint = 0、app_size = 0、样本数与时长不符；探针开头先删旧结果文件。
  4. Release 产物不得含该钩子：CI 的 Release 构建后跑 `scripts/check-release-no-perf-hook.sh`。检测依据是钩子里的长标识串与类型名，因为 Swift 把 15 字节以内的字符串编进指令，`strings` 搜不到 `--perf-stream` 本身（只搜它会假通过）。
  5. 占用检测与 `selftest.sh` 同一写法（按进程命令开头匹配），`--fixed-stream` 下每 30 秒等一次，最多 90 分钟；不匹配 `selftest.sh` 本身，因为它等占用时也会等 `perf-probe`，两边互等会死锁。
- 例外：钩子的两处调用（`StageModel.swift`、`StageModel+Playback.swift`）用了 `#if os(macOS) && (DEBUG || PERF_PROBE)`，违背 ADR-001「共享代码不写 `#if os`」。原因是钩子实现只在 macOS 目标里（`App/PerfStream+Mac.swift`），iOS 的 Debug 构建没有这个符号；不为一个调试钩子给 iOS 再加一份空实现。
- 证据（同一构建，60 秒各 3 轮，详见 `docs/PERF.md`）：固定流 cpu_avg 2.71、2.46、2.79（均值 2.65，样本标准差 0.17）；活直播 2.83、3.04、3.16（均值 3.01，样本标准差 0.17）。**两组标准差在 3 轮里没有明显差别**，固定流的收益是去掉码率与房间的混杂、免受外部断流重连与站点限流影响，而不是 3 轮就能看出的离散度下降；要量化需更多轮次。
- 代价：固定流是合成画面，不含真实站点的编码特征与网络抖动；中继的续期、403 路径测不到，仍靠 selftest 与人工实测。基线只覆盖 3 轮，噪声约 0.15 个百分点，门限 0.7 个百分点内可用。

