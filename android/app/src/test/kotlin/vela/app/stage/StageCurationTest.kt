package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.core.base.model.FeedSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageCurationTest {
    private val harness = StageHarness()
    private lateinit var settings: Settings
    private lateinit var directory: java.nio.file.Path
    private val clock = ManualClock()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context)
        directory = Files.createTempDirectory("vela-stage-curation")
    }

    @After
    fun tearDown() {
        deleteTree(directory)
        harness.close()
    }

    @Test
    fun testFavoriteAndBlockFollowTheVisibleList() = harness.onStage {
        settings.ageConfirmed = true
        settings.source = FeedSource.popular
        val http = FakeCatalogHttp(mapOf(0 to catalogJson(listOf("alice", "bob"))))
        val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        try {
            stage.awaitLoads()
            assertEquals("alice", stage.state.value.currentUsername)
            val target = stage.target()
            stage.dispatch(StageIntent.ToggleFavorite(target))
            assertTrue(stage.state.value.curation.isFavorite("alice"))
            stage.dispatch(StageIntent.ToggleFavorite(stage.target()))
            assertFalse(stage.state.value.curation.isFavorite("alice"))
            assertTrue(stage.state.value.toast.orEmpty().contains("alice"))
            stage.dispatch(StageIntent.BlockCurrent(stage.target()))
            assertEquals("bob", stage.state.value.currentUsername)
            assertTrue(stage.state.value.rooms.none { it.username == "alice" })
            assertTrue(stage.state.value.curation.isBlocked("alice"))
        } finally {
            stage.close()
        }
    }
}
