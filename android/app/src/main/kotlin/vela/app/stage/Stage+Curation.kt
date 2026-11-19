package vela.app.stage

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vela.core.base.lane.RoomTarget
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.lifecycle.ChallengeResume
import vela.core.base.site.CatalogFailure
import vela.core.base.site.CatalogPage
import vela.core.base.site.FavoriteStatus
import vela.core.curation.Curation
import vela.core.curation.FavoriteExchange
import vela.core.curation.FavoriteFetcher
import vela.core.curation.FavoriteResolver
import vela.core.curation.KnownCatalog
import vela.core.curation.LastBroadcastFetcher
import vela.core.ledger.SuggestEnd
import vela.core.ledger.SuggestRules
import vela.core.ledger.WatchLedger
import vela.core.base.model.timeIntervalSince1970
import vela.core.selection.LaneSelection

internal data class FavoriteResolution(
    val statuses: Map<String, FavoriteStatus>,
    val challenged: Boolean,
    val incomplete: Boolean = false,
)

internal fun StageViewModel.toggleFavorite(target: RoomTarget?) {
    if (!accepts(target)) return
    val room = feed.current ?: return
    if (curation.isFavorite(room.username)) {
        commitCuration(
            curation.unfavoriting(room.username),
            text.unfavorite(room.displayName),
            toastSeconds = StageRules.UNFAVORITE_TOAST_SECONDS,
            undoFavorite = room.username,
        )
    } else {
        taste = taste.following(room, now(), tagRarity)
        tasteSaver.schedule(taste)
        commitCuration(curation.favoriting(room.username), null)
    }
}

internal fun StageViewModel.blockCurrent(target: RoomTarget?) {
    if (!accepts(target)) return
    val room = feed.current ?: return
    laneFace = laneFace.copy(manuallyPaused = false)
    commitCuration(curation.blocking(room.username), text.blocked(room.displayName))
}

internal fun StageViewModel.undoToast() {
    val name = toastUndoName ?: return
    if (!curation.canRestoreFavorite(name)) return
    cancelToastTimer()
    toast = null
    toastUndoName = null
    commitCuration(curation.restoringFavorite(name), null)
}

internal fun StageViewModel.addFavorite(raw: String) {
    val name = raw.stageTrim()
    if (!Room.isValidUsername(name)) {
        favoriteDraftError = text.favoriteInvalid()
        publish()
        return
    }
    if (curation.isFavorite(name)) {
        favoriteDraftError = text.favoriteExists()
        publish()
        return
    }
    favoriteDraftError = null
    commitCuration(curation.favoriting(name), null)
    if (source != FeedSource.favorites) track { checkFavorites() }
}

internal fun StageViewModel.playFavorite(name: String) {
    val key = Curation.key(name)
    val room = favoriteStatus.status(name)?.room
        ?: feed.catalog.firstOrNull { Curation.key(it.username) == key && it.show.isPlayable }
        ?: return
    cancelSiteSearch()
    surface = StageSurface.None
    updateFeed(intent = LaneSelection.Intent.Reveal(room.username)) { store ->
        store.reveal(room)
    }
    if (siteSearch == null && feed.query.text != searchText) searchText = feed.query.text
    requestPlay()
}

internal fun StageViewModel.exportFavorites() {
    exportedFavorites = FavoriteExchange.encode(curation.favorites)
}

internal fun StageViewModel.importFavorites(bytes: ByteArray) {
    val imported = try {
        FavoriteExchange.decode(bytes)
    } catch (_: FavoriteExchange.Failure.TooLarge) {
        showToast(text.fileLarge())
        return
    } catch (_: FavoriteExchange.Failure) {
        showToast(text.fileBad())
        return
    }
    val merged = curation.importing(imported.names)
    val skipped = if (imported.rejected > 0) text.importSkipped(imported.rejected) else ""
    commitCuration(merged.curation, text.imported(merged.added, skipped))
    if (source != FeedSource.favorites) track { checkFavorites() }
}

internal fun StageViewModel.commitCuration(
    next: Curation,
    message: String?,
    toastSeconds: Double = StageRules.TOAST_SECONDS,
    undoFavorite: String? = null,
) {
    val previous = curation
    curation = next
    favoriteDraftError = null
    curationSaver.schedule(next)
    if (next.favorites != previous.favorites) refreshReasons()
    val before = feed.currentUsername
    if (before != null && next.isBlocked(before)) dwell.discard()
    updateFeed { store ->
        store.setFilters(blocked = next.blockedSet, only = allowedNames(next))
    }
    val prompt = favoriteSuggest
    if (prompt != null && next.isFavorite(prompt.username)) {
        favoriteSuggest = null
        favoriteSuggestJob?.cancel()
    }
    if (feed.currentUsername != before) {
        requestPlay()
        track { loadMoreIfNeeded() }
    }
    if (message != null) showToast(message, toastSeconds, undoFavorite) else publish()
    if (source == FeedSource.favorites && siteSearch == null &&
        next.blocked == previous.blocked && next.favorites != previous.favorites
    ) {
        track { reload() }
    }
}

internal suspend fun StageViewModel.refreshFavorites() {
    if (favoritesChecking) return
    favoriteRetry.reset()
    favoriteStatus = vela.core.curation.FavoriteCache()
    knownCatalog = KnownCatalog()
    if (source == FeedSource.favorites) reload() else checkFavorites()
}

internal suspend fun StageViewModel.checkFavorites() {
    val result = resolveFavorites()
    if (!result.challenged) return
    if (!allowChallengePrompt()) {
        showToast(text.challengeFavorites())
        return
    }
    val next = ChallengeResume.Favorites
    challengeResume = challengeResume?.merging(next) ?: next
    if (surface != StageSurface.Challenge) surface = StageSurface.None
    presentCatalogChallenge()
}

internal suspend fun StageViewModel.refreshFavoritesIfStale() {
    val at = now()
    if (favoritesChecking || !favoriteRetry.allowsAutoCheck(at)) return
    val stale = curation.favorites.any { favoriteStatus.fresh(it, at) == null }
    if (!stale) return
    resolveFavorites()
}

internal suspend fun StageViewModel.refreshLastBroadcastsIfStale() {
    val at = now()
    val offline = curation.favorites.filter { favoriteStatus.status(it) == FavoriteStatus.Offline }
    val pending = lastBroadcasts.pending(offline, at)
    if (lastBroadcastChecking || pending.isEmpty() || !lastBroadcastRetry.allowsAutoCheck(at)) return
    lastBroadcastChecking = true
    val result = LastBroadcastFetcher(http, now = { now() }, backoff = backoff).fetch(pending)
    lastBroadcastChecking = false
    lastBroadcasts = lastBroadcasts.storing(result.found, at).pruned(curation.favorites)
    if (result.challenged || result.found.size < pending.size) {
        lastBroadcastRetry.recordFailure(at)
    } else {
        lastBroadcastRetry.reset()
    }
}

internal suspend fun StageViewModel.resolveFavorites(): FavoriteResolution {
    favoritesChecking = true
    publish()
    val result = favoriteRunner.run { id -> runFavoriteBatch(id) }
    if (!favoriteRunner.isRunning) {
        favoritesChecking = false
        publish()
    }
    noteFavoriteOutcome(result)
    return result
}

private fun StageViewModel.noteFavoriteOutcome(result: FavoriteResolution) {
    val at = now()
    if (result.challenged || result.incomplete) favoriteRetry.recordFailure(at) else favoriteRetry.reset()
}

private suspend fun StageViewModel.runFavoriteBatch(id: Int): FavoriteResolution {
    val plan = FavoriteResolver.plan(curation.favorites, knownCatalog, favoriteStatus, now())
    val statuses = LinkedHashMap(plan.resolved)
    var challenged = false
    val fetched = if (plan.pending.isEmpty()) {
        emptyMap()
    } else {
        val result = fetchFavoriteStatuses(plan.pending)
        challenged = result.challenged
        statuses.putAll(result.statuses)
        result.statuses
    }
    val incomplete = FavoriteResolver.hasUnresolved(plan.pending, fetched)
    if (!favoriteRunner.isCurrent(id)) {
        return FavoriteResolution(statuses, challenged, incomplete)
    }
    favoriteStatus = favoriteStatus
        .storing(plan.fromCatalog)
        .storing(fetched, now())
        .pruned(curation.favorites)
    return FavoriteResolution(statuses, challenged, incomplete)
}

private suspend fun StageViewModel.fetchFavoriteStatuses(names: List<String>): FavoriteFetchResult {
    return FavoriteFetcher(http, now = { now() }, backoff = backoff).fetch(names)
}

internal suspend fun StageViewModel.fetchFavoritesPage(): CatalogPage {
    val result = resolveFavorites()
    if (result.challenged) throw CatalogFailure.Challenge
    val rooms = FavoriteResolver.onlineRooms(curation.favorites, result.statuses)
    return CatalogPage(rooms = rooms, total = rooms.size, nextOffset = rooms.size)
}

internal fun StageViewModel.acceptFavoriteSuggest() {
    val prompt = favoriteSuggest ?: return
    val room = feed.current ?: return
    if (WatchLedger.key(room.username) != prompt.username) return
    endFavoriteSuggest(SuggestEnd.accepted)
    if (curation.isFavorite(room.username)) return
    taste = taste.following(room, now(), tagRarity)
    tasteSaver.schedule(taste)
    commitCuration(curation.favoriting(room.username), null)
}

internal fun StageViewModel.closeFavoriteSuggest() {
    endFavoriteSuggest(SuggestEnd.closed)
}

internal fun StageViewModel.dismissFavoriteSuggestIfMismatch() {
    val prompt = favoriteSuggest
    val current = feed.currentUsername
    if (prompt == null || current == null || WatchLedger.key(current) != prompt.username) {
        favoriteSuggest = null
        favoriteSuggestJob?.cancel()
    }
}

internal fun StageViewModel.endFavoriteSuggest(end: SuggestEnd) {
    val prompt = favoriteSuggest ?: return
    favoriteSuggest = null
    favoriteSuggestJob?.cancel()
    if (SuggestRules.countsAsAsked(end)) {
        watchLedger = watchLedger.markingSuggested(prompt.username, now().timeIntervalSince1970())
        watchLedgerSaver.schedule(watchLedger)
    } else if (end != SuggestEnd.withdrawn) {
        suggestSnoozed.add(prompt.username)
    }
}

internal fun StageViewModel.checkFavoriteSuggest() {
    if (taste.paused || dwell.isDiscarded || favoriteSuggest != null) return
    val room = feed.current ?: return
    if (!dwell.isActive) return
    val key = WatchLedger.key(room.username)
    if (!SuggestRules.canPresent(pointerCanClick = true, snoozed = key in suggestSnoozed)) return
    val live = dwell.ledgerDwell(now())
    val start = dwell.startedAt?.timeIntervalSince1970()
    if (!watchLedger.shouldSuggest(
            username = room.username,
            favorited = curation.isFavorite(room.username),
            blocked = curation.isBlocked(room.username),
            liveSeconds = live,
            liveStart = start,
        )
    ) {
        return
    }
    presentFavoriteSuggest(room)
}

internal fun StageViewModel.presentFavoriteSuggest(room: Room) {
    favoriteSuggestWakeJob?.cancel()
    favoriteSuggestWakeJob = null
    toast = null
    toastUndoName = null
    val key = WatchLedger.key(room.username)
    val display = room.displayName.ifEmpty { room.username }
    favoriteSuggest = FavoriteSuggest(key, display)
    favoriteSuggestJob?.cancel()
    favoriteSuggestJob = scope.launch {
        delay((SUGGEST_SECONDS * 1000.0).toLong())
        if (favoriteSuggest?.username == key) endFavoriteSuggest(SuggestEnd.timedOut)
        publish()
    }
    publish()
}

internal fun StageViewModel.scheduleFavoriteSuggestWake() {
    favoriteSuggestWakeJob?.cancel()
    favoriteSuggestWakeJob = null
    if (taste.paused || dwell.isDiscarded || favoriteSuggest != null) return
    val room = feed.current ?: return
    if (!dwell.isActive) return
    val spell = dwell.startedAt ?: return
    val at = now()
    val live = dwell.ledgerDwell(at)
    val favorited = curation.isFavorite(room.username)
    val blocked = curation.isBlocked(room.username)
    if (watchLedger.shouldSuggest(room.username, favorited, blocked, live, spell.timeIntervalSince1970())) return
    val wait = watchLedger.secondsUntilNextSuggestCheck(
        username = room.username,
        favorited = favorited,
        blocked = blocked,
        liveSeconds = live,
        liveStart = spell.timeIntervalSince1970(),
    ) ?: return
    if (wait <= 0.05) return
    val name = WatchLedger.key(room.username)
    favoriteSuggestWakeJob = scope.launch {
        delay(((wait + 0.05) * 1000.0).toLong())
        if (WatchLedger.key(feed.currentUsername.orEmpty()) != name) return@launch
        checkFavoriteSuggest()
        scheduleFavoriteSuggestWake()
    }
}

private const val SUGGEST_SECONDS: Double = 6.0

private typealias FavoriteFetchResult = vela.core.curation.FavoriteFetchResult
