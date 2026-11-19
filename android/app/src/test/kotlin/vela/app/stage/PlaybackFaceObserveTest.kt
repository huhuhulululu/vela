package vela.app.stage

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import java.net.URI
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.app.lane.Cancellable
import vela.app.lane.FakeLanePlayer
import vela.app.lane.PlaybackLane
import vela.app.playback.LaneHub
import vela.core.base.lane.LaneID
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.play.StreamRendition
import vela.core.base.site.CatalogPage
import vela.core.hls.FailureCode
import vela.core.selection.LaneSelection

/**
 * 前台不该每 250ms 轮询播放器。播放和失败要在监听回调里立刻反映到舞台。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackFaceObserveTest {
    @Test
    fun testPlayingAndFailureReachTheStageWithoutPolling() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val settings = Settings(context)
        settings.ageConfirmed = true
        settings.muted = true
        settings.source = FeedSource.popular
        val directory = Files.createTempDirectory("vela-face")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val stage = StageViewModel(
            settings = settings,
            http = scriptHttp { catalogBody(listOf("ada")) },
            directory = directory,
            context = activity,
        )
        stage.updateFeed(installing = FeedSource.popular, intent = LaneSelection.Intent.Reload) { store ->
            store.replace(
                CatalogPage(
                    rooms = listOf(Room(username = "ada", viewers = 1, show = ShowState.publicShow)),
                    total = 1,
                    nextOffset = 1,
                ),
            )
            store.setQuery(stage.currentQuery())
        }
        val player = FakeLanePlayer()
        val lane = PlaybackLane(
            id = LaneID.PRIMARY,
            hub = LaneHub(),
            player = player,
            sleeper = { _, _ -> Cancellable { } },
        )
        val session = AppPlaybackSession(
            activity = activity,
            stage = stage,
            disposeComposition = {},
            restoreComposition = {},
        )
        try {
            lane.play(
                StreamRendition.unselected(URI.create("https://cdn.example/master.m3u8")),
                "ada",
                800_000.0,
                true,
            )
            val observe = AppPlaybackSession::class.java.getDeclaredMethod("observe", PlaybackLane::class.java)
            observe.isAccessible = true
            observe.invoke(session, lane)
            assertNull(stage.state.value.lane.playingUsername)
            player.emitPlaying(true)
            assertEquals("ada", stage.state.value.lane.playingUsername)
            player.emitPlaying(false)
            val chain = listOf(FailureCode("media", 1))
            player.emitError(false, chain)
            player.emitError(false, chain)
            assertEquals(LanePlayback.Failed, stage.state.value.lane.playback)
        } finally {
            session.releaseLeftovers()
            lane.release()
            stage.close()
            directory.toFile().deleteRecursively()
        }
    }
}
