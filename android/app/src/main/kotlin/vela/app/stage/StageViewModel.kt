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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vela.app.R
import vela.app.infra.Settings
import vela.core.base.io.DeferredSaver
import vela.core.base.lane.LaneCursor
import vela.core.base.lane.LaneID
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
import vela.core.ledger.DwellTracker
import vela.core.ledger.WatchLedger
import vela.core.ledger.WatchLedgerStore
import vela.core.lifecycle.ChallengeGate
import vela.core.lifecycle.ChallengeResume
import vela.core.recommend.RankSignals
import vela.core.taste.HotTagCache
import vela.core.taste.TagRarity
import vela.core.taste.Taste
import vela.core.taste.TasteStore
import vela.core.taste.suggestedTags
import vela.core.base.model.Room

/**
 * 单窗舞台。目录、收藏、口味和列表落在这里；起播链、侧车和质询页由 A5b 接上。
 *
 * 列表改动一律走 [updateFeed]：先改副本，再交给 [vela.core.selection.LaneSelection.resolve]。
 * 单窗不设副路。房间换了用 [vela.core.selection.LaneSettlement] 结算停留。
 */
class StageViewModel(
    val settings: Settings,
    http: HTTPClient,
    directory: Path,
    val clock: Clock = Clock.systemUTC(),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    context: Context? = null,
) : StageContract {
    internal val http: HTTPClient = http
    internal val text = StageText(context)
    internal val backoff = EndpointBackoffBox(now = { clock.instant() })
    internal val catalogAPI = CatalogAPI(http, backoff)

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
    internal var hintVisible: Boolean = false
    internal var hintSeen: Boolean = false
    internal var toast: String? = null
    internal var toastUndoName: String? = null
    internal var favoriteSuggest: FavoriteSuggest? = null
    internal var favoritesChecking: Boolean = false
    internal var favoriteDraftError: String? = null
    internal var infoAlwaysOn: Boolean = false
    internal var startupNotices: List<String> = emptyList()
    internal var challengeResume: ChallengeResume? = null
    internal var laneFace = LaneFace()
    internal var quitRequested: Boolean = false
    internal var exportedFavorites: ByteArray? = null

    /** 单窗恒为 null。双窗再装上副路游标。 */
    internal var secondaryCursor: LaneCursor? = null
    internal var primaryCursor = LaneCursor()

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
        scope.cancel()
        tasteSaver.flush()
        curationSaver.flush()
        watchLedgerSaver.flush()
    }

    override fun dispatch(intent: StageIntent) {
        if (!ageConfirmed && intent !is StageIntent.ConfirmAge && intent !is StageIntent.Quit) return
        when (intent) {
            StageIntent.ConfirmAge -> confirmAge()
            StageIntent.Quit -> {
                quitRequested = true
                publish()
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
            is StageIntent.Step -> if (intent.lane == LaneID.PRIMARY) move(intent.delta, userInitiated = true)
            is StageIntent.TogglePause -> togglePause(intent.target)
            is StageIntent.ToggleFavorite -> toggleFavorite(intent.target)
            is StageIntent.BlockCurrent -> blockCurrent(intent.target)
            is StageIntent.MarkNotInterested -> markNotInterested(intent.target)
            is StageIntent.RetryPlayback -> retryPlayback(intent.target)
            is StageIntent.RemoveFavorite -> commitCuration(curation.unfavoriting(intent.name), null)
            is StageIntent.RestoreFavorite -> commitCuration(curation.restoringFavorite(intent.name), null)
            is StageIntent.Unblock -> commitCuration(curation.unblocking(intent.name), null)
            is StageIntent.AddFavorite -> addFavorite(intent.name)
            is StageIntent.PlayFavorite -> playFavorite(intent.name)
            StageIntent.RefreshFavorites -> track { refreshFavorites() }
            StageIntent.ExportFavorites -> exportFavorites()
            is StageIntent.ImportFavorites -> importFavorites(intent.bytes)
            StageIntent.Retry -> track { retry() }
            StageIntent.RetryAfterChallenge -> retryAfterChallenge()
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
        }
    }

    override fun noteReady(username: String?) {
        laneFace = laneFace.copy(readyUsername = username)
        publish()
    }

    override fun notePlaying(username: String?) {
        val current = feed.currentUsername
        val same = username != null && current != null && username.equals(current, ignoreCase = true)
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
            dwell.clearFailure()
        }
        updateDwellActive()
        publish()
    }

    override fun noteFailed(failed: Boolean, note: String) {
        if (!failed) {
            val clear = laneFace.playbackNote == failureNote
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
            publish()
            return
        }
        failureNote = note
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

    internal fun target(): RoomTarget? {
        val name = feed.currentUsername ?: return null
        return RoomTarget.primary(name, laneFace.generation)
    }

    internal fun accepts(target: RoomTarget?): Boolean {
        if (target == null || target.laneID != LaneID.PRIMARY) return false
        val name = feed.currentUsername ?: return false
        return target.matches(LaneID.PRIMARY, name, laneFace.generation)
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
        val name = feed.currentUsername
        val playback = if (name == null) LanePlayback.Empty else laneFace.playback
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
        )
    }

    /** 只改界面上的这一路。真正向站点要地址是 A5b 的事。 */
    internal fun requestPlay() {
        if (!ageConfirmed || laneFace.manuallyPaused) {
            publish()
            return
        }
        val name = feed.currentUsername
        if (name == null) {
            laneFace = laneFace.copy(username = null, playback = LanePlayback.Empty, playbackNote = null)
            idleStart.markIdle()
            updateDwellActive()
            publish()
            return
        }
        failureNote = null
        laneFace = laneFace.copy(
            username = name,
            generation = laneFace.generation + 1,
            playback = LanePlayback.Idle,
            playbackNote = null,
            readyUsername = null,
            playingUsername = null,
        )
        updateDwellActive()
        publish()
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
        feed = FeedStore(
            query = RoomQuery(
                publicOnly = publicOnly,
                blocked = curation.blockedSet,
                hidden = if (source == FeedSource.guess) taste.vetoedKeys else emptySet(),
            ),
        )
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
        muted = !muted
        if (!muted && volume <= 0.0) {
            volume = StageRules.RESTORE_VOLUME
            settings.volume = volume
        }
        settings.muted = muted
        publish()
    }

    private fun setVolume(value: Double) {
        volume = value.coerceIn(0.0, 1.0)
        muted = volume == 0.0
        settings.volume = volume
        settings.muted = muted
        publish()
    }

    private fun togglePause(target: RoomTarget?) {
        if (target != null && !accepts(target)) return
        laneFace = laneFace.copy(manuallyPaused = !laneFace.manuallyPaused)
        updateDwellActive()
        publish()
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

    fun imported(added: Int, skipped: String): String = get(R.string.stage_import, "已导入 %1\$d 个收藏%2\$s", added, skipped)

    fun importSkipped(rejected: Int): String = get(R.string.stage_import_skipped, "，忽略 %1\$d 条格式不对的", rejected)

    fun rollback(message: String, source: FeedSource): String =
        get(R.string.stage_rollback, "%1\$s，仍停在%2\$s", message, source(source))

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
