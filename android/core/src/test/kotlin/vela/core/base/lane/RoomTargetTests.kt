package vela.core.base.lane

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomTargetTests {
    @Test
    fun testMatchesLaneNameAndGeneration() {
        val target = RoomTarget(LaneID.SECONDARY, "Bob", 4)
        assertEquals(LaneID.SECONDARY, target.laneID)
        assertEquals("Bob", target.username)
        assertEquals(4, target.generation)
        assertTrue(target.matches(LaneID.SECONDARY, "bob", 4))
        assertFalse(target.matches(LaneID.SECONDARY, "bob", 5), "代次变了就放弃")
        assertFalse(target.matches(LaneID.PRIMARY, "bob", 4), "不是这一路就放弃")
        assertFalse(target.matches(LaneID.SECONDARY, "ann", 4), "房间换了就放弃")
        assertEquals(LaneID.PRIMARY, RoomTarget.primary("a", 1).laneID)
    }
}
