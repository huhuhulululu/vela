package vela.app.pip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.app.stage.LanePlayback
import vela.app.stage.LifecycleSession
import vela.app.stage.LifecycleWiring
import vela.app.stage.StageViewModel
import vela.app.stage.setLaneCount
import vela.core.base.lane.LaneID
import vela.core.base.lane.PlaybackLaneRole
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage
import vela.core.lifecycle.LifecycleEpoch
import vela.core.lifecycle.PhoneLifecycleAction
import vela.core.lifecycle.PhoneLaneActivity
import vela.core.lifecycle.PhoneScenePhase
import vela.core.lifecycle.PiPPolicy
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.selection.LaneSelection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PipLifecycleTest {
    @Test
    fun testBackgroundWithPipReleasesSecondaryNotPrimary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = Settings(context)
        settings.ageConfirmed = true
        settings.muted = true
        val stage = StageViewModel(
            settings = settings,
            http = object : HTTPClient {
                override suspend fun send(request: HTTPRequest): HTTPResult =
                    HTTPResult(200, "{}".toByteArray())
            },
            directory = Files.createTempDirectory("vela-pip"),
            context = context,
        )
        stage.updateFeed(installing = null, intent = LaneSelection.Intent.Reload) { store ->
            store.replace(
                CatalogPage(
                    rooms = listOf(
                        Room(username = "a", viewers = 2, show = ShowState.publicShow),
                        Room(username = "b", viewers = 3, show = ShowState.publicShow),
                    ),
                    total = 2,
                    nextOffset = 2,
                ),
            )
            store.select("a")
        }
        stage.setLaneCount(2, remember = false)
        stage.laneFace = stage.laneFace.copy(
            username = "a",
            playback = LanePlayback.Playing,
            visible = true,
            manuallyPaused = false,
        )
        stage.secondaryFace = stage.secondaryFace?.copy(
            username = "b",
            playback = LanePlayback.Playing,
            visible = true,
            manuallyPaused = false,
        )
        stage.primaryActivity = PhoneLaneActivity.PLAYING
        stage.secondaryActivity = PhoneLaneActivity.PLAYING
        stage.focusLane = LaneID.PRIMARY
        stage.publish()
        val session = object : LifecycleSession {
            val released = ArrayList<PlaybackLaneRole>()
            override fun closeGate() = Unit
            override fun onEpoch(epoch: LifecycleEpoch) = Unit
            override fun suspendLanes() = Unit
            override fun releaseLane(role: PlaybackLaneRole) {
                released.add(role)
            }
            override fun retainRelayFor(role: PlaybackLaneRole) = Unit
            override fun deactivateAudio() = Unit
            override fun prepareAudio() = Unit
            override fun stopListener() = Unit
            override fun ensureLane() = Unit
            override fun restoreUi() = Unit
        }
        val wiring = LifecycleWiring(stage, session)
        wiring.pipInput = {
            PiPPolicy.Input(
                platform = PiPPolicy.Platform.PHONE,
                scene = PhoneScenePhase.BACKGROUND,
                pictureInPicture = PiPPolicy.State.NONE,
                autoEnterEnabled = true,
                muted = true,
                focus = PlaybackLaneRole.PRIMARY,
                lanes = listOf(PlaybackLaneRole.PRIMARY, PlaybackLaneRole.SECONDARY),
                focusPlaying = true,
                bossKey = false,
                lockScreenOrSystemSleep = false,
                windowMinimized = false,
                windowOccluded = false,
            )
        }
        wiring.moveTo(PhoneScenePhase.BACKGROUND)
        assertTrue(
            wiring.trace().any { it.startsWith("release-lane") },
        )
        assertEquals(listOf(PlaybackLaneRole.SECONDARY), session.released)
        assertEquals(PlaybackLaneRole.PRIMARY, stage.lifecycleEpoch.allowedLane)
    }
}