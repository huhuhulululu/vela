package vela.app.lifecycle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vela.app.hooks.StageHost

/** 单路仪器测试不起完整舞台；真实启动和其它仪器测试照常起。 */
class LaneHostIsolationTest {
    @Test
    fun laneInstrumentDoesNotBootStage() {
        assertFalse(StageHost.shouldBootStageFor("vela.app.lane.LaneEmulatorTest"))
        assertFalse(
            StageHost.shouldBootStageFor(
                "vela.app.lane.LaneEmulatorTest#testFirstFrameFitLetterboxAndAdoption",
            ),
        )
        assertFalse(
            StageHost.shouldBootStageFor(
                "vela.app.other.SomeTest, vela.app.lane.LaneEmulatorTest",
            ),
        )
        assertTrue(StageHost.isLaneInstrumentName("vela.app.lane.LaneEmulatorTest${'$'}Companion"))
    }

    @Test
    fun realLaunchAndOtherInstrumentsBootStage() {
        assertTrue(StageHost.shouldBootStageFor(null))
        assertTrue(StageHost.shouldBootStageFor(""))
        assertTrue(StageHost.shouldBootStageFor("vela.app.playchain.ChallengeEmulatorTest"))
        assertTrue(StageHost.shouldBootStageFor("vela.app.lifecycle.BackgroundEmulatorTest"))
        assertTrue(StageHost.shouldBootStageFor("vela.app.lane.LaneEmulatorTestHelper"))
    }
}
