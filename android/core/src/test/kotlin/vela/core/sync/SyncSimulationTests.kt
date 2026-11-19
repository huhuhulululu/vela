package vela.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import vela.core.curation.Curation

/** 第 7.2 节：固定种子的 1000 组随机多副本，最终一致且不超过上限。 */
class SyncSimulationTests {
    class SplitMix64(var state: Long) {
        fun next(): Long {
            state += 0x9E3779B97F4A7C15u.toLong()
            var z = state
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9u.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBu.toLong()
            return z xor (z ushr 31)
        }

        fun int(from: Int, to: Int): Int {
            val span = (to - from + 1).toLong()
            var bits = next().toULong()
            val bound = span.toULong()
            while (true) {
                val max = ULong.MAX_VALUE / bound * bound
                if (bits < max) return from + (bits % bound).toInt()
                bits = next().toULong()
            }
        }

        fun long(from: Long, to: Long): Long {
            val span = (to - from + 1).toULong()
            var bits = next().toULong()
            while (true) {
                val max = ULong.MAX_VALUE / span * span
                if (bits < max) return from + (bits % span).toLong()
                bits = next().toULong()
            }
        }

        fun bool(): Boolean = next() < 0
    }

    class Device(var replica: SyncReplica, val skew: Long, var maxSentL: Long = 0)

    class Outcome(val heavy: Boolean, var trimmedSomething: Boolean = false)

    @Test
    fun testRandomReplicasConverge() {
        var heavyRuns = 0
        var trimmedRuns = 0
        var ops = 0
        for (run in 0 until RUNS) {
            val rng = SplitMix64(SEED + run.toLong() * 0x100000001L)
            val heavy = run % 10 == 0
            val result = simulate(run, heavy, rng) { ops += 1 }
            if (result.heavy) heavyRuns += 1
            if (result.trimmedSomething) trimmedRuns += 1
        }
        println("同步模拟：$RUNS 组，其中重负载 $heavyRuns 组、实际触发裁剪 $trimmedRuns 组，本地操作 $ops 次")
        assertTrue(trimmedRuns > 0, "重负载组必须真的超过上限")
    }

    private fun simulate(run: Int, heavy: Boolean, rng: SplitMix64, countOp: () -> Unit): Outcome {
        val outcome = Outcome(heavy)
        val count = rng.int(2, 6)
        var now = 1_800_000_000_000L
        val devices = (0 until count).map { i ->
            Device(
                SyncReplica(SyncDocument.empty(), SyncClock(0, 0, (i + 1).toLong() * 0x01010101)),
                if (i == 0 && run % 3 == 0) 3 * 3_600_000L else 0L,
            )
        }.toMutableList()
        var server = SyncDocument.empty()
        var oracle = SyncDocument.empty()
        val namePool = if (heavy) 700 else 120
        val tagPool = if (heavy) 1_200 else 60
        val steps = if (heavy) 120 else 40
        fun name() = "u%03d".format(rng.int(0, namePool - 1))
        fun tag() = "t:k%03d".format(rng.int(0, tagPool - 1))
        fun maxL(d: SyncDocument) = d.allRegisters.maxOfOrNull { it.hlc.l } ?: 0L
        fun merge(a: SyncDocument, b: SyncDocument): SyncDocument {
            val u = SyncMerge.union(a, b)
            val fitted = SyncMerge.fitSize(u)
            if (fitted.doc != u) outcome.trimmedSomething = true
            return fitted.doc
        }
        fun syncWithServer(i: Int) {
            val d = devices[i]
            d.replica.doc = merge(d.replica.doc, server)
            d.replica.clock.receive(server.maxHlc)
            SyncRestamp.presend(d.replica, now, d.maxSentL, now)
            server = d.replica.doc
            d.maxSentL = maxOf(d.maxSentL, maxL(d.replica.doc))
        }
        if (heavy) {
            for (i in 0 until count) {
                val favs = (0 until rng.int(100, 300)).map { name() }
                val blocks = (0 until rng.int(0, 120)).map { name() }
                val vetoes = (0 until rng.int(200, 460)).map { tag() }
                val local = SyncLocalData(Curation(favs, blocks), vetoes)
                devices[i].replica = SyncMigration.stampCreator(local, devices[i].replica.clock, now + devices[i].skew).first
                if (rng.bool()) {
                    val applied = SyncOps.apply(SyncLocalOp.ClearVetoes, devices[i].replica, now + devices[i].skew + 1)
                    if (applied is SyncOpOutcome.Applied) devices[i].replica = applied.replica
                }
            }
        }
        repeat(steps) {
            now += rng.long(0, 3)
            val i = rng.int(0, count - 1)
            when (rng.int(0, 99)) {
                in 0..54 -> {
                    val op = when (rng.int(0, 11)) {
                        0, 1, 2 -> SyncLocalOp.Favorite(name())
                        3 -> SyncLocalOp.Unfavorite(name())
                        4, 5 -> SyncLocalOp.Block(name())
                        6 -> SyncLocalOp.Unblock(name())
                        7 -> SyncLocalOp.RestoreFavorite(name())
                        8 -> SyncLocalOp.Veto(tag())
                        9 -> SyncLocalOp.Unveto(tag())
                        10 -> if (rng.bool()) SyncLocalOp.Setting("publicOnly", rng.bool()) else SyncLocalOp.ClearVetoes
                        else -> SyncLocalOp.Importing((0 until if (heavy) rng.int(50, 320) else rng.int(1, 8)).map { name() })
                    }
                    countOp()
                    val applied = SyncOps.apply(op, devices[i].replica, now + devices[i].skew)
                    if (applied is SyncOpOutcome.Applied) {
                        devices[i].replica = applied.replica
                        oracle = SyncMerge.union(oracle, applied.replica.doc)
                    }
                }
                in 55..79 -> {
                    val j = rng.int(0, count - 1)
                    devices[j].maxSentL = maxOf(devices[j].maxSentL, maxL(devices[j].replica.doc))
                    val merged = merge(devices[i].replica.doc, devices[j].replica.doc)
                    if (rng.int(0, 3) == 0) {
                        assertEquals(merged, merge(devices[j].replica.doc, devices[i].replica.doc), "交换律 run=$run")
                    }
                    devices[i].replica.doc = merged
                    devices[i].replica.clock.receive(merged.maxHlc)
                    if (rng.bool()) {
                        devices[i].maxSentL = maxOf(devices[i].maxSentL, maxL(devices[i].replica.doc))
                        devices[j].replica.doc = merged
                        devices[j].replica.clock.receive(merged.maxHlc)
                    }
                    if (rng.bool()) {
                        assertEquals(merge(devices[i].replica.doc, merged), devices[i].replica.doc, "幂等 run=$run")
                    }
                }
                else -> syncWithServer(i)
            }
            assertTrue(SyncMerge.withinCounts(devices[i].replica.doc), "上限 run=$run")
        }
        var quiet = 0
        var rounds = 0
        while (quiet < 2) {
            val before = devices.map { it.replica.doc } + server
            for (i in 0 until count) syncWithServer(i)
            val after = devices.map { it.replica.doc } + server
            quiet = if (before == after) quiet + 1 else 0
            rounds += 1
            if (rounds > 20) {
                fail("收尾不收敛 run=$run")
                break
            }
        }
        val final = devices[0].replica.doc
        for (d in devices) assertEquals(final, d.replica.doc, "最终一致 run=$run")
        assertTrue(SyncMerge.withinCounts(final), "最终上限 run=$run")
        assertTrue(final.encoded().size <= SyncLimits.plaintextBytes)
        assertEquals(SyncDecodeResult.Valid(final), SyncDocument.decode(final.encoded()), "最终文档合法 run=$run")
        if (!heavy && !outcome.trimmedSomething && devices.all { it.skew == 0L }) {
            assertEquals(oracle, final, "从未超限时所有操作全部生效 run=$run")
        }
        return outcome
    }

    companion object {
        const val SEED: Long = 0x5EED_2026_1118
        const val RUNS: Int = 1_000
    }
}
