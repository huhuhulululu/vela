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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalDensity
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
import vela.app.stage.LaneFace
import vela.app.stage.LanePlayback
import vela.app.stage.StageContract
import vela.app.stage.StageIntent
import vela.app.stage.StageSurface
import vela.app.stage.StageUiState
import vela.app.sync.SyncHub
import vela.app.ui.agegate.AgeGateScreen
import vela.app.ui.theme.ChampagneButton
import vela.app.ui.theme.Theme
import vela.core.base.lane.LaneID
import vela.core.base.model.BroadcastClock
import vela.core.base.model.Room
import vela.core.base.model.ViewerCount
import vela.core.feed.InfoVisibility

/**
 * 舞台主界面。竖屏多路时交给 [DualStageScreen]；横屏或多路关闭时只显示焦点路单窗。
 * 垂直分页只摆当前页和上下各一页，播放器只挂在当前页。
 * 画面比例由 [vela.app.lane.LaneSurface] 按原始比例摆，这里不裁切、不拉伸。
 */
@Composable
fun StageScreen(
    stage: StageContract,
    modifier: Modifier = Modifier,
    challengeUrl: String? = null,
    onImportFavorites: (() -> Unit)? = null,
    syncHub: SyncHub? = null,
    onRequestSyncReveal: ((onGranted: () -> Unit) -> Unit)? = null,
    player: @Composable (LaneID, Room) -> Unit = { _, _ -> },
) {
    val state by stage.state.collectAsState()
    val dispatch = stage::dispatch
    val density = LocalDensity.current
    val inPip = rememberUpdatedState(state.pictureInPicture)
    Box(
        modifier
            .fillMaxSize()
            .background(Theme.ink)
            .onSizeChanged { size ->
                if (inPip.value) return@onSizeChanged
                val landscape = size.width > size.height
                dispatch(StageIntent.SetLandscape(landscape))
                if (!landscape && size.width > 0 && size.height > 0) {
                    val width = with(density) { size.width.toDp().value.toDouble() }
                    val height = with(density) { size.height.toDp().value.toDouble() }
                    dispatch(StageIntent.NotePortraitStageSize(width, height))
                }
            },
    ) {
        if (!state.ageConfirmed) {
            AgeGateScreen(
                onEnter = { dispatch(StageIntent.ConfirmAge) },
                onQuit = { dispatch(StageIntent.Quit) },
            )
        } else {
            StageBody(state, dispatch, challengeUrl, onImportFavorites, syncHub, onRequestSyncReveal, player)
        }
    }
}

@Composable
private fun StageBody(
    state: StageUiState,
    dispatch: (StageIntent) -> Unit,
    challengeUrl: String?,
    onImportFavorites: (() -> Unit)?,
    syncHub: SyncHub?,
    onRequestSyncReveal: ((onGranted: () -> Unit) -> Unit)?,
    player: @Composable (LaneID, Room) -> Unit,
) {
    if (state.pictureInPicture) {
        val focus = if (state.dualWindow) state.focusLane else LaneID.PRIMARY
        val room = state.focusRoom ?: state.current
        Box(Modifier.fillMaxSize().testTag("pip-stage")) {
            if (room != null) player(focus, room)
        }
        return
    }
    var volumeOpen by remember { mutableStateOf(false) }
    if (state.surface != StageSurface.None && volumeOpen) volumeOpen = false
    val dualPortrait = state.dualWindow && !state.landscape
    Box(Modifier.fillMaxSize()) {
        if (state.rooms.isEmpty()) {
            StatusPane(state, dispatch, Modifier.fillMaxSize())
        } else if (dualPortrait) {
            DualStageScreen(state = state, dispatch = dispatch, player = player)
        } else {
            val focus = if (state.dualWindow) state.focusLane else LaneID.PRIMARY
            val face = state.face(focus) ?: state.lane
            LaneRoomPager(
                state = state,
                laneId = focus,
                face = face,
                dispatch = dispatch,
                onStep = { dispatch(StageIntent.Step(it, focus)) },
                onTap = { dispatch(StageIntent.NoteActivity) },
                player = { room -> player(focus, room) },
                pagerTag = "pager",
            )
            if (state.chromeShown && state.focusRoom != null) {
                ActionRail(state, dispatch, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
            }
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            if (state.chromeShown) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    ChromeBar(
                        state = state,
                        dispatch = dispatch,
                        volumeOpen = volumeOpen,
                        onVolumeOpen = { volumeOpen = it },
                        modifier = Modifier.weight(1f),
                    )
                    LaneCountPicker(
                        state = state,
                        dispatch = dispatch,
                        modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                    )
                }
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
            StageSurface.Taste -> TasteSheet(
                state = state,
                dispatch = dispatch,
                onDismiss = { dispatch(StageIntent.DismissSurfaces) },
                syncHub = syncHub,
                onRequestSyncReveal = onRequestSyncReveal,
            )
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
internal fun LaneRoomPager(
    state: StageUiState,
    laneId: LaneID,
    face: LaneFace,
    dispatch: (StageIntent) -> Unit,
    onStep: (Int) -> Unit,
    onTap: () -> Unit,
    player: @Composable (Room) -> Unit,
    modifier: Modifier = Modifier,
    pagerTag: String = "pager:${laneId.label}",
) {
    val step = rememberUpdatedState(onStep)
    val tap = rememberUpdatedState(onTap)
    var dragY by remember(laneId) { mutableFloatStateOf(0f) }
    var heightPx by remember(laneId) { mutableIntStateOf(0) }
    val pages = state.lanePagerRooms(laneId)
    val current = face.username
    val index = state.indexOfLane(laneId)
    val pagerLabel = stringResource(R.string.ui_hint_swipe)
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height }
            .semantics { contentDescription = pagerLabel }
            .testTag(pagerTag)
            .pointerInput(laneId) {
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
                room.username.equals(current, ignoreCase = true) -> 0
                at >= 0 && at < index -> -1
                else -> 1
            }
            key(laneId, room.username) {
                RoomPage(
                    room = room,
                    state = state,
                    face = face,
                    laneId = laneId,
                    active = room.username.equals(current, ignoreCase = true),
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
    face: LaneFace,
    laneId: LaneID,
    active: Boolean,
    dispatch: (StageIntent) -> Unit,
    player: @Composable (Room) -> Unit,
    modifier: Modifier = Modifier,
) {
    val videoReady = active && face.readyUsername.equals(room.username, ignoreCase = true)
    val infoVisible = InfoVisibility.visible(
        isCurrent = active,
        alwaysShow = state.infoAlwaysOn,
        chromeOn = state.chromeShown,
    )
    val paneChrome = state.dualWindow && !state.landscape
    Box(modifier.testTag("room:${room.username}").background(brush())) {
        if (active) {
            Box(Modifier.fillMaxSize().testTag("player-slot")) {
                player(room)
            }
        }
        if (infoVisible && !paneChrome) {
            Caption(
                room = room,
                reason = state.reasonFor(room),
                live = active && face.playingUsername.equals(room.username, ignoreCase = true) && infoVisible,
                modifier = Modifier.align(Alignment.BottomStart).testTag("caption:${room.username}"),
            )
        }
        if (active && face.manuallyPaused) {
            PauseMark(Modifier.align(Alignment.Center))
        } else if (active) {
            val note = failureNote(face, videoReady)
            if (note != null) {
                FailureMark(note, laneId, face, dispatch, Modifier.align(Alignment.Center))
            } else if (!videoReady && state.lanePlaybackAllowed(laneId)) {
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
private fun failureNote(face: LaneFace, videoReady: Boolean): String? {
    if (face.manuallyPaused) return null
    if (face.playback == LanePlayback.Degraded) {
        return face.playbackNote ?: vela.app.stage.StageRules.DUAL_UNAVAILABLE
    }
    if (face.playback == LanePlayback.Failed) {
        return face.playbackNote ?: stringResource(R.string.stage_playback_interrupted)
    }
    if (!videoReady && face.playbackNote != null) return face.playbackNote
    return null
}

@Composable
internal fun Caption(room: Room, reason: String?, live: Boolean, modifier: Modifier = Modifier) {
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
    laneId: LaneID,
    face: LaneFace,
    dispatch: (StageIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = RoomTargetFor(laneId, face)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(
            note,
            style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        if (face.playback != LanePlayback.Degraded) {
            ChampagneButton(
                text = stringResource(R.string.ui_retry),
                onClick = { dispatch(StageIntent.RetryPlayback(target)) },
                modifier = Modifier.testTag("retry-playback"),
            )
        }
    }
}

private fun RoomTargetFor(laneId: LaneID, face: LaneFace): vela.core.base.lane.RoomTarget? {
    val name = face.username ?: return null
    return vela.core.base.lane.RoomTarget(laneId, name, face.generation)
}

@Composable
private fun ActionRail(state: StageUiState, dispatch: (StageIntent) -> Unit, modifier: Modifier = Modifier) {
    val focus = if (state.dualWindow) state.focusLane else LaneID.PRIMARY
    val paused = (state.face(focus) ?: state.lane).manuallyPaused
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        RailButton(
            text = stringResource(if (paused) R.string.ui_resume else R.string.ui_pause),
            tag = "pause-room",
        ) {
            dispatch(StageIntent.TogglePause(state.focusTarget()))
        }
        RailButton(stringResource(R.string.ui_not_interested), "not-interested") {
            dispatch(StageIntent.MarkNotInterested(state.focusTarget()))
        }
        RailButton(stringResource(R.string.ui_block), "block-room") {
            dispatch(StageIntent.BlockCurrent(state.focusTarget()))
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
