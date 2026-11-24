package vela.core.lifecycle

import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import vela.core.base.net.EndpointBackoffBox

/**
 * A8：只有正文像质询页才算质询；普通 403 / 503 是普通失败。连续质询有上限。
 * 流地址和目录那五条引用 StreamDecoder / CatalogAPI，登记 deferred:C9，这里不编译它们。
 */
class ChallengeGateTests {
    /** 默认共用的非 JSON 退避记录跨用例清空，免得前一个用例的退避挡住后一个。 */
    @BeforeTest
    fun setUp() {
        EndpointBackoffBox.shared.reset()
    }

    private val t0: Instant = Instant.ofEpochSecond(2_000_000)

    @Test
    fun testFirstChallengePresents() {
        val gate = ChallengeGate()
        assertEquals(ChallengeGate.Decision.Present, gate.register(t0))
        assertEquals(1, gate.consecutive)
    }

    @Test
    fun testQuickRepeatIsSuppressed() {
        val gate = ChallengeGate()
        gate.register(t0)
        assertEquals(
            ChallengeGate.Decision.Suppress,
            gate.register(t0.adding(ChallengeGate.repeatWindow - 1)),
        )
    }

    @Test
    fun testRepeatAfterWindowPresentsUntilLimit() {
        val gate = ChallengeGate()
        val step = ChallengeGate.repeatWindow + 1
        for (index in 0 until ChallengeGate.limit) {
            assertEquals(
                ChallengeGate.Decision.Present,
                gate.register(t0.adding(step * index)),
                "第 ${index + 1} 次",
            )
        }
        val over = t0.adding(step * ChallengeGate.limit)
        assertEquals(ChallengeGate.Decision.Suppress, gate.register(over), "超过上限不再弹")
        assertEquals(ChallengeGate.Decision.Suppress, gate.register(over.adding(step)))
    }

    @Test
    fun testRepeatWindowBoundaryPresents() {
        val gate = ChallengeGate()
        gate.register(t0)
        assertEquals(ChallengeGate.Decision.Present, gate.register(t0.adding(ChallengeGate.repeatWindow)))
    }

    @Test
    fun testResetAndLongGapStartOver() {
        val gate = ChallengeGate()
        gate.register(t0)
        gate.register(t0.adding(1.0))
        gate.reset()
        assertEquals(0, gate.consecutive)
        assertNull(gate.lastAt)
        assertEquals(ChallengeGate.Decision.Present, gate.register(t0.adding(2.0)), "成功后重新计数")

        val stale = ChallengeGate()
        for (index in 0..ChallengeGate.limit) {
            stale.register(t0.adding(index.toDouble()))
        }
        val later = t0.adding(ChallengeGate.limit.toDouble() + ChallengeGate.resetAfter)
        assertEquals(ChallengeGate.Decision.Present, stale.register(later), "很久以前的质询不算连续")
        assertEquals(1, stale.consecutive)
    }

    /** SearchFix13 的合并规则。类型在本包，C9 的类名对上之后以那边为准。 */
    @Test
    fun testChallengeResumeMergingKeepsSiteSearch() {
        assertEquals(ChallengeResume.SiteSearch("a"), ChallengeResume.Favorites.merging(ChallengeResume.SiteSearch("a")))
        assertEquals(
            ChallengeResume.SiteSearch("a"),
            ChallengeResume.SiteSearch("a").merging(ChallengeResume.Favorites),
            "补查收藏不顶掉要重跑的搜索",
        )
        assertEquals(
            ChallengeResume.SiteSearch("b"),
            ChallengeResume.SiteSearch("a").merging(ChallengeResume.SiteSearch("b")),
        )
        assertEquals(ChallengeResume.Reload, ChallengeResume.Reload.merging(ChallengeResume.SiteSearch("a")))
        assertEquals(ChallengeResume.Reload, ChallengeResume.SiteSearch("a").merging(ChallengeResume.Reload))
        assertEquals(ChallengeResume.Favorites, ChallengeResume.Favorites.merging(ChallengeResume.Favorites))
    }

    @Test
    fun testDroppingSiteSearchIntent() {
        assertNull(ChallengeResume.SiteSearch("a").droppingSiteSearch())
        assertEquals(ChallengeResume.Reload, ChallengeResume.Reload.droppingSiteSearch())
        assertEquals(ChallengeResume.Favorites, ChallengeResume.Favorites.droppingSiteSearch())
    }

    private fun Instant.adding(seconds: Double): Instant =
        plusNanos((seconds * 1_000_000_000.0).toLong())
}
