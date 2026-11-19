package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import vela.app.infra.Settings
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult

/** 失败日志只留异常类名，搜索词和地址不能进 logcat。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogRedactionTest {
    private val harness = StageHarness()
    private lateinit var settings: Settings
    private lateinit var directory: java.nio.file.Path

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context)
        directory = Files.createTempDirectory("vela-log-redaction")
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        deleteTree(directory)
        harness.close()
    }

    @Test
    fun testSiteSearchFailureDoesNotLogTheKeywordOrUrl() = harness.onStage {
        val http = object : HTTPClient {
            override suspend fun send(request: HTTPRequest): HTTPResult {
                throw IOException("https://chaturbate.com/search?q=secret-term")
            }
        }
        val stage = StageViewModel(settings, http, directory, ManualClock(), harness.dispatcher)
        try {
            stage.ageConfirmed = true
            ShadowLog.clear()
            stage.startSiteSearch("secret-term", null)
            stage.awaitLoads()
            val lines = ShadowLog.getLogs().map { item ->
                item.msg.orEmpty() + " " + (item.throwable?.message ?: "")
            }
            assertTrue(lines.any { it.contains("site search failed") })
            assertTrue(lines.none { it.contains("secret-term") || it.contains("http") })
        } finally {
            stage.close()
        }
    }
}
