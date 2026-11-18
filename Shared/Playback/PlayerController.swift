import AVFoundation
import Combine
import Foundation
import os

/// 一次播放失败的结构化信息：交给失败分类钩子与界面，不含地址。
struct PlaybackFailureInfo {
    /// `item-failed`、`start-timeout`、`stuck-giveup`。
    var stage: String
    var username: String
    /// 错误本身与各层 underlying error 的 (domain, code)。
    var chain: [(domain: String, code: Int)]
    /// 归类后的中文文案（与 `lastError` 一致）。
    var message: String
}

@MainActor
final class PlayerController: ObservableObject {
    let player = AVPlayer()
    /// 界面用：该房间的画面已出现过（换档时保留，新项出首帧前继续显示上一帧）。
    @Published private(set) var readyUsername: String?
    /// 此刻播放器真的在播（不含缓冲、暂停）的房间；停留计时用它。
    @Published private(set) var playingUsername: String?
    @Published private(set) var failed = false {
        // 终态失败（含失败界面）立即停掉中继后台刷新；重试 / 切房经 start() 重新开启。
        didSet { if failed { syncRelayRefreshing() } }
    }
    @Published private(set) var lastError: String?

    /// 播放项失败的一次自动重试、长时间暂停后的恢复都要换新地址：交给外面重新解析当前房间。
    /// 参数：房间名、是否属于失败重试（重试只允许一次）。
    var refreshRequested: ((String, Bool) -> Void)?
    /// 开播后复查仍没有可听音轨：参数是房间名，外面据此提示「这路没有声音」。
    var noAudioDetected: ((String) -> Void)?
    /// 中继收到上游 403（子列表地址闲置过期）时，请外面重新解析这个房间并选好档，返回新的子列表地址；
    /// 中继原地换上，播放无感。参数：房间名、起播时的画质上限。拿不到返回 nil。
    var renewRendition: (@MainActor @Sendable (String, Double) async -> StreamRendition?)?
    /// 播放项失败、进入 `itemFailed` 之前先问一次怎么归类（`PlaybackFailureClassifier`）。
    /// 没设或单窗时恒为 `.roomRetry`，与原来的重试与失败流程完全一致。
    var failureFilter: ((PlaybackFailureInfo) -> PlaybackFailureClassifier.Outcome)?
    /// 判为 `.resourceDegrade` 时调用：不消耗重试预算、不进入失败态，由外面释放这一路。
    var resourceDegraded: ((PlaybackFailureInfo) -> Void)?
    /// 日志与 signpost 里的路标识（`first-frame lane=<id>`）。
    var laneLabel = "primary"
    /// 最近一次失败的结构化信息；新起播与停止时清空。
    private(set) var lastFailure: PlaybackFailureInfo?

    private var statusObservation: NSKeyValueObservation?
    private var timeObservation: NSKeyValueObservation?
    private var generation = 0
    private var watchTask: Task<Void, Never>?
    private var startupTask: Task<Void, Never>?
    /// 已开播后的卡住看门狗：开播过的项持续等待（未播放）超时即恢复一次。
    private var stuckTask: Task<Void, Never>?
    /// 卡住看门狗开始计时时，已加载区间的末端（秒）；到点时与现在比较，判断缓冲是否仍在增长。
    private var stuckBaseEnd: TimeInterval?
    /// 开播后的音轨检测（含复查）；换项、切房、停止时取消。
    private var audioTask: Task<Void, Never>?
    /// 把静音落实到可听组的选择上；换项、切房、停止、再次改静音时取消。
    private var audibleTask: Task<Void, Never>?
    /// 界面要的静音。默认开，和 `AudioPolicy.initialMuted` 一致；`setVolume` 会改。
    private var wantsMuted = true
    /// 读到可听组之后才知道。nil 表示还不知道：静音时先按「可以不拉音频」处理。
    private var audioAllowsEmpty: Bool?
    /// 当前项的可听选项被我们拿掉了。取消静音要选回去。新起播清掉，避免对新项 seek。
    private var audioDetached = false
    /// 最近一次可听组快照。音轨日志用它区分「静音故意不选」和「真的没有音轨」。
    private var audibleGroup = MuteAudioPolicy.AudibleGroupState.unknown
    /// 当前播放项的可听组。留着是为了取消静音时同步选回去，不必再等一次加载。换项清空。
    private var cachedAudibleGroup: AVMediaSelectionGroup?
    private var pausedAt: Date?
    /// 停播且窗口不可见达到阈值后释放播放项；续播、重新起播、停止、窗口重新可见时取消。
    private var releaseTask: Task<Void, Never>?
    /// 窗口连续不可见的起点；可见时为 nil。
    private var hiddenSince: Date?
    private var lastPlay: (rendition: StreamRendition, username: String, peak: Double)?
    /// 本播放器在中继里登记的源 id。中继可同时有多个源（双窗），这里只动自己的；换项先作废旧源再登记新源。
    private var relaySource: String?
    private var retried = false
    /// 当前播放项是否真正开播过（与 readyUsername 无关：换档保留上一帧但新项仍要证明自己能播）。
    private var itemPlayed = false
    /// 应当播放（StageModel 门控的结果）。所有起播都经过它，重建播放项时「应暂停」的意图不丢。
    private var allowed = true
    /// 恢复时已请求外面重新解析地址：新项到来前不要接着播旧项的过期画面。
    private var refresh = RefreshTracker()
    private static let log = Logger(subsystem: "com.vela.stage", category: "playback")
    private static let signposter = OSSignposter(subsystem: "com.vela.stage", category: "playback")
    /// 首帧计时（Release 也保留）：从登记播放项起，到开播后第一次拿到有效画面尺寸为止。
    private var playStarted = Date()
    private var firstFrameLogged = true
    private var firstFrameInterval: OSSignpostIntervalState?
    private var presentationObservation: NSKeyValueObservation?
    #if DEBUG
    private var tickToken: Any?
    #endif

    var hasItem: Bool { player.currentItem != nil }

    init() {
        PlaylistRelay.shared.start()
        player.automaticallyWaitsToMinimizeStalling = true
        player.preventsDisplaySleepDuringVideoPlayback = false
        player.actionAtItemEnd = .none
    }

    /// `allowed`：此刻的播放门控结果，新播放项据此决定立刻播还是只备好等恢复。
    /// `isRetry`：由失败重试带来的重新起播，不重置「已重试」标记。
    func play(rendition: StreamRendition, username: String, peakBitrate: Double, allowed: Bool, isRetry: Bool = false) {
        self.allowed = allowed
        // 重试预算按房间算：换了房间（或不是重试起播）就重置，不继承上一个房间的 true。
        if !isRetry || lastPlay?.username != username { retried = false }
        start(rendition: rendition, username: username, peakBitrate: peakBitrate)
    }

    /// 同一房间换画质档：重建播放项并回到直播点，沿用已显示的画面状态（不清 readyUsername，
    /// 新项出首帧前保留上一帧，界面不闪回加载态）。旧项随 replaceCurrentItem 释放，
    /// 同一时刻只有一路下载。房间不是当前就绪的那一路时不动，返回 false。
    @discardableResult
    func retier(rendition: StreamRendition, username: String, peakBitrate: Double) -> Bool {
        guard hasItem, readyUsername == username, lastPlay?.username == username else { return false }
        Self.log.info("retier peak=\(peakBitrate.isFinite ? Int(peakBitrate) : -1)")
        start(rendition: rendition, username: username, peakBitrate: peakBitrate, keepReady: true)
        return true
    }

    private func start(rendition: StreamRendition, username: String, peakBitrate: Double, keepReady: Bool = false) {
        releaseTask?.cancel()
        releaseTask = nil
        lastPlay = (rendition, username, peakBitrate)
        generation += 1
        let token = generation
        failed = false
        lastError = nil
        lastFailure = nil
        if !keepReady { readyUsername = nil }
        playingUsername = nil
        itemPlayed = false
        let renewer = renewRendition
        let renew: @Sendable () async -> StreamRendition? = { [weak self] in
            guard let fresh = await renewer?(username, peakBitrate) else { return nil }
            await self?.adoptRenewed(fresh, username: username, token: token)
            return fresh
        }
        // 先作废自己的旧源，再登记新源（别的播放器登记的源不动）。
        if let relaySource { PlaylistRelay.shared.deactivate(source: relaySource) }
        relaySource = nil
        // 新项先按「还不知道能不能不选音轨」登记静音标志；读到可听组后由 syncAudioSuppression 更新。
        let suppressed = MuteAudioPolicy.shouldSuppressAudioFetch(muted: wantsMuted, allowsEmptySelection: nil)
        // 中继没就绪时不把低延迟主列表直接交给 AVPlayer（会卡住甚至不起播）：停下并报错，界面给出重试。
        guard let source = PlaylistRelay.shared.localURL(for: rendition, maxBandwidth: peakBitrate, audioSuppressed: suppressed, renew: renew) else {
            stop()
            // 中继被挂起（iOS 后台）不是故障：静默停下，回前台由生命周期重新起播。macOS 恒允许，不会走到这里。
            guard PlaylistRelay.shared.allowStart else { return }
            Self.log.error("relay unavailable")
            lastError = "本地中继没能启动，暂时无法播放"
            failed = true
            return
        }
        relaySource = source.id
        let item = makeItem(url: source.url)
        cancelStuckWatchdog()
        audioTask?.cancel()
        audioTask = nil
        audibleTask?.cancel()
        audibleTask = nil
        // 新项重新判断能不能拿掉音轨。先按静音与否停掉音频清单，再开刷新循环，避免静音起播先拉一圈 audio.m3u8。
        audioDetached = false
        audioAllowsEmpty = nil
        audibleGroup = .unknown
        cachedAudibleGroup = nil
        syncAudioSuppression()
        syncRelayRefreshing()
        beginFirstFrame()
        pausedAt = nil
        refresh.clear()
        observe(item, username: username, token: token)
        watch(item)
        player.replaceCurrentItem(with: item)
        if allowed {
            player.play()
            armStartupWatchdog()
        } else {
            // 不该播（暂停、老板键隐藏、休眠、窗口不可见）：只备好播放项，恢复时再起。
            pausedAt = Date()
        }
        scheduleAudibleSelection()
    }

    func setVolume(_ volume: Double, muted: Bool) {
        let muteChanged = wantsMuted != muted
        wantsMuted = muted
        #if DEBUG
        // 自测期间音量恒为 0：取消静音也不会出声。
        player.volume = Self.selftestActive ? 0 : Float(min(max(volume, 0), 1))
        #else
        player.volume = Float(min(max(volume, 0), 1))
        #endif
        player.isMuted = muted
        syncAudioSuppression()
        guard muteChanged else { return }
        if let item = player.currentItem, let group = cachedAudibleGroup {
            applyAudible(group, on: item)
            return
        }
        scheduleAudibleSelection()
    }

    private func syncAudioSuppression() {
        guard let relaySource else { return }
        let suppressed = MuteAudioPolicy.shouldSuppressAudioFetch(muted: wantsMuted, allowsEmptySelection: audioAllowsEmpty)
        PlaylistRelay.shared.setAudioSuppressed(suppressed, source: relaySource)
    }

    /// 静音起播时先关掉自动选轨，等可听组读到再决定：允许空选择就保持不选，不允许就选回去并重新打开自动选择。
    /// 主列表里的音轨一直在，不靠重载播放项恢复声音。
    private func scheduleAudibleSelection() {
        audibleTask?.cancel()
        let token = generation
        guard let item = player.currentItem else { return }
        audibleTask = Task { @MainActor [weak self] in
            guard let self else { return }
            let group: AVMediaSelectionGroup?
            do {
                group = try await item.asset.loadMediaSelectionGroup(for: AVMediaCharacteristic.audible)
            } catch {
                guard !Task.isCancelled, token == self.generation, self.player.currentItem === item else { return }
                self.fallbackToAutomaticAudio(on: item, groupLoaded: false)
                return
            }
            guard !Task.isCancelled, token == self.generation, self.player.currentItem === item else { return }
            guard let group else {
                self.fallbackToAutomaticAudio(on: item, groupLoaded: true)
                return
            }
            self.applyAudible(group, on: item)
        }
    }

    /// 没有可听组，或组没读出来：回到系统自动选择，静音只靠 `isMuted`。独立音轨仍留在主列表里。
    private func fallbackToAutomaticAudio(on item: AVPlayerItem, groupLoaded: Bool) {
        player.appliesMediaSelectionCriteriaAutomatically = true
        audioAllowsEmpty = false
        audioDetached = false
        if groupLoaded {
            audibleGroup = MuteAudioPolicy.AudibleGroupState(
                loaded: true, optionCount: 0, selectedIndex: nil, allowsEmptySelection: false
            )
        }
        cachedAudibleGroup = nil
        syncAudioSuppression()
        Self.log.info("audio selection automatic")
    }

    private func applyAudible(_ group: AVMediaSelectionGroup, on item: AVPlayerItem) {
        cachedAudibleGroup = group
        let options = group.options
        let selected = item.currentMediaSelection.selectedMediaOption(in: group)
        let selectedIndex: Int?
        if let selected {
            selectedIndex = options.firstIndex { $0 === selected }
        } else {
            selectedIndex = nil
        }
        let choice = MuteAudioPolicy.audibleChoice(
            muted: wantsMuted,
            allowsEmptySelection: group.allowsEmptySelection,
            options: options.map { option in
                guard let defaultOption = group.defaultOption else {
                    return MuteAudioPolicy.AudibleOption(isDefault: false)
                }
                return MuteAudioPolicy.AudibleOption(isDefault: option === defaultOption)
            },
            selectedIndex: selectedIndex
        )
        var resulting = selectedIndex
        var justAttached = false
        switch choice {
        case .leave:
            break
        case .clear:
            guard group.allowsEmptySelection else { break }
            item.select(nil, in: group)
            resulting = nil
            Self.log.info("audio selection clear")
        case .pick(let index):
            guard options.indices.contains(index) else { break }
            item.select(options[index], in: group)
            resulting = index
            justAttached = true
            Self.log.info("audio selection pick")
        }
        if !group.allowsEmptySelection {
            player.appliesMediaSelectionCriteriaAutomatically = true
        }
        let wasDetached = audioDetached
        if wantsMuted, group.allowsEmptySelection, resulting == nil, !options.isEmpty {
            audioDetached = true
        } else {
            audioDetached = false
        }
        audioAllowsEmpty = group.allowsEmptySelection
        audibleGroup = MuteAudioPolicy.AudibleGroupState(
            loaded: true,
            optionCount: options.count,
            selectedIndex: resulting,
            allowsEmptySelection: group.allowsEmptySelection
        )
        syncAudioSuppression()
        if !wantsMuted, MuteAudioPolicy.shouldSeekLiveEdge(
            audioWasDetached: wasDetached && justAttached,
            playing: allowed && player.timeControlStatus == .playing
        ) {
            #if DEBUG
            selftestLiveSeeks.add(1)
            #endif
            player.seek(to: CMTime.positiveInfinity)
        }
    }

    func setDisplaySleepPrevented(_ prevented: Bool) {
        player.preventsDisplaySleepDuringVideoPlayback = prevented
    }

    /// StageModel 门控的唯一入口：该播就恢复，不该播就暂停。
    func setAllowed(_ value: Bool) {
        allowed = value
        if value { resumeIfNeeded() } else { pauseDecoding() }
    }

    /// 窗口是否对用户不可见（StageModel 每次重算闸门时先于 `setAllowed` 调用）。
    /// 可见：取消释放计时；变为不可见：记起点，已经停着就从此刻起算 30 秒。
    func setHidden(_ hidden: Bool) {
        if hidden {
            guard hiddenSince == nil else { return }
            hiddenSince = Date()
            schedulePausedRelease()
        } else {
            hiddenSince = nil
            releaseTask?.cancel()
            releaseTask = nil
        }
    }

    private func syncRelayRefreshing() {
        guard let relaySource else { return }
        PlaylistRelay.shared.setRefreshing(PlaybackPolicy.relayShouldRefresh(allowed: allowed, failed: failed), source: relaySource)
    }

    private func pauseDecoding() {
        startupTask?.cancel()
        startupTask = nil
        cancelStuckWatchdog()
        if let relaySource { PlaylistRelay.shared.setRefreshing(false, source: relaySource) }
        guard hasItem else { return }
        if pausedAt == nil { pausedAt = Date() }
        player.pause()
        schedulePausedRelease()
    }

    /// 停播且窗口不可见都满阈值才拆播放项（窗口可见的手动暂停不拆，保留最后一帧）。
    /// 未到点就接着等，提前醒来则按剩余时间再排一次。
    private func schedulePausedRelease() {
        releaseTask?.cancel()
        releaseTask = nil
        guard !allowed, hasItem, let since = pausedAt, let hidden = hiddenSince else { return }
        let now = Date()
        let pausedFor = now.timeIntervalSince(since)
        let hiddenFor = now.timeIntervalSince(hidden)
        if PlaybackPolicy.shouldReleasePausedItem(pausedFor: pausedFor, hiddenFor: hiddenFor) {
            releasePausedSource(pausedFor: pausedFor)
            return
        }
        let delay = PlaybackPolicy.releasePausedItemAfter - min(pausedFor, hiddenFor)
        guard let nanos = Self.sleepNanos(delay) else { return }
        releaseTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: nanos)
            guard let self, !Task.isCancelled else { return }
            self.schedulePausedRelease()
        }
    }

    /// `delay` 不是有限正数，或换算后不足 1 纳秒，返回 nil。
    private static func sleepNanos(_ delay: TimeInterval) -> UInt64? {
        guard delay.isFinite, delay > 0 else { return nil }
        let nanos = delay * 1_000_000_000
        guard nanos >= 1, nanos <= Double(UInt64.max) else { return nil }
        return UInt64(nanos)
    }

    /// 放掉播放项和中继里该源的缓冲。`pausedAt` 与 `lastPlay` 留着，恢复时重新解析，不再读旧项。
    /// 同时清掉 `readyUsername`：画面已释放，画布回到垫底缩略图，而不是留一个空的黑色播放层。
    /// 手动暂停时界面只显示垫底与暂停图标（不出加载线）；自动恢复时 `reresolve` 照常走加载线并起播。
    private func releasePausedSource(pausedFor: TimeInterval) {
        guard hasItem, !allowed else { return }
        Self.log.info("paused-release after \(pausedFor, format: .fixed(precision: 0))s")
        generation += 1
        statusObservation = nil
        timeObservation = nil
        watchTask?.cancel()
        watchTask = nil
        startupTask?.cancel()
        startupTask = nil
        cancelStuckWatchdog()
        audioTask?.cancel()
        audioTask = nil
        audibleTask?.cancel()
        audibleTask = nil
        cachedAudibleGroup = nil
        endFirstFrame()
        #if DEBUG
        if let tickToken { player.removeTimeObserver(tickToken) }
        tickToken = nil
        #endif
        player.pause()
        player.replaceCurrentItem(with: nil)
        if let relaySource { PlaylistRelay.shared.deactivate(source: relaySource) }
        relaySource = nil
        itemPlayed = false
        if playingUsername != nil { playingUsername = nil }
        if readyUsername != nil { readyUsername = nil }
    }

    private func resumeIfNeeded() {
        releaseTask?.cancel()
        releaseTask = nil
        guard !refresh.pending else { return }
        // 播放项已按阈值放掉：旧项不存在，也不能再读它的属性。只能重新解析。
        guard hasItem else {
            guard let since = pausedAt else { return }
            pausedAt = nil
            reresolve(pausedFor: Date().timeIntervalSince(since))
            return
        }
        guard let since = pausedAt else {
            syncRelayRefreshing()
            if player.timeControlStatus != .playing { player.play() }
            if !itemPlayed, startupTask == nil { armStartupWatchdog() }
            return
        }
        pausedAt = nil
        let pausedFor = Date().timeIntervalSince(since)
        // 三段式：暂停不超过 `liveRejoinAfter` 直接续播（自动贴回直播偏移）；
        // 之后直播旧画面已丢，重建播放项回到直播点；超过 `reresolveAfter` 流地址可能过期，交给外面重新解析。
        switch PlaybackPolicy.resumeAction(pausedFor: pausedFor) {
        case .resume:
            syncRelayRefreshing()
            player.play()
            if !itemPlayed { armStartupWatchdog() }
        case .rejoin:
            Self.log.info("resume-live rebuild after \(pausedFor, format: .fixed(precision: 0))s")
            rebuildCurrent()
        case .reresolve:
            reresolve(pausedFor: pausedFor)
        }
    }

    /// 长时间暂停后的恢复：请外面重新解析当前房间。没有回调时用记下的地址重建，不读当前播放项。
    private func reresolve(pausedFor: TimeInterval) {
        Self.log.info("resume-live re-resolve after \(pausedFor, format: .fixed(precision: 0))s")
        if let last = lastPlay, let refreshRequested {
            startupTask?.cancel()
            startupTask = nil
            cancelStuckWatchdog()
            if let relaySource { PlaylistRelay.shared.setRefreshing(false, source: relaySource) }
            refresh.begin()
            refreshRequested(last.username, false)
        } else {
            rebuildCurrent()
        }
    }

    /// 原地续期成功：后续 rejoin / 重建要用新地址，别把已过期的起播地址重新注册。播放项已换走则忽略。
    private func adoptRenewed(_ fresh: StreamRendition, username: String, token: Int) {
        guard generation == token, let last = lastPlay, last.username == username else { return }
        lastPlay = (fresh, username, last.peak)
    }

    private func rebuildCurrent() {
        guard let last = lastPlay else { player.play(); return }
        start(rendition: last.rendition, username: last.username, peakBitrate: last.peak, keepReady: true)
    }

    func stop() {
        releaseTask?.cancel()
        releaseTask = nil
        generation += 1
        statusObservation = nil
        timeObservation = nil
        watchTask?.cancel()
        watchTask = nil
        startupTask?.cancel()
        startupTask = nil
        cancelStuckWatchdog()
        audioTask?.cancel()
        audioTask = nil
        audibleTask?.cancel()
        audibleTask = nil
        cachedAudibleGroup = nil
        if let relaySource { PlaylistRelay.shared.deactivate(source: relaySource) }
        relaySource = nil
        endFirstFrame()
        #if DEBUG
        if let tickToken { player.removeTimeObserver(tickToken) }
        tickToken = nil
        #endif
        player.pause()
        player.replaceCurrentItem(with: nil)
        readyUsername = nil
        playingUsername = nil
        itemPlayed = false
        pausedAt = nil
        refresh.clear()
        lastError = nil
        lastFailure = nil
        failed = false
    }

    /// 立刻挂起（后台、双窗降级）：放掉播放项、作废自己的中继源，不等 30 秒释放计时。
    /// `lastPlay` 留着；`pausedAt` 为空就补上，恢复（`setAllowed(true)`）时按暂停时长重新解析起播。
    func suspendNow() {
        allowed = false
        if pausedAt == nil { pausedAt = Date() }
        releaseTask?.cancel()
        releaseTask = nil
        generation += 1
        statusObservation = nil
        timeObservation = nil
        watchTask?.cancel()
        watchTask = nil
        startupTask?.cancel()
        startupTask = nil
        cancelStuckWatchdog()
        audioTask?.cancel()
        audioTask = nil
        audibleTask?.cancel()
        audibleTask = nil
        cachedAudibleGroup = nil
        endFirstFrame()
        #if DEBUG
        if let tickToken { player.removeTimeObserver(tickToken) }
        tickToken = nil
        #endif
        player.pause()
        player.replaceCurrentItem(with: nil)
        if let relaySource { PlaylistRelay.shared.deactivate(source: relaySource) }
        relaySource = nil
        refresh.clear()
        itemPlayed = false
        if playingUsername != nil { playingUsername = nil }
        if readyUsername != nil { readyUsername = nil }
        Self.log.info("suspend-now lane=\(self.laneLabel, privacy: .public)")
    }

    // MARK: 首帧

    /// 每个新播放项重新计时：结束上一段未完成的 signpost，再开一段。
    private func beginFirstFrame() {
        endFirstFrame()
        playStarted = Date()
        firstFrameLogged = false
        firstFrameInterval = Self.signposter.beginInterval("first-frame", id: Self.signposter.makeSignpostID())
    }

    /// 播放项被拆掉或停止：没出首帧的那段 signpost 也要收尾，不留悬空区间。
    private func endFirstFrame() {
        presentationObservation = nil
        if let state = firstFrameInterval {
            Self.signposter.endInterval("first-frame", state)
            firstFrameInterval = nil
        }
    }

    /// 首帧以「开播（timeControlStatus 首次为 playing）之后，播放项第一次有有效画面尺寸（presentationSize）」为准。
    /// 播放层（`AVPlayerLayer.isReadyForDisplay`）在视图里，控制器拿不到，所以用播放项的尺寸；两端口径一致。
    private func armFirstFrame(_ item: AVPlayerItem?, token: Int) {
        guard !firstFrameLogged, let item else { return }
        if Self.hasPicture(item.presentationSize) {
            logFirstFrame(item)
            return
        }
        presentationObservation = item.observe(\.presentationSize, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let self, token == self.generation, Self.hasPicture(item.presentationSize) else { return }
                self.logFirstFrame(item)
            }
        }
    }

    private static func hasPicture(_ size: CGSize) -> Bool {
        size.width > 0 && size.height > 0
    }

    private func logFirstFrame(_ item: AVPlayerItem) {
        guard !firstFrameLogged else { return }
        firstFrameLogged = true
        let elapsed = Date().timeIntervalSince(playStarted)
        let size = item.presentationSize
        Self.log.info("first-frame lane=\(self.laneLabel, privacy: .public) \(elapsed, format: .fixed(precision: 2))s size=\(Int(size.width))x\(Int(size.height))")
        endFirstFrame()
    }

    private func makeItem(url: URL) -> AVPlayerItem {
        let asset = AVURLAsset(url: url, options: [
            "AVURLAssetHTTPHeaderFieldsKey": [
                "Referer": "https://chaturbate.com/",
                "Origin": "https://chaturbate.com",
                "User-Agent": UserAgent.safari
            ]
        ])
        let item = AVPlayerItem(asset: asset)
        // The relay already selected one rung. A peak cap makes AVPlayer stall when that rung
        // bursts above the number, or start on a softer variant.
        item.preferredPeakBitRate = 0
        item.startsOnFirstEligibleVariant = false
        item.preferredForwardBufferDuration = 6
        item.automaticallyPreservesTimeOffsetFromLive = true
        item.configuredTimeOffsetFromLive = CMTime(seconds: 3, preferredTimescale: 600)
        item.canUseNetworkResourcesForLiveStreamingWhilePaused = false
        // 静音从一开始就不自动选独立音轨。读到组之后若不允许空选择，再打开自动选择。
        // 该开关在 AVPlayer 上而不是 AVPlayerItem 上，会跨播放项保留，所以每次都显式写。
        player.appliesMediaSelectionCriteriaAutomatically = !wantsMuted
        return item
    }

    /// 卡顿、码率、错误日志：只在事件发生时记录，没有定时唤醒；不含地址与 cookie。
    /// DEBUG 构建另有首帧耗时与每 10 秒一次的缓冲采样。
    private func watch(_ item: AVPlayerItem) {
        watchTask?.cancel()
        #if DEBUG
        watchDebugSamples(item)
        #endif
        watchTask = Task { @MainActor [weak item] in
            guard let item else { return }
            let center = NotificationCenter.default
            await withTaskGroup(of: Void.self) { group in
                group.addTask { @MainActor in
                    for await _ in center.notifications(named: .AVPlayerItemPlaybackStalled, object: item) {
                        Self.log.info("stall")
                    }
                }
                group.addTask { @MainActor in
                    for await _ in center.notifications(named: .AVPlayerItemNewAccessLogEntry, object: item) {
                        guard let e = item.accessLog()?.events.last else { continue }
                        Self.log.info("access stalls=\(e.numberOfStalls) indicated=\(Int(e.indicatedBitrate)) observed=\(Int(e.observedBitrate)) startup=\(e.startupTime, format: .fixed(precision: 2))s dropped=\(e.numberOfDroppedVideoFrames) requests=\(e.numberOfMediaRequests)")
                    }
                }
                group.addTask { @MainActor in
                    for await _ in center.notifications(named: .AVPlayerItemNewErrorLogEntry, object: item) {
                        guard let e = item.errorLog()?.events.last else { continue }
                        Self.log.error("error-log \(e.errorStatusCode) \(e.errorDomain, privacy: .public) \(e.errorComment ?? "", privacy: .private)")
                    }
                }
                group.addTask { @MainActor in
                    for await _ in center.notifications(named: .AVPlayerItemFailedToPlayToEndTime, object: item) {
                        Self.log.error("failed-to-play-to-end")
                    }
                }
            }
        }
    }

    #if DEBUG
    private func watchDebugSamples(_ item: AVPlayerItem) {
        if let tickToken { player.removeTimeObserver(tickToken) }
        tickToken = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 10, preferredTimescale: 1), queue: .main) { [weak self, weak item] _ in
            MainActor.assumeIsolated {
                guard let self, let item else { return }
                let now = item.currentTime().seconds
                let ahead = item.loadedTimeRanges.map(\.timeRangeValue).first { $0.containsTime(item.currentTime()) }
                    .map { $0.end.seconds - now } ?? 0
                let rate = self.player.rate
                Self.log.info("buffer ahead=\(ahead, format: .fixed(precision: 1))s rate=\(rate, format: .fixed(precision: 1)) muted=\(self.player.isMuted) likelyToKeepUp=\(item.isPlaybackLikelyToKeepUp)")
            }
        }
    }
    #endif

    /// 开播后记音轨数（事件日志）：独立音轨被丢掉时可听音轨是 0。
    /// 静音且允许空选择时音轨还在主列表里，只是没选中（日志带 `held`），不报「这路没有声音」。
    /// HLS 的 `asset.loadTracks` 恒为空，要看 `item.tracks`；而它在刚开播时可能还没填好，所以等 2 秒再数；
    /// 没有可听音轨时不立刻记错，再等 3 秒复查（判定见 `MuteAudioPolicy.audioReport`），复查仍无才记 error。
    private func logAudioTracks(of item: AVPlayerItem?, username: String) {
        guard let item else { return }
        audioTask?.cancel()
        audioTask = Task { @MainActor [weak self, weak item] in
            var delay = PlaybackPolicy.audioCheckDelay
            var isRecheck = false
            while true {
                try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                guard !Task.isCancelled, let self, let item else { return }
                let audio = item.tracks.filter { $0.assetTrack?.mediaType == .audio }
                let enabled = audio.filter(\.isEnabled).count
                switch MuteAudioPolicy.audioReport(
                    muted: self.wantsMuted, isRecheck: isRecheck, enabledTrackCount: enabled, group: self.audibleGroup
                ) {
                case .ok(let reported, let held):
                    if held {
                        Self.log.info("audio tracks=\(reported) enabled=\(reported) held")
                    } else {
                        Self.log.info("audio tracks=\(audio.count) enabled=\(reported)")
                    }
                    return
                case .missing:
                    Self.log.error("audio tracks=\(audio.count) enabled=0 (no audible track)")
                    self.noAudioDetected?(username)
                    return
                case .recheck:
                    isRecheck = true
                    delay = PlaybackPolicy.audioRecheckDelay
                }
            }
        }
    }

    /// 错误本身与各层 underlying error 的 (domain, code)，供归类成中文文案。
    private static func errorChain(_ error: Error?) -> [(domain: String, code: Int)] {
        var chain: [(domain: String, code: Int)] = []
        var current = error as NSError?
        while let e = current, chain.count < 6 {
            chain.append((e.domain, e.code))
            current = e.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return chain
    }

    /// 系统原文只进日志（域与错误码一并记，便于核对映射），不进界面。
    private static func logFailure(_ error: Error?, what: String) {
        let chain = errorChain(error).map { "\($0.domain)/\($0.code)" }.joined(separator: " <- ")
        // 系统错误原文可能含地址：只公开错误域与错误码，原文标 private（正式版日志里被遮蔽）。
        let text = error?.localizedDescription ?? "-"
        log.error("\(what, privacy: .public) \(chain, privacy: .public) \(text, privacy: .private)")
    }

    private func observe(_ item: AVPlayerItem, username: String, token: Int) {
        statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let self, token == self.generation, item.status == .failed else { return }
                self.classifyItemFailure(item, username: username)
            }
        }
        timeObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            Task { @MainActor in
                guard let self, token == self.generation else { return }
                if player.timeControlStatus == .playing {
                    if !self.itemPlayed {
                        // 每个媒体项只打一次：性能探针以它为真实起播时刻（Release 也输出；房间名按隐私标 private）。
                        Self.log.info("playing-started \(username, privacy: .private)")
                        self.logAudioTracks(of: self.player.currentItem, username: username)
                        self.armFirstFrame(self.player.currentItem, token: token)
                    }
                    self.itemPlayed = true
                    self.startupTask?.cancel()
                    self.startupTask = nil
                    self.cancelStuckWatchdog()
                    self.readyUsername = username
                    self.playingUsername = username
                    self.failed = false
                    self.lastError = nil
                } else {
                    self.playingUsername = nil
                    self.armStuckWatchdog()
                }
            }
        }
    }

    private func failureInfo(_ error: Error?, stage: String, username: String, message: String) -> PlaybackFailureInfo {
        PlaybackFailureInfo(stage: stage, username: username, chain: Self.errorChain(error), message: message)
    }

    /// 进入 `itemFailed` 之前先过分类钩子：资源降级不消耗重试预算、不进入失败态；
    /// 不可恢复直接失败，不做那一次自动重试；其余（单窗恒为此）走原来的流程。
    private func classifyItemFailure(_ item: AVPlayerItem, username: String) {
        let chain = Self.errorChain(item.error)
        let info = PlaybackFailureInfo(
            stage: "item-failed", username: username, chain: chain,
            message: PlaybackErrorCopy.failureMessage(chain: chain)
        )
        switch failureFilter?(info) ?? .roomRetry {
        case .roomRetry:
            itemFailed(item, username: username, allowRetry: true)
        case .fatal:
            itemFailed(item, username: username, allowRetry: false)
        case .resourceDegrade:
            Self.logFailure(item.error, what: "item-degrade")
            lastFailure = info
            resourceDegraded?(info)
        }
    }

    private func itemFailed(_ item: AVPlayerItem, username: String, allowRetry: Bool) {
        // One silent retry on a fresh address; a second failure goes to the UI.
        if allowRetry, !retried, lastPlay?.username == username {
            Self.log.info("retry after item failure")
            retryWithFreshAddress(username: username)
            return
        }
        Self.logFailure(item.error, what: "item-failed")
        let message = PlaybackErrorCopy.failureMessage(chain: Self.errorChain(item.error))
        lastFailure = failureInfo(item.error, stage: "item-failed", username: username, message: message)
        lastError = message
        failed = true
    }

    /// 占用该房间唯一一次自动重试：交给外面重新解析（没有外部回调就原地重建）。
    private func retryWithFreshAddress(username: String) {
        guard let last = lastPlay, last.username == username else { return }
        retried = true
        // 重新解析加取档可能超过起播超时：旧看门狗不能在等待新地址时误报；新项起播时重新计时。
        startupTask?.cancel()
        startupTask = nil
        cancelStuckWatchdog()
        if let refreshRequested {
            if let relaySource { PlaylistRelay.shared.setRefreshing(false, source: relaySource) }
            refresh.begin()
            refreshRequested(username, true)
        } else {
            start(rendition: last.rendition, username: last.username, peakBitrate: last.peak)
        }
    }

    /// 刷新没换来新播放项就结束了（失败、确认页、任务放弃）：清掉待刷新，并把原因交给界面（失败态 + 重试）。
    /// 已被新项起播或停止清掉时不重复报。`reason` 为 nil 表示只清状态不报错（任务放弃，由取代者收尾）。
    func refreshFinished(reason: String?, report: Bool) {
        let message = refresh.fail(reason: reason)
        guard report, let message else { return }
        lastError = message
        failed = true
    }

    /// 已开播后的卡住看门狗：只在「应该播放且开过播却没在播」时计时；状态在 waiting / paused 间抖动
    /// 不重置计时，开始播放、暂停、换项、停止时取消。
    private func armStuckWatchdog() {
        guard stuckTask == nil, itemPlayed, allowed, !refresh.pending else { return }
        let token = generation
        stuckBaseEnd = loadedEnd()
        stuckTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(PlaybackPolicy.stuckAfter * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            self.stuckTask = nil
            self.stuckFired(token: token)
        }
    }

    private func cancelStuckWatchdog() {
        stuckTask?.cancel()
        stuckTask = nil
        stuckBaseEnd = nil
    }

    /// 当前播放项已加载区间的最远末端（秒）；没有项或没有已加载区间为 nil。
    private func loadedEnd() -> TimeInterval? {
        player.currentItem?.loadedTimeRanges.map { $0.timeRangeValue.end.seconds }.filter(\.isFinite).max()
    }

    private func stuckFired(token: Int) {
        let action = PlaybackPolicy.stuckAction(
            sameItem: token == generation,
            allowed: allowed,
            itemPlayed: itemPlayed,
            isPlaying: player.timeControlStatus == .playing,
            refreshing: refresh.pending,
            retryAvailable: !retried,
            bufferAdvancing: PlaybackPolicy.bufferAdvanced(previousEnd: stuckBaseEnd, currentEnd: loadedEnd()),
            renewing: relaySource.map { PlaylistRelay.shared.isRenewing(source: $0) } ?? false
        )
        guard let username = lastPlay?.username else { return }
        switch action {
        case .ignore:
            return
        case .rearm:
            // 仍有进展（缓冲在涨或续期进行中）：不消耗重试，重新计时。
            Self.log.info("stuck-watchdog rearm: still progressing")
            armStuckWatchdog()
        case .recover:
            Self.log.info("stuck-watchdog recover after \(PlaybackPolicy.stuckAfter, format: .fixed(precision: 0))s")
            retryWithFreshAddress(username: username)
        case .giveUp:
            let error = player.currentItem?.error
            Self.logFailure(error, what: "stuck-giveup")
            let message = PlaybackErrorCopy.failureMessage(chain: Self.errorChain(error))
            lastFailure = failureInfo(error, stage: "stuck-giveup", username: username, message: message)
            lastError = message
            failed = true
        }
    }

    /// 起播看门狗：只在「应该播放」时计时，暂停 / 停止时取消，恢复后重新计时。
    private func armStartupWatchdog() {
        startupTask?.cancel()
        let token = generation
        startupTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(PlaybackPolicy.startupTimeout * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            let stalled = PlaybackPolicy.startupStalled(
                sameItem: token == self.generation,
                allowed: self.allowed,
                itemPlayed: self.itemPlayed,
                isPlaying: self.player.timeControlStatus == .playing,
                refreshing: self.refresh.pending
            )
            guard stalled else { return }
            let error = self.player.currentItem?.error
            Self.logFailure(error, what: "start-timeout")
            let message = PlaybackErrorCopy.startTimeoutMessage(chain: Self.errorChain(error))
            self.lastFailure = self.failureInfo(
                error, stage: "start-timeout", username: self.lastPlay?.username ?? "", message: message
            )
            self.lastError = message
            self.failed = true
        }
    }
}

#if DEBUG
/// 自测用的音频状态快照（含中继侧的音频清单状态）。
struct SelfTestAudioSnapshot {
    var isMuted: Bool
    var volume: Float
    var wantsMuted: Bool
    var groupOptions: Int?
    var audibleSelected: Bool?
    var tracks: Int
    var enabled: Int
    var detached: Bool
    var relayAudioFeed: Bool
    var relayAudioSuppressed: Bool
}

/// 线程安全计数器：直播边缘 seek 次数（仅 Debug）。
final class SelfTestCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func add(_ delta: Int) { lock.lock(); count += delta; lock.unlock() }
    var value: Int { lock.lock(); defer { lock.unlock() }; return count }
}

let selftestLiveSeeks = SelfTestCounter()

extension PlayerController {
    /// 命令行带 `--selftest` 时为真：音量恒为 0。
    static let selftestActive = CommandLine.arguments.contains("--selftest")

    func selftestAudioSnapshot() -> SelfTestAudioSnapshot {
        let item = player.currentItem
        let audio = item?.tracks.filter { $0.assetTrack?.mediaType == .audio } ?? []
        var selected: Bool?
        if let item, let group = cachedAudibleGroup {
            selected = item.currentMediaSelection.selectedMediaOption(in: group) != nil
        }
        let relay = relaySource.map { PlaylistRelay.shared.selftestAudioState(source: $0) } ?? (false, false)
        return SelfTestAudioSnapshot(
            isMuted: player.isMuted, volume: player.volume, wantsMuted: wantsMuted,
            groupOptions: cachedAudibleGroup?.options.count, audibleSelected: selected,
            tracks: audio.count, enabled: audio.filter(\.isEnabled).count, detached: audioDetached,
            relayAudioFeed: relay.0, relayAudioSuppressed: relay.1
        )
    }
}
#endif
