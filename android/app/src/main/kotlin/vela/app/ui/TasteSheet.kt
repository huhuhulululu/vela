package vela.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import vela.app.R
import vela.app.stage.DayBarState
import vela.app.stage.StageIntent
import vela.app.stage.StageUiState
import vela.app.stage.TasteRowState
import vela.app.ui.theme.Theme
import vela.core.base.model.TasteKeys
import vela.core.taste.Taste

private const val COLLAPSED_ROWS = 8

private enum class TasteConfirm { Reset, Clear }

/** 「我的口味」。数值由舞台算好，这里只画行和发意图。 */
@Composable
fun TasteSheet(
    state: StageUiState,
    dispatch: (StageIntent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var likedExpanded by remember { mutableStateOf(false) }
    var dislikedExpanded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<TasteConfirm?>(null) }
    val taste = state.taste
    SheetFrame(dismissOnScrim = true, onDismiss = onDismiss, modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        text = stringResource(R.string.ui_taste),
                        style = TextStyle(color = Theme.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    )
                    BasicText(
                        text = stringResource(R.string.ui_device_only),
                        style = TextStyle(color = Theme.textSecondary, fontSize = 12.sp),
                    )
                }
                TextAction(stringResource(R.string.ui_close), onDismiss)
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (taste.tagCount < Taste.coldStartTags && taste.suggestions.isNotEmpty()) {
                    QuickSort(taste.suggestions, dispatch)
                }
                HitRate(taste.hitRate, taste.week)
                TasteSection(
                    title = stringResource(R.string.ui_liked_tags),
                    rows = taste.liked,
                    empty = stringResource(R.string.ui_liked_empty),
                    expanded = likedExpanded,
                    onToggle = { likedExpanded = !likedExpanded },
                    expandTag = "expand-liked",
                    detail = { likedDetail(it) },
                    actions = { row ->
                        listOf(
                            TasteAction(stringResource(R.string.ui_boost), Theme.champagne, "taste-boost:${row.key}") {
                                dispatch(StageIntent.BoostTaste(row.key))
                            },
                            TasteAction(stringResource(R.string.ui_veto), Theme.live, "taste-veto:${row.key}") {
                                dispatch(StageIntent.VetoTaste(row.key))
                            },
                            TasteAction(stringResource(R.string.ui_remove_taste), Theme.textSecondary, "taste-remove:${row.key}") {
                                dispatch(StageIntent.RemoveTaste(row.key))
                            },
                        )
                    },
                )
                TasteSection(
                    title = stringResource(R.string.ui_disliked_tags),
                    rows = taste.disliked,
                    empty = stringResource(R.string.ui_disliked_empty),
                    expanded = dislikedExpanded,
                    onToggle = { dislikedExpanded = !dislikedExpanded },
                    expandTag = "expand-disliked",
                    detail = { dislikedDetail(it) },
                    actions = { row ->
                        listOf(
                            TasteAction(stringResource(R.string.ui_restore), Theme.champagne, "taste-restore:${row.key}") {
                                dispatch(StageIntent.RestoreTaste(row.key))
                            },
                            TasteAction(stringResource(R.string.ui_remove_taste), Theme.textSecondary, "taste-remove:${row.key}") {
                                dispatch(StageIntent.RemoveTaste(row.key))
                            },
                        )
                    },
                )
                GenderSection(taste.genders, dispatch)
            }
            Footer(taste.paused, confirm, dispatch) { confirm = it }
        }
    }
}

private data class TasteAction(val label: String, val color: Color, val tag: String, val run: () -> Unit)

@Composable
private fun QuickSort(tags: List<String>, dispatch: (StageIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = stringResource(R.string.ui_quick_sort),
            style = TextStyle(color = Theme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        )
        BasicText(
            text = stringResource(R.string.ui_quick_sort_note),
            style = TextStyle(color = Theme.textSecondary, fontSize = 11.sp),
        )
        tags.forEach { tag ->
            val key = TasteKeys.tagKey(tag)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = tag,
                    style = TextStyle(color = Theme.textPrimary, fontSize = 13.sp),
                    modifier = Modifier.weight(1f),
                )
                TextAction(
                    text = stringResource(R.string.ui_like),
                    onClick = { dispatch(StageIntent.BoostTaste(key)) },
                    modifier = Modifier.testTag("suggest-like:$tag"),
                )
                TextAction(
                    text = stringResource(R.string.ui_veto),
                    onClick = { dispatch(StageIntent.VetoTaste(key)) },
                    modifier = Modifier.testTag("suggest-veto:$tag"),
                    color = Theme.live,
                )
            }
        }
    }
}

@Composable
private fun HitRate(rate: Double?, week: List<DayBarState>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = stringResource(R.string.ui_hit_rate),
                style = TextStyle(color = Theme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                text = if (rate == null) {
                    stringResource(R.string.ui_not_enough)
                } else {
                    "${(rate * 100.0).roundToInt()}%"
                },
                style = TextStyle(
                    color = if (rate == null) Theme.textSecondary else Theme.champagne,
                    fontSize = if (rate == null) 12.sp else 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
        BasicText(
            text = stringResource(R.string.ui_hit_rate_window),
            style = TextStyle(color = Theme.textSecondary, fontSize = 11.sp),
        )
        if (week.isNotEmpty()) {
            val peak = week.maxOf { it.hit + it.miss }.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                week.forEachIndexed { index, day ->
                    val today = index == week.lastIndex
                    val description = stringResource(R.string.ui_day_hit, day.hit, day.miss)
                    Column(
                        Modifier.weight(1f).semantics { this.contentDescription = description },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val total = day.hit + day.miss
                        Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(total.toFloat() / peak.toFloat())
                                    .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(2.dp)),
                            )
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(day.hit.toFloat() / peak.toFloat())
                                    .background(Theme.champagne, RoundedCornerShape(2.dp)),
                            )
                        }
                        BasicText(
                            text = if (today) stringResource(R.string.ui_today) else weekdayLabel(day.weekday),
                            style = TextStyle(color = Theme.textSecondary, fontSize = 10.sp),
                        )
                    }
                }
            }
        }
    }
}

private fun weekdayLabel(weekday: Int): String {
    val labels = listOf("日", "一", "二", "三", "四", "五", "六")
    val index = (weekday - 1).coerceIn(0, labels.lastIndex)
    return labels[index]
}

@Composable
private fun TasteSection(
    title: String,
    rows: List<TasteRowState>,
    empty: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    expandTag: String,
    detail: @Composable (TasteRowState) -> String,
    actions: @Composable (TasteRowState) -> List<TasteAction>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(title, style = TextStyle(color = Theme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
        if (rows.isEmpty()) {
            BasicText(empty, style = TextStyle(color = Theme.textSecondary, fontSize = 12.sp))
        }
        val shown = if (expanded) rows else rows.take(COLLAPSED_ROWS)
        shown.forEach { row -> TasteLine(row, detail(row), actions(row)) }
        if (rows.size > COLLAPSED_ROWS) {
            TextAction(
                text = if (expanded) stringResource(R.string.ui_collapse) else stringResource(R.string.ui_expand_all, rows.size),
                onClick = onToggle,
                modifier = Modifier.testTag(expandTag),
            )
        }
    }
}

@Composable
private fun GenderSection(rows: List<TasteRowState>, dispatch: (StageIntent) -> Unit) {
    val female = stringResource(R.string.ui_gender_female)
    val male = stringResource(R.string.ui_gender_male)
    val trans = stringResource(R.string.ui_gender_trans)
    val couple = stringResource(R.string.ui_gender_couple)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
            text = stringResource(R.string.ui_gender_pref),
            style = TextStyle(color = Theme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        )
        if (rows.isEmpty()) {
            BasicText(stringResource(R.string.ui_gender_empty), style = TextStyle(color = Theme.textSecondary, fontSize = 12.sp))
        }
        rows.forEach { row ->
            val actions = if (row.vetoed) {
                listOf(
                    TasteAction(stringResource(R.string.ui_restore), Theme.champagne, "taste-restore:${row.key}") {
                        dispatch(StageIntent.RestoreTaste(row.key))
                    },
                    TasteAction(stringResource(R.string.ui_remove_taste), Theme.textSecondary, "taste-remove:${row.key}") {
                        dispatch(StageIntent.RemoveTaste(row.key))
                    },
                )
            } else {
                listOf(
                    TasteAction(stringResource(R.string.ui_boost), Theme.champagne, "taste-boost:${row.key}") {
                        dispatch(StageIntent.BoostTaste(row.key))
                    },
                    TasteAction(stringResource(R.string.ui_veto), Theme.live, "taste-veto:${row.key}") {
                        dispatch(StageIntent.VetoTaste(row.key))
                    },
                    TasteAction(stringResource(R.string.ui_remove_taste), Theme.textSecondary, "taste-remove:${row.key}") {
                        dispatch(StageIntent.RemoveTaste(row.key))
                    },
                )
            }
            TasteLine(
                row = row,
                detail = if (row.vetoed) stringResource(R.string.ui_filtered) else likedDetail(row),
                actions = actions,
                title = genderWireLabel(row.name, female, male, trans, couple),
            )
        }
    }
}

@Composable
private fun TasteLine(row: TasteRowState, detail: String, actions: List<TasteAction>, title: String = row.name) {
    val strength = stringResource(R.string.ui_strength, (row.mean.coerceIn(0.0, 1.0) * 100.0).roundToInt())
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("taste-row:${row.key}")
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = title,
                style = TextStyle(color = Theme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f),
            )
            BasicText(detail, style = TextStyle(color = Theme.textSecondary, fontSize = 11.sp))
        }
        Box(
            Modifier
                .padding(vertical = 4.dp)
                .width(44.dp)
                .height(5.dp)
                .semantics { contentDescription = strength }
                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(percent = 50)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(row.mean.coerceIn(0.04, 1.0).toFloat())
                    .background(Theme.champagne, RoundedCornerShape(percent = 50)),
            )
        }
        Row {
            actions.forEach { action ->
                TextAction(action.label, action.run, Modifier.testTag(action.tag), action.color)
            }
        }
    }
}

@Composable
private fun likedDetail(row: TasteRowState): String = when {
    row.hits > 0 -> stringResource(R.string.ui_hits, row.hits)
    row.mean < 0.5 -> stringResource(R.string.ui_skipped_often)
    else -> stringResource(R.string.ui_manual_boost)
}

@Composable
private fun dislikedDetail(row: TasteRowState): String =
    if (row.vetoed) stringResource(R.string.ui_filtered) else stringResource(R.string.ui_skipped_often)

@Composable
private fun Footer(
    paused: Boolean,
    confirm: TasteConfirm?,
    dispatch: (StageIntent) -> Unit,
    setConfirm: (TasteConfirm?) -> Unit,
) {
    if (confirm == null) {
        Column {
            TextAction(
                text = stringResource(if (paused) R.string.ui_resume_learning else R.string.ui_pause_learning),
                onClick = { dispatch(StageIntent.SetLearningPaused(!paused)) },
                modifier = Modifier.testTag("pause-learning"),
            )
            Row {
                TextAction(
                    text = stringResource(R.string.ui_reset_taste),
                    onClick = { setConfirm(TasteConfirm.Reset) },
                    modifier = Modifier.testTag("reset-taste"),
                )
                TextAction(
                    text = stringResource(R.string.ui_clear_preferences),
                    onClick = { setConfirm(TasteConfirm.Clear) },
                    modifier = Modifier.testTag("clear-preferences"),
                    color = Theme.live,
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(
                text = stringResource(if (confirm == TasteConfirm.Reset) R.string.ui_reset_confirm else R.string.ui_clear_confirm),
                style = TextStyle(color = Theme.textPrimary, fontSize = 12.sp),
            )
            Row {
                TextAction(
                    text = stringResource(R.string.ui_confirm),
                    onClick = {
                        if (confirm == TasteConfirm.Reset) dispatch(StageIntent.ResetTaste) else dispatch(StageIntent.ClearPreferences)
                        setConfirm(null)
                    },
                    modifier = Modifier.testTag("confirm-danger"),
                    color = Theme.live,
                )
                TextAction(
                    text = stringResource(R.string.ui_cancel),
                    onClick = { setConfirm(null) },
                    modifier = Modifier.testTag("cancel-danger"),
                    color = Theme.textSecondary,
                )
            }
        }
    }
}
