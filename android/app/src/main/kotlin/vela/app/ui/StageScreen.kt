package vela.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import vela.app.R
import vela.app.lane.DisplayCheck
import vela.app.stage.LanePlayback
import vela.app.stage.StageContract
import vela.app.stage.StageIntent
import vela.app.stage.StageSurface
import vela.app.stage.StageUiState
import vela.app.ui.agegate.AgeGateScreen
import vela.app.ui.theme.ChampagneButton
import vela.app.ui.theme.Theme
import vela.core.base.model.BroadcastClock
import vela.core.base.model.Room
import vela.core.base.model.ViewerCount
import vela.core.feed.InfoVisibility
import androidx.compose.runtime.collectAsState

/**
 * 单窗。垂直方向只摆 [StageUiState.pagerRooms]（当前页和上下各一页），
 * [player] 只在当前页调用，邻页只有深色渐变垫底，不挂播放器。
 * 画面比例由播放器那一层的 [vela.app.lane.LaneSurface] 按原始比例摆，这里不裁切、不拉伸。
 */
@Composable
fun StageScreen(
    stage: StageContract,
    modifier: Modifier = Modifier,
    challengeUrl: String? = null,
    onImportFavorites: (() -> Unit)? = null,
    player: @Composable (room: Room) -> Unit = {},
) {
    val state by stage.state.collectAsState()
    val dispatch = stage::dispatch
    Box(modifier.fillMaxSize().background(Theme.ink)) {
        if (!state.ageConfirmed) {
            AgeGateScreen(
                onEnter = { dispatch(StageIntent.ConfirmAge) },
                onQuit = { dispatch(StageIntent.Quit) },
            )
        } else {
            StageBody(state, dispatch, challengeUrl, onImportFavorites, player)
        }
    }
}

@Composable
private fun StageBody(
    state: StageUiState,
    dispatch: (StageIntent) -> Unit,
    challengeUrl: String?,
    onImportFavorites: (() -> Unit)?,
    player: @Composable (Room) -> Unit,
) {
    var volumeOpen by remember { mutableStateOf(false) }
    if (state.surface != StageSurface.None && volumeOpen) volumeOpen = false
    Box(Modifier.fillMaxSize()) {
        if (state.rooms.isEmpty()) {
            StatusPane(state, dispatch, Modifier.fillMaxSize())
        } else {
            RoomPager(
                state = state,
                dispatch = dispatch,
                onStep = { dispatch(StageIntent.Step(it)) },
                onTap = { dispatch(StageIntent.NoteActivity) },
                player = player,
            )
            if (state.chromeShown && state.current != null) {
                ActionRail(state, dispatch, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
            }
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            if (state.chromeShown) {
                ChromeBar(
                    state = state,
                    dispatch = dispatch,
                    volumeOpen = volumeOpen,
                    onVolumeOpen = { volumeOpen = it },
                )
            }
            val keyword = state.siteSearch
            if (keyword != null) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text = stringResource(R.string.ui_site_search, keyword),
                        style = TextStyle(color = Theme.textPrimary, fontSize = 13.sp),
                        modifier = Modifier.weight(1f),
                    )
                    TextAction(
                        text = stringResource(R.string.ui_exit_site_search),
                        onClick = { dispatch(StageIntent.SetSearchText("")) },
                        modifier = Modifier.testTag("exit-site-search"),
                    )
                }
            }
        }
        Notices(state, dispatch)
        when (state.surface) {
            StageSurface.Filters -> FilterSheet(state, dispatch, onDismiss = { dispatch(StageIntent.DismissSurfaces) })
            StageSurface.Curation -> FavoritesSheet(
                state = state,
                dispatch = dispatch,
                onDismiss = { dispatch(StageIntent.DismissSurfaces) },
                onImport = onImportFavorites,
            )
            StageSurface.Taste -> TasteSheet(state, dispatch, onDismiss = { dispatch(StageIntent.DismissSurfaces) })
            StageSurface.Challenge -> ChallengeSheet(
                onDone = { dispatch(StageIntent.RetryAfterChallenge) },
                onClose = { dispatch(StageIntent.DismissChallenge) },
                pageUrl = challengeUrl,
            )
            StageSurface.None -> Unit
        }
    }
}

@Composable
private fun RoomPager(
    state: StageUiState,
    dispatch: (StageIntent) -> Unit,
    onStep: (Int) -> Unit,
    onTap: () -> Unit,
    player: @Composable (Room) -> Unit,
) {
    val step = rememberUpdatedState(onStep)
    val tap = rememberUpdatedState(onTap)
    var dragY by remember { mutableFloatStateOf(0f) }
    var heightPx by remember { mutableIntStateOf(0) }
    val pages = state.pagerRooms()
    val current = state.currentUsername
    val index = state.index
    val pagerLabel = stringResource(R.string.ui_hint_swipe)
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height }
            .semantics { contentDescription = pagerLabel }
            .testTag("pager")
            .pointerInput(Unit) {
                val threshold = 48.dp.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var total = 0f
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (dragging) {
                                val delta = when {
                                    total <= -threshold -> 1
                                    total >= threshold -> -1
                                    else -> 0
                                }
                                dragY = 0f
                                if (delta != 0) step.value(delta)
                            } else if (abs(total) <= slop) {
                                tap.value()
                            }
                            break
                        }
                        total += change.position.y - change.previousPosition.y
                        if (!dragging && abs(total) > slop) dragging = true
                        if (dragging) {
                            change.consume()
                            val limit = heightPx.toFloat().coerceAtLeast(1f)
                            dragY = total.coerceIn(-limit, limit)
                        }
                    }
                }
            },
    ) {
        pages.forEach { room ->
            val at = state.rooms.indexOfFirst { it.username == room.username }
            val slot = when {
                room.username == current -> 0
                at >= 0 && at < index -> -1
                else -> 1
            }
            key(room.username) {
                RoomPage(
                    room = room,
                    state = state,
                    active = room.username == current,
                    dispatch = dispatch,
                    player = player,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(0, slot * heightPx + dragY.roundToInt()) },
                )
            }
        }
    }
}

@Composable
private fun RoomPage(
    room: Room,
    state: StageUiState,
    active: Boolean,
    dispatch: (StageIntent) -> Unit,
    player: @Composable (Room) -> Unit,
    modifier: Modifier = Modifier,
) {
    val videoReady = active && state.lane.readyUsername == room.username
    val infoVisible = InfoVisibility.visible(
        isCurrent = active,
        alwaysShow = state.infoAlwaysOn,
        chromeOn = state.chromeShown,
    )
    Box(modifier.testTag("room:${room.username}").background(brush())) {
        if (active) {
            Box(Modifier.fillMaxSize().testTag("player-slot")) {
                player(room)
            }
        }
        if (infoVisible) {
            Caption(
                room = room,
                reason = state.reasonFor(room),
                live = active && state.lane.playingUsername == room.username && infoVisible,
                modifier = Modifier.align(Alignment.BottomStart).testTag("caption:${room.username}"),
            )
        }
        if (active && state.lane.manuallyPaused) {
            PauseMark(Modifier.align(Alignment.Center))
        } else if (active) {
            val note = failureNote(state, videoReady)
            if (note != null) {
                FailureMark(note, state, dispatch, Modifier.align(Alignment.Center))
            } else if (!videoReady && state.playbackAllowed) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .testTag("loading")
                        .fillMaxWidth(0.2f)
                        .fillMaxHeight(0.004f)
                        .background(Theme.champagne, RoundedCornerShape(percent = 50)),
                )
            }
        }
    }
}

@Composable
private fun brush(): Brush = Brush.verticalGradient(
    listOf(Color(DisplayCheck.FALLBACK_TOP), Color(DisplayCheck.FALLBACK_BOTTOM)),
)

@Composable
private fun failureNote(state: StageUiState, videoReady: Boolean): String? {
    val lane = state.lane
    if (lane.manuallyPaused) return null
    if (lane.playback == LanePlayback.Failed) {
        return lane.playbackNote ?: stringResource(R.string.stage_playback_interrupted)
    }
    if (!videoReady && lane.playbackNote != null) return lane.playbackNote
    return null
}

@Composable
private fun Caption(room: Room, reason: String?, live: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 22.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .testTag(if (live) "pulse-live" else "pulse-still")
                    .background(Theme.live, RoundedCornerShape(percent = 50))
                    .padding(3.5.dp),
            )
            BasicText(stringResource(R.string.ui_live), style = TextStyle(color = Theme.textPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp))
            BasicText(ViewerCount.string(room.viewers), style = TextStyle(color = Theme.textPrimary, fontSize = 12.sp))
            if (room.isHD) {
                BasicText(
                    stringResource(R.string.ui_hd),
                    style = TextStyle(color = Theme.textPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(percent = 50))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        BasicText(room.displayName, style = TextStyle(color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold))
        if (room.subject.isNotEmpty()) {
            BasicText(room.subject, style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp))
        }
        val meta = roomMeta(room)
        if (meta.isNotEmpty()) {
            BasicText(meta, style = TextStyle(color = Theme.textSecondary, fontSize = 12.sp))
        }
        if (reason != null) {
            BasicText(reason, style = TextStyle(color = Theme.champagne, fontSize = 12.sp, fontWeight = FontWeight.Medium))
        }
        if (room.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                room.tags.take(4).forEach { tag ->
                    BasicText(
                        tag,
                        style = TextStyle(color = Color.White.copy(alpha = 0.88f), fontSize = 11.sp),
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(percent = 50))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun roomMeta(room: Room): String {
    val parts = ArrayList<String>(4)
    room.gender?.let { parts.add(genderLabel(it)) }
    room.age?.let { parts.add(it.toString()) }
    BroadcastClock.string(room.secondsOnline)?.let { parts.add(it) }
    if (room.location.isNotEmpty()) parts.add(room.location)
    return parts.joinToString("  ·  ")
}

@Composable
private fun PauseMark(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.ui_paused)
    Box(
        modifier
            .testTag("paused-mark")
            .semantics { contentDescription = label }
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(percent = 50))
            .padding(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun FailureMark(
    note: String,
    state: StageUiState,
    dispatch: (StageIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(
            note,
            style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        ChampagneButton(
            text = stringResource(R.string.ui_retry),
            onClick = { dispatch(StageIntent.RetryPlayback(state.primaryTarget())) },
            modifier = Modifier.testTag("retry-playback"),
        )
    }
}

@Composable
private fun ActionRail(state: StageUiState, dispatch: (StageIntent) -> Unit, modifier: Modifier = Modifier) {
    val paused = state.lane.manuallyPaused
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        RailButton(
            text = stringResource(if (paused) R.string.ui_resume else R.string.ui_pause),
            tag = "pause-room",
        ) {
            dispatch(StageIntent.TogglePause(state.primaryTarget()))
        }
        RailButton(stringResource(R.string.ui_not_interested), "not-interested") {
            dispatch(StageIntent.MarkNotInterested(state.primaryTarget()))
        }
        RailButton(stringResource(R.string.ui_block), "block-room") {
            dispatch(StageIntent.BlockCurrent(state.primaryTarget()))
        }
    }
}

@Composable
private fun RailButton(text: String, tag: String, onClick: () -> Unit) {
    TextAction(text, onClick, Modifier.testTag(tag))
}

@Composable
private fun StatusPane(state: StageUiState, dispatch: (StageIntent) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f))
        BasicText(
            "VELA",
            style = TextStyle(color = Theme.champagne, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 6.sp),
        )
        if (state.loading) {
            Box(
                Modifier
                    .testTag("loading")
                    .fillMaxWidth(0.16f)
                    .padding(vertical = 8.dp)
                    .background(Theme.champagne, RoundedCornerShape(percent = 50))
                    .padding(vertical = 1.dp),
            )
            BasicText(stringResource(R.string.ui_loading_rooms), style = TextStyle(color = Theme.textSecondary, fontSize = 13.sp))
        } else {
            BasicText(
                state.emptyMessage(),
                style = TextStyle(color = Theme.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.source == vela.core.base.model.FeedSource.favorites) {
                    ChampagneButton(
                        text = stringResource(R.string.ui_open_favorites),
                        onClick = { dispatch(StageIntent.ShowSurface(StageSurface.Curation)) },
                        modifier = Modifier.testTag("empty-open-curation"),
                    )
                }
                if (state.source != vela.core.base.model.FeedSource.favorites || state.curation.favorites.isNotEmpty()) {
                    ChampagneButton(
                        text = stringResource(R.string.ui_retry),
                        onClick = { dispatch(StageIntent.Retry) },
                        modifier = Modifier.testTag("empty-retry"),
                    )
                }
            }
        }
        Box(Modifier.weight(1f))
    }
}

@Composable
private fun Notices(state: StageUiState, dispatch: (StageIntent) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 64.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val suggest = state.favoriteSuggest
            if (suggest != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        text = stringResource(R.string.ui_suggest, suggest.displayName),
                        style = TextStyle(color = Theme.textPrimary, fontSize = 13.sp),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextAction(
                        text = stringResource(R.string.ui_suggest_accept),
                        onClick = { dispatch(StageIntent.AcceptFavoriteSuggest) },
                        modifier = Modifier.testTag("accept-suggest"),
                    )
                    val closeSuggest = stringResource(R.string.ui_close_suggest)
                    TextAction(
                        text = stringResource(R.string.ui_close),
                        onClick = { dispatch(StageIntent.CloseFavoriteSuggest) },
                        modifier = Modifier
                            .testTag("close-suggest")
                            .semantics { contentDescription = closeSuggest },
                        color = Theme.textSecondary,
                    )
                }
            }
            val toast = state.toast
            if (toast != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(toast, style = TextStyle(color = Theme.textPrimary, fontSize = 13.sp), modifier = Modifier.weight(1f, fill = false))
                    val undo = state.toastUndoName
                    if (undo != null && state.curation.canRestoreFavorite(undo)) {
                        TextAction(
                            text = stringResource(R.string.ui_undo),
                            onClick = { dispatch(StageIntent.UndoToast) },
                            modifier = Modifier.testTag("toast-undo"),
                        )
                    }
                }
            }
        }
        if (state.hintVisible) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 28.dp)
                    .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(stringResource(R.string.ui_hint_swipe), style = TextStyle(color = Theme.textPrimary, fontSize = 12.sp))
                    BasicText(stringResource(R.string.ui_hint_heart), style = TextStyle(color = Theme.textPrimary, fontSize = 12.sp))
                    BasicText(stringResource(R.string.ui_hint_panels), style = TextStyle(color = Theme.textPrimary, fontSize = 12.sp))
                }
                val closeHint = stringResource(R.string.ui_close_hint)
                TextAction(
                    text = stringResource(R.string.ui_close),
                    onClick = { dispatch(StageIntent.DismissHint) },
                    modifier = Modifier
                        .testTag("close-hint")
                        .semantics { contentDescription = closeHint },
                    color = Theme.textSecondary,
                )
            }
        }
    }
}

/** 底部面板的底。点外面只收普通面板；质询页的遮罩不收。 */
@Composable
internal fun SheetFrame(
    dismissOnScrim: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = { if (dismissOnScrim) onDismiss() },
                )
                .testTag("sheet-scrim"),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .background(Theme.ink)
                .pointerInput(Unit) {
                    // 空白处自己接住，不穿透到遮罩。不用 clickable，避免把子孙语义合并掉。
                    // 命中先落到按钮和滚动区，这里只接到没有子节点接住的按下。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.all { !it.pressed }) break
                        }
                    }
                },
        ) {
            content()
        }
    }
}
