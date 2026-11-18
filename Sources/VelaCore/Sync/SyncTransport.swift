/// 协调器与服务端、加密层之间的接口（docs/plans/sync.md 第 8–11 节）。实现在 S2（Apple）与 S4（安卓）。

/// 服务端响应，已由传输层解析成型。所有 JSON 响应都带 `epoch` 与 `now`。
public enum SyncServerResponse: Equatable, Sendable {
    /// GET 200。
    case blob(ver: UInt64, pub: String, data: String, epoch: String, now: UInt64)
    /// GET 404：从未存在。
    case notFound(epoch: String, now: UInt64)
    /// 410：已删除。
    case gone(deletedAt: UInt64)
    /// PUT 200。
    case stored(ver: UInt64, epoch: String, now: UInt64)
    /// 409：附当前行。
    case conflict(ver: UInt64, pub: String, data: String, epoch: String, now: UInt64)
    /// POST delete 200。
    case deleted
    /// 403：验签失败（含 epoch 不符）。
    case forbidden
    case payloadTooLarge
    case rateLimited(retryAfter: Int)
    /// 507：服务器空间已满。
    case quotaFull
    /// 断网、超时、5xx、非 JSON 或截断的响应：暂时故障。
    case transient
}

/// 一次写请求。签名（第 9 节）由传输层用 `epoch`、`base`、`next` 与信封计算。
public struct SyncPutRequest: Equatable, Sendable {
    public let base: UInt64
    public let next: UInt64
    public let envelope: String
    public let epoch: String?
    /// 创建（`base = 0`）时请求体带 `pub`。
    public var includesPub: Bool { base == 0 }
}

public protocol SyncTransport: AnyObject {
    func get() async -> SyncServerResponse
    func put(_ request: SyncPutRequest) async -> SyncServerResponse
    func delete(base: UInt64, epoch: String?) async -> SyncServerResponse
}

public enum SyncOpenResult: Equatable, Sendable {
    case plaintext([UInt8])
    /// 未知信封前缀：只读，提示更新 App。
    case unsupported
    /// AEAD 解封失败、解压失败或超过 `P`。
    case failed
}

/// 加密封装与摘要（S2 用 CryptoKit 实现；S1 测试用明文假实现）。
public protocol SyncEnvelopeCodec: AnyObject {
    /// 本机派生的公钥（base64url），与服务端返回的 `pub` 比较。
    var publicKey: String { get }
    /// 以将要存放的版本号作 AAD 封装；随机源失败等错误返回 nil。
    func seal(_ plaintext: [UInt8], ver: UInt64) -> String?
    func open(_ envelope: String, ver: UInt64) -> SyncOpenResult
    /// 信封 ASCII 字节的 SHA-256，小写 hex。
    func digest(_ envelope: String) -> String
}
