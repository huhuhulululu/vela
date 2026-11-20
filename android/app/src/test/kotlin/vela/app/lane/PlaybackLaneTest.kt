package vela.app.lane

import android.net.Uri
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.playback.LaneHub
import vela.app.playback.ScriptUpstream
import vela.app.playback.VelaLoadErrorPolicy
import vela.core.base.lane.LaneID
import vela.core.base.play.StreamRendition
import vela.core.hls.FailureCode
import vela.core.hls.MuteAudioPolicy
import vela.core.hls.PlaybackErrorCopy
import vela.core.hls.PlaybackFailureClassifier
import vela.core.hls.PlaybackPolicy
import vela.core.hls.UrlErrors

/**
 * 先锁住暂停零网络、15/30 秒恢复、看门狗读续期、R1 预算、静默重试一次、失败分类。
 * 播放器是假的；后台刷新用真的 [LaneHub]，才能数到请求。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackLaneTest {
    private var upstream: ScriptUpstream? = null
    private var hub: LaneHub? = null
    private var lane: PlaybackLane? = null

    @After
    fun tearDown() {
        lane?.release()
        hub?.stopListener()
        upstream?.close()
        LaneLog.clear()
    }

    @Test(timeout = 20_000)
    fun testPauseStopZeroNetworkAndShortResumeKeepsSource() {
        val fixture = open()
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        waitUntil(5_000) { fixture.upstream.count("/live.m3u8") > 0 }
        assertTrue("起播后应该有播放列表请求", fixture.upstream.count("/live.m3u8") > 0)
        val source = fixture.lane.activeSourceId
        assertNotNull(source)

        fixture.lane.setAllowed(false)
        assertTrue("暂停必须 stop 播放器", fixture.player.stops.get() >= 1)
        Thread.sleep(400)
        val settled = fixture.upstream.count("/live.m3u8")
        Thread.sleep(1_500)
        assertEquals("暂停后零网络", settled, fixture.upstream.count("/live.m3u8"))

        fixture.clock.advanceSeconds(10.0)
        fixture.lane.setAllowed(true)
        assertEquals("15 秒内续播不换源", source, fixture.lane.activeSourceId)
        assertTrue(fixture.refreshes.isEmpty())
        assertTrue("续播要重新 prepare", fixture.player.medias.size >= 2)
        assertEquals(fixture.player.medias.first(), fixture.player.medias.last())
        waitUntil(3_000) { fixture.upstream.count("/live.m3u8") > settled }
        assertTrue(fixture.upstream.count("/live.m3u8") > settled)
    }

    @Test(timeout = 15_000)
    fun testResumeTiersFollow15And30Seconds() {
        val fixture = open()
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        val first = fixture.lane.activeSourceId

        fixture.lane.setAllowed(false)
        fixture.clock.advanceSeconds(PlaybackPolicy.liveRejoinAfter)
        fixture.lane.setAllowed(true)
        assertEquals(first, fixture.lane.activeSourceId)
        assertTrue(fixture.refreshes.isEmpty())

        fixture.lane.setAllowed(false)
        fixture.clock.advanceSeconds(PlaybackPolicy.liveRejoinAfter + 0.01)
        fixture.lane.setAllowed(true)
        val rejoined = fixture.lane.activeSourceId
        assertNotNull(rejoined)
        assertTrue("超过 15 秒要重建播放项", rejoined != first)
        assertTrue(fixture.refreshes.isEmpty())

        fixture.lane.setAllowed(false)
        fixture.clock.advanceSeconds(PlaybackPolicy.reresolveAfter)
        fixture.lane.setAllowed(true)
        assertEquals(listOf("room-a" to false), fixture.refreshes)
        assertTrue(fixture.lane.refreshPending)
        assertFalse(fixture.player.wantPlay)
    }

    @Test
    fun testHiddenFor30SecondsReleasesAndResumeReresolves() {
        val fixture = open()
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        fixture.lane.setHidden(true)
        fixture.lane.setAllowed(false)
        val nap = fixture.sleeper.naps.single { it.seconds == PlaybackPolicy.releasePausedItemAfter }
        fixture.sleeper.fire(nap.seconds, fixture.clock)
        assertNull("窗口不可见满 30 秒要放掉源", fixture.lane.activeSourceId)
        fixture.lane.setAllowed(true)
        assertEquals(listOf("room-a" to false), fixture.refreshes)
    }

    @Test
    fun testFullStopAlsoZeroNetwork() {
        val fixture = open()
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        waitUntil(5_000) { fixture.upstream.count("/live.m3u8") > 0 }
        fixture.lane.stop()
        Thread.sleep(400)
        val settled = fixture.upstream.count("/live.m3u8")
        Thread.sleep(1_500)
        assertEquals(settled, fixture.upstream.count("/live.m3u8"))
        assertNull(fixture.lane.activeSourceId)
        assertFalse(fixture.lane.failed)
    }

    @Test
    fun testWatchdogRearmsWhileRenewingOrBufferGrows() {
        val fixture = open()
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        fixture.player.emitPlaying(true)
        fixture.player.bufferedEnd = 5.0
        fixture.player.emitPlaying(false)
        assertTrue(fixture.sleeper.naps.any { kotlin.math.abs(it.seconds - PlaybackPolicy.stuckAfter) < 0.001 })

        fixture.renewing.set(true)
        fixture.sleeper.fire(PlaybackPolicy.stuckAfter, fixture.clock)
        assertTrue(fixture.refreshes.isEmpty())
        assertFalse(fixture.lane.silentRetryUsed)
        assertFalse(fixture.lane.failed)
        assertTrue(
            "naps=${fixture.sleeper.naps.map { it.seconds }} log=${LaneLog.snapshot()}",
            fixture.sleeper.naps.any { kotlin.math.abs(it.seconds - PlaybackPolicy.stuckAfter) < 0.001 },
        )

        fixture.renewing.set(false)
        fixture.player.bufferedEnd = 5.0 + PlaybackPolicy.bufferAdvanceEpsilon + 0.05
        fixture.sleeper.fire(PlaybackPolicy.stuckAfter, fixture.clock)
        assertTrue(fixture.refreshes.isEmpty())
        assertFalse(fixture.lane.silentRetryUsed)
        assertTrue(fixture.sleeper.naps.any { kotlin.math.abs(it.seconds - PlaybackPolicy.stuckAfter) < 0.001 })
    }

    @Test
    fun testWatchdogRecoversOnceThenGivesUp() {
        val fixture = open()
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        fixture.player.emitPlaying(true)
        fixture.player.bufferedEnd = 3.0
        fixture.player.emitPlaying(false)
        fixture.sleeper.fire(PlaybackPolicy.stuckAfter, fixture.clock)
        assertEquals(listOf("room-a" to true), fixture.refreshes)
        assertTrue(fixture.lane.silentRetryUsed)
        assertTrue(fixture.lane.refreshPending)
        assertFalse(fixture.lane.failed)

        fixture.lane.refreshFinished(reason = null, report = false)
        fixture.player.emitPlaying(true)
        fixture.player.emitPlaying(false)
        fixture.player.bufferedEnd = 3.0
        fixture.sleeper.fire(PlaybackPolicy.stuckAfter, fixture.clock)
        assertEquals(1, fixture.refreshes.size)
        assertTrue(fixture.lane.failed)
        assertEquals("stuck-giveup", fixture.lane.lastFailure?.stage)
    }

    @Test
    fun testStartupTimeoutDoesNotRetry() {
        val fixture = open()
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        assertEquals(PlaybackPolicy.startupTimeout, fixture.sleeper.naps.single().seconds, 0.001)
        fixture.sleeper.fire(PlaybackPolicy.startupTimeout, fixture.clock)
        assertTrue(fixture.lane.failed)
        assertTrue(fixture.refreshes.isEmpty())
        assertEquals("start-timeout", fixture.lane.lastFailure?.stage)
        assertEquals(
            PlaybackErrorCopy.startTimeoutMessage(emptyList()),
            fixture.lane.lastError,
        )
    }

    @Test
    fun testStartupCancelsOncePlaying() {
        val fixture = open()
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        fixture.player.emitPlaying(true)
        assertTrue(fixture.sleeper.naps.none { it.seconds == PlaybackPolicy.startupTimeout })
        assertFalse(fixture.lane.failed)
        assertEquals("room-a", fixture.lane.readyUsername)
        assertEquals("room-a", fixture.lane.playingUsername)
    }

    @Test
    fun testSilentRetryOnceThenFailureCopy() {
        val fixture = open()
        val chain = listOf(FailureCode(UrlErrors.DOMAIN, UrlErrors.TIMED_OUT))
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        fixture.player.emitError(reset = false, chain = chain)
        assertEquals(listOf("room-a" to true), fixture.refreshes)
        assertFalse(fixture.lane.failed)
        assertNull(fixture.lane.lastError)
        assertTrue(fixture.lane.silentRetryUsed)

        fixture.player.emitError(reset = false, chain = chain)
        assertEquals(1, fixture.refreshes.size)
        assertTrue(fixture.lane.failed)
        assertEquals(PlaybackErrorCopy.failureMessage(chain), fixture.lane.lastError)
        assertEquals("item-failed", fixture.lane.lastFailure?.stage)
    }

    @Test
    fun testRetryFlagResetsWhenRoomChanges() {
        val fixture = open()
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        fixture.player.emitError(reset = false, chain = emptyList())
        assertTrue(fixture.lane.silentRetryUsed)
        fixture.lane.play(fixture.rendition, "room-b", 800_000.0, allowed = true, isRetry = false)
        assertFalse(fixture.lane.silentRetryUsed)
    }

    @Test
    fun testFailureFilterDegradeDoesNotRetryOrFail() {
        val fixture = open()
        var degraded: PlaybackFailureInfo? = null
        fixture.lane.failureFilter = { PlaybackFailureClassifier.Outcome.RESOURCE_DEGRADE }
        fixture.lane.resourceDegraded = { degraded = it }
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        val chain = listOf(
            FailureCode(
                PlaybackFailureClassifier.media3ErrorDomain,
                PlaybackFailureClassifier.decoderInitFailedCode,
            ),
        )
        fixture.player.emitError(reset = false, chain = chain)
        assertNotNull(degraded)
        assertEquals("item-failed", degraded?.stage)
        assertFalse(fixture.lane.failed)
        assertFalse(fixture.lane.silentRetryUsed)
        assertTrue(fixture.refreshes.isEmpty())
    }

    @Test
    fun testFatalSkipsSilentRetry() {
        val fixture = open()
        fixture.lane.failureFilter = { PlaybackFailureClassifier.Outcome.FATAL }
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        fixture.player.emitError(reset = false, chain = emptyList())
        assertTrue(fixture.lane.failed)
        assertTrue(fixture.refreshes.isEmpty())
        assertFalse(fixture.lane.silentRetryUsed)
    }

    @Test
    fun testBehindLiveRejoinsWithoutOtherErrorOrReprepare() {
        val fixture = open()
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        val medias = fixture.player.medias.size
        fixture.player.emitBehindLive()
        fixture.player.emitBehindLive()
        assertEquals(0, fixture.lane.otherErrorCount)
        assertEquals(0, fixture.lane.reprepareCount)
        assertFalse(fixture.lane.failed)
        assertFalse(fixture.lane.silentRetryUsed)
        assertTrue(fixture.refreshes.isEmpty())
        assertEquals(medias + 2, fixture.player.medias.size)

        fixture.player.emitBehindLive()
        assertEquals(1, fixture.lane.otherErrorCount)
        assertEquals(listOf("room-a" to true), fixture.refreshes)
        assertFalse(fixture.lane.failed)
    }

    @Test
    fun testPlaylistResetReprepareBudgetDoesNotConsumeRetry() {
        val fixture = open()
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        val first = fixture.lane.activeSourceId
        fixture.player.emitError(reset = true, chain = emptyList())
        fixture.player.emitError(reset = true, chain = emptyList())
        fixture.player.emitError(reset = true, chain = emptyList())
        assertEquals(2, fixture.lane.reprepareCount)
        assertEquals(2, fixture.lane.resetErrorCount)
        assertEquals(3, fixture.player.medias.size)
        assertFalse(fixture.lane.failed)
        assertFalse(fixture.lane.silentRetryUsed)
        assertTrue(fixture.refreshes.isEmpty())
        assertTrue(fixture.lane.activeSourceId != first)

        fixture.clock.advanceSeconds(61.0)
        fixture.player.emitError(reset = true, chain = emptyList())
        assertEquals(3, fixture.lane.reprepareCount)
        assertFalse(fixture.lane.failed)
    }

    @Test
    fun testMutedDoesNotReportMissingAudio() {
        val fixture = open()
        assertTrue(fixture.player.muted)
        assertEquals(0f, fixture.player.volumeLevel)
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        fixture.player.audioEnabled = 0
        fixture.player.group = MuteAudioPolicy.AudibleGroupState(
            loaded = true,
            optionCount = 0,
            selectedIndex = null,
            allowsEmptySelection = true,
        )
        fixture.player.emitPlaying(true)
        fixture.sleeper.fire(PlaybackPolicy.audioCheckDelay, fixture.clock)
        fixture.sleeper.fire(PlaybackPolicy.audioRecheckDelay, fixture.clock)
        assertTrue(fixture.noAudio.isEmpty())
    }

    @Test
    fun testUnmutedMissingAudioNotifiesOnce() {
        val fixture = open()
        fixture.lane.setVolume(0.2, muted = false)
        assertFalse(fixture.player.muted)
        assertEquals(0.2f, fixture.player.volumeLevel)
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        fixture.player.audioEnabled = 0
        fixture.player.group = MuteAudioPolicy.AudibleGroupState(
            loaded = true,
            optionCount = 0,
            selectedIndex = null,
            allowsEmptySelection = true,
        )
        fixture.player.emitPlaying(true)
        fixture.sleeper.fire(PlaybackPolicy.audioCheckDelay, fixture.clock)
        assertTrue(fixture.noAudio.isEmpty())
        fixture.sleeper.fire(PlaybackPolicy.audioRecheckDelay, fixture.clock)
        assertEquals(listOf("room-a"), fixture.noAudio)
    }

    @Test
    fun testFirstFrameProbeAndLog() {
        val fixture = open()
        val frames = mutableListOf<Pair<String, Long>>()
        fixture.lane.probe = { lane, at -> frames.add(lane to at) }
        fixture.lane.play(fixture.rendition, "room-a", 800_000.0, allowed = true)
        fixture.player.emitVideoSize(160, 90)
        fixture.player.emitFirstFrame()
        assertEquals(1, frames.size)
        assertEquals("primary", frames.single().first)
        assertTrue(LaneLog.snapshot().any { it.contains("first-frame lane=primary") && it.contains("size=160x90") })
        fixture.player.emitFirstFrame()
        assertEquals(1, frames.size)
    }

    private fun open(): Fixture {
        val upstream = ScriptUpstream()
        val hub = LaneHub()
        check(hub.start())
        val clock = MutableClock()
        val sleeper = ManualSleeper()
        val player = FakeLanePlayer()
        val renewing = AtomicBoolean(false)
        val refreshes = mutableListOf<Pair<String, Boolean>>()
        val noAudio = mutableListOf<String>()
        val lane = PlaybackLane(
            id = LaneID.PRIMARY,
            hub = hub,
            player = player,
            clock = clock,
            sleeper = sleeper,
            isRenewing = { renewing.get() },
        )
        lane.refreshRequested = { name, isRetry -> refreshes.add(name to isRetry) }
        lane.noAudioDetected = { noAudio.add(it) }
        this.upstream = upstream
        this.hub = hub
        this.lane = lane
        return Fixture(
            upstream = upstream,
            hub = hub,
            clock = clock,
            sleeper = sleeper,
            player = player,
            renewing = renewing,
            refreshes = refreshes,
            noAudio = noAudio,
            lane = lane,
            rendition = StreamRendition(video = URI.create("${upstream.base}/live.m3u8")),
        )
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(40)
        }
        assertTrue(condition())
    }
}

internal class Fixture(
    val upstream: ScriptUpstream,
    val hub: LaneHub,
    val clock: MutableClock,
    val sleeper: ManualSleeper,
    val player: FakeLanePlayer,
    val renewing: AtomicBoolean,
    val refreshes: MutableList<Pair<String, Boolean>>,
    val noAudio: MutableList<String>,
    val lane: PlaybackLane,
    val rendition: StreamRendition,
)

internal class MutableClock(start: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
    private var current: Instant = start

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current

    fun advanceSeconds(seconds: Double) {
        current = current.plusNanos((seconds * 1_000_000_000.0).toLong())
    }
}

internal class ManualSleeper : Sleeper {
    class Nap(val seconds: Double, val onDone: () -> Unit)

    val naps = ArrayList<Nap>()

    override fun sleep(seconds: Double, onDone: () -> Unit): Cancellable {
        val nap = Nap(seconds, onDone)
        naps.add(nap)
        return Cancellable { naps.remove(nap) }
    }

    fun fire(seconds: Double, clock: MutableClock?) {
        val index = naps.indexOfFirst { kotlin.math.abs(it.seconds - seconds) < 0.001 }
        if (index < 0) {
            error("没有 ${seconds}s 的等待，现有 ${naps.map { it.seconds }}")
        }
        val nap = naps.removeAt(index)
        clock?.advanceSeconds(nap.seconds)
        nap.onDone()
    }
}

internal class FakeLanePlayer : LanePlayer {
    val stops = AtomicInteger()
    val medias = mutableListOf<Uri>()
    var callbacks: LanePlayerListener? = null
    var bufferedEnd: Double? = 0.0
    var muted: Boolean = false
    var volumeLevel: Float = 1f
    var wantPlay: Boolean = false
    override var isPrepared: Boolean = false
    var audioEnabled: Int = 0
    var group: MuteAudioPolicy.AudibleGroupState = MuteAudioPolicy.AudibleGroupState.unknown
    var rendered: Int = 0

    override fun setMedia(url: Uri, ownerId: String, policy: VelaLoadErrorPolicy, playWhenReady: Boolean) {
        medias.add(url)
        wantPlay = playWhenReady
        isPrepared = true
    }

    override fun setPlayWhenReady(play: Boolean) {
        wantPlay = play
    }

    override fun stop() {
        stops.incrementAndGet()
        isPrepared = false
        wantPlay = false
    }

    override fun release() {
        stop()
    }

    override fun setMuted(muted: Boolean, volume: Float) {
        this.muted = muted
        this.volumeLevel = volume
    }

    override fun attachSurface(surface: android.view.Surface?) = Unit

    override fun bufferedEndSeconds(): Double? = bufferedEnd

    override fun renderedOutputBufferCount(): Int = rendered

    override fun audioEnabledCount(): Int = audioEnabled

    override fun volume(): Float = volumeLevel

    override fun audibleGroup(): MuteAudioPolicy.AudibleGroupState = group

    override fun setListener(listener: LanePlayerListener?) {
        callbacks = listener
    }

    fun emitPlaying(playing: Boolean) {
        callbacks?.onPlaying(playing)
    }

    fun emitError(reset: Boolean, chain: List<FailureCode>) {
        callbacks?.onPlaybackError(reset, chain)
    }

    fun emitBehindLive() {
        callbacks?.onBehindLiveWindow()
    }

    fun emitFirstFrame() {
        rendered += 1
        callbacks?.onFirstFrame()
    }

    fun emitVideoSize(width: Int, height: Int) {
        callbacks?.onVideoSize(width, height)
    }
}
