package vela.app.stage

import android.util.Log
import java.net.URI
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import vela.app.infra.Endpoints
import vela.app.infra.HttpClientImpl
import vela.app.lane.PlaybackLane
import vela.core.base.model.ShowState
import vela.core.base.net.EndpointBackedOff
import vela.core.base.net.EndpointBackoff
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.net.SiteEndpoint
import vela.core.base.net.UserAgent
import vela.core.base.play.StreamRendition
import vela.core.base.site.ChatContext
import vela.core.base.site.looksLikeChallenge
import vela.core.stream.probe
import vela.core.hls.NetworkFailure
import vela.core.hls.RefreshTracker
import vela.core.hls.RenewalFreshness
import vela.core.hls.RenewalPolicy
import vela.core.hls.SkipTracker
import vela.core.lifecycle.AwayRetry
import vela.core.lifecycle.LifecycleEpoch
import vela.core.stream.ContextProbe
import vela.core.stream.SourcedTicket
import vela.core.stream.StreamDecodeError
import vela.core.stream.StreamDecoder
import vela.core.stream.StreamFailure
import vela.core.stream.StreamOrigin
import vela.core.stream.StreamRequest
import vela.core.stream.StreamSourcing
import vela.core.stream.StreamTicket

/**
 * 播放器给舞台用的窄接口。真路是 [LaneSink]；解析测试用记录实现，不必起 ExoPlayer。
 * 没调用 [StageViewModel.bindPlayback] 时，起播只改界面代次，不发解析请求。
 */
interface PlaybackSink {
    val lastError: String?
    val failed: Boolean

    fun play(
        rendition: StreamRendition,
        username: String,
        peakBitrate: Double,
        allowed: Boolean,
        isRetry: Boolean,
    )

    fun stop()

    fun setVolume(volume: Double, muted: Boolean)

    fun setAllowed(allowed: Boolean)

    fun refreshFinished(reason: String?, report: Boolean)

    fun setRefreshHandler(handler: ((String, Boolean) -> Unit)?)

    fun setRenewHandler(handler: (suspend (String, Double) -> StreamRendition?)?)
}

class LaneSink(val lane: PlaybackLane) : PlaybackSink {
    override val lastError: String?
        get() = lane.lastError
    override val failed: Boolean
        get() = lane.failed

    override fun play(
        rendition: StreamRendition,
        username: String,
        peakBitrate: Double,
        allowed: Boolean,
        isRetry: Boolean,
    ) {
        lane.play(rendition, username, peakBitrate, allowed, isRetry)
    }

    override fun stop() {
        lane.stop()
    }

    override fun setVolume(volume: Double, muted: Boolean) {
        lane.setVolume(volume, muted)
    }

    override fun setAllowed(allowed: Boolean) {
        lane.setAllowed(allowed)
    }

    override fun refreshFinished(reason: String?, report: Boolean) {
        lane.refreshFinished(reason, report)
    }

    override fun setRefreshHandler(handler: ((String, Boolean) -> Unit)?) {
        lane.refreshRequested = handler
    }

    override fun setRenewHandler(handler: (suspend (String, Double) -> StreamRendition?)?) {
        lane.renewRendition = handler
    }
}

/** 一次起播要用的地址。命中预取时没有主列表。 */
internal data class PlayTarget(
    val master: URI?,
    val playable: StreamRendition,
)

internal fun StageViewModel.bindPlayback(sink: PlaybackSink, page: SidecarPage? = null) {
    playbackSink = sink
    playbackBound = true
    sink.setRefreshHandler { username, isRetry -> refreshCurrent(username, isRetry) }
    sink.setRenewHandler { username, cap -> renewRendition(username, cap) }
    if (page != null) installSidecar(page)
}

/** 接上真播放器。侧车网页挂到 [webHost] 外面，不传则先不创建，避免测试误加载。 */
fun StageViewModel.bindLane(lane: PlaybackLane, webHost: android.widget.FrameLayout? = null) {
    val context = appContext
    val root = Endpoints.siteRoot()
    val page = if (context != null) WebSidecarPage(context, root, webHost) else null
    if (page != null) {
        bindPlayback(LaneSink(lane), page)
    } else {
        bindPlayback(LaneSink(lane), null)
    }
}

internal fun StageViewModel.installSidecar(page: SidecarPage) {
    sidecarHolder?.close()
    val root = Endpoints.siteRoot()
    sidecarHolder = SiteSidecar(page, sidecarEntryForRoot(root), scope)
}

internal fun StageViewModel.isSidecarWindow(): Boolean {
    val until = sidecarUntil ?: return false
    return now().isBefore(until)
}

/** 没接播放器时的起播：只推进代次和空态，不访问网络。 */
internal fun StageViewModel.startUnboundPlayback() {
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

internal fun StageViewModel.startBoundPlayback(
    isRetry: Boolean,
    after: (suspend StageViewModel.() -> Unit)? = null,
) {
    prefetchJob?.cancel()
    prefetchJob = null
    val token = bumpPlayback()
    val previous = playJob
    val job = scope.launch {
        try {
            playCurrent(token, isRetry)
            after?.invoke(this@startBoundPlayback)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }
    playJob = job
    if (previous != null && previous != job) previous.cancel()
}

internal fun StageViewModel.refreshCurrent(username: String, isRetry: Boolean) {
    val sink = playbackSink ?: return
    val current = feed.currentUsername
    if (current == null || !current.equals(username, ignoreCase = true)) {
        sink.refreshFinished(null, false)
        return
    }
    prepared.remove(username)
    val scheduled = laneFace.generation
    startBoundPlayback(isRetry) {
        if (!RefreshTracker.ownsOutcome(scheduled, laneFace.generation)) return@startBoundPlayback
        playbackSink?.refreshFinished(laneFace.playbackNote, true)
    }
}

internal suspend fun StageViewModel.renewRendition(username: String, cap: Double): StreamRendition? =
    withContext(Dispatchers.Main.immediate) {
        var renewed: StreamRendition? = null
        for (attempt in 1..2) {
            val token = laneFace.generation
            renewed = renewRenditionOnce(username, cap, token)
            if (renewed != null) break
            if (attempt == 1 && renewalIsCurrent(username, token)) delay(400)
        }
        renewed
    }

private suspend fun StageViewModel.playCurrent(token: Int, isRetry: Boolean) {
    if (!ageConfirmed || !playbackGateOpen || !laneFace.visible || !primaryVisible) return
    val life = lifecycleEpoch.begin(vela.core.base.lane.PlaybackLaneRole.PRIMARY) ?: return
    if (token != laneFace.generation) return
    skipJob?.cancel()
    awayJob?.cancel()
    awayTimer.clear()
    val room = feed.current
    if (room == null) {
        playbackSink?.stop()
        laneFace = laneFace.copy(username = null, playback = LanePlayback.Empty, playbackNote = null)
        idleStart.markIdle()
        updateDwellActive()
        publish()
        return
    }
    idleStart.clear()
    awayRetry.enter(room.username)
    try {
        val cap = playbackCap()
        val target = playTarget(room.username, cap, token, life) ?: return
        if (token != laneFace.generation || !coroutineContext.isActive) return
        if (!playbackGateOpen || !laneFace.visible || !primaryVisible) return
        if (!lifecycleEpoch.allows(LifecycleEpoch.Gate.BEFORE_COMMIT, life)) return
        if (!hubReady(token)) return
        startPlayback(room.username, target, cap, isRetry)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        handlePlayFailure(error, token)
    }
}

private fun StageViewModel.bumpPlayback(): Int {
    val name = feed.currentUsername
    failureNote = null
    playerNote = null
    val playback = if (name == null) LanePlayback.Empty else LanePlayback.Idle
    laneFace = laneFace.copy(
        username = name,
        generation = laneFace.generation + 1,
        playback = playback,
        playbackNote = null,
        readyUsername = null,
        playingUsername = null,
    )
    if (name == null) idleStart.markIdle() else idleStart.clear()
    updateDwellActive()
    publish()
    return laneFace.generation
}

private fun StageViewModel.hubReady(token: Int): Boolean {
    if (token != laneFace.generation || playbackSink == null) return false
    return true
}

private fun StageViewModel.laneAllowed(): Boolean {
    if (surface == StageSurface.Challenge) return false
    if (!ageConfirmed || laneFace.manuallyPaused || !laneFace.visible || feed.current == null) return false
    return degradedHold.gate(true)
}

private suspend fun StageViewModel.playTarget(
    username: String,
    cap: Double,
    token: Int,
    life: LifecycleEpoch.Token,
): PlayTarget? {
    prepared.fresh(username, cap, now())?.let { cached ->
        return PlayTarget(master = null, playable = cached)
    }
    if (!lifecycleEpoch.allows(LifecycleEpoch.Gate.BEFORE_REQUEST, life)) return null
    val sourced = resolveSourced(username)
    if (token != laneFace.generation || !coroutineContext.isActive) return null
    if (!lifecycleEpoch.allows(LifecycleEpoch.Gate.BEFORE_REQUEST, life)) return null
    challengeGate.reset()
    Log.i(PLAY_TAG, "start-source ${sourced.origin.rawValue}")
    val ticket = sourced.ticket
    val address = ticket.url
    if (!ticket.isPlayable || address == null) {
        playbackSink?.stop()
        noteUnplayable(ticket.failure, ticket.delistedShow, username, token)
        return null
    }
    val master = loopbackSharedMaster(address)
    val playable = selectRendition(master, cap)
    if (token != laneFace.generation || !coroutineContext.isActive) return null
    if (!lifecycleEpoch.allows(LifecycleEpoch.Gate.BEFORE_COMMIT, life)) return null
    if (!playable.isUnselected) prepared.set(playable, cap, username, now())
    return PlayTarget(master = master, playable = playable)
}

internal fun StageViewModel.noteUnplayable(
    failure: StreamFailure?,
    delist: ShowState?,
    username: String,
    token: Int,
) {
    if (delist != null && feed.canDelist(username)) {
        dwell.markFailed()
        showPlaybackNote(text.failure(failure), failed = true)
        autoSkip()
        // 没跳成（列表尽头、连续跳过到上限）就留在这间并保留原因，不筛；跳成了才拿掉。
        if (feed.currentUsername?.stageLower() == username.stageLower()) return
        clearDelistNote()
        updateFeed { store ->
            store.delist(username, delist, protecting = inUseNames())
        }
        return
    }
    if (failure != StreamFailure.away) {
        showPlaybackNote(text.failure(failure), failed = true)
        scheduleSkip(token)
        return
    }
    dwell.markFailed()
    chromeVisible = true
    val decision = AwayRetry.gated(awayRetry.onAway(username), playbackAllowed = laneAllowed())
    showPlaybackNote(decision.note, failed = true)
    if (decision is AwayRetry.Decision.RetryLater) {
        scheduleAwayRetry(username, token, decision.after, countsAsAway = true)
    }
}

internal fun StageViewModel.scheduleAwayRetry(
    username: String,
    token: Int,
    afterSeconds: Double,
    countsAsAway: Boolean = true,
) {
    awayJob?.cancel()
    awayTimer.schedule()
    val delayMs = awayDelayOverrideMillis ?: (afterSeconds * 1000.0).toLong()
    awayJob = scope.launch {
        delay(delayMs.coerceAtLeast(0L))
        val fire = AwayRetry.shouldFire(
            scheduledRoom = username,
            currentRoom = feed.currentUsername,
            scheduledGeneration = token,
            currentGeneration = laneFace.generation,
            playbackAllowed = laneAllowed(),
            cancelled = !isActive || !awayTimer.pending,
        )
        if (!fire) return@launch
        awayTimer.clear()
        if (countsAsAway) awayRetry.beginRetry()
        startBoundPlayback(isRetry = false)
    }
}

private fun StageViewModel.startPlayback(username: String, target: PlayTarget, cap: Double, isRetry: Boolean) {
    awayRetry.clear()
    currentCap = cap
    val sink = playbackSink ?: return
    sink.play(target.playable, username, cap, laneAllowed(), isRetry)
    sink.setVolume(volume, muted)
    if (sink.failed && sink.lastError != null) {
        val relay = text.relayDown()
        val reported = sink.lastError
        showPlaybackNote(if (reported == null || reported == relay) reported ?: text.failure(null) else reported, failed = true)
        return
    }
    if (prefetchEnabled && !lifecycleEpoch.suspended && !pictureInPicture) {
        val token = laneFace.generation
        prefetchJob?.cancel()
        prefetchJob = scope.launch { prefetchAhead(token) }
    }
}

internal suspend fun StageViewModel.resolveSourced(
    username: String,
    life: LifecycleEpoch.Token? = null,
): SourcedTicket {
    if (life != null && !prefetchStillCurrent(life, laneFace.generation)) {
        throw CancellationException("prefetch gated")
    }
    if (isSidecarWindow()) {
        return SourcedTicket(ticket = requireSidecar().resolve(username), origin = StreamOrigin.sidecar)
    }
    val probe = fetchProbe(username)
    if (probe.context is ContextProbe.Room &&
        probe.context.show == ShowState.publicShow &&
        probe.context.source == null
    ) {
        val recovered = permissiveStreamUrl(probe.rawSource)
        if (recovered != null) {
            return SourcedTicket(
                ticket = StreamTicket(success = true, url = recovered, status = ShowState.publicShow),
                origin = StreamOrigin.chatContext,
            )
        }
    }
    when (val step = StreamSourcing.first(probe.context)) {
        is StreamSourcing.Step.Use -> return SourcedTicket(step.ticket, StreamOrigin.chatContext)
        StreamSourcing.Step.Fallback -> Unit
    }
    if (life != null && !prefetchStillCurrent(life, laneFace.generation)) {
        throw CancellationException("prefetch gated")
    }
    val fallback = resolveEdge(username)
    return SourcedTicket(
        ticket = StreamSourcing.settle(probe.context, fallback.ticket),
        origin = fallback.origin,
    )
}

private data class ProbeFetch(val context: ContextProbe, val rawSource: String?)

private suspend fun StageViewModel.fetchProbe(username: String): ProbeFetch {
    if (!backoff.allows(SiteEndpoint.chatContext)) return ProbeFetch(ContextProbe.BackedOff, null)
    val result = try {
        http.send(ChatContext.request(username))
    } catch (error: Exception) {
        if (RenewalPolicy.isCancellation(error) || !coroutineContext.isActive) throw error
        return ProbeFetch(ContextProbe.Failed, null)
    }
    val context = ChatContext.probe(result.status, result.data)
    if (context == ContextProbe.NotJSON) backoff.record(SiteEndpoint.chatContext)
    val raw = if (context is ContextProbe.Room) jsonStringField(result.data, "hls_source") else null
    return ProbeFetch(context, raw)
}

private suspend fun StageViewModel.resolveEdge(username: String): SourcedTicket {
    try {
        if (!backoff.allows(SiteEndpoint.streamURL)) throw EndpointBackedOff(SiteEndpoint.streamURL)
        val result = http.send(StreamRequest.make(username))
        if (backoff.note(SiteEndpoint.streamURL, result.status, result.data)) {
            throw StreamDecodeError.Unavailable(result.status)
        }
        val ticket = StreamDecoder.decode(result.status, result.data)
            .withRecoveredUrl(jsonStringField(result.data, "url"))
        return SourcedTicket(ticket, StreamOrigin.edge)
    } catch (error: StreamDecodeError) {
        if (error is StreamDecodeError.Challenge) {
            sidecarUntil = now().plusMillis(SIDECAR_CHALLENGE_MS)
            return SourcedTicket(requireSidecar().resolve(username), StreamOrigin.sidecar)
        }
        throw error
    } catch (error: EndpointBackedOff) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (RenewalPolicy.isCancellation(error) || !coroutineContext.isActive) throw error
        if (NetworkFailure.isDisconnected(error)) throw error
        sidecarUntil = now().plusMillis(SIDECAR_NETWORK_MS)
        return SourcedTicket(requireSidecar().resolve(username), StreamOrigin.sidecar)
    }
}

private fun StageViewModel.requireSidecar(): SiteSidecar =
    sidecarHolder ?: throw SidecarError.Unavailable

internal suspend fun StageViewModel.selectRendition(master: URI, cap: Double): StreamRendition {
    val request = HTTPRequest(url = master.toString(), method = "GET")
        .withTimeout(8.0)
        .withHeader("User-Agent", UserAgent.safari)
        .withHeader("Referer", "https://chaturbate.com/")
    val result = try {
        http.send(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return StreamRendition.unselected(master)
    }
    if (result.status !in 200..299) return StreamRendition.unselected(master)
    val text = result.data.toString(Charsets.UTF_8)
    if (!text.contains("#EXTM3U")) return StreamRendition.unselected(master)
    return StreamRendition.select(text, master, cap) ?: StreamRendition.unselected(master)
}

internal suspend fun StageViewModel.prefetchAhead(token: Int) {
    val life = lifecycleEpoch.begin() ?: return
    if (!prefetchStillCurrent(life, token)) return
    val cap = playbackCap()
    val active = inUseNames()
    val next = feed.prefetchUsernames().firstOrNull { it.stageLower() !in active } ?: return
    if (prepared.fresh(next, cap, now()) != null) return
    if (!prefetchStillCurrent(life, token)) return
    val ticket = try {
        resolveSourced(next, life).ticket
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return
    }
    val delist = ticket.delistedShow
    if (delist != null) {
        if (!prefetchStillCurrent(life, token)) return
        // 实测已私密或离线：从列表拿掉，用户就划不到它。正在播的房间不动。
        updateFeed { store ->
            store.delist(next, delist, protecting = inUseNames())
        }
        return
    }
    val address = ticket.url
    if (!ticket.isPlayable || address == null) return
    if (!prefetchStillCurrent(life, token)) return
    val playable = selectRendition(loopbackSharedMaster(address), cap)
    if (playable.isUnselected || !prefetchStillCurrent(life, token, commit = true)) return
    prepared.set(playable, cap, next, now())
}

private fun StageViewModel.prefetchStillCurrent(
    life: LifecycleEpoch.Token,
    token: Int,
    commit: Boolean = false,
): Boolean {
    if (!playbackGateOpen || token != laneFace.generation) return false
    val gate = if (commit) LifecycleEpoch.Gate.BEFORE_COMMIT else LifecycleEpoch.Gate.BEFORE_REQUEST
    return lifecycleEpoch.allows(gate, life)
}

private suspend fun StageViewModel.renewRenditionOnce(
    username: String,
    cap: Double,
    token: Int,
): StreamRendition? {
    val life = lifecycleEpoch.begin(vela.core.base.lane.PlaybackLaneRole.PRIMARY) ?: return null
    if (!laneFace.visible || !primaryVisible || !renewalIsCurrent(username, token)) return null
    if (!lifecycleEpoch.allows(LifecycleEpoch.Gate.BEFORE_REQUEST, life)) return null
    val ticket = try {
        resolveSourced(username).ticket
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return null
    }
    val address = ticket.url
    if (!ticket.isPlayable || address == null) return null
    if (!laneFace.visible || !primaryVisible || !renewalIsCurrent(username, token)) return null
    if (!lifecycleEpoch.allows(LifecycleEpoch.Gate.BEFORE_REQUEST, life)) return null
    val playable = selectRendition(loopbackSharedMaster(address), cap)
    if (playable.isUnselected || !renewalIsCurrent(username, token)) return null
    if (!lifecycleEpoch.allows(LifecycleEpoch.Gate.BEFORE_COMMIT, life)) return null
    prepared.set(playable, cap, username, now())
    return playable
}

private suspend fun StageViewModel.renewalIsCurrent(username: String, token: Int): Boolean =
    RenewalFreshness.isCurrent(
        startedGeneration = token,
        currentGeneration = laneFace.generation,
        startedRoom = username,
        currentRoom = feed.currentUsername,
        cancelled = !coroutineContext.isActive,
    )

private suspend fun StageViewModel.handlePlayFailure(error: Throwable, token: Int) {
    if (RenewalPolicy.isCancellation(error) || !coroutineContext.isActive) return
    if (token != laneFace.generation) return
    when (error) {
        is SidecarError.Challenge, is StreamDecodeError.Challenge -> presentPlaybackChallenge(token)
        is EndpointBackedOff -> {
            playbackSink?.stop()
            dwell.markFailed()
            showPlaybackNote(EndpointBackoff.pendingNote(laneAllowed()), failed = true)
            val name = feed.currentUsername
            if (laneAllowed() && name != null) {
                scheduleAwayRetry(name, token, EndpointBackoff.window, countsAsAway = false)
            }
        }
        is StreamDecodeError.Unavailable -> {
            playbackSink?.stop()
            showPlaybackNote(text.failure(StreamFailure.unavailable), failed = true)
            scheduleSkip(token)
        }
        else -> {
            playbackSink?.stop()
            if (NetworkFailure.isOffline(error)) {
                dwell.markFailed()
                showPlaybackNote(NetworkFailure.message, failed = true)
                return
            }
            showPlaybackNote(text.connectFailed(), failed = true)
            scheduleSkip(token)
        }
    }
}

private fun StageViewModel.presentPlaybackChallenge(token: Int) {
    playbackSink?.stop()
    if (token != laneFace.generation) return
    if (!allowChallengePrompt()) {
        showPlaybackNote(text.challengeSkip(), failed = true)
        scheduleSkip(token)
        return
    }
    showPlaybackNote(text.needsConfirm(), failed = false)
    sidecarHolder?.suspendPage()
    chromeVisible = true
    surface = StageSurface.Challenge
    publish()
}

private fun StageViewModel.clearDelistNote() {
    if (laneFace.playbackNote == null && playerNote == null && failureNote == null) return
    playerNote = null
    failureNote = null
    val playback = if (laneFace.playback == LanePlayback.Failed) LanePlayback.Idle else laneFace.playback
    laneFace = laneFace.copy(playback = playback, playbackNote = null)
    publish()
}

/** 各路此刻占着的房间。筛除与主路预取时都不能动它们。 */
private fun StageViewModel.inUseNames(): Set<String> {
    val names = LinkedHashSet<String>()
    feed.currentUsername?.let { names.add(it.stageLower()) }
    laneFace.username?.let { names.add(it.stageLower()) }
    laneFace.playingUsername?.let { names.add(it.stageLower()) }
    if (laneCount >= 2) {
        secondaryCursor?.username?.let { names.add(it.stageLower()) }
        secondaryFace?.username?.let { names.add(it.stageLower()) }
        secondaryFace?.playingUsername?.let { names.add(it.stageLower()) }
    }
    if (laneCount >= 3) {
        tertiaryFace?.username?.let { names.add(it.stageLower()) }
    }
    return names
}

internal fun StageViewModel.scheduleSkip(token: Int) {
    dwell.markFailed()
    skipJob?.cancel()
    skipJob = scope.launch {
        delay(skipDelayMillis.coerceAtLeast(0L))
        if (token != laneFace.generation || !isActive || !laneAllowed() || lifecycleEpoch.suspended) return@launch
        autoSkip()
    }
}

internal fun StageViewModel.autoSkip() {
    val direction = skips.direction
    val outcome = skips.attemptAuto { move(direction, userInitiated = false) }
    val notice = outcome.notice(keeping = laneFace.playbackNote) ?: return
    showPlaybackNote(notice, failed = true)
}

private fun StageViewModel.showPlaybackNote(note: String, failed: Boolean) {
    chromeVisible = true
    playerNote = note
    failureNote = note
    val playback = if (failed) LanePlayback.Failed else laneFace.playback
    laneFace = laneFace.copy(playback = playback, playbackNote = note)
    updateDwellActive()
    publish()
}

private fun StageViewModel.playbackCap(): Double = Double.POSITIVE_INFINITY

internal fun streamFailureMessage(failure: StreamFailure?): String = StageText(null).failure(failure)

internal fun needsConfirmMessage(): String = StageText(null).needsConfirm()
internal fun challengeSkipMessage(): String = StageText(null).challengeSkip()
internal fun connectFailedMessage(): String = StageText(null).connectFailed()

/**
 * 只接受 https，外加伪上游那三个回环主机的 http。
 * 其它明文地址当没拿到，避免调试包被指向真实站点以外的地方还照播。
 */
/**
 * 伪上游把同一份根主列表挂在 `/hls/<房间>/master.m3u8`。
 * 相对地址若按这条嵌套路径解析会 404，所以回环主机改回根上的 `master.m3u8`。
 * 生产 https 地址原样返回。
 */
internal fun loopbackSharedMaster(master: URI): URI {
    val host = master.host?.lowercase(Locale.ROOT) ?: return master
    if (host != "127.0.0.1" && host != "localhost" && host != "10.0.2.2") return master
    val path = master.path ?: return master
    if (!path.startsWith("/hls/")) return master
    return try {
        URI(master.scheme, null, master.host, master.port, "/master.m3u8", null, null)
    } catch (_: Exception) {
        master
    }
}

internal fun permissiveStreamUrl(raw: String?): URI? {
    if (raw == null) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val uri = try {
        URI(trimmed)
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme == "https") return uri
    if (scheme != "http") return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2") return uri
    return null
}

internal fun StreamTicket.withRecoveredUrl(raw: String?): StreamTicket {
    if (url != null || !status.isPlayable) return this
    val recovered = permissiveStreamUrl(raw) ?: return this
    return copy(success = true, url = recovered)
}

internal fun jsonStringField(data: ByteArray, field: String): String? {
    if (looksLikeChallenge(data)) return null
    val element = try {
        Json.parseToJsonElement(data.toString(Charsets.UTF_8))
    } catch (_: Exception) {
        return null
    }
    val obj = element as? JsonObject ?: return null
    val value = obj[field] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: return null
    return primitive.contentOrNull
}

/** 质询页地址。覆盖根之后指向伪上游，不覆盖时是站点首页。 */
fun challengeEntryUrl(): String = Endpoints.siteRoot().trimEnd('/') + "/"

/** 把生产站点的绝对地址改到伪上游。已经改过的地址保持不动。 */
object UpstreamRewrite {
    fun apply(url: String, root: String): String {
        val normalized = root.trim().trimEnd('/')
        for (prefix in PREFIXES) {
            if (url.startsWith(prefix)) return normalized + url.substring(prefix.length)
        }
        return url
    }

    private val PREFIXES = listOf("https://chaturbate.com", "http://chaturbate.com")
}

/**
 * 仪器测试用的客户端：先改写到伪上游，主机仍是直播站点就拒绝，避免误连。
 */
class UpstreamHttp(
    private val root: String,
    private val inner: HTTPClient = HttpClientImpl(),
) : HTTPClient {
    override suspend fun send(request: HTTPRequest): HTTPResult {
        val url = UpstreamRewrite.apply(request.url, root)
        val host = hostOf(url)
        if (host == null || host == "chaturbate.com" || host.endsWith(".chaturbate.com")) {
            error("拒绝访问直播站点")
        }
        if (url == request.url) return inner.send(request)
        return inner.send(HTTPRequest(url, request.method, request.headers, request.body, request.timeoutSeconds))
    }
}

private const val PLAY_TAG = "vela.playchain"
private const val SIDECAR_CHALLENGE_MS = 600_000L
private const val SIDECAR_NETWORK_MS = 120_000L
