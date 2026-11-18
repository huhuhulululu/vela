package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.core.base.model.FeedSource

/**
 * 冷启动与续页。假 HTTP 客户端记下目录 URL，不断开到真实站点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageCatalogTest {
    private val harness = StageHarness()
    private lateinit var settings: Settings
    private lateinit var directory: java.nio.file.Path
    private val clock = ManualClock()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context)
        directory = Files.createTempDirectory("vela-stage-catalog")
    }

    @After
    fun tearDown() {
        deleteTree(directory)
        harness.close()
    }

    @Test
    fun testColdStartSendsOneCatalogRequest() = harness.onStage {
        settings.ageConfirmed = true
        settings.source = FeedSource.popular
        val http = FakeCatalogHttp(mapOf(0 to catalogJson(listOf("alpha"))))
        val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        try {
            stage.awaitLoads()
            assertEquals(listOf(true), listOf(stage.state.value.catalogClaimed))
            assertEquals(1, catalogHits(http))
            assertEquals("alpha", stage.state.value.currentUsername)
            stage.bootstrap()
            stage.awaitLoads()
            stage.dispatch(StageIntent.ConfirmAge)
            stage.awaitLoads()
            assertEquals(1, catalogHits(http))
            assertTrue(http.urls.all { it.contains("/api/ts/roomlist/room-list/") })
        } finally {
            stage.close()
        }
    }

    @Test
    fun testUnconfirmedAgeSendsNoCatalogRequest() = harness.onStage {
        settings.ageConfirmed = false
        val http = FakeCatalogHttp(mapOf(0 to catalogJson(listOf("alpha"))))
        val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        try {
            stage.bootstrap()
            stage.dispatch(StageIntent.SelectSource(FeedSource.popular))
            stage.dispatch(StageIntent.Retry)
            stage.dispatch(StageIntent.SubmitSearch)
            stage.awaitLoads()
            yield()
            assertEquals(0, http.urls.size)
            assertEquals(FeedSource.guess, stage.state.value.source)
            assertEquals(false, stage.state.value.ageConfirmed)
            stage.dispatch(StageIntent.ConfirmAge)
            stage.awaitLoads()
            assertEquals(1, catalogHits(http))
            assertEquals(true, stage.state.value.ageConfirmed)
            assertEquals("alpha", stage.state.value.currentUsername)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testRestPageMergesAfterCurrentRoom() = harness.onStage {
        settings.ageConfirmed = true
        settings.source = FeedSource.guess
        val first = namedRooms(90)
        val rest = listOf("room90", "room91")
        val http = FakeCatalogHttp(
            pages = mapOf(
                0 to catalogJson(first, total = 92),
                90 to catalogJson(rest, total = 92),
            ),
            holdRest = true,
        )
        val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        try {
            stage.awaitUntil { it.rooms.size == 90 }
            val before = stage.feed.catalog.map { it.username }
            assertEquals(90, before.size)
            stage.dispatch(StageIntent.Step(5))
            val prefix = before.take(6)
            val current = stage.state.value.currentUsername
            assertEquals(prefix.last(), current)
            http.releaseRest()
            stage.awaitLoads()
            val after = stage.feed.catalog.map { it.username }
            assertEquals(prefix, after.take(6))
            assertEquals(current, stage.state.value.currentUsername)
            assertTrue(after.indexOf("room90") >= 6)
            assertTrue(after.indexOf("room91") >= 6)
            assertEquals(2, catalogHits(http))
        } finally {
            stage.close()
        }
    }

    private fun catalogHits(http: FakeCatalogHttp): Int =
        http.urls.count { it.contains("/api/ts/roomlist/room-list/") }
}
