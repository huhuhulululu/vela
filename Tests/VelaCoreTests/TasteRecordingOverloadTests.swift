import XCTest
@testable import VelaCore

final class TasteRecordingOverloadTests: XCTestCase {
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    func room() -> Room {
        Room(username: "a", viewers: 10, gender: nil, tags: ["x"], show: .publicShow)
    }

    func dailyHits(_ taste: Taste) -> Int { taste.daily.values.reduce(0) { $0 + $1.hit } }
    func dailyMisses(_ taste: Taste) -> Int { taste.daily.values.reduce(0) { $0 + $1.miss } }

    /// learnSeconds 走学习，daily 走每日统计；两边相等时与原 recording(watched:) 相同。
    func testLearnSecondsAndDailySecondsAreIndependent() {
        let sample = room()
        let split = Taste().recording(room: sample, learnSeconds: 60, daily: 1, source: .guess, now: now)
        XCTAssertEqual(
            split.posterior(Taste.tagKey("x"), now: now).alpha,
            1 + Feedback.grade(watched: 60).hit,
            accuracy: 1e-9
        )
        XCTAssertEqual(dailyHits(split), 0)
        XCTAssertEqual(dailyMisses(split), 1)

        let learnOnly = Taste().recording(room: sample, learnSeconds: 60, daily: 10, source: .guess, now: now)
        XCTAssertTrue(learnOnly.daily.isEmpty)
        XCTAssertEqual(
            learnOnly.posterior(Taste.tagKey("x"), now: now).alpha,
            1 + Feedback.grade(watched: 60).hit,
            accuracy: 1e-9
        )

        let dailyOnly = Taste().recording(room: sample, learnSeconds: 10, daily: 40, source: .guess, now: now)
        XCTAssertTrue(dailyOnly.beliefs.isEmpty)
        XCTAssertEqual(dailyHits(dailyOnly), 1)
        XCTAssertNotNil(dailyOnly.lastSeen("a"))

        for seconds in [1.0, 10, 25, 60] {
            let original = Taste().recording(room: sample, watched: seconds, source: .guess, now: now)
            let overload = Taste().recording(
                room: sample, learnSeconds: seconds, daily: seconds, source: .guess, now: now
            )
            XCTAssertEqual(original, overload, "watched \(seconds)")
            let other = Taste().recording(room: sample, watched: seconds, source: .popular, now: now)
            let otherOverload = Taste().recording(
                room: sample, learnSeconds: seconds, daily: seconds, source: .popular, now: now
            )
            XCTAssertEqual(other, otherOverload, "popular \(seconds)")
        }

        let paused = Taste().settingPaused(true).recording(
            room: sample, learnSeconds: 60, daily: 1, source: .guess, now: now
        )
        XCTAssertTrue(paused.beliefs.isEmpty)
        XCTAssertTrue(paused.daily.isEmpty)
        XCTAssertNotNil(paused.lastSeen("a"))
    }
}
