# iOS 三窗计划

> 状态：计划 v1（2026-11-18）。用户裁决：最多 3 路；iOS 先做，macOS 维持单窗，安卓在其双窗里程碑后按 3 路版本移植。依赖：`fix/dual-recent-skip`（双窗共享「最近划走」记录）合入 main 之后开工。

## 冻结区块（需求边界与验收，批准后不可自改）

**需求边界**
- 竖屏可选 1、2、3 路，上下等高叠放，每格 `.resizeAspect` 不裁切、模糊缩略图垫底。横屏一律单窗（与双窗现行规则一致）。
- 设置项由「双窗」开关改为「1 / 2 / 3 路」选择，默认 1，记住选择；旧的 `phoneDual=true` 迁移为 2。
- 只有焦点路出声；焦点切换按现有 `AudioFocusController` 顺序执行，不出现两路同时出声。
- 任一时刻各路房间两两不同；共享「最近划走」记录对 3 路同样生效。
- 画质：2 路维持「两路最高档」；3 路按每格显示尺寸选档（`LaneQuality` 的 displaySize 规则）；热降档与回升对每路生效。
- 画中画只给焦点路；进入时释放其余各路，退出恢复；3 秒未进入则全停。
- 无画中画时进后台全停、网络归零；回前台各路按 §3.2 恢复。
- 减少路数或转横屏：只保留焦点路，其余释放；恢复竖屏与原路数后用 `requestPlay(lane:)` 恢复。
- macOS 行为不变。

**验收标准**
- T-1：`swift test` 全绿；VelaPhoneTests 全绿且数量不少于合入前基线。
- T-2：模型层测试覆盖：3 路两两不重复的全部既有场景（屏蔽、取消收藏、搜索装入与退出、切来源、列表只剩 1 到 3 个、空列表续页恢复、reveal 目标在另一路）；焦点路操作只作用于焦点路；路数增减与横竖屏切换的释放与恢复；`phoneDual` 迁移。
- T-3：iPhone 与 iPad 模拟器 3 路静音实跑（固定测试流）：三格首帧、`lane-rooms` 两两不同、`display-check` 全部 `uncropped=true`、回桌面无画中画时 5 秒内服务端字节为 0、iPad 画中画只剩焦点路联网。
- T-4：G-mac 三轮中位数合格，空闲合格（共享代码改动）。
- T-5：CI 五项全绿；双路审计与盲审无未处理的 high。

## 设计要点

- `LaneID` 新增 `.tertiary`，提供 `allCases` 与按路数取前 N 个的 `active(count:)`；不改 `.primary`、`.secondary` 名字（现有约 700 处引用保持可读）。
- `LaneSelection`：凡「另一路」改为「其他各路集合」；`moved`、`filtered`、`merged`、`revealed`、`replacement`、`refill` 都以「其他各路当前房间 ∪ 最近划走」为排除集；替补顺序固定为 primary → secondary → tertiary，保证确定性。
- `StageModel+Lanes`：路数状态取代布尔双窗；增减路数时按顺序创建或释放 `PlaybackLane`；焦点不可落在已释放的路上。
- 视图：`PhoneDualStage` 泛化为 N 格等高；格间距与现双窗一致。
- 画质：3 路时每路按格子显示尺寸选档；不新增定时器。
- 画中画控制器仍只挂焦点路一个（ADR-007 结论不变）。

## 任务（本机，顺序执行）

| 任务 | 范围 | 验证 |
|---|---|---|
| R1 LaneID 与选房泛化 | `Sources/VelaCore/LaneCursor.swift`、`LaneSelection.swift` 与测试 | T-1、T-2 模型部分 |
| R2 舞台与设置 | `Shared/Stage/StageModel+Lanes.swift` 等、`SettingsStore`、迁移 | VelaPhoneTests |
| R3 视图、画质、画中画 | `iOS/PhoneStageView.swift`、双窗视图、`PictureInPicture+Phone.swift`、热降档接线 | T-3 |
| R4 收尾 | `docs/ARCHITECTURE.md`、`docs/PERF.md`、ADR | T-4、T-5 |

## 公开方案调研

| 出处 | 借什么 | 不借什么 |
|---|---|---|
| 本仓双窗实现（PR #5，ADR-006、ADR-007） | 焦点出声、单画中画控制器、按尺寸选档、释放与恢复流程 | — |
| Apple「AVPlayerLayer 多实例」与 HLS 自适应码率说明 | 小窗口选低档以降带宽与解码负载 | 多画中画（系统只支持一个） |
