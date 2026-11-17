import XCTest
@testable import VelaCore

final class PlaybackErrorTests: XCTestCase {
    func chain(_ pairs: (String, Int)...) -> [(domain: String, code: Int)] { pairs.map { (domain: $0.0, code: $0.1) } }

    func testOfflineFromUnderlyingURLError() {
        let c = chain(("AVFoundationErrorDomain", -11800), (NSURLErrorDomain, -1009))
        XCTAssertEqual(PlaybackErrorCopy.kind(chain: c), .offline)
        XCTAssertTrue(PlaybackErrorCopy.failureMessage(chain: c).contains("网络"))
    }

    func testTimeout() {
        XCTAssertEqual(PlaybackErrorCopy.kind(chain: chain((NSURLErrorDomain, -1001))), .timeout)
    }

    func testUnavailableFromAVFoundation() {
        XCTAssertEqual(PlaybackErrorCopy.kind(chain: chain(("AVFoundationErrorDomain", -11850))), .unavailable)
        XCTAssertEqual(PlaybackErrorCopy.kind(chain: chain(("CoreMediaErrorDomain", -12938))), .unavailable)
    }

    func testUnknownAndEmptyChainFallBackToGeneric() {
        XCTAssertEqual(PlaybackErrorCopy.kind(chain: []), .other)
        XCTAssertEqual(PlaybackErrorCopy.kind(chain: chain(("X", 1))), .other)
    }

    func testEveryMessageIsNonEmptyChinese() {
        for kind in [PlaybackErrorKind.offline, .timeout, .unavailable, .other] {
            let text = PlaybackErrorCopy.message(for: kind)
            XCTAssertFalse(text.isEmpty)
            XCTAssertTrue(text.unicodeScalars.contains { $0.value > 0x4E00 })
        }
        XCTAssertFalse(PlaybackErrorCopy.startTimeoutMessage(chain: []).isEmpty)
        XCTAssertTrue(PlaybackErrorCopy.startTimeoutMessage(chain: chain((NSURLErrorDomain, -1009))).contains("网络"))
    }
}
