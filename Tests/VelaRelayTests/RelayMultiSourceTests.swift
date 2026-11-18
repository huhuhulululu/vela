#if canImport(Network)
import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import VelaRelay
import VelaCore
#else
@testable import VelaPhone
#endif

/// 多源中继：真实回环端口 + `URLProtocol` 假上游。每个用例用自己的路径前缀，互不串扰。
final class RelayMultiSourceTests: XCTestCase {
    private var relay: PlaylistRelay!
    private var tag = ""

    override func setUp() {
        super.setUp()
        tag = UUID().uuidString
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FakeUpstream.self]
        configuration.timeoutIntervalForRequest = 4
        relay = PlaylistRelay(session: URLSession(configuration: configuration))
    }

    override func tearDown() {
        relay.stopListener()
        relay = nil
        FakeUpstream.state.reset(prefix: tag)
        super.tearDown()
    }

    // MARK: 用例

    func testTwoSourcesServeConcurrently() async throws {
        try await listen()
        let a = try register(muxed("a"))
        let b = try register(muxed("b"))
        async let first = get(a.url)
        async let second = get(b.url)
        let (ra, rb) = try await (first, second)
        XCTAssertEqual(ra.status, 200)
        XCTAssertEqual(rb.status, 200)
        XCTAssertTrue(ra.body.contains("/\(tag)/a/seg"), ra.body)
        XCTAssertTrue(rb.body.contains("/\(tag)/b/seg"), rb.body)
        XCTAssertFalse(ra.body.contains("/\(tag)/b/"))
        XCTAssertNotEqual(a.id, b.id)
    }

    func testDeactivatingOneSourceKeepsTheOther() async throws {
        try await listen()
        let a = try register(muxed("a"))
        let b = try register(muxed("b"))
        let status1 = try await get(a.url).status
        XCTAssertEqual(status1, 200)
        let status2 = try await get(b.url).status
        XCTAssertEqual(status2, 200)
        relay.deactivate(source: a.id)
        let status3 = try await get(a.url).status
        XCTAssertEqual(status3, 404)
        let again = try await get(b.url)
        XCTAssertEqual(again.status, 200)
        XCTAssertTrue(again.body.contains("/\(tag)/b/seg"))
    }

    func testForbiddenRenewsOnlyThatSource() async throws {
        try await listen()
        let expired = path("a/video.m3u8")
        FakeUpstream.state.script(expired, statuses: [403, 403, 403, 403])
        let renewedA = Counter()
        let renewedB = Counter()
        let fresh = muxed("a2")
        let a = try register(muxed("a"), renew: { renewedA.bump(); return fresh })
        let b = try register(muxed("b"), renew: { renewedB.bump(); return nil })
        relay.setRefreshing(true, source: a.id)
        relay.setRefreshing(true, source: b.id)

        let ra = try await get(a.url)
        XCTAssertEqual(ra.status, 200)
        XCTAssertTrue(ra.body.contains("/\(tag)/a2/seg"), ra.body)
        let rb = try await get(b.url)
        XCTAssertEqual(rb.status, 200)
        XCTAssertTrue(rb.body.contains("/\(tag)/b/seg"))
        XCTAssertEqual(renewedA.value, 1)
        XCTAssertEqual(renewedB.value, 0)
        XCTAssertFalse(relay.isRenewing(source: b.id))
        XCTAssertGreaterThan(FakeUpstream.state.count(path("b/video.m3u8")), 0)
    }

    func testSuppressingAudioOfOneSourceKeepsTheOther() async throws {
        try await listen()
        let a = try register(separate("a"))
        let b = try register(separate("b"))
        relay.setRefreshing(true, source: a.id)
        relay.setRefreshing(true, source: b.id)
        let audioA = path("a/audio.m3u8")
        let audioB = path("b/audio.m3u8")
        let videoB = path("b/video.m3u8")
        try await waitFor { FakeUpstream.state.count(audioA) >= 1 && FakeUpstream.state.count(audioB) >= 1 }

        relay.setAudioSuppressed(true, source: b.id)
        let beforeA = FakeUpstream.state.count(audioA)
        let beforeB = FakeUpstream.state.count(audioB)
        let beforeVideoB = FakeUpstream.state.count(videoB)
        try await Task.sleep(nanoseconds: 3_000_000_000)

        // A 的音频照常刷新；B 只可能还有停之前已在途的一次；B 的视频不受影响。
        XCTAssertGreaterThanOrEqual(FakeUpstream.state.count(audioA) - beforeA, 2)
        XCTAssertLessThanOrEqual(FakeUpstream.state.count(audioB) - beforeB, 1)
        XCTAssertGreaterThanOrEqual(FakeUpstream.state.count(videoB) - beforeVideoB, 2)
    }

    func testStopListenerInvalidatesOldAddresses() async throws {
        try await listen()
        let a = try register(muxed("a"))
        let status4 = try await get(a.url).status
        XCTAssertEqual(status4, 200)

        relay.stopListener()
        XCTAssertEqual(relay.listeningPort, 0)
        let stale = try? await get(a.url)
        XCTAssertNotEqual(stale?.status, 200)

        try await listen()
        // 源表已清空：旧 id 换到新端口上也找不到。
        let moved = try XCTUnwrap(URL(string: "\(PlaylistRelay.localBase(port: relay.listeningPort))/\(a.id)/master.m3u8"))
        let status5 = try await get(moved).status
        XCTAssertEqual(status5, 404)
    }

    func testRestartBeforeStopCompletesKeepsNewPort() async throws {
        try await listen()
        relay.stopListener()
        XCTAssertTrue(relay.start())
        let ready = await relay.waitUntilListening(timeout: 3)
        XCTAssertTrue(ready)
        let port = relay.listeningPort
        XCTAssertNotEqual(port, 0)

        // 旧监听的「已取消」回调迟到也不能清掉新端口，也不能让下一次 start 另起一个监听。
        try await Task.sleep(nanoseconds: 500_000_000)
        XCTAssertEqual(relay.listeningPort, port)
        XCTAssertTrue(relay.start())
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(relay.listeningPort, port)

        let a = try register(muxed("a"))
        XCTAssertTrue(a.url.absoluteString.hasPrefix(PlaylistRelay.localBase(port: port)))
        let status6 = try await get(a.url).status
        XCTAssertEqual(status6, 200)
    }

    func testAllowStartOffRefusesToListen() async throws {
        relay.allowStart = false
        XCTAssertFalse(relay.start())
        let ready = await relay.waitUntilListening(timeout: 0.3)
        XCTAssertFalse(ready)
        XCTAssertEqual(relay.listeningPort, 0)
        XCTAssertNil(relay.localURL(for: muxed("a"), maxBandwidth: .infinity, audioSuppressed: false))

        relay.allowStart = true
        try await listen()
        XCTAssertNotEqual(relay.listeningPort, 0)
    }

    // MARK: 工具

    private func path(_ suffix: String) -> String { "/\(tag)/\(suffix)" }

    private func upstream(_ suffix: String) -> URL {
        URL(string: "https://\(FakeUpstream.host)\(path(suffix))")!
    }

    private func muxed(_ name: String) -> StreamRendition {
        StreamRendition(video: upstream("\(name)/video.m3u8"))
    }

    private func separate(_ name: String) -> StreamRendition {
        StreamRendition(
            video: upstream("\(name)/video.m3u8"), audio: upstream("\(name)/audio.m3u8"),
            videoAttributes: HLSAttribute.parse("BANDWIDTH=800000,AUDIO=\"aud\""),
            audioAttributes: HLSAttribute.parse("TYPE=AUDIO,GROUP-ID=\"aud\"")
        )
    }

    private func listen() async throws {
        let ready = await relay.waitUntilListening(timeout: 3)
        XCTAssertTrue(ready)
        XCTAssertNotEqual(relay.listeningPort, 0)
    }

    private func register(
        _ rendition: StreamRendition, renew: (@Sendable () async -> StreamRendition?)? = nil
    ) throws -> (id: String, url: URL) {
        try XCTUnwrap(relay.localURL(for: rendition, maxBandwidth: .infinity, audioSuppressed: false, renew: renew))
    }

    private func get(_ url: URL) async throws -> (status: Int, body: String) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.connectionProxyDictionary = [:]
        configuration.timeoutIntervalForRequest = 15
        let session = URLSession(configuration: configuration)
        defer { session.finishTasksAndInvalidate() }
        let (data, response) = try await session.data(from: url)
        let status = (response as? HTTPURLResponse)?.statusCode ?? -1
        return (status, String(decoding: data, as: UTF8.self))
    }

    private func waitFor(timeout: TimeInterval = 5, _ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            guard Date() < deadline else {
                XCTFail("等待超时")
                return
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
    }
}

/// 线程安全计数。
private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    var value: Int { lock.withLock { count } }
    func bump() { lock.withLock { count += 1 } }
}

/// 假上游：按路径计数，按脚本回状态码（脚本用完后回 200）。200 时回一个只含一个完整分片的媒体列表，
/// 媒体序号随请求次数前进，中继据此判断「有进展」。
final class FakeUpstream: URLProtocol {
    static let host = "upstream.test"
    static let state = State()

    final class State: @unchecked Sendable {
        private let lock = NSLock()
        private var counts: [String: Int] = [:]
        private var scripts: [String: [Int]] = [:]

        func script(_ path: String, statuses: [Int]) {
            lock.withLock { scripts[path] = statuses }
        }

        func count(_ path: String) -> Int {
            lock.withLock { counts[path] ?? 0 }
        }

        /// 记一次请求，返回（状态码，第几次）。
        func hit(_ path: String) -> (status: Int, n: Int) {
            lock.withLock {
                let n = (counts[path] ?? 0) + 1
                counts[path] = n
                var queue = scripts[path] ?? []
                let status = queue.isEmpty ? 200 : queue.removeFirst()
                scripts[path] = queue
                return (status, n)
            }
        }

        func reset(prefix: String) {
            lock.withLock {
                counts = counts.filter { !$0.key.hasPrefix("/\(prefix)/") }
                scripts = scripts.filter { !$0.key.hasPrefix("/\(prefix)/") }
            }
        }
    }

    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.host == host
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        let (status, n) = Self.state.hit(url.path)
        let body = status == 200 ? Self.playlist(sequence: n) : ""
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func playlist(sequence: Int) -> String {
        """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:1
        #EXT-X-MEDIA-SEQUENCE:\(sequence)
        #EXTINF:1.0,
        seg\(sequence).ts

        """
    }
}
#endif
