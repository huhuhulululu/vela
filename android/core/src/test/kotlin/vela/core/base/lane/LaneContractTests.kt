package vela.core.base.lane

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 路身份与格子高度。语义来自 `LaneCursor.swift` 的 `LaneID` / `LaneLayout`，
 * 对应选房组里两条只谈契约、不谈选房算法的用例。
 */
class LaneContractTests {
    // parity: Tests/VelaCoreTests/LaneSelectionTests.swift#testLaneIDsActiveCountAndRoleMapping
    @Test
    fun testLaneIDsActiveCountAndRoleMapping() {
        assertEquals(listOf(LaneID.PRIMARY, LaneID.SECONDARY, LaneID.TERTIARY), LaneID.entries)
        assertEquals(listOf(LaneID.PRIMARY), LaneID.active(0), "夹紧到至少 1 路")
        assertEquals(listOf(LaneID.PRIMARY, LaneID.SECONDARY), LaneID.active(2))
        assertEquals(
            listOf(LaneID.PRIMARY, LaneID.SECONDARY, LaneID.TERTIARY),
            LaneID.active(3),
        )
        assertEquals(
            listOf(LaneID.PRIMARY, LaneID.SECONDARY, LaneID.TERTIARY),
            LaneID.active(9),
            "最多 3 路",
        )
        assertEquals(3, LaneID.maxCount)
        for (id in LaneID.entries) {
            assertEquals(id, id.role.laneID)
        }
        assertEquals(PlaybackLaneRole.TERTIARY, LaneID.TERTIARY.role)
        assertEquals(
            listOf("primary", "secondary", "tertiary"),
            PlaybackLaneRole.entries.map { it.label },
        )
        assertEquals("tertiary", LaneID.TERTIARY.label)
    }

    // parity: Tests/VelaCoreTests/LaneSelectionTests.swift#testPaneHeightAndThreeLaneFit
    @Test
    fun testPaneHeightAndThreeLaneFit() {
        assertEquals(2.0, LaneLayout.gap, 0.0)
        assertEquals(290.0, LaneLayout.paneHeight(874.0, 3), 0.001, "iPhone 17 Pro：(874 − 4) / 3")
        assertEquals(436.0, LaneLayout.paneHeight(874.0, 2), 0.001)
        assertEquals(874.0, LaneLayout.paneHeight(874.0, 1), 0.001)
        assertEquals(1.0, LaneLayout.paneHeight(0.0, 3), 0.001, "不出现 0 或负高度")
        assertTrue(LaneLayout.fitsThreeLanes(874.0))
        assertTrue(LaneLayout.fitsThreeLanes(3 * 160.0 + 4))
        assertFalse(LaneLayout.fitsThreeLanes(3 * 160.0 + 3), "每格低于 160 点")
        assertFalse(LaneLayout.fitsThreeLanes(400.0))
    }

    @Test
    fun testCursorKeepsPreviousRoom() {
        val cursor = LaneCursor(username = "a", direction = -3, previous = "b")
        assertEquals(-1, cursor.direction)
        assertEquals("b", cursor.previous)
        assertNull(LaneCursor().previous)
    }
}
