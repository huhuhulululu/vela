package vela.app.stage

import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vela.app.infra.Settings
import vela.core.base.lane.LaneID
import vela.core.base.lane.RoomTarget
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.model.dedupeRooms
import vela.core.base.site.FavoriteStatus
import vela.core.base.site.SiteSearchText
import vela.core.curation.Curation
import vela.core.curation.FavoriteExchange
import vela.core.curation.FavoritesBoard
import vela.core.lifecycle.ChallengeGate
import vela.core.lifecycle.ChallengeResume

/**
 * 单窗契约的假实现，给界面测试和本包的契约测试用。
 * 目录、推荐、真播放都不在这里：需要联网的动作记成 [StageEffect]，由测试用 [installCatalog]、
 * [deliverSiteSearch] 把结果送回来。口味数值不是 C6a 的 `Taste`，只移动面板上的行。
 *
 * 年龄确认前除 [StageIntent.ConfirmAge] 和 [StageIntent.Quit] 外一律丢弃。
 */
class FakeStage(initial: StageUiState = StageUiState()) : StageContract {
    private val mutable = MutableStateFlow(initial.materialized(emptyMap()))
    override val state: StateFlow<StageUiState> = mutable.asStateFlow()

    private val effectLog = ArrayList<StageEffect>()
    private val favoriteStatuses = HashMap<String, FavoriteStatus>()
    private val challengeGate = ChallengeGate()
    private var playerNote: String? = null
    private var sourceCatalog: List<Room> = initial.catalog
    private var searchEpoch: Int = 0
    private var pendingSearch: PendingSearch? = null

    /** 质询闸门用的钟，测试可改。 */
    var now: Instant = Instant.EPOCH

    var pipEnters: Int = 0
        private set

    val effects: List<StageEffect>
        get() = effectLog.toList()

    fun clearEffects() {
        effectLog.clear()
    }

    /** 原样换上快照，不重算列表。测试布置界面用。 */
    fun seed(next: StageUiState) {
        sourceCatalog = next.catalog
        mutable.value = next
    }

    fun target(lane: LaneID = LaneID.PRIMARY): RoomTarget? {
        if (lane != LaneID.PRIMARY) return null
        val snapshot = mutable.value
        val name = snapshot.currentUsername ?: return null
        return RoomTarget.primary(name, snapshot.lane.generation)
    }

    fun presentFavoriteSuggest(username: String, displayName: String) {
        val snapshot = mutable.value
        if (!snapshot.ageConfirmed) return
        commit(snapshot.copy(favoriteSuggest = FavoriteSuggest(username, displayName), toast = null, toastUndoName = null))
    }

    /**
     * 目录请求的回包。带 [error] 时不换列表：切来源且列表非空则回滚来源，否则留下错误文案。
     */
    fun installCatalog(rooms: List<Room>, error: String? = null) {
        val snapshot = mutable.value
        if (error != null) {
            failCatalog(snapshot, error)
            return
        }
        challengeGate.reset()
        val catalog = dedupeRooms(rooms)
        sourceCatalog = catalog
        val previous = snapshot.currentUsername
        var next = snapshot.copy(
            catalog = catalog,
            loadedSource = snapshot.source,
            catalogError = null,
            loading = false,
            favoritesChecking = false,
            retainedUsername = null,
            catalogClaimed = true,
        )
        val visible = visibleRooms(catalog, next.roomQuery(), null)
        val kept = previous?.takeIf { name -> visible.any { it.username == name } } ?: visible.firstOrNull()?.username
        next = next.copy(currentUsername = kept)
        val armed = if (kept != null) arm(next) else next to null
        commit(armed.first, listOfNotNull(armed.second))
    }

    fun deliverSiteSearch(keyword: String, rooms: List<Room>) {
        val pending = pendingSearch ?: return
        if (pending.epoch != searchEpoch) return
        if (!keyword.equals(pending.keyword, ignoreCase = true)) return
        pendingSearch = null
        challengeGate.reset()
        val blocked = mutable.value.curation.blockedSet
        val catalog = dedupeRooms(rooms).filter { it.username.lowercase(Locale.ROOT) !in blocked }
        var next = mutable.value.copy(
            catalog = catalog,
            siteSearch = pending.keyword,
            loading = false,
            catalogError = null,
            retainedUsername = null,
            surface = if (mutable.value.surface == StageSurface.Filters) StageSurface.None else mutable.value.surface,
        )
        next = next.copy(currentUsername = visibleRooms(catalog, next.roomQuery(), null).firstOrNull()?.username)
        val armed = arm(next)
        commit(armed.first, listOfNotNull(armed.second))
    }

    /** 全站没有结果，或请求失败。合法且没被屏蔽的用户名改为直达。 */
    fun deliverSiteSearchMiss(keyword: String, failed: Boolean) {
        val pending = pendingSearch ?: return
        if (pending.epoch != searchEpoch) return
        if (!keyword.equals(pending.keyword, ignoreCase = true)) return
        pendingSearch = null
        var snapshot = mutable.value.copy(loading = false)
        val direct = Room.direct(keyword)
        if (direct != null && !snapshot.curation.isBlocked(direct.username)) {
            snapshot = insertDirect(snapshot, direct)
            val armed = arm(snapshot)
            commit(armed.first, listOfNotNull(armed.second))
            return
        }
        val text = if (failed) "全站搜索失败，稍后再试" else "全站没有找到「$keyword」"
        commit(snapshot.withToast(text))
    }

    fun deliverFavoriteStatus(name: String, status: FavoriteStatus) {
        favoriteStatuses[Curation.key(name)] = status
        commit(mutable.value.copy(favoritesChecking = false))
    }

    fun finishFavoriteCheck() {
        commit(mutable.value.copy(favoritesChecking = false))
    }

    /** 网络层撞上质询页时调用。闸门建议不弹时只提示，不开网页。 */
    fun noteChallenge(resume: ChallengeResume, at: Instant = now) {
        when (challengeGate.register(at)) {
            is ChallengeGate.Decision.Suppress -> suppressChallenge(resume)
            is ChallengeGate.Decision.Present -> presentChallenge(resume)
        }
    }

    override fun dispatch(intent: StageIntent) {
        val snapshot = mutable.value
        if (!snapshot.ageConfirmed && intent !is StageIntent.ConfirmAge && intent !is StageIntent.Quit) return
        @Suppress("REDUNDANT_ELSE_IN_WHEN") // B0 / PiP1 会在契约末尾加意图；else 让假舞台先编译过。
        when (intent) {
            StageIntent.ConfirmAge -> confirmAge(snapshot)
            StageIntent.Quit -> commit(snapshot, listOf(StageEffect.Quit))
            is StageIntent.SelectSource -> selectSource(snapshot, intent.source)
            is StageIntent.SetSearchText -> setSearchText(snapshot, intent.text)
            StageIntent.SubmitSearch -> submitSearch(snapshot)
            is StageIntent.ChooseGender -> chooseGender(snapshot, intent.gender)
            is StageIntent.ChooseRegion -> chooseRegion(snapshot, intent.region)
            is StageIntent.SetPublicOnly -> setPublicOnly(snapshot, intent.value)
            is StageIntent.SetTagText -> commit(snapshot.copy(tagText = intent.text))
            StageIntent.ApplyTag -> requestCatalog(snapshot)
            StageIntent.ToggleMute -> toggleMute(snapshot)
            is StageIntent.SetVolume -> setVolume(snapshot, intent.value)
            StageIntent.ToggleInfo -> commit(snapshot.copy(infoAlwaysOn = !snapshot.infoAlwaysOn))
            is StageIntent.Step -> step(snapshot, intent.delta, intent.lane)
            is StageIntent.TogglePause -> togglePause(snapshot, intent.target)
            is StageIntent.ToggleFavorite -> toggleFavorite(snapshot, intent.target)
            is StageIntent.BlockCurrent -> blockCurrent(snapshot, intent.target)
            is StageIntent.MarkNotInterested -> markNotInterested(snapshot, intent.target)
            is StageIntent.RetryPlayback -> retryPlayback(snapshot, intent.target)
            is StageIntent.RemoveFavorite -> applyCuration(snapshot, snapshot.curation.unfavoriting(intent.name), null, null)
            is StageIntent.RestoreFavorite -> applyCuration(snapshot, snapshot.curation.restoringFavorite(intent.name), null, null)
            is StageIntent.Unblock -> applyCuration(snapshot, snapshot.curation.unblocking(intent.name), null, null)
            is StageIntent.AddFavorite -> addFavorite(snapshot, intent.name)
            is StageIntent.PlayFavorite -> playFavorite(snapshot, intent.name)
            StageIntent.RefreshFavorites -> refreshFavorites(snapshot)
            StageIntent.ExportFavorites -> exportFavorites(snapshot)
            is StageIntent.ImportFavorites -> importFavorites(snapshot, intent.bytes)
            StageIntent.Retry -> retry(snapshot)
            StageIntent.RetryAfterChallenge -> retryAfterChallenge(snapshot)
            StageIntent.DismissChallenge -> {
                if (snapshot.surface == StageSurface.Challenge) commit(snapshot.copy(surface = StageSurface.None))
            }
            StageIntent.DismissHint -> commit(snapshot.copy(hintVisible = false, hintSeen = true))
            StageIntent.UndoToast -> undoToast(snapshot)
            StageIntent.DismissToast -> commit(snapshot.copy(toast = null, toastUndoName = null))
            StageIntent.AcceptFavoriteSuggest -> acceptSuggest(snapshot)
            StageIntent.CloseFavoriteSuggest -> commit(snapshot.copy(favoriteSuggest = null))
            is StageIntent.ShowSurface -> showSurface(snapshot, intent.surface)
            StageIntent.DismissSurfaces -> {
                if (snapshot.surface != StageSurface.Challenge) commit(snapshot.copy(surface = StageSurface.None))
            }
            StageIntent.NoteActivity -> commit(snapshot.copy(chromeVisible = true))
            StageIntent.ChromeIdle -> {
                if (snapshot.surface == StageSurface.None) commit(snapshot.copy(chromeVisible = false))
            }
            is StageIntent.BoostTaste -> boostTaste(snapshot, intent.key)
            is StageIntent.VetoTaste -> vetoTaste(snapshot, intent.key)
            is StageIntent.RestoreTaste -> restoreTaste(snapshot, intent.key)
            is StageIntent.RemoveTaste -> removeTaste(snapshot, intent.key)
            is StageIntent.SetLearningPaused -> setLearningPaused(snapshot, intent.paused)
            StageIntent.ResetTaste -> resetTaste(snapshot, "口味画像已重置")
            StageIntent.ClearPreferences -> resetTaste(snapshot, "已清除口味与观看记录，收藏与屏蔽保留")
            is StageIntent.SetDual -> commit(LaneWindow.setDual(snapshot, intent.on, intent.remember))
            is StageIntent.SetLaneCount -> commit(LaneWindow.setLaneCount(snapshot, intent.count, intent.remember))
            is StageIntent.SetFocus -> commit(LaneWindow.setFocus(snapshot, intent.lane))
            is StageIntent.SetLandscape -> commit(LaneWindow.setLandscape(snapshot, intent.on))
            is StageIntent.NotePortraitStageSize ->
                commit(LaneWindow.notePortraitStageSize(snapshot, intent.width, intent.height))
            is StageIntent.SetAutoEnterPip -> commit(snapshot.copy(autoEnterPip = intent.enabled))
            StageIntent.EnterPictureInPicture -> pipEnters += 1
            else -> Unit
        }
    }

    override fun noteReady(username: String?) {
        val snapshot = mutable.value
        commit(snapshot.copy(lane = snapshot.lane.copy(readyUsername = username)))
    }

    override fun notePlaying(username: String?) {
        val snapshot = mutable.value
        val current = snapshot.currentUsername
        val same = username != null && current != null && username.equals(current, ignoreCase = true)
        val playback = when {
            same -> LanePlayback.Playing
            snapshot.lane.playback == LanePlayback.Failed -> LanePlayback.Failed
            snapshot.current == null -> LanePlayback.Empty
            else -> LanePlayback.Idle
        }
        val note = if (same) null else snapshot.lane.playbackNote
        if (same) playerNote = null
        commit(
            snapshot.copy(
                lane = snapshot.lane.copy(
                    playingUsername = username,
                    playback = playback,
                    playbackNote = note,
                ),
            ),
        )
    }

    override fun noteFailed(failed: Boolean, note: String) {
        val snapshot = mutable.value
        if (!failed) {
            val clear = snapshot.lane.playbackNote == playerNote
            playerNote = null
            val playback = when (snapshot.lane.playback) {
                LanePlayback.Failed ->
                    if (snapshot.lane.playingUsername != null) LanePlayback.Playing else LanePlayback.Idle
                else -> snapshot.lane.playback
            }
            commit(
                snapshot.copy(
                    lane = snapshot.lane.copy(
                        playback = playback,
                        playbackNote = if (clear) null else snapshot.lane.playbackNote,
                    ),
                ),
            )
            return
        }
        playerNote = note
        commit(
            snapshot.copy(
                chromeVisible = true,
                lane = snapshot.lane.copy(playback = LanePlayback.Failed, playbackNote = note),
            ),
        )
    }

    private fun confirmAge(snapshot: StageUiState) {
        if (snapshot.ageConfirmed) return
        var next = snapshot.copy(ageConfirmed = true, hintVisible = !snapshot.hintSeen)
        val notices = next.startupNotices
        if (notices.isNotEmpty()) {
            next = next.withToast(notices.joinToString("；")).copy(startupNotices = emptyList())
        }
        if (next.catalogClaimed) {
            commit(next)
            return
        }
        requestCatalog(next.copy(catalogClaimed = true))
    }

    private fun selectSource(snapshot: StageUiState, source: FeedSource) {
        if (snapshot.source == source) return
        requestCatalog(snapshot.copy(source = source, siteSearch = null, retainedUsername = null))
    }

    private fun chooseGender(snapshot: StageUiState, gender: vela.core.base.model.Gender?) {
        if (snapshot.gender == gender) return
        requestCatalog(snapshot.copy(gender = gender))
    }

    private fun chooseRegion(snapshot: StageUiState, region: vela.core.base.model.Region?) {
        if (snapshot.region == region) return
        requestCatalog(snapshot.copy(region = region))
    }

    private fun setPublicOnly(snapshot: StageUiState, value: Boolean) {
        if (snapshot.publicOnly == value) return
        refilter(snapshot.copy(publicOnly = value, retainedUsername = null), snapshot.rooms, playOnMove = true)
    }

    private fun toggleMute(snapshot: StageUiState) {
        val muted = !snapshot.muted
        val volume = if (!muted && snapshot.volume <= 0.0) StageRules.RESTORE_VOLUME else snapshot.volume
        commit(snapshot.copy(muted = muted, volume = volume))
    }

    private fun setVolume(snapshot: StageUiState, value: Double) {
        val volume = value.coerceIn(0.0, 1.0)
        commit(snapshot.copy(volume = volume, muted = volume == 0.0))
    }

    private fun setSearchText(snapshot: StageUiState, text: String) {
        val active = snapshot.siteSearch
        if (active == null) {
            if (SiteSearchText.normalized(text) == null) invalidateSearch()
            commit(snapshot.copy(searchText = text, retainedUsername = snapshot.currentUsername))
            return
        }
        if (SiteSearchText.normalized(text) == null) {
            invalidateSearch()
            val restored = snapshot.copy(
                searchText = text,
                siteSearch = null,
                catalog = sourceCatalog,
                retainedUsername = null,
                loading = true,
            )
            commit(restored, listOf(StageEffect.ExitSiteSearch, StageEffect.CatalogReload))
            return
        }
        commit(snapshot.copy(searchText = text))
    }

    private fun submitSearch(snapshot: StageUiState) {
        val text = trimEdge(snapshot.searchText)
        val focused = snapshot.rooms.firstOrNull { it.username.equals(text, ignoreCase = true) }
        if (focused != null) {
            invalidateSearch()
            var next = snapshot.copy(
                siteSearch = null,
                currentUsername = focused.username,
                retainedUsername = null,
                surface = if (snapshot.surface == StageSurface.Filters) StageSurface.None else snapshot.surface,
            )
            val armed = arm(next)
            next = armed.first
            commit(next, listOfNotNull(armed.second))
            return
        }
        val keyword = SiteSearchText.normalized(text)
        if (keyword == null) return
        val active = snapshot.siteSearch
        if (active != null && keyword.equals(active, ignoreCase = true)) return
        if (active != null || snapshot.localMatches == 0) {
            val epoch = invalidateSearch()
            val pending = PendingSearch(keyword, epoch)
            pendingSearch = pending
            commit(snapshot.copy(searchText = text, loading = true), listOf(StageEffect.SiteSearch(keyword)))
            return
        }
        invalidateSearch()
        val direct = Room.direct(text)
        if (direct != null) {
            if (snapshot.curation.isBlocked(direct.username)) {
                commit(snapshot.withToast("${direct.username} 在屏蔽列表里"))
                return
            }
            var next = insertDirect(snapshot, direct).copy(
                surface = if (snapshot.surface == StageSurface.Filters) StageSurface.None else snapshot.surface,
            )
            val armed = arm(next)
            commit(armed.first, listOfNotNull(armed.second))
            return
        }
        refilter(snapshot.copy(searchText = text, retainedUsername = null), snapshot.rooms, playOnMove = true)
    }

    /** @return 是否真的换了房间。没换时不改状态，调用方决定要不要提交自己的文案。 */
    private fun step(snapshot: StageUiState, delta: Int, lane: LaneID): Boolean {
        if (lane != LaneID.PRIMARY || delta == 0 || snapshot.rooms.isEmpty()) return false
        val landed = landingIndex(snapshot.index, delta, snapshot.rooms.map { it.username }, emptySet())
        val nextName = snapshot.rooms[landed].username
        if (nextName == snapshot.currentUsername) return false
        var next = snapshot.copy(
            currentUsername = nextName,
            retainedUsername = null,
            lane = snapshot.lane.copy(manuallyPaused = false),
        )
        if (next.hintVisible) next = next.copy(hintVisible = false, hintSeen = true)
        val armed = arm(next)
        commit(armed.first, listOfNotNull(armed.second))
        return true
    }

    private fun togglePause(snapshot: StageUiState, target: RoomTarget?) {
        if (target != null && !snapshot.accepts(target)) return
        val paused = !snapshot.lane.manuallyPaused
        commit(snapshot.copy(lane = snapshot.lane.copy(manuallyPaused = paused)))
    }

    private fun toggleFavorite(snapshot: StageUiState, target: RoomTarget?) {
        if (!snapshot.accepts(target)) return
        val room = snapshot.current ?: return
        if (snapshot.curation.isFavorite(room.username)) {
            val toast = "已取消收藏 ${room.displayName}"
            applyCuration(snapshot, snapshot.curation.unfavoriting(room.username), toast, room.username)
        } else {
            applyCuration(snapshot, snapshot.curation.favoriting(room.username), null, null)
        }
    }

    private fun blockCurrent(snapshot: StageUiState, target: RoomTarget?) {
        if (!snapshot.accepts(target)) return
        val room = snapshot.current ?: return
        applyCuration(
            snapshot.copy(lane = snapshot.lane.copy(manuallyPaused = false)),
            snapshot.curation.blocking(room.username),
            "已屏蔽 ${room.displayName}，可在收藏与屏蔽列表里取消",
            null,
        )
    }

    private fun markNotInterested(snapshot: StageUiState, target: RoomTarget?) {
        if (!snapshot.accepts(target)) return
        val text = if (snapshot.taste.paused) "已跳过，学习已暂停所以没有记口味" else "已记下，会少推荐类似的"
        val toasted = snapshot.withToast(text)
        if (!step(toasted, 1, LaneID.PRIMARY)) commit(toasted)
    }

    private fun retryPlayback(snapshot: StageUiState, target: RoomTarget?) {
        if (!snapshot.accepts(target)) return
        var next = snapshot.copy(
            lane = snapshot.lane.copy(
                playback = if (snapshot.lane.playback == LanePlayback.Failed) LanePlayback.Idle else snapshot.lane.playback,
                playbackNote = null,
            ),
        )
        playerNote = null
        val armed = arm(next)
        commit(armed.first, listOfNotNull(armed.second))
    }

    private fun acceptSuggest(snapshot: StageUiState) {
        val suggest = snapshot.favoriteSuggest ?: return
        applyCuration(
            snapshot.copy(favoriteSuggest = null),
            snapshot.curation.favoriting(suggest.username),
            null,
            null,
        )
    }

    private fun undoToast(snapshot: StageUiState) {
        val name = snapshot.toastUndoName ?: return
        if (!snapshot.curation.canRestoreFavorite(name)) return
        applyCuration(
            snapshot.copy(toast = null, toastUndoName = null),
            snapshot.curation.restoringFavorite(name),
            null,
            null,
        )
    }

    private fun addFavorite(snapshot: StageUiState, raw: String) {
        val name = trimEdge(raw)
        if (!Room.isValidUsername(name)) {
            commit(snapshot.copy(favoriteDraftError = "用户名只含字母、数字、下划线，2 到 30 位"))
            return
        }
        if (snapshot.curation.isFavorite(name)) {
            commit(snapshot.copy(favoriteDraftError = "已经在收藏里了"))
            return
        }
        val effects = if (snapshot.source != FeedSource.favorites) listOf(StageEffect.CheckFavorites) else emptyList()
        applyCuration(snapshot.copy(favoriteDraftError = null), snapshot.curation.favoriting(name), null, null, effects)
    }

    private fun playFavorite(snapshot: StageUiState, name: String) {
        val key = Curation.key(name)
        val room = favoriteStatuses[key]?.room
            ?: snapshot.catalog.firstOrNull { Curation.key(it.username) == key && it.show.isPlayable }
            ?: sourceCatalog.firstOrNull { Curation.key(it.username) == key && it.show.isPlayable }
            ?: return
        invalidateSearch()
        var next = snapshot.copy(
            siteSearch = null,
            catalog = if (snapshot.siteSearch != null) sourceCatalog else snapshot.catalog,
            surface = StageSurface.None,
            retainedUsername = null,
        )
        if (!next.roomQuery().matches(room)) next = next.copy(searchText = "")
        val visible = visibleRooms(next.catalog, next.roomQuery(), null)
        val match = visible.firstOrNull { Curation.key(it.username) == key }
        next = if (match != null) {
            next.copy(currentUsername = match.username)
        } else {
            insertDirect(next, room)
        }
        val armed = arm(next)
        commit(armed.first, listOfNotNull(armed.second))
    }

    private fun refreshFavorites(snapshot: StageUiState) {
        if (snapshot.favoritesChecking) return
        if (snapshot.source == FeedSource.favorites) {
            requestCatalog(snapshot.copy(favoritesChecking = true))
        } else {
            commit(snapshot.copy(favoritesChecking = true), listOf(StageEffect.RefreshFavorites))
        }
    }

    private fun exportFavorites(snapshot: StageUiState) {
        val bytes = FavoriteExchange.encode(snapshot.curation.favorites)
        effectLog.add(StageEffect.ExportedFavorites(bytes))
    }

    private fun importFavorites(snapshot: StageUiState, bytes: ByteArray) {
        val imported = try {
            FavoriteExchange.decode(bytes)
        } catch (_: FavoriteExchange.Failure.TooLarge) {
            commit(snapshot.withToast("文件太大，不是收藏夹导出文件"))
            return
        } catch (_: FavoriteExchange.Failure) {
            commit(snapshot.withToast("无法识别这个文件"))
            return
        }
        val merged = snapshot.curation.importing(imported.names)
        val skipped = if (imported.rejected > 0) "，忽略 ${imported.rejected} 条格式不对的" else ""
        val effects = if (snapshot.source != FeedSource.favorites) listOf(StageEffect.CheckFavorites) else emptyList()
        applyCuration(
            snapshot,
            merged.curation,
            "已导入 ${merged.added} 个收藏$skipped",
            null,
            effects,
        )
    }

    private fun retry(snapshot: StageUiState) {
        if (snapshot.source == FeedSource.favorites) refreshFavorites(snapshot) else requestCatalog(snapshot)
    }

    private fun retryAfterChallenge(snapshot: StageUiState) {
        val resume = snapshot.challengeResume
        var next = snapshot.copy(surface = StageSurface.None, challengeResume = null)
        when (resume) {
            null -> {
                val armed = arm(next)
                commit(armed.first, listOfNotNull(armed.second))
            }
            is ChallengeResume.Reload, is ChallengeResume.Favorites -> {
                if (resume is ChallengeResume.Favorites && next.source != FeedSource.favorites) {
                    val armed = arm(next.copy(favoritesChecking = true))
                    commit(armed.first, listOfNotNull(StageEffect.CheckFavorites, armed.second))
                } else {
                    requestCatalog(next.copy(catalogError = null))
                }
            }
            is ChallengeResume.SiteSearch -> {
                val epoch = invalidateSearch()
                pendingSearch = PendingSearch(resume.query, epoch)
                commit(next.copy(loading = true, catalogError = null), listOf(StageEffect.SiteSearch(resume.query)))
            }
        }
    }

    private fun showSurface(snapshot: StageUiState, surface: StageSurface) {
        if (surface == StageSurface.None) {
            if (snapshot.surface != StageSurface.Challenge) commit(snapshot.copy(surface = StageSurface.None))
            return
        }
        commit(snapshot.copy(surface = surface, chromeVisible = true))
    }

    private fun boostTaste(snapshot: StageUiState, key: String) {
        if (key.isEmpty()) return
        val taste = snapshot.taste
        val existing = (taste.disliked + taste.liked + taste.genders).firstOrNull { it.key == key }
        val row = (existing ?: TasteRowState(key, tasteName(key), 0.6, 1, false)).copy(vetoed = false)
        val nextTaste = if (key.startsWith("g:")) {
            taste.copy(genders = taste.genders.filter { it.key != key } + row)
        } else {
            taste.copy(
                liked = listOf(row) + taste.liked.filter { it.key != key },
                disliked = taste.disliked.filter { it.key != key },
                suggestions = taste.suggestions.filterNot { it.equals(row.name, ignoreCase = true) },
                tagCount = taste.tagCount + if (existing == null) 1 else 0,
            )
        }
        refilter(snapshot.copy(taste = nextTaste, hiddenKeys = snapshot.hiddenKeys - key), snapshot.rooms, playOnMove = true)
    }

    private fun vetoTaste(snapshot: StageUiState, key: String) {
        if (key.isEmpty()) return
        val taste = snapshot.taste
        val existing = (taste.liked + taste.disliked + taste.genders).firstOrNull { it.key == key }
        val row = (existing ?: TasteRowState(key, tasteName(key), 0.2, 1, true)).copy(vetoed = true)
        val nextTaste = if (key.startsWith("g:")) {
            taste.copy(genders = taste.genders.filter { it.key != key } + row)
        } else {
            taste.copy(
                liked = taste.liked.filter { it.key != key },
                disliked = listOf(row) + taste.disliked.filter { it.key != key },
                suggestions = taste.suggestions.filterNot { it.equals(row.name, ignoreCase = true) },
                tagCount = taste.tagCount + if (existing == null) 1 else 0,
            )
        }
        refilter(snapshot.copy(taste = nextTaste, hiddenKeys = snapshot.hiddenKeys + key), snapshot.rooms, playOnMove = true)
    }

    private fun restoreTaste(snapshot: StageUiState, key: String) {
        if (key.isEmpty()) return
        val row = snapshot.taste.disliked.firstOrNull { it.key == key }?.copy(vetoed = false) ?: return
        val taste = snapshot.taste.copy(
            disliked = snapshot.taste.disliked.filter { it.key != key },
            liked = listOf(row) + snapshot.taste.liked,
        )
        refilter(snapshot.copy(taste = taste, hiddenKeys = snapshot.hiddenKeys - key), snapshot.rooms, playOnMove = false)
    }

    private fun removeTaste(snapshot: StageUiState, key: String) {
        val taste = snapshot.taste
        val had = (taste.liked + taste.disliked).any { it.key == key }
        val next = taste.copy(
            liked = taste.liked.filter { it.key != key },
            disliked = taste.disliked.filter { it.key != key },
            genders = taste.genders.filter { it.key != key },
            tagCount = (taste.tagCount - if (had) 1 else 0).coerceAtLeast(0),
        )
        refilter(snapshot.copy(taste = next, hiddenKeys = snapshot.hiddenKeys - key), snapshot.rooms, playOnMove = true)
    }

    private fun setLearningPaused(snapshot: StageUiState, paused: Boolean) {
        val text = if (paused) "已暂停学习，排序仍按现有口味" else "已恢复学习"
        commit(
            snapshot.copy(
                taste = snapshot.taste.copy(paused = paused),
                favoriteSuggest = if (paused) null else snapshot.favoriteSuggest,
            ).withToast(text),
        )
    }

    private fun resetTaste(snapshot: StageUiState, toast: String) {
        refilter(
            snapshot.copy(taste = TastePanelState(), hiddenKeys = emptySet()).withToast(toast),
            snapshot.rooms,
            playOnMove = true,
        )
    }

    private fun applyCuration(
        snapshot: StageUiState,
        nextCuration: Curation,
        toast: String?,
        undo: String?,
        extra: List<StageEffect> = emptyList(),
    ) {
        var next = snapshot.copy(curation = nextCuration, favoriteDraftError = null, retainedUsername = null)
        if (toast != null) next = next.withToast(toast, undo)
        val suggest = snapshot.favoriteSuggest
        if (suggest != null && nextCuration.isFavorite(suggest.username)) {
            next = next.copy(favoriteSuggest = null)
        }
        val favoritesChanged = nextCuration.favorites != snapshot.curation.favorites
        val reloadFavorites = favoritesChanged && snapshot.source == FeedSource.favorites && snapshot.siteSearch == null
        refilter(next, snapshot.rooms, playOnMove = true, extra = extra, catalogReload = reloadFavorites)
    }

    private fun refilter(
        snapshot: StageUiState,
        previousRooms: List<Room>,
        playOnMove: Boolean,
        extra: List<StageEffect> = emptyList(),
        catalogReload: Boolean = false,
    ) {
        val query = snapshot.roomQuery()
        val name = snapshot.currentUsername
        var next = snapshot
        if (name != null) {
            val at = previousRooms.indexOfFirst { it.username == name }
            if (at >= 0 && !query.matches(previousRooms[at])) {
                val after = previousRooms.drop(at + 1).firstOrNull { query.matches(it) }
                val beforeRoom = previousRooms.take(at).lastOrNull { query.matches(it) }
                next = next.copy(currentUsername = (after ?: beforeRoom)?.username)
            }
        }
        val moved = next.currentUsername != snapshot.currentUsername
        if (moved && next.hintVisible) next = next.copy(hintVisible = false, hintSeen = true)
        val effects = ArrayList<StageEffect>(extra)
        if (catalogReload) {
            next = next.copy(loading = true)
            effects.add(StageEffect.CatalogReload)
        }
        if (playOnMove && moved) {
            val armed = arm(next)
            next = armed.first
            armed.second?.let { effects.add(it) }
        }
        commit(next, effects)
    }

    private fun requestCatalog(snapshot: StageUiState) {
        commit(snapshot.copy(loading = true, catalogClaimed = true), listOf(StageEffect.CatalogReload))
    }

    private fun failCatalog(snapshot: StageUiState, message: String) {
        if (snapshot.source != snapshot.loadedSource && snapshot.rooms.isNotEmpty()) {
            commit(
                snapshot.copy(source = snapshot.loadedSource, loading = false, favoritesChecking = false).withToast(message),
            )
            return
        }
        var next = snapshot.copy(loading = false, favoritesChecking = false, catalogError = message)
        if (snapshot.rooms.isNotEmpty()) next = next.withToast(message)
        commit(next)
    }

    private fun presentChallenge(resume: ChallengeResume) {
        val snapshot = mutable.value
        val merged = snapshot.challengeResume?.merging(resume) ?: resume
        commit(
            snapshot.copy(
                loading = false,
                chromeVisible = true,
                surface = StageSurface.Challenge,
                challengeResume = merged,
                catalogError = StageRules.CATALOG_NEEDS_CHALLENGE,
            ),
        )
    }

    private fun suppressChallenge(resume: ChallengeResume) {
        val snapshot = mutable.value.copy(loading = false)
        val message = when (resume) {
            is ChallengeResume.SiteSearch -> "站点连续要求确认，全站搜索稍后再试"
            is ChallengeResume.Favorites -> "站点连续要求确认，收藏状态稍后再查"
            is ChallengeResume.Reload -> "站点连续要求确认，稍后再试"
        }
        if (resume is ChallengeResume.Reload) {
            failCatalog(snapshot, message)
        } else {
            commit(snapshot.withToast(message))
        }
    }

    private fun arm(snapshot: StageUiState): Pair<StageUiState, StageEffect?> {
        if (!snapshot.ageConfirmed || snapshot.lane.manuallyPaused) return snapshot to null
        val name = snapshot.currentUsername ?: return snapshot.copy(
            lane = snapshot.lane.copy(username = null, playback = LanePlayback.Empty),
        ) to null
        playerNote = null
        val lane = snapshot.lane.copy(
            username = name,
            generation = snapshot.lane.generation + 1,
            playback = LanePlayback.Idle,
            playbackNote = null,
            readyUsername = null,
            playingUsername = null,
        )
        return snapshot.copy(lane = lane) to StageEffect.Play(name)
    }

    private fun insertDirect(snapshot: StageUiState, room: Room): StageUiState {
        val key = room.username.lowercase(Locale.ROOT)
        val catalog = snapshot.catalog.filterNot { it.username.lowercase(Locale.ROOT) == key }.toMutableList()
        val anchor = snapshot.currentUsername
        val at = anchor?.let { name -> catalog.indexOfFirst { it.username == name } } ?: -1
        catalog.add(if (at >= 0) at + 1 else 0, room)
        val frozen = catalog.toList()
        if (snapshot.siteSearch == null) sourceCatalog = frozen
        return snapshot.copy(catalog = frozen, currentUsername = room.username, retainedUsername = null)
    }

    private fun invalidateSearch(): Int {
        searchEpoch += 1
        pendingSearch = null
        return searchEpoch
    }

    private fun commit(snapshot: StageUiState, effects: List<StageEffect> = emptyList()) {
        mutable.value = snapshot.materialized(favoriteStatuses)
        effectLog.addAll(effects)
    }

    private data class PendingSearch(val keyword: String, val epoch: Int)
}

private fun StageUiState.materialized(statuses: Map<String, FavoriteStatus>): StageUiState {
    val visible = visibleRooms(catalog, roomQuery(), retainedUsername)
    val name = currentUsername?.takeIf { wanted -> visible.any { it.username == wanted } }
    val playback = if (name == null) LanePlayback.Empty else lane.playback
    return copy(
        rooms = visible,
        currentUsername = name,
        lane = lane.copy(username = name, playback = playback),
        favoritesBoard = FavoritesBoard.make(
            favorites = curation.favorites,
            statuses = statuses,
            catalog = catalog,
        ),
    )
}

private fun StageUiState.withToast(text: String, undo: String? = null): StageUiState {
    val undoName = undo?.takeIf { curation.canRestoreFavorite(it) }
    return copy(toast = text, toastUndoName = undoName, favoriteSuggest = null)
}

private fun trimEdge(raw: String): String = raw.trim { it.isWhitespace() || Character.isSpaceChar(it) }

private fun tasteName(key: String): String = if (key.length > 2 && key[1] == ':') key.substring(2) else key

private fun room(name: String, tags: List<String> = emptyList(), viewers: Int = 1): Room = Room(
    username = name,
    tags = tags,
    viewers = viewers,
    show = ShowState.publicShow,
)

/**
 * 契约测试。类名带 StageContract，好让 `:app:testDebugUnitTest --tests '*StageContract*'` 挑中。
 * 假舞台的行为就是单窗界面要依赖的规则。
 */
class StageContractTest {
    @Test
    fun testStageContractDefaultsAreMutedAndAgeClosed() {
        val stage = FakeStage()
        val state = stage.state.value
        assertFalse(state.ageConfirmed)
        assertTrue(state.muted)
        assertFalse(state.playbackAllowed)
        assertEquals(Settings.DEFAULT_VOLUME, state.volume, 0.0)
        assertEquals(FeedSource.guess, state.source)
        assertTrue(state.publicOnly)
        assertTrue(state.chromeVisible)
        assertTrue(state.chromeShown)
        assertEquals(StageSurface.None, state.surface)
        assertEquals(LaneID.PRIMARY, state.lane.id)
        assertEquals(LanePlayback.Empty, state.lane.playback)
        assertEquals(0, state.lane.generation)
        assertNull(state.current)
        assertTrue(stage.effects.isEmpty())
    }

    @Test
    fun testStageContractIgnoresCommandsUntilAgeConfirmedThenLoadsOnce() {
        val stage = FakeStage()
        stage.seed(stage.state.value.copy(startupNotices = listOf("口味读失败", "收藏读失败")))
        stage.dispatch(StageIntent.SelectSource(FeedSource.popular))
        stage.dispatch(StageIntent.ToggleMute)
        stage.dispatch(StageIntent.SubmitSearch)
        stage.dispatch(StageIntent.Retry)
        assertEquals(FeedSource.guess, stage.state.value.source)
        assertTrue(stage.state.value.muted)
        assertTrue(stage.effects.isEmpty())

        stage.dispatch(StageIntent.ConfirmAge)
        val opened = stage.state.value
        assertTrue(opened.ageConfirmed)
        assertTrue(opened.hintVisible)
        assertEquals("口味读失败；收藏读失败", opened.toast)
        assertTrue(opened.startupNotices.isEmpty())
        assertEquals(listOf(StageEffect.CatalogReload), stage.effects)
        assertTrue(opened.loading)
        assertTrue(opened.catalogClaimed)

        stage.dispatch(StageIntent.ConfirmAge)
        assertEquals(listOf(StageEffect.CatalogReload), stage.effects)

        stage.dispatch(StageIntent.Quit)
        assertEquals(StageEffect.Quit, stage.effects.last())
    }

    @Test
    fun testStageContractSourceRollbackAndFilters() {
        val stage = opened()
        stage.installCatalog(listOf(room("alice"), room("bob", viewers = 3)))
        assertEquals("alice", stage.state.value.currentUsername)
        assertEquals(StageEffect.Play("alice"), stage.effects.last())
        assertEquals(FeedSource.guess, stage.state.value.loadedSource)
        stage.clearEffects()

        stage.dispatch(StageIntent.SelectSource(FeedSource.fresh))
        assertEquals(FeedSource.fresh, stage.state.value.source)
        assertTrue(stage.state.value.loading)
        stage.installCatalog(emptyList(), error = "目录请求失败（500）")
        assertEquals(FeedSource.guess, stage.state.value.source)
        assertEquals("目录请求失败（500）", stage.state.value.toast)
        assertEquals("alice", stage.state.value.currentUsername)
        assertFalse(stage.state.value.loading)

        stage.clearEffects()
        stage.dispatch(StageIntent.ChooseGender(vela.core.base.model.Gender.female))
        stage.dispatch(StageIntent.ChooseGender(vela.core.base.model.Gender.female))
        assertEquals(listOf(StageEffect.CatalogReload), stage.effects)
        stage.dispatch(StageIntent.SetPublicOnly(false))
        assertFalse(stage.state.value.publicOnly)
    }

    @Test
    fun testStageContractMuteAndVolumeFollowAudioRules() {
        val stage = opened()
        stage.dispatch(StageIntent.SetVolume(0.0))
        assertEquals(0.0, stage.state.value.volume, 0.0)
        assertTrue(stage.state.value.muted)
        stage.dispatch(StageIntent.ToggleMute)
        assertFalse(stage.state.value.muted)
        assertEquals(StageRules.RESTORE_VOLUME, stage.state.value.volume, 0.0)
        stage.dispatch(StageIntent.ToggleMute)
        assertTrue(stage.state.value.muted)
        assertEquals(StageRules.RESTORE_VOLUME, stage.state.value.volume, 0.0)
        stage.dispatch(StageIntent.SetVolume(2.0))
        assertEquals(1.0, stage.state.value.volume, 0.0)
        assertFalse(stage.state.value.muted)
        stage.dispatch(StageIntent.SetVolume(-1.0))
        assertEquals(0.0, stage.state.value.volume, 0.0)
        assertTrue(stage.state.value.muted)
        stage.dispatch(StageIntent.SetVolume(0.4))
        stage.dispatch(StageIntent.ToggleMute)
        assertTrue(stage.state.value.muted)
        stage.dispatch(StageIntent.ToggleMute)
        assertEquals(0.4, stage.state.value.volume, 0.0)
        assertFalse(stage.state.value.muted)
    }

    @Test
    fun testStageContractSearchKeepsCurrentThenSubmitsRemote() {
        val stage = opened()
        stage.installCatalog(listOf(room("alice", tags = listOf("synth")), room("bob")))
        stage.clearEffects()
        stage.dispatch(StageIntent.SetSearchText("bob"))
        assertEquals("alice", stage.state.value.currentUsername)
        assertEquals("alice", stage.state.value.retainedUsername)
        assertEquals(listOf("alice", "bob"), stage.state.value.rooms.map { it.username })
        assertEquals(1, stage.state.value.localMatches)

        stage.dispatch(StageIntent.SetSearchText("zzz"))
        assertEquals(listOf("alice"), stage.state.value.rooms.map { it.username })
        assertEquals(0, stage.state.value.localMatches)
        stage.dispatch(StageIntent.SubmitSearch)
        assertEquals(listOf(StageEffect.SiteSearch("zzz")), stage.effects)
        assertTrue(stage.state.value.loading)
        assertNull(stage.state.value.siteSearch)

        stage.deliverSiteSearch("zzz", listOf(room("zed")))
        assertEquals("zzz", stage.state.value.siteSearch)
        assertEquals("zed", stage.state.value.currentUsername)
        assertFalse(stage.state.value.loading)
        assertEquals(StageEffect.Play("zed"), stage.effects.last())

        stage.clearEffects()
        stage.dispatch(StageIntent.SetSearchText("nope"))
        assertEquals("zzz", stage.state.value.siteSearch)
        assertEquals(listOf("zed"), stage.state.value.rooms.map { it.username })
        assertTrue(stage.effects.isEmpty())

        stage.dispatch(StageIntent.SetSearchText("  "))
        assertNull(stage.state.value.siteSearch)
        assertEquals(listOf(StageEffect.ExitSiteSearch, StageEffect.CatalogReload), stage.effects)
        assertEquals(listOf("alice", "bob"), stage.state.value.rooms.map { it.username })
    }

    @Test
    fun testStageContractSubmitFocusesLocalAndMissGoesDirect() {
        val stage = opened()
        stage.installCatalog(listOf(room("alice", tags = listOf("hello")), room("bob")))
        stage.clearEffects()
        stage.dispatch(StageIntent.ShowSurface(StageSurface.Filters))
        stage.dispatch(StageIntent.SetSearchText("bob"))
        stage.dispatch(StageIntent.SubmitSearch)
        assertEquals("bob", stage.state.value.currentUsername)
        assertEquals(StageSurface.None, stage.state.value.surface)
        assertTrue(stage.effects.none { it is StageEffect.SiteSearch })
        assertEquals(StageEffect.Play("bob"), stage.effects.last())

        stage.clearEffects()
        stage.dispatch(StageIntent.SetSearchText("hello"))
        stage.dispatch(StageIntent.SubmitSearch)
        assertTrue(stage.effects.none { it is StageEffect.SiteSearch })
        assertEquals("hello", stage.state.value.currentUsername)

        stage.dispatch(StageIntent.BlockCurrent(stage.target()))
        stage.clearEffects()
        stage.dispatch(StageIntent.SetSearchText("hello"))
        stage.dispatch(StageIntent.SubmitSearch)
        assertEquals("hello 在屏蔽列表里", stage.state.value.toast)

        stage.clearEffects()
        stage.dispatch(StageIntent.SetSearchText("ghost"))
        stage.dispatch(StageIntent.SubmitSearch)
        stage.deliverSiteSearchMiss("ghost", failed = false)
        assertEquals("ghost", stage.state.value.currentUsername)
        assertEquals(StageEffect.Play("ghost"), stage.effects.last())
    }

    @Test
    fun testStageContractFavoriteBlockAndUndo() {
        val stage = opened()
        stage.installCatalog(listOf(room("alice"), room("bob")))
        stage.clearEffects()
        val alice = stage.target()!!
        stage.dispatch(StageIntent.ToggleFavorite(null))
        assertFalse(stage.state.value.curation.isFavorite("alice"))
        stage.dispatch(StageIntent.ToggleFavorite(alice))
        assertTrue(stage.state.value.currentIsFavorite)
        assertTrue(stage.state.value.favoritesBoard.online.any { it.username == "alice" })
        assertNull(stage.state.value.toast)

        stage.dispatch(StageIntent.ToggleFavorite(stage.target()))
        assertFalse(stage.state.value.curation.isFavorite("alice"))
        assertEquals("已取消收藏 alice", stage.state.value.toast)
        assertEquals("alice", stage.state.value.toastUndoName)
        stage.dispatch(StageIntent.UndoToast)
        assertTrue(stage.state.value.curation.isFavorite("alice"))
        assertNull(stage.state.value.toast)

        stage.dispatch(StageIntent.TogglePause(null))
        assertTrue(stage.state.value.lane.manuallyPaused)
        stage.dispatch(StageIntent.BlockCurrent(stage.target()))
        assertFalse(stage.state.value.lane.manuallyPaused)
        assertEquals("bob", stage.state.value.currentUsername)
        assertTrue(stage.state.value.rooms.none { it.username == "alice" })
        assertTrue(stage.state.value.toast!!.contains("已屏蔽"))
        stage.dispatch(StageIntent.BlockCurrent(stage.target()))
        assertEquals("目录里的房间都在屏蔽名单里", blockedOnly(stage).emptyMessage())
    }

    @Test
    fun testStageContractStepClampsClearsPauseAndHint() {
        val stage = opened()
        stage.installCatalog(listOf(room("a"), room("b"), room("c")))
        stage.dispatch(StageIntent.TogglePause(null))
        stage.clearEffects()
        assertTrue(stage.state.value.hintVisible)
        stage.dispatch(StageIntent.Step(0))
        assertEquals("a", stage.state.value.currentUsername)
        assertTrue(stage.state.value.lane.manuallyPaused)
        assertTrue(stage.state.value.hintVisible)
        stage.dispatch(StageIntent.Step(1))
        assertEquals("b", stage.state.value.currentUsername)
        assertFalse(stage.state.value.lane.manuallyPaused)
        assertFalse(stage.state.value.hintVisible)
        assertTrue(stage.state.value.hintSeen)
        assertEquals(listOf(StageEffect.Play("b")), stage.effects)
        stage.dispatch(StageIntent.Step(50))
        assertEquals("c", stage.state.value.currentUsername)
        val end = stage.state.value
        stage.dispatch(StageIntent.Step(1))
        assertEquals(end.currentUsername, stage.state.value.currentUsername)
        assertEquals(end.lane.generation, stage.state.value.lane.generation)
        stage.dispatch(StageIntent.Step(-1))
        assertEquals("b", stage.state.value.currentUsername)
        assertEquals(listOf("a", "b", "c"), stage.state.value.pagerRooms().map { it.username })
    }

    @Test
    fun testStageContractStaleTargetAndSecondaryLaneAreIgnored() {
        val stage = opened()
        stage.installCatalog(listOf(room("a"), room("b")))
        val stale = stage.target()!!
        stage.dispatch(StageIntent.Step(1))
        stage.dispatch(StageIntent.ToggleFavorite(stale))
        assertFalse(stage.state.value.curation.isFavorite("a"))
        assertFalse(stage.state.value.curation.isFavorite("b"))
        stage.dispatch(StageIntent.TogglePause(RoomTarget(LaneID.SECONDARY, "b", stage.state.value.lane.generation)))
        assertFalse(stage.state.value.lane.manuallyPaused)
        val before = stage.state.value.currentUsername
        stage.dispatch(StageIntent.Step(1, LaneID.SECONDARY))
        assertEquals(before, stage.state.value.currentUsername)
        assertFalse(stage.state.value.accepts(stale))
        assertTrue(stage.state.value.accepts(stage.target()))
    }

    @Test
    fun testStageContractPagerShowsOnlyNeighbors() {
        val stage = opened()
        val names = listOf("a", "b", "c", "d", "e")
        stage.installCatalog(names.map { room(it) })
        assertEquals(listOf("a", "b"), stage.state.value.pagerRooms().map { it.username })
        stage.dispatch(StageIntent.Step(2))
        assertEquals(listOf("b", "c", "d"), stage.state.value.pagerRooms().map { it.username })
        stage.dispatch(StageIntent.Step(10))
        assertEquals(listOf("d", "e"), stage.state.value.pagerRooms().map { it.username })
        assertEquals(0, landingIndex(0, -3, names, emptySet()))
        assertEquals(4, landingIndex(4, 3, names, emptySet()))
        assertEquals(0, landingIndex(2, -1, names, setOf("b", "c")))
    }

    @Test
    fun testStageContractEmptyMessageSurfacesAndChrome() {
        val stage = opened()
        stage.dispatch(StageIntent.SelectSource(FeedSource.favorites))
        stage.installCatalog(emptyList())
        assertEquals("还没有收藏，在房间里按 ⌘D 收藏", stage.state.value.emptyMessage())
        stage.dispatch(StageIntent.ChromeIdle)
        assertFalse(stage.state.value.chromeVisible)
        stage.dispatch(StageIntent.ShowSurface(StageSurface.Curation))
        assertTrue(stage.state.value.chromeShown)
        assertTrue(stage.state.value.isInteracting)
        stage.dispatch(StageIntent.ShowSurface(StageSurface.Taste))
        assertEquals(StageSurface.Taste, stage.state.value.surface)
        stage.dispatch(StageIntent.DismissSurfaces)
        assertEquals(StageSurface.None, stage.state.value.surface)
        stage.dispatch(StageIntent.ShowSurface(StageSurface.Challenge))
        stage.dispatch(StageIntent.DismissSurfaces)
        assertEquals(StageSurface.Challenge, stage.state.value.surface)
        stage.dispatch(StageIntent.NoteActivity)
        assertTrue(stage.state.value.chromeVisible)
        assertFalse(stage.state.value.playbackAllowed)
    }

    @Test
    fun testStageContractChallengeGateSuppressesRepeat() {
        val stage = opened()
        stage.installCatalog(listOf(room("alice")))
        stage.clearEffects()
        stage.now = Instant.parse("2026-01-01T00:00:00Z")
        stage.noteChallenge(ChallengeResume.Reload, stage.now)
        assertEquals(StageSurface.Challenge, stage.state.value.surface)
        assertEquals(StageRules.CATALOG_NEEDS_CHALLENGE, stage.state.value.catalogError)
        assertEquals(ChallengeResume.Reload, stage.state.value.challengeResume)
        stage.dispatch(StageIntent.RetryAfterChallenge)
        assertEquals(StageSurface.None, stage.state.value.surface)
        assertNull(stage.state.value.challengeResume)
        assertEquals(StageEffect.CatalogReload, stage.effects.last())
        assertTrue(stage.state.value.loading)

        stage.installCatalog(listOf(room("alice")))
        stage.noteChallenge(ChallengeResume.Reload, stage.now)
        stage.dispatch(StageIntent.DismissChallenge)
        assertEquals(StageSurface.None, stage.state.value.surface)
        assertEquals(ChallengeResume.Reload, stage.state.value.challengeResume)
        stage.noteChallenge(ChallengeResume.Reload, stage.now.plusSeconds(1))
        assertEquals(StageSurface.None, stage.state.value.surface)
        assertEquals("站点连续要求确认，稍后再试", stage.state.value.toast)
    }

    @Test
    fun testStageContractTasteSheetEditsAndNotInterested() {
        val stage = opened()
        stage.installCatalog(listOf(room("alice", tags = listOf("synth")), room("bob"), room("cara")))
        stage.seed(
            stage.state.value.copy(
                taste = TastePanelState(
                    liked = listOf(TasteRowState("t:synth", "synth", 0.8, 2, false)),
                    tagCount = 1,
                    suggestions = listOf("other"),
                ),
            ),
        )
        stage.dispatch(StageIntent.VetoTaste("t:synth"))
        assertTrue(stage.state.value.taste.liked.isEmpty())
        assertEquals("t:synth", stage.state.value.taste.disliked.single().key)
        assertTrue(stage.state.value.taste.disliked.single().vetoed)
        assertTrue(stage.state.value.rooms.none { it.username == "alice" })
        assertEquals("bob", stage.state.value.currentUsername)

        stage.dispatch(StageIntent.RestoreTaste("t:synth"))
        assertTrue(stage.state.value.rooms.any { it.username == "alice" })
        assertEquals("bob", stage.state.value.currentUsername)
        stage.dispatch(StageIntent.BoostTaste("t:other"))
        assertTrue(stage.state.value.taste.liked.any { it.key == "t:other" })
        assertFalse(stage.state.value.taste.suggestions.contains("other"))

        stage.dispatch(StageIntent.SetLearningPaused(true))
        assertTrue(stage.state.value.taste.paused)
        assertEquals("已暂停学习，排序仍按现有口味", stage.state.value.toast)
        stage.clearEffects()
        stage.dispatch(StageIntent.MarkNotInterested(stage.target()))
        assertEquals("cara", stage.state.value.currentUsername)
        assertEquals("已跳过，学习已暂停所以没有记口味", stage.state.value.toast)
        assertEquals(StageEffect.Play("cara"), stage.effects.last())

        val favorites = stage.state.value.curation
        stage.dispatch(StageIntent.ClearPreferences)
        assertEquals(favorites, stage.state.value.curation)
        assertTrue(stage.state.value.taste.liked.isEmpty())
        assertEquals("已清除口味与观看记录，收藏与屏蔽保留", stage.state.value.toast)
    }

    @Test
    fun testStageContractFavoritesBoardAddImportExport() {
        val stage = opened()
        stage.installCatalog(listOf(room("alice", viewers = 4), room("bob", viewers = 9)))
        stage.dispatch(StageIntent.AddFavorite("a"))
        assertEquals("用户名只含字母、数字、下划线，2 到 30 位", stage.state.value.favoriteDraftError)
        stage.dispatch(StageIntent.AddFavorite("bob"))
        assertNull(stage.state.value.favoriteDraftError)
        assertTrue(stage.state.value.curation.isFavorite("bob"))
        assertEquals(StageEffect.CheckFavorites, stage.effects.last())
        assertEquals("bob", stage.state.value.favoritesBoard.online.single().username)
        stage.dispatch(StageIntent.AddFavorite("bob"))
        assertEquals("已经在收藏里了", stage.state.value.favoriteDraftError)

        stage.dispatch(StageIntent.RefreshFavorites)
        assertTrue(stage.state.value.favoritesChecking)
        assertEquals(StageEffect.RefreshFavorites, stage.effects.last())
        stage.dispatch(StageIntent.RefreshFavorites)
        assertEquals(1, stage.effects.count { it == StageEffect.RefreshFavorites })
        stage.finishFavoriteCheck()
        assertFalse(stage.state.value.favoritesChecking)

        stage.clearEffects()
        stage.dispatch(StageIntent.ExportFavorites)
        val exported = stage.effects.filterIsInstance<StageEffect.ExportedFavorites>().single().bytes
        val other = opened()
        other.installCatalog(listOf(room("bob")))
        other.clearEffects()
        other.dispatch(StageIntent.ImportFavorites(exported))
        assertTrue(other.state.value.curation.isFavorite("bob"))
        assertTrue(other.state.value.toast!!.startsWith("已导入 1 个收藏"))
        other.dispatch(StageIntent.ImportFavorites("not-json".toByteArray()))
        assertEquals("无法识别这个文件", other.state.value.toast)

        stage.dispatch(StageIntent.PlayFavorite("bob"))
        assertEquals("bob", stage.state.value.currentUsername)
        assertEquals(StageSurface.None, stage.state.value.surface)
    }

    @Test
    fun testStageContractPlaybackNotesAndSuggestBanner() {
        val stage = opened()
        stage.installCatalog(listOf(room("alice")))
        stage.noteReady("alice")
        stage.notePlaying("alice")
        assertEquals(LanePlayback.Playing, stage.state.value.lane.playback)
        assertEquals("alice", stage.state.value.lane.readyUsername)
        assertTrue(stage.state.value.playbackAllowed)
        stage.presentFavoriteSuggest("alice", "Alice")
        assertEquals("alice", stage.state.value.favoriteSuggest?.username)
        stage.dispatch(StageIntent.AcceptFavoriteSuggest)
        assertTrue(stage.state.value.curation.isFavorite("alice"))
        assertNull(stage.state.value.favoriteSuggest)
        stage.presentFavoriteSuggest("bob", "Bob")
        stage.dispatch(StageIntent.CloseFavoriteSuggest)
        assertNull(stage.state.value.favoriteSuggest)
        stage.presentFavoriteSuggest("cara", "Cara")
        stage.noteFailed(true, "解码失败")
        assertEquals("cara", stage.state.value.favoriteSuggest?.username)
        assertEquals(LanePlayback.Failed, stage.state.value.lane.playback)
        assertEquals("解码失败", stage.state.value.lane.playbackNote)
        assertTrue(stage.state.value.chromeVisible)
        assertFalse(stage.state.value.playbackAllowed)
        stage.noteFailed(false)
        assertNull(stage.state.value.lane.playbackNote)
        assertEquals(LanePlayback.Playing, stage.state.value.lane.playback)
    }
}

private fun opened(): FakeStage {
    val stage = FakeStage()
    stage.dispatch(StageIntent.ConfirmAge)
    stage.clearEffects()
    return stage
}

private fun blockedOnly(stage: FakeStage): StageUiState {
    val state = stage.state.value
    assertTrue(state.rooms.isEmpty())
    assertTrue(state.catalog.isNotEmpty())
    return state
}
