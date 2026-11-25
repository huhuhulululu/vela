package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.core.base.model.FeedSource
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.hls.NetworkFailure

/**
 * 首屏目录：超时或连接类失败自动重试一次，重试期间仍是「正在整理在线房间」，不闪错误页；
 * 第二次仍失败才显示错误与重试按钮。质询、非 2xx、格式错误不重试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageOpeningRetryTest {
    private val harness = StageHarness()
    private lateinit var settings: Settings
    private lateinit var directory: java.nio.file.Path
    private val clock = ManualClock()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context)
        settings.ageConfirmed = true
        settings.source = FeedSource.guess
        directory = Files.createTempDirectory("vela-stage-retry")
    }

    @After
    fun tearDown() {
        deleteTree(directory)
        harness.close()
    }

    @Test
    fun testTimeoutOnOpeningPageRetriesOnceWithoutShowingError() = harness.onStage {
        val http = ScriptedCatalog(listOf(Fail(SocketTimeoutException("deadline")), Page))
        val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        http.stage = stage
        try {
            stage.awaitLoads()
            assertEquals(2, http.catalogHits)
            val during = http.seenOnSecondAttempt!!
            assertTrue("重试期间应仍在加载", during.loading)
            assertNull("重试期间闪了错误文案", during.catalogError)
            assertEquals("alpha", stage.state.value.currentUsername)
            assertNull(stage.state.value.catalogError)
            assertFalse(stage.state.value.loading)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testConnectionFailureTwiceShowsErrorAfterSecondAttempt() = harness.onStage {
        val http = ScriptedCatalog(listOf(Fail(SocketException("reset")), Fail(SocketException("reset"))))
        val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        http.stage = stage
        try {
            stage.awaitLoads()
            assertEquals(2, http.catalogHits)
            assertFalse(stage.state.value.loading)
            assertEquals(NetworkFailure.message, stage.state.value.catalogError)
            assertTrue(stage.state.value.rooms.isEmpty())
        } finally {
            stage.close()
        }
    }

    @Test
    fun testChallengeStatusAndMalformedAreNotRetried() {
        val cases = listOf(
            Body(200, "<html><title>Just a moment...</title></html>"),
            Body(503, "busy"),
            Body(200, "not json"),
        )
        for (case in cases) {
            harness.onStage {
                val http = ScriptedCatalog(listOf(case, Page))
                val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
                http.stage = stage
                try {
                    stage.awaitLoads()
                    assertEquals("不应重试：$case", 1, http.catalogHits)
                    assertTrue(stage.state.value.rooms.isEmpty())
                } finally {
                    stage.close()
                }
            }
        }
    }

    private sealed interface Step
    private data class Fail(val error: Exception) : Step
    private data class Body(val status: Int, val text: String) : Step
    private data object Page : Step

    /** 按顺序回目录请求；其它请求一律 404，不访问网络。 */
    private class ScriptedCatalog(private val steps: List<Step>) : HTTPClient {
        var stage: StageViewModel? = null
        var catalogHits = 0
        var seenOnSecondAttempt: StageUiState? = null

        override suspend fun send(request: HTTPRequest): HTTPResult {
            if (!request.url.contains("/api/ts/roomlist/room-list/")) {
                return HTTPResult(status = 404, data = ByteArray(0), headers = emptyMap())
            }
            catalogHits += 1
            if (catalogHits == 2) seenOnSecondAttempt = stage?.state?.value
            return when (val step = steps.getOrElse(catalogHits - 1) { Page }) {
                is Fail -> throw step.error
                is Body -> HTTPResult(status = step.status, data = step.text.toByteArray(), headers = emptyMap())
                Page -> HTTPResult(status = 200, data = catalogJson(listOf("alpha", "beta")), headers = emptyMap())
            }
        }
    }
}
