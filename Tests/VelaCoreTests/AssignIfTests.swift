#if canImport(Combine)
import Combine
#endif
import XCTest
@testable import VelaCore

final class AssignIfTests: XCTestCase {
    func testEqualValueDoesNotWrite() {
        var stored = "room"
        var writes = 0
        let wrote = AssignIf.changed(from: stored, to: "room") { newValue in
            writes += 1
            stored = newValue
        }
        XCTAssertFalse(wrote)
        XCTAssertEqual(writes, 0)
        XCTAssertEqual(stored, "room")
    }

    func testDifferentValueWritesOnce() {
        var stored: String? = nil
        var seen: [String?] = []
        let wrote = AssignIf.changed(from: stored, to: "room") { newValue in
            seen.append(newValue)
            stored = newValue
        }
        XCTAssertTrue(wrote)
        XCTAssertEqual(seen, ["room"])
        XCTAssertEqual(stored, "room")
        let again = AssignIf.changed(from: stored, to: "room") { newValue in
            seen.append(newValue)
            stored = newValue
        }
        XCTAssertFalse(again)
        XCTAssertEqual(seen, ["room"])
    }
}

/// Linux 上的 XCTest 发现器不能直接调用 `@MainActor` 测试方法，断言放在 `MainActor.run` 里。
final class PlaybackFaceTests: XCTestCase {
    func testSameReadyAndFailedDoNotNotify() async {
        await MainActor.run {
            let face = PlaybackFace()
            var notified = 0
            let token = face.objectWillChange.sink { notified += 1 }
            face.setReady(nil)
            face.setFailed(false)
            for _ in 0..<20 {
                face.setReady("room")
                face.setFailed(true)
            }
            token.cancel()
            XCTAssertEqual(notified, 2)
            XCTAssertEqual(face.readyUsername, "room")
            XCTAssertTrue(face.failed)
        }
    }

    func testClearingReadyNotifiesAgain() async {
        await MainActor.run {
            let face = PlaybackFace()
            face.setReady("room")
            face.setFailed(true)
            var notified = 0
            let token = face.objectWillChange.sink { notified += 1 }
            face.setReady(nil)
            face.setFailed(false)
            face.setReady(nil)
            face.setFailed(false)
            token.cancel()
            XCTAssertEqual(notified, 2)
            XCTAssertNil(face.readyUsername)
            XCTAssertFalse(face.failed)
        }
    }

    func testPlayingNotifiesOnlyWhenTheRoomChanges() async {
        await MainActor.run {
            let face = PlaybackFace()
            var notified = 0
            let token = face.objectWillChange.sink { notified += 1 }
            face.setPlaying("room")
            face.setPlaying("room")
            face.setPlaying(nil)
            face.setPlaying(nil)
            token.cancel()
            XCTAssertEqual(notified, 2)
            XCTAssertNil(face.playingUsername)
        }
    }
}
