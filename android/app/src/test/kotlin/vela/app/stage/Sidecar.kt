package vela.app.stage

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.hls.SidecarIdlePolicy
import vela.core.stream.StreamTicket

/**
 * 侧车解析、空闲拆除、以及冷却期内把收藏查询改走网页。不创建 WebView，也不访问网络。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SidecarResolveTest {
    @Test
    fun testResolveReadsTicketAndChallenge() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val good = FakeSidecarPage(ticketPayload())
        val sidecar = SiteSidecar(good, "http://127.0.0.1:9/auth/login/?next=/", scope)
        val ticket: StreamTicket = sidecar.resolve("ann")
        assertEquals(true, ticket.isPlayable)
        assertEquals("https://cdn.example/master.m3u8", ticket.url.toString())
        assertTrue(good.loaded)

        val challenged = SiteSidecar(FakeSidecarPage(challengePayload()), "http://127.0.0.1:9/", scope)
        var thrown: Throwable? = null
        try {
            challenged.resolve("ann")
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue(thrown is SidecarError.Challenge)
        scope.coroutineContext[Job]?.cancel()
        Unit
    }

    @Test
    fun testIdleTeardownDestroysThePage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val page = FakeSidecarPage(ticketPayload())
        val idleDelay = AtomicLong(-1)
        val idle = CompletableDeferred<() -> Unit>()
        val sidecar = SiteSidecar(
            page = page,
            entryUrl = "http://127.0.0.1:9/",
            scope = scope,
            scheduleIdle = { delayMs, block ->
                idleDelay.set(delayMs)
                idle.complete(block)
                IdleHandle { }
            },
        )
        sidecar.resolve("ann")
        assertEquals((SidecarIdlePolicy.seconds * 1000.0).toLong(), idleDelay.get())
        assertFalse(page.destroyed)
        idle.await().invoke()
        assertTrue(page.destroyed)
        scope.coroutineContext[Job]?.cancel()
        Unit
    }

    @Test
    fun testScriptQuotesUsername() {
        val script = sidecarScript("return username;", mapOf("username" to "a\"b"))
        assertTrue(script.contains("const username = \"a\\\"b\";"))
        val (status, body) = sidecarPayload(ticketPayload())
        assertEquals(200, status)
        assertTrue(body.toString(Charsets.UTF_8).contains("cdn.example"))
    }

    @Test
    fun testCooldownRoutesChatButNotCatalog() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val page = FakeSidecarPage("""{"status":200,"body":"{\"ok\":true}"}""")
        val sidecar = SiteSidecar(page, "http://127.0.0.1:9/", scope)
        var direct = 0
        var active = true
        val client = SidecarAwareClient(
            direct = object : HTTPClient {
                override suspend fun send(request: HTTPRequest): HTTPResult {
                    direct += 1
                    return ok("direct")
                }
            },
            sidecar = { sidecar },
            active = { active },
        )
        val chat = client.send(HTTPRequest(url = "https://chaturbate.com/api/chatvideocontext/ann/", method = "GET"))
        assertEquals(0, direct)
        assertTrue(chat.data.toString(Charsets.UTF_8).contains("ok"))
        client.send(HTTPRequest(url = "https://chaturbate.com/api/ts/roomlist/room-list/?offset=0", method = "GET"))
        assertEquals(1, direct)
        active = false
        client.send(HTTPRequest(url = "https://chaturbate.com/api/biocontext/ann/", method = "GET"))
        assertEquals(2, direct)
        scope.coroutineContext[Job]?.cancel()
        Unit
    }
}
