package vela.core.recommend

import java.time.Instant
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.taste.TagRarity
import vela.core.taste.Taste
import vela.core.taste.TasteTargeting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 综合场景：大量快速划走 + 少量长停留，长停留房间共有的稀有标签对应的房间应明显上升。 */
class RecommendScenarioTests {
    private val now: Instant = Instant.ofEpochSecond(1_800_000_000)
    private val seed: ULong = 2_026UL

    /** 240 个候选：teen / 18 / new 几乎每个房间都有，中频标签三选一，piano 只在 10 个房间（P0…P9）。 */
    private fun candidates(): List<Room> {
        val mids = listOf("blonde", "latina", "asian")
        fun tags(index: Int, rare: Boolean): List<String> {
            val tags = ArrayList<String>()
            if (index % 10 != 0) tags.add("teen")
            if (index % 7 != 0) tags.add("18")
            if (index % 5 != 0) tags.add("new")
            tags.add(mids[index % 3])
            if (rare) tags.add("piano")
            tags.add("own$index")
            return tags
        }
        val piano = (0 until 10).map { index ->
            Room(
                username = "P$index",
                viewers = 50,
                gender = Gender.female,
                tags = tags(index, rare = true),
                show = ShowState.publicShow,
            )
        }
        val rest = (0 until 230).map { index ->
            Room(
                username = "R$index",
                viewers = 50 + index,
                gender = Gender.female,
                tags = tags(index + 10, rare = false),
                show = ShowState.publicShow,
            )
        }
        return piano + rest
    }

    private fun rank(names: Set<String>, out: List<Room>): Double {
        val ranks = out.mapIndexedNotNull { index, room ->
            if (room.username in names) index.toDouble() else null
        }
        return ranks.sum() / ranks.size.toDouble()
    }

    @Test
    fun testLongStaysLiftRoomsSharingTheirRareTag() {
        val rooms = candidates()
        val rarity = TagRarity(rooms)
        val signals = RankSignals(rarity = rarity)
        val unwatched = (3 until 10).map { "P$it" }.toSet()
        val before = rank(unwatched, Recommender.recommend(rooms, Taste(), now, seed, signals = signals))

        var taste = Taste()
        var clock = now.minusSeconds(3_600L)
        for (room in rooms.filter { it.username.startsWith("R") }.take(120)) {
            clock = clock.plusSeconds(2L)
            taste = taste.recording(room = room, watched = 1.5, source = FeedSource.guess, now = clock, rarity = rarity)
        }
        for (name in listOf("P0", "P1", "P2")) {
            clock = clock.plusSeconds(240L)
            val room = rooms.first { it.username == name }
            taste = taste.recording(room = room, watched = 240.0, source = FeedSource.guess, now = clock, rarity = rarity)
        }
        val out = Recommender.recommend(rooms, taste, now, seed, signals = signals)
        val after = rank(unwatched, out)

        assertEquals(rooms.size, out.size)
        assertTrue(after < 25.0, "before $before after $after")
        assertTrue(after * 3 < before, "before $before after $after")
        assertTrue(taste.posterior(Taste.tagKey("teen"), now).beta - 1 < 120 * 0.3 * 0.2)
        assertTrue(out.take(30).count { "piano" !in it.tags } > 10)
        assertEquals("piano", TasteTargeting.favoriteTags(taste, now, rarity).first())
    }
}
