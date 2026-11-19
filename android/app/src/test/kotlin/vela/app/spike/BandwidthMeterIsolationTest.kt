package vela.app.spike

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.NetworkTypeObserver
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * NetworkTypeObserver 的单例只要被 getInstance 就会注册连接广播。
 * 默认播放器有两条路径会碰到它：DefaultBandwidthMeter，以及 API 31+ 默认打开的平台诊断。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class BandwidthMeterIsolationTest {
    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @Test(timeout = 90_000)
    fun testCustomBandwidthMeterAvoidsNetworkTypeObserver() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val field = NetworkTypeObserver::class.java.getDeclaredField("staticInstance")
        field.isAccessible = true

        val meterOnly = observerAfterPlayback(context, diagnostics = true)
        val meterAndDiagnosticsOff = observerAfterPlayback(context, diagnostics = false)

        NetworkTypeObserver.resetForTests()
        DefaultBandwidthMeter.Builder(context).build()
        val defaultMeter = field.get(null)
        NetworkTypeObserver.resetForTests()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        println(
            "SPIKE bandwidth meterOnly=${meterOnly != null} " +
                "diagnosticsOff=${meterAndDiagnosticsOff != null} defaultMeter=${defaultMeter != null}",
        )
        SpikeReport.emit(
            "bandwidth-meter",
            mapOf(
                "customMeterStillCreatesObserver" to (meterOnly != null),
                "customMeterWithDiagnosticsOffCreatesObserver" to (meterAndDiagnosticsOff != null),
                "defaultMeterCreatesObserver" to (defaultMeter != null),
            ),
        )
        assertNotNull(meterOnly)
        assertNull(meterAndDiagnosticsOff)
        assertNotNull(defaultMeter)
    }

    private fun observerAfterPlayback(context: Context, diagnostics: Boolean): Any? {
        val field = NetworkTypeObserver::class.java.getDeclaredField("staticInstance")
        field.isAccessible = true
        NetworkTypeObserver.resetForTests()
        val upstream = FakeUpstream()
        val session = SpikeSession(
            context = context,
            upstream = upstream,
            stripLowLatency = true,
            bandwidthMeter = FixedBandwidthMeter(),
            usePlatformDiagnostics = diagnostics,
        )
        return try {
            assertTrue(session.awaitFirstFrame(20_000))
            session.play(500)
            field.get(null)
        } finally {
            session.close()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
