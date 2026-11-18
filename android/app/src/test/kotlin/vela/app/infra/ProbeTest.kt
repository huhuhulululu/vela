package vela.app.infra

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.hooks.Hooks
import vela.app.hooks.PerfProbe

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProbeTest {
    @After
    fun tearDown() {
        Probe.install(ProbeNop)
        PerfProbe.reset()
        Endpoints.clearOverride()
    }

    @Test
    fun testDefaultProbeIgnoresFirstFrame() {
        Probe.install(ProbeNop)
        Probe.current.onRenderedFirstFrame("lane-a", 1_700_000_000_000L)
        assertSame(ProbeNop, Probe.current)
    }

    @Test
    fun testHooksInstallRecorderAndKeepFirstFrame() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Hooks.install(context)
        assertSame(PerfProbe, Probe.current)
        Probe.current.onRenderedFirstFrame("focus", 1_735_000_000_123L)
        val frames = PerfProbe.firstFrames()
        assertEquals(1, frames.size)
        assertEquals("focus", frames[0].lane)
        assertEquals(1_735_000_000_123L, frames[0].wallTimeMs)
    }

    @Test
    fun testEmptyUpstreamDoesNotReplaceSiteRoot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Hooks.install(context)
        assertEquals(Endpoints.PRODUCTION_ROOT, Endpoints.siteRoot())
        assertTrue(PerfProbe.firstFrames().isEmpty())
    }
}
