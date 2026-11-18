package vela.core.base.lane

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LaneCursorTests {
    @Test
    fun testNonNegativeDirectionBecomesForward() {
        assertEquals(1, LaneCursor(direction = 0).direction)
        assertEquals(1, LaneCursor(direction = 4).direction)
        assertEquals(-1, LaneCursor(direction = -3).direction)
        assertEquals(1, LaneCursor().direction)
        assertNull(LaneCursor().username)
        assertEquals("a", LaneCursor(username = "a").username)
    }

    @Test
    fun testAutoDeltaMatchesStoredSign() {
        val backward = LaneCursor(username = "a", direction = -1)
        assertEquals(-1, backward.autoDelta)
        assertEquals(-1, backward.direction)
        // 构造之后直接改字段不再夹紧，和 Swift 的存储属性一致；步长仍只看符号。
        backward.direction = 2
        assertEquals(2, backward.direction)
        assertEquals(1, backward.autoDelta)
    }
}
