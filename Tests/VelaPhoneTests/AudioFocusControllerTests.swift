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
        var onCategory: () -> Void = {}

        func setCategory(_ category: AudioSessionCategory, mode: AudioSessionMode?, options: AudioSessionOptions) throws {
            onCategory()
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

    // MARK: 首次起播前的初始转换

    func testPrepareSessionRunsMutedConfigurationOnce() {
        let (stage, controller, driver) = make()
        XCTAssertTrue(stage.muted)
        stage.prepareAudioSession()
        XCTAssertEqual(driver.calls, ["category playback mix=true", "active true notify=false"])
        XCTAssertTrue(controller.sessionPrepared)
        stage.prepareAudioSession()
        XCTAssertEqual(driver.calls.count, 2, "只在首次起播前做一次")
        XCTAssertTrue(stage.muted)
        XCTAssertTrue(isMuted(stage))
    }

    func testPrepareSessionAudibleUsesUnmixedPlayback() {
        let (stage, _, driver) = make()
        stage.muted = false
        stage.prepareAudioSession()
        XCTAssertEqual(driver.calls, ["category playback mix=false", "active true notify=false"])
        XCTAssertFalse(stage.muted)
        XCTAssertFalse(isMuted(stage))
    }

    func testFirstStartPlaybackPreparesSessionBeforePlayerGetsItem() async throws {
        PlaylistRelay.shared.allowStart = true
        let listening = await PlaylistRelay.shared.waitUntilListening(timeout: 5)
        XCTAssertTrue(listening, "本地中继没能监听，测不了起播顺序")
        guard listening else { return }
        let (stage, _, driver) = make()
        var hadItemAtCategory: Bool?
        driver.onCategory = { hadItemAtCategory = stage.primary.player.hasItem }
        let room = try XCTUnwrap(Room.direct(username: "audio_prepare_probe"))
        let rendition = StreamRendition(video: try XCTUnwrap(URL(string: "http://127.0.0.1:9/probe.m3u8")))
        stage.startPlayback(
            room: room, target: .init(master: nil, playable: rendition), cap: .infinity, isRetry: false, lane: stage.primary
        )
        defer { stage.primary.player.stop() }
        XCTAssertEqual(hadItemAtCategory, false, "设会话类别时播放器还没有播放项：初始转换在起播之前")
        XCTAssertEqual(Array(driver.calls.prefix(2)), ["category playback mix=true", "active true notify=false"])
        XCTAssertTrue(stage.primary.player.hasItem, "随后确实起播了（播放器拿到了播放项）")
    }
    // MARK: 审计修复：后台不改静音意图

    /// 出声进后台再回前台：运行时与设置一致（仍是出声），回前台起播前按出声重新准备会话。
    func testAudibleBackgroundThenForegroundKeepsIntentAndPreparesAudibleSession() {
        let (stage, controller, driver) = make()
        stage.toggleMute()
        stage.prepareAudioSession()
        XCTAssertFalse(stage.muted)
        XCTAssertFalse(stage.settings.muted)

        stage.platform.scenePhaseChanged(.inactive, stage: stage)
        stage.platform.scenePhaseChanged(.background, stage: stage)
        XCTAssertTrue(driver.calls.contains("active false notify=true"), "后台释放会话")
        XCTAssertFalse(stage.muted, "后台停用不把运行时改成静音")
        XCTAssertEqual(stage.muted, stage.settings.muted, "运行时与设置一致")
        XCTAssertFalse(controller.sessionPrepared, "会话已停用，下次起播要重新准备")

        stage.platform.scenePhaseChanged(.inactive, stage: stage)
        stage.platform.scenePhaseChanged(.active, stage: stage)
        driver.calls = []
        stage.prepareAudioSession()
        XCTAssertEqual(driver.calls, ["category playback mix=false", "active true notify=false"], "按出声重新准备")
        XCTAssertFalse(stage.muted)
        XCTAssertFalse(isMuted(stage))
        XCTAssertEqual(stage.muted, stage.settings.muted)
    }

    /// 冷启动仍按设置：设置里是静音就以静音准备。
    func testColdStartStillFollowsSettings() {
        let (stage, _, driver) = make()
        XCTAssertTrue(stage.settings.muted)
        stage.prepareAudioSession()
        XCTAssertEqual(driver.calls, ["category playback mix=true", "active true notify=false"])
        XCTAssertTrue(stage.muted)
    }
}
