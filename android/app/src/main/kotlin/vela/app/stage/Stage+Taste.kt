package vela.app.stage

import android.util.Log
import vela.app.sync.rejectWhileSyncJoining
import vela.app.sync.syncedClearVetoes
import vela.app.sync.syncedUnveto
import vela.app.sync.syncedVeto
import vela.core.base.lane.RoomTarget
import vela.core.base.model.FeedSource
import vela.core.recommend.Recommender
import vela.core.selection.LaneSelection
import vela.core.taste.HotTags
import vela.core.taste.Taste

internal fun StageViewModel.reasonFor(room: vela.core.base.model.Room): String? {
    if (loadedSource != FeedSource.guess || siteSearch != null) return null
    return reasons[room.username.stageLower()]
}

internal suspend fun StageViewModel.loadHotTagsIfStale() {
    if (hotTagsLoading || !hotTags.needsFetch(now())) return
    hotTagsLoading = true
    try {
        val result = http.send(HotTags.request())
        val tags = HotTags.decode(result.status, result.data)
        hotTags = hotTags.storing(tags, now())
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(LOG, "hot tags failed ${error.javaClass.simpleName}")
        hotTags = hotTags.recordingFailure(now())
    }
    hotTagsLoading = false
    publish()
}

internal fun StageViewModel.commitTaste(next: Taste) {
    val previous = taste
    taste = next
    tasteSaver.schedule(next)
    dwell.setLedgerExcluded(next.paused, now())
    syncHiddenFilter(previous)
    refreshReasons()
}

internal fun StageViewModel.syncHiddenFilter(previous: Taste) {
    if (previous.vetoedKeys == taste.vetoedKeys || source != FeedSource.guess || loadedSource != FeedSource.guess) return
    val room = feed.current
    if (room != null && taste.isVetoed(room)) dwell.discard()
    val before = feed.currentUsername
    updateFeed { store ->
        store.setFilters(curation.blockedSet, allowedNames(curation), taste.vetoedKeys)
    }
    if (feed.currentUsername != before) {
        requestPlay()
        track { loadMoreIfNeeded() }
    }
}

internal fun StageViewModel.refreshReasons() {
    if (loadedSource != FeedSource.guess) return
    val at = now()
    val favorites = curation.favoriteSet
    val current = taste
    for (room in feed.rooms.drop(feed.index)) {
        reasons[room.username.stageLower()] = Recommender.reason(room, current, favorites, at)
    }
}

internal fun StageViewModel.commitTasteAndRearrange(next: Taste) {
    commitTaste(next)
    if (loadedSource != FeedSource.guess || source != FeedSource.guess || siteSearch != null) return
    val at = now()
    val favorites = curation.favoriteSet
    val seed = java.util.concurrent.ThreadLocalRandom.current().nextLong().toULong()
    val current = taste
    val signals = rankSignals()
    updateFeed(intent = LaneSelection.Intent.Merge) { store ->
        store.reorderAfterCurrent { rooms ->
            Recommender.arrange(rooms, FeedSource.guess, current, at, seed, favorites, signals)
        }
    }
    track { loadMoreIfNeeded() }
}

internal fun StageViewModel.boostTaste(key: String) {
    if (key.isEmpty()) return
    if (!syncedUnveto(key)) return
    commitTasteAndRearrange(taste.boosting(key, now()))
}

internal fun StageViewModel.vetoTaste(key: String) {
    if (key.isEmpty()) return
    if (!syncedVeto(key)) return
    commitTasteAndRearrange(taste.vetoing(key, now()))
}

internal fun StageViewModel.restoreTaste(key: String) {
    if (key.isEmpty()) return
    if (!syncedUnveto(key)) return
    commitTasteAndRearrange(taste.restoring(key, now()))
}

internal fun StageViewModel.removeTaste(key: String) {
    if (!syncedUnveto(key)) return
    commitTasteAndRearrange(taste.removing(key))
}

internal fun StageViewModel.setLearningPaused(paused: Boolean) {
    if (rejectWhileSyncJoining()) return
    commitTaste(taste.settingPaused(paused))
    if (paused) {
        favoriteSuggest = null
        favoriteSuggestJob?.cancel()
        favoriteSuggestWakeJob?.cancel()
    } else {
        scheduleFavoriteSuggestWake()
    }
    showToast(if (paused) text.learningOff() else text.learningOn())
}

internal fun StageViewModel.resetTasteProfile() {
    if (!syncedClearVetoes()) return
    commitTaste(taste.resettingProfile())
    showToast(text.tasteReset())
}

internal fun StageViewModel.clearAllPreferences() {
    if (!syncedClearVetoes()) return
    dwell.discard()
    commitTaste(taste.erased())
    tasteSaver.flush()
    watchLedger = watchLedger.cleared()
    watchLedgerSaver.flush()
    watchLedgerStore.erase()
    favoriteSuggest = null
    favoriteSuggestJob?.cancel()
    favoriteSuggestWakeJob?.cancel()
    showToast(text.cleared())
}

internal fun StageViewModel.markNotInterested(target: RoomTarget?) {
    if (rejectWhileSyncJoining()) return
    if (!accepts(target)) return
    val room = feed.current ?: return
    commitTaste(taste.rejecting(room, dwellSource, now()))
    dwell.discard()
    showToast(if (taste.paused) text.skipPaused() else text.skip())
    move(1, userInitiated = true)
}
