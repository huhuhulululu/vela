import AVFoundation
import XCTest
@testable import VelaPhone

/// 音频控制器接到真的 `StageModel` 上：转换顺序、失败保持静音、打断与路由的处理（§3.3、修订 A2 的 7b）。
/// 会话用桩，不碰真实 `AVAudioSession`。
@MainActor
final class AudioFocusControllerTests: XCTestCase {
    private final class FakeDriver: AudioSessionDriving {
        struct Failure: Error {}
        var calls: [String] = []
        var failActive = false
        var failCategory = false

        func setCategory(_ category: AudioSessionCategory, mode: AudioSessionMode?, options: AudioSessionOptions) throws {
            calls.append("category \(category) mix=\(options.contains(.mixWithOthers))")
            if failCategory { throw Failure() }
        }

        func setActive(_ active: Bool, notifyOthers: Bool) throws {
            calls.append("active \(active) notify=\(notifyOthers)")
            if failActive { throw Failure() }
        }
    }

    private var suites: [String] = []

    override func tearDown() {
        for name in suites { UserDefaults().removePersistentDomain(forName: name) }
        suites = []
        super.tearDown()
    }

    private func make(pip: Bool = false) -> (StageModel, AudioFocusController, FakeDriver) {
        let name = "vela-phone-audio-\(UUID().uuidString)"
        suites.append(name)
        let stage = StageModel(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!))
        let driver = FakeDriver()
        let controller = AudioFocusController(stage: stage, driver: driver)
        controller.pictureInPictureActive = { pip }
        stage.audioFocus = controller
        return (stage, controller, driver)
    }

    private func isMuted(_ stage: StageModel) -> Bool { stage.primary.player.player.isMuted }

    func testUnmuteSetsCategoryThenActivatesThenUnmutes() {
        let (stage, _, driver) = make()
        XCTAssertTrue(stage.muted)
        stage.toggleMute()
        XCTAssertEqual(driver.calls, ["category playback mix=false", "active true notify=false"])
        XCTAssertFalse(stage.muted)
        XCTAssertFalse(isMuted(stage))
        XCTAssertFalse(stage.settings.muted, "出声成功才写进设置")
    }

    func testActivationFailureStaysMutedNotWrittenAndShowsNotice() {
        let (stage, _, driver) = make()
        driver.failActive = true
        stage.toggleMute()
        XCTAssertTrue(stage.muted)
        XCTAssertTrue(isMuted(stage))
        XCTAssertTrue(stage.settings.muted, "失败不改设置（初始就是静音）")
        XCTAssertEqual(stage.toast, "暂时无法出声")
    }

    func testCategoryFailureDoesNotActivate() {
        let (stage, _, driver) = make()
        driver.failCategory = true
        stage.toggleMute()
        XCTAssertEqual(driver.calls, ["category playback mix=false"])
        XCTAssertTrue(stage.muted)
        XCTAssertEqual(stage.toast, "暂时无法出声")
    }

    func testRemuteUsesMixCategoryAndDeactivatesNotifyingOthers() {
        let (stage, _, driver) = make()
        stage.toggleMute()
        driver.calls = []
        stage.toggleMute()
        XCTAssertEqual(driver.calls, ["category playback mix=true", "active false notify=true"])
        XCTAssertTrue(stage.muted)
        XCTAssertTrue(isMuted(stage))
        XCTAssertTrue(stage.settings.muted)
    }

    func testRemuteKeepsSessionWhilePictureInPictureIsPlaying() {
        let (stage, _, driver) = make(pip: true)
        stage.toggleMute()
        driver.calls = []
        stage.toggleMute()
        XCTAssertEqual(driver.calls, ["category playback mix=true"])
    }

    func testInterruptionBeganMutesRuntimeOnlyAndEndedDoesNotResume() {
        let (stage, controller, _) = make()
        stage.toggleMute()
        let began = AVAudioSession.InterruptionType.began.rawValue
        controller.handleInterruption(typeRaw: began, wasSuspended: false)
        XCTAssertTrue(stage.muted)
        XCTAssertTrue(isMuted(stage))
        XCTAssertFalse(stage.settings.muted, "打断只改运行时状态")
        controller.handleInterruption(typeRaw: AVAudioSession.InterruptionType.ended.rawValue, wasSuspended: false)
        XCTAssertTrue(stage.muted, "打断结束不自动出声")
    }

    func testSuspendedInterruptionBeganIsIgnored() {
        let (stage, controller, _) = make()
        stage.toggleMute()
        controller.handleInterruption(typeRaw: AVAudioSession.InterruptionType.began.rawValue, wasSuspended: true)
        XCTAssertFalse(stage.muted)
        XCTAssertFalse(isMuted(stage))
    }

    func testOldDeviceUnavailableMutesAndOtherReasonsDoNot() {
        let (stage, controller, driver) = make()
        stage.toggleMute()
        controller.handleRouteChange(reasonRaw: AVAudioSession.RouteChangeReason.newDeviceAvailable.rawValue)
        XCTAssertFalse(stage.muted)
        driver.calls = []
        controller.handleRouteChange(reasonRaw: AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue)
        XCTAssertTrue(stage.muted)
        XCTAssertEqual(driver.calls.last, "active false notify=true")
    }

    func testOldDeviceUnavailableKeepsSessionDuringPictureInPicture() {
        let (stage, controller, driver) = make(pip: true)
        stage.toggleMute()
        driver.calls = []
        controller.handleRouteChange(reasonRaw: AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue)
        XCTAssertTrue(stage.muted)
        XCTAssertFalse(driver.calls.contains { $0.hasPrefix("active") })
    }

    func testBackgroundDeactivationGoesThroughController() {
        let (stage, _, driver) = make()
        stage.toggleMute()
        driver.calls = []
        stage.platform.scenePhaseChanged(.inactive, stage: stage)
        stage.platform.scenePhaseChanged(.background, stage: stage)
        XCTAssertTrue(driver.calls.contains("active false notify=true"))
    }

    func testSliderToZeroMutesAndRaisingUnmutesThroughController() {
        let (stage, _, driver) = make()
        stage.toggleMute()
        driver.calls = []
        stage.setVolume(0)
        XCTAssertTrue(stage.muted)
        XCTAssertEqual(driver.calls.last, "active false notify=true")
        driver.calls = []
        stage.setVolume(0.5)
        XCTAssertFalse(stage.muted)
        XCTAssertEqual(driver.calls, ["category playback mix=false", "active true notify=false"])
        driver.calls = []
        stage.setVolume(0.6)
        XCTAssertTrue(driver.calls.isEmpty, "已出声时拖音量条不动会话")
    }
}
