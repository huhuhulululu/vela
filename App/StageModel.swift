import AVFoundation
import Combine
import Foundation
import os

@MainActor
final class StageModel: ObservableObject {
    typealias Source = FeedSource

    @Published var feed = FeedStore()
    @Published var presence = Presence()
    @Published var pinned = true
    @Published var fitFill = true
    /// 竖屏房间自动完整画面（只对当前房间，不改 `fitFill`）。
    @Published var portraitFit = PortraitFit()
    @Published var gender: Gender?
    @Published var region: Region?
    @Published var publicOnly = true
    @Published var restSavesPower = false
    @Published var volume = 0.85
    @Published var muted = true
    @Published var manuallyPaused = false
    @Published var searchText = ""
    @Published var tagText = ""
    /// 正在显示的全站搜索词：非 nil 时列表是搜索结果组成的临时列表，清空搜索框回到原来源。
    @Published var siteSearch: String?
    @Published var source: Source = .guess
    @Published var ageConfirmed = false
    @Published var loading = false
    @Published var catalogError: String?
    @Published var playbackNote: String?
    @Published var chromeVisible = true
    @Published var showFilters = false
    @Published var showOpacity = false
    @Published var showVolume = false
    @Published var showCuration = false
    @Published var showTaste = false
    @Published var showChallenge = false
    @Published var hintVisible = false
    @Published var toast: String?
    /// 取消收藏提示里的可撤销用户名；`canRestoreFavorite` 为 false 时不显示撤销。
    @Published var toastUndoName: String?
    struct FavoriteSuggestPrompt: Equatable {
        var username: String
        var displayName: String
    }
    @Published var favoriteSuggest: FavoriteSuggestPrompt?
    /// 老板键隐藏了窗口：此时无论窗口可见性怎么变，都不恢复播放。
    @Published private(set) var bossHidden = false
    @Published var curation = Curation()
    /// 收藏房间的在线状态缓存（3 分钟内的结果不重查）与最近一次取回的目录。
    @Published var favoriteStatus = FavoriteCache()
    /// 查询失败 / 被拦截后的自动补查冷却（手动刷新不受限）。
    var favoriteRetry = FavoriteRetryGate()
    @Published var favoritesChecking = false
    /// 离线收藏的上次开播时间（30 分钟缓存，只在打开收藏夹时按需查）与它的自动补查冷却。
    @Published var lastBroadcasts = LastBroadcastCache()
    var lastBroadcastChecking = false
    var lastBroadcastRetry = FavoriteRetryGate()
    /// 全站热门标签（6 小时缓存，打开口味面板或筛选面板时按需取）。
    @Published var hotTags = HotTagCache()
    var hotTagsLoading = false
    var knownCatalog = KnownCatalog()
    /// 收藏查询同一时间只跑一批（新请求取代在途批次）。
    let favoriteRunner = SupersedingRunner<FavoriteResolution>()
    /// 站点确认页完成后要接着做的事：被确认打断的目录加载或收藏查询。nil 表示只恢复播放。
    var challengeResume: ChallengeResume?

    let player = PlayerController()
    /// 画面订阅这一份，不直接订阅播放器：播放器会把没变的就绪 / 失败状态反复写进 `@Published`。
    let playback = PlaybackFace()
    let sidecar = SiteSidecar()
    var screenScale = 2.0

    let settings: SettingsStore
    let http: URLSessionHTTPClient
    var catalogAPI: CatalogAPI
    /// 冷启动只进一次 bootstrap；年龄已确认时目录请求只发一次（初始化可以先领）。
    var startupGate = StartupGate()
    var loadGeneration = 0
    var playGeneration = 0
    var paging = PagingState()
    var loadedOffset: Int {
        get { paging.offset }
        set { paging.commit(newValue) }
    }
    var idleStart = IdleStart()
    var loadSlot = LoadSlot()
    /// 当前列表是哪个来源取回的（切换来源失败时据此回滚）。
    var loadedSource: Source = .guess
    /// 最近一次由播放器失败状态写进 playbackNote 的文案；失败恢复时据此清掉，不动其他来源的提示。
    var playerNote: String?
    /// 播放器已报告的状态，来自 StageView 订阅时收到的新值（@Published 在 willSet 发出，回读属性拿到的是旧值）。
    var readyName: String?
    var playingName: String?
    var sidecarUntil = Date.distantPast
    var pointHeight = 740.0
    var chromeTask: Task<Void, Never>?
    var quality = QualityGovernor(now: Date())
    var qualityTask: Task<Void, Never>?
    var qualitySignature = ""
    var currentPlayable: StreamRendition?
    var currentCap = Double.infinity
    var qualityObserver: AnyCancellable?
    static let qualityLog = Logger(subsystem: "com.vela.stage", category: "quality")
    static let playbackLog = Logger(subsystem: "com.vela.stage", category: "renew")
    var skipTask: Task<Void, Never>?
    /// 当前这一轮起播（解析流地址、取档）：同一时间只保留一个，新的起播取消旧的。
    var playTask: Task<Void, Never>?
    /// 启动时读偏好文件的现场（坏文件已备份，或备份失败进入只读），界面可见后提示一次。
    var startupNotices: [String] = []
    /// 连续质询的弹窗闸门：达到上限后改为提示并跳过，不再循环弹确认页。
    var challengeGate = ChallengeGate()
    /// 按口味定向的候选在常规目录起播后并发补拉；重载或换来源时取消。
    var targetedTask: Task<Void, Never>?
    /// 冷启动首页之后的目录页。换来源、重新加载、全站搜索时取消，失败只记日志。
    var catalogRestTask: Task<Void, Never>?
    /// 房间暂时离开时的一次性延迟重试；切房（`playCurrent`）时取消。
    var awayTask: Task<Void, Never>?
    var awayTimer = AwayRetryTimer()
    /// 在途全站搜索及其操作代次（见 `SearchEpoch`）。
    var siteSearchTask: Task<Void, Never>?
    var searchEpoch = SearchEpoch()
    var awayRetry = AwayRetry()
    let tasteSaver: DeferredSaver<Taste>
    let tasteStore: TasteStore
    let curationSaver: DeferredSaver<Curation>
    let watchLedgerStore: WatchLedgerStore
    let watchLedgerSaver: DeferredSaver<WatchLedger>
    @Published var watchLedger = WatchLedger()
    /// 平台钩子：老板键、系统活跃度、应用通知与隐藏 / 终止。macOS 与 iOS 各有一份同名实现。
    let platform = PlatformHooks()
    /// 播放闸门或 App 隐藏变化后，让窗口桥接重判指针轮询。`BridgeView` 挂上时登记。
    var refreshPointerPolling: (() -> Void)?
    /// 口味画像：停留结算、收藏、面板操作都更新它；只存本机。
    @Published var taste = Taste()
    /// 当前停留记账的房间是在哪个来源里出现的（换来源时 `source` 先变、列表后换，结算要用旧来源）。
    var dwellSource: Source = .guess
    /// 「猜你喜欢」列表里各房间（小写用户名）的推荐理由，随目录重载整体替换。
    var reasons: [String: String] = [:]
    var dwell = DwellTracker()
    var relatedTask: Task<Void, Never>?
    var skips = SkipTracker()
    var systemInactive = false
    var toastTask: Task<Void, Never>?
    /// 推荐用的常规候选（不含按口味定向拉来的额外页）与它的标签稀有度；记账与排序共用。
    var regularCandidates: [Room] = []
    var tagRarity = TagRarity(rooms: [])
    /// 本次停留里已超时消失过的常看提示（不记账，换房后清空）。
    var suggestSnoozed: Set<String> = []
    var favoriteSuggestTask: Task<Void, Never>?
    var favoriteSuggestWakeTask: Task<Void, Never>?
    let log = Logger(subsystem: "com.vela.stage", category: "stage")

    init(
        settings: SettingsStore = SettingsStore(),
        http: URLSessionHTTPClient = URLSessionHTTPClient()
    ) {
        self.settings = settings
        self.http = http
        let tasteStore = TasteStore(url: TasteStore.defaultURL(), legacyURL: PreferenceStore.defaultURL())
        self.tasteStore = tasteStore
        let curationStore = CurationStore(url: CurationStore.defaultURL())
        let watchLedgerStore = WatchLedgerStore(url: WatchLedgerStore.defaultURL())
        let log = self.log
        // 读失败且坏文件没能改名备份时存储进入只读：保存被跳过，日志里要区分于普通写盘失败。
        self.tasteSaver = DeferredSaver {
            guard !tasteStore.save($0) else { return }
            if tasteStore.isReadOnly { log.error("taste save skipped: unreadable file kept read-only") } else { log.error("taste save failed") }
        }
        self.curationSaver = DeferredSaver {
            guard !curationStore.save($0) else { return }
            if curationStore.isReadOnly { log.error("curation save skipped: unreadable file kept read-only") } else { log.error("curation save failed") }
        }
        self.watchLedgerStore = watchLedgerStore
        self.watchLedgerSaver = DeferredSaver {
            guard !watchLedgerStore.save($0) else { return }
            if watchLedgerStore.isReadOnly { log.error("watch ledger save skipped: unreadable file kept read-only") } else { log.error("watch ledger save failed") }
        }
        self.catalogAPI = CatalogAPI(client: http)
        var notices: [String] = []
        let tasteBefore = PreferenceHealth.backupNames(tasteStore.url)
        taste = tasteStore.load()
        notices += PreferenceHealth.notice(label: "口味", url: tasteStore.url, before: tasteBefore, readOnly: tasteStore.isReadOnly)
        let curationBefore = PreferenceHealth.backupNames(curationStore.url)
        curation = curationStore.load()
        notices += PreferenceHealth.notice(label: "收藏与屏蔽", url: curationStore.url, before: curationBefore, readOnly: curationStore.isReadOnly)
        let ledgerBefore = PreferenceHealth.backupNames(watchLedgerStore.url)
        watchLedger = watchLedgerStore.load()
        notices += PreferenceHealth.notice(label: "观看记录", url: watchLedgerStore.url, before: ledgerBefore, readOnly: watchLedgerStore.isReadOnly)
        startupNotices = notices
        for text in notices { log.error("preference file unreadable: \(text, privacy: .public)") }
        loadSettings()
        player.setVolume(volume, muted: muted)
        player.refreshRequested = { [weak self] name, isRetry in self?.refreshCurrent(name, isRetry: isRetry) }
        player.noAudioDetected = { [weak self] name in self?.noteNoAudio(name) }
        player.renewRendition = { [weak self] name, cap in await self?.renewRendition(name, cap: cap) }
        installObservers()
        kickoffCatalogIfConfirmed()
    }

    private func loadSettings() {
        ageConfirmed = settings.ageConfirmed
        pinned = settings.pinned
        presence = Presence(
            conditionalOpacity: settings.conditionalOpacity,
            clickThrough: settings.clickThrough,
            restingOpacity: settings.restingOpacity,
            engagedOpacity: settings.engagedOpacity
        )
        restSavesPower = settings.restSavesPower
        publicOnly = settings.publicOnly
        fitFill = settings.fitFill
        if settings.layoutRevision < 3 {
            fitFill = false
            settings.fitFill = false
            restSavesPower = false
            settings.restSavesPower = false
            settings.layoutRevision = 3
        }
        volume = settings.volume
        muted = settings.muted
        gender = settings.gender
        region = settings.region
        tagText = settings.tag
        feed = FeedStore(query: RoomQuery(publicOnly: settings.publicOnly, blocked: curation.blockedSet))
        source = settings.source
        loadedSource = source
    }

    private func installObservers() {
        // 面板开合等不经过 applyPower 的状态变化也要让「离开」计时跟上。
        qualityObserver = objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async { self?.refreshQuality() }
        }
        platform.observeApp(
            unhidden: { [weak self] in
                self?.windowShownByUser()
                self?.syncPlayerHidden()
                // 老板键先改闸门再 unhide，当时 isHidden 还是 true，轮询要等隐藏状态落下再判。
                self?.refreshPointerPolling?()
            },
            hidden: { [weak self] in
                self?.syncPlayerHidden()
                self?.refreshPointerPolling?()
            },
            // 系统 ⌘Q 不经过 quit()：在终止通知里把最后一个房间的停留与未落盘的修改同步写出。
            terminating: { [weak self] in self?.flushForTermination() }
        )
    }

    /// 「播放」菜单里无修饰键等效键（空格、M、F、I、方向键）是否可用；与键盘监视器同一条规则（`ShortcutGate`）。
    /// 文本框在弹层里（弹层开着 `isInteracting` 即为真），所以菜单这边不需要再看第一响应者。
    var plainKeysEnabled: Bool {
        ShortcutGate.allowsPlainKeys(ageConfirmed: ageConfirmed, interacting: isInteracting, editingText: false)
    }

    var isInteracting: Bool {
        showFilters || showOpacity || showVolume || showCuration || showTaste || showChallenge
    }

    /// 列表为空时的说明：按原因区分（目录出错、搜索无结果、全被屏蔽、全被不想看过滤、没有公开房间、
    /// 收藏都不在线、目录确实为空），见 `EmptyListCopy`。
    var emptyMessage: String {
        EmptyListCopy.message(
            catalogError: catalogError, query: currentQuery, catalog: feed.catalog,
            source: source, favoriteCount: curation.favorites.count
        )
    }

    /// 复查后仍没有可听音轨：用现有横幅说一声，免得人反复点喇叭。
    func noteNoAudio(_ username: String) {
        guard username == feed.currentUsername else { return }
        showToast("这路没有声音")
    }

    /// 关闭首次提示：之后不再出现。
    func dismissHint() {
        hintVisible = false
        settings.hintSeen = true
    }

    /// 「播放」菜单的重试：有当前房间就重新解析起播，没有（空列表）就重新加载目录。
    func retryFromMenu() {
        if feed.current != nil {
            retryPlayback()
        } else {
            Task { await retry() }
        }
    }

    func resolvedPresence() -> Presence {
        var copy = presence
        if !ageConfirmed {
            copy.conditionalOpacity = false
            copy.interacting = true
            return copy
        }
        copy.interacting = isInteracting
        return copy
    }

    func bootstrap() {
        guard startupGate.enterBootstrap() else { return }
        LegacyLoginCleanup.runOnce(settings: settings)
        let hooked = platform.startSystemHooks(
            bossKey: { [weak self] in self?.toggleBoss() },
            inactiveChanged: { [weak self] inactive in
                self?.systemInactive = inactive
                self?.applyPower()
            }
        )
        if !hooked {
            log.error("boss key unavailable")
        }
        guard ageConfirmed else { return }
        hintVisible = !settings.hintSeen
        announceStartupNotices()
        // 初始化时已经领过目录请求就不再发；未确认年龄的路径不会领到这里。
        guard startupGate.claimCatalog(ageConfirmed: true) else { return }
        Task { await reload() }
    }

    /// 年龄已确认就在模型初始化后发唯一一次目录请求，不等首个视图出现。
    /// 未确认时不发；确认后仍由 `confirmAge` 加载。
    private func kickoffCatalogIfConfirmed() {
        guard startupGate.claimCatalog(ageConfirmed: ageConfirmed) else { return }
        Task { await reload() }
    }

    func confirmAge() {
        ageConfirmed = true
        settings.ageConfirmed = true
        hintVisible = !settings.hintSeen
        announceStartupNotices()
        // 与初始化、bootstrap 共用闸门：已领过目录请求就不再发。
        guard startupGate.claimCatalog(ageConfirmed: true) else { return }
        Task { await reload() }
    }

    /// 偏好文件读失败的提示只在界面可见后说一次。
    private func announceStartupNotices() {
        guard !startupNotices.isEmpty else { return }
        showToast(startupNotices.joined(separator: "；"), seconds: 8)
        startupNotices = []
    }

    func quit() {
        platform.terminate()
    }

    func flushForTermination() {
        endView()
        tasteSaver.flush()
        curationSaver.flush()
        watchLedgerSaver.flush()
    }


    /// 用户手动划动：记住方向，自动跳过沿同一方向。
    func step(_ delta: Int) {
        skips.noteManual(delta)
        move(delta, userInitiated: true)
    }

    @discardableResult
    func move(_ delta: Int, userInitiated: Bool = false) -> Bool {
        let before = feed.currentUsername
        updateFeed { $0.step(delta) }
        guard feed.currentUsername != before else { return false }
        manuallyPaused = MoveRules.pausedAfterMove(manuallyPaused: manuallyPaused, userInitiated: userInitiated)
        if hintVisible { dismissHint() }
        requestPlay()
        Task { await loadMoreIfNeeded() }
        return true
    }

    /// 画面已出现：界面与降画质判定用。必须用订阅收到的新值，不能回读 player.readyUsername。
    /// 同一次播放里 `readyUsername` 会被反复写成同一个值；画面镜像只在变化时通知。
    /// 视频尺寸可能比第一次回调更晚才有，竖屏判定仍每次看一眼。
    func noteReady(_ username: String?) {
        playback.setReady(username)
        let readyChanged = username != readyName
        readyName = username
        notePortrait(username)
        if readyChanged { refreshQuality() }
    }

    /// 播放器此刻真在播的房间变了（缓冲、暂停、失败都会让它变空）：回放成功清连续跳过计数，停留计时随之启停。
    /// `timeControlStatus` 停在 playing 时也会重复回调；房间没变就不要重排停留和画质。
    func notePlaying(_ username: String?) {
        playback.setPlaying(username)
        guard username != playingName else {
            // 仍是这一间在播：只补上「播放成功」该清的标记。重排停留会把收藏提示的等待任务取消再造一份。
            if let username, username == feed.currentUsername {
                skips.reset()
                dwell.clearFailure()
            }
            return
        }
        playingName = username
        if let username, username == feed.currentUsername {
            skips.reset()
            dwell.clearFailure()
        }
        updateDwellActive()
        refreshQuality()
    }

    /// `onReceive` 不补发当前值。视图刚挂上时若已经在播，把镜像和停留对齐一次；已经对齐则都是空操作。
    func syncPlaybackFace() {
        let ready = player.readyUsername
        let playing = player.playingUsername
        if ready != readyName {
            noteReady(ready)
        } else {
            playback.setReady(ready)
        }
        playback.setFailed(player.failed)
        if playing != playingName {
            notePlaying(playing)
        } else {
            playback.setPlaying(playing)
        }
    }

    func select(_ next: Source) {
        guard source != next else { return }
        source = next
        settings.source = next
        Task { await reload() }
    }

    /// 搜索框打字：只改可见列表，当前房间不换、不重新起播；回车（`submitSearch`）才提交换房。
    /// 全站搜索中：继续打字不换房也不过滤临时列表；清空搜索框退出临时列表、恢复原来源。
    func previewSearch() {
        switch SiteSearch.onEdit(text: searchText, active: siteSearch) {
        case .filter:
            if SiteSearch.cancelsInflight(text: searchText) { cancelSiteSearch() }
            updateFeed { $0.previewQuery(currentQuery) }
        case .keep:
            return
        case .exit:
            Task { await reload() }
        }
    }

    func applySearch() {
        // 全站搜索中继续打字不发请求（`currentQuery` 此时不按文字过滤），清空搜索框才回到原来源。
        if SiteSearch.onEdit(text: searchText, active: siteSearch) == .exit {
            Task { await reload() }
            return
        }
        let previous = feed.currentUsername
        updateFeed { $0.setQuery(currentQuery) }
        guard feed.currentUsername != previous else { return }
        requestPlay()
    }

    func submitSearch() {
        let text = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        var focused = false
        updateFeed { focused = $0.focus(text) }
        if focused {
            cancelSiteSearch()
            showFilters = false
            requestPlay()
            return
        }
        switch SiteSearch.onSubmit(text: text, localMatches: feed.localMatches, active: siteSearch) {
        case .ignore:
            return
        case .remote(let keyword):
            startSiteSearch(keyword, fallback: Room.direct(username: text))
            return
        case .local:
            cancelSiteSearch()
        }
        if let room = Room.direct(username: text) {
            guard !curation.isBlocked(room.username) else {
                showToast("\(room.username) 在屏蔽列表里")
                return
            }
            updateFeed { $0.insertDirect(room) }
            showFilters = false
            requestPlay()
            return
        }
        applySearch()
    }

    func chooseGender(_ value: Gender?) {
        guard gender != value else { return }
        gender = value
        settings.gender = value
        Task { await reload() }
    }

    func chooseRegion(_ value: Region?) {
        guard region != value else { return }
        region = value
        settings.region = value
        Task { await reload() }
    }

    func setPublicOnly(_ value: Bool) {
        publicOnly = value
        settings.publicOnly = value
        applySearch()
    }

    func applyTag() {
        settings.tag = tagText
        Task { await reload() }
    }

    func togglePinned() {
        pinned.toggle()
        settings.pinned = pinned
    }

    func toggleConditional() {
        updatePresence { $0.conditionalOpacity.toggle() }
        settings.conditionalOpacity = presence.conditionalOpacity
        if !presence.conditionalOpacity { chromeVisible = true }
        applyPower()
    }

    func setClickThrough(_ value: Bool) {
        updatePresence { $0.clickThrough = value }
        settings.clickThrough = value
    }

    func setRestSavesPower(_ value: Bool) {
        restSavesPower = value
        settings.restSavesPower = value
        applyPower()
    }

    func setRestingOpacity(_ value: Double) {
        let clamped = min(max(value, 0.18), 0.85)
        updatePresence { $0.restingOpacity = clamped }
        settings.restingOpacity = clamped
    }

    func setEngagedOpacity(_ value: Double) {
        let clamped = min(max(value, 0.72), 1)
        updatePresence { $0.engagedOpacity = clamped }
        settings.engagedOpacity = clamped
    }

    /// 当前房间实际用不用铺满：全局铺满时竖屏房间改用完整画面。平台不允许裁切（iOS，裁决 5）时恒为 false。
    var effectiveFill: Bool { platform.allowsCrop && portraitFit.fill(global: fitFill, current: feed.currentUsername) }

    /// 出画面时按视频尺寸判断竖屏（不另发请求）；尺寸未知按横屏处理。
    private func notePortrait(_ username: String?) {
        guard let username else { return }
        let size = player.player.currentItem?.presentationSize ?? .zero
        var next = portraitFit
        next.noteReady(username, width: Double(size.width), height: Double(size.height))
        if next != portraitFit { portraitFit = next }
    }

    /// 竖屏自动完整画面期间按切换：只撤掉这次自动，不改全局设置；否则照常切换全局铺满。
    func toggleFit() {
        var next = portraitFit
        if next.dismiss(global: fitFill, current: feed.currentUsername) {
            portraitFit = next
            return
        }
        fitFill.toggle()
        settings.fitFill = fitFill
    }

    func toggleMute() {
        muted.toggle()
        // 音量被拉到 0 之后取消静音：回到一个较小的非零值，否则喇叭变了声音还是没有。
        if !muted, volume <= 0 {
            volume = AudioPolicy.volumeOnUnmute(current: volume)
            settings.volume = volume
        }
        settings.muted = muted
        player.setVolume(volume, muted: muted)
        applyPower()
    }

    func setVolume(_ value: Double) {
        volume = min(max(value, 0), 1)
        if volume == 0 { muted = true } else { muted = false }
        settings.volume = volume
        settings.muted = muted
        player.setVolume(volume, muted: muted)
    }

    func shiftVolume(_ delta: Double) {
        setVolume(volume + delta)
    }

    func togglePause() {
        manuallyPaused.toggle()
        applyPower()
    }


    /// 老板键：隐藏窗口并停止解码；再按恢复显示。手动暂停的状态不受影响。
    func toggleBoss() {
        bossHidden.toggle()
        applyPower()
        if bossHidden {
            platform.hide()
        } else {
            platform.unhide()
        }
    }

    func notePointer(inside: Bool, moved: Bool) {
        let changed = !pointerKnown || presence.pointerInside != inside
        pointerKnown = true
        if changed {
            updatePresence { $0.pointerInside = inside }
        }
        if inside {
            if changed && !chromeVisible { chromeVisible = true }
            if changed {
                scheduleChromeHide()
            }
            if moved { noteActivity() }
        } else if changed && !isInteracting {
            chromeVisible = false
        }
        if changed { applyPower() }
        // 指针回到窗口：穿透期间被推迟的常看提示此时可以显示了。
        if changed && inside { checkFavoriteSuggest(now: Date()) }
    }

    func noteActivity() {
        if !chromeVisible { chromeVisible = true }
        scheduleChromeHide()
    }

    func setWindowVisible(_ visible: Bool) {
        if visible { windowShownByUser() }
        applyPower(windowVisible: visible)
    }

    /// 窗口被老板键以外的方式显示（Dock、⌘Tab）：老板键隐藏状态作废，恢复播放；手动暂停仍保持。
    func windowShownByUser() {
        guard bossHidden else { return }
        bossHidden = false
        applyPower()
    }

    func notePointHeight(_ points: Double) {
        guard abs(points - pointHeight) > 8 else { return }
        pointHeight = points
        applyPower()
    }

    func dismissSurfaces() {
        showFilters = false
        showOpacity = false
        showVolume = false
        showCuration = false
        showTaste = false
    }



    var pointVisible = true
    var pointerKnown = false
    var prepared = PreparedCache()
    var currentMaster: URL?

    var currentQuery: RoomQuery {
        RoomQuery(
            text: siteSearch == nil ? searchText : "", publicOnly: publicOnly, blocked: curation.blockedSet, only: allowedNames(for: curation),
            hidden: source == .guess ? taste.vetoedKeys : []
        )
    }

    /// 收藏来源只显示当前收藏名单里的房间，其他来源不限。
    /// 全站搜索的临时列表不限。
    func allowedNames(for curation: Curation) -> Set<String>? {
        source == .favorites && siteSearch == nil ? curation.favoriteSet : nil
    }

    var pixelHeight: Double { max(pointHeight, 1) * screenScale }

    /// 所有对列表的改动都走这里：改动前后各取当前房间，用户名变了才触发换房间结算。
    /// 不能靠 `$feed` 订阅：`@Published` 在 willSet 发出，回调里读 `feed` 还是旧值。
    /// `installing`：这次改动把列表整体换成了这个来源的；即使当前房间同名也要结算旧来源下的停留，
    /// 且 `loadedSource` 先于结算更新，停留归属取新列表的真实来源（不是用户刚选的来源）。
    func updateFeed(installing: Source? = nil, _ body: (inout FeedStore) -> Void) {
        var copy = feed
        let before = copy.current?.username
        body(&copy)
        feed = copy
        let sourceChanged = installing.map { $0 != loadedSource } ?? false
        loadedSource = ListOrigin.dwell(loaded: loadedSource, installing: installing)
        if DwellTracker.shouldSettle(before: before, after: feed.current?.username, sourceChanged: sourceChanged) {
            currentRoomChanged()
        }
    }

    func updatePresence(_ body: (inout Presence) -> Void) {
        var copy = presence
        body(&copy)
        // 整份写回时内容没变也会发 objectWillChange。滑块停在原值、指针状态没变都不该把整棵树标脏。
        AssignIf.changed(from: presence, to: copy) { presence = $0 }
    }

    /// 按当前来源排序；纯计算，不写任何状态（理由等副作用在代次检查通过后由 `commitFetched` 写）。
    func arranged(_ page: CatalogPage) -> CatalogPage {
        let rooms = Recommender.arrange(
            page.rooms, source: source, taste: taste, now: Date(), seed: .random(in: .min ... .max),
            favorites: curation.favoriteSet, signals: rankSignals
        )
        return CatalogPage(rooms: rooms, total: page.total, nextOffset: page.nextOffset)
    }




    func showToast(_ text: String, seconds: Double = 3.5, undoFavorite: String? = nil) {
        // 两条顶部横幅不同时显示：后出现的替换前一个（被替换的常看提示不算问过）。
        endFavoriteSuggest(.withdrawn)
        toast = text
        if let undoFavorite, curation.canRestoreFavorite(undoFavorite) {
            toastUndoName = undoFavorite
        } else {
            toastUndoName = nil
        }
        toastTask?.cancel()
        toastTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.toast = nil
            self?.toastUndoName = nil
        }
    }

    func undoToastFavorite() {
        guard let name = toastUndoName, curation.canRestoreFavorite(name) else { return }
        toastTask?.cancel()
        toast = nil
        toastUndoName = nil
        restoreFavorite(name)
    }

    func scheduleChromeHide() {
        chromeTask?.cancel()
        chromeTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_400_000_000)
            guard let self, !Task.isCancelled else { return }
            if self.resolvedPresence().engaged && !self.isInteracting {
                // 计时结束时控制栏可能已经收起；再写一次 false 也会让整棵树失效。
                AssignIf.changed(from: self.chromeVisible, to: false) { self.chromeVisible = $0 }
            }
        }
    }

    /// 统一的播放门控：起播、重试、换档、恢复都经过 PlayerController 里的这一个开关。
    var playbackAllowed: Bool {
        PlaybackPolicy.allowed(
            windowVisible: pointVisible, manuallyPaused: manuallyPaused,
            bossHidden: bossHidden, systemInactive: systemInactive
        )
    }

    /// 把「窗口对用户不可见」同步给播放器，决定停播后要不要释放播放项。
    func syncPlayerHidden() {
        player.setHidden(PlaybackPolicy.windowInvisible(
            windowVisible: pointVisible, bossHidden: bossHidden, systemInactive: systemInactive, appHidden: platform.appHidden
        ))
    }

    func applyPower(windowVisible: Bool? = nil) {
        if let windowVisible { pointVisible = windowVisible }
        let allowed = playbackAllowed
        if awayTimer.gateChanged(allowed: allowed) {
            awayTask?.cancel()
            awayTask = nil
            playbackNote = AwayRetry.noteAfterCancel(playbackNote)
        }
        syncPlayerHidden()
        player.setAllowed(allowed)
        player.setDisplaySleepPrevented(resolvedPresence().engaged && allowed && player.hasItem)
        updateDwellActive()
        refreshQuality()
        // 闸门刚按当前的窗口、暂停、老板键、休眠重算过。轮询是否还要留，由这些状态决定，这里再判一次。
        refreshPointerPolling?()
    }


}
