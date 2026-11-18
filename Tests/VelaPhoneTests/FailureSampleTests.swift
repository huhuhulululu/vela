import AVFoundation
import XCTest
@testable import VelaPhone

/// 损坏码流样本（T19，§4.4）：用真的 AVPlayerItem 读一份损坏文件，拿系统给出的错误链，
/// 证明它在双窗副路也只是「房间失败」，不会被误判成「双窗不可用」而释放副路。
@MainActor
final class FailureSampleTests: XCTestCase {
    private var suites: [String] = []
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("vela-failure-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func setUp() {
        super.setUp()
        TestPrefs.reset()
    }

    override func tearDown() {
        TestPrefs.reset()
        try? FileManager.default.removeItem(at: dir)
        for name in suites { UserDefaults().removePersistentDomain(forName: name) }
        suites = []
        super.tearDown()
    }

    private func makeDualStage() -> StageModel {
        let name = "vela-phone-failure-\(UUID().uuidString)"
        suites.append(name)
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!))
        stage.updateFeed(intent: .reload) {
            $0.replace(with: CatalogPage(rooms: ["alice", "bob"].map { Room.direct(username: $0)! }, total: 2, nextOffset: 2))
        }
        stage.setDual(true)
        // 另一路在播、副路还没出过画面：最容易被误判成资源不足的现场。
        stage.primary.playingName = "alice"
        return stage
    }

    /// 固定伪随机字节：每次生成同一份损坏内容。
    private func garbage(_ count: Int) -> Data {
        var state: UInt64 = 0x9E37_79B9_7F4A_7C15
        return Data((0..<count).map { _ in
            state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return UInt8(truncatingIfNeeded: state >> 33)
        })
    }

    /// 让系统去读这份文件，等到播放项失败，返回错误链。读不出失败就让用例失败（不空通过）。
    private func failureChain(of url: URL, file: StaticString = #filePath, line: UInt = #line) async -> [(domain: String, code: Int)] {
        let item = AVPlayerItem(url: url)
        let player = AVPlayer(playerItem: item)
        player.isMuted = true
        defer { player.replaceCurrentItem(with: nil) }
        for _ in 0..<150 where item.status == .unknown {
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertEqual(item.status, .failed, "损坏样本必须真的让播放项失败", file: file, line: line)
        return PlayerController.errorChain(item.error)
    }

    private func info(_ chain: [(domain: String, code: Int)]) -> PlaybackFailureInfo {
        PlaybackFailureInfo(stage: "item-failed", username: "bob", chain: chain, message: "")
    }

    private func assertRoomFailure(_ chain: [(domain: String, code: Int)], file: StaticString = #filePath, line: UInt = #line) {
        let stage = makeDualStage()
        let secondary = stage.secondary!
        XCTAssertFalse(chain.isEmpty, file: file, line: line)
        let degrade = PlaybackFailureClassifier.resourceDegradeWhitelist
        XCTAssertFalse(
            chain.contains { entry in degrade.contains { $0.domain == entry.domain && $0.code == entry.code } },
            "样本的错误码不在资源降级白名单里：\(chain)", file: file, line: line
        )
        XCTAssertEqual(stage.classifyFailure(info(chain), on: secondary), .roomRetry, "\(chain)", file: file, line: line)
        // 播放器真正调用的就是这个钩子。
        XCTAssertEqual(secondary.player.failureFilter?(info(chain)), .roomRetry, file: file, line: line)
        XCTAssertNotEqual(secondary.state, .degraded, file: file, line: line)
        XCTAssertFalse(secondary.degradedHold.held, "没有被资源降级扣住", file: file, line: line)
    }

    func testGarbageMovieFileIsRoomFailureOnSecondary() async throws {
        let url = dir.appendingPathComponent("garbage.mp4")
        try garbage(16_384).write(to: url)
        assertRoomFailure(await failureChain(of: url))
    }

    func testTruncatedMovieHeaderIsRoomFailureOnSecondary() async throws {
        // 像 mp4 的开头（ftyp 盒）后面全是垃圾：容器认得、内容读不出。
        var bytes = Data([0, 0, 0, 0x18]) + Data("ftypisom".utf8) + Data([0, 0, 2, 0]) + Data("isomiso2".utf8)
        bytes.append(garbage(8_192))
        let url = dir.appendingPathComponent("truncated.mp4")
        try bytes.write(to: url)
        assertRoomFailure(await failureChain(of: url))
    }

    func testHLSPlaylistWithGarbageSegmentIsRoomFailureOnSecondary() async throws {
        try garbage(32_768).write(to: dir.appendingPathComponent("seg0.ts"))
        let playlist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:2
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:2.0,
        seg0.ts
        #EXT-X-ENDLIST
        """
        let url = dir.appendingPathComponent("index.m3u8")
        try playlist.write(to: url, atomically: true, encoding: .utf8)
        assertRoomFailure(await failureChain(of: url))
    }

    func testDecodeFailureAndMissingDecoderCodesStayRoomFailureInDualWindow() {
        let stage = makeDualStage()
        let secondary = stage.secondary!
        for code in [-11821, -11833, -11800, -11829] {
            let chain = [(domain: PlaybackFailureClassifier.avFoundationErrorDomain, code: code)]
            XCTAssertEqual(stage.classifyFailure(info(chain), on: secondary), .roomRetry, "\(code)")
        }
    }

    func testOnlyWhitelistedDecoderCodeOnSecondaryDegradesAndPrimaryNever() {
        let stage = makeDualStage()
        let chain = [(domain: PlaybackFailureClassifier.avFoundationErrorDomain, code: PlaybackFailureClassifier.decoderTemporarilyUnavailableCode)]
        XCTAssertEqual(stage.classifyFailure(info(chain), on: stage.secondary!), .resourceDegrade)
        XCTAssertEqual(stage.classifyFailure(info(chain), on: stage.primary), .roomRetry, "主路没有可牺牲的另一路")
        stage.setDual(false)
        XCTAssertEqual(stage.classifyFailure(info(chain), on: stage.primary), .roomRetry, "单窗恒为房间重试")
    }

    func testDegradeIsOnlyReachedThroughTheWhitelistHook() {
        let stage = makeDualStage()
        let secondary = stage.secondary!
        let chain = [(domain: PlaybackFailureClassifier.avFoundationErrorDomain, code: PlaybackFailureClassifier.decoderTemporarilyUnavailableCode)]
        secondary.player.resourceDegraded?(info(chain))
        XCTAssertEqual(secondary.state, .degraded)
        XCTAssertEqual(secondary.playbackNote, "双窗暂不可用")
        XCTAssertTrue(secondary.degradedHold.held)
        XCTAssertEqual(stage.primary.state, .idle, "另一路不受影响")
    }
}
