package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.model.FeedSource
import vela.core.base.net.HTTPResult
import vela.core.curation.Curation
import vela.core.lifecycle.PhoneScenePhase

/**
 * 收藏批次不能脱离舞台生命周期：挂起或关闭后不得再发起查询，也不得写回缓存。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoriteBatchLifecycleTest {
    private val harness = StageHarness()
    private lateinit var settings: Settings
    private lateinit var directory: java.nio.file.Path
    private val clock = ManualClock()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context)
        directory = Files.createTempDirectory("vela-favorite-batch")
    }

    @After
    fun tearDown() {
        deleteTree(directory)
        harness.close()
    }

    @Test
    fun testSuspendStopsFavoriteBatchInsteadOfIssuingMoreLookups() = harness.onStage {
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        val http = blockingChat(started, cancelled)
        val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        try {
            stage.curation = Curation(favorites = listOf("a", "b", "c", "d", "e", "f"))
            stage.track { stage.checkFavorites() }
            val sawBatch = withTimeoutOrNull(3_000) {
                while (started.get() < 4) delay(10)
                true
            }
            assertTrue("收藏批次没有发出并发查询", sawBatch == true)
            stage.cancelSharedWork()
            val stopped = withTimeoutOrNull(1_500) {
                while (cancelled.get() < started.get()) delay(10)
                true
            }
            delay(150)
            assertTrue(
                "挂起后收藏批次仍在跑 started=${started.get()} cancelled=${cancelled.get()}",
                stopped == true,
            )
            assertEquals("挂起后又发起了新的收藏查询", 4, started.get())
            assertNull(stage.favoriteStatus.status("a"))
        } finally {
            stage.close()
        }
    }

    @Test
    fun testCloseStopsFavoriteBatch() = harness.onStage {
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        val http = blockingChat(started, cancelled)
        val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        stage.curation = Curation(favorites = listOf("a", "b", "c", "d", "e", "f"))
        stage.track { stage.checkFavorites() }
        assertTrue(withTimeoutOrNull(3_000) {
            while (started.get() < 1) delay(10)
            true
        } == true)
        stage.close()
        val stopped = withTimeoutOrNull(1_500) {
            while (cancelled.get() < 1) delay(10)
            true
        }
        assertTrue("关闭舞台后收藏查询仍挂着 started=${started.get()}", stopped == true)
    }

    @Test
    fun testStaleEpochDoesNotCommitFavoriteCache() = harness.onStage {
        lateinit var stage: StageViewModel
        val http = object : HTTPClient {
            override suspend fun send(request: HTTPRequest): HTTPResult {
                if (!request.url.contains("chatvideocontext")) error("意外请求 ${request.url}")
                stage.lifecycleEpoch.suspend()
                return HTTPResult(
                    status = 200,
                    data = """{"room_status":"offline"}""".toByteArray(),
                )
            }
        }
        stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        try {
            stage.curation = Curation(favorites = listOf("alice"))
            val job = stage.track { stage.resolveFavorites() }
            job.join()
            assertNull(stage.favoriteStatus.status("alice"))
        } finally {
            stage.close()
        }
    }


    @Test
    fun testFavoriteCheckCancelledByBackgroundCanRefreshAgain() = harness.onStage {
        settings.source = FeedSource.popular
        val started = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val http = object : HTTPClient {
            override suspend fun send(request: HTTPRequest): HTTPResult {
                if (!request.url.contains("chatvideocontext")) error("意外请求 ${request.url}")
                val turn = started.incrementAndGet()
                if (turn == 1) release.await()
                return HTTPResult(
                    status = 200,
                    data = """{"room_status":"public","broadcaster_username":"alice"}""".toByteArray(),
                )
            }
        }
        val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        val wiring = LifecycleWiring(stage, IdleFavoriteSession())
        try {
            stage.curation = Curation(favorites = listOf("alice"))
            stage.showSurface(StageSurface.Curation)
            val checking = withTimeoutOrNull(3_000) {
                while (started.get() < 1 || !stage.favoritesChecking) delay(10)
                true
            }
            assertTrue("打开收藏面板没有进入查询中", checking == true)
            assertTrue(stage.state.value.favoritesChecking)
            wiring.moveTo(PhoneScenePhase.BACKGROUND)
            val cleared = withTimeoutOrNull(1_500) {
                while (stage.favoritesChecking || stage.state.value.favoritesChecking) delay(10)
                true
            }
            assertTrue("进后台取消查询后仍停在查询中", cleared == true)
            wiring.moveTo(PhoneScenePhase.ACTIVE)
            assertFalse("回到前台又把查询中置上了", stage.favoritesChecking)
            val beforeStale = started.get()
            val stale = stage.track { stage.refreshFavoritesIfStale() }
            val staleRan = withTimeoutOrNull(1_500) {
                while (started.get() <= beforeStale) delay(10)
                true
            }
            assertTrue("查询中残留让过期刷新直接返回", staleRan == true)
            stale.join()
            assertFalse(stage.favoritesChecking)
            assertNotNull(stage.favoriteStatus.status("alice"))
            val beforeManual = started.get()
            val manual = stage.track { stage.refreshFavorites() }
            val manualRan = withTimeoutOrNull(1_500) {
                while (started.get() <= beforeManual) delay(10)
                true
            }
            assertTrue("查询中残留让手动刷新直接返回", manualRan == true)
            manual.join()
            assertFalse(stage.state.value.favoritesChecking)
        } finally {
            release.complete(Unit)
            stage.close()
        }
    }


    @Test
    fun testSupersededFavoriteBatchDoesNotClearChecking() = harness.onStage {
        val started = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val http = object : HTTPClient {
            override suspend fun send(request: HTTPRequest): HTTPResult {
                if (!request.url.contains("chatvideocontext")) error("意外请求 ${request.url}")
                started.incrementAndGet()
                release.await()
                return HTTPResult(
                    status = 200,
                    data = """{"room_status":"offline"}""".toByteArray(),
                )
            }
        }
        val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        try {
            stage.curation = Curation(favorites = listOf("alice"))
            val first = stage.track { stage.resolveFavorites() }
            assertTrue(withTimeoutOrNull(3_000) {
                while (started.get() < 1 || !stage.favoritesChecking) delay(10)
                true
            } == true)
            val second = stage.track { stage.resolveFavorites() }
            assertTrue(withTimeoutOrNull(3_000) {
                while (started.get() < 2) delay(10)
                true
            } == true)
            first.cancel()
            delay(80)
            assertTrue("旧批次取消时把新批次的查询中清掉了", stage.favoritesChecking)
            release.complete(Unit)
            second.join()
            assertFalse(stage.favoritesChecking)
            assertFalse(stage.state.value.favoritesChecking)
        } finally {
            release.complete(Unit)
            stage.close()
        }
    }

    private fun blockingChat(started: AtomicInteger, cancelled: AtomicInteger): HTTPClient = object : HTTPClient {
        override suspend fun send(request: HTTPRequest): HTTPResult {
            if (!request.url.contains("chatvideocontext")) error("意外请求 ${request.url}")
            started.incrementAndGet()
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { cancelled.incrementAndGet() }
            }
            error("收藏查询在取消后仍继续")
        }
    }
}

private class IdleFavoriteSession : LifecycleSession {
    override fun closeGate() = Unit
    override fun onEpoch(epoch: vela.core.lifecycle.LifecycleEpoch) = Unit
    override fun suspendLanes() = Unit
    override fun releaseLane(role: vela.core.base.lane.PlaybackLaneRole) = Unit
    override fun retainRelayFor(role: vela.core.base.lane.PlaybackLaneRole) = Unit
    override fun deactivateAudio() = Unit
    override fun prepareAudio() = Unit
    override fun stopListener() = Unit
    override fun ensureLane() = Unit
    override fun restoreUi() = Unit
}
