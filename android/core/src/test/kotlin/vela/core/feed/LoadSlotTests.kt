package vela.core.feed

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoadSlotTests {
    @Test
    fun testStaleGenerationDoesNotBlockNewGeneration() {
        val slot = LoadSlot()
        assertTrue(slot.acquire(generation = 1))
        assertTrue(slot.acquire(generation = 2))
        slot.release(generation = 1)
        assertFalse(slot.acquire(generation = 2))
        slot.release(generation = 2)
        assertTrue(slot.acquire(generation = 2))
    }

    @Test
    fun testSameGenerationIsBusyUntilReleased() {
        val slot = LoadSlot()
        assertTrue(slot.acquire(generation = 5))
        assertFalse(slot.acquire(generation = 5))
        slot.release(generation = 5)
        assertTrue(slot.acquire(generation = 5))
    }
}
