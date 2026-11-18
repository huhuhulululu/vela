package vela.core.base.model

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.net.EndpointBackoffBox

class VelaCoreTests {
    /** 默认共用的非 JSON 退避记录跨用例清空，免得前一个用例的退避挡住后一个。 */
    @BeforeTest
    fun setUp() {
        EndpointBackoffBox.shared.reset()
    }

    @Test
    fun testFormattingAndTokens() {
        assertEquals("419", ViewerCount.string(419))
        assertEquals("1.2万", ViewerCount.string(12_000))
        assertEquals("2万", ViewerCount.string(20_000))
        assertEquals(null, BroadcastClock.string(59))
        assertEquals("已开播 2 分钟", BroadcastClock.string(120))
        assertEquals("已开播 1 小时 1 分", BroadcastClock.string(3660))
        assertNull(Room.direct("nope name"))
        assertEquals("alpha_1", Room.direct("alpha_1")?.username)
        assertFalse(RoomQuery(text = "alpha", publicOnly = true).matches(room("beta")))
        assertTrue(RoomQuery(text = "hello", publicOnly = true).matches(room("alpha", subject = "say hello")))
        assertFalse(RoomQuery(publicOnly = true).matches(room("c", show = ShowState.privateShow)))
    }

    private fun room(name: String, show: ShowState = ShowState.publicShow, subject: String = ""): Room =
        Room(username = name, subject = subject, show = show)
}
