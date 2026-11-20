package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage
import vela.core.lifecycle.LifecycleEpoch
import vela.core.lifecycle.PhoneScenePhase
import vela.core.selection.LaneSelection

/**
 * INACTIVE 停表之后，播放状态通知不能把停留计时重新打开。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InactiveDwellTest {
    @Test
    fun testPlaybackNotificationsDoNotRestartDwellWhileInactive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val settings = Settings(context)
        settings.ageConfirmed = true
        settings.muted = true
        val directory = Files.createTempDirectory("vela-dwell")
        val clock = ManualClock()
        val stage = StageViewModel(
            settings = settings,
            http = scriptHttp { catalogBody(listOf("ada")) },
            directory = directory,
            clock = clock,
            context = context,
        )
        val wiring = LifecycleWiring(stage, object : LifecycleSession {
            override fun closeGate() = Unit
            override fun onEpoch(epoch: LifecycleEpoch) = Unit
            override fun suspendLanes() = Unit
            override fun deactivateAudio() = Unit
            override fun prepareAudio() = Unit
            override fun stopListener() = Unit
            override fun ensureLane() = Unit
            override fun restoreUi() = Unit
        })
        try {
            stage.updateFeed(installing = FeedSource.popular, intent = LaneSelection.Intent.Reload) { store ->
                store.replace(
                    CatalogPage(
                        rooms = listOf(Room(username = "ada", viewers = 2, show = ShowState.publicShow)),
                        total = 1,
                        nextOffset = 1,
                    ),
                )
                store.setQuery(stage.currentQuery())
            }
            wiring.moveTo(PhoneScenePhase.ACTIVE)
            stage.notePlaying("ada")
            assertTrue(stage.dwell.isActive)
            clock.instant = clock.instant.plusSeconds(5)
            wiring.moveTo(PhoneScenePhase.INACTIVE)
            assertFalse(stage.dwell.isActive)
            clock.instant = clock.instant.plusSeconds(10)
            stage.notePlaying("ada")
            assertFalse("INACTIVE 期间播放通知重新打开了停留计时", stage.dwell.isActive)
            clock.instant = clock.instant.plusSeconds(10)
            stage.notePlaying("ada")
            val settled = stage.dwell.finish(clock.instant)
            assertEquals(5.0, settled?.dwell ?: Double.NaN, 0.001)
        } finally {
            stage.close()
            directory.toFile().deleteRecursively()
        }
    }
}
