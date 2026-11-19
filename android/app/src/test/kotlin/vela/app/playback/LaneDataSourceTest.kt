package vela.app.playback

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.lang.reflect.Method
import java.net.URI
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import vela.app.spike.FakeUpstream
import vela.app.spike.WallClock
import vela.core.base.net.UserAgent
import vela.core.base.play.HLSAttribute
import vela.core.base.play.StreamRendition

/**
 * 对照 RelayMultiSource 的 9 个场景，加上请求头、合一映射、getUri、
 * 中断 / cancel、isRenewing，以及静音起播判据。
 * 分片地址用真实绝对地址（P1）。静音只关音轨，主列表仍含音轨（P1）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LaneDataSourceTest {
    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @Test(timeout = 30_000)
    fun testTwoSourcesServeConcurrently() {
        withHub { hub, upstream ->
            val a = register(hub, upstream, "a")
            val b = register(hub, upstream, "b")
            val bodies = arrayOfNulls<String>(2)
            val errors = arrayOfNulls<Throwable>(2)
            val threads = listOf(
                Thread {
                    try {
                        bodies[0] = readOk(hub, a.url).text
                    } catch (error: Throwable) {
                        errors[0] = error
                    }
                },
                Thread {
                    try {
                        bodies[1] = readOk(hub, b.url).text
                    } catch (error: Throwable) {
                        errors[1] = error
                    }
                },
            )
            threads.forEach { it.start() }
            threads.forEach { it.join(5_000) }
            assertNull(errors[0]?.toString(), errors[0])
            assertNull(errors[1]?.toString(), errors[1])
            assertTrue(bodies[0]!!.contains("/a/seg"))
            assertTrue(bodies[1]!!.contains("/b/seg"))
            assertFalse(bodies[0]!!.contains("/b/"))
            assertNotEquals(a.id, b.id)
        }
    }

    @Test(timeout = 30_000)
    fun testDeactivatingOneSourceKeepsTheOther() {
        withHub { hub, upstream ->
            val a = register(hub, upstream, "a")
            val b = register(hub, upstream, "b")
            assertEquals(200, open(hub, a.url).code)
            assertEquals(200, open(hub, b.url).code)
            hub.deactivate(a.id)
            assertEquals(404, open(hub, a.url).code)
            val again = open(hub, b.url)
            assertEquals(200, again.code)
            assertTrue(again.text.contains("/b/seg"))
        }
    }

    @Test(timeout = 30_000)
    fun testForbiddenRenewsOnlyThatSource() {
        withHub { hub, upstream ->
            upstream.script("/a/video.m3u8", listOf(403, 403, 403, 403))
            val renewedA = AtomicInteger()
            val renewedB = AtomicInteger()
            val fresh = StreamRendition(video = URI.create("${upstream.base}/a2/video.m3u8"))
            val a = register(hub, upstream, "a") {
                renewedA.incrementAndGet()
                fresh
            }
            val b = register(hub, upstream, "b") {
                renewedB.incrementAndGet()
                null
            }
            hub.setRefreshing(true, a.id)
            hub.setRefreshing(true, b.id)
            val ra = open(hub, a.url)
            assertEquals(ra.text, 200, ra.code)
            assertTrue(ra.text, ra.text.contains("/a2/seg"))
            val rb = open(hub, b.url)
            assertEquals(200, rb.code)
            assertTrue(rb.text.contains("/b/seg"))
            assertEquals(1, renewedA.get())
            assertEquals(0, renewedB.get())
            assertFalse(hub.isRenewing(b.id))
            assertTrue(upstream.count("/b/video.m3u8") > 0)
        }
    }

    /**
     * 旧地址的 GET 还在飞时，另一条请求已经把续期做完并清掉了在途任务。
     * 预算的 3 秒最短间隔会拒绝再续一次；这条请求必须跟着新地址重取，不能把 403 交回去。
     */
    @Test(timeout = 30_000)
    fun testInFlightForbiddenFollowsRenewalThatAlreadyFinished() {
        withHub { hub, upstream ->
            upstream.script("/a/video.m3u8", listOf(403, 403, 403, 403))
            val arrived = CountDownLatch(2)
            val releaseFollower = CountDownLatch(1)
            upstream.beforeResponse = { path, ordinal ->
                if (path == "/a/video.m3u8" && ordinal <= 2) {
                    arrived.countDown()
                    check(arrived.await(5, TimeUnit.SECONDS)) { "两条旧地址请求没有同时在途" }
                    if (ordinal == 2) {
                        check(releaseFollower.await(5, TimeUnit.SECONDS)) { "第二条 403 没有被放开" }
                    }
                }
            }
            val renewed = AtomicInteger()
            val fresh = StreamRendition(video = URI.create("${upstream.base}/a2/video.m3u8"))
            val handle = register(hub, upstream, "a") {
                renewed.incrementAndGet()
                fresh
            }
            val results = arrayOfNulls<Opened>(2)
            val errors = arrayOfNulls<Throwable>(2)
            val openers = List(2) { index ->
                Thread {
                    try {
                        results[index] = open(hub, handle.url)
                    } catch (error: Throwable) {
                        errors[index] = error
                    }
                }.apply {
                    isDaemon = true
                    name = "lane-open-$index"
                }
            }
            openers.forEach { it.start() }
            try {
                waitUntil(8_000) {
                    renewed.get() >= 1 &&
                        upstream.count("/a2/video.m3u8") >= 1 &&
                        !hub.isRenewing(handle.id)
                }
            } finally {
                releaseFollower.countDown()
            }
            openers.forEach { it.join(5_000) }
            openers.forEach { assertFalse(it.isAlive) }
            assertNull(errors[0]?.toString(), errors[0])
            assertNull(errors[1]?.toString(), errors[1])
            val first = results[0] ?: error("没有结果")
            val second = results[1] ?: error("没有结果")
            assertEquals("${first.text} / ${second.text} renews=${renewed.get()}", 200, first.code)
            assertEquals(second.text, 200, second.code)
            assertTrue(first.text, first.text.contains("/a2/seg"))
            assertTrue(second.text, second.text.contains("/a2/seg"))
            assertEquals(1, renewed.get())
        }
    }

    @Test(timeout = 30_000)
    fun testSuppressingAudioOfOneSourceKeepsTheOther() {
        withHub { hub, upstream ->
            val a = registerSeparate(hub, upstream, "a")
            val b = registerSeparate(hub, upstream, "b")
            hub.setRefreshing(true, a.id)
            hub.setRefreshing(true, b.id)
            waitUntil {
                upstream.count("/a/audio.m3u8") >= 1 && upstream.count("/b/audio.m3u8") >= 1
            }
            hub.setAudioSuppressed(true, b.id)
            val beforeA = upstream.count("/a/audio.m3u8")
            val beforeB = upstream.count("/b/audio.m3u8")
            val beforeVideoB = upstream.count("/b/video.m3u8")
            realSleep(3_500)
            assertTrue(upstream.count("/a/audio.m3u8") - beforeA >= 2)
            assertTrue(upstream.count("/b/audio.m3u8") - beforeB <= 1)
            assertTrue(upstream.count("/b/video.m3u8") - beforeVideoB >= 2)
        }
    }

    @Test(timeout = 30_000)
    fun testStopListenerInvalidatesOldAddresses() {
        withHub { hub, upstream ->
            val a = register(hub, upstream, "a")
            assertEquals(200, open(hub, a.url).code)
            hub.stopListener()
            assertEquals(0, hub.listeningPort)
            assertNotEquals(200, open(hub, a.url).code)
            assertTrue(hub.start())
            assertNotEquals(200, open(hub, a.url).code)
        }
    }

    @Test(timeout = 30_000)
    fun testRestartBeforeStopCompletesKeepsNewPort() {
        withHub { hub, upstream ->
            hub.stopListener()
            assertTrue(hub.start())
            val port = hub.listeningPort
            assertNotEquals(0, port)
            realSleep(500)
            assertEquals(port, hub.listeningPort)
            assertTrue(hub.start())
            realSleep(300)
            assertEquals(port, hub.listeningPort)
            val a = register(hub, upstream, "a")
            assertTrue(a.url.toString().startsWith("vela://lane/"))
            assertEquals(200, open(hub, a.url).code)
        }
    }

    @Test(timeout = 30_000)
    fun testRestartDuringStopKeepsNewlyRegisteredSource() {
        withHub { hub, upstream ->
            val old = register(hub, upstream, "a")
            var fresh: LaneHandle? = null
            hub.stopInterleaveForTesting = {
                assertTrue(hub.start())
                val deadline = realNanos() + 3_000_000_000L
                while (hub.listeningPort == 0 && realNanos() < deadline) Thread.sleep(20)
                fresh = register(hub, upstream, "b")
            }
            hub.stopListener()
            hub.stopInterleaveForTesting = null
            val registered = fresh ?: error("新源没有登记")
            assertEquals(200, open(hub, registered.url).code)
            assertEquals(404, open(hub, old.url).code)
        }
    }

    @Test(timeout = 20_000)
    fun testAllowStartOffRefusesToListen() {
        val hub = LaneHub()
        val upstream = ScriptUpstream()
        try {
            hub.allowStart = false
            assertFalse(hub.start())
            assertEquals(0, hub.listeningPort)
            assertNull(hub.localURL(muxed(upstream, "a"), Double.POSITIVE_INFINITY, false))
            hub.allowStart = true
            assertTrue(hub.start())
            assertNotEquals(0, hub.listeningPort)
        } finally {
            hub.stopListener()
            upstream.close()
        }
    }

    @Test(timeout = 30_000)
    fun testSuspendedAfterBindingRefusesNewSources() {
        withHub { hub, upstream ->
            val existing = register(hub, upstream, "a")
            hub.allowStart = false
            assertNotEquals(0, hub.listeningPort)
            assertNull(hub.localURL(muxed(upstream, "b"), Double.POSITIVE_INFINITY, false))
            assertEquals(200, open(hub, existing.url).code)
            hub.allowStart = true
            val resumed = register(hub, upstream, "c")
            assertEquals(200, open(hub, resumed.url).code)
        }
    }

    @Test(timeout = 30_000)
    fun testPlaylistAndSegmentCarryUserAgentAndReferer() {
        withHub { hub, upstream ->
            val handle = register(hub, upstream, "a")
            val opened = readOk(hub, handle.url)
            val segment = opened.text.lineSequence().map { it.trim() }.first { it.startsWith("http://") }
            readOk(hub, Uri.parse(segment), handle.id)
            val interesting = upstream.headerHits().filter { it.path.endsWith(".m3u8") || it.path.endsWith(".ts") }
            assertTrue(interesting.any { it.path.endsWith(".m3u8") })
            assertTrue(interesting.any { it.path.endsWith(".ts") })
            for (hit in interesting) {
                assertEquals(hit.path, UserAgent.safari, header(hit, "User-Agent"))
                assertEquals(hit.path, LaneHttpHeaders.REFERER, header(hit, "Referer"))
            }
        }
    }

    @Test(timeout = 30_000)
    fun testMuxedRenditionMapsVideoOnly() {
        withHub { hub, upstream ->
            val handle = register(hub, upstream, "mux")
            assertTrue(handle.url.toString().endsWith("/video.m3u8"))
            assertEquals(setOf("video"), hub.mappedTracks(handle.id))
            assertEquals(404, open(hub, velaUri(handle.id, "audio.m3u8")).code)
            val master = open(hub, velaUri(handle.id, "master.m3u8"))
            assertEquals(200, master.code)
            assertTrue(master.text.contains("#EXTINF"))
            assertFalse(master.text.contains("EXT-X-STREAM-INF"))
            assertFalse(master.text.contains("audio.m3u8"))
        }
    }

    @Test(timeout = 30_000)
    fun testGetUriReportsSyntheticPlaylistAndRealSegment() {
        withHub { hub, upstream ->
            val handle = register(hub, upstream, "a")
            val opened = readOk(hub, handle.url)
            assertEquals(handle.url.toString(), opened.reported.toString())
            assertTrue(opened.text.contains("#EXT-X-VERSION:7"))
            assertFalse(opened.text.contains("SERVER-CONTROL"))
            assertFalse(opened.text.contains("EXT-X-PART"))
            val segment = opened.text.lineSequence().map { it.trim() }.first { it.startsWith("http://") }
            assertFalse(segment.startsWith("vela:"))
            val loaded = readOk(hub, Uri.parse(segment), handle.id)
            assertEquals(segment, loaded.reported.toString())
        }
    }

    @Test(timeout = 20_000)
    fun testInterruptReturnsWithin200ms() {
        blockingRenew { hub, handle, gate, opener, thrown ->
            opener.interrupt()
            finishSoon(opener)
            gate.release()
            assertTrue(thrown() is InterruptedIOException)
        }
    }

    @Test(timeout = 20_000)
    fun testCancelReturnsWithin200ms() {
        blockingRenew { hub, handle, gate, opener, _ ->
            hub.deactivate(handle.id)
            finishSoon(opener)
            gate.release()
        }
    }

    @Test(timeout = 30_000)
    fun testIsRenewingVisibleThroughRefetch() {
        withHub { hub, upstream ->
            upstream.script("/a/video.m3u8", listOf(403))
            upstream.holdPaths = setOf("/a2/video.m3u8")
            val fresh = StreamRendition(video = URI.create("${upstream.base}/a2/video.m3u8"))
            val handle = register(hub, upstream, "a") { fresh }
            val body = arrayOfNulls<String>(1)
            val error = arrayOfNulls<Throwable>(1)
            val opener = Thread {
                try {
                    body[0] = readOk(hub, handle.url).text
                } catch (thrown: Throwable) {
                    error[0] = thrown
                }
            }
            opener.start()
            assertTrue(upstream.held.await(5, TimeUnit.SECONDS))
            assertTrue(hub.isRenewing(handle.id))
            upstream.releaseHold.countDown()
            opener.join(5_000)
            assertFalse(opener.isAlive)
            assertNull(error[0]?.toString(), error[0])
            assertFalse(hub.isRenewing(handle.id))
            assertTrue(body[0]!!.contains("/a2/seg"))
        }
    }

    @Test(timeout = 150_000)
    fun testMutedStartSteadyWindow() {
        val upstream = FakeUpstream()
        val hub = LaneHub()
        try {
            assertTrue(hub.start())
            val rendition = StreamRendition(
                video = URI.create(upstream.videoPlaylistUrl()),
                audio = URI.create(upstream.audioPlaylistUrl()),
                videoAttributes = HLSAttribute.parse(
                    "BANDWIDTH=120000,AVERAGE-BANDWIDTH=80000," +
                        "CODECS=\"avc1.42C01E,mp4a.40.2\",RESOLUTION=160x90,FRAME-RATE=10.000,AUDIO=\"aud\"",
                ),
                audioAttributes = HLSAttribute.parse(
                    "TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"main\",DEFAULT=YES,AUTOSELECT=YES",
                ),
            )
            val handle = hub.localURL(rendition, Double.POSITIVE_INFINITY, false)
                ?: error("没有登记")
            assertTrue(handle.url.toString().endsWith("/master.m3u8"))
            val master = readOk(hub, handle.url).text
            assertTrue(master.contains("audio.m3u8"))
            assertTrue(master.contains("video.m3u8"))
            val policy = VelaLoadErrorPolicy()
            val drive = PlaybackDrive(hub, handle, policy) { upstream.startPublishing() }
            try {
                assertTrue(drive.awaitFrame(20_000))
                val from = WallClock.millis()
                drive.play(60_000)
                val hits = upstream.snapshotHits().filter { it.startedMs >= from }
                val audioPlaylists = hits.count { it.path.endsWith("audio.m3u8") }
                val audioSegments = hits.count { it.path.endsWith(".aac") }
                val videoPlaylists = hits.count { it.path.endsWith("video.m3u8") }
                assertTrue("audio.m3u8=$audioPlaylists", audioPlaylists <= 1)
                assertEquals(0, audioSegments)
                assertTrue("video.m3u8=$videoPlaylists", videoPlaylists > 0)
                assertTrue(drive.rendered() > 0)
            } finally {
                drive.close()
            }
        } finally {
            hub.stopListener()
            upstream.close()
        }
    }

    /**
     * 伪上游的一次性回退经常没有独立 DISCONTINUITY 行。
     * 先到的调用方（刷新线程）把回退取走后，后到的播放器读到的是前进列表，也必须看到重置。
     * 只退 1、以及媒体序号前进但完整分片变少，都不是重置。
     */
    @Test(timeout = 30_000)
    fun testSequenceRewindWithoutDiscontinuityLatchesForLaterFetch() {
        withHub { hub, upstream ->
            upstream.playlistText = { path, ordinal ->
                if (!path.endsWith("/video.m3u8")) {
                    null
                } else {
                    when (ordinal) {
                        1 -> mediaPlaylist(sequence = 20)
                        2 -> mediaPlaylist(sequence = 19)
                        3 -> mediaPlaylist(sequence = 21, segments = 1)
                        4 -> mediaPlaylist(sequence = 22)
                        5 -> mediaPlaylist(sequence = 18)
                        else -> mediaPlaylist(sequence = 30)
                    }
                }
            }
            val handle = register(hub, upstream, "rw")
            readOk(hub, handle.url)
            readOk(hub, handle.url)
            readOk(hub, handle.url)
            val advanced = readOk(hub, handle.url)
            assertTrue(advanced.text, advanced.text.contains("MEDIA-SEQUENCE:22"))
            assertPlaylistReset(hub, handle.url, handle.id)
            assertPlaylistReset(hub, handle.url, handle.id)
            assertTrue(upstream.count("/rw/video.m3u8") >= 6)
        }
    }

    @Test(timeout = 30_000)
    fun testSingleStepRewindWithDiscontinuityStillResets() {
        withHub { hub, upstream ->
            upstream.playlistText = { path, ordinal ->
                if (!path.endsWith("/video.m3u8")) {
                    null
                } else if (ordinal == 1) {
                    mediaPlaylist(sequence = 20)
                } else {
                    mediaPlaylist(sequence = 19, discontinuity = true)
                }
            }
            val handle = register(hub, upstream, "disc")
            readOk(hub, handle.url)
            assertPlaylistReset(hub, handle.url, handle.id)
        }
    }

    private fun assertPlaylistReset(hub: LaneHub, uri: Uri, ownerId: String) {
        try {
            hub.load(uri, ownerId)
            fail("应该抛出 PlaylistResetException uri=$uri")
        } catch (error: HlsPlaylistTracker.PlaylistResetException) {
            assertEquals(uri, error.url)
        }
    }

    private fun mediaPlaylist(sequence: Int, segments: Int = 3, discontinuity: Boolean = false): String {
        val lines = ArrayList<String>()
        lines.add("#EXTM3U")
        lines.add("#EXT-X-VERSION:6")
        lines.add("#EXT-X-TARGETDURATION:2")
        lines.add("#EXT-X-MEDIA-SEQUENCE:$sequence")
        if (discontinuity) lines.add("#EXT-X-DISCONTINUITY")
        for (index in 0 until segments) {
            lines.add("#EXTINF:2.0,")
            lines.add("seg${sequence + index}.ts")
        }
        return lines.joinToString("\n") + "\n"
    }

    private fun blockingRenew(
        block: (LaneHub, LaneHandle, Semaphore, Thread, () -> Throwable?) -> Unit,
    ) {
        val hub = LaneHub()
        val upstream = ScriptUpstream()
        val gate = Semaphore(0)
        try {
            assertTrue(hub.start())
            upstream.script("/a/video.m3u8", listOf(403))
            val entered = CountDownLatch(1)
            val handle = register(hub, upstream, "a") {
                entered.countDown()
                gate.tryAcquire(30, TimeUnit.SECONDS)
                null
            }
            val thrown = arrayOfNulls<Throwable>(1)
            val ds = hub.openDataSource(handle.id)
            val opener = Thread {
                try {
                    ds.open(DataSpec(handle.url))
                } catch (error: Throwable) {
                    thrown[0] = error
                } finally {
                    runCatching { ds.close() }
                }
            }
            opener.isDaemon = true
            opener.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            block(hub, handle, gate, opener, { thrown[0] })
        } finally {
            gate.release()
            hub.stopListener()
            upstream.close()
        }
    }

    private fun finishSoon(thread: Thread) {
        val start = realNanos()
        while (thread.isAlive && realNanos() - start < 200_000_000L) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(30))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            thread.join(20)
        }
        val elapsedMs = (realNanos() - start) / 1_000_000L
        assertFalse("线程在 ${elapsedMs}ms 后仍未返回", thread.isAlive)
        assertTrue("返回用了 ${elapsedMs}ms", elapsedMs < 200)
    }

    private fun withHub(block: (LaneHub, ScriptUpstream) -> Unit) {
        val hub = LaneHub()
        val upstream = ScriptUpstream()
        try {
            assertTrue(hub.start())
            block(hub, upstream)
        } finally {
            hub.stopListener()
            upstream.close()
        }
    }

    private fun register(
        hub: LaneHub,
        upstream: ScriptUpstream,
        name: String,
        renew: (suspend () -> StreamRendition?)? = null,
    ): LaneHandle = hub.localURL(muxed(upstream, name), Double.POSITIVE_INFINITY, false, renew)
        ?: error("没有登记 $name")

    private fun registerSeparate(hub: LaneHub, upstream: ScriptUpstream, name: String): LaneHandle {
        val rendition = StreamRendition(
            video = URI.create("${upstream.base}/$name/video.m3u8"),
            audio = URI.create("${upstream.base}/$name/audio.m3u8"),
            videoAttributes = HLSAttribute.parse("BANDWIDTH=800000,AUDIO=\"aud\""),
            audioAttributes = HLSAttribute.parse("TYPE=AUDIO,GROUP-ID=\"aud\""),
        )
        return hub.localURL(rendition, Double.POSITIVE_INFINITY, false) ?: error("没有登记 $name")
    }

    private fun muxed(upstream: ScriptUpstream, name: String): StreamRendition =
        StreamRendition(video = URI.create("${upstream.base}/$name/video.m3u8"))

    private fun open(hub: LaneHub, uri: Uri, ownerId: String? = null): Opened {
        val source = hub.openDataSource(ownerId)
        return try {
            source.open(DataSpec(uri))
            val reported = source.uri
            val text = readAll(source).toString(Charsets.UTF_8)
            Opened(200, text, reported)
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            Opened(error.responseCode, "", null)
        } finally {
            source.close()
        }
    }

    private fun readOk(hub: LaneHub, uri: Uri, ownerId: String? = null): Opened {
        val opened = open(hub, uri, ownerId)
        assertEquals(opened.text, 200, opened.code)
        return opened
    }

    private fun readAll(source: androidx.media3.datasource.DataSource): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun header(hit: UpstreamHeaderHit, name: String): String? =
        hit.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = realNanos() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (realNanos() > end) fail("等待超时")
            Thread.sleep(30)
        }
    }

    private fun realSleep(ms: Long) {
        val end = realNanos() + ms * 1_000_000L
        while (realNanos() < end) Thread.sleep(20)
    }

    private data class Opened(val code: Int, val text: String, val reported: Uri?)

    companion object {
        private val realNano: Method = System::class.java.getMethod("nanoTime")

        fun realNanos(): Long = realNano.invoke(null) as Long
    }
}
