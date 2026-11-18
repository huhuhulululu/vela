import XCTest
@testable import VelaPhone

/// 双窗画质（裁决 3，§4.4，S12；ios-triple 修订 v2 H2）：两路平时都用最高档；`.serious` 降一档、`.critical` 降两档；
/// 回到 `.fair` 及以下满 60 秒才回升。热状态用 `noteThermal` 注入，时间也由测试给，不等真实的 60 秒。
@MainActor
final class DualQualityTests: XCTestCase {
    private var suites: [String] = []
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

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

    /// iPhone 竖屏双窗里 16:9 画面约 393 x 221 点，三倍屏。
    private func makeStage() -> StageModel {
        let name = "vela-phone-quality-\(UUID().uuidString)"
        suites.append(name)
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!))
        stage.updateFeed(intent: .reload) {
            $0.replace(with: CatalogPage(rooms: ["alice", "bob", "carol"].map { Room.direct(username: $0)! }, total: 3, nextOffset: 3))
        }
        stage.screenScale = 3
        stage.setDual(true)
        for lane in stage.lanes { lane.displaySize = CGSize(width: 393, height: 221) }
        return stage
    }

    func testBothLanesUseTopTierWhenCool() {
        let stage = makeStage()
        XCTAssertEqual(stage.playbackCap(on: stage.primary), .infinity)
        XCTAssertEqual(stage.playbackCap(on: stage.secondary!), .infinity)
        stage.noteThermal(.fair, now: t0)
        XCTAssertEqual(stage.playbackCap(on: stage.primary), .infinity, "fair 不降档")
        XCTAssertNil(stage.thermalRecoveryTask, "没降档不排回升检查")
    }

    /// ios-triple 修订 v2 H2：两路时尺寸上限不限，serious 从最高档降一档，与画面高度无关。
    func testSeriousStepsBothLanesDownOneTierFromTop() {
        let stage = makeStage()
        stage.noteThermal(.serious, now: t0)
        XCTAssertEqual(stage.playbackCap(on: stage.primary), 3_096_000, "5.128M 降一档")
        XCTAssertEqual(stage.playbackCap(on: stage.secondary!), 3_096_000)
        stage.secondary!.displaySize = CGSize(width: 393, height: 400)
        XCTAssertEqual(stage.playbackCap(on: stage.secondary!), 3_096_000, "两路不按画面高度限档")
    }

    func testCriticalStepsDownTwoTiersEvenWithoutPicture() {
        let stage = makeStage()
        stage.secondary!.displaySize = .zero
        stage.noteThermal(.critical, now: t0)
        XCTAssertEqual(stage.playbackCap(on: stage.secondary!), 1_696_000)
    }

    func testRecoversOnlyAfterSixtySecondsOfFairOrBelow() {
        let stage = makeStage()
        stage.noteThermal(.serious, now: t0)
        stage.noteThermal(.fair, now: t0.addingTimeInterval(10))
        XCTAssertNotNil(stage.thermalRecoveryTask, "只排一次性的回升检查")
        stage.noteThermal(.fair, now: t0.addingTimeInterval(69))
        XCTAssertEqual(stage.playbackCap(on: stage.primary), 3_096_000, "回落后第 59 秒仍降档")
        stage.noteThermal(.nominal, now: t0.addingTimeInterval(70))
        XCTAssertEqual(stage.playbackCap(on: stage.primary), .infinity, "满 60 秒回升")
        XCTAssertEqual(stage.playbackCap(on: stage.secondary!), .infinity)
        XCTAssertNil(stage.thermalRecoveryTask)
    }

    func testReheatRestartsTheSixtySeconds() {
        let stage = makeStage()
        stage.noteThermal(.serious, now: t0)
        stage.noteThermal(.fair, now: t0.addingTimeInterval(10))
        stage.noteThermal(.serious, now: t0.addingTimeInterval(50))
        XCTAssertNil(stage.thermalRecoveryTask, "又发热了就取消回升检查")
        stage.noteThermal(.fair, now: t0.addingTimeInterval(60))
        stage.noteThermal(.fair, now: t0.addingTimeInterval(119))
        XCTAssertEqual(stage.playbackCap(on: stage.primary), 3_096_000)
        stage.noteThermal(.fair, now: t0.addingTimeInterval(120))
        XCTAssertEqual(stage.playbackCap(on: stage.primary), .infinity)
    }

    func testRetargetCapTellsLanesToChangeTierOnBothTransitions() {
        let stage = makeStage()
        XCTAssertNil(stage.retargetCap(for: stage.primary), "上限没变不换档")
        stage.noteThermal(.serious, now: t0)
        XCTAssertEqual(stage.retargetCap(for: stage.primary), 3_096_000)
        stage.primary.currentCap = 3_096_000
        XCTAssertNil(stage.retargetCap(for: stage.primary))
        stage.noteThermal(.nominal, now: t0.addingTimeInterval(1))
        stage.noteThermal(.nominal, now: t0.addingTimeInterval(62))
        XCTAssertEqual(stage.retargetCap(for: stage.primary), .infinity, "回升也要换回最高档")
    }

    func testStartPlaybackCarriesThermalCap() {
        let stage = makeStage()
        stage.noteThermal(.serious, now: t0)
        // 起播时取的上限就是这一路当下的热状态上限。
        XCTAssertEqual(stage.playbackCap(on: stage.primary), stage.laneCap(for: stage.primary))
        XCTAssertLessThan(stage.laneCap(for: stage.primary), .infinity)
    }

    func testSystemThermalStateMapping() {
        XCTAssertEqual(PhoneThermalState(.nominal), .nominal)
        XCTAssertEqual(PhoneThermalState(.fair), .fair)
        XCTAssertEqual(PhoneThermalState(.serious), .serious)
        XCTAssertEqual(PhoneThermalState(.critical), .critical)
    }

    func testRetierSkipsLanesWithoutPlaybackItem() {
        let stage = makeStage()
        stage.noteThermal(.serious, now: t0)
        XCTAssertNil(stage.primary.capTask, "没有播放项的路不换档，下次起播带新上限")
        XCTAssertNil(stage.secondary!.capTask)
    }
}
