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
    func load() -> SyncStateRead { lock.withLock { stored.map { .present($0) } ?? .none } }
    func save(_ state: SyncState) -> Bool { lock.withLock { stored = state }; return true }
    func delete() -> Bool { lock.withLock { stored = nil }; return true }
}

final class MemoryKeyStore: SyncKeyStore {
    var entry: SyncKeychainEntry?
    func readEntry() -> SyncKeychainLookup { entry.map { .present($0) } ?? .none }
    func write(_ entry: SyncKeychainEntry) -> SyncKeychainWrite { self.entry = entry; return .ok }
    func read() -> SyncKeychainRead { entry.map { .present(blobID: $0.blobID) } ?? .none }
    func delete() -> Bool { entry = nil; return true }
}

final class MemorySink: SyncLocalSink {
    var curation = Curation()
    var taste = Taste()
    var settings: [String: Bool] = [:]
    func cancelPendingSaves() {}
    func currentTaste() -> Taste { taste }
    func saveCuration(_ c: Curation) -> Bool { curation = c; return true }
    func saveTaste(_ t: Taste) -> Bool { taste = t; return true }
    func applySettings(_ s: [String: Bool]) { settings.merge(s) { $1 } }
}
#endif
