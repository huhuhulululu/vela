# 安卓跨设备同步接线（S6）

语义对齐 Apple 端 `Shared/Sync/**`、`Shared/Stage/StageModel+Sync.swift` 与 `docs/plans/sync.md`（正文冲突处以第 23 节为准）。纯协议核心在 `android/core/src/main/kotlin/vela/core/sync/`（S4）；本文件记录 `:app` 层 `vela.app.sync` 的接线、约束与后续编排者收尾项。本文以代码为准。

## 模块布局

| 文件 | 职责 |
|---|---|
| `SyncConfig.kt` | minSdk 26 平台门槛（`SyncPlatformGate`）、运行期 P-256 五项探针与三级判定（`SyncProbe` / `SyncCapability`）、构建期服务端地址解析（`SyncConfig`）、入口可见性裁决（`SyncEntryPolicy`） |
| `AndroidSyncKeyStore.kt` | `AndroidKeyStore` AES-256-GCM 包裹密钥（别名 `vela_sync_wrap`，随机 IV，`setUserAuthenticationRequired(false)`）与专用偏好文件 `vela_sync`（单键 `space`，格式 `v1.<iv>.<ct>`），覆盖缺失 / 暂时不可用 / 永久失效三类读取结果 |
| `SyncCryptoCodec.kt` | 实现 `SyncEnvelopeCodec`：调用 `:core` 的 AES-256-GCM + raw DEFLATE 编解码、`e1.` 前缀检查、800,100 字节信封上限与 CSPRNG nonce |
| `SyncMarkedStateStore.kt` | 包装 `SyncStateFileStore`：状态文件存在时读前先落盘 `sync-state.json.corrupt` 标记，读成功再删；标记写不成时走只读解码，坏文件留在原处；标记在且状态文件已隔离时拒绝覆盖，直到 `delete()` 清除 |
| `SyncJoinJournal.kt` | 加入提交前的文件快照（`FileJoinJournal`）：同卷临时目录构建（0600 文件 + `absent-*` + `settings.json` + `complete`）后原子发布；写「已开启」后标 `committed`；失败/未定局重启时同卷原子换回并恢复「未设置」键，回滚严格检查 `SharedPreferences.Editor.commit()` 写盘结果，失败进入「需要修复」 |
| `SyncService.kt`、`SyncJoin.kt` | 第 12.1 节启动检查、第 12.2 节投影落盘屏障（经 `verifiedBarrierSink` 传播设置 `commit()` 失败）、周期单飞（协程 `Mutex` + 网络让出 `Semaphore`）、释放 `gate` 后串行派发文档变更通知、`createLocal` 先激活后发首轮周期、加入提交四条机制与全部中间态本机写入闸门 |
| `SyncHttpTransport.kt` | 基于平台 `HttpURLConnection` 的可注入传输层：`User-Agent: Vela-Sync/2`、不跟随重定向、不带 Cookie、不用缓存、ECDSA P-256 签名、响应体流式封顶 802,048 字节、单次请求总期限与协程取消即时断连中断阻塞 I/O，并交 `:core` `SyncHttp.interpret` 解析 |
| `SyncPrefs.kt`、`SyncStageAdapter.kt` | 复用 `com.vela.stage` 读写默认关闭的实验开关 `experimentSync` 及显式设置键；`StageSyncBridge.persistSettings` 使用单次可校验 `commit()` 落盘（不按内存相等跳过重试）；提供收藏/屏蔽/口味/停留/双设置的中间态阻断与 `perform` 辅助入口，远端设置投影不回声，回滚重读不污染未设置键（M9） |
| `SyncHub.kt`、`SyncPanel.kt`、`SyncStatusText.kt` | 界面状态机（`StateFlow<SyncUiState>`，含 `savedCode` 与 `revealedCode`）、由 `SyncHub` 自身长生命周期 scope 托管全部开启/加入/404创建/清除长事务（`SyncPanel` 不持有界面协程，离开面板/旋转/重建不打断）、离散触发与退避调度、`AndroidIcuSyncUnicode` 安全装入、查看同步码前强制设备身份验证（fail closed）与 `SyncClipboard` 敏感剪贴板标记（API 33+ `EXTRA_IS_SENSITIVE`） |
| `MainActivity.kt`、`LifecycleWiring.kt`、`StageScreen.kt`、`TasteSheet.kt`、`vela/app/stage/**` | 宿主启动调用 `SyncHub.createIfSupported(...)` 与 `attach()`，经 `LifecycleWiring` 转发前后台，经 `KeyguardManager.createConfirmDeviceCredentialIntent`（零新增权限）执行查看同步码前的设备锁屏验证，并将收藏、屏蔽、口味、停留与两个同步设置的写入全部接入中间态阻断与 `SyncHub.perform` |

## 中间状态与本机写入策略（对齐 Swift 侧权威文档不变量）

下表规定各状态下本机偏好写入（收藏、屏蔽、口味/不想看、停留记账改口味、`publicOnly`、`infoAlwaysOn`）的统一策略；**观看账本（`WatchLedger`）不在同步文档与加入快照内，所有状态下均照常记录**。

| 状态 | 判定条件 | 本机偏好写入策略 | 说明 |
|---|---|---|---|
| 未开启 / 实验开关关 | `experimentEnabled == false` 或 `phase == Off` 且无未收尾状态 | **允许（纯本机）** | 直接写本机文件与设置；实验开关关时不读 Keystore/状态文件 |
| 已开启（含创建激活后首轮网络中） | `hub.isOn && service.isActive` | **允许（走同步文档）** | `createLocal()` 在发起首轮网络前连续完成落盘、`activate()`、`savedCode` 发布与 `Hub` 激活；首轮网络期间写入直接经 `SyncHub.perform` 进入权威文档（`gate` 在网络期间让出），不被旧文档覆盖 |
| 创建未激活完 | `service.isCreating` 或启动恢复 `CREATING` / `save(ACTIVE)` 失败留盘 | **拒绝（不排队）** | 防止在 `CREATING` 待恢复前写入纯本机数据后被恢复投影覆盖 |
| 启动恢复中 | `wired && experimentEnabled && starting` | **拒绝（不排队）** | 启动检查尚未定论（可能恢复激活或执行 `CLEARING`/`DISABLING` 屏障），等待其结束 |
| 加入提交中 | `service.isCommittingJoin` | **拒绝（不排队）** | 对齐 `docs/plans/sync.md` §12.4.5.1 与 Swift `StageModel+Sync.swift`，提示「正在加入同步…」 |
| 加入待修复 | `service.joinRepairPending`（`!isActive && journal == PENDING`） | **拒绝（不排队）** | 对齐 `docs/plans/sync.md` §23.14，直到「关闭同步」重试恢复完成 |
| 清除进行中 | `service.isClearing` | **拒绝（不排队）** | 已摘下协调器并写盘 `CLEARING`，禁止新编辑避免被后续恢复屏障覆盖 |
| 清除失败待重试 / 关闭待收尾 / 暂停待处理 | `!service.isActive && service.hasPendingRecovery`（磁盘留有 `CLEARING`、`DISABLING`、`CREATING` 或暂停/密钥丢失/不匹配的 `ACTIVE`） | **拒绝（不排队）** | 清除遇断网/403/取消或关闭存储失败后持续阻断，防止再次「关闭同步」或重启恢复跑 `barrier()` 覆盖新编辑；收尾回到 `Off` 或重新激活后恢复允许 |

## 平台门槛、探针分级与构建期配置

- **minSdk 26**：`SyncPlatformGate.isSdkSupported(sdkInt)` 与 `SyncHub.createIfSupported(...)` 在 `sdkInt < 26` 时直接返回 `null`，不运行探针、不实例化密钥库与服务。
- **探针分级（`sync.md` 第 18 节）**：`SyncProbe.run()` 使用内嵌自 `Tests/Fixtures/sync/vectors.json` 的单组最小向量执行五项检查（私钥导入、签名与 DER→r‖s、公钥匹配、跨端验签、pubcheck 核对）：
  - 五项全过：`FULL`，显示全部入口。
  - 仅公钥匹配失败、其余四项全过：`JOIN_ONLY`，隐藏「在这台设备上开启同步」与加入 404 的「仍用此码创建」，保留加入/推送/清除。
  - 其余任一失败：`DISABLED`，不显示同步入口。
- **服务端地址注入**：代码内不写死真实主机名。`SyncConfig.configuredOrigin` 依次读取显式覆盖、`BuildConfig.VELA_SYNC_BASE`（反射读取，未定义时返回 `null`）与清单 `meta-data` `vela.sync.base`，并用 `SyncCode.isCanonicalOrigin` 校验。未配置时 `SyncEntryPolicy.showEntry` 返回 `false`，`SyncHub.createIfSupported` 返回 `null`，同步入口不显示。
- **实验开关**：`SyncPrefs.experimentSync` 默认 `false`。关闭时不联网、不读 Keystore、不读同步状态；同步处于已开启、磁盘留有同步状态、启动中、提交中或待修复时拒绝直接关闭实验开关。

## 密钥安全、身份验证与备份排除

- `AndroidSyncKeyStore` 的三类失败：
  1. **条目缺失（`None`）**：无密钥记录，启动检查按 `SyncLifecycle.startupAction` 判定未开启或密钥丢失。
  2. **暂时不可用（`Unavailable`）**：如 `UserNotAuthenticatedException` / `ProviderException` / `KeyStoreException`，跳过本次同步、不删状态、不写明文，下次离散触发重试。
  3. **永久失效（`Invalidated`）**：如 `KeyPermanentlyInvalidatedException` / `AEADBadTagException` / `UnrecoverableKeyException` / 密文损坏，提示重新输入同步码，绝不自动生成新 K、绝不把明文 K 写盘。
- **查看完整同步码前再次验证身份**：`SyncPanel` 未传入 `onRequestReveal` 时默认拒绝（fail closed）；`MainActivity` 通过 `KeyguardManager.createConfirmDeviceCredentialIntent` 触发系统锁屏凭据验证（不新增 `USE_BIOMETRIC` 等权限），仅在 `RESULT_OK` 时经 `SyncHub.revealAfterAuth()` 更新 `SyncUiState.revealedCode`（不捕获 Composable 局部状态，验证期间宿主 `onStop` 重建不丢失），退到后台或点击关闭时立即清空。
- **备份排除**：`AndroidManifest.xml` 保持 `android:allowBackup="false"`，同时配置 `android:dataExtractionRules="@xml/data_extraction_rules"`（API 31+ 云备份与设备间迁移）与 `android:fullBackupContent="@xml/full_backup_content"`（API 26–30），显式排除 `vela_sync.xml`、`Vela/sync-state.json`、`Vela/sync-state.json.corrupt`、`Vela/sync-join-snapshot` 及全量域。

## 需编排者后续收尾（超出本任务可写范围）

以下文件受本任务可写范围限制未做改动，需编排者在后续波次补齐并裁决：

1. **构建期地址注入与模板**：在 `android/app/build.gradle.kts` 增加 `VELA_SYNC_BASE` 的 `buildConfigField`（从 `-Pvela.sync.base` 或本地未入库文件读取），并在仓内新增模板文件（仿 Apple `Config/Local.xcconfig.template`）与 `.gitignore` 条目；完成后 `SyncConfig` 可改为直接读取 `BuildConfig.VELA_SYNC_BASE`（或保留反射并在 `proguard-rules.pro` 保留该字段）。
2. **偏好加载结果细分**：`:core` 的 `CurationStore` / `TasteStore` 目前仅暴露 `isReadOnly`（未区分 `QUARANTINED` 与 `MISSING`），可后续在 `:core` 补 `lastLoadOutcome` 对齐 Apple 端。
3. **未配置地址时的入口策略裁决**：原验收 7 要求「未配置时同步入口不显示」，`docs/plans/sync.md` 第 1 节写的是未配置默认地址时隐藏「创建」、保留「加入」。当前 `SyncEntryPolicy.showEntry` 按验收 7 实现；如需改回允许无默认地址时「加入」，只改 `SyncEntryPolicy.showEntry` 一行即可。
4. **parity 登记**：`android/parity/skip-sync.tsv` 中的 `deferred:S6` 条目待编排者确认后转入 `split-sync.tsv`。
5. **真机 / 模拟器仪器测试**：`androidTest/kotlin/vela/app/sync/SyncDeviceInstrumentTest.kt` 已在云端通过编译，需在真机或模拟器执行以实测真实 `AndroidKeyStore`、设备 P-256 探针与真机 `android.icu`。
