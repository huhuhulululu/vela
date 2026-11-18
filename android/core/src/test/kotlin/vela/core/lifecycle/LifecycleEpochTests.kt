package vela.core.lifecycle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.lane.PlaybackLaneRole

class LifecycleEpochTests {
    /** §3.1：挂起后请求、提交、中继注册、中继启动四处都拒绝；恢复后旧代次仍作废。 */
    @Test
    fun testSuspendInvalidatesRequestCommitRelayRegisterAndStart() {
        val life = LifecycleEpoch()
        val token = life.begin()
        assertEquals(0uL, token?.epoch)
        assertFalse(life.suspended)
        assertTrue(life.allowStart)
        for (gate in LifecycleEpoch.Gate.entries) {
            assertTrue(life.allows(gate, token!!), "$gate")
        }

        life.suspend()
        assertTrue(life.suspended)
        assertEquals(1uL, life.epoch)
        assertFalse(life.allowStart)
        assertNull(life.begin())
        for (gate in LifecycleEpoch.Gate.entries) {
            assertFalse(life.allows(gate, token!!), "$gate")
        }
        assertFalse(life.allows(LifecycleEpoch.Gate.BEFORE_COMMIT, token!!), "代次不一致就丢弃结果")

        life.resume()
        assertFalse(life.suspended)
        assertEquals(1uL, life.epoch)
        assertTrue(life.allowStart)
        for (gate in LifecycleEpoch.Gate.entries) {
            assertFalse(life.allows(gate, token), "恢复后仍丢弃挂起前的工作 $gate")
        }
        val fresh = life.begin()
        assertEquals(1uL, fresh?.epoch)
        for (gate in LifecycleEpoch.Gate.entries) {
            assertTrue(life.allows(gate, fresh!!))
        }
    }

    /** 未挂起时共享任务和每一路的四个 gate 都放行。 */
    @Test
    fun testNotSuspendedAllowsSharedTasksAndEveryLane() {
        val life = LifecycleEpoch()
        val shared = life.begin()
        val primary = life.begin(PlaybackLaneRole.PRIMARY)
        val secondary = life.begin(PlaybackLaneRole.SECONDARY)
        assertEquals(0uL, shared?.epoch)
        assertEquals(LifecycleEpoch.Token.Scope.Shared, shared?.scope)
        assertEquals(LifecycleEpoch.Token.Scope.Lane(PlaybackLaneRole.PRIMARY), primary?.scope)
        assertEquals(LifecycleEpoch.Token.Scope.Lane(PlaybackLaneRole.SECONDARY), secondary?.scope)
        assertFalse(life.suspended)
        assertNull(life.allowedLane)
        for (token in listOf(shared!!, primary!!, secondary!!)) {
            for (gate in LifecycleEpoch.Gate.entries) {
                assertTrue(life.allows(gate, token), "${token.scope} $gate")
            }
        }
    }

    /**
     * 画中画挂起：目录、搜索、收藏查询、侧车、缩略图四个 gate 被拒；
     * 放行路的请求、中继登记、中继启动、提交通过；另一路被拒。
     * resume 不回退代次，挂起前的 token 全部失效，之后新开工的 token 通过。
     */
    @Test
    fun testPictureInPictureAllowsOnlyTheGivenLaneThenResumeDropsOldTokens() {
        val life = LifecycleEpoch()
        val sharedBefore = life.begin()
        val primaryBefore = life.begin(PlaybackLaneRole.PRIMARY)
        val secondaryBefore = life.begin(PlaybackLaneRole.SECONDARY)

        life.suspend(allowingLane = PlaybackLaneRole.PRIMARY)
        assertTrue(life.suspended)
        assertEquals(PlaybackLaneRole.PRIMARY, life.allowedLane)
        assertEquals(1uL, life.epoch)
        assertTrue(life.allowStart, "放行路时中继仍可启动")

        assertNull(life.begin(), "共享任务不得开工")
        val sharedNow = LifecycleEpoch.Token(epoch = life.epoch, scope = LifecycleEpoch.Token.Scope.Shared)
        val sharedTasks = listOf("目录", "搜索", "收藏查询", "侧车", "缩略图")
        for (name in sharedTasks) {
            for (gate in LifecycleEpoch.Gate.entries) {
                assertFalse(life.allows(gate, sharedNow), "$name $gate")
            }
        }
        for (token in listOf(sharedBefore!!, primaryBefore!!, secondaryBefore!!)) {
            for (gate in LifecycleEpoch.Gate.entries) {
                assertFalse(life.allows(gate, token), "挂起前 ${token.scope} $gate")
            }
        }

        val pip = life.begin(PlaybackLaneRole.PRIMARY)
        assertEquals(1uL, pip?.epoch)
        assertEquals(LifecycleEpoch.Token.Scope.Lane(PlaybackLaneRole.PRIMARY), pip?.scope)
        val pipGates = listOf(
            LifecycleEpoch.Gate.BEFORE_REQUEST,
            LifecycleEpoch.Gate.BEFORE_RELAY_REGISTER,
            LifecycleEpoch.Gate.BEFORE_RELAY_START,
            LifecycleEpoch.Gate.BEFORE_COMMIT,
        )
        for (gate in pipGates) {
            assertTrue(life.allows(gate, pip!!), "放行路 $gate")
        }

        assertNull(life.begin(PlaybackLaneRole.SECONDARY), "另一路不得开工")
        val other = LifecycleEpoch.Token(
            epoch = life.epoch,
            scope = LifecycleEpoch.Token.Scope.Lane(PlaybackLaneRole.SECONDARY),
        )
        for (gate in LifecycleEpoch.Gate.entries) {
            assertFalse(life.allows(gate, other), "另一路 $gate")
        }

        life.resume()
        assertFalse(life.suspended)
        assertNull(life.allowedLane)
        assertEquals(1uL, life.epoch, "resume 不回退代次")
        assertTrue(life.allowStart)
        for (token in listOf(sharedBefore, primaryBefore, secondaryBefore)) {
            for (gate in LifecycleEpoch.Gate.entries) {
                assertFalse(life.allows(gate, token), "resume 后旧 token ${token.scope} $gate")
            }
        }
        val freshShared = life.begin()
        val freshPrimary = life.begin(PlaybackLaneRole.PRIMARY)
        val freshSecondary = life.begin(PlaybackLaneRole.SECONDARY)
        assertEquals(1uL, freshShared?.epoch)
        assertEquals(1uL, freshPrimary?.epoch)
        assertEquals(1uL, freshSecondary?.epoch)
        for (token in listOf(freshShared!!, freshPrimary!!, freshSecondary!!)) {
            for (gate in LifecycleEpoch.Gate.entries) {
                assertTrue(life.allows(gate, token), "新 token ${token.scope} $gate")
            }
        }
    }

    /** 连续两次 `suspend(allowingLane:)` 换路后，只放行最新那一路。 */
    @Test
    fun testConsecutiveSuspendAllowingLaneKeepsOnlyTheLatestLane() {
        val life = LifecycleEpoch()
        life.suspend(allowingLane = PlaybackLaneRole.PRIMARY)
        val primary = life.begin(PlaybackLaneRole.PRIMARY)
        assertTrue(primary != null)
        assertEquals(1uL, life.epoch)

        life.suspend(allowingLane = PlaybackLaneRole.SECONDARY)
        assertEquals(PlaybackLaneRole.SECONDARY, life.allowedLane)
        assertEquals(2uL, life.epoch)
        assertTrue(life.suspended)

        for (gate in LifecycleEpoch.Gate.entries) {
            assertFalse(life.allows(gate, primary!!), "上一路的旧 token $gate")
        }
        assertNull(life.begin(PlaybackLaneRole.PRIMARY))
        val primaryNow = LifecycleEpoch.Token(
            epoch = life.epoch,
            scope = LifecycleEpoch.Token.Scope.Lane(PlaybackLaneRole.PRIMARY),
        )
        for (gate in LifecycleEpoch.Gate.entries) {
            assertFalse(life.allows(gate, primaryNow), "上一路 $gate")
        }
        assertNull(life.begin())
        val shared = LifecycleEpoch.Token(epoch = life.epoch, scope = LifecycleEpoch.Token.Scope.Shared)
        for (gate in LifecycleEpoch.Gate.entries) {
            assertFalse(life.allows(gate, shared), "共享 $gate")
        }

        val secondary = life.begin(PlaybackLaneRole.SECONDARY)
        assertEquals(LifecycleEpoch.Token.Scope.Lane(PlaybackLaneRole.SECONDARY), secondary?.scope)
        for (gate in LifecycleEpoch.Gate.entries) {
            assertTrue(life.allows(gate, secondary!!), "最新一路 $gate")
        }

        life.suspend()
        assertNull(life.allowedLane)
        assertFalse(life.allowStart)
        assertNull(life.begin(PlaybackLaneRole.SECONDARY))
        val closed = LifecycleEpoch.Token(
            epoch = life.epoch,
            scope = LifecycleEpoch.Token.Scope.Lane(PlaybackLaneRole.SECONDARY),
        )
        for (gate in LifecycleEpoch.Gate.entries) {
            assertFalse(life.allows(gate, closed), "全量挂起后不再放行 $gate")
        }
    }
}
