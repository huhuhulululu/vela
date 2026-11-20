package vela.app.stage

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.app.lane.PlaybackLane
import vela.core.base.lane.LaneID
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage
import vela.core.selection.LaneSelection

/**
 * 关掉双窗或减少路数时，会话层必须 release 副路播放器，而不只是舞台里 stop。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecondaryLaneReleaseTest {
  @Test
  fun testSetLaneCountOneReleasesSessionSecondaryLane() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    val settings = Settings(context)
    settings.ageConfirmed = true
    settings.muted = true
    settings.source = FeedSource.popular
    val directory = Files.createTempDirectory("vela-secondary-release")
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val stage = StageViewModel(
        settings = settings,
        http = scriptHttp { catalogBody(listOf("ada", "bob", "carol")) },
        directory = directory,
        context = activity,
    )
    val session = AppPlaybackSession(
        activity = activity,
        stage = stage,
        disposeComposition = {},
        restoreComposition = {},
    )
  try {
        stage.updateFeed(installing = FeedSource.popular, intent = LaneSelection.Intent.Reload) { store ->
            store.replace(
                CatalogPage(
                    rooms = listOf(
                        Room(username = "ada", viewers = 1, show = ShowState.publicShow),
                        Room(username = "bob", viewers = 1, show = ShowState.publicShow),
                        Room(username = "carol", viewers = 1, show = ShowState.publicShow),
                    ),
                    total = 3,
                    nextOffset = 3,
                ),
            )
            store.setQuery(stage.currentQuery())
        }
        stage.setDual(true)
        session.ensureSecondary()
        assertNotNull(readSessionSecondary(session))
        assertEquals(2, stage.laneCount)

        stage.setLaneCount(1)

        assertEquals(1, stage.laneCount)
        assertNull(stage.secondaryLane)
        assertNull(readSessionSecondary(session))
        assertNull(stage.secondaryCursor)
    } finally {
        session.releaseLeftovers()
        stage.close()
        directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun testLandscapeHidesSecondaryWithoutReleasingSessionLane() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    val settings = Settings(context)
    settings.ageConfirmed = true
    settings.muted = true
    val directory = Files.createTempDirectory("vela-secondary-landscape")
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val stage = StageViewModel(
        settings = settings,
        http = scriptHttp { catalogBody(listOf("ada", "bob")) },
        directory = directory,
        context = activity,
    )
    val session = AppPlaybackSession(
        activity = activity,
        stage = stage,
        disposeComposition = {},
        restoreComposition = {},
    )
    try {
      stage.setDual(true)
      session.ensureSecondary()
      assertNotNull(readSessionSecondary(session))

      stage.setLandscape(true)

      assertTrue(stage.landscape)
      assertEquals(2, stage.laneCount)
      assertNotNull(readSessionSecondary(session))
      assertFalse(stage.state.value.secondary?.visible ?: true)

      stage.setLandscape(false)

      assertNotNull(readSessionSecondary(session))
    } finally {
      session.releaseLeftovers()
      stage.close()
      directory.toFile().deleteRecursively()
    }
  }

  private fun readSessionSecondary(session: AppPlaybackSession): PlaybackLane? {
    val field = AppPlaybackSession::class.java.getDeclaredField("secondaryLane")
    field.isAccessible = true
    return field.get(session) as PlaybackLane?
  }
}