package vela.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vela.app.R
import vela.app.stage.LaneFace
import vela.app.stage.LanePlayback
import vela.app.stage.StageIntent
import vela.app.stage.StageRules
import vela.app.stage.StageUiState
import vela.app.ui.theme.Theme
import vela.core.base.lane.LaneID
import vela.core.base.lane.LaneLayout
import vela.core.base.lane.RoomTarget
import vela.core.base.model.Room

/**
 * 竖屏多格（双窗到三窗）：上下等高叠放，格高 `(H − (N−1)×间距) / N`。
 * 每格独立划动；点格或在格里划动都会把焦点交给它；焦点路有香槟色细边。
 * 安卓本包实装到 2 路播放接线，界面按契约仍可摆 3 格。
 */
@Composable
fun DualStageScreen(
    state: StageUiState,
    dispatch: (StageIntent) -> Unit,
    modifier: Modifier = Modifier,
    player: @Composable (LaneID, Room) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag("dual-stage")) {
        val lanes = LaneID.active(state.laneCount)
        val paneHeight = LaneLayout.paneHeight(maxHeight.value.toDouble(), lanes.size).dp
        val gap = LaneLayout.gap.dp
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            lanes.forEach { id ->
                DualPane(
                    state = state,
                    laneId = id,
                    dispatch = dispatch,
                    player = player,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(paneHeight),
                )
            }
        }
    }
}

@Composable
private fun DualPane(
    state: StageUiState,
    laneId: LaneID,
    dispatch: (StageIntent) -> Unit,
    player: @Composable (LaneID, Room) -> Unit,
    modifier: Modifier = Modifier,
) {
    val face = state.face(laneId)
    val focused = state.focusLane == laneId
    val emptyMessage = state.emptyLaneMessage(laneId)
    Box(
        modifier
            .testTag("pane:${laneId.label}")
            .background(Theme.ink),
    ) {
        if (face == null || emptyMessage != null) {
            EmptyLanePane(
                message = emptyMessage ?: StageRules.NO_MORE_ROOMS,
                onFocus = {
                    dispatch(StageIntent.SetFocus(laneId))
                    dispatch(StageIntent.NoteActivity)
                },
            )
        } else {
            val username = face.username
            if (username == null) {
                EmptyLanePane(
                    message = StageRules.NO_MORE_ROOMS,
                    onFocus = {
                        dispatch(StageIntent.SetFocus(laneId))
                        dispatch(StageIntent.NoteActivity)
                    },
                )
            } else {
                LaneRoomPager(
                    state = state,
                    laneId = laneId,
                    face = face,
                    dispatch = dispatch,
                    onStep = { delta ->
                        dispatch(StageIntent.SetFocus(laneId))
                        dispatch(StageIntent.Step(delta, laneId))
                    },
                    onTap = {
                        dispatch(StageIntent.SetFocus(laneId))
                        dispatch(StageIntent.NoteActivity)
                    },
                    player = { room -> player(laneId, room) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (focused) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(1.5.dp, Theme.champagne.copy(alpha = 0.8f))
                    .testTag("focus-border:${laneId.label}"),
            )
        }
        if (state.chromeShown) {
            val room = state.rooms.firstOrNull {
                it.username.equals(face?.username, ignoreCase = true)
            }
            if (room != null && face != null) {
                PaneBar(
                    state = state,
                    laneId = laneId,
                    face = face,
                    room = room,
                    dispatch = dispatch,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EmptyLanePane(message: String, onFocus: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .clickableNoIndication(onFocus),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = message,
            style = TextStyle(color = Theme.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun PaneBar(
    state: StageUiState,
    laneId: LaneID,
    face: LaneFace,
    room: Room,
    dispatch: (StageIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember(laneId, room.username) { mutableStateOf(false) }
    val target = state.laneTarget(laneId) ?: return
    val playing = face.playingUsername != null &&
        face.playingUsername.equals(room.username, ignoreCase = true)
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Theme.gap),
            ) {
                Caption(
                    room = room,
                    reason = state.reasonFor(room),
                    live = playing && state.chromeShown,
                    modifier = Modifier.weight(1f).testTag("caption:${room.username}"),
                )
                TextAction(
                    text = "···",
                    onClick = { menuOpen = !menuOpen },
                    modifier = Modifier.testTag("pane-more:${laneId.label}"),
                )
                PaneHeart(state, laneId, target, dispatch)
            }
            if (menuOpen) {
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val paused = face.manuallyPaused
                    TextAction(
                        text = stringResource(if (paused) R.string.ui_resume else R.string.ui_pause),
                        onClick = {
                            dispatch(StageIntent.TogglePause(target))
                            menuOpen = false
                        },
                        modifier = Modifier.testTag("pane-pause:${laneId.label}"),
                    )
                    TextAction(
                        text = stringResource(R.string.ui_not_interested),
                        onClick = {
                            dispatch(StageIntent.MarkNotInterested(target))
                            menuOpen = false
                        },
                        modifier = Modifier.testTag("pane-skip:${laneId.label}"),
                    )
                    TextAction(
                        text = stringResource(R.string.ui_block),
                        onClick = {
                            dispatch(StageIntent.BlockCurrent(target))
                            menuOpen = false
                        },
                        modifier = Modifier.testTag("pane-block:${laneId.label}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun PaneHeart(
    state: StageUiState,
    laneId: LaneID,
    target: RoomTarget,
    dispatch: (StageIntent) -> Unit,
) {
    val favorite = state.curation.isFavorite(target.username)
    val label = stringResource(if (favorite) R.string.ui_unfavorite else R.string.ui_favorite)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = { dispatch(StageIntent.ToggleFavorite(target)) },
            )
            .semantics { contentDescription = label }
            .testTag("pane-heart:${laneId.label}")
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = if (favorite) "♥" else "♡",
            style = TextStyle(
                color = if (favorite) Theme.favorite else Theme.textPrimary,
                fontSize = 18.sp,
            ),
        )
    }
}

/** 这一路画面上仍显示的房间目标。没有房间时为 null。 */
internal fun StageUiState.laneTarget(id: LaneID): RoomTarget? {
    val shown = face(id) ?: return null
    val name = shown.username ?: return null
    return RoomTarget(id, name, shown.generation)
}

/** 这一路垂直分页只渲染当前页和上下各一页。 */
internal fun StageUiState.lanePagerRooms(id: LaneID): List<Room> {
    if (rooms.isEmpty()) return emptyList()
    val at = indexOfLane(id)
    val from = (at - 1).coerceAtLeast(0)
    val to = (at + 1).coerceAtMost(rooms.lastIndex)
    return rooms.subList(from, to + 1).toList()
}

/**
 * 路数选择 1 / 2 / 3。矮屏第三路置灰。
 * 放在 [StageScreen] 顶栏旁，不改 Chrome.kt。
 */
@Composable
internal fun LaneCountPicker(
    state: StageUiState,
    dispatch: (StageIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val max = if (state.threeLanesFit) LaneID.maxCount else 2
    val label = "${state.laneCount} 路"
    val help = "路数，当前 ${state.laneCount} 路"
    Column(modifier) {
        ChipButton(
            text = label,
            selected = state.dualWindow,
            onClick = { if (!state.laneCountLocked) open = !open },
            modifier = Modifier
                .testTag("lane-count")
                .semantics { contentDescription = help },
        )
        if (open) {
            Column(
                Modifier
                    .padding(top = 4.dp)
                    .background(Theme.ink.copy(alpha = 0.94f), RoundedCornerShape(12.dp))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (count in 1..LaneID.maxCount) {
                    val enabled = count <= max && !state.laneCountLocked
                    val title = when {
                        count == LaneID.maxCount && !state.threeLanesFit -> "$count 路（屏幕高度不够）"
                        count == state.laneCount -> "✓ $count 路"
                        else -> "$count 路"
                    }
                    TextAction(
                        text = title,
                        onClick = {
                            if (!enabled) return@TextAction
                            dispatch(StageIntent.SetLaneCount(count))
                            open = false
                        },
                        modifier = Modifier.testTag("lane-count:$count"),
                        color = if (enabled) Theme.textPrimary else Theme.textSecondary,
                    )
                }
                if (state.laneCountLocked) {
                    BasicText(
                        "画中画进行中，结束后可调整路数",
                        style = TextStyle(color = Theme.textSecondary, fontSize = 11.sp),
                    )
                }
            }
        }
    }
}
