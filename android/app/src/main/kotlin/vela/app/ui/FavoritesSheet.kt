package vela.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vela.app.R
import vela.app.stage.StageIntent
import vela.app.stage.StageUiState
import vela.app.ui.theme.Theme
import vela.core.curation.FavoriteEntry

/**
 * 收藏夹。在线的整行点进去就看；其余只能移除。
 * 导出交给舞台，导入的文件选择由调用方接上（A7 再绑系统选择器）。
 */
@Composable
fun FavoritesSheet(
    state: StageUiState,
    dispatch: (StageIntent) -> Unit,
    onDismiss: () -> Unit,
    onImport: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var blockedPage by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var errorAnchor by remember { mutableStateOf<String?>(null) }
    var removed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.curation.favorites) {
        if (draft.isNotEmpty() && state.favoriteDraftError == null && state.curation.isFavorite(draft)) {
            draft = ""
            errorAnchor = null
        }
    }
    SheetFrame(dismissOnScrim = true, onDismiss = onDismiss, modifier = modifier) {
        if (blockedPage) {
            BlockedPage(
                state = state,
                dispatch = dispatch,
                onBack = { blockedPage = false },
            )
        } else {
            ListPage(
                state = state,
                dispatch = dispatch,
                onDismiss = onDismiss,
                draft = draft,
                onDraft = {
                    draft = it
                    if (errorAnchor != it) errorAnchor = null
                },
                error = state.favoriteDraftError?.takeIf { errorAnchor == draft },
                onSubmit = {
                    errorAnchor = draft
                    dispatch(StageIntent.AddFavorite(draft))
                },
                removed = removed?.takeIf { state.curation.canRestoreFavorite(it) },
                onRemove = {
                    dispatch(StageIntent.RemoveFavorite(it))
                    removed = it
                },
                onUndo = {
                    val name = removed ?: return@ListPage
                    dispatch(StageIntent.RestoreFavorite(name))
                    removed = null
                },
                onBlocked = { blockedPage = true },
                onImport = onImport,
            )
        }
    }
}

@Composable
private fun ListPage(
    state: StageUiState,
    dispatch: (StageIntent) -> Unit,
    onDismiss: () -> Unit,
    draft: String,
    onDraft: (String) -> Unit,
    error: String?,
    onSubmit: () -> Unit,
    removed: String?,
    onRemove: (String) -> Unit,
    onUndo: () -> Unit,
    onBlocked: () -> Unit,
    onImport: (() -> Unit)?,
) {
    val board = state.favoritesBoard
    val total = board.online.size + board.others.size
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    text = stringResource(R.string.ui_favorites_title),
                    style = TextStyle(color = Theme.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                )
                BasicText(
                    text = if (total == 0) {
                        stringResource(R.string.ui_favorites_empty)
                    } else {
                        stringResource(R.string.ui_favorites_summary, board.onlineCount, total)
                    },
                    style = TextStyle(color = Theme.textSecondary, fontSize = 12.sp),
                )
            }
            TextAction(
                text = stringResource(if (state.favoritesChecking) R.string.ui_checking else R.string.ui_refresh),
                onClick = { if (!state.favoritesChecking) dispatch(StageIntent.RefreshFavorites) },
                modifier = Modifier.testTag("refresh-favorites"),
                color = if (state.favoritesChecking) Theme.textSecondary else Theme.champagne,
            )
            TextAction(stringResource(R.string.ui_close), onDismiss)
        }
        val current = state.focusRoom
        ChipButton(
            text = stringResource(if (state.currentIsFavorite) R.string.ui_favorites_kept else R.string.ui_favorite),
            selected = state.currentIsFavorite,
            onClick = {
                val target = state.focusTarget() ?: return@ChipButton
                dispatch(StageIntent.ToggleFavorite(target))
            },
            modifier = Modifier.fillMaxWidth().testTag("favorite-current"),
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (board.online.isEmpty() && board.others.isEmpty()) {
                BasicText(
                    text = stringResource(R.string.ui_favorites_empty_hint),
                    style = TextStyle(color = Theme.textSecondary, fontSize = 13.sp),
                )
            } else {
                SectionLabel(stringResource(R.string.ui_online), board.online.size.toString())
                if (board.online.isEmpty()) {
                    BasicText(
                        text = onlineEmpty(state, board.others),
                        style = TextStyle(color = Theme.textSecondary, fontSize = 12.sp),
                    )
                }
                board.online.forEach { entry ->
                    FavoriteRow(entry, playable = true, onPlay = { dispatch(StageIntent.PlayFavorite(entry.username)) }, onRemove = onRemove)
                }
                if (board.others.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.ui_others), board.others.size.toString())
                    board.others.forEach { entry ->
                        FavoriteRow(entry, playable = false, onPlay = {}, onRemove = onRemove)
                    }
                }
            }
        }
        if (removed != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = stringResource(R.string.ui_removed, removed),
                    style = TextStyle(color = Theme.textPrimary, fontSize = 12.sp),
                    modifier = Modifier.weight(1f),
                )
                TextAction(stringResource(R.string.ui_undo), onUndo, Modifier.testTag("favorite-undo"))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineField(
                value = draft,
                onValueChange = onDraft,
                placeholder = stringResource(R.string.ui_add_favorite_hint),
                modifier = Modifier.weight(1f).testTag("add-favorite"),
                ime = ImeAction.Done,
                onIme = onSubmit,
            )
            TextAction(stringResource(R.string.ui_add), onSubmit, Modifier.testTag("add-favorite-submit"))
        }
        if (error != null) {
            BasicText(error, style = TextStyle(color = Theme.live, fontSize = 12.sp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction(stringResource(R.string.ui_export), { dispatch(StageIntent.ExportFavorites) }, Modifier.testTag("export-favorites"))
            TextAction(
                text = stringResource(R.string.ui_import),
                onClick = { onImport?.invoke() },
                modifier = Modifier.testTag("import-favorites"),
            )
        }
        TextAction(
            text = stringResource(R.string.ui_blocked_count, state.curation.blocked.size),
            onClick = onBlocked,
            modifier = Modifier.testTag("open-blocked"),
            color = Theme.textSecondary,
        )
        if (current != null) {
            TextAction(
                text = stringResource(R.string.ui_block_current),
                onClick = {
                    val target = state.focusTarget() ?: return@TextAction
                    dispatch(StageIntent.BlockCurrent(target))
                },
                modifier = Modifier.testTag("block-current"),
                color = Theme.live,
            )
        }
    }
}

@Composable
private fun onlineEmpty(state: StageUiState, others: List<FavoriteEntry>): String = when {
    state.favoritesChecking -> stringResource(R.string.ui_online_checking)
    others.all { it.state == FavoriteEntry.State.unknown } -> stringResource(R.string.ui_online_unknown)
    else -> stringResource(R.string.ui_online_none)
}

@Composable
private fun FavoriteRow(
    entry: FavoriteEntry,
    playable: Boolean,
    onPlay: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val open = if (playable) {
            Modifier.clickableNoIndication(onPlay).testTag("favorite-play:${entry.username}")
        } else {
            Modifier
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .then(open)
                .padding(vertical = 6.dp),
        ) {
            BasicText(entry.username, style = TextStyle(color = Theme.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            BasicText(entry.detail, style = TextStyle(color = Theme.textSecondary, fontSize = 12.sp))
        }
        TextAction(
            text = stringResource(R.string.ui_remove),
            onClick = { onRemove(entry.username) },
            modifier = Modifier.testTag("favorite-remove:${entry.username}"),
            color = Theme.textSecondary,
        )
    }
}

@Composable
private fun BlockedPage(
    state: StageUiState,
    dispatch: (StageIntent) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction(stringResource(R.string.ui_back), onBack, Modifier.testTag("back-blocked"))
            BasicText(
                text = stringResource(R.string.ui_blocked_title),
                style = TextStyle(color = Theme.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            )
        }
        state.curation.blocked.forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = name,
                    style = TextStyle(color = Theme.textPrimary, fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                )
                TextAction(
                    text = stringResource(R.string.ui_unblock),
                    onClick = { dispatch(StageIntent.Unblock(name)) },
                    modifier = Modifier.testTag("unblock:$name"),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String, note: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(title, style = TextStyle(color = Theme.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        BasicText(note, style = TextStyle(color = Theme.textSecondary, fontSize = 12.sp))
    }
}
