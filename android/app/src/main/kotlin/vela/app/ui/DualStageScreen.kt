package vela.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import vela.app.stage.LaneFace
import vela.app.stage.LanePlayback
import vela.app.stage.LaneWindow
import vela.app.stage.StageIntent
import vela.app.stage.StageRules
import vela.app.stage.StageUiState
import vela.app.ui.theme.ChampagneButton
import vela.app.ui.theme.Theme
import vela.core.base.lane.LaneID
import vela.core.base.lane.LaneLayout
import vela.core.base.lane.RoomTarget
import vela.core.base.model.Room
import vela.core.feed.InfoVisibility

/**
 * 竖屏多格（双窗 / 三窗）。语义对齐 iOS [PhoneDualStage]：
 * 每路独立分页，点窗格或划动时切焦点；焦点路有香槟色描边；只有焦点窗格在出声时绑播放器。
 */
@Composable
fun DualStageScreen(
    state: StageUiState,
    dispatch: (StageIntent) -> Unit,
    modifier: Modifier = Modifier,
    player: @Composable (room: Room, lane: LaneID, active: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.height > size.width && size.width > 0) {
                    dispatch(
                        StageIntent.NotePortraitStageSize(
                            width = size.width.toDouble(),
                            height = size.height.toDouble(),
                        ),
                    )
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            val lanes = LaneID.active(state.laneCount)
            val paneHeightPx = remember(state.laneCount, state.portraitStageHeight) {
                with(density) {
                    LaneLayout.paneHeight(
                        if (state.portraitStageHeight > 0.0) state.portraitStageHeight
                        else 800.0,
                        state.laneCount,
                    ).dp.roundToPx()
                }
            }
            lanes.forEachIndexed { index, lane ->
                if (index > 0) {
                    Spacer(Modifier.height(with(density) { LaneLayout.gap.dp }))
                }
                DualPane(
                    state = state,
                    lane = lane,
                    focused = state.focusLane == lane,
                    chromeOn = state.chromeShown,
                    paneHeightPx = paneHeightPx,
                    dispatch = dispatch,
                    player = player,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { paneHeightPx.toDp() }),
                )
            }
        }
    }
}

@Composable
private fun DualPane(
    state: StageUiState,
    lane: LaneID,
    focused: Boolean,
    chromeOn: Boolean,
    paneHeightPx: Int,
    dispatch: (StageIntent) -> Unit,
    player: @Composable (room: Room, lane: LaneID, active: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val face = state.face(lane)
    val username = face?.username
    val focusModifier = if (focused) {
        Modifier
            .border(BorderStroke(1.5.dp, Theme.champagne.copy(alpha = 0.8f)))
            .testTag("dual-focus:${lane.name.lowercase()}")
    } else {
        Modifier
    }
    Box(
        modifier
            .testTag("dual-pane:${lane.name.lowercase()}")
            .background(Theme.ink)
            .then(focusModifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = {
                    dispatch(StageIntent.SetFocus(lane))
                    dispatch(StageIntent.NoteActivity)
                },
            ),
    ) {
        if (username == null) {
            val empty = state.emptyLaneMessage(lane) ?: StageRules.NO_MORE_ROOMS
            BasicText(
                empty,
                style = TextStyle(color = Theme.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            DualLanePager(
                state = state,
                lane = lane,
                face = face ?: LaneFace(id = lane),
                dispatch = dispatch,
                paneHeightPx = paneHeightPx,
                player = player,
            )
        }
        if (chromeOn) {
            state.roomOnLane(lane)?.let { room ->
                DualPaneBar(
                    state = state,
                    lane = lane,
                    room = room,
                    playing = face?.playingUsername != null &&
                        face.playingUsername.equals(room.username, ignoreCase = true),
                    dispatch = dispatch,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun DualLanePager(
    state: StageUiState,
    lane: LaneID,
    face: LaneFace,
    dispatch: (StageIntent) -> Unit,
    paneHeightPx: Int,
    player: @Composable (room: Room, lane: LaneID, active: Boolean) -> Unit,
) {
    val step = rememberUpdatedState(dispatch)
    var dragY by remember { mutableFloatStateOf(0f) }
    var heightPx by remember { mutableIntStateOf(paneHeightPx) }
    val laneIndex = state.indexOfLane(lane)
    val pages = lanePagerRooms(state, lane)
    val currentName = face.username
    val pagerLabel = stringResource(R.string.ui_hint_swipe)
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height }
            .semantics { contentDescription = pagerLabel }
            .testTag("pager:${lane.name.lowercase()}")
            .pointerInput(lane) {
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
                                if (delta != 0) {
                                    step.value(StageIntent.SetFocus(lane))
                                    step.value(StageIntent.Step(delta, lane))
                                }
                            } else if (abs(total) <= slop) {
                                step.value(StageIntent.SetFocus(lane))
                                step.value(StageIntent.NoteActivity)
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
                room.username == currentName -> 0
                at >= 0 && at < laneIndex -> -1
                else -> 1
            }
            key("$lane:${room.username}") {
                DualRoomPage(
                    room = room,
                    state = state,
                    lane = lane,
                    face = face,
                    active = room.username == currentName,
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
private fun DualRoomPage(
    room: Room,
    state: StageUiState,
    lane: LaneID,
    face: LaneFace,
    active: Boolean,
    dispatch: (StageIntent) -> Unit,
    player: @Composable (room: Room, lane: LaneID, active: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allowed = state.lanePlaybackAllowed(lane) && active
    val videoReady = allowed && face.readyUsername == room.username
    val infoVisible = InfoVisibility.visible(
        isCurrent = active,
        alwaysShow = state.infoAlwaysOn,
        chromeOn = state.chromeShown,
    )
    Box(modifier.testTag("room:${room.username}").background(brush())) {
        if (allowed) {
            Box(Modifier.fillMaxSize().testTag("player-slot")) {
                player(room, lane, active)
            }
        }
        if (infoVisible) {
            Caption(
                room = room,
                reason = state.reasonFor(room),
                live = active && face.playingUsername == room.username && infoVisible,
                modifier = Modifier.align(Alignment.BottomStart).testTag("caption:${room.username}"),
            )
        }
        if (active && face.manuallyPaused) {
            PauseMark(Modifier.align(Alignment.Center))
        } else if (active) {
            val note = dualFailureNote(state, lane, face, videoReady)
            if (note != null) {
                FailureMark(
                    note,
                    state,
                    dispatch,
                    onRetry = { dispatch(StageIntent.RetryPlayback(state.laneTarget(lane))) },
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (face.playback == LanePlayback.Degraded) {
                BasicText(
                    StageRules.DUAL_UNAVAILABLE,
                    style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            } else if (!videoReady && state.ageConfirmed) {
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
private fun DualPaneBar(
    state: StageUiState,
    lane: LaneID,
    room: Room,
    playing: Boolean,
    dispatch: (StageIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val target = state.laneTarget(lane)
    val paused = state.face(lane)?.manuallyPaused == true
    Box(modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Caption(
                room = room,
                reason = state.reasonFor(room),
                live = playing,
                modifier = Modifier.weight(1f).testTag("pane-caption:${lane.name.lowercase()}"),
            )
            TextAction(
                text = "⋯",
                onClick = { menuOpen = true },
                modifier = Modifier.testTag("pane-more:${lane.name.lowercase()}"),
            )
            val favorite = state.curation.isFavorite(room.username)
            val heart = if (favorite) "♥" else "♡"
            TextAction(
                text = heart,
                onClick = {
                    target?.let { dispatch(StageIntent.ToggleFavorite(it)) }
                },
                modifier = Modifier.testTag("pane-heart:${lane.name.lowercase()}"),
            )
        }
        PopupMenu(
            open = menuOpen,
            onDismiss = { menuOpen = false },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 52.dp),
        ) {
            MenuRow(
                text = stringResource(if (paused) R.string.ui_resume else R.string.ui_pause),
                onClick = {
                    menuOpen = false
                    if (target != null) dispatch(StageIntent.TogglePause(target))
                },
            )
            MenuRow(
                text = stringResource(R.string.ui_not_interested),
                onClick = {
                    menuOpen = false
                    if (target != null) dispatch(StageIntent.MarkNotInterested(target))
                },
            )
            MenuRow(
                text = stringResource(R.string.ui_block),
                onClick = {
                    menuOpen = false
                    if (target != null) dispatch(StageIntent.BlockCurrent(target))
                },
            )
        }
    }
}

/** 竖屏双窗顶栏：来源与控制项同 [ChromeBar]，外加路数选择。底栏房间信息由窗格自己画。 */
@Composable
fun DualTopChrome(
    state: StageUiState,
    dispatch: (StageIntent) -> Unit,
    volumeOpen: Boolean,
    onVolumeOpen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LaneCountPicker(state, dispatch, Modifier.padding(end = 8.dp))
        }
        ChromeBar(
            state = state,
            dispatch = dispatch,
            volumeOpen = volumeOpen,
            onVolumeOpen = onVolumeOpen,
        )
    }
}

@Composable
internal fun LaneCountPicker(state: StageUiState, dispatch: (StageIntent) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val max = LaneWindow.maxLaneCount(state.threeLanesFit)
    val label = "${state.laneCount} 路"
    val capsule = if (state.dualWindow) Theme.champagne else Color(0xFF141414).copy(alpha = 0.62f)
    val textColor = if (state.dualWindow) Color.Black.copy(alpha = 0.86f) else Color.White
    Box(modifier, contentAlignment = Alignment.TopEnd) {
        BasicText(
            text = label,
            style = TextStyle(color = textColor, fontSize = 13.sp, fontWeight = if (state.dualWindow) FontWeight.SemiBold else FontWeight.Medium),
            modifier = Modifier
                .testTag("lane-picker")
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    enabled = !state.laneCountLocked,
                    onClick = { open = !open },
                )
                .background(capsule, RoundedCornerShape(percent = 50))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
        if (open) {
            Column(
                Modifier
                    .padding(top = 40.dp)
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                    .padding(vertical = 4.dp),
            ) {
                for (count in 1..LaneID.maxCount) {
                    val enabled = count <= max && !state.laneCountLocked
                    val title = if (count == LaneID.maxCount && !state.threeLanesFit) {
                        "$count 路（屏幕高度不够）"
                    } else {
                        "$count 路"
                    }
                    MenuRow(
                        text = title,
                        enabled = enabled,
                        modifier = Modifier.testTag("lane-count:$count"),
                        onClick = {
                            open = false
                            dispatch(StageIntent.SetLaneCount(count))
                        },
                    )
                }
                if (state.laneCountLocked) {
                    BasicText(
                        "画中画进行中，结束后可调整路数",
                        style = TextStyle(color = Theme.textSecondary, fontSize = 12.sp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PopupMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!open) return
    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Column(
            modifier
                .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                .padding(vertical = 4.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            content = content,
        )
    }
}

@Composable
private fun MenuRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = if (enabled) Theme.textPrimary else Theme.textSecondary,
                fontSize = 14.sp,
            ),
        )
    }
}

@Composable
private fun FailureMark(
    note: String,
    state: StageUiState,
    dispatch: (StageIntent) -> Unit,
    onRetry: () -> Unit,
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
            onClick = onRetry,
            modifier = Modifier.testTag("retry-playback"),
        )
    }
}

internal fun lanePagerRooms(state: StageUiState, lane: LaneID): List<Room> {
    if (state.rooms.isEmpty()) return emptyList()
    val at = state.indexOfLane(lane)
    val from = (at - 1).coerceAtLeast(0)
    val to = (at + 1).coerceAtMost(state.rooms.lastIndex)
    return state.rooms.subList(from, to + 1).toList()
}

private fun dualFailureNote(state: StageUiState, lane: LaneID, face: LaneFace, videoReady: Boolean): String? {
    if (face.manuallyPaused) return null
    if (face.playback == LanePlayback.Failed) {
        return face.playbackNote ?: StageRules.PLAYBACK_INTERRUPTED
    }
    if (!videoReady && face.playbackNote != null) return face.playbackNote
    return null
}

internal fun StageUiState.laneTarget(lane: LaneID): RoomTarget? {
    val face = face(lane) ?: return null
    val name = if (lane == LaneID.PRIMARY) currentUsername else face.username
    if (name == null) return null
    return if (lane == LaneID.PRIMARY) {
        RoomTarget.primary(name, face.generation)
    } else {
        RoomTarget(lane, name, face.generation)
    }
}

internal fun StageUiState.roomOnLane(lane: LaneID): Room? {
    val name = if (lane == LaneID.PRIMARY) currentUsername else face(lane)?.username
    if (name == null) return null
    return rooms.firstOrNull { it.username.equals(name, ignoreCase = true) }
}