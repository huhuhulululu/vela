import XCTest
#if SWIFT_PACKAGE
@testable import VelaCore
#else
@testable import VelaPhone
#endif

final class LaneQualityTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    /// 尺寸上限（ios-triple 修订 v2 H2）：显示 1、2 路时不限；3 路按格子里画面的像素高度选不超过它的最高档，下限 896k。
    func testSizeCapOnlyAppliesToThreeLanes() {
        XCTAssertEqual(LaneQuality.sizeCap(displayHeight: 221, screenScale: 3, shownLanes: 1), .infinity)
        XCTAssertEqual(LaneQuality.sizeCap(displayHeight: 221, screenScale: 3, shownLanes: 2), .infinity, "两路维持最高档")
        XCTAssertEqual(LaneQuality.sizeCap(displayHeight: 226, screenScale: 3, shownLanes: 3), 1_696_000, "226pt x 3 = 678px")
        XCTAssertEqual(LaneQuality.sizeCap(displayHeight: 402, screenScale: 2, shownLanes: 3), 3_096_000, "iPad 三格 804px")
        XCTAssertEqual(LaneQuality.sizeCap(displayHeight: 400, screenScale: 3, shownLanes: 3), 5_128_000)
        XCTAssertEqual(LaneQuality.sizeCap(displayHeight: 100, screenScale: 2, shownLanes: 3), LaneQuality.minimumBitrate)
        XCTAssertEqual(LaneQuality.sizeCap(displayHeight: 0, screenScale: 3, shownLanes: 3), LaneQuality.minimumBitrate, "还没有尺寸落到下限")
        XCTAssertEqual(LaneQuality.sizeCap(displayHeight: .nan, screenScale: 3, shownLanes: 3), LaneQuality.minimumBitrate)
    }

    /// 热上限：serious 降一档、critical 降两档，以当前上限为基准逐档下降，已是最低档就保持最低档；最终取两者较小。
    func testThermalStepsDownFromTheCurrentCap() {
        var cool = LaneQuality()
        XCTAssertFalse(cool.isLimited)
        XCTAssertEqual(cool.cap(displayHeight: 221, screenScale: 3, shownLanes: 2), .infinity)
        cool.observe(.fair, now: t0)
        XCTAssertEqual(cool.cap(displayHeight: 226, screenScale: 3, shownLanes: 3), 1_696_000, "不热时只有尺寸上限")

        var serious = LaneQuality()
        serious.observe(.serious, now: t0)
        XCTAssertTrue(serious.isLimited)
        XCTAssertEqual(serious.cap(displayHeight: 221, screenScale: 3, shownLanes: 2), 3_096_000, "不限 = 最高档 5.128M，降一档")
        XCTAssertEqual(serious.cap(displayHeight: 226, screenScale: 3, shownLanes: 3), 896_000, "1.696M 降一档")
        XCTAssertEqual(serious.cap(displayHeight: 402, screenScale: 2, shownLanes: 3), 1_696_000)
        XCTAssertEqual(serious.cap(displayHeight: 100, screenScale: 2, shownLanes: 3), 896_000, "已是最低档保持最低档")

        var critical = LaneQuality()
        critical.observe(.critical, now: t0)
        XCTAssertEqual(critical.cap(displayHeight: 221, screenScale: 3, shownLanes: 1), 1_696_000, "降两档")
        XCTAssertEqual(critical.cap(displayHeight: 402, screenScale: 2, shownLanes: 3), 896_000)
        XCTAssertEqual(critical.thermalCap(below: 1_000_000), 896_000)
    }

    func testCriticalEscalatesAndCoolingDownBelowCriticalKeepsTheDeeperCapUntilRecovery() {
        var quality = LaneQuality()
        quality.observe(.serious, now: t0)
        XCTAssertEqual(quality.cap(displayHeight: 0, screenScale: 3, shownLanes: 1), 3_096_000)
        quality.observe(.critical, now: t0.addingTimeInterval(5))
        XCTAssertEqual(quality.cap(displayHeight: 0, screenScale: 3, shownLanes: 1), 1_696_000)
        quality.observe(.serious, now: t0.addingTimeInterval(10))
        XCTAssertEqual(quality.cap(displayHeight: 0, screenScale: 3, shownLanes: 1), 1_696_000, "仍在发热：不逐级回升")
    }

    /// 回到 .fair 及以下后，第 59 秒不回升，满 60 秒才回升；中途再热则重新计时。回升到尺寸上限而不是不限。
    func testThermalStaysLimitedAt59SecondsAndRecoversAt60() {
        var quality = LaneQuality()
        quality.observe(.serious, now: t0)
        quality.observe(.fair, now: t0)
        quality.observe(.fair, now: t0.addingTimeInterval(59))
        XCTAssertTrue(quality.isLimited)
        XCTAssertEqual(quality.cap(displayHeight: 226, screenScale: 3, shownLanes: 3), 896_000)

        quality.observe(.serious, now: t0.addingTimeInterval(59))
        quality.observe(.fair, now: t0.addingTimeInterval(59))
        quality.observe(.nominal, now: t0.addingTimeInterval(59 + 59))
        XCTAssertTrue(quality.isLimited)
        quality.observe(.nominal, now: t0.addingTimeInterval(59 + 60))
        XCTAssertFalse(quality.isLimited)
        XCTAssertEqual(quality.cap(displayHeight: 226, screenScale: 3, shownLanes: 3), 1_696_000, "回升到尺寸上限")
        XCTAssertEqual(quality.cap(displayHeight: 226, screenScale: 3, shownLanes: 2), .infinity)

        var direct = LaneQuality()
        direct.observe(.critical, now: t0)
        direct.observe(.nominal, now: t0)
        direct.observe(.fair, now: t0.addingTimeInterval(60))
        XCTAssertFalse(direct.isLimited)
        XCTAssertEqual(LaneQuality.minimumBitrate, 896_000)
        XCTAssertEqual(LaneQuality.ladder, [896_000, 1_696_000, 3_096_000, 5_128_000])
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
