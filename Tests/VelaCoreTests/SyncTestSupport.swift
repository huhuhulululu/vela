@testable import VelaCore

/// 同步测试的小工具：按 `(l, c, dev)` 造寄存器与文档。
enum SyncFixture {
    static func reg(_ l: UInt64, _ payload: String, c: UInt16 = 0, dev: UInt32 = 1) -> SyncRegister {
        SyncRegister(hlc: HLC(l: l, c: c, dev: dev), payload: payload)
    }

    static func doc(_ text: String) -> SyncDocument {
        guard case .valid(let d) = SyncDocument.decode(Array(text.utf8)) else { fatalError("bad fixture: \(text)") }
        return d
    }

    static func text(_ doc: SyncDocument) -> String { String(decoding: doc.encoded(), as: UTF8.self) }
}

/// 测试用 SHA-256（FIPS 180-4），纯 Swift，不依赖平台密码库。
enum TestSHA256 {
    static let k: [UInt32] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ]

    static func hash(_ message: [UInt8]) -> [UInt8] {
        var h: [UInt32] = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]
        var data = message
        let bitLength = UInt64(message.count) * 8
        data.append(0x80)
        while data.count % 64 != 56 { data.append(0) }
        for i in (0..<8).reversed() { data.append(UInt8(truncatingIfNeeded: bitLength >> (UInt64(i) * 8))) }
        func rotr(_ x: UInt32, _ n: UInt32) -> UInt32 { (x >> n) | (x << (32 - n)) }
        var w = [UInt32](repeating: 0, count: 64)
        for chunk in stride(from: 0, to: data.count, by: 64) {
            for i in 0..<16 {
                let j = chunk + i * 4
                w[i] = UInt32(data[j]) << 24 | UInt32(data[j + 1]) << 16 | UInt32(data[j + 2]) << 8 | UInt32(data[j + 3])
            }
            for i in 16..<64 {
                let s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3)
                let s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10)
                w[i] = w[i - 16] &+ s0 &+ w[i - 7] &+ s1
            }
            var (a, b, c, d, e, f, g, hh) = (h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7])
            for i in 0..<64 {
                let t1 = hh &+ (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) &+ ((e & f) ^ (~e & g)) &+ k[i] &+ w[i]
                let t2 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) &+ ((a & b) ^ (a & c) ^ (b & c))
                (hh, g, f, e, d, c, b, a) = (g, f, e, d &+ t1, c, b, a, t1 &+ t2)
            }
            h = [h[0] &+ a, h[1] &+ b, h[2] &+ c, h[3] &+ d, h[4] &+ e, h[5] &+ f, h[6] &+ g, h[7] &+ hh]
        }
        return h.flatMap { v in (0..<4).map { UInt8(truncatingIfNeeded: v >> (24 - $0 * 8)) } }
    }

    static func hex(_ s: String) -> String { SyncEncoding.hex(hash(Array(s.utf8))) }
}

/// 明文「信封」：`p1.<base64url(明文)>.<ver>`，AAD 绑版本的效果用版本号模拟。
final class PlainCodec: SyncEnvelopeCodec {
    var publicKey = "PUB"
    var failOpen = false
    func seal(_ plaintext: [UInt8], ver: UInt64) -> String? { "p1." + SyncEncoding.base64url(plaintext) + "." + String(ver) }
    func open(_ envelope: String, ver: UInt64) -> SyncOpenResult {
        let parts = envelope.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 3, parts[0] == "p1" else { return .unsupported }
        guard !failOpen, parts[2] == String(ver), let bytes = SyncEncoding.decodeBase64url(parts[1]) else { return .failed }
        return .plaintext(bytes)
    }
    func digest(_ envelope: String) -> String { TestSHA256.hex(envelope) }
    static func envelope(_ doc: SyncDocument, ver: UInt64) -> String { PlainCodec().seal(doc.encoded(), ver: ver)! }
}

final class MemoryStateStore: SyncStateStore {
    var stored: SyncState?
    var corrupted = false
    var failSaves = false
    var failDelete = false
    var saves = 0
    init(_ state: SyncState? = nil) { stored = state }
    func load() -> SyncStateRead { corrupted ? .corrupted : stored.map { .present($0) } ?? .none }
    func save(_ state: SyncState) -> Bool {
        guard !failSaves else { return false }
        saves += 1
        stored = state
        return true
    }
    func delete() -> Bool {
        guard !failDelete else { return false }
        stored = nil
        return true
    }
}

final class MemoryKeychain: SyncKeychain {
    var entry: SyncKeychainRead
    var failDelete = false
    init(_ entry: SyncKeychainRead = .none) { self.entry = entry }
    func read() -> SyncKeychainRead { entry }
    func delete() -> Bool {
        guard !failDelete else { return false }
        entry = .none
        return true
    }
}

final class MemorySink: SyncLocalSink {
    var curation: Curation?
    var taste = Taste()
    var settings: [String: Bool] = [:]
    var failCuration = false
    var failTaste = false
    var cancelled = 0
    func cancelPendingSaves() { cancelled += 1 }
    func currentTaste() -> Taste { taste }
    func saveCuration(_ c: Curation) -> Bool {
        guard !failCuration else { return false }
        curation = c
        return true
    }
    func saveTaste(_ t: Taste) -> Bool {
        guard !failTaste else { return false }
        taste = t
        return true
    }
    func applySettings(_ s: [String: Bool]) { settings.merge(s) { $1 } }
}

/// 内存服务端：按第 10 节的版本条件写入语义，带故障注入。
final class MemoryServer {
    var ver: UInt64 = 0
    var pub: String?
    var data: String?
    var epoch = "e0000000000000000000000000000001"
    var now: UInt64 = 1_800_000_000_000
    var deletedAt: UInt64?
    var puts: [SyncPutRequest] = []
    var gets = 0

    func get() -> SyncServerResponse {
        gets += 1
        if let deletedAt { return .gone(deletedAt: deletedAt) }
        guard let pub, let data else { return .notFound(epoch: epoch, now: now) }
        return .blob(ver: ver, pub: pub, data: data, epoch: epoch, now: now)
    }

    func put(_ r: SyncPutRequest, pub requester: String) -> SyncServerResponse {
        puts.append(r)
        if let deletedAt { return .gone(deletedAt: deletedAt) }
        guard r.epoch == nil || r.epoch == epoch else { return .forbidden }
        if r.base == 0 {
            guard data == nil else { return .conflict(ver: ver, pub: pub!, data: data!, epoch: epoch, now: now) }
            pub = requester
        } else {
            guard data != nil else { return .notFound(epoch: epoch, now: now) }
            guard r.base == ver else { return .conflict(ver: ver, pub: pub!, data: data!, epoch: epoch, now: now) }
        }
        ver = r.next
        data = r.envelope
        return .stored(ver: ver, epoch: epoch, now: now)
    }

    func delete(base: UInt64) -> SyncServerResponse {
        if deletedAt != nil { return .gone(deletedAt: deletedAt!) }
        guard data != nil else { return .notFound(epoch: epoch, now: now) }
        guard base == ver else { return .conflict(ver: ver, pub: pub!, data: data!, epoch: epoch, now: now) }
        deletedAt = now
        data = nil
        return .deleted
    }

    var document: SyncDocument? {
        guard let data, case .plaintext(let bytes) = PlainCodec().open(data, ver: ver), case .valid(let d) = SyncDocument.decode(bytes) else { return nil }
        return d
    }
}

/// 一台设备到内存服务端的传输，带钩子与故障注入。
final class MemoryTransport: SyncTransport {
    let server: MemoryServer
    var pub = "PUB"
    /// 返回前调用（模拟请求期间的本地编辑或其他设备的写入）。
    var beforeGetReturns: (() async -> Void)?
    var beforePutReturns: (() async -> Void)?
    /// 发到服务端之前调用（模拟其他设备抢先写入）。
    var beforePutSends: (() async -> Void)?
    /// 非 nil 时直接返回该响应，不经服务端。
    var putOverride: SyncServerResponse?
    /// 下一次 PUT 在服务端提交后丢掉响应。
    var dropNextPutResponse = false
    /// 依次替换 GET 的响应（为空时走服务端）。
    var scriptedGets: [SyncServerResponse] = []
    var deletes: [UInt64] = []
    var failDeletes = 0

    init(_ server: MemoryServer) { self.server = server }

    func get() async -> SyncServerResponse {
        let r = scriptedGets.isEmpty ? server.get() : scriptedGets.removeFirst()
        await beforeGetReturns?()
        return r
    }

    func put(_ request: SyncPutRequest) async -> SyncServerResponse {
        await beforePutSends?()
        if let putOverride { return putOverride }
        let r = server.put(request, pub: pub)
        await beforePutReturns?()
        if dropNextPutResponse {
            dropNextPutResponse = false
            return .transient
        }
        return r
    }

    func delete(base: UInt64, epoch: String?) async -> SyncServerResponse {
        deletes.append(base)
        if failDeletes > 0 {
            failDeletes -= 1
            return .transient
        }
        return server.delete(base: base)
    }
}
