# 跨设备同步（同步码 + 端到端加密）设计

> 状态：设计稿 v1（2026-11-18），用户已批准方案 C：不做账号，用同步码加端到端加密，服务端只存密文。实现前先过对抗审查。

## 1. 目标与边界

- 目标：在 macOS、iOS、安卓之间同步收藏、屏蔽、不感兴趣、口味标签与少量设置。
- 不做：账号、邮箱、密码、找回、分享给他人、服务端可读的任何字段、实时推送。
- 不同步：停留记录、学习账本、缩略图缓存、窗口位置、音量数值。这些量大或与设备相关。
- 轻量硬约束：无轮询、无常驻连接、无新增第三方依赖；只在「启动或回到前台拉一次、改动后去抖 5 秒推一次、进后台前推一次」三个时机联网；单次传输几 KB。
- 服务端地址不进公开仓：Apple 端经本地 xcconfig 注入，安卓经本地 gradle 属性注入；同步码里也带地址，所以加入方不需要预置地址。未配置地址的构建隐藏同步入口。

## 2. 公开方案调研

| 出处 | 借什么 | 不借什么 |
|---|---|---|
| Firefox Sync（`docs.services.mozilla.com` 的 storage 与 crypto 文档） | 客户端持有主密钥、服务端只存加密记录；按记录合并而不是整包覆盖；HKDF 从主密钥派生用途不同的子密钥 | 账号体系、多集合多记录的服务端存储、BSO 格式 |
| Standard Notes / Bitwarden 的端到端加密说明 | 每次加密用随机 nonce 的 AEAD（AES-GCM），密文自带版本号便于将来换算法 | 用户名密码派生密钥（我们没有密码，直接随机 256 位） |
| Apple iCloud KVS、Android Backup | 冲突时「按项最后写入胜出」的简单口径 | 平台锁定（要跨三端） |

## 3. 密钥与同步码

- 主密钥 `K`：首台设备开启同步时用系统 CSPRNG 生成 32 字节。只存在本机钥匙串（Apple）或 Android Keystore 包裹的加密偏好里，不进普通偏好文件、不进日志、不进崩溃报告。
- 派生（HKDF-SHA256，salt 为空，info 区分用途，各输出 32 字节）：
  - `blobID = hex(HKDF(K, "vela-sync/v1/id"))`：服务端存取用的标识，不可逆推 `K`。
  - `encKey = HKDF(K, "vela-sync/v1/enc")`：AES-256-GCM 密钥。
  - `writeToken = base64url(HKDF(K, "vela-sync/v1/write"))`：写入凭据，服务端只存它的 SHA-256。
- 同步码：`vela1:` + base64url(`K`) + `@` + 服务端基址（https URL）。另以二维码呈现同一字符串。复制、粘贴、扫码三种方式加入，v1 只要求复制粘贴，二维码显示可选。
- 关闭同步：本机删除 `K` 与本地同步状态，不动服务端；提供「清除云端数据」按钮，用 `writeToken` 调删除接口。
- 同步码丢失即数据不可恢复，界面明确告知。

## 4. 数据格式（明文，加密前）

```json
{
  "v": 1,
  "updatedAt": 1763452800123,
  "sets": {
    "favorites":    { "<room>": {"a": 1763452800000, "d": 0} },
    "blocked":      { "<room>": {"a": 0, "d": 0} },
    "notInterested":{ "<room>": {"a": 0, "d": 0} },
    "tags":         { "<tag>":  {"a": 0, "d": 0} }
  },
  "order": { "favorites": {"value": ["<room>", "..."], "t": 1763452800000} },
  "settings": { "<key>": {"value": <json>, "t": 1763452800000} }
}
```

- 时间戳一律毫秒 Unix 时间（`Int64`），由写入设备的本地时钟给出。
- 集合项：`a` 为最近一次加入时间，`d` 为最近一次删除时间；`a > d` 视为存在。删除不抹记录（墓碑），墓碑保留 90 天后可清理。
- 收藏顺序单独存为「最后写入胜出」的列表；合并后与收藏集合求交并把集合里有、列表里没有的项按加入时间追加在末尾。
- 设置值只允许布尔、整数（`Int64`）、字符串三种类型。设置白名单（v1）：`muted`、`phoneDual`、`pipAutoEnter`、`publicOnly`、`infoAlwaysOn`。macOS 专有的透明度、置顶、点穿等不同步。未知键原样保留不丢弃（前向兼容）。
- 房间与标签名一律按现有规范化函数处理后再入集合（小写、去首尾空白、去 `#`），与本地存储同一口径。

## 5. 合并规则（纯函数，三端逐位一致）

- `merge(local, remote) -> merged`：
  - 集合每项：`a = max(a1, a2)`、`d = max(d1, d2)`。
  - 列表与设置每项：取 `t` 较大者；`t` 相等时比较值的「类型前缀字符串」取较大者（布尔 `b:true`/`b:false`，整数 `n:<十进制>`，字符串 `s:<原文>`，列表 `l:` 加各元素用 `\u001f` 连接），按 UTF-8 字节序比较，保证三端确定一致。
  - `updatedAt = max`。
- 性质（测试必须覆盖）：交换律、结合律、幂等；任意两端各自增删后合并，结果与合并顺序无关。
- 本地改动先写入本地同步状态（带时间戳），再触发去抖推送。

## 6. 加密封装

- `envelope = "v1." + base64url(nonce 12 字节) + "." + base64url(ciphertext‖tag)`；AAD 为 `"vela-sync/v1|" + blobID`。
- 明文为第 4 节 JSON 的 UTF-8，不压缩（数据只有几 KB；各端 zlib 输出字节不同，会破坏金样本）。各端序列化格式不要求逐字节一致，解密后按 JSON 语义解析。
- Apple 用 CryptoKit（`HKDF`、`AES.GCM`），安卓用 `javax.crypto`（HKDF 按 RFC 5869 用 `Mac` 实现）。不引第三方库。
- 跨端金样本：`Tests/Fixtures/sync/vectors.json` 给出固定 `K`、nonce、明文字节（base64）与期望的 `blobID`、`writeToken`、`envelope`；三端用这份明文字节加密必须得到逐字节相同的 `envelope`，解密必须还原相同字节。另给若干 `merge` 用例（输入两份 JSON、期望输出的语义结构），三端合并结果按语义相等。VelaCore 在 Linux 上没有 CryptoKit，加密实现放在 Apple 侧（`Shared/`）并在 Apple 测试里校验金样本；合并与编解码放在 VelaCore，Linux 也测。

## 7. 服务端（aanao）

- 进程：单个 Python 标准库 HTTP 服务（无第三方依赖），SQLite 单表 `blobs(id TEXT PRIMARY KEY, ver INTEGER, token_hash TEXT, data TEXT, updated_at INTEGER)`。只监听 `127.0.0.1`，前面由 Cloudflare Tunnel（`sync` 子域，橙云）接入；不改 aanao 现有 nginx 与站点。
- 接口：
  - `GET /v1/blob/{blobID}` → `200 {"ver":n,"data":"<envelope>"}`；不存在 `404`。
  - `PUT /v1/blob/{blobID}`，头 `Authorization: Bearer <writeToken>`、`If-Match: <ver>`（新建用 `0`），体 `{"data":"<envelope>"}` → `200 {"ver":n+1}`；版本不符 `409 {"ver":当前,"data":当前}`；token 不符 `403`。首写时记录 token 哈希。
  - `DELETE /v1/blob/{blobID}`，带 `Authorization` → `204`。
- 限制：`blobID` 必须是 64 位小写十六进制；体积上限 256 KB；每 IP 每分钟 60 次、每 blob 每分钟 20 次写；超限 `429`。不记录请求体，访问日志里 `blobID` 只记前 8 位。
- 运维：systemd 服务加隧道，备份为每日 SQLite 快照（只含密文），保留 7 天。

## 8. 客户端流程

- 拉取：GET，若 404 视为空；解密失败（换过密钥、数据损坏）不覆盖本地，提示一次并停用同步。
- 推送：先 GET 拿 `ver` 与远端，`merge(local, remote)`，写回本地，再带 `If-Match` PUT；`409` 时用响应体再合并后重试，最多 3 次。
- 加入：解析同步码，保存 `K` 与基址，立即执行一次拉取合并推送；本地已有数据与远端合并而不是被覆盖。
- 失败：网络失败静默重试到下一个时机，不弹窗、不轮询；连续失败 3 次在设置页显示「上次同步失败」。

## 9. 威胁模型

- 服务端或链路被看穿：只见 `blobID` 与密文、体积、访问时间；看不到房间名与口味。体积可大致反映收藏数量，接受。
- 他人拿到 `blobID`：无法解密；无 `writeToken` 不能覆盖或删除。
- 他人拿到同步码：等同拿到全部同步数据，与「把手机交给别人」同级；界面提示同步码要保密，提供「更换同步码」（生成新 `K`、迁移数据、删除旧 blob）。
- 重放旧密文：服务端版本号单调递增，客户端合并规则让旧数据不会覆盖新数据。
- 滥用存储：体积与频率限制、无枚举接口。

## 10. 任务拆分（文件归属不相交，可并行）

| 任务 | 执行 | 范围 | 验收 |
|---|---|---|---|
| S0 契约与金样本 | 编排者 | 本文件、`Tests/Fixtures/sync/vectors.json` | 审查通过 |
| S1 合并与编解码（Swift） | 本机 agent | `Sources/VelaCore/Sync*.swift`、`Tests/VelaCoreTests/Sync*` | Linux 与 macOS `swift test` 全绿，覆盖第 5 节性质 |
| S2 加密与 Apple 接线 | 本机 agent | `Shared/Sync/**`、设置页入口、钥匙串、xcconfig 注入 | 金样本逐字节一致；两个目标构建；实跑门 |
| S3 服务端 | 云端 grok（Linux） | `server/sync/**`（Python 与 systemd、隧道配置模板，不含真实域名与凭据） | 本地起服务跑接口测试：404、首写、409、403、429、体积上限、删除 |
| S4 安卓核心 | 云端 grok | `android/core/src/{main,test}/kotlin/vela/core/sync/**` | 金样本与合并性质测试全绿 |
| S5 部署 | 编排者 | aanao 上的服务与隧道 | 公网 GET/PUT/409/403 实测 |
| S6 安卓接线 | 安卓里程碑 A 之后 | `android/app/.../sync/**` | 与 Apple 端实测互通 |
