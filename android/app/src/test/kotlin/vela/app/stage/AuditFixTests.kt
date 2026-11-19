package vela.app.stage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.site.CatalogPage
import vela.core.hls.SkipTracker
import vela.core.stream.StreamFailure
import java.io.IOException

/**
 * 仅公开下落到实测已私密或离线的房间：不显示提示，立即沿划动方向跳走并从列表筛掉。
 * 对应 Tests/VelaPhoneTests/AuditFixTests.swift 的 SkipNonPublicTests。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuditFixTests {
    private val harness = StageHarness()
    private lateinit var settings: Settings
    private lateinit var directory: java.nio.file.Path
    private val clock = ManualClock()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context)
        directory = Files.createTempDirectory("vela-skip-nonpublic")
    }

    @After
    fun tearDown() {
        deleteTree(directory)
        harness.close()
    }

    @Test
    fun testPrivateRoomIsSkippedImmediatelyAndDelisted() = harness.onStage {
        val stage = makeStage()
        try {
            land(stage, StreamFailure.privateShow)
            assertEquals("c", stage.feed.currentUsername)
            assertEquals(listOf("a", "c"), stage.feed.rooms.map { it.username })
            assertNull(stage.state.value.lane.playbackNote)
            assertFalse(stage.skipJob?.isActive == true)
            assertEquals(1, stage.skips.count)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSkipFollowsSwipeDirectionBackwards() = harness.onStage {
        val stage = makeStage()
        try {
            stage.skips.noteManual(-1)
            land(stage, StreamFailure.offline)
            assertEquals("a", stage.feed.currentUsername)
            assertEquals(listOf("a", "c"), stage.feed.rooms.map { it.username })
        } finally {
            stage.close()
        }
    }

    @Test
    fun testEveryNonPublicStateIsDelisted() = harness.onStage {
        val failures = listOf(
            StreamFailure.privateShow,
            StreamFailure.group,
            StreamFailure.hidden,
            StreamFailure.password,
            StreamFailure.offline,
        )
        for (failure in failures) {
            val stage = makeStage()
            try {
                land(stage, failure)
                assertEquals(failure.toString(), listOf("a", "c"), stage.feed.rooms.map { it.username })
            } finally {
                stage.close()
            }
        }
    }

    @Test
    fun testStaysWithReasonAtEndOfListAndIsNotDelisted() = harness.onStage {
        val stage = makeStage(at = "c")
        try {
            land(stage, StreamFailure.privateShow, "c")
            assertEquals("c", stage.feed.currentUsername)
            assertEquals(3, stage.feed.rooms.size)
            assertEquals("私密表演，已到列表尽头", stage.state.value.lane.playbackNote)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testAutoSkipLimitStillApplies() = harness.onStage {
        val stage = makeStage()
        try {
            repeat(SkipTracker.limit) { assertTrue(stage.skips.registerAuto()) }
            land(stage, StreamFailure.privateShow)
            assertEquals("b", stage.feed.currentUsername)
            assertEquals(3, stage.feed.rooms.size)
            assertEquals("连续几个房间都无法播放", stage.state.value.lane.playbackNote)
        } finally {
            stage.close()
        }
    }

    @Test
    fun testPublicOnlyOffKeepsNoticeAndList() = harness.onStage {
        val stage = makeStage(publicOnly = false)
        try {
            stage.skipDelayMillis = 60_000
            land(stage, StreamFailure.privateShow)
            assertEquals("b", stage.feed.currentUsername)
            assertEquals(3, stage.feed.rooms.size)
            assertEquals("私密表演", stage.state.value.lane.playbackNote)
            assertTrue(stage.skipJob?.isActive == true)
        } finally {
            stage.skipJob?.cancel()
            stage.close()
        }
    }

    @Test
    fun testAwayKeepsRetryBehaviourAndIsNotDelisted() = harness.onStage {
        val stage = makeStage()
        try {
            land(stage, StreamFailure.away)
            assertEquals("b", stage.feed.currentUsername)
            assertEquals(3, stage.feed.rooms.size)
            assertFalse(stage.skipJob?.isActive == true)
            assertTrue(stage.awayJob?.isActive == true)
        } finally {
            stage.awayJob?.cancel()
            stage.close()
        }
    }

    @Test
    fun testUnavailableIsNotDelisted() = harness.onStage {
        val stage = makeStage()
        try {
            stage.skipDelayMillis = 60_000
            land(stage, StreamFailure.unavailable)
            assertEquals(3, stage.feed.rooms.size)
            assertEquals("暂时无法播放", stage.state.value.lane.playbackNote)
            assertTrue(stage.skipJob?.isActive == true)
        } finally {
            stage.skipJob?.cancel()
            stage.close()
        }
    }

    @Test
    fun testUnknownStatusKeepsNoticeThenSkipsWithoutDelisting() = harness.onStage {
        val stage = makeStage()
        try {
            stage.skipDelayMillis = 60_000
            stage.noteUnplayable(StreamFailure.offline, delist = null, username = "b", token = stage.laneFace.generation)
            assertEquals("b", stage.feed.currentUsername)
            assertEquals(listOf("a", "b", "c"), stage.feed.rooms.map { it.username })
            assertEquals("已离线", stage.state.value.lane.playbackNote)
            assertTrue(stage.skipJob?.isActive == true)
        } finally {
            stage.skipJob?.cancel()
            stage.close()
        }
    }

    @Test
    fun testPrefetchDelistsConfirmedNonPublicAndKeepsPlayingRoom() = harness.onStage {
        val stage = makeStage(at = "a")
        val http = scriptHttp { request ->
            when {
                request.url.contains("/b/") || request.url.contains("broadcaster") ->
                    ok("""{"room_status":"private","broadcaster_username":"b"}""")
                request.url.contains("chatvideocontext") ->
                    ok("""{"room_status":"private","broadcaster_username":"b"}""")
                else -> error("意外请求 ${request.url}")
            }
        }
        val checking = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        try {
            checking.ageConfirmed = true
            checking.updateFeed { store ->
                store.replace(pageOf("a", "b", "c"))
                store.select("a")
            }
            checking.prefetchAhead(checking.laneFace.generation)
            assertEquals("a", checking.feed.currentUsername)
            assertEquals(listOf("a", "c"), checking.feed.rooms.map { it.username })
            assertEquals(listOf("c"), checking.feed.prefetchUsernames())
        } finally {
            checking.close()
            stage.close()
        }
    }

    @Test
    fun testPrefetchNetworkErrorDoesNotDelist() = harness.onStage {
        val http = scriptHttp { request ->
            if (request.url.contains("chatvideocontext")) throw IOException("offline")
            error("意外请求 ${request.url}")
        }
        val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        try {
            stage.ageConfirmed = true
            stage.updateFeed { store ->
                store.replace(pageOf("a", "b", "c"))
                store.select("a")
            }
            stage.prefetchAhead(stage.laneFace.generation)
            assertEquals(listOf("a", "b", "c"), stage.feed.rooms.map { it.username })
        } finally {
            stage.close()
        }
    }

    private fun makeStage(publicOnly: Boolean = true, at: String = "b"): StageViewModel {
        settings.publicOnly = publicOnly
        val http = object : HTTPClient {
            override suspend fun send(request: HTTPRequest): HTTPResult = error("不该访问网络 ${request.url}")
        }
        val stage = StageViewModel(settings, http, directory, clock, harness.dispatcher)
        stage.ageConfirmed = true
        stage.skipDelayMillis = 60_000
        stage.updateFeed { store ->
            store.setQuery(store.query.copy(publicOnly = publicOnly))
            store.replace(pageOf("a", "b", "c"))
            store.select(at)
        }
        return stage
    }

    private fun land(stage: StageViewModel, failure: StreamFailure?, name: String = "b") {
        val confirmed: ShowState? = when (failure) {
            StreamFailure.privateShow -> ShowState.privateShow
            StreamFailure.group -> ShowState.group
            StreamFailure.hidden -> ShowState.hidden
            StreamFailure.password -> ShowState.password
            StreamFailure.offline -> ShowState.offline
            else -> null
        }
        stage.noteUnplayable(failure, confirmed, name, stage.laneFace.generation)
    }

    private fun pageOf(vararg names: String): CatalogPage {
        val rooms = names.map { Room(username = it, viewers = 10, show = ShowState.publicShow) }
        return CatalogPage(rooms = rooms, total = rooms.size, nextOffset = rooms.size)
    }
}
