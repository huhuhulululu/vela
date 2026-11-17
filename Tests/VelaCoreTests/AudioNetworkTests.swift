import XCTest
@testable import VelaCore

final class AudioNetworkTests: XCTestCase {
    // A3：键不存在（首次启动）默认静音；存过的值保留，包括用户显式关过静音。
    func testFirstLaunchIsMutedAndStoredChoiceIsKept() {
        XCTAssertTrue(AudioPolicy.initialMuted(stored: nil))
        XCTAssertFalse(AudioPolicy.initialMuted(stored: false), "显式关过静音的用户保留")
        XCTAssertTrue(AudioPolicy.initialMuted(stored: true))
    }

    // B5：音量为 0 时取消静音回到 0.2，其余不变。
    func testUnmuteAtZeroVolumeRestoresSmallVolume() {
        XCTAssertEqual(AudioPolicy.volumeOnUnmute(current: 0), 0.2)
        XCTAssertEqual(AudioPolicy.volumeOnUnmute(current: -0.1), 0.2)
        XCTAssertEqual(AudioPolicy.volumeOnUnmute(current: 0.6), 0.6)
        XCTAssertEqual(AudioPolicy.volumeOnUnmute(current: 0.01), 0.01)
    }

    // A4：断网类错误归为「网络不通」，取消与其他错误不算。
    func testOfflineClassification() {
        XCTAssertTrue(NetworkFailure.isOffline(URLError(.notConnectedToInternet)))
        XCTAssertTrue(NetworkFailure.isOffline(URLError(.timedOut)))
        XCTAssertTrue(NetworkFailure.isOffline(URLError(.cannotFindHost)))
        XCTAssertFalse(NetworkFailure.isOffline(URLError(.cancelled)))
        XCTAssertFalse(NetworkFailure.isOffline(URLError(.badServerResponse)))
        XCTAssertFalse(NetworkFailure.isOffline(CancellationError()))
        XCTAssertTrue(NetworkFailure.isDisconnected(URLError(.notConnectedToInternet)))
        XCTAssertFalse(NetworkFailure.isDisconnected(URLError(.timedOut)), "超时仍可能是线路问题，保留侧车备路")
    }
}
