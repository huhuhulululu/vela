package vela.core.selection

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.lane.LaneCursor
import vela.core.base.lane.LaneID
import vela.core.base.lane.RoomTarget
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage
import vela.core.feed.FeedStore
import vela.core.feed.SiteSearch
import vela.core.taste.Taste

/** 联合选房：单窗必须与 FeedStore 自己的选择一致；双窗按 §4.1 调整副路并避免两路同房。 */
class LaneSelectionTests {
    private fun room(name: String, tags: List<String> = emptyList()): Room =
        Room(username = name, viewers = 10, gender = Gender.female, tags = tags, show = ShowState.publicShow)

    private fun makeFeed(
        names: List<String>,
        current: String? = null,
        query: RoomQuery = RoomQuery(),
    ): FeedStore {
        val store = FeedStore(query = query)
        store.replace(
            CatalogPage(
                rooms = names.map { room(it) },
                total = maxOf(names.size, 1),
                nextOffset = names.size,
            ),
        )
        if (current != null) {
            assertTrue(store.focus(current), "测试夹具应能停在 $current")
        }
        return store
    }

    private fun resolve(
        before: FeedStore,
        afterRooms: List<String>,
        proposed: String?,
        secondary: LaneCursor?,
        focus: LaneID = LaneID.PRIMARY,
        primaryDirection: Int = 1,
        intent: LaneSelection.Intent,
    ): LaneSelection.Outcome =
        LaneSelection.resolve(
            before = LaneSelection.Snapshot(
                rooms = before.rooms.map { it.username },
                primary = LaneCursor(username = before.currentUsername, direction = primaryDirection),
                secondary = secondary,
                focus = focus,
            ),
            afterRooms = afterRooms,
            proposedPrimary = proposed,
            intent = intent,
        )

    /** 屏蔽主路后，FeedStore 会落到副路那间；主路沿自己的方向再找一间，两路不重复。 */
    @Test
    fun testBlockingPrimarySkipsTheSecondaryRoom() {
        val before = makeFeed(listOf("a", "b", "c", "d"), current = "b")
        val blocked = before.copy()
        blocked.setBlocked(setOf("b"))
        assertEquals("c", blocked.currentUsername, "单窗被动选择是后一间")
        assertEquals(listOf("a", "c", "d"), blocked.rooms.map { it.username })

        val forward = resolve(
            before = before,
            afterRooms = blocked.rooms.map { it.username },
            proposed = blocked.currentUsername,
            secondary = LaneCursor(username = "d"),
            intent = LaneSelection.Intent.Filter,
        )
        assertEquals("c", forward.primary.username, "没落到副路时保留 FeedStore 的选择")
        assertEquals("d", forward.secondary?.username)

        val collision = resolve(
            before = before,
            afterRooms = blocked.rooms.map { it.username },
            proposed = blocked.currentUsername,
            secondary = LaneCursor(username = "c"),
            intent = LaneSelection.Intent.Filter,
        )
        assertEquals("d", collision.primary.username)
        assertEquals("c", collision.secondary?.username)
        assertTrue(collision.primary.username != collision.secondary?.username)

        val backward = resolve(
            before = before,
            afterRooms = blocked.rooms.map { it.username },
            proposed = blocked.currentUsername,
            secondary = LaneCursor(username = "c"),
            primaryDirection = -1,
            intent = LaneSelection.Intent.Filter,
        )
        assertEquals("a", backward.primary.username, "主路沿自己的方向（向上）另找")
        assertEquals("c", backward.secondary?.username)
        assertEquals(-1, backward.primary.direction)
    }

    @Test
    fun testUnfavoritingPrimaryDoesNotDuplicateSecondary() {
        val favorites = setOf("a", "b", "c")
        val before = makeFeed(listOf("a", "b", "c"), current = "b", query = RoomQuery(only = favorites))
        val after = before.copy()
        after.setFilters(blocked = emptySet(), only = setOf("a", "c"))
        assertEquals(listOf("a", "c"), after.rooms.map { it.username })
        assertEquals("c", after.currentUsername)

        val outcome = resolve(
            before = before,
            afterRooms = after.rooms.map { it.username },
            proposed = after.currentUsername,
            secondary = LaneCursor(username = "c"),
            intent = LaneSelection.Intent.Filter,
        )
        assertEquals("a", outcome.primary.username, "向前没有别的房间，就反向离开副路")
        assertEquals("c", outcome.secondary?.username)
        assertTrue(outcome.primary.username != outcome.secondary?.username)
    }

    /** setQuery 在当前房间两侧都有匹配时取新列表第一项，而不是原位置的后一项。副路仍匹配则不动。 */
    @Test
    fun testSubmittingLocalSearchKeepsSecondaryWhenItStillMatches() {
        val before = makeFeed(listOf("amy", "bob", "cara", "dave"), current = "bob")
        val after = before.copy()
        after.setQuery(RoomQuery(text = "a"))
        assertEquals(listOf("amy", "cara", "dave"), after.rooms.map { it.username })
        assertEquals("amy", after.currentUsername)

        val outcome = resolve(
            before = before,
            afterRooms = after.rooms.map { it.username },
            proposed = after.currentUsername,
            secondary = LaneCursor(username = "dave"),
            intent = LaneSelection.Intent.Filter,
        )
        assertEquals("amy", outcome.primary.username)
        assertEquals("dave", outcome.secondary?.username, "全站重载才会把副路改到主路的后一间")
    }

    @Test
    fun testTwoRoomsCollapseToTheFocusedLane() {
        val before = makeFeed(listOf("a", "b"), current = "a")
        val onPrimary = resolve(
            before = before,
            afterRooms = listOf("a"),
            proposed = "a",
            secondary = LaneCursor(username = "b"),
            focus = LaneID.PRIMARY,
            intent = LaneSelection.Intent.Filter,
        )
        assertEquals("a", onPrimary.primary.username)
        assertNotNull(onPrimary.secondary)
        assertNull(onPrimary.secondary?.username)
        assertEquals(LaneID.PRIMARY, onPrimary.focus)
        assertEquals(LaneSelection.Reason.EMPTY, onPrimary.reasons[LaneID.SECONDARY])

        val onSecondary = resolve(
            before = before,
            afterRooms = listOf("a"),
            proposed = "a",
            secondary = LaneCursor(username = "b"),
            focus = LaneID.SECONDARY,
            intent = LaneSelection.Intent.Filter,
        )
        assertNull(onSecondary.primary.username)
        assertEquals("a", onSecondary.secondary?.username)
        assertEquals(LaneID.SECONDARY, onSecondary.focus)
        assertEquals(LaneSelection.Reason.EMPTY, onSecondary.reasons[LaneID.PRIMARY])
    }

    @Test
    fun testEmptyListClearsBothLanes() {
        val before = makeFeed(listOf("a", "b"), current = "a")
        val after = before.copy()
        after.setBlocked(setOf("a", "b"))
        assertNull(after.current)
        assertTrue(after.rooms.isEmpty())

        val outcome = resolve(
            before = before,
            afterRooms = emptyList(),
            proposed = null,
            secondary = LaneCursor(username = "b"),
            intent = LaneSelection.Intent.Filter,
        )
        assertNull(outcome.primary.username)
        assertNotNull(outcome.secondary, "双窗的副路还在，只是没有房间")
        assertNull(outcome.secondary?.username)
        assertEquals(LaneSelection.Reason.EMPTY, outcome.reasons[LaneID.PRIMARY])
        assertEquals(LaneSelection.Reason.EMPTY, outcome.reasons[LaneID.SECONDARY])
    }

    @Test
    fun testBlankResumeReselectsBothLanes() {
        val resumed = FeedStore()
        resumed.append(CatalogPage(rooms = listOf(room("c"), room("d"), room("e")), total = 3, nextOffset = 3))
        assertEquals("c", resumed.currentUsername)

        val outcome = LaneSelection.resolve(
            before = LaneSelection.Snapshot(
                rooms = emptyList(),
                primary = LaneCursor(username = null, direction = -1),
                secondary = LaneCursor(username = null, direction = 1),
                focus = LaneID.PRIMARY,
            ),
            afterRooms = resumed.rooms.map { it.username },
            proposedPrimary = resumed.currentUsername,
            intent = LaneSelection.Intent.BlankResume,
        )
        assertEquals("c", outcome.primary.username)
        assertEquals("d", outcome.secondary?.username)
        assertEquals(-1, outcome.primary.direction, "重新选房不改这路原来的方向")
        assertEquals(LaneSelection.Reason.RESUME, outcome.reasons[LaneID.PRIMARY])
        assertEquals(LaneSelection.Reason.RESUME, outcome.reasons[LaneID.SECONDARY])
        assertTrue(outcome.primary.username != outcome.secondary?.username)
    }

    /** 经过 `updateFeed` 用的入口：可见列表由空变非空（续页、放宽筛选）时按 blankResume 两路都选上。 */
    @Test
    fun testFeedChangeFromBlankResumesBothLanes() {
        val blank = LaneSelection.Snapshot(
            rooms = emptyList(),
            primary = LaneCursor(username = null, direction = 1),
            secondary = LaneCursor(username = null, direction = 1),
            focus = LaneID.PRIMARY,
        )
        for (intent in listOf(LaneSelection.Intent.Merge, LaneSelection.Intent.Filter)) {
            val outcome = LaneSelection.resolveFeedChange(
                before = blank,
                afterRooms = listOf("c", "d", "e"),
                proposedPrimary = "c",
                intent = intent,
            )
            assertEquals("c", outcome.primary.username, "$intent")
            assertEquals("d", outcome.secondary?.username, "$intent 副路也要续上")
            assertEquals(LaneSelection.Reason.RESUME, outcome.reasons[LaneID.PRIMARY], "$intent")
            assertEquals(LaneSelection.Reason.RESUME, outcome.reasons[LaneID.SECONDARY], "$intent")
        }
        assertEquals(
            LaneSelection.Intent.BlankResume,
            LaneSelection.feedIntent(LaneSelection.Intent.Merge, emptyList(), listOf("a")),
        )
        assertEquals(
            LaneSelection.Intent.Merge,
            LaneSelection.feedIntent(LaneSelection.Intent.Merge, listOf("a"), listOf("a", "b")),
            "非空到非空不改",
        )
        assertEquals(
            LaneSelection.Intent.Filter,
            LaneSelection.feedIntent(LaneSelection.Intent.Filter, emptyList(), emptyList()),
            "仍为空不改",
        )
        assertEquals(
            LaneSelection.Intent.Reload,
            LaneSelection.feedIntent(LaneSelection.Intent.Reload, emptyList(), listOf("a")),
            "整表换源保留原意图",
        )
        assertEquals(
            LaneSelection.Intent.Reveal("a"),
            LaneSelection.feedIntent(LaneSelection.Intent.Reveal("a"), emptyList(), listOf("a")),
        )
    }

    /** 单窗：续页恢复与 FeedStore 的选择一致（macOS 行为不变）。 */
    @Test
    fun testFeedChangeFromBlankSingleWindowMatchesFeedStore() {
        val resumed = FeedStore()
        resumed.append(CatalogPage(rooms = listOf(room("c"), room("d")), total = 2, nextOffset = 2))
        val outcome = LaneSelection.resolveFeedChange(
            before = LaneSelection.Snapshot(
                rooms = emptyList(),
                primary = LaneCursor(),
                secondary = null,
                focus = LaneID.PRIMARY,
            ),
            afterRooms = resumed.rooms.map { it.username },
            proposedPrimary = resumed.currentUsername,
            intent = LaneSelection.Intent.Merge,
        )
        assertEquals(resumed.currentUsername, outcome.primary.username)
        assertNull(outcome.secondary)
    }

    @Test
    fun testRevealTargetOnTheOtherLaneOnlySwitchesFocus() {
        val before = makeFeed(listOf("a", "b", "c"), current = "a")
        val revealed = before.copy()
        assertTrue(revealed.reveal(room("b")))
        assertEquals("b", revealed.currentUsername, "单窗会把主路直接改到目标")

        val outcome = resolve(
            before = before,
            afterRooms = revealed.rooms.map { it.username },
            proposed = revealed.currentUsername,
            secondary = LaneCursor(username = "b"),
            focus = LaneID.PRIMARY,
            intent = LaneSelection.Intent.Reveal("b"),
        )
        assertEquals(LaneID.SECONDARY, outcome.focus)
        assertEquals("a", outcome.primary.username)
        assertEquals("b", outcome.secondary?.username)
        assertTrue(outcome.reasons.isEmpty(), "只切焦点，两路游标都不结算")
    }

    @Test
    fun testSiteSearchInstallAndExitPlaceSecondaryAfterPrimary() {
        val before = makeFeed(listOf("a", "b", "c"), current = "b")
        val installed = FeedStore(currentUsername = "b")
        installed.replace(SiteSearch.page(listOf(room("s1"), room("b"), room("s3")), blocked = emptySet()))
        installed.setQuery(RoomQuery())
        assertEquals("b", installed.currentUsername)

        val install = resolve(
            before = before,
            afterRooms = installed.rooms.map { it.username },
            proposed = installed.currentUsername,
            secondary = LaneCursor(username = "c"),
            intent = LaneSelection.Intent.SearchInstall,
        )
        assertEquals("b", install.primary.username)
        assertEquals("s3", install.secondary?.username, "副路取新列表里主路之后的第一间，不沿用旧副路")

        val restored = makeFeed(listOf("a", "b", "c"), current = "a")
        val exit = LaneSelection.resolve(
            before = LaneSelection.Snapshot(
                rooms = installed.rooms.map { it.username },
                primary = LaneCursor(username = "s1"),
                secondary = LaneCursor(username = "s3"),
                focus = LaneID.PRIMARY,
            ),
            afterRooms = restored.rooms.map { it.username },
            proposedPrimary = restored.currentUsername,
            intent = LaneSelection.Intent.SearchExit,
        )
        assertEquals("a", restored.currentUsername)
        assertEquals("a", exit.primary.username)
        assertEquals("b", exit.secondary?.username)
        assertEquals(LaneSelection.Reason.SOURCE_CHANGE, exit.reasons[LaneID.PRIMARY])
        assertEquals(LaneSelection.Reason.SOURCE_CHANGE, exit.reasons[LaneID.SECONDARY])
    }

    @Test
    fun testReloadPlacesSecondaryAfterPrimaryInsteadOfKeepingIt() {
        val before = makeFeed(listOf("a", "b", "c", "d"), current = "b")
        val reloaded = before.copy()
        reloaded.replace(
            CatalogPage(
                rooms = listOf(room("a"), room("b"), room("c"), room("d")),
                total = 4,
                nextOffset = 4,
            ),
        )
        reloaded.setQuery(RoomQuery())
        assertEquals("b", reloaded.currentUsername)

        val outcome = resolve(
            before = before,
            afterRooms = reloaded.rooms.map { it.username },
            proposed = reloaded.currentUsername,
            secondary = LaneCursor(username = "d"),
            intent = LaneSelection.Intent.Reload,
        )
        assertEquals("b", outcome.primary.username)
        assertEquals("c", outcome.secondary?.username)

        val atEnd = resolve(
            before = makeFeed(listOf("a", "b", "c"), current = "c"),
            afterRooms = listOf("a", "b", "c"),
            proposed = "c",
            secondary = LaneCursor(username = "a"),
            intent = LaneSelection.Intent.Reload,
        )
        assertEquals("c", atEnd.primary.username)
        assertNull(atEnd.secondary?.username, "主路已经是最后一间时副路为空，不绕回列表开头")
    }

    @Test
    fun testMergeKeepsLanesWhoseRoomsRemain() {
        val before = makeFeed(listOf("a", "b", "c", "d"), current = "b")
        val after = before.copy()
        after.reorderAfterCurrent { rooms -> rooms.sortedByDescending { it.username } }
        assertEquals(listOf("a", "b", "d", "c"), after.catalog.map { it.username })
        assertEquals("b", after.currentUsername)

        val outcome = resolve(
            before = before,
            afterRooms = after.rooms.map { it.username },
            proposed = after.currentUsername,
            secondary = LaneCursor(username = "d", direction = -1),
            intent = LaneSelection.Intent.Merge,
        )
        assertEquals("b", outcome.primary.username)
        assertEquals("d", outcome.secondary?.username)
        assertEquals(-1, outcome.secondary?.direction)
        assertTrue(outcome.reasons.isEmpty())
    }

    /** 「不想看」或取消收藏让副路房间消失：沿这路原来的方向找，找不到再反方向，再没有就空。 */
    @Test
    fun testSecondaryLostToUnfavoriteOrHiddenSearchesAlongItsDirection() {
        val all = setOf("a", "b", "c", "d", "e")
        val favorites = makeFeed(listOf("a", "b", "c", "d", "e"), current = "a", query = RoomQuery(only = all))
        val dropped = favorites.copy()
        dropped.setFilters(blocked = emptySet(), only = setOf("a", "b", "d", "e"))
        assertEquals(listOf("a", "b", "d", "e"), dropped.rooms.map { it.username })
        assertEquals("a", dropped.currentUsername)

        val forward = resolve(
            before = favorites,
            afterRooms = dropped.rooms.map { it.username },
            proposed = dropped.currentUsername,
            secondary = LaneCursor(username = "c", direction = 1),
            intent = LaneSelection.Intent.Filter,
        )
        assertEquals("d", forward.secondary?.username)
        assertEquals("a", forward.primary.username)

        val backward = resolve(
            before = favorites,
            afterRooms = dropped.rooms.map { it.username },
            proposed = dropped.currentUsername,
            secondary = LaneCursor(username = "c", direction = -1),
            intent = LaneSelection.Intent.Filter,
        )
        assertEquals("b", backward.secondary?.username)

        val tagged = FeedStore()
        tagged.replace(
            CatalogPage(
                rooms = listOf(
                    room("a"),
                    room("b"),
                    room("c"),
                    room("d"),
                    room("e", tags = listOf("nope")),
                ),
                total = 5,
                nextOffset = 5,
            ),
        )
        assertTrue(tagged.focus("a"))
        val hidden = tagged.copy()
        hidden.setFilters(blocked = emptySet(), only = null, hidden = setOf(Taste.tagKey("nope")))
        assertEquals(listOf("a", "b", "c", "d"), hidden.rooms.map { it.username })
        val reversed = resolve(
            before = tagged,
            afterRooms = hidden.rooms.map { it.username },
            proposed = hidden.currentUsername,
            secondary = LaneCursor(username = "e", direction = 1),
            intent = LaneSelection.Intent.Filter,
        )
        assertEquals("d", reversed.secondary?.username, "原方向没有下一间，就反方向找")
        assertEquals("a", reversed.primary.username)
    }

    @Test
    fun testAutoSkipUsesEachLanesOwnDirection() {
        val before = makeFeed(listOf("a", "b", "c", "d"), current = "b")
        val primaryCursor = LaneCursor(username = "b", direction = -1)
        val secondaryCursor = LaneCursor(username = "c", direction = 1)

        val primaryHop = LaneSelection.resolve(
            before = LaneSelection.Snapshot(
                rooms = before.rooms.map { it.username },
                primary = primaryCursor,
                secondary = secondaryCursor,
                focus = LaneID.PRIMARY,
            ),
            afterRooms = before.rooms.map { it.username },
            proposedPrimary = before.currentUsername,
            intent = LaneSelection.Intent.Move(LaneID.PRIMARY, primaryCursor.autoDelta),
        )
        assertEquals("a", primaryHop.primary.username)
        assertEquals(-1, primaryHop.primary.autoDelta)
        assertEquals("c", primaryHop.secondary?.username, "另一路的方向和房间都不被动")
        assertEquals(1, primaryHop.secondary?.autoDelta)
        assertEquals(LaneSelection.Reason.MOVE, primaryHop.reasons[LaneID.PRIMARY])

        val secondaryHop = LaneSelection.resolve(
            before = LaneSelection.Snapshot(
                rooms = before.rooms.map { it.username },
                primary = primaryCursor,
                secondary = secondaryCursor,
                focus = LaneID.SECONDARY,
            ),
            afterRooms = before.rooms.map { it.username },
            proposedPrimary = before.currentUsername,
            intent = LaneSelection.Intent.Move(LaneID.SECONDARY, secondaryCursor.autoDelta),
        )
        assertEquals("b", secondaryHop.primary.username)
        assertEquals(-1, secondaryHop.primary.autoDelta)
        assertEquals("d", secondaryHop.secondary?.username)
        assertEquals(LaneSelection.Reason.MOVE, secondaryHop.reasons[LaneID.SECONDARY])
    }

    @Test
    fun testMoveDoesNotLandOnTheOtherLane() {
        val before = makeFeed(listOf("a", "b", "c", "d"), current = "b")
        val stepped = before.copy()
        stepped.step(1, setOf("c"))

        val outcome = resolve(
            before = before,
            afterRooms = before.rooms.map { it.username },
            proposed = before.currentUsername,
            secondary = LaneCursor(username = "c"),
            intent = LaneSelection.Intent.Move(LaneID.PRIMARY, 1),
        )
        assertEquals("d", stepped.currentUsername)
        assertEquals(stepped.currentUsername, outcome.primary.username)
        assertEquals("c", outcome.secondary?.username)

        val blocked = resolve(
            before = makeFeed(listOf("a", "b"), current = "a"),
            afterRooms = listOf("a", "b"),
            proposed = "a",
            secondary = LaneCursor(username = "b"),
            intent = LaneSelection.Intent.Move(LaneID.PRIMARY, 1),
        )
        assertEquals("a", blocked.primary.username, "方向上只剩副路时主路停住")
        assertEquals("b", blocked.secondary?.username)
        assertTrue(blocked.reasons.isEmpty())
    }

    @Test
    fun testSingleWindowMatchesFeedStoreSelection() {
        fun matches(
            label: String,
            intent: LaneSelection.Intent,
            before: FeedStore,
            mutate: (FeedStore) -> Unit,
        ) {
            val after = before.copy()
            mutate(after)
            val outcome = resolve(
                before = before,
                afterRooms = after.rooms.map { it.username },
                proposed = after.currentUsername,
                secondary = null,
                intent = intent,
            )
            assertEquals(after.currentUsername, outcome.primary.username, label)
            assertNull(outcome.secondary, label)
            assertEquals(LaneID.PRIMARY, outcome.focus, label)
        }

        val middle = makeFeed(listOf("a", "b", "c", "d"), current = "b")
        matches("屏蔽主路", LaneSelection.Intent.Filter, middle) { it.setBlocked(setOf("b")) }
        matches("屏蔽后一间仍留在主路", LaneSelection.Intent.Filter, middle) { it.setBlocked(setOf("c")) }

        val favorites = makeFeed(listOf("a", "b", "c"), current = "b", query = RoomQuery(only = setOf("a", "b", "c")))
        matches("取消主路收藏", LaneSelection.Intent.Filter, favorites) {
            it.setFilters(blocked = emptySet(), only = setOf("a", "c"))
        }

        val searchable = makeFeed(listOf("amy", "bob", "cara", "dave"), current = "bob")
        matches("提交本地搜索", LaneSelection.Intent.Filter, searchable) { it.setQuery(RoomQuery(text = "a")) }

        matches("整表重载", LaneSelection.Intent.Reload, middle) {
            it.replace(CatalogPage(rooms = listOf(room("x"), room("y")), total = 2, nextOffset = 2))
            it.setQuery(RoomQuery())
        }
        matches("全站搜索装入", LaneSelection.Intent.SearchInstall, middle) {
            it.replace(SiteSearch.page(listOf(room("s1"), room("s2")), blocked = emptySet()))
            it.setQuery(RoomQuery())
        }
        matches("全站搜索退出", LaneSelection.Intent.SearchExit, middle) {
            it.replace(CatalogPage(rooms = listOf(room("a"), room("b"), room("c")), total = 3, nextOffset = 3))
            it.setQuery(RoomQuery())
        }
        matches("重排", LaneSelection.Intent.Merge, middle) { it.reorderAfterCurrent { rooms -> rooms.reversed() } }
        matches("reveal", LaneSelection.Intent.Reveal("d"), middle) { it.reveal(room("d")) }
        matches("向下划", LaneSelection.Intent.Move(LaneID.PRIMARY, 1), middle) { it.step(1) }
        matches("向上划", LaneSelection.Intent.Move(LaneID.PRIMARY, -1), middle) { it.step(-1) }
        matches("划动为零", LaneSelection.Intent.Move(LaneID.PRIMARY, 0), middle) { it.step(0) }
        matches("划过尽头", LaneSelection.Intent.Move(LaneID.PRIMARY, 20), middle) { it.step(20) }

        val empty = FeedStore()
        matches("空列表续页", LaneSelection.Intent.BlankResume, empty) {
            it.append(CatalogPage(rooms = listOf(room("c"), room("d")), total = 2, nextOffset = 2))
        }

        val last = makeFeed(listOf("solo"), current = "solo")
        matches("屏蔽最后一间", LaneSelection.Intent.Filter, last) { it.setBlocked(setOf("solo")) }
    }

    /**
     * Swift 把这条放在同一文件的 `RoomTargetTests`。方法名保持不变，
     * 类放在这里，避免和 L0b 已有的 `RoomTargetTests` 方法名对不上。
     */
    @Test
    fun testRoomTargetMatchesLaneNameAndGeneration() {
        val target = RoomTarget(laneID = LaneID.SECONDARY, username = "Bob", generation = 4)
        assertEquals(LaneID.SECONDARY, target.laneID)
        assertEquals("Bob", target.username)
        assertEquals(4, target.generation)
        assertTrue(target.matches(LaneID.SECONDARY, "bob", 4))
        assertTrue(!target.matches(LaneID.SECONDARY, "bob", 5), "代次变了就放弃")
        assertTrue(!target.matches(LaneID.PRIMARY, "bob", 4), "不是这一路就放弃")
        assertTrue(!target.matches(LaneID.SECONDARY, "ann", 4), "房间换了就放弃")
        assertEquals(LaneID.PRIMARY, RoomTarget.primary(username = "a", generation = 1).laneID)
    }

    @Test
    fun testFilterRelaxRefillsEmptySecondary() {
        val before = LaneSelection.Snapshot(
            rooms = listOf("bob"),
            primary = LaneCursor(username = "bob", direction = 1),
            secondary = LaneCursor(username = null, direction = 1),
            focus = LaneID.PRIMARY,
        )
        val out = LaneSelection.resolve(
            before = before,
            afterRooms = listOf("alice", "alex", "bob", "alina"),
            proposedPrimary = "bob",
            intent = LaneSelection.Intent.Filter,
        )
        assertEquals("bob", out.primary.username)
        assertEquals("alina", out.secondary?.username, "沿副路方向（向后）取主路之后的一间")
    }

    @Test
    fun testMergeRefillsEmptySecondaryEvenWhenPrimaryIsLast() {
        val before = LaneSelection.Snapshot(
            rooms = listOf("bob"),
            primary = LaneCursor(username = "bob", direction = 1),
            secondary = LaneCursor(username = null, direction = 1),
            focus = LaneID.PRIMARY,
        )
        val out = LaneSelection.resolve(
            before = before,
            afterRooms = listOf("alice", "bob"),
            proposedPrimary = "bob",
            intent = LaneSelection.Intent.Merge,
        )
        assertEquals("alice", out.secondary?.username, "后面没有就取前面一间")
    }

    @Test
    fun testRefillNeverDuplicatesPrimary() {
        val before = LaneSelection.Snapshot(
            rooms = listOf("a"),
            primary = LaneCursor(username = "a", direction = 1),
            secondary = LaneCursor(username = null, direction = 1),
            focus = LaneID.PRIMARY,
        )
        val out = LaneSelection.resolve(
            before = before,
            afterRooms = listOf("a", "b", "c"),
            proposedPrimary = "a",
            intent = LaneSelection.Intent.Merge,
        )
        assertTrue(out.primary.username != out.secondary?.username)
        assertEquals("b", out.secondary?.username)
    }

    @Test
    fun testSecondaryBeforePrimaryMovingBackwardKeepsPrimaryWhenBlocked() {
        val before = LaneSelection.Snapshot(
            rooms = listOf("a", "b", "c", "d"),
            primary = LaneCursor(username = "b", direction = 1),
            secondary = LaneCursor(username = "a", direction = -1),
            focus = LaneID.PRIMARY,
        )
        val out = LaneSelection.resolve(
            before = before,
            afterRooms = listOf("b", "c", "d"),
            proposedPrimary = "b",
            intent = LaneSelection.Intent.Filter,
        )
        assertEquals("b", out.primary.username)
        assertEquals("c", out.secondary?.username)
    }

    @Test
    fun testSecondaryAfterPrimaryMovingForwardKeepsPrimaryWhenBlocked() {
        val before = LaneSelection.Snapshot(
            rooms = listOf("a", "b", "c", "d"),
            primary = LaneCursor(username = "c", direction = 1),
            secondary = LaneCursor(username = "b", direction = 1),
            focus = LaneID.PRIMARY,
        )
        val out = LaneSelection.resolve(
            before = before,
            afterRooms = listOf("a", "c", "d"),
            proposedPrimary = "c",
            intent = LaneSelection.Intent.Filter,
        )
        assertEquals("c", out.primary.username)
        assertEquals("d", out.secondary?.username)
    }

    @Test
    fun testMergePathExcludesPrimaryToo() {
        val before = LaneSelection.Snapshot(
            rooms = listOf("a", "b", "c", "d"),
            primary = LaneCursor(username = "b", direction = 1),
            secondary = LaneCursor(username = "a", direction = -1),
            focus = LaneID.PRIMARY,
        )
        val out = LaneSelection.resolve(
            before = before,
            afterRooms = listOf("b", "c", "d"),
            proposedPrimary = "b",
            intent = LaneSelection.Intent.Merge,
        )
        assertEquals("b", out.primary.username)
        assertEquals("c", out.secondary?.username)
    }

    private fun recentSnapshot(
        rooms: List<String> = listOf("a", "b", "c", "d", "e"),
        primary: String,
        secondary: String,
        recent: Set<String>,
        primaryPrevious: String? = null,
        secondaryPrevious: String? = null,
    ): LaneSelection.Snapshot = LaneSelection.Snapshot(
        rooms = rooms,
        primary = LaneCursor(username = primary, previous = primaryPrevious),
        secondary = LaneCursor(username = secondary, previous = secondaryPrevious),
        focus = LaneID.PRIMARY,
        recent = recent,
    )

    private fun moveRecent(before: LaneSelection.Snapshot, lane: LaneID, delta: Int): LaneSelection.Outcome =
        LaneSelection.resolve(
            before = before,
            afterRooms = before.rooms,
            proposedPrimary = before.primary.username,
            intent = LaneSelection.Intent.Move(lane, delta),
        )

    @Test
    fun testForwardSwipeSkipsRoomsRecentlyLeftByEitherLane() {
        val before = recentSnapshot(primary = "c", secondary = "a", recent = setOf("d"))
        val out = moveRecent(before, LaneID.PRIMARY, 1)
        assertEquals("e", out.primary.username, "d 刚被划走，向前跳过")
        assertEquals("a", out.secondary?.username)
    }

    @Test
    fun testForwardSwipeStillSkipsOtherLaneRoomAndRecent() {
        val before = recentSnapshot(primary = "a", secondary = "b", recent = setOf("c"))
        assertEquals("d", moveRecent(before, LaneID.PRIMARY, 1).primary.username)
        val sec = recentSnapshot(primary = "d", secondary = "a", recent = setOf("b", "c"))
        assertEquals("e", moveRecent(sec, LaneID.SECONDARY, 1).secondary?.username)
    }

    @Test
    fun testRecentMatchingIsCaseInsensitive() {
        val before = recentSnapshot(rooms = listOf("a", "b", "C", "d"), primary = "a", secondary = "d", recent = setOf("c"))
        assertEquals("b", moveRecent(before, LaneID.PRIMARY, 1).primary.username)
        val after = recentSnapshot(rooms = listOf("a", "B", "c", "d"), primary = "a", secondary = "d", recent = setOf("b", "c"))
        assertEquals("B", moveRecent(after, LaneID.PRIMARY, 1).primary.username)
    }

    @Test
    fun testBackSwipeToOwnPreviousRoomIsAllowed() {
        val before = recentSnapshot(primary = "a", secondary = "d", recent = setOf("c", "b"), secondaryPrevious = "c")
        assertEquals("c", moveRecent(before, LaneID.SECONDARY, -1).secondary?.username)
        val noPrevious = recentSnapshot(primary = "a", secondary = "d", recent = setOf("c"))
        assertEquals("b", moveRecent(noPrevious, LaneID.SECONDARY, -1).secondary?.username)
    }

    @Test
    fun testOwnPreviousRoomStillRefusedWhenTheOtherLaneIsOnIt() {
        val before = recentSnapshot(primary = "c", secondary = "d", recent = setOf("c"), secondaryPrevious = "c")
        val out = moveRecent(before, LaneID.SECONDARY, -1)
        assertNotEquals("c", out.secondary?.username)
    }

    @Test
    fun testCurrentRoomInRecentNeverLandsOnTheOtherLane() {
        val before = recentSnapshot(rooms = listOf("a", "b", "c"), primary = "a", secondary = "b", recent = setOf("b", "c"), secondaryPrevious = "c")
        val out = moveRecent(before, LaneID.SECONDARY, -1)
        assertEquals("b", out.secondary?.username)
        assertEquals("a", out.primary.username)
    }

    @Test
    fun testShortListRelaxesRecentBeforeStayingPut() {
        val before = recentSnapshot(rooms = listOf("a", "b", "c"), primary = "a", secondary = "b", recent = setOf("c"))
        val out = moveRecent(before, LaneID.PRIMARY, 1)
        assertEquals("c", out.primary.username)
        assertNotEquals(out.primary.username, out.secondary?.username)
    }

    @Test
    fun testShortListStaysPutWhenNothingElseExists() {
        val before = recentSnapshot(rooms = listOf("a", "b"), primary = "a", secondary = "b", recent = setOf("a", "b"))
        val out = moveRecent(before, LaneID.PRIMARY, 1)
        assertEquals("a", out.primary.username)
        assertEquals("b", out.secondary?.username)
    }

    @Test
    fun testRecentDoesNotAffectDirectReveal() {
        val before = recentSnapshot(primary = "a", secondary = "b", recent = setOf("d"))
        val out = LaneSelection.resolve(
            before = before,
            afterRooms = before.rooms,
            proposedPrimary = "d",
            intent = LaneSelection.Intent.Reveal("d"),
        )
        assertEquals("d", out.primary.username)
    }

    @Test
    fun testRecentSkipsExpireByReadTime() {
        val skips = RecentSkips()
        val t0 = Instant.ofEpochSecond(1_000_000)
        skips.record("Alice", t0)
        assertEquals(setOf("alice"), skips.active(t0.plusSeconds(30 * 60 - 1)))
        assertTrue(skips.active(t0.plusSeconds(30 * 60)).isEmpty(), "满 30 分钟恢复可选")
    }

    @Test
    fun testRecentSkipsRerecordRefreshesTimestamp() {
        val skips = RecentSkips()
        val t0 = Instant.ofEpochSecond(1_000_000)
        skips.record("a", t0)
        skips.record("A", t0.plusSeconds(20 * 60))
        assertEquals(1, skips.count)
        assertEquals(setOf("a"), skips.active(t0.plusSeconds(40 * 60)))
    }

    @Test
    fun testRecentSkipsCapacityDropsOldest() {
        val skips = RecentSkips()
        val t0 = Instant.ofEpochSecond(1_000_000)
        for (index in 0 until (RecentSkips.capacity + 5)) {
            skips.record("r$index", t0.plusSeconds(index.toLong()))
        }
        assertEquals(RecentSkips.capacity, skips.count)
        val active = skips.active(t0.plusSeconds(300))
        assertFalse(active.contains("r0"))
        assertTrue(active.contains("r${RecentSkips.capacity + 4}"))
    }

    @Test
    fun testSingleWindowIgnoresRecent() {
        val before = LaneSelection.Snapshot(
            rooms = listOf("a", "b", "c"),
            primary = LaneCursor(username = "a"),
            secondary = null,
            focus = LaneID.PRIMARY,
            recent = setOf("b"),
        )
        val out = LaneSelection.resolve(
            before = before,
            afterRooms = before.rooms,
            proposedPrimary = "b",
            intent = LaneSelection.Intent.Move(LaneID.PRIMARY, 1),
        )
        assertEquals("b", out.primary.username, "单窗不受 recent 约束")
        assertNull(out.secondary)
    }

    @Test
    fun testStillSingleRoomLeavesSecondaryEmpty() {
        val before = LaneSelection.Snapshot(
            rooms = listOf("bob"),
            primary = LaneCursor(username = "bob"),
            secondary = LaneCursor(username = null),
            focus = LaneID.PRIMARY,
        )
        val out = LaneSelection.resolve(
            before = before,
            afterRooms = listOf("bob"),
            proposedPrimary = "bob",
            intent = LaneSelection.Intent.Filter,
        )
        assertEquals("bob", out.primary.username)
        assertNull(out.secondary?.username)
    }
}
