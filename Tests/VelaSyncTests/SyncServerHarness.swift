#if canImport(CryptoKit) && os(macOS)
import Foundation
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 本机起的 S3 服务端（`python3 server/sync/app.py --db <临时> --host 127.0.0.1 --port 0`），只绑回环、系统分配端口。
/// 缺 python3 或 python3-cryptography 时测试跳过并说明原因，不伪造通过。
final class SyncServerHarness {
    let process = Process()
    let dir: URL
    let port: Int
    var base: URL { URL(string: "http://127.0.0.1:\(port)")! }

    static var repoRoot: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    }

    static func python() -> URL? {
        for path in ["/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3"] where FileManager.default.isExecutableFile(atPath: path) {
            let probe = Process()
            probe.executableURL = URL(fileURLWithPath: path)
            probe.arguments = ["-I", "-c", "import cryptography.hazmat.primitives.asymmetric.ec"]
            probe.standardOutput = FileHandle.nullDevice
            probe.standardError = FileHandle.nullDevice
            guard (try? probe.run()) != nil else { continue }
            probe.waitUntilExit()
            if probe.terminationStatus == 0 { return URL(fileURLWithPath: path) }
        }
        return nil
    }

    init() throws {
        guard let python = Self.python() else {
            throw XCTSkip("本机没有可用的 python3-cryptography，集成测试跳过（未伪造通过）")
        }
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("vela-sync-it-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let out = Pipe()
        process.executableURL = python
        process.arguments = ["-I", "-u", Self.repoRoot.appendingPathComponent("server/sync/app.py").path,
                             "--db", dir.appendingPathComponent("sync.db").path, "--host", "127.0.0.1", "--port", "0"]
        process.standardOutput = out
        process.standardError = FileHandle.nullDevice
        try process.run()
        // 第一行 `PORT <n>`；最多等 10 秒。
        let deadline = Date().addingTimeInterval(10)
        var line = ""
        let handle = out.fileHandleForReading
        while !line.contains("\n"), Date() < deadline {
            let chunk = handle.availableData
            if chunk.isEmpty { break }
            line += String(decoding: chunk, as: UTF8.self)
        }
        guard let p = line.split(separator: "\n").first?.split(separator: " ").last.flatMap({ Int($0) }) else {
            process.terminate()
            throw XCTSkip("服务端没有启动")
        }
        port = p
    }

    func stop() {
        if process.isRunning {
            process.terminate()
            process.waitUntilExit()
        }
        try? FileManager.default.removeItem(at: dir)
    }
}

/// 一台模拟设备：内存状态、内存钥匙串、真实 HTTP 传输与 CryptoKit 加密。
final class TestDevice {
    let stateStore = MemoryStateStore()
    let keyStore = MemoryKeyStore()
    let sink = MemorySink()
    let service: SyncService
    var clock: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    /// 用不同的 `CF-Connecting-IP` 区分设备，免得撞上「每 IP 每小时 3 次创建」。
    let ip: String
    var signingOverride: SyncKeyMaterial?

    init(server: SyncServerHarness, ip: String) {
        self.ip = ip
        var me: TestDevice?
        let env = SyncService.Environment(
            stateStore: stateStore, keyStore: keyStore,
            makeTransport: { _, keys in
                let signing = me?.signingOverride ?? keys
                return SyncHTTPTransport(base: server.base, keys: signing, extraHeaders: ["CF-Connecting-IP": ip])
            },
            wallClock: { me?.clock ?? 0 }
        )
        service = SyncService(env: env)
        me = self
    }

    func tick(_ ms: UInt64 = 1) { clock += ms }

    var doc: SyncDocument? { service.state?.doc }

    func local(favorites: [String] = [], blocked: [String] = []) -> SyncLocalData {
        SyncLocalData(curation: Curation(favorites: favorites, blocked: blocked))
    }
}

final class MemoryStateStore: SyncStateStore {
    private let lock = NSLock()
    private var stored: SyncState?
    var corrupted = false
    /// 从第几次保存起失败（nil 表示不失败）。
    var failFromSave: Int?
    private(set) var saves = 0
    func load() -> SyncStateRead { lock.withLock { corrupted ? .corrupted : (stored.map { .present($0) } ?? .none) } }
    func save(_ state: SyncState) -> Bool {
        lock.withLock {
            saves += 1
            if let n = failFromSave, saves >= n { return false }
            stored = state
            return true
        }
    }
    func delete() -> Bool { lock.withLock { stored = nil }; return true }
}

final class MemoryKeyStore: SyncKeyStore {
    var entry: SyncKeychainEntry?
    var failWrite = false
    var onWrite: (() -> Void)?
    private(set) var writes = 0
    func readEntry() -> SyncKeychainLookup { entry.map { .present($0) } ?? .none }
    func write(_ entry: SyncKeychainEntry) -> SyncKeychainWrite {
        writes += 1
        guard !failWrite else { return .failed }
        self.entry = entry
        onWrite?()
        return .ok
    }
    func read() -> SyncKeychainRead { entry.map { .present(blobID: $0.blobID) } ?? .none }
    var failDelete = false
    func delete() -> Bool {
        guard !failDelete else { return false }
        entry = nil
        return true
    }
}

final class MemorySink: SyncJoinSink {
    var curation = Curation()
    var taste = Taste()
    var settings: [String: Bool] = [:]
    private(set) var saveCount = 0
    /// 第几次保存失败（只这一次；nil 表示不失败）。
    var failOnSave: Int?
    var onSave: (() -> Void)?
    func cancelPendingSaves() {}
    func currentTaste() -> Taste { taste }
    func saveCuration(_ c: Curation) -> Bool {
        saveCount += 1
        if saveCount == failOnSave { return false }
        curation = c
        return true
    }
    func saveTaste(_ t: Taste) -> Bool {
        saveCount += 1
        if saveCount == failOnSave { return false }
        taste = t
        onSave?()
        return true
    }
    func applySettings(_ s: [String: Bool]) { settings.merge(s) { $1 } }
    /// 内存替身没有保存队列：内存即磁盘。
    var failPersist = false
    func persistPending() -> Bool { !failPersist }
    func reloadAfterRollback() {}
}
/// 不经网络的服务端替身：按顺序返回脚本里的响应，记录请求，可在请求里插入等待。
final class ScriptedTransport: SyncTransport, @unchecked Sendable {
    private let lock = NSLock()
    var gets: [SyncServerResponse] = []
    var puts: [SyncServerResponse] = []
    var deletes: [SyncServerResponse] = []
    var fallback: SyncServerResponse = .transient
    private(set) var log: [String] = []
    private(set) var inFlight = 0
    private(set) var maxInFlight = 0
    /// 每个请求在返回前睡眠的纳秒数（模拟慢网络）。
    var delay: UInt64 = 0

    private func next(_ list: ReferenceWritableKeyPath<ScriptedTransport, [SyncServerResponse]>, _ name: String) async -> SyncServerResponse {
        let r: SyncServerResponse = lock.withLock {
            log.append(name)
            inFlight += 1
            maxInFlight = max(maxInFlight, inFlight)
            return self[keyPath: list].isEmpty ? fallback : self[keyPath: list].removeFirst()
        }
        if delay > 0 { try? await Task.sleep(nanoseconds: delay) }
        lock.withLock { inFlight -= 1 }
        return r
    }

    func get() async -> SyncServerResponse { await next(\.gets, "GET") }
    /// PUT 返回之前调用一次（模拟提交途中用户取消或退出）。
    var onPut: (() -> Void)?
    func put(_ request: SyncPutRequest) async -> SyncServerResponse {
        let r = await next(\.puts, "PUT \(request.base)")
        onPut?()
        return r
    }
    func delete(base: UInt64, epoch: String?) async -> SyncServerResponse { await next(\.deletes, "DELETE \(base)") }
}

/// 加入提交的文件快照替身：整份记下 sink 里的收藏、口味与设置，回滚时原样换回。
final class MemoryJournal: SyncJoinJournal {
    let sink: MemorySink
    private var saved: (Curation, Taste, [String: Bool])?
    var failSnapshot = false
    var failRestore = false
    private(set) var restores = 0
    init(_ sink: MemorySink) { self.sink = sink }
    private var committed = false
    var status: SyncJoinJournalStatus { saved == nil ? .none : (committed ? .committed : .pending) }
    func snapshot() -> Bool {
        guard !failSnapshot else { return false }
        saved = (sink.curation, sink.taste, sink.settings)
        committed = false
        return true
    }
    func markCommitted() -> Bool {
        guard saved != nil else { return false }
        committed = true
        return true
    }
    func restore() -> Bool {
        restores += 1
        guard !failRestore, !committed, let saved else { return false }
        (sink.curation, sink.taste, sink.settings) = saved
        return true
    }
    func discard() -> Bool {
        saved = nil
        committed = false
        return true
    }
}

enum ServiceFixture {
    static let origin = "https://sync.example"
    static let epoch = "0123456789abcdef0123456789abcdef"
    static let key = [UInt8](repeating: 5, count: 32)
    static var code: String { SyncCrypto.formatCode(key: key, origin: origin)! }
    static var keys: SyncKeyMaterial { try! SyncCrypto.derive(key) }

    /// 用真实加密封好的远端文档响应。
    static func blob(_ doc: SyncDocument, ver: UInt64, epoch: String = epoch, conflict: Bool = false) -> SyncServerResponse {
        let codec = SyncCryptoCodec(keys: keys)
        let data = codec.seal(doc.encoded(), ver: ver)!
        return conflict ? .conflict(ver: ver, pub: keys.publicKeyText, data: data, epoch: epoch, now: 1_800_000_000_000)
            : .blob(ver: ver, pub: keys.publicKeyText, data: data, epoch: epoch, now: 1_800_000_000_000)
    }

    static func remoteDoc(favorites: [String]) -> SyncDocument {
        var doc = SyncDocument()
        for (i, name) in favorites.enumerated() {
            doc.rooms["cb:" + name] = SyncRoom(f: SyncRegister(hlc: HLC(l: 1_799_999_000_000 + UInt64(i), c: 0, dev: 9), payload: "+"))
        }
        return doc
    }

    static func service(_ transport: ScriptedTransport, store: MemoryStateStore = MemoryStateStore(), keys: MemoryKeyStore = MemoryKeyStore(),
                        journal: SyncJoinJournal = MemoryJournal(MemorySink())) -> SyncService {
        SyncService(env: SyncService.Environment(stateStore: store, keyStore: keys, makeTransport: { _, _ in transport },
                                                 journal: journal,
                                                 wallClock: { 1_800_000_000_000 },
                                                 random: { n in n == 32 ? key : [UInt8](repeating: 1, count: n) }))
    }
}
#endif
