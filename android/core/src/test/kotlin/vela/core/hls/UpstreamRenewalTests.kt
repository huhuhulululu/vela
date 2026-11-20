package vela.core.hls

import java.time.Instant
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vela.core.base.play.RelayFailure

class UpstreamRenewalTests {
    private val t0: Instant = Instant.ofEpochSecond(1_800_000_000)

    @Test
    fun testOnlyExpiryStatusesTriggerRenewal() {
        assertTrue(RenewalPolicy.isExpiry(RelayFailure.Http(403)))
        assertTrue(RenewalPolicy.isExpiry(RelayFailure.Http(410)))
        assertFalse(RenewalPolicy.isExpiry(RelayFailure.Http(404)))
        assertFalse(RenewalPolicy.isExpiry(RelayFailure.Http(503)))
        assertFalse(RenewalPolicy.isExpiry(RelayFailure.BadBody))
        assertFalse(RenewalPolicy.isExpiry(RelayFailure.Transport(domain = "NSURLErrorDomain", code = -1001)))
        assertFalse(RenewalPolicy.isExpiry(null))
    }

    @Test
    fun testBudgetLimitsAttemptsInWindow() {
        val budget = RenewalBudget(limit = 3, window = 60.0, minGap = 3.0)
        assertTrue(budget.take(t0))
        assertTrue(budget.take(t0.plusSeconds(4.0)))
        assertTrue(budget.take(t0.plusSeconds(8.0)))
        assertFalse(budget.take(t0.plusSeconds(12.0)), "窗口内用满 3 次后不再续期")
        assertFalse(budget.canRenew(t0.plusSeconds(59.0)))
        // 最早一次滑出窗口后恢复一次额度。
        assertTrue(budget.take(t0.plusSeconds(61.0)))
    }

    @Test
    fun testBudgetEnforcesMinGapWithoutConsuming() {
        val budget = RenewalBudget(limit = 3, window = 60.0, minGap = 3.0)
        assertTrue(budget.take(t0))
        assertFalse(budget.take(t0.plusSeconds(1.0)), "间隔太近")
        assertFalse(budget.take(t0.plusSeconds(2.0)), "被拒的不记次数")
        assertTrue(budget.take(t0.plusSeconds(3.0)))
        assertTrue(budget.take(t0.plusSeconds(6.0)))
        assertFalse(budget.take(t0.plusSeconds(9.0)))
    }

    @Test
    fun testLongWatchingKeepsRenewingAtSteadyPace() {
        val budget = RenewalBudget()
        for (minute in 0 until 10) {
            assertTrue(budget.take(t0.plusSeconds(minute.toDouble() * 300.0)))
        }
    }

    /** 因取消而未完成的续期退回额度，不占最短间隔与次数。 */
    @Test
    fun testRefundFreesAttemptAndGap() {
        val budget = RenewalBudget(limit = 3, window = 60.0, minGap = 3.0)
        assertTrue(budget.take(t0))
        budget.refund(t0)
        assertTrue(budget.take(t0.plusSeconds(1.0)), "退回后不再受最短间隔限制")
        val full = RenewalBudget(limit = 1, window = 60.0, minGap = 3.0)
        assertTrue(full.take(t0))
        full.refund(t0)
        assertTrue(full.take(t0))
    }

    @Test
    fun testIsCancellationRecognizesCancelErrors() {
        assertTrue(RenewalPolicy.isCancellation(CancellationException()))
        assertTrue(RenewalPolicy.isCancellation(TransportError(UrlErrors.CANCELLED)))
        assertTrue(RenewalPolicy.isCancellation(TransportError(domain = UrlErrors.DOMAIN, code = UrlErrors.CANCELLED)))
        assertFalse(RenewalPolicy.isCancellation(TransportError(UrlErrors.TIMED_OUT)))
        assertFalse(RenewalPolicy.isCancellation(RelayFailure.Http(403)))
    }
}
