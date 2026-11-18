import XCTest
#if SWIFT_PACKAGE
@testable import VelaCore
#else
@testable import VelaPhone
#endif

/// 播放失败分类。损坏码流按房间重试；只有双窗副路的解码器暂时不可用才降级。
final class PlaybackFailureClassifierTests: XCTestCase {
    private func chain(_ pairs: (String, Int)...) -> [(domain: String, code: Int)] {
        pairs.map { (domain: $0.0, code: $0.1) }
    }

    /// 最容易被误判成「双窗不可用」的上下文：副路、双窗、另一路在播、这路还没出过画面。
    private func secondaryDual(
        otherIsPlaying: Bool = true,
        hasPlayed: Bool = false
    ) -> PlaybackFailureClassifier.Context {
        PlaybackFailureClassifier.Context(
            isDualWindow: true,
            isSecondary: true,
            otherIsPlaying: otherIsPlaying,
            hasPlayed: hasPlayed
        )
    }

    /// 损坏码流（decodeFailed）和找不到解码器，在副路双窗下仍是房间失败，不是资源降级。
    func testCorruptStreamIsRoomRetry() {
        let decodeFailed = chain(("AVFoundationErrorDomain", -11821))
        let decoderNotFound = chain(("AVFoundationErrorDomain", -11833))
        let wrapped = chain(("AVFoundationErrorDomain", -11800), ("AVFoundationErrorDomain", -11821))
        for sample in [decodeFailed, decoderNotFound, wrapped] {
            XCTAssertEqual(
                PlaybackFailureClassifier.classify(chain: sample, context: secondaryDual()),
                .roomRetry
            )
            XCTAssertEqual(
                PlaybackFailureClassifier.classify(chain: sample, context: secondaryDual(hasPlayed: true)),
                .roomRetry
            )
        }
    }

    /// 副路、双窗，错误链里有 decoderTemporarilyUnavailable（-11839）才降级。包在外层错误里也认。
    func testSecondaryDualWindowDecoderTemporarilyUnavailableIsResourceDegrade() {
        XCTAssertEqual(PlaybackFailureClassifier.decoderTemporarilyUnavailableCode, -11839)
        XCTAssertEqual(PlaybackFailureClassifier.avFoundationErrorDomain, "AVFoundationErrorDomain")
        let direct = chain(("AVFoundationErrorDomain", -11839))
        let wrapped = chain(("AVFoundationErrorDomain", -11800), ("AVFoundationErrorDomain", -11839))
        for sample in [direct, wrapped] {
            XCTAssertEqual(
                PlaybackFailureClassifier.classify(chain: sample, context: secondaryDual()),
                .resourceDegrade
            )
            XCTAssertEqual(
                PlaybackFailureClassifier.classify(chain: sample, context: secondaryDual(hasPlayed: true)),
                .resourceDegrade
            )
        }
        // 码对了、域不对，或者只是相邻的编码器不可用：仍按房间失败。
        let foreignDomain = chain(("OtherErrorDomain", -11839))
        let encoderUnavailable = chain(("AVFoundationErrorDomain", -11840))
        XCTAssertEqual(
            PlaybackFailureClassifier.classify(chain: foreignDomain, context: secondaryDual()),
            .roomRetry
        )
        XCTAssertEqual(
            PlaybackFailureClassifier.classify(chain: encoderUnavailable, context: secondaryDual()),
            .roomRetry
        )
    }

    /// 主路撞上同一个 -11839，仍是房间重试。主路不降级。
    func testPrimaryDecoderTemporarilyUnavailableIsRoomRetry() {
        let sample = chain(("AVFoundationErrorDomain", -11839))
        let primary = PlaybackFailureClassifier.Context(
            isDualWindow: true,
            isSecondary: false,
            otherIsPlaying: true,
            hasPlayed: false
        )
        XCTAssertEqual(PlaybackFailureClassifier.classify(chain: sample, context: primary), .roomRetry)
        var played = primary
        played.hasPlayed = true
        XCTAssertEqual(PlaybackFailureClassifier.classify(chain: sample, context: played), .roomRetry)
    }

    /// 单窗永不判资源降级，哪怕标志被写成副路。
    func testSingleWindowDecoderTemporarilyUnavailableIsRoomRetry() {
        let sample = chain(("AVFoundationErrorDomain", -11839))
        let single = PlaybackFailureClassifier.Context(
            isDualWindow: false,
            isSecondary: false,
            otherIsPlaying: false,
            hasPlayed: false
        )
        XCTAssertEqual(PlaybackFailureClassifier.classify(chain: sample, context: single), .roomRetry)
        var markedSecondary = single
        markedSecondary.isSecondary = true
        markedSecondary.otherIsPlaying = true
        XCTAssertEqual(
            PlaybackFailureClassifier.classify(chain: sample, context: markedSecondary),
            .roomRetry
        )
    }
}
