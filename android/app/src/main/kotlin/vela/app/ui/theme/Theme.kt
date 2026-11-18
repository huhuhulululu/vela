package vela.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 与共享 `Theme` 对齐的颜色和尺寸。界面不用 Material。 */
object Theme {
    val champagne = Color(red = 0.91f, green = 0.72f, blue = 0.52f)
    val live = Color(red = 0.96f, green = 0.27f, blue = 0.24f)
    val favorite = Color(red = 0.98f, green = 0.30f, blue = 0.36f)
    val online = Color(red = 0.36f, green = 0.78f, blue = 0.55f)
    val ink = Color(red = 0.045f, green = 0.045f, blue = 0.05f)
    val textPrimary = Color.White.copy(alpha = 0.92f)
    val textSecondary = Color.White.copy(alpha = 0.72f)
    val chip = Color.Black.copy(alpha = 0.36f)
    val radius = 22.dp
    val tapHeight = 28.dp
    val barHeight = 44.dp
    val inset = 12.dp
    val gap = 8.dp
}

@Composable
fun ChampagneButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .background(Theme.champagne, RoundedCornerShape(percent = 50))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = Color.Black.copy(alpha = 0.86f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
fun QuietTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = Theme.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
