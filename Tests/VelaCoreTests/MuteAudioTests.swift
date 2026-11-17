import XCTest
@testable import VelaCore

final class MuteAudioTests: XCTestCase {
    private let main = MuteAudioPolicy.AudibleOption(isDefault: false)
    private let preferred = MuteAudioPolicy.AudibleOption(isDefault: true)

    func testMutedWithEmptySelectionClearsOnlyTheCurrentOption() {
        let options = [main, preferred]
        XCTAssertEqual(
            MuteAudioPolicy.audibleChoice(muted: true, allowsEmptySelection: true, options: options, selectedIndex: 0),
            .clear
        )
        XCTAssertEqual(
            MuteAudioPolicy.audibleChoice(muted: true, allowsEmptySelection: true, options: options, selectedIndex: 1),
            .clear
        )
        // 已经没选中：别再 select 一次。
        XCTAssertEqual(
            MuteAudioPolicy.audibleChoice(muted: true, allowsEmptySelection: true, options: options, selectedIndex: nil),
            .leave
        )
    }

    func testDisallowingEmptySelectionNeverClears() {
        let options = [main, preferred]
        for selected in [nil, Optional(0), Optional(1)] {
            let choice = MuteAudioPolicy.audibleChoice(
                muted: true, allowsEmptySelection: false, options: options, selectedIndex: selected
            )
            if case .clear = choice { XCTFail("不允许空选择时不能 clear，selected=\(String(describing: selected))") }
        }
        // 静音但不能清空：没有选中项时补上默认项，取消静音不用重载播放器。
        XCTAssertEqual(
            MuteAudioPolicy.audibleChoice(muted: true, allowsEmptySelection: false, options: options, selectedIndex: nil),
            .pick(1)
        )
        XCTAssertEqual(
            MuteAudioPolicy.audibleChoice(muted: true, allowsEmptySelection: false, options: options, selectedIndex: 0),
            .leave
        )
    }

    func testUnmutePicksTheDefaultOptionAndLeavesAnExistingChoice() {
        let options = [main, preferred]
        XCTAssertEqual(
            MuteAudioPolicy.audibleChoice(muted: false, allowsEmptySelection: true, options: options, selectedIndex: nil),
            .pick(1),
            "取消静音选回默认音轨，而不是列表里的第一项"
        )
        XCTAssertEqual(
            MuteAudioPolicy.audibleChoice(muted: false, allowsEmptySelection: false, options: [main], selectedIndex: nil),
            .pick(0),
            "没有默认项时选第一个"
        )
        // 已经在播这条音轨：不要重复 select。
        XCTAssertEqual(
            MuteAudioPolicy.audibleChoice(muted: false, allowsEmptySelection: true, options: options, selectedIndex: 0),
            .leave
        )
    }

    func testNoAudibleOptionsLeavesSelectionAlone() {
        XCTAssertEqual(
            MuteAudioPolicy.audibleChoice(muted: true, allowsEmptySelection: true, options: [], selectedIndex: nil),
            .leave
        )
        XCTAssertEqual(
            MuteAudioPolicy.audibleChoice(muted: false, allowsEmptySelection: false, options: [], selectedIndex: 0),
            .leave
        )
    }

    func testAudioFetchSuppression() {
        XCTAssertTrue(MuteAudioPolicy.shouldSuppressAudioFetch(muted: true, allowsEmptySelection: nil))
        XCTAssertTrue(MuteAudioPolicy.shouldSuppressAudioFetch(muted: true, allowsEmptySelection: true))
        XCTAssertFalse(MuteAudioPolicy.shouldSuppressAudioFetch(muted: true, allowsEmptySelection: false))
        XCTAssertFalse(MuteAudioPolicy.shouldSuppressAudioFetch(muted: false, allowsEmptySelection: nil))
        XCTAssertFalse(MuteAudioPolicy.shouldSuppressAudioFetch(muted: false, allowsEmptySelection: true))
    }

    func testRelayRefreshesAudioOnlyWhenPlaybackWantsIt() {
        XCTAssertFalse(MuteAudioPolicy.shouldRefreshAudioPlaylist(
            hasSeparateAudio: true, relayRefreshing: true, suppressAudioFetch: true
        ))
        XCTAssertTrue(MuteAudioPolicy.shouldRefreshAudioPlaylist(
            hasSeparateAudio: true, relayRefreshing: true, suppressAudioFetch: false
        ))
        XCTAssertFalse(MuteAudioPolicy.shouldRefreshAudioPlaylist(
            hasSeparateAudio: false, relayRefreshing: true, suppressAudioFetch: false
        ))
        XCTAssertFalse(MuteAudioPolicy.shouldRefreshAudioPlaylist(
            hasSeparateAudio: true, relayRefreshing: false, suppressAudioFetch: false
        ))
    }

    func testSeekLiveEdgeOnlyWhenReattachingWhilePlaying() {
        XCTAssertTrue(MuteAudioPolicy.shouldSeekLiveEdge(audioWasDetached: true, playing: true))
        XCTAssertFalse(MuteAudioPolicy.shouldSeekLiveEdge(audioWasDetached: true, playing: false))
        XCTAssertFalse(MuteAudioPolicy.shouldSeekLiveEdge(audioWasDetached: false, playing: true))
    }

    func testHeldMuteStillCountsAsAudibleForTheStartupLog() {
        let held = MuteAudioPolicy.AudibleGroupState(
            loaded: true, optionCount: 1, selectedIndex: nil, allowsEmptySelection: true
        )
        XCTAssertEqual(
            MuteAudioPolicy.audioReport(muted: true, isRecheck: false, enabledTrackCount: 0, group: held),
            .ok(enabled: 1, held: true)
        )
        XCTAssertEqual(
            MuteAudioPolicy.audioReport(muted: true, isRecheck: true, enabledTrackCount: 0, group: held),
            .ok(enabled: 1, held: true)
        )
        // 轨确实开着：按真实启用数记，不标 held。
        XCTAssertEqual(
            MuteAudioPolicy.audioReport(muted: true, isRecheck: false, enabledTrackCount: 2, group: held),
            .ok(enabled: 2, held: false)
        )
    }

    func testStillSelectedOrUnknownGroupDoesNotPretendAudioIsHeld() {
        let stillSelected = MuteAudioPolicy.AudibleGroupState(
            loaded: true, optionCount: 1, selectedIndex: 0, allowsEmptySelection: true
        )
        XCTAssertEqual(
            MuteAudioPolicy.audioReport(muted: true, isRecheck: false, enabledTrackCount: 0, group: stillSelected),
            .recheck
        )
        let cannotClear = MuteAudioPolicy.AudibleGroupState(
            loaded: true, optionCount: 1, selectedIndex: nil, allowsEmptySelection: false
        )
        XCTAssertEqual(
            MuteAudioPolicy.audioReport(muted: true, isRecheck: false, enabledTrackCount: 0, group: cannotClear),
            .recheck
        )
        XCTAssertEqual(
            MuteAudioPolicy.audioReport(muted: true, isRecheck: false, enabledTrackCount: 0, group: .unknown),
            .recheck
        )
        XCTAssertEqual(
            MuteAudioPolicy.audioReport(muted: true, isRecheck: true, enabledTrackCount: 0, group: .unknown),
            .missing
        )
        // 未静音却没有启用轨：照旧复查，不把「有可选项」当成已经出声。
        let held = MuteAudioPolicy.AudibleGroupState(
            loaded: true, optionCount: 1, selectedIndex: nil, allowsEmptySelection: true
        )
        XCTAssertEqual(
            MuteAudioPolicy.audioReport(muted: false, isRecheck: false, enabledTrackCount: 0, group: held),
            .recheck
        )
        XCTAssertEqual(
            MuteAudioPolicy.audioReport(muted: false, isRecheck: true, enabledTrackCount: 0, group: held),
            .missing
        )
    }
}
