import AVFoundation
import XCTest
@testable import VelaPhone

/// 双窗切焦点的声音转换（§3.3、S11）：同一时刻只有焦点路出声，转换有序，失败时全部静音。会话用桩。
@MainActor
final class DualAudioFocusTests: XCTestCase {
    private final class FakeDriver: AudioSessionDriving {
        struct Failure: Error {}
        var calls: [String] = []
        var failActive = false
        var failCategory = false
        var onCategory: () -> Void = {}
        var onActive: () -> Void = {}

        func setCategory(_ category: AudioSessionCategory, mode: AudioSessionMode?, options: AudioSessionOptions) throws {
            onCategory()
            calls.append("category \(category) mix=\(options.contains(.mixWithOthers))")
            if failCategory { throw Failure() }
        }

        func setActive(_ active: Bool, notifyOthers: Bool) throws {
            onActive()
            calls.append("active \(active) notify=\(notifyOthers)")
            if failActive { throw Failure() }
        }
    }

    private var suites: [String] = []

    override func setUp() {
        super.setUp()
        TestPrefs.reset()
    }

    override func tearDown() {
        TestPrefs.reset()
        for name in suites { UserDefaults().removePersistentDomain(forName: name) }
        suites = []
        super.tearDown()
    }

    private func make(muted: Bool) -> (StageModel, AudioFocusController, FakeDriver) {
        let name = "vela-phone-dualaudio-\(UUID().uuidString)"
        suites.append(name)
        let settings = SettingsStore(defaults: UserDefaults(suiteName: name)!)
        let stage = TestPrefs.stage(settings: settings)
        stage.updateFeed(installing: nil, intent: .reload) {
            $0.replace(with: CatalogPage(rooms: ["alice", "bob", "carol"].map { Room.direct(username: $0)! }, total: 3, nextOffset: 3))
        }
        let driver = FakeDriver()
        let controller = AudioFocusController(stage: stage, driver: driver)
        stage.audioFocus = controller
        stage.setDual(true)
        if !muted {
            stage.toggleMute()
            XCTAssertFalse(stage.muted)
        }
        driver.calls = []
        return (stage, controller, driver)
    }

    private func audible(_ stage: StageModel) -> [LaneID] {
        stage.lanes.filter { !$0.player.player.isMuted && !stage.muted }.map(\.id)
    }

    func testFocusSwitchWhenUnmutedMutesOldFirstThenSessionThenUnmutesNew() {
        let (stage, _, driver) = make(muted: false)
        XCTAssertEqual(audible(stage), [.primary], "只有焦点路出声")
        var seenAtCategory: [LaneID] = []
        var seenAtActive: [LaneID] = []
        driver.onCategory = { seenAtCategory = self.audible(stage) }
        driver.onActive = { seenAtActive = self.audible(stage) }
        stage.setFocus(.secondary)
        XCTAssertEqual(seenAtCategory, [], "改类别时旧的出声路已静音，新路还没出声")
        XCTAssertEqual(seenAtActive, [], "激活会话时仍没有任何路出声")
        XCTAssertEqual(driver.calls, ["category playback mix=false", "active true notify=false"])
        XCTAssertEqual(audible(stage), [.secondary])
        XCTAssertFalse(stage.muted)
        XCTAssertEqual(stage.focusLane, .secondary)
    }

    func testFocusSwitchBackAndForthNeverHasTwoAudibleLanes() {
        let (stage, _, driver) = make(muted: false)
        var maxAudible = 0
        driver.onCategory = { maxAudible = max(maxAudible, self.audible(stage).count) }
        driver.onActive = { maxAudible = max(maxAudible, self.audible(stage).count) }
        for id in [LaneID.secondary, .primary, .secondary, .primary] {
            stage.setFocus(id)
            maxAudible = max(maxAudible, audible(stage).count)
            XCTAssertEqual(audible(stage), [id])
        }
        XCTAssertLessThanOrEqual(maxAudible, 1)
    }

    func testFocusSwitchWhileMutedDoesNotTouchSession() {
        let (stage, _, driver) = make(muted: true)
        stage.setFocus(.secondary)
        XCTAssertEqual(driver.calls, [])
        XCTAssertEqual(audible(stage), [])
        XCTAssertTrue(stage.muted)
        XCTAssertEqual(stage.focusLane, .secondary)
    }

    func testCategoryFailureKeepsAllLanesMutedAndDoesNotWriteSettings() {
        let (stage, _, driver) = make(muted: false)
        driver.failCategory = true
        stage.settings.muted = false
        stage.setFocus(.secondary)
        XCTAssertTrue(stage.muted, "运行时静音")
        XCTAssertFalse(stage.settings.muted, "不写进设置")
        XCTAssertEqual(audible(stage), [])
        XCTAssertEqual(stage.toast, "暂时无法出声")
        XCTAssertEqual(stage.focusLane, .secondary, "焦点照常切换，只是不出声")
    }

    func testActivationFailureKeepsAllLanesMuted() {
        let (stage, _, driver) = make(muted: false)
        driver.failActive = true
        stage.setFocus(.secondary)
        XCTAssertTrue(stage.muted)
        XCTAssertEqual(audible(stage), [])
        XCTAssertEqual(stage.toast, "暂时无法出声")
    }

    func testUnmuteTargetsTheFocusLane() {
        let (stage, _, driver) = make(muted: true)
        stage.setFocus(.secondary)
        stage.toggleMute()
        XCTAssertEqual(driver.calls, ["category playback mix=false", "active true notify=false"])
        XCTAssertEqual(audible(stage), [.secondary])
    }

    func testClosingDualWithSecondaryFocusReturnsSoundToPrimary() {
        let (stage, _, _) = make(muted: false)
        stage.setFocus(.secondary)
        stage.setDual(false)
        XCTAssertEqual(audible(stage), [.primary])
        XCTAssertFalse(stage.muted)
    }

    func testNonFocusLaneIsMutedWhenNewLaneJoinsWhileUnmuted() {
        let (stage, _, _) = make(muted: false)
        stage.setDual(false)
        stage.setDual(true)
        XCTAssertEqual(audible(stage), [.primary], "新开的副路不出声")
    }
}
