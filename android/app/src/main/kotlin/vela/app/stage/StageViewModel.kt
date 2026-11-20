package vela.app.stage

import android.content.Context
import android.util.Log
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vela.app.R
import vela.app.infra.Settings
import vela.app.lane.PlaybackLane
import vela.app.lane.ThermalMonitor
import vela.core.base.io.DeferredSaver
import vela.core.base.lane.LaneCursor
import vela.core.base.lane.LaneEndReason
import vela.core.base.lane.LaneID
import vela.core.base.lane.PlaybackLaneRole
import vela.core.base.lane.RoomTarget
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Region
import vela.core.base.model.RoomQuery
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.HTTPClient
import vela.core.base.site.CatalogFailure
import vela.core.curation.Curation
import vela.core.curation.CurationStore
import vela.core.curation.FavoriteCache
import vela.core.curation.FavoriteRetryGate
import vela.core.curation.FavoritesBoard
import vela.core.curation.KnownCatalog
import vela.core.curation.LastBroadcastCache
import vela.core.curation.SupersedingRunner
import vela.core.feed.CatalogAPI
import vela.core.feed.FeedStore
import vela.core.feed.IdleStart
import vela.core.feed.LoadSlot
import vela.core.feed.PagingState
import vela.core.feed.SearchEpoch
import vela.core.feed.StartupGate
import vela.core.hls.DegradedHold
import vela.core.hls.LaneQuality
import vela.core.hls.PhoneThermalState
import vela.core.hls.PlaybackNoteRule
import vela.core.hls.PreparedCache
import vela.core.hls.SkipTracker
import vela.core.ledger.DwellTracker
import vela.core.lifecycle.AwayRetry
import vela.core.lifecycle.AwayRetryTimer
import vela.core.ledger.WatchLedger
import vela.core.ledger.WatchLedgerStore
import vela.core.lifecycle.ChallengeGate
import vela.core.lifecycle.ChallengeResume
import vela.core.lifecycle.LifecycleEpoch
import vela.core.lifecycle.PhoneLaneActivity
import vela.core.lifecycle.PhoneResumeIntent
import vela.core.recommend.RankSignals
import vela.core.selection.LaneSelection
import vela.core.taste.HotTagCache
import vela.core.taste.TagRarity
import vela.core.taste.Taste
import vela.core.taste.TasteStore
import vela.core.taste.suggestedTags
import vela.core.base.model.Room

/**
 * 舞台。目录、收藏、口味、列表，以及接上播放器之后的起播链、侧车和质询都在这里。
 *
 * 列表改动一律走 [updateFeed]：先改副本，再交给 [vela.core.selection.LaneSelection.resolve]。
 * 单窗不设副路。开了第二路之后焦点、热降档和停留都按路结算。
 */
class StageViewModel(
    val settings: Settings,
    http: HTTPClient,
    directory: Path,
    val clock: Clock = Clock.systemUTC(),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    context: Context? = null,
) : StageContract {
    internal val appContext: Context? = context
    private val directHttp: HTTPClient = http
    internal var sidecarHolder: SiteSidecar? = null
    internal val http: HTTPClient = SidecarAwareClient(
        direct = directHttp,
        sidecar = { sidecarHolder },
        active = { isSidecarWindow() },
    )
    internal val text = StageText(context)
    internal val backoff = EndpointBackoffBox(now = { clock.instant() })
    // 构造参数也叫 http。这里必须点属性，否则目录绕过改写，请求会打到真实站点。
    internal val catalogAPI = CatalogAPI(this.http, backoff)

    private val jobs = SupervisorJob()
    internal val scope = CoroutineScope(jobs + dispatcher)
    private val loadLock = Any()
    private val loadJobs = ArrayList<Job>()

    internal val tasteStore = TasteStore(TasteStore.defaultURL(directory))
    internal val curationStore = CurationStore(CurationStore.fileIn(directory))
    internal val watchLedgerStore = WatchLedgerStore(WatchLedgerStore.defaultURL(directory))
    internal val tasteSaver = DeferredSaver<Taste> { value ->
        if (!tasteStore.save(value, now())) Log.w(TAG, "taste save skipped")
    }
    internal val curationSaver = DeferredSaver<Curation> { value ->
        if (!curationStore.save(value)) Log.w(TAG, "curation save skipped")
    }
    internal val watchLedgerSaver = DeferredSaver<WatchLedger> { value ->
        if (!watchLedgerStore.save(value)) Log.w(TAG, "watch ledger save skipped")
    }

    internal var taste = Taste()
    internal var curation = Curation()
    internal var watchLedger = WatchLedger()
    internal var feed = FeedStore()
    internal var source: FeedSource = FeedSource.guess
    internal var loadedSource: FeedSource = FeedSource.guess
    internal var ageConfirmed: Boolean = false
    internal var loading: Boolean = false
    internal var catalogError: String? = null
    internal var searchText: String = ""
    internal var siteSearch: String? = null
    internal var tagText: String = ""
    internal var gender: Gender? = null
    internal var region: Region? = null
    internal var publicOnly: Boolean = true
    internal var muted: Boolean = true
    internal var volume: Double = Settings.DEFAULT_VOLUME
    internal var chromeVisible: Boolean = true
    internal var surface: StageSurface = StageSurface.None
        set(value) {
            val previous = field
            field = value
            if (value == StageSurface.Challenge) {
                sidecarHolder?.suspendPage()
            } else if (previous == StageSurface.Challenge) {
                sidecarHolder?.resumeIfNeeded()
            }
        }
    internal var hintVisible: Boolean = false
    internal var hintSeen: Boolean = false
    internal var toast: String? = null
    internal var toastUndoName: String? = null
    internal var favoriteSuggest: FavoriteSuggest? = null
    internal var favoritesChecking: Boolean = false
    /** 收藏查询批次。旧批次收尾时不能把新批次的「查询中」清掉。 */
    internal var favoriteCheckEpoch: Int = 0
    internal var favoriteDraftError: String? = null
    internal var infoAlwaysOn: Boolean = false
    internal var startupNotices: List<String> = emptyList()
    internal var challengeResume: ChallengeResume? = null
    internal var laneFace = LaneFace()
    /** 接上 [bindPlayback] 之后才向站点要地址。目录测试不接，请求数保持原样。 */
    internal var playbackBound: Boolean = false
    internal var playbackSink: PlaybackSink? = null
        set(value) {
            field = wrapPrimarySink(value)
        }
    internal var playJob: Job? = null
    internal var prefetchJob: Job? = null
    internal var skipJob: Job? = null
    internal var awayJob: Job? = null
    internal val skips = SkipTracker()
    internal val awayRetry = AwayRetry()
    internal val awayTimer = AwayRetryTimer()
    internal val prepared = PreparedCache()
    internal val degradedHold = DegradedHold()
    internal var sidecarUntil: Instant? = null
    internal var playerNote: String? = null
    internal var currentCap: Double = Double.POSITIVE_INFINITY
    internal var prefetchEnabled: Boolean = true
    internal var skipDelayMillis: Long = (SkipTracker.noticeSeconds * 1000.0).toLong()
    internal var awayDelayOverrideMillis: Long? = null
    internal var quitRequested: Boolean = false

    /** 导出字节只发一次。界面取走后就没有了，不留在状态里。 */
    internal val favoriteExports = Channel<ByteArray>(Channel.CONFLATED)

    /** 单窗为 null。开了第二路就始终有游标，即使这一路没有房间。 */
    internal var secondaryCursor: LaneCursor? = null
    internal var primaryCursor = LaneCursor()
    internal var focusLane: LaneID = LaneID.PRIMARY
    internal var laneCount: Int = 1
    internal var rememberedLaneCount: Int = 1
    internal var threeLanesFit: Boolean = true
    internal var portraitStageWidth: Double = 0.0
    internal var portraitStageHeight: Double = 0.0
    internal var landscape: Boolean = false
    internal var laneCountLocked: Boolean = false
    internal var focusLocked: Boolean = false
    internal var thermal: PhoneThermalState = PhoneThermalState.NOMINAL
    internal val laneQuality: LaneQuality = LaneQuality()
    internal var screenScale: Double = 1.0
    internal val displayHeights: HashMap<LaneID, Double> = HashMap()
    internal val appliedCap: HashMap<LaneID, Double> = HashMap()
    internal var secondaryFace: LaneFace? = null
    internal var tertiaryFace: LaneFace? = null
    internal val secondaryDwell: DwellTracker = DwellTracker()
    internal var secondarySource: FeedSource = FeedSource.guess
    internal val primaryFocusLog: FocusSegments = FocusSegments()
    internal val secondaryFocusLog: FocusSegments = FocusSegments()
    internal var settlementRole: PlaybackLaneRole = PlaybackLaneRole.PRIMARY
    internal var settlementFocused: Boolean = true
    internal var settlementSourceOverride: FeedSource? = null
    internal var secondarySink: PlaybackSink? = null
    internal var secondaryPlayJob: Job? = null
    internal var secondaryPlayRequests: Int = 0
    internal var secondaryActivity: PhoneLaneActivity = PhoneLaneActivity.IDLE
    internal var deferSecondaryAutoplay: Boolean = false
    internal var primaryLane: PlaybackLane? = null
    internal var secondaryLane: PlaybackLane? = null
    internal var ensureSecondary: (() -> Unit)? = null
    internal var onLaneFocusChanged: ((LaneID) -> Unit)? = null
    internal var onLanesChanged: (() -> Unit)? = null
    internal var thermalMonitor: ThermalMonitor? = null
    internal var thermalJob: Job? = null

    internal val startupGate = StartupGate()
    internal var loadGeneration: Int = 0
    internal val paging = PagingState()
    internal var loadedOffset: Int
        get() = paging.offset
        set(value) {
            paging.commit(value)
        }
    internal val idleStart = IdleStart()
    internal val loadSlot = LoadSlot()
    internal val searchEpoch = SearchEpoch()
    internal val challengeGate = ChallengeGate()
    internal val dwell = DwellTracker()
    internal var dwellSource: FeedSource = FeedSource.guess
    internal var moveUserInitiated: Boolean = false
    internal val reasons = LinkedHashMap<String, String>()
    internal var regularCandidates: List<Room> = emptyList()
    internal var tagRarity = TagRarity(emptyList())
    internal var knownCatalog = KnownCatalog()
    internal var favoriteStatus = FavoriteCache()
    internal var favoriteRetry = FavoriteRetryGate()
    internal var lastBroadcasts = LastBroadcastCache()
    internal var lastBroadcastChecking = false
    internal var lastBroadcastRetry = FavoriteRetryGate()
    internal var hotTags = HotTagCache()
    internal var hotTagsLoading = false
    internal val favoriteRunner = SupersedingRunner<FavoriteResolution>()
    internal val suggestSnoozed = HashSet<String>()
    internal var failureNote: String? = null

    /** 进后台时关掉。回前台的 [vela.core.lifecycle.PhoneLifecycleAction.EpochResume] 再打开。 */
    internal var playbackGateOpen: Boolean = true

    /** 只有前台 ACTIVE 才计停留。播放通知不能在 INACTIVE 把计时重新打开。 */
    internal var sceneAttentive: Boolean = true

    /** 单窗恒为真。隐藏的路回前台保持已释放。 */
    internal var primaryVisible: Boolean = true

    /** 进后台快照用。回前台满足起播条件时原样交给重新解析。 */
    internal var primaryActivity: PhoneLaneActivity = PhoneLaneActivity.IDLE

    internal val lifecycleEpoch: LifecycleEpoch = LifecycleEpoch()

    /** 播放器还没接上时，第一次真正起播前回来创建。目录协程挂起之后才赋值也来得及。 */
    internal var ensurePlayback: (() -> Unit)? = null

    /** 非空时静音变化交给音频焦点，不再自己写设置。 */
    internal var onMuteChanged: ((Boolean) -> Unit)? = null

    /** 年龄确认页退出。正式入口用来结束 Activity。 */
    internal var onQuit: (() -> Unit)? = null

    /** 播放器已经 release，[close] 不能再 stop。 */
    internal var playerReleased: Boolean = false

    internal var pendingSearchKeyword: String? = null
    internal var notedResume: PhoneResumeIntent? = null
    internal var lastSettlementUsername: String? = null
    internal var flushCount: Int = 0

    internal var catalogRestJob: Job? = null
    internal var targetedJob: Job? = null
    internal var siteSearchJob: Job? = null
    private var toastJob: Job? = null
    private var chromeJob: Job? = null
    internal var favoriteSuggestJob: Job? = null
    internal var favoriteSuggestWakeJob: Job? = null
    internal var relatedJob: Job? = null

    private val zone: ZoneId = clock.zone
    private val mutable = MutableStateFlow(StageUiState())
    override val state: StateFlow<StageUiState> = mutable.asStateFlow()

    init {
        val notices = ArrayList<String>()
        taste = tasteStore.load(now())
        if (tasteStore.isReadOnly) notices.add(text.readOnly("口味"))
        curation = curationStore.load(now())
        if (curationStore.isReadOnly) notices.add(text.readOnly("收藏与屏蔽"))
        watchLedger = watchLedgerStore.load(now())
        if (watchLedgerStore.isReadOnly) notices.add(text.readOnly("观看记录"))
        startupNotices = notices
        loadSettings()
        publish()
        kickoffCatalogIfConfirmed()
    }

    fun bootstrap() {
        if (!startupGate.enterBootstrap()) return
        if (!ageConfirmed) return
        hintVisible = !settings.hintSeen
        announceStartupNotices()
        if (!startupGate.claimCatalog(true)) {
            publish()
            return
        }
        track { reload() }
    }

    fun close() {
        playbackGateOpen = false
        stopThermalMonitor()
        cancelPlaybackWork()
        cancelSharedWork()
        if (!playerReleased) {
            playbackSink?.stop()
            secondarySink?.stop()
        }
        sidecarHolder?.close()
        sidecarHolder = null
        favoriteRunner.close()
        favoriteExports.close()
        scope.cancel()
        tasteSaver.flush()
        curationSaver.flush()
        watchLedgerSaver.flush()
    }

    /** 接上外部播放器。正式路径走 [bindLane]；测试用记录实现，不必起 ExoPlayer。 */
    fun attachPlayback(sink: PlaybackSink) {
        bindPlayback(sink, page = null)
        playerReleased = false
    }

    /** 接上侧车页面。进后台时拆掉。 */
    fun attachSidecar(page: SidecarPage) {
        installSidecar(page)
    }

    /**
     * 在构造返回、主线程协程恢复之前调用。
     * 这样第一次 [requestPlay] 能创建播放器，静音能交给音频焦点。
     */
    fun bindLifecycleHooks(ensurePlayback: () -> Unit, onMuteChanged: (Boolean) -> Unit) {
        this.ensurePlayback = ensurePlayback
        this.onMuteChanged = onMuteChanged
        startThermalMonitor()
    }

    override fun dispatch(intent: StageIntent) {
        if (!ageConfirmed && intent !is StageIntent.ConfirmAge && intent !is StageIntent.Quit) return
        when (intent) {
            StageIntent.ConfirmAge -> confirmAge()
            StageIntent.Quit -> {
                quitRequested = true
                publish()
                onQuit?.invoke()
            }
            is StageIntent.SelectSource -> selectSource(intent.source)
            is StageIntent.SetSearchText -> {
                searchText = intent.text
                previewSearch()
            }
            StageIntent.SubmitSearch -> submitSearch()
            is StageIntent.ChooseGender -> chooseGender(intent.gender)
            is StageIntent.ChooseRegion -> chooseRegion(intent.region)
            is StageIntent.SetPublicOnly -> setPublicOnly(intent.value)
            is StageIntent.SetTagText -> {
                tagText = intent.text
                publish()
            }
            StageIntent.ApplyTag -> {
                settings.tag = tagText
                track { reload() }
            }
            StageIntent.ToggleMute -> toggleMute()
            is StageIntent.SetVolume -> setVolume(intent.value)
            StageIntent.ToggleInfo -> {
                infoAlwaysOn = !infoAlwaysOn
                settings.infoAlwaysOn = infoAlwaysOn
                publish()
            }
            is StageIntent.Step -> when {
                intent.lane == LaneID.PRIMARY -> {
                    if (intent.delta != 0) skips.noteManual(intent.delta)
                    move(intent.delta, userInitiated = true)
                }
                laneCount > 1 && intent.delta != 0 -> moveLane(intent.lane, intent.delta, userInitiated = true)
                else -> Unit
            }
            is StageIntent.TogglePause -> togglePause(intent.target)
            is StageIntent.ToggleFavorite -> toggleFavorite(intent.target)
            is StageIntent.BlockCurrent -> blockCurrent(intent.target)
            is StageIntent.MarkNotInterested -> markNotInterested(intent.target)
            is StageIntent.RetryPlayback -> {
                val degradedSecondary = intent.target?.laneID == LaneID.SECONDARY &&
                    secondaryFace?.playback == LanePlayback.Degraded
                if (accepts(intent.target) && !degradedSecondary) {
                    degradedHold.release(DegradedHold.Release.RETRY)
                    skips.reset()
                    feed.currentUsername?.let { prepared.remove(it) }
                }
                retryPlayback(intent.target)
            }
            is StageIntent.RemoveFavorite -> commitCuration(curation.unfavoriting(intent.name), null)
            is StageIntent.RestoreFavorite -> commitCuration(curation.restoringFavorite(intent.name), null)
            is StageIntent.Unblock -> commitCuration(curation.unblocking(intent.name), null)
            is StageIntent.AddFavorite -> addFavorite(intent.name)
            is StageIntent.PlayFavorite -> playFavorite(intent.name)
            StageIntent.RefreshFavorites -> track { refreshFavorites() }
            StageIntent.ExportFavorites -> exportFavorites()
            is StageIntent.ImportFavorites -> importFavorites(intent.bytes)
            StageIntent.Retry -> track { retry() }
            StageIntent.RetryAfterChallenge -> {
                skips.reset()
                retryAfterChallenge()
            }
            StageIntent.DismissChallenge -> {
                if (surface == StageSurface.Challenge) {
                    surface = StageSurface.None
                    publish()
                }
            }
            StageIntent.DismissHint -> {
                hintVisible = false
                hintSeen = true
                settings.hintSeen = true
                publish()
            }
            StageIntent.UndoToast -> undoToast()
            StageIntent.DismissToast -> {
                toastJob?.cancel()
                toast = null
                toastUndoName = null
                publish()
            }
            StageIntent.AcceptFavoriteSuggest -> acceptFavoriteSuggest()
            StageIntent.CloseFavoriteSuggest -> closeFavoriteSuggest()
            is StageIntent.ShowSurface -> showSurface(intent.surface)
            StageIntent.DismissSurfaces -> {
                if (surface != StageSurface.Challenge) {
                    surface = StageSurface.None
                    publish()
                }
            }
            StageIntent.NoteActivity -> noteActivity()
            StageIntent.ChromeIdle -> {
                if (surface == StageSurface.None) {
                    chromeVisible = false
                    publish()
                }
            }
            is StageIntent.BoostTaste -> boostTaste(intent.key)
            is StageIntent.VetoTaste -> vetoTaste(intent.key)
            is StageIntent.RestoreTaste -> restoreTaste(intent.key)
            is StageIntent.RemoveTaste -> removeTaste(intent.key)
            is StageIntent.SetLearningPaused -> setLearningPaused(intent.paused)
            StageIntent.ResetTaste -> resetTasteProfile()
            StageIntent.ClearPreferences -> clearAllPreferences()
            is StageIntent.SetDual -> setDual(intent.on, intent.remember)
            is StageIntent.SetLaneCount -> setLaneCount(intent.count, intent.remember)
            is StageIntent.SetFocus -> setFocus(intent.lane)
            is StageIntent.SetLandscape -> setLandscape(intent.on)
            is StageIntent.NotePortraitStageSize -> notePortraitStageSize(intent.width, intent.height)
        }
    }

    override fun noteReady(username: String?) {
        laneFace = laneFace.copy(readyUsername = username)
        publish()
    }

    override fun notePlaying(username: String?) {
        val current = feed.currentUsername
        val same = username != null && current != null && username.equals(current, ignoreCase = true)
        if (same) primaryActivity = PhoneLaneActivity.PLAYING
        val playback = when {
            same -> LanePlayback.Playing
            laneFace.playback == LanePlayback.Failed -> LanePlayback.Failed
            feed.current == null -> LanePlayback.Empty
            else -> LanePlayback.Idle
        }
        laneFace = laneFace.copy(
            playingUsername = username,
            playback = playback,
            playbackNote = if (same) null else laneFace.playbackNote,
        )
        if (same) {
            failureNote = null
            playerNote = null
            dwell.clearFailure()
        }
        updateDwellActive()
        publish()
    }

    override fun noteFailed(failed: Boolean, note: String) {
        if (!failed) {
            val clear = PlaybackNoteRule.clearOnRecovery(laneFace.playbackNote, playerNote)
            playerNote = null
            failureNote = null
            val playback = when (laneFace.playback) {
                LanePlayback.Failed ->
                    if (laneFace.playingUsername != null) LanePlayback.Playing else LanePlayback.Idle
                else -> laneFace.playback
            }
            laneFace = laneFace.copy(
                playback = playback,
                playbackNote = if (clear) null else laneFace.playbackNote,
            )
            dwell.clearFailure()
            publish()
            return
        }
        dwell.markFailed()
        failureNote = note
        playerNote = note
        chromeVisible = true
        laneFace = laneFace.copy(playback = LanePlayback.Failed, playbackNote = note)
        updateDwellActive()
        publish()
    }

    internal fun now(): Instant = clock.instant()

    internal fun currentQuery(): RoomQuery = RoomQuery(
        text = if (siteSearch == null) searchText else "",
        publicOnly = publicOnly,
        blocked = curation.blockedSet,
        only = allowedNames(curation),
        hidden = if (source == FeedSource.guess) taste.vetoedKeys else emptySet(),
    )

    internal fun allowedNames(value: Curation): Set<String>? =
        if (source == FeedSource.favorites && siteSearch == null) value.favoriteSet else null

    internal fun rankSignals(): RankSignals = RankSignals(
        rarity = tagRarity,
        ledger = watchLedger,
        blocked = curation.blockedSet,
    )

    internal fun target(): RoomTarget? = target(LaneID.PRIMARY)

    internal fun target(id: LaneID): RoomTarget? {
        if (id != LaneID.PRIMARY && laneCount <= 1) return null
        val face = when (id) {
            LaneID.PRIMARY -> laneFace
            LaneID.SECONDARY -> if (laneCount >= 2) secondaryFace else null
            LaneID.TERTIARY -> if (laneCount >= 3) tertiaryFace else null
        } ?: return null
        val name = if (id == LaneID.PRIMARY) feed.currentUsername else face.username
        if (name == null) return null
        return if (id == LaneID.PRIMARY) {
            RoomTarget.primary(name, laneFace.generation)
        } else {
            RoomTarget(id, name, face.generation)
        }
    }

    internal fun accepts(target: RoomTarget?): Boolean {
        if (target == null) return false
        if (laneCount <= 1 || target.laneID == LaneID.PRIMARY) {
            if (target.laneID != LaneID.PRIMARY) return false
            val name = feed.currentUsername ?: return false
            return target.matches(LaneID.PRIMARY, name, laneFace.generation)
        }
        if (target.laneID !in LaneID.active(laneCount)) return false
        val face = when (target.laneID) {
            LaneID.SECONDARY -> secondaryFace
            LaneID.TERTIARY -> tertiaryFace
            LaneID.PRIMARY -> laneFace
        } ?: return false
        val name = face.username ?: return false
        return target.matches(target.laneID, name, face.generation)
    }

    internal fun track(block: suspend () -> Unit): Job {
        val job = scope.launch { block() }
        synchronized(loadLock) { loadJobs.add(job) }
        job.invokeOnCompletion { synchronized(loadLock) { loadJobs.remove(job) } }
        return job
    }

    internal suspend fun awaitLoads() {
        while (true) {
            val pending = synchronized(loadLock) { loadJobs.filter { it.isActive } }
            if (pending.isEmpty()) return
            pending.forEach { it.join() }
        }
    }

    internal fun publish() {
        if (laneCount >= 2 && secondaryCursor != null) {
            val secondaryName = secondaryCursor?.username
            val face = secondaryFace ?: LaneFace(
                id = LaneID.SECONDARY,
                playback = if (secondaryName == null) LanePlayback.Empty else LanePlayback.Idle,
            )
            val playback = when {
                secondaryName == null -> LanePlayback.Empty
                face.playback == LanePlayback.Empty -> LanePlayback.Idle
                else -> face.playback
            }
            secondaryFace = face.copy(id = LaneID.SECONDARY, username = secondaryName, playback = playback)
        }
        val name = feed.currentUsername
        val playback = when {
            name == null -> LanePlayback.Empty
            laneFace.playback == LanePlayback.Empty -> LanePlayback.Idle
            else -> laneFace.playback
        }
        laneFace = laneFace.copy(id = LaneID.PRIMARY, username = name, playback = playback)
        val summary = taste.summary(now(), zone)
        mutable.value = StageUiState(
            ageConfirmed = ageConfirmed,
            loading = loading,
            catalogError = catalogError,
            source = source,
            loadedSource = loadedSource,
            catalog = feed.catalog.toList(),
            rooms = feed.rooms.toList(),
            currentUsername = name,
            retainedUsername = feed.retained,
            searchText = searchText,
            siteSearch = siteSearch,
            tagText = tagText,
            gender = gender,
            region = region,
            publicOnly = publicOnly,
            hiddenKeys = if (source == FeedSource.guess) taste.vetoedKeys else emptySet(),
            muted = muted,
            volume = volume,
            chromeVisible = chromeVisible,
            surface = surface,
            hintVisible = hintVisible,
            hintSeen = hintSeen,
            toast = toast,
            toastUndoName = toastUndoName,
            favoriteSuggest = favoriteSuggest,
            curation = curation,
            favoritesBoard = favoritesBoard(),
            favoritesChecking = favoritesChecking,
            favoriteDraftError = favoriteDraftError,
            taste = TastePanelState(
                liked = summary.liked.map { it.toRow() },
                disliked = summary.disliked.map { it.toRow() },
                genders = summary.genders.map { it.toRow() },
                tagCount = summary.tagCount,
                week = summary.week.map { DayBarState(it.weekday, it.hit, it.miss) },
                hitRate = summary.hitRate,
                suggestions = taste.suggestedTags(hotTags.fresh(now()), feed.catalog),
                paused = taste.paused,
            ),
            lane = laneFace,
            infoAlwaysOn = infoAlwaysOn,
            reasons = reasons.toMap(),
            startupNotices = startupNotices,
            catalogClaimed = startupGate.catalogRequested,
            challengeResume = challengeResume,
            secondary = if (laneCount >= 2) secondaryFace else null,
            tertiary = if (laneCount >= 3) tertiaryFace else null,
            focusLane = focusLane,
            laneCount = laneCount,
            rememberedLaneCount = rememberedLaneCount,
            threeLanesFit = threeLanesFit,
            portraitStageWidth = portraitStageWidth,
            portraitStageHeight = portraitStageHeight,
            landscape = landscape,
            laneCountLocked = laneCountLocked,
            focusLocked = focusLocked,
            thermal = thermal,
        )
    }

    /** 没接播放器时只推进界面。接上之后才解析地址并交给这一路。闸门关着时直接丢掉。 */
    internal fun requestPlay(isRetry: Boolean = false) {
        if (!playbackGateOpen) return
        ensurePlayback?.invoke()
        if (!playbackBound) {
            startUnboundPlayback()
            return
        }
        if (!ageConfirmed || laneFace.manuallyPaused) {
            playJob?.cancel()
            if (laneFace.manuallyPaused) playbackSink?.setAllowed(false)
            publish()
            return
        }
        val playing = laneFace.playingUsername
        val current = feed.currentUsername
        primaryActivity = when {
            playing == null -> PhoneLaneActivity.FIRST_RESOLVE
            current == null || !current.equals(playing, ignoreCase = true) -> PhoneLaneActivity.ROOM_SWITCH
            else -> PhoneLaneActivity.REFRESHING
        }
        startBoundPlayback(isRetry)
    }

    internal fun cancelPlaybackWork() {
        playJob?.cancel()
        playJob = null
        secondaryPlayJob?.cancel()
        secondaryPlayJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        skipJob?.cancel()
        skipJob = null
        awayJob?.cancel()
        awayJob = null
    }

    /** 目录、搜索、收藏、关联。播放任务也一起停。代次作废后调用。 */
    internal fun cancelSharedWork() {
        cancelPlaybackWork()
        catalogRestJob?.cancel()
        catalogRestJob = null
        targetedJob?.cancel()
        targetedJob = null
        siteSearchJob?.cancel()
        siteSearchJob = null
        favoriteSuggestJob?.cancel()
        favoriteSuggestJob = null
        favoriteSuggestWakeJob?.cancel()
        favoriteSuggestWakeJob = null
        relatedJob?.cancel()
        relatedJob = null
        favoriteRunner.cancel()
        cancelTrackedLoads()
        if (loading) {
            loading = false
            publish()
        }
    }

    internal fun cancelTrackedLoads() {
        val pending = synchronized(loadLock) { loadJobs.toList() }
        pending.forEach { it.cancel() }
    }

    /** 后台前正在做的共享工作。搜索优先于续页。 */
    internal fun captureResumeIntent(): PhoneResumeIntent? {
        if (siteSearchJob?.isActive == true) {
            val keyword = siteSearch ?: searchText.stageTrim()
            pendingSearchKeyword = keyword.ifEmpty { null }
            return PhoneResumeIntent.SEARCH
        }
        if (loading || catalogRestJob?.isActive == true || targetedJob?.isActive == true) {
            return PhoneResumeIntent.RELOAD
        }
        return null
    }

    internal fun invalidateGenerations() {
        lifecycleEpoch.suspend()
        loadGeneration += 1
        laneFace = laneFace.copy(generation = laneFace.generation + 1)
        val secondary = secondaryFace
        if (secondary != null) secondaryFace = secondary.copy(generation = secondary.generation + 1)
        publish()
    }

    internal fun resumeGenerations() {
        lifecycleEpoch.resume()
        playbackGateOpen = true
    }

    internal fun applyMute(nextMuted: Boolean, writeSettings: Boolean) {
        muted = nextMuted
        if (writeSettings) settings.muted = nextMuted
        pushVolumes()
        publish()
    }

    internal fun showNotice(message: String) {
        showToast(message)
    }

    internal fun cancelToastTimer() {
        toastJob?.cancel()
    }

    internal fun showToast(message: String, seconds: Double = StageRules.TOAST_SECONDS, undoFavorite: String? = null) {
        endFavoriteSuggest(vela.core.ledger.SuggestEnd.withdrawn)
        toast = message
        toastUndoName = undoFavorite?.takeIf { curation.canRestoreFavorite(it) }
        favoriteSuggest = null
        toastJob?.cancel()
        toastJob = scope.launch {
            delay((seconds * 1000.0).toLong())
            toast = null
            toastUndoName = null
            publish()
        }
        publish()
    }

    private fun loadSettings() {
        ageConfirmed = settings.ageConfirmed
        publicOnly = settings.publicOnly
        infoAlwaysOn = settings.infoAlwaysOn
        hintSeen = settings.hintSeen
        volume = settings.volume
        muted = settings.muted
        gender = settings.gender
        region = settings.region
        tagText = settings.tag
        source = settings.source
        loadedSource = source
        dwellSource = source
        secondarySource = source
        feed = FeedStore(
            query = RoomQuery(
                publicOnly = publicOnly,
                blocked = curation.blockedSet,
                hidden = if (source == FeedSource.guess) taste.vetoedKeys else emptySet(),
            ),
        )
        rememberDualFromSettings()
    }

    private fun kickoffCatalogIfConfirmed() {
        if (!startupGate.claimCatalog(ageConfirmed)) return
        track { reload() }
    }

    private fun confirmAge() {
        if (ageConfirmed) return
        ageConfirmed = true
        settings.ageConfirmed = true
        hintVisible = !settings.hintSeen
        announceStartupNotices()
        if (!startupGate.claimCatalog(true)) {
            publish()
            return
        }
        track { reload() }
    }

    private fun announceStartupNotices() {
        if (startupNotices.isEmpty()) return
        showToast(startupNotices.joinToString("；"), seconds = 8.0)
        startupNotices = emptyList()
    }

    private fun toggleMute() {
        val next = !muted
        if (!next && volume <= 0.0) {
            volume = StageRules.RESTORE_VOLUME
            settings.volume = volume
        }
        val bridge = onMuteChanged
        if (bridge != null) {
            bridge.invoke(next)
            return
        }
        muted = next
        settings.muted = next
        publish()
    }

    private fun setVolume(value: Double) {
        volume = value.coerceIn(0.0, 1.0)
        settings.volume = volume
        val nextMuted = volume == 0.0
        val bridge = onMuteChanged
        if (bridge != null && nextMuted != muted) {
            bridge.invoke(nextMuted)
            return
        }
        muted = nextMuted
        settings.muted = muted
        if (bridge != null) playbackSink?.setVolume(volume, muted)
        publish()
    }

    private fun togglePause(target: RoomTarget?) {
        if (target != null && !accepts(target)) return
        if (target?.laneID == LaneID.SECONDARY && laneCount > 1) {
            togglePauseLane(target)
            return
        }
        val paused = !laneFace.manuallyPaused
        laneFace = laneFace.copy(manuallyPaused = paused)
        val allowed = !paused && ageConfirmed && feed.current != null && surface != StageSurface.Challenge
        if (awayTimer.gateChanged(allowed)) {
            awayJob?.cancel()
            val replaced = AwayRetry.noteAfterCancel(laneFace.playbackNote)
            if (replaced != laneFace.playbackNote) {
                laneFace = laneFace.copy(playbackNote = replaced)
            }
        }
        updateDwellActive()
        publish()
        if (paused) {
            playJob?.cancel()
            prefetchJob?.cancel()
            prefetchJob = null
            playbackSink?.setAllowed(false)
        } else if (playbackBound) {
            requestPlay()
        }
    }

    private fun noteActivity() {
        if (!chromeVisible) chromeVisible = true
        publish()
        chromeJob?.cancel()
        chromeJob = scope.launch {
            delay((StageRules.CHROME_HIDE_SECONDS * 1000.0).toLong())
            if (surface == StageSurface.None) {
                chromeVisible = false
                publish()
            }
        }
    }

    internal fun showSurface(next: StageSurface) {
        if (next == StageSurface.None) {
            if (surface != StageSurface.Challenge) surface = StageSurface.None
            publish()
            return
        }
        surface = next
        chromeVisible = true
        publish()
        if (next == StageSurface.Taste || next == StageSurface.Filters) track { loadHotTagsIfStale() }
        if (next == StageSurface.Curation) {
            track { refreshFavoritesIfStale() }
            track { refreshLastBroadcastsIfStale() }
        }
    }

    private fun favoritesBoard(): FavoritesBoard {
        val statuses = HashMap<String, vela.core.base.site.FavoriteStatus>()
        for (name in curation.favorites) {
            favoriteStatus.status(name)?.let { statuses[name] = it }
        }
        return FavoritesBoard.make(curation.favorites, statuses, feed.catalog)
    }

    private fun vela.core.taste.TasteRow.toRow(): TasteRowState =
        TasteRowState(key = key, name = name, mean = mean, hits = hits, vetoed = vetoed)

    internal fun updateFeed(
        installing: FeedSource? = null,
        intent: LaneSelection.Intent = LaneSelection.Intent.Filter,
        body: (FeedStore) -> Unit,
    ) = updateFeedLanes(installing, intent, body)

    internal fun recordSettlement(
        settled: DwellTracker.Settled,
        endReason: LaneEndReason,
        userInitiated: Boolean,
    ) = recordSettlementLanes(settled, endReason, userInitiated)

    internal fun updateDwellActive() = updateDwellActiveLanes()

    internal fun scheduleRelated() = scheduleRelatedLanes()

    internal fun blockCurrent(target: RoomTarget?) = blockCurrentLanes(target)

    internal fun toggleFavorite(target: RoomTarget?) = toggleFavoriteLanes(target)

    internal fun markNotInterested(target: RoomTarget?) = markNotInterestedLanes(target)

    internal fun retryPlayback(target: RoomTarget?) = retryPlaybackLanes(target)

    fun bindLane(lane: PlaybackLane, webHost: android.widget.FrameLayout? = null) {
        bindLaneRouted(lane, webHost)
    }

    internal fun requestPlay(lane: LaneID, isRetry: Boolean = false) = requestLane(lane, isRetry)

    companion object {
        private const val TAG = "vela.stage"
    }
}

internal fun String.stageLower(): String = lowercase(java.util.Locale.ROOT)

internal fun String.stageTrim(): String = trim { it.isWhitespace() || Character.isSpaceChar(it) }

/** 文案与 `strings_stage.xml` 同一套。没有 Context 时用同样的字面量。 */
internal class StageText(context: Context?) {
    private val resources = context?.resources

    fun source(source: FeedSource): String = when (source) {
        FeedSource.guess -> get(R.string.stage_source_guess, "猜你喜欢")
        FeedSource.popular -> get(R.string.stage_source_popular, "热门")
        FeedSource.fresh -> get(R.string.stage_source_fresh, "新开播")
        FeedSource.favorites -> get(R.string.stage_source_favorites, "收藏")
    }

    fun unfavorite(name: String): String = get(R.string.stage_toast_unfavorite, "已取消收藏 %1\$s", name)

    fun blocked(name: String): String = get(R.string.stage_toast_blocked, "已屏蔽 %1\$s，可在收藏与屏蔽列表里取消", name)

    fun inBlocklist(name: String): String = get(R.string.stage_toast_in_blocklist, "%1\$s 在屏蔽列表里", name)

    fun moreFailed(): String = get(R.string.stage_toast_more_failed, "没能加载更多房间")

    fun challengeLater(): String = get(R.string.stage_toast_challenge_later, "站点连续要求确认，稍后再试")

    fun challengeSearch(): String = get(R.string.stage_toast_challenge_search, "站点连续要求确认，全站搜索稍后再试")

    fun challengeFavorites(): String = get(R.string.stage_toast_challenge_favorites, "站点连续要求确认，收藏状态稍后再查")

    fun learningOff(): String = get(R.string.stage_toast_learning_off, "已暂停学习，排序仍按现有口味")

    fun learningOn(): String = get(R.string.stage_toast_learning_on, "已恢复学习")

    fun tasteReset(): String = get(R.string.stage_toast_taste_reset, "口味画像已重置")

    fun cleared(): String = get(R.string.stage_toast_cleared, "已清除口味与观看记录，收藏与屏蔽保留")

    fun skip(): String = get(R.string.stage_toast_skip, "已记下，会少推荐类似的")

    fun skipPaused(): String = get(R.string.stage_toast_skip_paused, "已跳过，学习已暂停所以没有记口味")

    fun fileLarge(): String = get(R.string.stage_toast_file_large, "文件太大，不是收藏夹导出文件")

    fun fileBad(): String = get(R.string.stage_toast_file_bad, "无法识别这个文件")

    fun favoriteInvalid(): String = get(R.string.stage_favorite_invalid, "用户名只含字母、数字、下划线，2 到 30 位")

    fun favoriteExists(): String = get(R.string.stage_favorite_exists, "已经在收藏里了")

    fun needsChallenge(): String = get(R.string.stage_catalog_needs_challenge, "目录需要先通过站点确认")

    fun needsConfirm(): String = get(R.string.playchain_needs_confirm, "需要确认访问后才能播放")

    fun challengeSkip(): String = get(R.string.playchain_challenge_skip, "站点连续要求确认，这一间先跳过")

    fun connectFailed(): String = get(R.string.playchain_connect_failed, "连不上这路画面")

    fun relayDown(): String = get(R.string.playchain_relay_down, "本地中继没能启动，暂时无法播放")

    fun failure(failure: vela.core.stream.StreamFailure?): String = when (failure) {
        vela.core.stream.StreamFailure.geo -> get(R.string.playchain_failure_geo, "当前地区不可观看")
        vela.core.stream.StreamFailure.privateShow -> get(R.string.playchain_failure_private, "私密表演")
        vela.core.stream.StreamFailure.group -> get(R.string.playchain_failure_group, "群组表演")
        vela.core.stream.StreamFailure.away -> get(R.string.playchain_failure_away, "暂时离开")
        vela.core.stream.StreamFailure.offline -> get(R.string.playchain_failure_offline, "已离线")
        vela.core.stream.StreamFailure.hidden -> get(R.string.playchain_failure_hidden, "隐藏中")
        vela.core.stream.StreamFailure.password -> get(R.string.playchain_failure_password, "需要房间密码")
        vela.core.stream.StreamFailure.unavailable, null -> get(R.string.playchain_failure_unavailable, "暂时无法播放")
    }

    fun imported(added: Int, skipped: String): String = get(R.string.stage_import, "已导入 %1\$d 个收藏%2\$s", added, skipped)

    fun importSkipped(rejected: Int): String = get(R.string.stage_import_skipped, "，忽略 %1\$d 条格式不对的", rejected)

    fun rollback(message: String, source: FeedSource): String =
        get(R.string.stage_rollback, "%1\$s，仍停在%2\$s", message, source(source))

    fun challengeTitle(): String = get(R.string.playchain_challenge_title, "确认访问")

    fun challengeBody(): String = get(R.string.playchain_challenge_body, "站点要求确认这次访问。完成后回到播放器即可，画面不会在这个窗口里播放。")

    fun challengeDone(): String = get(R.string.playchain_challenge_done, "已完成，继续播放")

    fun challengeClose(): String = get(R.string.playchain_challenge_close, "关闭")

    fun readOnly(label: String): String = "$label 文件无法读取，已停用保存"

    private fun get(id: Int, fallback: String, vararg args: Any): String {
        val resolved = resources?.let { res ->
            if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
        }
        if (resolved != null) return resolved
        if (args.isEmpty()) return fallback
        return fallback.format(*args)
    }
}
