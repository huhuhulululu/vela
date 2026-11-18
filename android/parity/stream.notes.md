# C4 取流与起播来源移植笔记

行为按 Swift 原样搬。下面是形状差异，不是自行改过的语义。

## 类型落点

都在 `vela.core.stream`：

- `StreamFailure`、`StreamTicket`、`StreamDecodeError`、`StreamDecoder`、`StreamRequest`
- `StreamOrigin`、`SourcedTicket`、`ContextProbe`、`StreamSourcing`
- `ChatContext.probe` 是同包的扩展函数。L0a 的 `ChatContext` 文件不改；调用写法仍是 `ChatContext.probe(...)`。

地址用 `java.net.URI`。`URL(string:)` 解析失败时为 null。只接受 `https://` 前缀，裁空白用 L0a 的 `trimSwiftWhitespace`。

时刻不在这组里读墙钟。退避窗口仍由调用方注入的 `EndpointBackoffBox` 决定。

## 解码

`LooseBool` 不走 kotlinx 的严格标量：布尔、整数 `1`、字符串 `"1"` / `"true"`（忽略大小写）为真；`null` 和其他标量为假。`success` 若是对象或数组，整段按格式错误，与 Swift `singleValueContainer` 抛错一致。缺 `success` 也是格式错误。可选字符串类型不对同样是格式错误；缺键和 JSON `null` 都是 null。

`StreamDecoder.fetch`：退避中抛 `EndpointBackedOff(streamURL)`，不发请求。非 JSON 且不是质询页时 `note` 登记退避并抛 `Unavailable`。质询不登记退避。网络错误原样抛出。

## 探测

同步 `probe` 先看质询，再看 `ResponseShape.shouldBackOff`（确定状态码用 `ChatContext.definitiveStatuses`，即 401 / 404），然后才要求 200 和能解开的对象。

异步 `probe`：窗口内直接 `BackedOff`。非 JSON 才 `record(chatContext)`，不连坐 `streamURL`。`RenewalPolicy.isCancellation`（`CancellationException` 或 `NSURLErrorDomain/-999`）以及协程已取消时，原样抛出；其他异常当成 `Failed`，不退避。Swift 的 `Task.isCancelled` 这里看 `coroutineContext.isActive`。

## 选择

`StreamSourcing.first` / `settle` 是纯函数，规则与 Swift 相同：公开且有 https 地址就直接用；已知的非公开状态直接定论并且丢掉地址；两边都给不出可播地址、而上下文说过公开时，归为 `geo`。

## 请求

`StreamRequest.make` 是 POST `/get_edge_hls_url_ajax/`，正文 `room_slug=` 加百分号编码。允许的字符对齐 Apple `CharacterSet.urlQueryAllowed`：可打印 ASCII，去掉空格以及 `"#%<>[\]^`{|}``。`%` 会编成 `%25`。Referer 仍是未编码的 `https://chaturbate.com/<username>/`。浏览器头走 L0a 的 `HTTPHeaders.applyBrowser`，再加表单 `Content-Type`。不写 Cookie，也不带带宽参数。

## 测试归属

`StreamSourcingTests`、`ChatContextProbeTests`、`GeoRestrictedTests` 类名与 Swift 文件名一致，parity 按方法名认。`VelaCoreTests` 的两条写在 `split-stream.tsv`。没有跳过。

`EndpointBackoffTests` 里两条 `StreamDecoder.fetch` 仍是 L0a 的 `deferred:C9`，没有挪走。`StreamFetchTests` 用同样的方法名先把行为锁住，类名不是 `EndpointBackoffTests`，不消化那两条 deferred。C9 仍应在原类名下跑一遍，或写进 `split-cross.tsv`。

地区受限的收藏夹、缓存、在线房间断言复用已经合入的 `ChatContext.decode`、`FavoriteStatus.Restricted`、`FavoritesBoard`、`FavoriteCache`、`FavoriteResolver`，本包不改这些类型。
