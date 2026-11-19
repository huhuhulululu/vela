package vela.core.hls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MuteAudioTests {
    private val main = MuteAudioPolicy.AudibleOption(isDefault = false)
    private val preferred = MuteAudioPolicy.AudibleOption(isDefault = true)

    @Test
    fun testMutedWithEmptySelectionClearsOnlyTheCurrentOption() {
        val options = listOf(main, preferred)
        assertEquals(
            MuteAudioPolicy.AudibleChoice.Clear,
            MuteAudioPolicy.audibleChoice(muted = true, allowsEmptySelection = true, options = options, selectedIndex = 0),
        )
        assertEquals(
            MuteAudioPolicy.AudibleChoice.Clear,
            MuteAudioPolicy.audibleChoice(muted = true, allowsEmptySelection = true, options = options, selectedIndex = 1),
        )
        assertEquals(
            MuteAudioPolicy.AudibleChoice.Leave,
            MuteAudioPolicy.audibleChoice(muted = true, allowsEmptySelection = true, options = options, selectedIndex = null),
        )
    }

    /**
     * Swift 在不允许空选择时不 clear。安卓静音即关音轨，有选中项就清空，没有就不动。
     * 方法名留给 parity，语义见 hls.notes.md。
     */
    @Test
    fun testDisallowingEmptySelectionNeverClears() {
        val options = listOf(main, preferred)
        for (selected in listOf(0, 1)) {
            assertEquals(
                MuteAudioPolicy.AudibleChoice.Clear,
                MuteAudioPolicy.audibleChoice(
                    muted = true,
                    allowsEmptySelection = false,
                    options = options,
                    selectedIndex = selected,
                ),
                "静音关音轨，selected=$selected",
            )
        }
        assertEquals(
            MuteAudioPolicy.AudibleChoice.Leave,
            MuteAudioPolicy.audibleChoice(
                muted = true,
                allowsEmptySelection = false,
                options = options,
                selectedIndex = null,
            ),
        )
    }

    @Test
    fun testUnmutePicksTheDefaultOptionAndLeavesAnExistingChoice() {
        val options = listOf(main, preferred)
        assertEquals(
            MuteAudioPolicy.AudibleChoice.Pick(1),
            MuteAudioPolicy.audibleChoice(muted = false, allowsEmptySelection = true, options = options, selectedIndex = null),
            "取消静音选回默认音轨，而不是列表里的第一项",
        )
        assertEquals(
            MuteAudioPolicy.AudibleChoice.Pick(0),
            MuteAudioPolicy.audibleChoice(muted = false, allowsEmptySelection = false, options = listOf(main), selectedIndex = null),
            "没有默认项时选第一个",
        )
        assertEquals(
            MuteAudioPolicy.AudibleChoice.Leave,
            MuteAudioPolicy.audibleChoice(muted = false, allowsEmptySelection = true, options = options, selectedIndex = 0),
        )
    }

    @Test
    fun testNoAudibleOptionsLeavesSelectionAlone() {
        assertEquals(
            MuteAudioPolicy.AudibleChoice.Leave,
            MuteAudioPolicy.audibleChoice(muted = true, allowsEmptySelection = true, options = emptyList(), selectedIndex = null),
        )
        assertEquals(
            MuteAudioPolicy.AudibleChoice.Leave,
            MuteAudioPolicy.audibleChoice(muted = false, allowsEmptySelection = false, options = emptyList(), selectedIndex = 0),
        )
    }

    @Test
    fun testAudioFetchSuppression() {
        assertTrue(MuteAudioPolicy.shouldSuppressAudioFetch(muted = true, allowsEmptySelection = null))
        assertTrue(MuteAudioPolicy.shouldSuppressAudioFetch(muted = true, allowsEmptySelection = true))
        // 关音轨后不再因为「不允许空选择」继续刷音频清单。
        assertTrue(MuteAudioPolicy.shouldSuppressAudioFetch(muted = true, allowsEmptySelection = false))
        assertFalse(MuteAudioPolicy.shouldSuppressAudioFetch(muted = false, allowsEmptySelection = null))
        assertFalse(MuteAudioPolicy.shouldSuppressAudioFetch(muted = false, allowsEmptySelection = true))
        assertFalse(MuteAudioPolicy.shouldSuppressAudioFetch(muted = false, allowsEmptySelection = false))
    }

    @Test
    fun testRelayRefreshesAudioOnlyWhenPlaybackWantsIt() {
        assertFalse(
            MuteAudioPolicy.shouldRefreshAudioPlaylist(
                hasSeparateAudio = true,
                relayRefreshing = true,
                suppressAudioFetch = true,
            ),
        )
        assertTrue(
            MuteAudioPolicy.shouldRefreshAudioPlaylist(
                hasSeparateAudio = true,
                relayRefreshing = true,
                suppressAudioFetch = false,
            ),
        )
        assertFalse(
            MuteAudioPolicy.shouldRefreshAudioPlaylist(
                hasSeparateAudio = false,
                relayRefreshing = true,
                suppressAudioFetch = false,
            ),
        )
        assertFalse(
            MuteAudioPolicy.shouldRefreshAudioPlaylist(
                hasSeparateAudio = true,
                relayRefreshing = false,
                suppressAudioFetch = false,
            ),
        )
    }

    @Test
    fun testSeekLiveEdgeOnlyWhenReattachingWhilePlaying() {
        assertTrue(MuteAudioPolicy.shouldSeekLiveEdge(audioWasDetached = true, playing = true))
        assertFalse(MuteAudioPolicy.shouldSeekLiveEdge(audioWasDetached = true, playing = false))
        assertFalse(MuteAudioPolicy.shouldSeekLiveEdge(audioWasDetached = false, playing = true))
    }

    @Test
    fun testHeldMuteStillCountsAsAudibleForTheStartupLog() {
        val held = MuteAudioPolicy.AudibleGroupState(
            loaded = true,
            optionCount = 1,
            selectedIndex = null,
            allowsEmptySelection = true,
        )
        assertEquals(
            MuteAudioPolicy.AudioReport.Ok(enabled = 1, held = true),
            MuteAudioPolicy.audioReport(muted = true, isRecheck = false, enabledTrackCount = 0, group = held),
        )
        assertEquals(
            MuteAudioPolicy.AudioReport.Ok(enabled = 1, held = true),
            MuteAudioPolicy.audioReport(muted = true, isRecheck = true, enabledTrackCount = 0, group = held),
        )
        assertEquals(
            MuteAudioPolicy.AudioReport.Ok(enabled = 2, held = false),
            MuteAudioPolicy.audioReport(muted = true, isRecheck = false, enabledTrackCount = 2, group = held),
        )
    }

    @Test
    fun testStillSelectedOrUnknownGroupDoesNotPretendAudioIsHeld() {
        val stillSelected = MuteAudioPolicy.AudibleGroupState(
            loaded = true,
            optionCount = 1,
            selectedIndex = 0,
            allowsEmptySelection = true,
        )
        assertEquals(
            MuteAudioPolicy.AudioReport.Recheck,
            MuteAudioPolicy.audioReport(muted = true, isRecheck = false, enabledTrackCount = 0, group = stillSelected),
        )
        // 关音轨之后选中下标为空，即使播放器报「不允许空选择」也记为 held。
        val clearedDespiteDisallow = MuteAudioPolicy.AudibleGroupState(
            loaded = true,
            optionCount = 1,
            selectedIndex = null,
            allowsEmptySelection = false,
        )
        assertEquals(
            MuteAudioPolicy.AudioReport.Ok(enabled = 1, held = true),
            MuteAudioPolicy.audioReport(muted = true, isRecheck = false, enabledTrackCount = 0, group = clearedDespiteDisallow),
        )
        assertEquals(
            MuteAudioPolicy.AudioReport.Recheck,
            MuteAudioPolicy.audioReport(
                muted = true,
                isRecheck = false,
                enabledTrackCount = 0,
                group = MuteAudioPolicy.AudibleGroupState.unknown,
            ),
        )
        assertEquals(
            MuteAudioPolicy.AudioReport.Missing,
            MuteAudioPolicy.audioReport(
                muted = true,
                isRecheck = true,
                enabledTrackCount = 0,
                group = MuteAudioPolicy.AudibleGroupState.unknown,
            ),
        )
        val held = MuteAudioPolicy.AudibleGroupState(
            loaded = true,
            optionCount = 1,
            selectedIndex = null,
            allowsEmptySelection = true,
        )
        assertEquals(
            MuteAudioPolicy.AudioReport.Recheck,
            MuteAudioPolicy.audioReport(muted = false, isRecheck = false, enabledTrackCount = 0, group = held),
        )
        assertEquals(
            MuteAudioPolicy.AudioReport.Missing,
            MuteAudioPolicy.audioReport(muted = false, isRecheck = true, enabledTrackCount = 0, group = held),
        )
    }
}
