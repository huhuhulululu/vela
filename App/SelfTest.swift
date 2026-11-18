#if DEBUG
import AppKit
import AVFoundation
import Foundation
import os

let selftestLoadingLines = SelfTestCounter()

/// Debug 自测：`--selftest unmute|lock-resume|boss`，针对主路（primary）。无声（音量恒 0）、不截屏，只渲染 App 自己的视图。
/// 结果写 os_log（com.vela.stage / selftest），末行 `selftest <场景> PASS|FAIL 原因`，随后自行退出。
enum SelfTest {
    static let requested: String? = {
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "--selftest"), i + 1 < args.count else { return nil }
        return args[i + 1]
    }()

    private static let log = Logger(subsystem: "com.vela.stage", category: "selftest")
    private static let frameDir = "/tmp/vela-st2-frames"
    private static var started = false

    @MainActor
    static func startIfRequested(_ model: StageModel) {
        guard !started, let name = requested else { return }
        started = true
        Task { @MainActor in await run(name, model) }
    }

    // MARK: 公共

    private static func say(_ text: String) {
        log.notice("\(text, privacy: .public)")
    }

    @MainActor
    private static func finish(_ name: String, _ failures: [String]) async -> Never {
        say("selftest \(name) " + (failures.isEmpty ? "PASS" : "FAIL " + failures.joined(separator: "；")))
        try? await Task.sleep(nanoseconds: 1_500_000_000)
        exit(0)
    }

    @MainActor
    private static func sleep(_ seconds: Double) async {
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    @MainActor
    private static func waitPlaying(_ model: StageModel, timeout: Double) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if model.player.playingUsername != nil { return true }
            await sleep(0.2)
        }
        return false
    }

    /// 只渲染 App 自己的主窗口内容（不是屏幕截图）。返回 (纯黑像素比例, 全透明像素比例)。
    @MainActor
    private static func renderFrame(_ tag: String) -> (black: Double, clear: Double)? {
        guard let window = NSApp.windows.first(where: { $0.contentView != nil && $0.frame.width > 300 }),
              let view = window.contentView,
              let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds),
              let data = rep.bitmapData else { return nil }
        view.cacheDisplay(in: view.bounds, to: rep)
        try? FileManager.default.createDirectory(atPath: frameDir, withIntermediateDirectories: true)
        if let png = rep.representation(using: .png, properties: [:]) {
            try? png.write(to: URL(fileURLWithPath: "\(frameDir)/\(requested ?? "x")-\(tag).png"))
        }
        let spp = rep.samplesPerPixel
        guard spp >= 3 else { return nil }
        var total = 0, black = 0, clear = 0
        for y in stride(from: 0, to: rep.pixelsHigh, by: 2) {
            let row = data + y * rep.bytesPerRow
            for x in stride(from: 0, to: rep.pixelsWide, by: 2) {
                let px = row + x * (rep.bitsPerPixel / 8)
                total += 1
                if spp == 4, px[3] == 0 { clear += 1 }
                if px[0] <= 6, px[1] <= 6, px[2] <= 6 { black += 1 }
            }
        }
        guard total > 0 else { return nil }
        return (Double(black) / Double(total), Double(clear) / Double(total))
    }

    private static func pct(_ v: Double) -> String { String(format: "%.1f%%", v * 100) }

    // MARK: 入口

    @MainActor
    private static func run(_ name: String, _ model: StageModel) async {
        say("selftest \(name) start volume=\(model.player.player.volume) isMuted=\(model.player.player.isMuted)")
        guard ["unmute", "lock-resume", "boss"].contains(name) else {
            await finish(name, ["未知场景"])
        }
        guard await waitPlaying(model, timeout: 90) else {
            await finish(name, ["90 秒内未起播"])
        }
        await sleep(10)
        guard model.player.playingUsername != nil else {
            await finish(name, ["起播后 10 秒内停播"])
        }
        say("selftest \(name) stable")
        switch name {
        case "unmute": await runUnmute(model)
        case "lock-resume":
            await runInactivity(name, model, begin: { model.platform.selftestSet(inactive: true) },
                                end: { model.platform.selftestSet(inactive: false) })
        default:
            await runInactivity(name, model, begin: { model.toggleBoss() }, end: { model.toggleBoss() })
        }
    }

    // MARK: unmute

    @MainActor
    private static func runUnmute(_ model: StageModel) async {
        let name = "unmute"
        let player = model.player
        func snap(_ tag: String) -> SelfTestAudioSnapshot {
            let s = player.selftestAudioSnapshot()
            say("selftest unmute \(tag) isMuted=\(s.isMuted) volume=\(s.volume) wantsMuted=\(s.wantsMuted) "
                + "groupOptions=\(s.groupOptions.map(String.init) ?? "nil") audibleSelected=\(s.audibleSelected.map(String.init) ?? "nil") "
                + "tracks=\(s.tracks) enabled=\(s.enabled) detached=\(s.detached) "
                + "relayAudioFeed=\(s.relayAudioFeed) relayAudioSuppressed=\(s.relayAudioSuppressed) "
                + "audioFetches=\(PlaylistRelay.shared.selftestAudioFetches) liveSeeks=\(selftestLiveSeeks.value)")
            return s
        }
        var failures: [String] = []
        _ = snap("before")
        player.player.volume = 0
        guard player.player.volume == 0 else { await finish(name, ["无法把 AVPlayer.volume 设为 0，中止"]) }
        say("selftest unmute volume-guard volume=\(player.player.volume) (==0 证明已静默)")
        let fetches0 = PlaylistRelay.shared.selftestAudioFetches
        let seeks0 = selftestLiveSeeks.value
        model.toggleMute()  // 与用户点静音按钮同一函数
        guard player.player.volume == 0 else {
            player.player.volume = 0
            model.toggleMute()
            await finish(name, ["取消静音后 AVPlayer.volume != 0，已立即恢复静音"])
        }
        say("selftest unmute unmuted volume=\(player.player.volume) model.muted=\(model.muted)")
        await sleep(20)
        let s = snap("after-20s")
        let fetchDelta = PlaylistRelay.shared.selftestAudioFetches - fetches0
        let seekDelta = selftestLiveSeeks.value - seeks0
        say("selftest unmute delta audioFetches=\(fetchDelta) liveEdgeSeeks=\(seekDelta)")
        if model.muted || s.isMuted { failures.append("取消静音后 isMuted 仍为 true") }
        if s.volume != 0 { failures.append("音量不为 0") }
        if let selected = s.audibleSelected, !selected { failures.append("可听音轨未被选中") }
        if s.enabled == 0 { failures.append("可听音轨 enabled=0") }
        if s.relayAudioFeed {
            if fetchDelta == 0 { failures.append("音频清单未恢复刷新") }
            if s.relayAudioSuppressed { failures.append("音频清单仍处于抑制状态") }
        }
        if model.player.playingUsername == nil { failures.append("取消静音后停播") }
        model.toggleMute()
        await sleep(2)
        let r = snap("remuted")
        if !r.isMuted || !model.muted { failures.append("恢复静音失败") }
        await finish(name, failures)
    }

    // MARK: lock-resume / boss

    @MainActor
    private static func runInactivity(
        _ name: String, _ model: StageModel, begin: () -> Void, end: () -> Void
    ) async {
        var failures: [String] = []
        let player = model.player
        func frame(_ tag: String, _ since: Date) -> Double? {
            let r = renderFrame(tag)
            say("selftest \(name) frame \(tag) t=\(String(format: "%.1f", Date().timeIntervalSince(since)))s "
                + "black=\(r.map { pct($0.black) } ?? "n/a") clear=\(r.map { pct($0.clear) } ?? "n/a") "
                + "loadingLines=\(selftestLoadingLines.value) hasItem=\(player.hasItem) gateOpen=\(model.playbackGateOpen)")
            return r?.black
        }
        _ = frame("pre", Date())
        let t0 = Date()
        begin()
        say("selftest \(name) inactive-begin gateOpen=\(model.playbackGateOpen)")
        var postReleaseMaxLines = 0
        var postReleaseBlack: [Double] = []
        var nextFrame = 5.0
        while true {
            let t = Date().timeIntervalSince(t0)
            if t >= 40 { break }
            if t >= 31 { postReleaseMaxLines = max(postReleaseMaxLines, selftestLoadingLines.value) }
            if t >= nextFrame {
                nextFrame += 5
                if let b = frame("inactive-\(Int(t.rounded()))s", t0), t >= 34 { postReleaseBlack.append(b) }
            }
            await sleep(0.2)
        }
        let releasedHasItem = player.hasItem
        say("selftest \(name) inactive-end hasItem=\(releasedHasItem) postReleaseMaxLines=\(postReleaseMaxLines) "
            + "blackSamples=\(postReleaseBlack.map(pct).joined(separator: ","))")
        if releasedHasItem { failures.append("40 秒后播放项仍未释放（前提不成立）") }
        if postReleaseMaxLines > 0 { failures.append("释放后加载线仍在视图树中（\(postReleaseMaxLines)）") }
        if postReleaseBlack.isEmpty { failures.append("释放后未取得画面帧") }
        if let worst = postReleaseBlack.max(), worst >= 0.5 { failures.append("释放后画面纯黑比例 \(pct(worst)) >= 50%") }

        end()
        let tu = Date()
        say("selftest \(name) resume-begin gateOpen=\(model.playbackGateOpen)")
        var lineAt: Double?
        var playAt: Double?
        var framesDone = Set<Int>()
        while true {
            let t = Date().timeIntervalSince(tu)
            if t >= 12 { break }
            if lineAt == nil, selftestLoadingLines.value > 0 { lineAt = t }
            if playAt == nil, player.playingUsername != nil { playAt = t }
            for mark in [1, 3, 10] where t >= Double(mark) && !framesDone.contains(mark) {
                framesDone.insert(mark)
                _ = frame("resume-\(mark)s", tu)
            }
            await sleep(0.1)
        }
        say("selftest \(name) resume lineAt=\(lineAt.map { String(format: "%.1fs", $0) } ?? "never") "
            + "playingStartedAt=\(playAt.map { String(format: "%.1fs", $0) } ?? "never")")
        let early = (lineAt.map { $0 <= 3 } ?? false) || (playAt.map { $0 <= 3 } ?? false)
        if !early { failures.append("解锁 3 秒内加载线未出现、也未出画面") }
        if playAt == nil || playAt! > 10 { failures.append("解锁后 10 秒内未 playing-started") }
        await finish(name, failures)
    }
}
#endif
