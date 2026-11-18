package vela.app.infra

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vela.app.hooks.Endpoints

class EndpointsTest {
    @After
    fun tearDown() {
        vela.app.infra.Endpoints.clearOverride()
    }

    @Test
    fun testProductionRootIsChaturbate() {
        assertEquals("https://chaturbate.com", vela.app.infra.Endpoints.siteRoot())
        assertEquals(
            "https://chaturbate.com/api/ts/roomlist/room-list/?limit=1",
            vela.app.infra.Endpoints.catalog("limit=1"),
        )
        assertEquals(
            "https://chaturbate.com/api/chatvideocontext/room_a/",
            vela.app.infra.Endpoints.chatContext("room_a"),
        )
    }

    @Test
    fun testHooksOverrideRewritesOnlyLoopbackUpstream() {
        assertTrue(Endpoints.installOverride("http://10.0.2.2:4242"))
        assertEquals("http://10.0.2.2:4242", vela.app.infra.Endpoints.siteRoot())
        assertEquals(
            "http://10.0.2.2:4242/api/chatvideocontext/room_a/",
            vela.app.infra.Endpoints.rewrite("https://chaturbate.com/api/chatvideocontext/room_a/"),
        )
        assertEquals(
            "https://thumb.live.mmcdn.com/riw/room_a.jpg",
            vela.app.infra.Endpoints.thumbnail("room_a"),
        )
    }

    @Test
    fun testHooksRejectRealSiteAndBlankUpstream() {
        assertFalse(Endpoints.installOverride(""))
        assertFalse(Endpoints.installOverride("https://chaturbate.com"))
        assertFalse(Endpoints.installOverride("http://example.com:9"))
        assertFalse(Endpoints.installOverride("ftp://127.0.0.1:9"))
        assertEquals("https://chaturbate.com", vela.app.infra.Endpoints.siteRoot())
    }

    @Test
    fun testLocalhostOverrideAccepted() {
        assertTrue(Endpoints.installOverride("http://127.0.0.1:9/"))
        assertEquals("http://127.0.0.1:9", vela.app.infra.Endpoints.siteRoot())
        assertEquals(
            "http://127.0.0.1:9/room_a/",
            vela.app.infra.Endpoints.referer("room_a"),
        )
    }
}
