import XCTest
@testable import VelaCore

final class DeferredSaverTests: XCTestCase {
    func testDeferredSaverCoalescesAndFlushWritesLatest() {
        let box = Box()
        let saver = DeferredSaver<Int>(delay: 0.05) { box.add($0) }
        saver.schedule(1)
        saver.schedule(2)
        saver.schedule(3)
        saver.flush()
        XCTAssertEqual(box.values, [3])
        saver.flush()
        XCTAssertEqual(box.values, [3])
        saver.schedule(4)
        Thread.sleep(forTimeInterval: 0.3)
        XCTAssertEqual(box.values, [3, 4])
    }

    func testDeferredSaverWritesNeverOverlap() {
        let box = Box()
        let saver = DeferredSaver<Int>(delay: 0.001) { box.enter($0) }
        DispatchQueue.concurrentPerform(iterations: 200) { saver.schedule($0) }
        saver.flush()
        Thread.sleep(forTimeInterval: 0.1)
        XCTAssertFalse(box.overlapped)
    }
}

private final class Box: @unchecked Sendable {
    private let lock = NSLock()
    private var inside = false
    private(set) var values: [Int] = []
    private(set) var overlapped = false

    func add(_ v: Int) {
        lock.lock(); values.append(v); lock.unlock()
    }

    func enter(_ v: Int) {
        lock.lock()
        if inside { overlapped = true }
        inside = true
        lock.unlock()
        Thread.sleep(forTimeInterval: 0.002)
        lock.lock(); inside = false; lock.unlock()
    }
}
