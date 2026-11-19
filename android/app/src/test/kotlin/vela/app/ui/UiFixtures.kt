package vela.app.ui

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import vela.app.stage.FakeStage
import vela.app.stage.LaneFace
import vela.app.stage.StageUiState
import vela.app.stage.TastePanelState
import vela.app.stage.TasteRowState
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.curation.Curation

internal fun testRoom(
    name: String,
    viewers: Int = 12,
    tags: List<String> = listOf("tag-$name"),
    gender: Gender? = Gender.female,
): Room = Room(
    username = name,
    displayName = name,
    subject = "$name 的标题",
    viewers = viewers,
    gender = gender,
    tags = tags,
    isHD = true,
    show = ShowState.publicShow,
    location = "测试城",
    age = 24,
    secondsOnline = 120,
)

internal fun testStage(state: StageUiState): FakeStage {
    val stage = FakeStage()
    stage.seed(state)
    return stage
}

internal fun pagedState(
    names: List<String>,
    index: Int,
    chromeVisible: Boolean = true,
    block: StageUiState.() -> StageUiState = { this },
): StageUiState {
    val rooms = names.map { testRoom(it) }
    val current = rooms.getOrNull(index)?.username
    return StageUiState(
        ageConfirmed = true,
        catalog = rooms,
        rooms = rooms,
        currentUsername = current,
        source = FeedSource.guess,
        loadedSource = FeedSource.guess,
        chromeVisible = chromeVisible,
        lane = LaneFace(username = current, generation = 3),
    ).block()
}

internal fun tasteRow(key: String, name: String, mean: Double = 0.7, hits: Int = 2, vetoed: Boolean = false) =
    TasteRowState(key = key, name = name, mean = mean, hits = hits, vetoed = vetoed)

internal fun withTaste(liked: List<TasteRowState>, suggestions: List<String> = emptyList(), paused: Boolean = false) =
    TastePanelState(liked = liked, suggestions = suggestions, paused = paused, tagCount = liked.size)

internal fun withFavorite(name: String): Curation = Curation(favorites = listOf(name))

internal fun SemanticsNodeInteractionsProvider.requireTag(tag: String) {
    assertTrue("missing $tag", onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty())
}

internal fun SemanticsNodeInteractionsProvider.requireNoTag(tag: String) {
    assertEquals("unexpected $tag", 0, onAllNodesWithTag(tag).fetchSemanticsNodes().size)
}

internal fun SemanticsNodeInteractionsProvider.requireNoText(text: String) {
    assertEquals("unexpected text $text", 0, onAllNodesWithText(text).fetchSemanticsNodes().size)
}
