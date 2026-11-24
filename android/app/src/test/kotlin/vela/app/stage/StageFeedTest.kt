package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.core.base.lane.LaneID
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage
import vela.core.selection.LaneSelection

/**
 * 单窗选房必须与 FeedStore 自己的结果一致；停留结算走 LaneSettlement，秒数进账本。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageFeedTest {
    private val harness = StageHarness()
    private lateinit var settings: Settings
    private lateinit var directory: java.nio.file.Path
    private val clock = ManualClock()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context)
        directory = Files.createTempDirectory("vela-stage-feed")
    }

    @After
    fun tearDown() {
        deleteTree(directory)
        harness.close()
    }

    @Test
    fun testSingleWindowSelectionMatchesFeedStore() = harness.onStage {
        val http = FakeCatalogHttp(emptyMap())
        val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        try {
            val page = pageOf("a", "b", "c", "d")
            stage.updateFeed(installing = FeedSource.guess, intent = LaneSelection.Intent.Reload) { store ->
                store.replace(page)
                store.setQuery(stage.currentQuery())
            }
            val stepped = stage.feed.copy()
            stage.updateFeed(intent = LaneSelection.Intent.Move(LaneID.PRIMARY, 1)) { store ->
                store.step(1)
            }
            stepped.step(1)
            assertEquals(stepped.currentUsername, stage.feed.currentUsername)
            assertEquals(stepped.rooms.map { it.username }, stage.feed.rooms.map { it.username })

            val blocked = setOf("b")
            val filtered = stage.feed.copy()
            stage.updateFeed(intent = LaneSelection.Intent.Filter) { store ->
                store.setFilters(blocked, only = null, hidden = emptySet())
            }
            filtered.setFilters(blocked, only = null, hidden = emptySet())
            assertEquals(filtered.currentUsername, stage.feed.currentUsername)
            assertEquals(filtered.rooms.map { it.username }, stage.feed.rooms.map { it.username })
            assertEquals("c", stage.feed.currentUsername)
            assertEquals(0, http.urls.size)
            assertNull(stage.secondaryCursor)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testDwellSettlementRecordsWeightedSeconds() = harness.onStage {
        val http = FakeCatalogHttp(emptyMap())
        val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        try {
            stage.updateFeed(installing = FeedSource.popular, intent = LaneSelection.Intent.Reload) { store ->
                store.replace(pageOf("ada", "bea"))
                store.setQuery(stage.currentQuery())
            }
            stage.notePlaying("ada")
            clock.instant = clock.instant.plusSeconds(10)
            val moved = stage.move(1, userInitiated = true)
            assertEquals(true, moved)
            assertEquals("bea", stage.feed.currentUsername)
            val entry = stage.watchLedger.entries["ada"]
            assertEquals(10.0, entry?.totalSeconds ?: Double.NaN, 0.001)
            assertEquals(0, http.urls.size)
        } finally {
            stage.close()
        }
    }

    private fun pageOf(vararg names: String): CatalogPage {
        val rooms = names.map { name ->
            Room(username = name, viewers = 2, show = ShowState.publicShow)
        }
        return CatalogPage(rooms = rooms, total = rooms.size, nextOffset = rooms.size)
    }
}
