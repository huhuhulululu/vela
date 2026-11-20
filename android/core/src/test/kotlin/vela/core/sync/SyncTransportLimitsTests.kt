package vela.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SyncTransportLimitsTests {
    @Test
    fun testRetryAfterIsClampedToOneDay() {
        assertEquals(
            SyncServerResponse.RateLimited(86_400),
            SyncHttp.parse(429, ByteArray(0), Int.MAX_VALUE, SyncHttp.Kind.GET),
        )
        assertEquals(
            SyncServerResponse.RateLimited(1),
            SyncHttp.parse(429, ByteArray(0), -5, SyncHttp.Kind.GET),
        )
    }

    @Test
    fun testSchedulerSurvivesHugeRetryAfter() {
        val s = SyncScheduler()
        s.record(SyncCycleResult.RateLimited(Int.MAX_VALUE), ULong.MAX_VALUE - 10u)
        assertFalse(s.allows(SyncScheduler.Trigger.MANUAL, ULong.MAX_VALUE - 1u))
        val t = SyncScheduler()
        t.record(SyncCycleResult.QuotaFull, ULong.MAX_VALUE - 10u)
        repeat(70) { t.record(SyncCycleResult.Transient, ULong.MAX_VALUE - 10u) }
        assertFalse(t.allows(SyncScheduler.Trigger.MANUAL, ULong.MAX_VALUE - 1u))
    }

    @Test
    fun testOversizedResponseBodyIsTransient() {
        val big = ("{\"epoch\":\"e\",\"now\":1,\"pad\":\"" + "a".repeat(900_000) + "\"}").toByteArray(Charsets.UTF_8)
        assertEquals(SyncServerResponse.Transient, SyncHttp.interpret(404, emptyMap(), big, SyncHttp.Kind.GET))
        val small = "{\"epoch\":\"e\",\"now\":1}".toByteArray(Charsets.UTF_8)
        assertEquals(
            SyncServerResponse.Transient,
            SyncHttp.interpret(404, mapOf("Content-Length" to "900000"), small, SyncHttp.Kind.GET),
        )
        assertEquals(
            SyncServerResponse.NotFound("e", 1),
            SyncHttp.interpret(404, emptyMap(), small, SyncHttp.Kind.GET),
        )
    }
}
