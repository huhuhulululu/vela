# 安卓跨设备同步接线（S6）

语义对齐 Apple 端 `Shared/Sync/**`、`Shared/Stage/StageModel+Sync.swift` 与 `docs/plans/sync.md`（正文冲突处以第 23 节为准）。纯协议核心在 `android/core/src/main/kotlin/vela/core/sync/`（S4）；本文件记录 `:app` 层 `vela.app.sync` 的接线、约束与后续编排者收尾项。本文以代码为准。

## 模块布局

| 文件 | 职责 |
|---|---|
| `SyncConfig.kt` | minSdk 26 平台门槛（`SyncPlatformGate`）、运行期 P-256 五项探针与三级判定（`SyncProbe` / `SyncCapability`）、构建期服务端地址解析（`SyncConfig`）、入口可见性裁决（`SyncEntryPolicy`） |
| `AndroidSyncKeyStore.kt` | `AndroidKeyStore` AES-256-GCM 包裹密钥（别名 `vela_sync_wrap`，随机 IV，`setUserAuthenticationRequired(false)`）与专用偏好文件 `vela_sync`（单键 `space`，格式 `v1.<iv>.<ct>`），覆盖缺失 / 暂时不可用 / 永久失效三类读取结果 |
| `SyncCryptoCodec.kt` | 实现 `SyncEnvelopeCodec`：调用 `:core` 的 AES-256-GCM + raw DEFLATE 编解码、`e1.` 前缀检查、800,100 字节信封上限与 CSPRNG nonce |
| `SyncMarkedStateStore.kt` | 包装 `SyncStateFileStore`：状态文件存在时读前先落盘 `sync-state.json.corrupt` 标记，读成功再删；标记写不成时走只读解码，坏文件留在原处；标记在且状态文件已隔离时拒绝覆盖，直到 `delete()` 清除 |
| `SyncJoinJournal.kt` | 加入提交前的文件快照（`FileJoinJournal`）：同卷临时目录构建（0600 文件 + `absent-*` + `settings.json` + `complete`）后原子发布；写「已开启」后标 `committed`；失败/未定局重启时同卷原子换回并恢复「未设置」键 |
| `SyncService.kt`、`SyncJoin.kt` | 第 12.1 节启动检查、第 12.2 节投影落盘屏障、周期单飞（协程 `Mutex` + 网络让出 `Semaphore`）、创建/加入/关闭/清除全流程、第 23 节第 13 条加入提交四条机制（提交锁、写前快照与原子换回、「已开启」最后写、「需要修复」状态）与 409 水位检查 |
| `SyncHttpTransport.kt` | 基于平台 `HttpURLConnection` 的可注入传输层：`User-Agent: Vela-Sync/2`、不跟随重定向、不带 Cookie、不用缓存、ECDSA P-256 签名、响应体流式封顶 802,048 字节并交 `:core` `SyncHttp.interpret` 解析 |
| `SyncPrefs.kt`、`SyncStageAdapter.kt` | 复用 `com.vela.stage` 读写默认关闭的实验开关 `experimentSync` 及显式设置键；通过同模块 `internal` 接口适配 `StageViewModel`，回滚重读不污染未设置键（M9） |
| `SyncHub.kt`、`SyncPanel.kt`、`SyncStatusText.kt` | 界面状态机（`StateFlow<SyncUiState>`）、离散触发与退避调度、`AndroidIcuSyncUnicode` 安全装入、Compose 同步面板与 `SyncClipboard` 敏感剪贴板标记（API 33+ `EXTRA_IS_SENSITIVE`） |
| `vela/app/ui/TasteSheet.kt` | 口味/设置面板底部可选入口参数 `syncHub: SyncHub? = null`，非空且可见时渲染 `SyncSettingsSection` |

## 平台门槛、探针分级与构建期配置

- **minSdk 26**：`SyncPlatformGate.isSdkSupported(sdkInt)` 与 `SyncHub.createIfSupported(...)` 在 `sdkInt < 26` 时直接返回 `null`，不运行探针、不实例化密钥库与服务。
- **探针分级（`sync.md` 第 18 节）**：`SyncProbe.run()` 使用内嵌自 `Tests/Fixtures/sync/vectors.json` 的单组最小向量执行五项检查（私钥导入、签名与 DER→r‖s、公钥匹配、跨端验签、pubcheck 核对）：
  - 五项全过：`FULL`，显示全部入口。
  - 仅公钥匹配失败、其余四项全过：`JOIN_ONLY`，隐藏「在这台设备上开启同步」与加入 404 的「仍用此码创建」，保留加入/推送/清除。
  - 其余任一失败：`DISABLED`，不显示同步入口。
- **服务端地址注入**：代码内不写死真实主机名。`SyncConfig.configuredOrigin` 依次读取显式覆盖、`BuildConfig.VELA_SYNC_BASE`（反射读取，未定义时返回 `null`）与清单 `meta-data` `vela.sync.base`，并用 `SyncCode.isCanonicalOrigin` 校验。未配置时 `SyncEntryPolicy.showEntry` 返回 `false`，同步入口不显示。
- **实验开关**：`SyncPrefs.experimentSync` 默认 `false`。关闭时不联网、不读 Keystore、不读同步状态；同步处于已开启、磁盘留有同步状态、启动中、提交中或待修复时拒绝直接关闭实验开关。

## 密钥安全与备份排除

- `AndroidSyncKeyStore` 的三类失败：
  1. **条目缺失（`None`）**：无密钥记录，启动检查按 `SyncLifecycle.startupAction` 判定未开启或密钥丢失。
  2. **暂时不可用（`Unavailable`）**：如 `UserNotAuthenticatedException` / `ProviderException` / `KeyStoreException`，跳过本次同步、不删状态、不写明文，下次离散触发重试。
  3. **永久失效（`Invalidated`）**：如 `KeyPermanentlyInvalidatedException` / `AEADBadTagException` / `UnrecoverableKeyException` / 密文损坏，提示重新输入同步码，绝不自动生成新 K、绝不把明文 K 写盘。
- **备份排除**：`AndroidManifest.xml` 保持 `android:allowBackup="false"`，同时配置 `android:dataExtractionRules="@xml/data_extraction_rules"`（API 31+ 云备份与设备间迁移）与 `android:fullBackupContent="@xml/full_backup_content"`（API 26–30），显式排除 `vela_sync.xml`、`Vela/sync-state.json`、`Vela/sync-state.json.corrupt`、`Vela/sync-join-snapshot` 及全量域。

## 需编排者后续收尾（超出本任务可写范围）

以下文件受本任务可写范围限制未做改动，需编排者在后续波次补齐并裁决：

1. **构建期地址注入与模板**：在 `android/app/build.gradle.kts` 增加 `VELA_SYNC_BASE` 的 `buildConfigField`（从 `-Pvela.sync.base` 或本地未入库文件读取），并在仓内新增模板文件（仿 Apple `Config/Local.xcconfig.template`）与 `.gitignore` 条目；完成后 `SyncConfig` 可改为直接读取 `BuildConfig.VELA_SYNC_BASE`（或保留反射并在 `proguard-rules.pro` 保留该字段）。
2. **宿主与舞台接线**：`MainActivity.kt`、`StageScreen.kt`、`LifecycleWiring.kt` 与 `vela/app/stage/**` 不在可写范围内。需在宿主层调用 `SyncHub.createIfSupported(...)` 并 `attach()`、把 `syncHub` 传入 `StageScreen` → `TasteSheet`、在前后台转发 `onForeground()`/`onBackground()`，以及在 `StageViewModel` 的收藏/屏蔽/口味/设置修改路径前置 `syncHub.localWriteAllowed` 与 `syncHub.perform(...)`。
3. **偏好加载结果细分**：`:core` 的 `CurationStore` / `TasteStore` 目前仅暴露 `isReadOnly`（未区分 `QUARANTINED` 与 `MISSING`），可后续在 `:core` 补 `lastLoadOutcome` 对齐 Apple 端。
4. **未配置地址时的入口策略裁决**：任务验收 7 要求「未配置时同步入口不显示」，`docs/plans/sync.md` 第 1 节写的是未配置默认地址时隐藏「创建」、保留「加入」。当前 `SyncEntryPolicy.showEntry` 按验收 7 实现；如需改回允许无默认地址时「加入」，只改 `SyncEntryPolicy.showEntry` 一行即可。
5. **parity 登记**：`android/parity/skip-sync.tsv` 中的 `deferred:S6` 条目待宿主接线全部就位后转入 `split-sync.tsv`。
6. **真机 / 模拟器仪器测试**：`androidTest/kotlin/vela/app/sync/SyncDeviceInstrumentTest.kt` 已在云端通过编译，需在真机或模拟器执行以实测真实 `AndroidKeyStore`、设备 P-256 探针与真机 `android.icu`。
