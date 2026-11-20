package vela.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import vela.app.R
import vela.app.stage.StageIntent
import vela.app.stage.StageSurface
import vela.app.stage.StageUiState
import vela.app.ui.theme.Theme
import vela.core.base.lane.RoomTarget
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Region

/** 顶部控制栏。没有置顶、淡出、铺满、窗口按钮。音量面板是本地状态，不占 [StageSurface]。 */
@Composable
fun ChromeBar(
    state: StageUiState,
    dispatch: (StageIntent) -> Unit,
    modifier: Modifier = Modifier,
    volumeOpen: Boolean = false,
    onVolumeOpen: (Boolean) -> Unit = {},
) {
    Column(modifier.fillMaxWidth().background(Theme.ink.copy(alpha = 0.92f))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FeedSource.entries.forEach { source ->
                val label = sourceLabel(source)
                val help = if (source == FeedSource.fresh) {
                    stringResource(R.string.ui_source_fresh_help)
                } else {
                    stringResource(R.string.ui_source_help, label)
                }
                ChipButton(
                    text = label,
                    selected = state.source == source,
                    onClick = { dispatch(StageIntent.SelectSource(source)) },
                    modifier = Modifier
                        .semantics { contentDescription = help }
                        .testTag("source:${source.name}"),
                )
            }
            ChipButton(
                text = stringResource(R.string.ui_filters),
                selected = state.surface == StageSurface.Filters,
                onClick = {
                    dispatch(
                        if (state.surface == StageSurface.Filters) {
                            StageIntent.DismissSurfaces
                        } else {
                            StageIntent.ShowSurface(StageSurface.Filters)
                        },
                    )
                },
                modifier = Modifier.testTag("open-filters"),
            )
            HeartButton(state, dispatch)
            val tasteHelp = stringResource(R.string.ui_taste)
            ChipButton(
                text = stringResource(R.string.ui_taste_short),
                selected = state.surface == StageSurface.Taste,
                onClick = {
                    dispatch(
                        if (state.surface == StageSurface.Taste) {
                            StageIntent.DismissSurfaces
                        } else {
                            StageIntent.ShowSurface(StageSurface.Taste)
                        },
                    )
                },
                modifier = Modifier.testTag("open-taste").semantics { contentDescription = tasteHelp },
            )
            ChipButton(
                text = stringResource(R.string.ui_volume),
                selected = volumeOpen || state.muted,
                onClick = { onVolumeOpen(!volumeOpen) },
                modifier = Modifier.testTag("open-volume"),
            )
            ChipButton(
                text = stringResource(R.string.ui_info),
                selected = state.infoAlwaysOn,
                onClick = { dispatch(StageIntent.ToggleInfo) },
                modifier = Modifier.testTag("toggle-info"),
            )
            ChipButton(
                text = stringResource(R.string.pip_enter),
                selected = state.pictureInPicture,
                onClick = { dispatch(StageIntent.EnterPictureInPicture) },
                modifier = Modifier.testTag("enter-pip"),
            )
            ChipButton(
                text = stringResource(R.string.pip_auto_enter),
                selected = state.autoEnterPip,
                onClick = { dispatch(StageIntent.SetAutoEnterPip(!state.autoEnterPip)) },
                modifier = Modifier.testTag("toggle-auto-pip"),
            )
        }
        if (volumeOpen) {
            VolumePanel(state, dispatch, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun HeartButton(state: StageUiState, dispatch: (StageIntent) -> Unit) {
    val favorite = state.currentIsFavorite
    val label = stringResource(if (favorite) R.string.ui_unfavorite else R.string.ui_favorite)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = {
                    val target = state.primaryTarget()
                    if (target != null) dispatch(StageIntent.ToggleFavorite(target))
                },
                onLongClick = { dispatch(StageIntent.ShowSurface(StageSurface.Curation)) },
            )
            .semantics { contentDescription = label }
            .testTag("heart")
            .padding(horizontal = 10.dp),
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

@Composable
private fun VolumePanel(state: StageUiState, dispatch: (StageIntent) -> Unit, modifier: Modifier = Modifier) {
    val percent = (state.volume * 100.0).roundToInt().coerceIn(0, 100)
    val quieter = stringResource(R.string.ui_volume_down)
    val louder = stringResource(R.string.ui_volume_up)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
            text = if (state.muted) {
                stringResource(R.string.ui_mute)
            } else {
                stringResource(R.string.ui_volume_percent, percent)
            },
            style = TextStyle(color = Theme.textSecondary, fontSize = 12.sp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction(
                text = "−",
                onClick = { dispatch(StageIntent.SetVolume((state.volume - 0.1).coerceAtLeast(0.0))) },
                modifier = Modifier.testTag("volume-down").semantics { contentDescription = quieter },
            )
            TextAction(
                text = "+",
                onClick = { dispatch(StageIntent.SetVolume((state.volume + 0.1).coerceAtMost(1.0))) },
                modifier = Modifier.testTag("volume-up").semantics { contentDescription = louder },
            )
            TextAction(
                text = stringResource(if (state.muted) R.string.ui_unmute else R.string.ui_mute),
                onClick = { dispatch(StageIntent.ToggleMute) },
            )
        }
    }
}

/** 搜索与筛选。热门标签不在契约里，这里不编造。 */
@Composable
fun FilterSheet(
    state: StageUiState,
    dispatch: (StageIntent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetFrame(dismissOnScrim = true, onDismiss = onDismiss, modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = stringResource(R.string.ui_filters),
                    style = TextStyle(color = Theme.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                TextAction(stringResource(R.string.ui_close_filters), onDismiss)
            }
            LineField(
                value = state.searchText,
                onValueChange = { dispatch(StageIntent.SetSearchText(it)) },
                placeholder = stringResource(R.string.ui_search_hint),
                modifier = Modifier.testTag("search-field"),
                ime = ImeAction.Search,
                onIme = { dispatch(StageIntent.SubmitSearch) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                GenderChip(null, state.gender, dispatch)
                Gender.entries.forEach { GenderChip(it, state.gender, dispatch) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                RegionChip(null, state.region, dispatch)
                Region.entries.forEach { RegionChip(it, state.region, dispatch) }
            }
            ChipButton(
                text = stringResource(if (state.publicOnly) R.string.ui_public_on else R.string.ui_public_off),
                selected = state.publicOnly,
                onClick = { dispatch(StageIntent.SetPublicOnly(!state.publicOnly)) },
                modifier = Modifier.testTag("public-only"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LineField(
                    value = state.tagText,
                    onValueChange = { dispatch(StageIntent.SetTagText(it)) },
                    placeholder = stringResource(R.string.ui_tag_hint),
                    modifier = Modifier.weight(1f).testTag("tag-field"),
                    ime = ImeAction.Done,
                    onIme = { dispatch(StageIntent.ApplyTag) },
                )
                TextAction(
                    text = stringResource(R.string.ui_apply),
                    onClick = { dispatch(StageIntent.ApplyTag) },
                    modifier = Modifier.testTag("apply-tag"),
                )
            }
        }
    }
}

@Composable
private fun GenderChip(gender: Gender?, selected: Gender?, dispatch: (StageIntent) -> Unit) {
    val wire = gender?.wire ?: "all"
    ChipButton(
        text = genderLabel(gender),
        selected = selected == gender,
        onClick = { dispatch(StageIntent.ChooseGender(gender)) },
        modifier = Modifier.testTag("gender:$wire"),
    )
}

@Composable
private fun RegionChip(region: Region?, selected: Region?, dispatch: (StageIntent) -> Unit) {
    val id = region?.name ?: "all"
    ChipButton(
        text = regionLabel(region),
        selected = selected == region,
        onClick = { dispatch(StageIntent.ChooseRegion(region)) },
        modifier = Modifier.testTag("region:$id"),
    )
}

@Composable
internal fun sourceLabel(source: FeedSource): String = when (source) {
    FeedSource.guess -> stringResource(R.string.stage_source_guess)
    FeedSource.popular -> stringResource(R.string.stage_source_popular)
    FeedSource.fresh -> stringResource(R.string.stage_source_fresh)
    FeedSource.favorites -> stringResource(R.string.stage_source_favorites)
}

@Composable
internal fun genderLabel(gender: Gender?): String = when (gender) {
    Gender.female -> stringResource(R.string.ui_gender_female)
    Gender.male -> stringResource(R.string.ui_gender_male)
    Gender.trans -> stringResource(R.string.ui_gender_trans)
    Gender.couple -> stringResource(R.string.ui_gender_couple)
    null -> stringResource(R.string.ui_gender_all)
}

@Composable
internal fun regionLabel(region: Region?): String = when (region) {
    Region.asia -> stringResource(R.string.ui_region_asia)
    Region.europeRussia -> stringResource(R.string.ui_region_europe)
    Region.northAmerica -> stringResource(R.string.ui_region_na)
    Region.southAmerica -> stringResource(R.string.ui_region_sa)
    Region.other -> stringResource(R.string.ui_region_other)
    null -> stringResource(R.string.ui_region_all)
}

/** 性别行的展示名。画像里的 `name` 是去掉 `g:` 之后的 wire（f/m/t/c）。 */
internal fun genderWireLabel(name: String, female: String, male: String, trans: String, couple: String): String =
    when (name) {
        "f", "female" -> female
        "m", "male" -> male
        "t", "s", "trans" -> trans
        "c", "couple" -> couple
        else -> name
    }

@Composable
internal fun ChipButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .background(
                if (selected) Theme.champagne else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(percent = 50),
            )
            .clickable(interaction, null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = if (selected) Color.Black.copy(alpha = 0.86f) else Theme.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
internal fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Theme.champagne,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(interaction, null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
internal fun LineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    ime: ImeAction = ImeAction.Done,
    onIme: () -> Unit = {},
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Theme.textPrimary, fontSize = 14.sp),
        keyboardOptions = KeyboardOptions(imeAction = ime),
        keyboardActions = KeyboardActions(onSearch = { onIme() }, onDone = { onIme() }),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .then(modifier),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    BasicText(placeholder, style = TextStyle(color = Theme.textSecondary, fontSize = 13.sp))
                }
                inner()
            }
        },
    )
}

internal fun StageUiState.primaryTarget(): RoomTarget? {
    val name = currentUsername ?: return null
    return RoomTarget.primary(name, lane.generation)
}

@Composable
internal fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
}
