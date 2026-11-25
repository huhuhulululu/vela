package vela.core.base.lane

import kotlin.test.Test
import kotlin.test.assertEquals

class LaneRoleTests {
    @Test
    fun testRolesAndEndReasons() {
        assertEquals(
            listOf(PlaybackLaneRole.PRIMARY, PlaybackLaneRole.SECONDARY, PlaybackLaneRole.TERTIARY),
            PlaybackLaneRole.entries,
        )
        assertEquals(
            listOf(
                LaneEndReason.SWIPE,
                LaneEndReason.AUTO_SKIP,
                LaneEndReason.BLOCK,
                LaneEndReason.BACKGROUND,
                LaneEndReason.DUAL_OFF,
                LaneEndReason.SOURCE_CHANGE,
                LaneEndReason.FAILED,
                LaneEndReason.NOT_INTERESTED,
            ),
            LaneEndReason.entries,
        )
    }
}
