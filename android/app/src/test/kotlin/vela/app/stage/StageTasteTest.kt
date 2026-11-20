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
import vela.core.base.model.TasteKeys

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageTasteTest {
    private val harness = StageHarness()
    private lateinit var settings: Settings
    private lateinit var directory: java.nio.file.Path
    private val clock = ManualClock()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context)
        directory = Files.createTempDirectory("vela-stage-taste")
    }

    @After
    fun tearDown() {
        deleteTree(directory)
        harness.close()
    }

    @Test
    fun testVetoHidesTaggedRoomAndPauseStopsLearning() = harness.onStage {
        settings.ageConfirmed = true
        settings.source = FeedSource.guess
        val http = FakeCatalogHttp(mapOf(0 to catalogJson(listOf("ann", "bob"))))
        val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        try {
            stage.awaitLoads()
            val key = TasteKeys.tagKey("synth")
            stage.dispatch(StageIntent.VetoTaste(key))
            assertTrue(stage.taste.isVetoed(key))
            assertTrue(stage.state.value.rooms.none { it.username == "ann" })
            assertEquals("bob", stage.state.value.currentUsername)
            stage.dispatch(StageIntent.SetLearningPaused(true))
            assertTrue(stage.state.value.taste.paused)
            assertTrue(stage.state.value.toast.orEmpty().contains("暂停学习"))
            stage.dispatch(StageIntent.SetLearningPaused(false))
            assertFalse(stage.state.value.taste.paused)
        } finally {
            stage.close()
        }
    }
}
