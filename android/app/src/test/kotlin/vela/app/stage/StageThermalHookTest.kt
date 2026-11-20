package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.core.base.lane.LaneID
import vela.core.hls.LaneQuality
import vela.core.hls.PhoneThermalState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageThermalHookTest {
    @Test
    fun injectThermalForTest_drivesLaneQualityWithoutReflection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val directory = Files.createTempDirectory("vela-thermal-hook")
        val stage = StageViewModel(
            settings = Settings(context),
            http = scriptHttp { ok("{}") },
            directory = directory,
            context = context,
        )
        try {
            assertFalse(stage.isQualityLimitedForTest())
            val t0 = Instant.EPOCH
            stage.injectThermalForTest(PhoneThermalState.SERIOUS, t0, scheduleRecovery = false)
            assertTrue(stage.isQualityLimitedForTest())
            val cap = stage.laneCapForTest(LaneID.PRIMARY)
            assertTrue(cap.isFinite())
            assertTrue(cap >= LaneQuality.minimumBitrate)
            stage.injectThermalForTest(PhoneThermalState.FAIR, t0, scheduleRecovery = false)
            assertTrue(stage.isQualityLimitedForTest())
            stage.injectThermalForTest(PhoneThermalState.FAIR, t0.plusSeconds(61), scheduleRecovery = false)
            assertFalse(stage.isQualityLimitedForTest())
        } finally {
            stage.close()
            directory.toFile().deleteRecursively()
        }
    }
}