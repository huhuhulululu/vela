import Foundation

/// 同步状态的阶段（第 12.1 节）。
public enum SyncPhase: String, Codable, Equatable, Sendable {
    case creating, active, disabling, clearing
}

/// 停止同步、需要用户「关闭再重开」的情况（第 11.4 节）。存在期间协调器不发任何请求。
public enum SyncHalt: Codable, Equatable, Sendable {
    /// 403：云端拒绝了这台设备的写入。
    case forbidden
    /// 410：空间已于 `deletedAt`（毫秒）被清除。
    case gone(deletedAt: UInt64)
    /// 404 且本机 `lastSeenVer > 0` 或 `inflight` 非空：云端同步空间不见了。
    case missing
    case epochChanged
    case rollback
    /// `pub` 与本机派生的不一致：同步空间被占用。
    case pubMismatch
    /// AEAD 解封在两个不同周期连续失败。
    case decryptFailed
}

/// 已发出、结果未知的 PUT（第 11.2 节第 6 步）。
public struct SyncInflight: Codable, Equatable, Sendable {
    public var base: UInt64
    public var next: UInt64
    public var envelopeHash: String
    public var sentGen: UInt64
}

/// `sync-state.json`：同步开启期间的唯一事实来源（第 11.1 节）。不含 `K`。
public struct SyncState: Codable, Equatable, Sendable {
    public var phase: SyncPhase
    public var origin: String
    public var blobID: String
    public var epoch: String?
    public var doc: SyncDocument
    public var clock: SyncClock
    public var maxSentL: UInt64 = 0
    public var gen: UInt64 = 0
    public var pushedGen: UInt64 = 0
    public var lastSeenVer: UInt64 = 0
    public var lastSeenHash: String?
    public var inflight: SyncInflight?
    /// 迁移盖章已做过（同一次开启只做一次）。
    public var migrated = false
    /// 合并结果墓碑删完仍超过 `P`：停止推送，直到用户删减（第 6.7 节第 3 步）。
    public var oversize = false
    public var halt: SyncHalt?
    /// 连续 AEAD 解封失败的周期数。
    public var decryptFailures = 0
    public var lastSuccessAt: UInt64?
    public var lastFailure: String?

    public init(phase: SyncPhase, origin: String, blobID: String, clock: SyncClock, doc: SyncDocument = .empty) {
        self.phase = phase
        self.origin = origin
        self.blobID = blobID
        self.clock = clock
        self.doc = doc
    }

    public var replica: SyncReplica {
        get { SyncReplica(doc: doc, clock: clock) }
        set {
            doc = newValue.doc
            clock = newValue.clock
        }
    }

    enum CodingKeys: String, CodingKey {
        case phase, origin, blobID, epoch, doc, clock, maxSentL, gen, pushedGen, lastSeenVer, lastSeenHash
        case inflight, migrated, oversize, halt, decryptFailures, lastSuccessAt, lastFailure
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        phase = try c.decode(SyncPhase.self, forKey: .phase)
        origin = try c.decode(String.self, forKey: .origin)
        blobID = try c.decode(String.self, forKey: .blobID)
        epoch = try c.decodeIfPresent(String.self, forKey: .epoch)
        // 文档以紧凑 JSON 文本保存，读回时按接收方规则重新校验。
        let text = try c.decode(String.self, forKey: .doc)
        guard case .valid(let decoded) = SyncDocument.decode(Array(text.utf8)) else {
            throw DecodingError.dataCorruptedError(forKey: .doc, in: c, debugDescription: "同步文档不合法")
        }
        doc = decoded
        clock = try c.decode(SyncClock.self, forKey: .clock)
        maxSentL = try c.decode(UInt64.self, forKey: .maxSentL)
        gen = try c.decode(UInt64.self, forKey: .gen)
        pushedGen = try c.decode(UInt64.self, forKey: .pushedGen)
        lastSeenVer = try c.decode(UInt64.self, forKey: .lastSeenVer)
        lastSeenHash = try c.decodeIfPresent(String.self, forKey: .lastSeenHash)
        inflight = try c.decodeIfPresent(SyncInflight.self, forKey: .inflight)
        migrated = try c.decode(Bool.self, forKey: .migrated)
        oversize = try c.decode(Bool.self, forKey: .oversize)
        halt = try c.decodeIfPresent(SyncHalt.self, forKey: .halt)
        decryptFailures = try c.decode(Int.self, forKey: .decryptFailures)
        lastSuccessAt = try c.decodeIfPresent(UInt64.self, forKey: .lastSuccessAt)
        lastFailure = try c.decodeIfPresent(String.self, forKey: .lastFailure)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(phase, forKey: .phase)
        try c.encode(origin, forKey: .origin)
        try c.encode(blobID, forKey: .blobID)
        try c.encodeIfPresent(epoch, forKey: .epoch)
        try c.encode(String(decoding: doc.encoded(), as: UTF8.self), forKey: .doc)
        try c.encode(clock, forKey: .clock)
        try c.encode(maxSentL, forKey: .maxSentL)
        try c.encode(gen, forKey: .gen)
        try c.encode(pushedGen, forKey: .pushedGen)
        try c.encode(lastSeenVer, forKey: .lastSeenVer)
        try c.encodeIfPresent(lastSeenHash, forKey: .lastSeenHash)
        try c.encodeIfPresent(inflight, forKey: .inflight)
        try c.encode(migrated, forKey: .migrated)
        try c.encode(oversize, forKey: .oversize)
        try c.encodeIfPresent(halt, forKey: .halt)
        try c.encode(decryptFailures, forKey: .decryptFailures)
        try c.encodeIfPresent(lastSuccessAt, forKey: .lastSuccessAt)
        try c.encodeIfPresent(lastFailure, forKey: .lastFailure)
    }
}

/// 读同步状态的结果。
public enum SyncStateRead: Equatable, Sendable {
    case none
    case present(SyncState)
    /// 读失败（已改名备份或改名也失败）：同步进入「暂停：同步状态损坏」，不推送（第 11.1 节）。
    case corrupted
}

/// 同步状态的持久化（协议注入，便于故障注入测试）。
public protocol SyncStateStore: AnyObject {
    func load() -> SyncStateRead
    /// 原子替换；失败返回 false。
    func save(_ state: SyncState) -> Bool
    func delete() -> Bool
}

/// 文件实现：沿用 `PreferenceQuarantine` 口径。备份排除属性（`isExcludedFromBackup`）由 Apple 端接线时设置（S2）。
public final class SyncStateFileStore: SyncStateStore, @unchecked Sendable {
    public let url: URL
    private let lock = NSLock()
    private var readOnly = false
    var moveItem: PreferenceQuarantine.Mover = PreferenceQuarantine.fileMover

    public init(url: URL) { self.url = url }

    public static func defaultURL() -> URL {
        CurationStore.defaultURL().deletingLastPathComponent().appendingPathComponent("sync-state.json")
    }

    /// 文件上限：文档 `P` 加上其余字段的余量。
    static let maxFileBytes = SyncLimits.plaintextBytes * 2 + 64_000

    public func load() -> SyncStateRead {
        lock.lock()
        defer { lock.unlock() }
        switch PreferenceQuarantine.read(SyncState.self, from: url, limit: Self.maxFileBytes, move: moveItem) {
        case .missing:
            readOnly = false
            return .none
        case .loaded(let state):
            readOnly = false
            return .present(state)
        case .quarantined:
            readOnly = false
            return .corrupted
        case .readOnly:
            readOnly = true
            return .corrupted
        }
    }

    public func save(_ state: SyncState) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !readOnly else { return false }
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(state).write(to: url, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    public func delete() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        do {
            if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
            readOnly = false
            return true
        } catch {
            return false
        }
    }
}
