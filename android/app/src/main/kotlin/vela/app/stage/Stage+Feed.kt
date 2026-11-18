package vela.app.stage

import vela.core.base.lane.LaneEndReason
import vela.core.base.lane.LaneID
import vela.core.base.lane.PlaybackLaneRole
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vela.core.base.model.timeIntervalSince1970
import vela.core.base.site.CatalogPage
import vela.core.feed.FeedStore
import vela.core.feed.MoveRules
import vela.core.ledger.ListOrigin
import vela.core.feed.SiteSearch
import vela.core.ledger.DwellTracker
import vela.core.recommend.Recommender
import vela.core.selection.LaneSelection
import vela.core.selection.LaneSettlement

/**
 * 改列表的唯一入口。先在副本上做完 FeedStore 的变更，再交给 [LaneSelection.resolve]。
 * 单窗的 [StageViewModel.secondaryCursor] 保持为空，主路因此与 FeedStore 自己的选择一致。
 * 房间或来源变了，用 [LaneSettlement] 结算上一段停留。
 */
internal fun StageViewModel.updateFeed(
    installing: FeedSource? = null,
    intent: LaneSelection.Intent = LaneSelection.Intent.Filter,
    body: (FeedStore) -> Unit,
) {
    val copy = feed.copy()
    val before = copy.current?.username
    val snapshot = LaneSelection.Snapshot(
        rooms = copy.rooms.map { it.username },
        primary = primaryCursor.copy(),
        secondary = secondaryCursor?.copy(),
        focus = LaneID.PRIMARY,
    )
    body(copy)
    val outcome = LaneSelection.resolveFeedChange(
        before = snapshot,
        afterRooms = copy.rooms.map { it.username },
        proposedPrimary = copy.current?.username,
        intent = intent,
    )
    primaryCursor = outcome.primary.copy()
    secondaryCursor = outcome.secondary?.copy()
    copy.select(outcome.primary.username)
    feed = copy
    val sourceChanged = installing != null && installing != loadedSource
    val endReason = when (intent) {
        is LaneSelection.Intent.Move ->
            if (moveUserInitiated) LaneEndReason.SWIPE else LaneEndReason.AUTO_SKIP
        else -> LaneEndReason.SOURCE_CHANGE
    }
    val userInitiated = intent is LaneSelection.Intent.Move && moveUserInitiated
    loadedSource = ListOrigin.dwell(loadedSource, installing)
    if (DwellTracker.shouldSettle(before, feed.current?.username, sourceChanged)) {
        currentRoomChanged(endReason, userInitiated)
    }
    publish()
}

internal fun StageViewModel.arranged(page: CatalogPage): CatalogPage {
    val rooms = Recommender.arrange(
        rooms = page.rooms,
        source = source,
        taste = taste,
        now = now(),
        seed = randomSeed(),
        favorites = curation.favoriteSet,
        signals = rankSignals(),
    )
    return CatalogPage(rooms = rooms, total = page.total, nextOffset = page.nextOffset)
}

internal fun StageViewModel.move(delta: Int, userInitiated: Boolean): Boolean {
    if (delta == 0 || feed.rooms.isEmpty()) return false
    val before = feed.currentUsername
    moveUserInitiated = userInitiated
    updateFeed(intent = LaneSelection.Intent.Move(LaneID.PRIMARY, delta)) { store ->
        store.step(delta)
    }
    moveUserInitiated = false
    if (feed.currentUsername == before) return false
    laneFace = laneFace.copy(
        manuallyPaused = MoveRules.pausedAfterMove(laneFace.manuallyPaused, userInitiated),
    )
    if (hintVisible) {
        hintVisible = false
        hintSeen = true
        settings.hintSeen = true
    }
    requestPlay()
    track { loadMoreIfNeeded() }
    return true
}

internal fun StageViewModel.currentRoomChanged(endReason: LaneEndReason, userInitiated: Boolean) {
    relatedJob?.cancel()
    relatedJob = null
    val settled = dwell.switchTo(feed.current, now())
    if (settled != null) recordSettlement(settled, endReason, userInitiated)
    dwellSource = SiteSearch.dwellSource(loadedSource, siteSearch != null)
    suggestSnoozed.clear()
    dwell.setLedgerExcluded(taste.paused, now())
    dismissFavoriteSuggestIfMismatch()
    updateDwellActive()
    scheduleRelated()
}

/**
 * 单窗全程算焦点。学习与每日统计交给 [LaneSettlement]；账本记加权秒。
 * 暂停学习被排除的那段不进账本，口味仍按整段停留。
 */
internal fun StageViewModel.recordSettlement(
    settled: DwellTracker.Settled,
    endReason: LaneEndReason,
    userInitiated: Boolean,
) {
    val at = now()
    val settlement = LaneSettlement.settle(
        lane = PlaybackLaneRole.PRIMARY,
        username = settled.room.username,
        source = dwellSource,
        spans = listOf(LaneSettlement.Span(seconds = settled.dwell, focused = true)),
        endReason = endReason,
        userInitiated = userInitiated,
    )
    taste = settlement.recording(taste, settled.room, at, tagRarity)
    tasteSaver.schedule(taste)
    val start = settled.start
    if (settled.ledgerSeconds <= 0.0 || start == null) return
    val ledgerSettlement = if (settled.ledgerSeconds == settled.dwell) {
        settlement
    } else {
        LaneSettlement.settle(
            lane = PlaybackLaneRole.PRIMARY,
            username = settled.room.username,
            source = dwellSource,
            spans = listOf(LaneSettlement.Span(seconds = settled.ledgerSeconds, focused = true)),
            endReason = endReason,
            userInitiated = userInitiated,
        )
    }
    watchLedger = ledgerSettlement.recording(
        ledger = watchLedger,
        start = start.timeIntervalSince1970(),
        now = at.timeIntervalSince1970(),
    )
    watchLedgerSaver.schedule(watchLedger)
}

internal fun StageViewModel.updateDwellActive() {
    val name = feed.currentUsername
    val playing = laneFace.playingUsername
    val active = !laneFace.manuallyPaused &&
        laneFace.playback != LanePlayback.Failed &&
        name != null &&
        playing != null &&
        playing.equals(name, ignoreCase = true)
    val was = dwell.isActive
    dwell.setActive(active, now())
    if (active) {
        if (was != active) scheduleRelated()
        checkFavoriteSuggest()
        scheduleFavoriteSuggestWake()
    } else if (was) {
        relatedJob?.cancel()
        relatedJob = null
        favoriteSuggestWakeJob?.cancel()
        favoriteSuggestWakeJob = null
    }
}

internal fun StageViewModel.scheduleRelated() {
    relatedJob?.cancel()
    relatedJob = null
    val username = feed.currentUsername ?: return
    when (val plan = dwell.relatedPlan(username, now())) {
        DwellTracker.RelatedPlan.None, DwellTracker.RelatedPlan.Idle -> Unit
        DwellTracker.RelatedPlan.Due -> {
            dwell.markRelatedDone()
            promoteRelated(username)
        }
        is DwellTracker.RelatedPlan.Wait -> {
            val seconds = plan.seconds
            relatedJob = scope.launch {
                delay(((seconds + 0.05) * 1000.0).toLong())
                if (feed.currentUsername.equals(username, ignoreCase = true)) scheduleRelated()
            }
        }
    }
}

internal fun StageViewModel.promoteRelated(username: String) {
    if (source != FeedSource.guess) return
    val room = feed.current ?: return
    if (!room.username.equals(username, ignoreCase = true)) return
    val ahead = feed.rooms.drop(feed.index + 1)
    val names = Recommender.related(room, ahead, taste)
    if (names.isEmpty()) return
    updateFeed(intent = LaneSelection.Intent.Merge) { store ->
        store.promoteAfterCurrent(names)
    }
}

private fun randomSeed(): ULong = java.util.concurrent.ThreadLocalRandom.current().nextLong().toULong()
