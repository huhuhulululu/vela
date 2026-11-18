import XCTest
#if SWIFT_PACKAGE
@testable import VelaCore
#else
@testable import VelaPhone
#endif

final class LaneQualityTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    /// .serious 及以上按 displayHeight × screenScale 降档：663px → 1.696M，且不低于 896k。未发热不封顶。
    func testSeriousCapsByDisplaySize663To1696kFloor896k() {
        var cool = LaneQuality()
        XCTAssertFalse(cool.isLimited)
        XCTAssertEqual(cool.peakBitrate(displayHeight: 221, screenScale: 3), .infinity)
        XCTAssertEqual(cool.peakBitrate(displayHeight: 400, screenScale: 3), .infinity)
        cool.observe(.fair, now: t0.addingTimeInterval(600))
        XCTAssertEqual(cool.peakBitrate(displayHeight: 221, screenScale: 3), .infinity)

        var hot = LaneQuality()
        hot.observe(.serious, now: t0)
        XCTAssertTrue(hot.isLimited)
        XCTAssertEqual(hot.peakBitrate(displayHeight: 221, screenScale: 1), 896_000)
        XCTAssertEqual(hot.peakBitrate(displayHeight: 221, screenScale: 3), 1_696_000)
        XCTAssertEqual(hot.peakBitrate(displayHeight: 663, screenScale: 1), 1_696_000)
        XCTAssertEqual(hot.peakBitrate(displayHeight: 400, screenScale: 3), 5_128_000)
        XCTAssertEqual(hot.peakBitrate(displayHeight: 10, screenScale: 1), LaneQuality.minimumBitrate)
        XCTAssertEqual(hot.peakBitrate(displayHeight: 0, screenScale: 3), LaneQuality.minimumBitrate)
        XCTAssertGreaterThanOrEqual(hot.peakBitrate(displayHeight: 1, screenScale: 1), LaneQuality.minimumBitrate)
        XCTAssertEqual(LaneQuality.minimumBitrate, 896_000)

        var critical = LaneQuality()
        critical.observe(.critical, now: t0)
        XCTAssertEqual(critical.peakBitrate(displayHeight: 663, screenScale: 1), 1_696_000)
    }

    /// 回到 .fair 及以下后，第 59 秒不回升，满 60 秒才回升；中途再热则重新计时。
    func testThermalStaysLimitedAt59SecondsAndRecoversAt60() {
        var quality = LaneQuality()
        quality.observe(.serious, now: t0)
        quality.observe(.fair, now: t0)
        quality.observe(.fair, now: t0.addingTimeInterval(59))
        XCTAssertTrue(quality.isLimited)
        XCTAssertEqual(quality.peakBitrate(displayHeight: 663, screenScale: 1), 1_696_000)

        quality.observe(.serious, now: t0.addingTimeInterval(59))
        quality.observe(.fair, now: t0.addingTimeInterval(59))
        quality.observe(.nominal, now: t0.addingTimeInterval(59 + 59))
        XCTAssertTrue(quality.isLimited)
        quality.observe(.nominal, now: t0.addingTimeInterval(59 + 60))
        XCTAssertFalse(quality.isLimited)
        XCTAssertEqual(quality.peakBitrate(displayHeight: 663, screenScale: 1), .infinity)

        var direct = LaneQuality()
        direct.observe(.critical, now: t0)
        direct.observe(.nominal, now: t0)
        direct.observe(.fair, now: t0.addingTimeInterval(60))
        XCTAssertFalse(direct.isLimited)
        XCTAssertEqual(direct.peakBitrate(displayHeight: 221, screenScale: 3), .infinity)
    }
}

/// 回升等待：只在降档且已回到 fair 及以下时有值，便于只排一次性的检查。
final class LaneQualityRecoveryRemainingTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func testNilWhenNotLimitedOrStillHot() {
        var quality = LaneQuality()
        XCTAssertNil(quality.recoveryRemaining(now: t0))
        quality.observe(.serious, now: t0)
        XCTAssertNil(quality.recoveryRemaining(now: t0.addingTimeInterval(100)), "还在发热不排回升")
    }

    func testCountsDownFromFairAndResetsOnReheat() {
        var quality = LaneQuality()
        quality.observe(.serious, now: t0)
        quality.observe(.fair, now: t0.addingTimeInterval(10))
        XCTAssertEqual(quality.recoveryRemaining(now: t0.addingTimeInterval(10)) ?? -1, 60, accuracy: 0.001)
        XCTAssertEqual(quality.recoveryRemaining(now: t0.addingTimeInterval(40)) ?? -1, 30, accuracy: 0.001)
        XCTAssertEqual(quality.recoveryRemaining(now: t0.addingTimeInterval(500)) ?? -1, 0, accuracy: 0.001)
        quality.observe(.serious, now: t0.addingTimeInterval(50))
        XCTAssertNil(quality.recoveryRemaining(now: t0.addingTimeInterval(60)))
    }

    func testNilAfterRecovered() {
        var quality = LaneQuality()
        quality.observe(.serious, now: t0)
        quality.observe(.nominal, now: t0.addingTimeInterval(1))
        quality.observe(.nominal, now: t0.addingTimeInterval(61))
        XCTAssertFalse(quality.isLimited)
        XCTAssertNil(quality.recoveryRemaining(now: t0.addingTimeInterval(62)))
    }
}
