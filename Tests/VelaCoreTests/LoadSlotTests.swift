import XCTest
@testable import VelaCore

final class LoadSlotTests: XCTestCase {
    // MARK: 翻页占位（F1）

    func testStaleGenerationDoesNotBlockNewGeneration() {
        var slot = LoadSlot()
        XCTAssertTrue(slot.acquire(generation: 1))
        // 旧来源的请求还在飞，新来源的补拉照常发出。
        XCTAssertTrue(slot.acquire(generation: 2))
        // 旧请求收尾不会清掉新请求的占位。
        slot.release(generation: 1)
        XCTAssertFalse(slot.acquire(generation: 2))
        slot.release(generation: 2)
        XCTAssertTrue(slot.acquire(generation: 2))
    }

    func testSameGenerationIsBusyUntilReleased() {
        var slot = LoadSlot()
        XCTAssertTrue(slot.acquire(generation: 5))
        XCTAssertFalse(slot.acquire(generation: 5))
        slot.release(generation: 5)
        XCTAssertTrue(slot.acquire(generation: 5))
    }
}
