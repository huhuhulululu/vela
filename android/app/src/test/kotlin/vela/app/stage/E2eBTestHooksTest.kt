package vela.app.stage

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.time.Instant
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
import vela.app.lane.FakeLanePlayer
import vela.app.lane.PlaybackLane
import vela.app.playback.LaneHub
import vela.core.base.lane.LaneID
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage
import vela.core.hls.LaneQuality
import vela.core.hls.PhoneThermalState
import vela.core.selection.LaneSelection

/**
 * 正式测试接口：E2eB 仪器测试不得再靠反射读私有字段或调 StageViewModelKt。
 * 本文件先断言这些钩子存在且行为正确。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class E2eBTestHooksTest {
    @Test
    fun testSessionExposesSecondaryLaneForTest() {
        val env = openSession()
        try {
            assertNull(env.session.secondaryLaneForTest)
            env.stage.setDual(true)
            env.session.ensureSecondary()
            assertNotNull(env.session.secondaryLaneForTest)
            assertEquals(LaneID.SECONDARY, env.session.secondaryLaneForTest!!.id)

            env.stage.setLaneCount(1)
            assertNull(env.session.secondaryLaneForTest)
        } finally {
            env.close()
        }
    }

    @Test
    fun testInjectThermalForTestLimitsAndRecovers() {
        val env = openSession()
        try {
            val stage = env.stage
            assertFalse(stage.isQualityLimitedForTest)

            val t0 = Instant.parse("2026-01-01T00:00:00Z")
            stage.injectThermalForTest(PhoneThermalState.SERIOUS, t0, scheduleRecovery = false)
            assertEquals(PhoneThermalState.SERIOUS, stage.state.value.thermal)
            assertTrue(stage.isQualityLimitedForTest)
            val cap = stage.laneCapForTest(LaneID.PRIMARY)
            assertTrue(cap.isFinite())
            assertTrue(cap >= LaneQuality.minimumBitrate)

            stage.injectThermalForTest(PhoneThermalState.FAIR, t0, scheduleRecovery = false)
            assertTrue(stage.isQualityLimitedForTest)

            stage.injectThermalForTest(
                PhoneThermalState.FAIR,
                t0.plusSeconds(LaneQuality.recoveryDelay.toLong()),
                scheduleRecovery = false,
            )
            assertFalse(stage.isQualityLimitedForTest)
        } finally {
            env.close()
        }
    }

    @Test
    fun testDegradeLaneForTestMarksSecondaryUnavailable() {
        val env = openSession()
        try {
            val stage = env.stage
            stage.updateFeed(installing = FeedSource.popular, intent = LaneSelection.Intent.Reload) { store ->
                store.replace(
                    CatalogPage(
                        rooms = listOf(
                            Room(username = "ada", viewers = 1, show = ShowState.publicShow),
                            Room(username = "bob", viewers = 1, show = ShowState.publicShow),
                        ),
                        total = 2,
                        nextOffset = 2,
                    ),
                )
                store.setQuery(stage.currentQuery())
            }
            stage.setDual(true)
            assertNotNull(stage.state.value.secondary?.username)

            stage.degradeLaneForTest(LaneID.SECONDARY)

            assertEquals(LanePlayback.Degraded, stage.state.value.secondary?.playback)
            assertEquals(StageRules.DUAL_UNAVAILABLE, stage.state.value.secondary?.playbackNote)
        } finally {
            env.close()
        }
    }

    @Test
    fun testPlaybackLaneExposesAudioAndVolumeForTest() {
        val hub = LaneHub()
        val player = FakeLanePlayer()
        val lane = PlaybackLane(id = LaneID.PRIMARY, hub = hub, player = player)
        // 构造时默认静音音量为 0；之后用正式入口改音量，再读测试钩子。
        lane.setVolume(0.4, muted = false)
        player.audioEnabled = 2

        assertEquals(2, lane.audioEnabledCountForTest())
        assertEquals(0.4f, lane.playerVolumeForTest(), 0.0001f)
    }

    @Test
    fun testHooksArePublicJvmMethods() {
        // VisibleForTesting 是 CLASS retention，运行时看不到；这里只钉住 JVM 签名，避免再靠反射猜名字。
        assertNotNull(AppPlaybackSession::class.java.getMethod("getSecondaryLaneForTest"))
        assertNotNull(
            StageViewModel::class.java.getMethod(
                "injectThermalForTest",
                PhoneThermalState::class.java,
                Instant::class.java,
                java.lang.Boolean.TYPE,
            ),
        )
        assertNotNull(StageViewModel::class.java.getMethod("isQualityLimitedForTest"))
        assertNotNull(StageViewModel::class.java.getMethod("laneCapForTest", LaneID::class.java))
        assertNotNull(StageViewModel::class.java.getMethod("degradeLaneForTest", LaneID::class.java))
        assertNotNull(PlaybackLane::class.java.getMethod("audioEnabledCountForTest"))
        assertNotNull(PlaybackLane::class.java.getMethod("playerVolumeForTest"))
    }

    private fun openSession(): SessionEnv {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val settings = Settings(context)
        settings.ageConfirmed = true
        settings.muted = true
        settings.source = FeedSource.popular
        val directory = Files.createTempDirectory("vela-e2e-b-hooks")
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
        return SessionEnv(stage, session, directory)
    }

    private class SessionEnv(
        val stage: StageViewModel,
        val session: AppPlaybackSession,
        private val directory: java.nio.file.Path,
    ) {
        fun close() {
            session.releaseLeftovers()
            stage.close()
            directory.toFile().deleteRecursively()
        }
    }
}
