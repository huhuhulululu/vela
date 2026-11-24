package vela.app.stage

import android.util.Log
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import vela.app.infra.Endpoints
import vela.app.lane.PlaybackLane
import vela.app.lane.ThermalMonitor
import vela.core.base.lane.LaneCursor
import vela.core.base.lane.LaneEndReason
import vela.core.base.lane.LaneID
import vela.core.base.lane.PlaybackLaneRole
import vela.core.base.lane.RoomTarget
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.timeIntervalSince1970
import vela.core.base.play.StreamRendition
import vela.core.feed.FeedStore
import vela.core.feed.MoveRules
import vela.core.feed.SiteSearch
import vela.core.hls.FailureCode
import vela.core.hls.LaneQuality
import vela.core.hls.PhoneThermalState
import vela.core.hls.PlaybackFailureClassifier
import vela.core.ledger.DwellTracker
import vela.core.ledger.ListOrigin
import vela.core.lifecycle.PhoneLaneActivity
import vela.core.selection.LaneSelection
import vela.core.selection.LaneSettlement

/**
 * 竖屏多路。安卓这一包实现到 2 路：选房、焦点、热降档、副路资源降级、双路结算。
 * 第三路只留界面槽位（[LaneID.TERTIARY]），不创建播放器。
 *
 * 画质上限直接用 [LaneQuality]：未发热不封顶；serious 及以上按画面实际显示高度降档，
 * 下限 896 kbps；回到 fair 及以下连续满 60 秒回升。core 里没有按档位逐级下降的接口。
 */

/** 一路停留里焦点切来切去的片段。单窗不读它，结算仍按整段都是焦点。 */
internal class FocusSegments {
    private val pieces = ArrayList<LaneSettlement.Span>()
    private var since: Instant? = null
    private var focused: Boolean = true

    fun align(now: Instant, active: Boolean, focused: Boolean) {
        val open = since
        if (open != null && (!active || this.focused != focused)) {
            val seconds = now.timeIntervalSince1970() - open.timeIntervalSince1970()
            if (seconds > 0.0) pieces.add(LaneSettlement.Span(seconds, this.focused))
            since = null
        }
        this.focused = focused
        if (active && since == null) since = now
    }

    fun take(now: Instant): List<LaneSettlement.Span> {
        align(now, active = false, focused = focused)
        val out = pieces.toList()
        pieces.clear()
        return out
    }

    fun reset() {
        pieces.clear()
        since = null
    }
}

/**
 * 起播时把码率上限收成这一路的 [LaneQuality] 结果。未降档时上限是正无穷，不改变原来的值。
 */
internal class ThermalCapSink(
    private val inner: PlaybackSink,
    private val capOf: () -> Double,
) : PlaybackSink {
    override val lastError: String?
        get() = inner.lastError
    override val failed: Boolean
        get() = inner.failed

    override fun play(
        rendition: StreamRendition,
        username: String,
        peakBitrate: Double,
        allowed: Boolean,
        isRetry: Boolean,
    ) {
        val cap = capOf()
        val used = if (!cap.isFinite()) peakBitrate else minOf(peakBitrate, cap)
        inner.play(rendition, username, used, allowed, isRetry)
    }

    override fun stop() = inner.stop()

    override fun setVolume(volume: Double, muted: Boolean) = inner.setVolume(volume, muted)

    override fun setAllowed(allowed: Boolean) = inner.setAllowed(allowed)

    override fun refreshFinished(reason: String?, report: Boolean) = inner.refreshFinished(reason, report)

    override fun setRefreshHandler(handler: ((String, Boolean) -> Unit)?) = inner.setRefreshHandler(handler)

    override fun setRenewHandler(handler: (suspend (String, Double) -> StreamRendition?)?) =
        inner.setRenewHandler(handler)
}

internal fun StageViewModel.wrapPrimarySink(sink: PlaybackSink?): PlaybackSink? {
    if (sink == null || sink is ThermalCapSink) return sink
    return ThermalCapSink(sink) {
        val cap = laneCap(LaneID.PRIMARY)
        appliedCap[LaneID.PRIMARY] = cap
        cap
    }
}

internal fun StageViewModel.updateFeedLanes(
    installing: FeedSource? = null,
    intent: LaneSelection.Intent = LaneSelection.Intent.Filter,
    body: (FeedStore) -> Unit,
) {
    val copy = feed.copy()
    val before = copy.current?.username
    val beforeSecondary = secondaryCursor?.username
    val snapshot = LaneSelection.Snapshot(
        rooms = copy.rooms.map { it.username },
        primary = primaryCursor.copy(),
        secondary = secondaryCursor?.copy(),
        focus = if (secondaryCursor == null) LaneID.PRIMARY else focusLane,
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
        settlementRole = PlaybackLaneRole.PRIMARY
        settlementFocused = laneCount <= 1 || focusLane == LaneID.PRIMARY
        settlementSourceOverride = null
        currentRoomChanged(endReason, userInitiated)
    }
    val afterSecondary = secondaryCursor?.username
    if (secondaryCursor != null &&
        DwellTracker.shouldSettle(beforeSecondary, afterSecondary, sourceChanged)
    ) {
        settlementRole = PlaybackLaneRole.SECONDARY
        settlementFocused = focusLane == LaneID.SECONDARY
        settlementSourceOverride = secondarySource
        if (beforeSecondary != null && curation.isBlocked(beforeSecondary)) secondaryDwell.discard()
        val nextRoom = roomNamed(afterSecondary)
        val settled = secondaryDwell.switchTo(nextRoom, now())
        if (settled != null) recordSettlement(settled, endReason, userInitiated)
        secondaryDwell.setLedgerExcluded(taste.paused, now())
        secondarySource = SiteSearch.dwellSource(loadedSource, siteSearch != null)
    }
    if (!sameUser(beforeSecondary, afterSecondary)) {
        val previous = secondaryFace
        val generation = (previous?.generation ?: 0) + 1
        secondaryFace = (previous ?: LaneFace(id = LaneID.SECONDARY)).copy(
            id = LaneID.SECONDARY,
            username = afterSecondary,
            generation = generation,
            playback = if (afterSecondary == null) LanePlayback.Empty else LanePlayback.Idle,
            playbackNote = null,
            readyUsername = null,
            playingUsername = null,
            manuallyPaused = MoveRules.pausedAfterMove(previous?.manuallyPaused == true, userInitiated),
        )
    }
    val switchedFocus = outcome.focus != focusLane && secondaryCursor != null && laneCount > 1
    if (switchedFocus) setFocus(outcome.focus)
    publish()
    if (!sameUser(beforeSecondary, afterSecondary) && !deferSecondaryAutoplay) {
        autoplaySecondary()
    }
}

internal fun StageViewModel.recordSettlementLanes(
    settled: DwellTracker.Settled,
    endReason: LaneEndReason,
    userInitiated: Boolean,
) {
    val at = now()
    val single = laneCount <= 1
    val role = if (single) PlaybackLaneRole.PRIMARY else settlementRole
    val source = if (single) dwellSource else (settlementSourceOverride ?: dwellSource)
    val spans = if (single) {
        listOf(LaneSettlement.Span(seconds = settled.dwell, focused = true))
    } else {
        val log = if (role == PlaybackLaneRole.SECONDARY) secondaryFocusLog else primaryFocusLog
        val logged = log.take(at)
        if (logged.isEmpty()) {
            listOf(LaneSettlement.Span(seconds = settled.dwell, focused = settlementFocused))
        } else {
            logged
        }
    }
    val settlement = LaneSettlement.settle(
        lane = role,
        username = settled.room.username,
        source = source,
        spans = spans,
        endReason = endReason,
        userInitiated = userInitiated,
    )
    val isFocusLane = single || role == focusLane.role
    val daily = when {
        settlement.countsDaily -> settlement.dailySeconds
        isFocusLane && settled.dwell <= 0.0 && !settlement.resumesAsNewDwell -> 0.0
        else -> null
    }
    taste = taste.recording(
        room = settled.room,
        learnSeconds = if (settlement.learnsTaste) settlement.learnSeconds else null,
        daily = daily,
        source = settlement.source,
        now = at,
        rarity = tagRarity,
    )
    tasteSaver.schedule(taste)
    val start = settled.start
    if (settled.ledgerSeconds <= 0.0 || start == null) return
    val ledgerSettlement = if (settled.ledgerSeconds == settled.dwell) {
        settlement
    } else if (single) {
        LaneSettlement.settle(
            lane = PlaybackLaneRole.PRIMARY,
            username = settled.room.username,
            source = source,
            spans = listOf(LaneSettlement.Span(seconds = settled.ledgerSeconds, focused = true)),
            endReason = endReason,
            userInitiated = userInitiated,
        )
    } else {
        val factor = if (settled.dwell > 0.0) settled.ledgerSeconds / settled.dwell else 0.0
        LaneSettlement.settle(
            lane = role,
            username = settled.room.username,
            source = source,
            spans = spans.map { span -> span.copy(seconds = span.seconds * factor) },
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

internal fun StageViewModel.updateDwellActiveLanes() {
    val now = now()
    val name = feed.currentUsername
    val playing = laneFace.playingUsername
    val active = sceneAttentive &&
        !laneFace.manuallyPaused &&
        laneFace.playback != LanePlayback.Failed &&
        name != null &&
        playing != null &&
        playing.equals(name, ignoreCase = true)
    val was = dwell.isActive
    dwell.setActive(active, now)
    primaryFocusLog.align(now, active, laneCount <= 1 || focusLane == LaneID.PRIMARY)
    val primaryIsFocus = laneCount <= 1 || focusLane == LaneID.PRIMARY
    if (primaryIsFocus) {
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
    val secondaryName = secondaryCursor?.username
    val secondaryPlaying = secondaryFace?.playingUsername
    val secondaryFaceNow = secondaryFace
    val secondaryActive = laneCount > 1 &&
        sceneAttentive &&
        secondaryFaceNow != null &&
        !secondaryFaceNow.manuallyPaused &&
        secondaryFaceNow.playback != LanePlayback.Failed &&
        secondaryFaceNow.playback != LanePlayback.Degraded &&
        secondaryFaceNow.visible &&
        secondaryName != null &&
        secondaryPlaying != null &&
        secondaryPlaying.equals(secondaryName, ignoreCase = true)
    secondaryDwell.setActive(secondaryActive, now)
    secondaryFocusLog.align(now, secondaryActive, focusLane == LaneID.SECONDARY)
    if (focusLane == LaneID.SECONDARY && laneCount > 1) {
        if (secondaryActive) checkSecondarySuggest()
    }
}

internal fun StageViewModel.scheduleRelatedLanes() {
    if (laneCount > 1 && focusLane != LaneID.PRIMARY) {
        relatedJob?.cancel()
        relatedJob = null
        return
    }
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
                kotlinx.coroutines.delay(((seconds + 0.05) * 1000.0).toLong())
                if (feed.currentUsername.equals(username, ignoreCase = true)) scheduleRelated()
            }
        }
    }
}

internal fun StageViewModel.setDual(on: Boolean, remember: Boolean = true) {
    if (on) {
        if (laneCount >= 2) return
        setLaneCount(2, remember)
    } else {
        setLaneCount(1, remember)
    }
}

internal fun StageViewModel.setLaneCount(requested: Int, remember: Boolean = true) {
    if (laneCountLocked || focusLocked) return
    val cap = LaneWindow.maxLaneCount(threeLanesFit)
    val next = requested.coerceIn(1, cap)
    if (next == laneCount) {
        if (remember && requested == next && rememberedLaneCount != next) {
            rememberedLaneCount = next
            settings.dualWindow = next >= 2
            publish()
        }
        return
    }
    if (next > laneCount) openLanes(next, remember) else closeLanes(next, remember)
}

internal fun StageViewModel.setFocus(id: LaneID) {
    if (laneCount <= 1 || focusLocked || id == focusLane) return
    if (id !in LaneID.active(laneCount)) return
    focusLane = id
    noteFocusedLaneVideoSize()
    val now = now()
    primaryFocusLog.align(now, dwell.isActive, id == LaneID.PRIMARY)
    secondaryFocusLog.align(now, secondaryDwell.isActive, id == LaneID.SECONDARY)
    onLaneFocusChanged?.invoke(id)
    pushVolumes()
    syncLaneVisibility()
    if (id == LaneID.SECONDARY && secondaryFace?.playback == LanePlayback.Degraded) {
        secondaryFace = secondaryFace?.copy(playback = LanePlayback.Idle, playbackNote = null)
        requestPlay(LaneID.SECONDARY)
    }
    dismissFavoriteSuggestIfMismatch()
    publish()
    Log.i(LANE_TAG, "focus ${id.label}")
}

internal fun StageViewModel.setLandscape(on: Boolean) {
    if (on == landscape) return
    landscape = on
    syncLaneVisibility()
    syncLaneCaps()
    publish()
}

internal fun StageViewModel.notePortraitStageSize(width: Double, height: Double) {
    val next = LaneWindow.notePortraitStageSize(state.value, width, height)
    portraitStageWidth = next.portraitStageWidth
    portraitStageHeight = next.portraitStageHeight
    threeLanesFit = next.threeLanesFit
    if (next.rememberedLaneCount != rememberedLaneCount && next.laneCount == laneCount) {
        rememberedLaneCount = next.rememberedLaneCount
    }
    if (next.laneCount != laneCount) {
        setLaneCount(next.laneCount, remember = false)
    } else {
        syncLaneCaps()
        publish()
    }
}

/** 这一路此刻的码率上限。未降档为正无穷，两路都是最高档。 */
internal fun StageViewModel.laneCap(id: LaneID): Double =
    laneQuality.peakBitrate(estimatedDisplayHeight(id), screenScale)

/** 上限与上次起播或换档用的不同时返回新值。没有播放项也不会改已记录的上限。 */
internal fun StageViewModel.retargetCap(id: LaneID): Double? {
    val cap = laneCap(id)
    val applied = appliedCap[id] ?: Double.POSITIVE_INFINITY
    return if (cap == applied) null else cap
}

internal fun StageViewModel.noteAppliedCap(id: LaneID, cap: Double) {
    appliedCap[id] = cap
}

internal fun StageViewModel.noteLaneDisplay(id: LaneID, height: Double, scale: Double = screenScale) {
    screenScale = scale
    if (displayHeights[id] == height) return
    displayHeights[id] = height
    syncLaneCaps()
}

/**
 * 注入热状态。时间用 [now]，测试可以拨快，不必真等 60 秒。
 * [scheduleRecovery] 为真时，回到 fair 及以下会排一次 60 秒后的复查，不留周期定时器。
 */
internal fun StageViewModel.noteThermal(
    state: PhoneThermalState,
    now: Instant = now(),
    scheduleRecovery: Boolean = false,
) {
    val wasLimited = laneQuality.isLimited
    laneQuality.observe(state, now)
    thermal = state
    thermalJob?.cancel()
    thermalJob = null
    if (scheduleRecovery && laneQuality.isLimited && state < PhoneThermalState.SERIOUS) {
        thermalJob = scope.launch {
            kotlinx.coroutines.delay((LaneQuality.recoveryDelay * 1000.0).toLong())
            thermalJob = null
            noteThermal(thermal, now(), scheduleRecovery = true)
        }
    }
    if (laneQuality.isLimited != wasLimited) {
        Log.i(LANE_TAG, "thermal ${if (laneQuality.isLimited) "limit" else "recover"}")
        syncLaneCaps()
    }
    publish()
}

internal fun StageViewModel.outcomeFor(id: LaneID, chain: List<FailureCode>): PlaybackFailureClassifier.Outcome {
    val secondaryPlaying = secondaryFace?.playback == LanePlayback.Playing
    val primaryPlaying = laneFace.playback == LanePlayback.Playing
    return PlaybackFailureClassifier.classify(
        chain,
        PlaybackFailureClassifier.Context(
            isDualWindow = laneCount > 1,
            isSecondary = id == LaneID.SECONDARY,
            otherIsPlaying = if (id == LaneID.SECONDARY) primaryPlaying else secondaryPlaying,
            hasPlayed = if (id == LaneID.SECONDARY) {
                secondaryFace?.playingUsername != null
            } else {
                laneFace.playingUsername != null
            },
        ),
    )
}

internal fun StageViewModel.degradeLane(id: LaneID) {
    if (id != LaneID.SECONDARY || laneCount <= 1) return
    val face = secondaryFace ?: return
    if (face.username == null) return
    secondarySink?.stop()
    secondaryLane?.stop()
    secondaryFace = face.copy(
        playback = LanePlayback.Degraded,
        playbackNote = StageRules.DUAL_UNAVAILABLE,
        playingUsername = null,
        readyUsername = null,
    )
    publish()
    Log.i(LANE_TAG, "degrade secondary")
}

/** 焦点路已有画面尺寸时立刻交给画中画，不等下一次尺寸回调。 */
internal fun StageViewModel.noteFocusedLaneVideoSize() {
    val lane = when (focusLane) {
        LaneID.PRIMARY -> primaryLane
        LaneID.SECONDARY -> secondaryLane
        LaneID.TERTIARY -> null
    } ?: return
    val size = lane.currentVideoSize()
    if (size.width > 0.0 && size.height > 0.0) onFocusVideoSize?.invoke(size.width, size.height)
}

internal fun StageViewModel.bindLaneRouted(lane: PlaybackLane, webHost: android.widget.FrameLayout? = null) {
    installLaneFailure(lane)
    lane.onPlaybackFace = { syncFromPlayback(lane) }
    if (lane.id == LaneID.PRIMARY || lane.id == LaneID.SECONDARY) {
        lane.addVideoSizeListener { width, height ->
            if (focusLane == lane.id) onFocusVideoSize?.invoke(width.toDouble(), height.toDouble())
        }
    }
    if (lane.id == LaneID.SECONDARY) {
        secondaryLane = lane
        noteFocusedLaneVideoSize()
        val sink = LaneSink(lane)
        sink.setRefreshHandler { username, isRetry -> refreshSecondary(username, isRetry) }
        sink.setRenewHandler { username, cap -> renewRendition(username, cap) }
        secondarySink = ThermalCapSink(sink) {
            val cap = laneCap(LaneID.SECONDARY)
            appliedCap[LaneID.SECONDARY] = cap
            cap
        }
        return
    }
    if (lane.id == LaneID.TERTIARY) return
    primaryLane = lane
    noteFocusedLaneVideoSize()
    val context = appContext
    val root = Endpoints.siteRoot()
    val page = if (context != null && webHost != null) WebSidecarPage(context, root, webHost) else null
    if (page != null) bindPlayback(LaneSink(lane), page) else bindPlayback(LaneSink(lane), null)
}

internal fun StageViewModel.syncFromPlayback(lane: PlaybackLane) {
    if (lane.id == LaneID.SECONDARY) {
        if (secondaryFace?.playback == LanePlayback.Degraded) return
        val failed = lane.failed
        if (failed && secondaryFace?.playback != LanePlayback.Failed) {
            noteLaneFailed(LaneID.SECONDARY, true, lane.lastError ?: StageRules.PLAYBACK_INTERRUPTED)
        }
        if (lane.readyUsername != null) noteLaneReady(LaneID.SECONDARY, lane.readyUsername)
        if (lane.playingUsername != null) noteLanePlaying(LaneID.SECONDARY, lane.playingUsername)
        return
    }
    if (lane.failed && laneFace.playback != LanePlayback.Failed) {
        noteFailed(true, lane.lastError ?: StageRules.PLAYBACK_INTERRUPTED)
    }
    if (lane.readyUsername != null) noteReady(lane.readyUsername)
    if (lane.playingUsername != null) notePlaying(lane.playingUsername)
}

internal fun StageViewModel.noteLanePlaying(id: LaneID, username: String?) {
    if (id != LaneID.SECONDARY) {
        notePlaying(username)
        return
    }
    val face = secondaryFace ?: return
    val name = face.username
    val same = username != null && name != null && username.equals(name, ignoreCase = true)
    if (same) secondaryActivity = PhoneLaneActivity.PLAYING
    if (face.playback == LanePlayback.Degraded) {
        secondaryFace = face.copy(playingUsername = username)
        publish()
        return
    }
    val playback = when {
        same -> LanePlayback.Playing
        face.playback == LanePlayback.Failed -> LanePlayback.Failed
        name == null -> LanePlayback.Empty
        else -> LanePlayback.Idle
    }
    secondaryFace = face.copy(
        playingUsername = username,
        playback = playback,
        playbackNote = if (same) null else face.playbackNote,
    )
    updateDwellActive()
    publish()
}

internal fun StageViewModel.noteLaneReady(id: LaneID, username: String?) {
    if (id != LaneID.SECONDARY) {
        noteReady(username)
        return
    }
    secondaryFace = secondaryFace?.copy(readyUsername = username)
    publish()
}

internal fun StageViewModel.noteLaneFailed(id: LaneID, failed: Boolean, note: String) {
    if (id != LaneID.SECONDARY) {
        noteFailed(failed, note)
        return
    }
    val face = secondaryFace ?: return
    if (face.playback == LanePlayback.Degraded) return
    if (!failed) {
        val playback = if (face.playback == LanePlayback.Failed) {
            if (face.playingUsername != null) LanePlayback.Playing else LanePlayback.Idle
        } else {
            face.playback
        }
        secondaryFace = face.copy(playback = playback, playbackNote = null)
        secondaryDwell.clearFailure()
        publish()
        return
    }
    secondaryDwell.markFailed()
    secondaryFace = face.copy(playback = LanePlayback.Failed, playbackNote = note)
    updateDwellActive()
    publish()
}

internal fun StageViewModel.requestLane(id: LaneID, isRetry: Boolean = false) {
    when (id) {
        LaneID.PRIMARY -> requestPlay(isRetry)
        LaneID.SECONDARY -> requestSecondary(isRetry)
        LaneID.TERTIARY -> Unit
    }
}

internal fun StageViewModel.toggleFavoriteLanes(target: RoomTarget?) {
    if (!accepts(target)) return
    val id = target?.laneID ?: LaneID.PRIMARY
    val room = roomOn(id) ?: return
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
    replaySecondaryIfMoved()
}

internal fun StageViewModel.blockCurrentLanes(target: RoomTarget?) {
    if (!accepts(target)) return
    val id = target?.laneID ?: LaneID.PRIMARY
    val room = roomOn(id) ?: return
    if (id == LaneID.SECONDARY) {
        secondaryFace = secondaryFace?.copy(manuallyPaused = false)
        secondaryDwell.discard()
    } else {
        laneFace = laneFace.copy(manuallyPaused = false)
    }
    val before = secondaryCursor?.username
    commitCuration(curation.blocking(room.username), text.blocked(room.displayName))
    if (!sameUser(before, secondaryCursor?.username)) requestLane(LaneID.SECONDARY)
}

internal fun StageViewModel.markNotInterestedLanes(target: RoomTarget?) {
    if (!accepts(target)) return
    val id = target?.laneID ?: LaneID.PRIMARY
    val room = roomOn(id) ?: return
    val source = if (id == LaneID.SECONDARY) secondarySource else dwellSource
    commitTaste(taste.rejecting(room, source, now()))
    if (id == LaneID.SECONDARY) secondaryDwell.discard() else dwell.discard()
    showToast(if (taste.paused) text.skipPaused() else text.skip())
    if (id == LaneID.SECONDARY) moveLane(LaneID.SECONDARY, 1, userInitiated = true) else move(1, true)
}

internal fun StageViewModel.retryPlaybackLanes(target: RoomTarget?) {
    if (!accepts(target)) return
    if (target?.laneID == LaneID.SECONDARY) {
        val face = secondaryFace ?: return
        secondaryFace = face.copy(
            playback = if (face.playback == LanePlayback.Failed || face.playback == LanePlayback.Degraded) {
                LanePlayback.Idle
            } else {
                face.playback
            },
            playbackNote = null,
        )
        requestLane(LaneID.SECONDARY)
        return
    }
    failureNote = null
    laneFace = laneFace.copy(
        playback = if (laneFace.playback == LanePlayback.Failed) LanePlayback.Idle else laneFace.playback,
        playbackNote = null,
    )
    requestPlay()
}

internal fun StageViewModel.togglePauseLane(target: RoomTarget?) {
    val face = secondaryFace ?: return
    val paused = !face.manuallyPaused
    secondaryFace = face.copy(manuallyPaused = paused)
    updateDwellActive()
    publish()
    if (paused) {
        secondaryPlayJob?.cancel()
        secondarySink?.setAllowed(false)
    } else {
        requestLane(LaneID.SECONDARY)
    }
}

internal fun StageViewModel.moveLane(id: LaneID, delta: Int, userInitiated: Boolean): Boolean {
    if (id == LaneID.PRIMARY || secondaryCursor == null) return move(delta, userInitiated)
    if (delta == 0 || feed.rooms.isEmpty()) return false
    val before = secondaryCursor?.username
    moveUserInitiated = userInitiated
    deferSecondaryAutoplay = true
    try {
        updateFeed(intent = LaneSelection.Intent.Move(id, delta)) { }
    } finally {
        moveUserInitiated = false
        deferSecondaryAutoplay = false
    }
    if (sameUser(secondaryCursor?.username, before)) return false
    secondaryFace = secondaryFace?.copy(
        manuallyPaused = MoveRules.pausedAfterMove(secondaryFace?.manuallyPaused == true, userInitiated),
    )
    requestLane(LaneID.SECONDARY)
    track { loadMoreIfNeeded() }
    return true
}

internal fun StageViewModel.settleAllLanes(reason: LaneEndReason) {
    settlementRole = PlaybackLaneRole.PRIMARY
    settlementFocused = laneCount <= 1 || focusLane == LaneID.PRIMARY
    settlementSourceOverride = null
    val settled = dwell.finish(now())
    if (settled != null) recordSettlement(settled, reason, userInitiated = false)
    lastSettlementUsername = settled?.room?.username
    if (secondaryCursor == null) return
    settlementRole = PlaybackLaneRole.SECONDARY
    settlementFocused = focusLane == LaneID.SECONDARY
    settlementSourceOverride = secondarySource
    val secondarySettled = secondaryDwell.finish(now())
    if (secondarySettled != null) recordSettlement(secondarySettled, reason, userInitiated = false)
}

internal fun StageViewModel.reopenDwellLanes() {
    if (dwell.room == null && feed.current != null) {
        dwell.switchTo(feed.current, now())
        dwell.setLedgerExcluded(taste.paused, now())
    }
    if (laneCount > 1 && secondaryDwell.room == null) {
        val room = roomNamed(secondaryCursor?.username)
        if (room != null) {
            secondaryDwell.switchTo(room, now())
            secondaryDwell.setLedgerExcluded(taste.paused, now())
        }
    }
}

internal fun StageViewModel.freezeDwells() {
    val now = now()
    dwell.setActive(false, now)
    secondaryDwell.setActive(false, now)
    primaryFocusLog.align(now, active = false, focused = focusLane == LaneID.PRIMARY || laneCount <= 1)
    secondaryFocusLog.align(now, active = false, focused = focusLane == LaneID.SECONDARY)
}

internal fun StageViewModel.pushVolumes() {
    val primaryAudible = !muted && (laneCount <= 1 || focusLane == LaneID.PRIMARY)
    playbackSink?.setVolume(if (primaryAudible) volume else 0.0, !primaryAudible)
    val secondaryAudible = !muted && focusLane == LaneID.SECONDARY && laneCount > 1
    secondarySink?.setVolume(if (secondaryAudible) volume else 0.0, !secondaryAudible)
}

internal fun StageViewModel.openRoles(): List<PlaybackLaneRole> =
    LaneID.active(laneCount).map { it.role }

internal fun StageViewModel.roomOn(id: LaneID): Room? = when (id) {
    LaneID.PRIMARY -> feed.current
    LaneID.SECONDARY -> roomNamed(secondaryCursor?.username)
    LaneID.TERTIARY -> roomNamed(tertiaryFace?.username)
}

internal fun StageViewModel.startThermalMonitor() {
    val context = appContext ?: return
    if (thermalMonitor != null) return
    val monitor = ThermalMonitor(onState = { state -> noteThermal(state, scheduleRecovery = true) })
    thermalMonitor = monitor
    monitor.start(context)
}

internal fun StageViewModel.stopThermalMonitor() {
    thermalMonitor?.stop()
    thermalMonitor = null
    thermalJob?.cancel()
    thermalJob = null
}

private fun StageViewModel.openLanes(next: Int, remember: Boolean) {
    val openingSecondary = laneCount < 2 && next >= 2
    laneCount = next
    if (next >= 3 && tertiaryFace == null) {
        tertiaryFace = LaneFace(id = LaneID.TERTIARY, playback = LanePlayback.Empty)
    }
    if (next < 3) tertiaryFace = null
    if (openingSecondary) {
        secondaryCursor = LaneCursor()
        secondaryFace = LaneFace(
            id = LaneID.SECONDARY,
            playback = LanePlayback.Empty,
            visible = !(landscape && focusLane != LaneID.SECONDARY),
        )
        secondarySource = SiteSearch.dwellSource(loadedSource, siteSearch != null)
        primaryFocusLog.reset()
        secondaryFocusLog.reset()
        if (feed.rooms.isNotEmpty()) {
            updateFeed(intent = LaneSelection.Intent.Reload) { }
        }
    }
    if (remember) {
        rememberedLaneCount = next
        settings.dualWindow = next >= 2
    }
    onLanesChanged?.invoke()
    syncLaneVisibility()
    syncLaneCaps()
    publish()
    Log.i(LANE_TAG, "lanes $next")
}

private fun StageViewModel.closeLanes(next: Int, remember: Boolean) {
    if (next <= 1) {
        val keep = if (focusLane != LaneID.PRIMARY) secondaryCursor?.username else null
        if (focusLane != LaneID.PRIMARY) setFocus(LaneID.PRIMARY)
        dropSecondary(LaneEndReason.DUAL_OFF)
        tertiaryFace = null
        laneCount = 1
        focusLane = LaneID.PRIMARY
        laneFace = laneFace.copy(visible = true)
        primaryVisible = true
        primaryFocusLog.reset()
        if (keep != null && !sameUser(keep, feed.currentUsername)) {
            val room = roomNamed(keep)
            updateFeed(intent = LaneSelection.Intent.Reveal(keep)) { store ->
                if (room != null) store.reveal(room) else store.select(keep)
            }
            requestPlay()
        }
    } else {
        if (focusLane == LaneID.TERTIARY) {
            val moved = tertiaryFace?.username
            focusLane = LaneID.SECONDARY
            if (moved != null) {
                secondaryCursor = (secondaryCursor ?: LaneCursor()).copy(username = moved)
                secondaryFace = (secondaryFace ?: LaneFace(id = LaneID.SECONDARY)).copy(
                    id = LaneID.SECONDARY,
                    username = moved,
                    playback = LanePlayback.Idle,
                )
            }
        }
        tertiaryFace = null
        laneCount = 2
    }
    if (remember) {
        rememberedLaneCount = laneCount
        settings.dualWindow = laneCount >= 2
    }
    onLanesChanged?.invoke()
    pushVolumes()
    syncLaneCaps()
    publish()
    Log.i(LANE_TAG, "lanes $laneCount")
}

private fun StageViewModel.dropSecondary(reason: LaneEndReason) {
    if (secondaryCursor != null) {
        settlementRole = PlaybackLaneRole.SECONDARY
        settlementFocused = focusLane == LaneID.SECONDARY
        settlementSourceOverride = secondarySource
        val settled = secondaryDwell.finish(now())
        if (settled != null) recordSettlement(settled, reason, userInitiated = false)
    }
    secondaryPlayJob?.cancel()
    secondaryPlayJob = null
    secondarySink?.stop()
    if (releaseSecondaryLane != null) {
        releaseSecondaryLane?.invoke()
    } else {
        secondaryLane?.release()
    }
    secondaryLane = null
    secondarySink = null
    secondaryCursor = null
    secondaryFace = null
    secondaryFocusLog.reset()
}

/**
 * 横屏或画中画只留焦点路。PiP1 引入 [StageViewModel.pipSolo]；
 * 本函数仍归 B1 文件，PiP1 只改这一处口径（与计划串行重叠表略有出入，见交付说明）。
 */
internal fun StageViewModel.syncLaneVisibilityForPip() {
    syncLaneVisibility()
}

private fun StageViewModel.syncLaneVisibility() {
    val solo = (landscape || pipSolo) && laneCount > 1
    val showPrimary = !solo || focusLane == LaneID.PRIMARY
    if (laneFace.visible != showPrimary) {
        laneFace = laneFace.copy(visible = showPrimary)
        primaryVisible = showPrimary
        if (!showPrimary) {
            playbackSink?.stop()
            primaryLane?.stop()
        } else if (playbackGateOpen && !laneFace.manuallyPaused && !lifecycleEpoch.suspended) {
            requestPlay()
        }
    } else {
        primaryVisible = showPrimary
    }
    val face = secondaryFace
    if (face != null && laneCount >= 2) {
        val showSecondary = !solo || focusLane == LaneID.SECONDARY
        if (face.visible != showSecondary) {
            secondaryFace = face.copy(visible = showSecondary)
            if (!showSecondary) {
                secondarySink?.stop()
                secondaryLane?.stop()
            } else if (
                playbackGateOpen &&
                face.manuallyPaused.not() &&
                face.playback != LanePlayback.Degraded &&
                !lifecycleEpoch.suspended
            ) {
                requestLane(LaneID.SECONDARY)
            }
        }
    }
}

private fun StageViewModel.syncLaneCaps() {
    for (id in listOf(LaneID.PRIMARY, LaneID.SECONDARY)) {
        val next = retargetCap(id) ?: continue
        val lane = if (id == LaneID.PRIMARY) primaryLane else secondaryLane
        if (lane != null && lane.retierPeak(next)) {
            appliedCap[id] = next
            Log.i(LANE_TAG, "thermal-retier lane=${id.label}")
        }
    }
}

private fun StageViewModel.estimatedDisplayHeight(id: LaneID): Double {
    val noted = displayHeights[id]
    if (noted != null && noted > 0.0) return noted
    if (portraitStageHeight <= 0.0 || portraitStageWidth <= 0.0) return 0.0
    val pane = vela.core.base.lane.LaneLayout.paneHeight(portraitStageHeight, laneCount.coerceAtLeast(1))
    return minOf(pane, portraitStageWidth * 9.0 / 16.0)
}

private fun StageViewModel.installLaneFailure(lane: PlaybackLane) {
    lane.failureFilter = { info ->
        outcomeFor(lane.id, info.chain)
    }
    lane.resourceDegraded = { info ->
        if (lane.id == LaneID.SECONDARY &&
            outcomeFor(LaneID.SECONDARY, info.chain) == PlaybackFailureClassifier.Outcome.RESOURCE_DEGRADE
        ) {
            degradeLane(LaneID.SECONDARY)
        }
    }
}

private fun StageViewModel.autoplaySecondary() {
    val name = secondaryCursor?.username ?: return
    val face = secondaryFace ?: return
    if (face.manuallyPaused) return
    if (face.playback == LanePlayback.Failed || face.playback == LanePlayback.Degraded) return
    if (!face.visible) return
    val playing = face.playingUsername
    if (playing != null && playing.equals(name, ignoreCase = true) && face.playback == LanePlayback.Playing) return
    requestLane(LaneID.SECONDARY)
}

private fun StageViewModel.replaySecondaryIfMoved() {
    // commit 已经在过滤器里改过副路。这里只补一次起播，避免和 updateFeed 的自动起播重复。
}

private fun StageViewModel.requestSecondary(isRetry: Boolean) {
    secondaryPlayRequests += 1
    if (!playbackGateOpen) return
    if (!ageConfirmed) {
        publish()
        return
    }
    val face = secondaryFace
    if (face?.manuallyPaused == true || face?.playback == LanePlayback.Degraded) {
        publish()
        return
    }
    ensureSecondary?.invoke()
    if (secondarySink == null) {
        startUnboundSecondary()
        return
    }
    startBoundSecondary(isRetry)
}

private fun StageViewModel.startUnboundSecondary() {
    val name = secondaryCursor?.username
    val face = secondaryFace ?: LaneFace(id = LaneID.SECONDARY)
    if (name == null) {
        secondaryFace = face.copy(username = null, playback = LanePlayback.Empty, playbackNote = null)
        publish()
        return
    }
    secondaryFace = face.copy(
        username = name,
        generation = face.generation + 1,
        playback = LanePlayback.Idle,
        playbackNote = null,
        readyUsername = null,
        playingUsername = null,
    )
    updateDwellActive()
    publish()
}

private fun StageViewModel.startBoundSecondary(isRetry: Boolean) {
    val token = bumpSecondary()
    val previous = secondaryPlayJob
    val job = scope.launch {
        try {
            playSecondary(token, isRetry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }
    secondaryPlayJob = job
    if (previous != null && previous != job) previous.cancel()
}

private fun StageViewModel.bumpSecondary(): Int {
    val name = secondaryCursor?.username
    val face = secondaryFace ?: LaneFace(id = LaneID.SECONDARY)
    val playback = if (name == null) LanePlayback.Empty else LanePlayback.Idle
    secondaryFace = face.copy(
        username = name,
        generation = face.generation + 1,
        playback = playback,
        playbackNote = null,
        readyUsername = null,
        playingUsername = null,
    )
    secondaryActivity = if (name == null) PhoneLaneActivity.IDLE else PhoneLaneActivity.ROOM_SWITCH
    updateDwellActive()
    publish()
    return secondaryFace?.generation ?: 0
}

private suspend fun StageViewModel.playSecondary(token: Int, isRetry: Boolean) {
    if (!ageConfirmed || token != secondaryFace?.generation) return
    val room = roomNamed(secondaryCursor?.username)
    val sink = secondarySink ?: return
    if (room == null) {
        sink.stop()
        secondaryFace = secondaryFace?.copy(username = null, playback = LanePlayback.Empty)
        publish()
        return
    }
    if (secondaryFace?.manuallyPaused == true || secondaryFace?.visible == false) return
    try {
        val cap = laneCap(LaneID.SECONDARY)
        appliedCap[LaneID.SECONDARY] = cap
        val target = secondaryPlayable(room.username, cap, token) ?: return
        if (token != secondaryFace?.generation) return
        sink.play(target.playable, room.username, cap, secondaryAllowed(), isRetry)
        pushVolumes()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (token != secondaryFace?.generation) return
        noteLaneFailed(LaneID.SECONDARY, true, error.message ?: StageRules.PLAYBACK_INTERRUPTED)
    }
}

private suspend fun StageViewModel.secondaryPlayable(username: String, cap: Double, token: Int): PlayTarget? {
    prepared.fresh(username, cap, now())?.let { cached ->
        return PlayTarget(master = null, playable = cached)
    }
    val sourced = resolveSourced(username)
    if (token != secondaryFace?.generation) return null
    val ticket = sourced.ticket
    val address = ticket.url
    if (!ticket.isPlayable || address == null) {
        secondarySink?.stop()
        noteLaneFailed(LaneID.SECONDARY, true, text.failure(ticket.failure))
        return null
    }
    val master = loopbackSharedMaster(address)
    val playable = selectRendition(master, cap)
    if (token != secondaryFace?.generation) return null
    if (!playable.isUnselected) prepared.set(playable, cap, username, now())
    return PlayTarget(master = master, playable = playable)
}

private fun StageViewModel.secondaryAllowed(): Boolean {
    if (surface == StageSurface.Challenge) return false
    val face = secondaryFace ?: return false
    if (!ageConfirmed || face.manuallyPaused || !face.visible) return false
    if (face.playback == LanePlayback.Failed || face.playback == LanePlayback.Degraded) return false
    return secondaryCursor?.username != null
}

private fun StageViewModel.refreshSecondary(username: String, isRetry: Boolean) {
    val sink = secondarySink ?: return
    val current = secondaryCursor?.username
    if (current == null || !current.equals(username, ignoreCase = true)) {
        sink.refreshFinished(null, false)
        return
    }
    prepared.remove(username)
    requestLane(LaneID.SECONDARY, isRetry)
}

private fun StageViewModel.checkSecondarySuggest() {
    if (taste.paused || secondaryDwell.isDiscarded || favoriteSuggest != null) return
    val room = roomNamed(secondaryCursor?.username) ?: return
    if (!secondaryDwell.isActive) return
    val key = vela.core.ledger.WatchLedger.key(room.username)
    if (!vela.core.ledger.SuggestRules.canPresent(pointerCanClick = true, snoozed = key in suggestSnoozed)) return
    val live = secondaryDwell.ledgerDwell(now())
    val start = secondaryDwell.startedAt?.timeIntervalSince1970()
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

private fun StageViewModel.roomNamed(name: String?): Room? {
    if (name == null) return null
    return feed.rooms.firstOrNull { it.username.equals(name, ignoreCase = true) }
        ?: feed.catalog.firstOrNull { it.username.equals(name, ignoreCase = true) }
}

private fun sameUser(left: String?, right: String?): Boolean = when {
    left == null && right == null -> true
    left != null && right != null -> left.equals(right, ignoreCase = true)
    else -> false
}

private const val LANE_TAG: String = "vela.lanes"

internal fun StageViewModel.secondarySnapshotVisible(): Boolean = secondaryFace?.visible ?: false

internal fun StageViewModel.secondarySnapshotPaused(): Boolean = secondaryFace?.manuallyPaused ?: false

internal fun StageViewModel.secondarySnapshotFailed(): Boolean = secondaryFace?.playback == LanePlayback.Failed

internal fun StageViewModel.rememberDualFromSettings() {
    if (!settings.dualWindow || laneCount >= 2) return
    laneCount = 2
    rememberedLaneCount = 2
    secondaryCursor = LaneCursor()
    secondaryFace = LaneFace(id = LaneID.SECONDARY, playback = LanePlayback.Empty)
    secondarySource = source
}

