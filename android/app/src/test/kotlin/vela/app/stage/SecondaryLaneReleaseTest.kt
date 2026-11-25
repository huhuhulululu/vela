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
        assertNotNull(session.secondaryLaneForTest)
        assertEquals(2, stage.laneCount)

        stage.setLaneCount(1)

        assertEquals(1, stage.laneCount)
        assertNull(stage.secondaryLane)
        assertNull(session.secondaryLaneForTest)
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
      assertNotNull(session.secondaryLaneForTest)

      stage.setLandscape(true)

      assertTrue(stage.landscape)
      assertEquals(2, stage.laneCount)
      assertNotNull(session.secondaryLaneForTest)
      assertFalse(stage.state.value.secondary?.visible ?: true)

      stage.setLandscape(false)

      assertNotNull(session.secondaryLaneForTest)
    } finally {
      session.releaseLeftovers()
      stage.close()
      directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun testSuspendLanesAndReleaseSecondaryClearStageReferences() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    val settings = Settings(context)
    settings.ageConfirmed = true
    settings.muted = true
    val directory = Files.createTempDirectory("vela-secondary-clear")
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
      session.ensureLane()
      session.ensureSecondary()
      assertNotNull(stage.primaryLane)
      assertNotNull(stage.secondaryLane)
      assertNotNull(stage.secondarySink)

      // 画中画路径释放副路时，必须一并清空 stage.secondaryLane / stage.secondarySink，避免回前台挂到已 release 的播放器。
      session.releaseSecondaryLane()
      assertNull(session.secondaryLaneForTest)
      assertNull("releaseSecondaryLane 应清空 stage.secondaryLane", stage.secondaryLane)
      assertNull("releaseSecondaryLane 应清空 stage.secondarySink", stage.secondarySink)

      // 重新建立双路后走全停 suspendLanes，两路舞台引用均应清空。
      session.ensureSecondary()
      assertNotNull(stage.secondaryLane)
      session.suspendLanes()
      assertNull("suspendLanes 应清空 stage.primaryLane", stage.primaryLane)
      assertNull("suspendLanes 应清空 stage.secondaryLane", stage.secondaryLane)
      assertNull("suspendLanes 应清空 stage.secondarySink", stage.secondarySink)
    } finally {
      session.releaseLeftovers()
      stage.close()
      directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun testCreatingPrimaryLaneDoesNotClearSecondaryObserver() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    val settings = Settings(context)
    settings.ageConfirmed = true
    settings.muted = true
    val directory = Files.createTempDirectory("vela-secondary-observer")
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
      // 先建副路，再建主路（冷启动恢复或副路焦点画中画回前台时序）。
      session.ensureSecondary()
      val secondary = session.secondaryLaneForTest
      assertNotNull(secondary)
      assertNotNull("副路创建后应装上观察器", secondary!!.onPlaybackFace)

      session.ensureLane()
      assertNotNull("创建主路不得清掉仍存活的副路 onPlaybackFace", secondary.onPlaybackFace)
    } finally {
      session.releaseLeftovers()
      stage.close()
      directory.toFile().deleteRecursively()
    }
  }
}