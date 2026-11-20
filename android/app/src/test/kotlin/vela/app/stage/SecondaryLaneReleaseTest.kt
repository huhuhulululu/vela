package vela.app.stage

import android.content.Context
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.app.lane.PlaybackLane
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage
import vela.core.lifecycle.PhoneScenePhase
import vela.core.selection.LaneSelection

/**
 * 减少路数时会话层必须真正 release 第二路；横屏/后台的临时释放路径不得被误伤。
 * 对齐仪器测试 MilestoneBTest 第 118 行：SetLaneCount(1) 后 session.secondaryLane 为 null。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecondaryLaneReleaseTest {
    @Test
    fun testSetLaneCountOneReleasesSessionSecondary() {
        val fixture = open()
        try {
            install(fixture.stage, "alice", "bob", "carol")
            fixture.session.ensureLane()
            fixture.stage.setLaneCount(2)
            fixture.session.ensureSecondary()
            val held = sessionSecondary(fixture.session)
            assertNotNull("双窗时应持有副路", held)
            assertSame(held, fixture.stage.secondaryLane)

            fixture.stage.setLaneCount(1)
            assertTrue(fixture.stage.laneCount == 1)
            assertNull("舞台侧副路应已清空", fixture.stage.secondaryLane)
            assertNull(
                "SetLaneCount(1) 后会话仍持有第二路 PlaybackLane",
                sessionSecondary(fixture.session),
            )

            fixture.stage.setLaneCount(2)
            fixture.session.ensureSecondary()
            val recreated = sessionSecondary(fixture.session)
            assertNotNull(recreated)
            assertNotSame("再开双窗应重新创建副路", held, recreated)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testDualOffReleasesSessionSecondary() {
        val fixture = open()
        try {
            install(fixture.stage, "alice", "bob", "carol")
            fixture.session.ensureLane()
            fixture.stage.setDual(true)
            fixture.session.ensureSecondary()
            assertNotNull(sessionSecondary(fixture.session))

            fixture.stage.setDual(false)
            assertNull(sessionSecondary(fixture.session))
            assertNull(fixture.stage.secondaryLane)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testLandscapeKeepsSessionSecondaryForRestore() {
        val fixture = open()
        try {
            install(fixture.stage, "alice", "bob", "carol")
            fixture.session.ensureLane()
            fixture.stage.setLaneCount(2)
            fixture.session.ensureSecondary()
            val held = sessionSecondary(fixture.session)
            assertNotNull(held)

            fixture.stage.setLandscape(true)
            assertTrue(fixture.stage.state.value.lane.visible)
            assertTrue(fixture.stage.state.value.secondary?.visible != true)
            assertSame(
                "横屏只是临时停副路，会话层引用应保留以便回竖屏恢复",
                held,
                sessionSecondary(fixture.session),
            )
            assertEqualsRoomStillOnSecondary(fixture.stage, "bob")

            fixture.stage.setLandscape(false)
            assertSame(held, sessionSecondary(fixture.session))
            assertTrue(fixture.stage.state.value.secondary?.visible == true)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testBackgroundSuspendStillReleasesBothLanes() {
        val fixture = open()
        try {
            install(fixture.stage, "alice", "bob", "carol")
            fixture.wiring.moveTo(PhoneScenePhase.ACTIVE)
            fixture.session.ensureLane()
            fixture.stage.setLaneCount(2)
            fixture.session.ensureSecondary()
            assertNotNull(sessionSecondary(fixture.session))

            fixture.wiring.moveTo(PhoneScenePhase.BACKGROUND)
            assertNull(sessionSecondary(fixture.session))
            assertNull(fixture.session.lane.value)
            assertTrue(fixture.stage.playerReleased)
        } finally {
            fixture.close()
        }
    }

    private fun assertEqualsRoomStillOnSecondary(stage: StageViewModel, username: String) {
        assertTrue(stage.secondaryCursor?.username == username)
    }

    private fun sessionSecondary(session: AppPlaybackSession): PlaybackLane? {
        val field = AppPlaybackSession::class.java.getDeclaredField("secondaryLane")
        field.isAccessible = true
        return field.get(session) as? PlaybackLane
    }

    private fun install(stage: StageViewModel, vararg names: String) {
        stage.updateFeed(installing = FeedSource.popular, intent = LaneSelection.Intent.Reload) { store ->
            store.replace(
                CatalogPage(
                    rooms = names.map { Room(username = it, viewers = 1, show = ShowState.publicShow) },
                    total = names.size,
                    nextOffset = names.size,
                ),
            )
            store.setQuery(stage.currentQuery())
        }
    }

    private fun open(): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val settings = Settings(context)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = true
        settings.source = FeedSource.popular
        val directory = Files.createTempDirectory("vela-secondary-release")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val audio = activity.getSystemService(AudioManager::class.java)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val stage = StageViewModel(
            settings = settings,
            http = scriptHttp { catalogBody(listOf("alice", "bob", "carol")) },
            directory = directory,
            context = activity,
        )
        val session = AppPlaybackSession(
            activity = activity,
            stage = stage,
            disposeComposition = {},
            restoreComposition = {},
        )
        val wiring = LifecycleWiring(stage, session)
        return Fixture(activity, stage, session, wiring, directory)
    }

    private class Fixture(
        val activity: ComponentActivity,
        val stage: StageViewModel,
        val session: AppPlaybackSession,
        val wiring: LifecycleWiring,
        val directory: java.nio.file.Path,
    ) {
        fun close() {
            session.releaseLeftovers()
            stage.close()
            directory.toFile().deleteRecursively()
        }
    }
}
