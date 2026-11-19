package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
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
import vela.core.base.net.HTTPResult
import vela.core.curation.Curation

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
