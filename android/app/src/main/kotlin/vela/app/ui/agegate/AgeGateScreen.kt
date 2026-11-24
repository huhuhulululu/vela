package vela.app.ui.agegate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vela.app.R
import vela.app.ui.theme.ChampagneButton
import vela.app.ui.theme.QuietTextButton
import vela.app.ui.theme.Theme

/**
 * 首次打开的年龄确认。点「已满 18 岁」才回调 [onEnter]；只是显示这一页不会改设置。
 * 外壳（何时弹出、退出时结束 Activity）由后面的界面包接上。
 */
@Composable
fun AgeGateScreen(
    onEnter: () -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Theme.ink)
            .padding(horizontal = 36.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        BasicText(
            text = stringResource(R.string.age_gate_brand),
            style = TextStyle(
                color = Theme.champagne,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 7.sp,
            ),
        )
        BasicText(
            text = stringResource(R.string.age_gate_title),
            style = TextStyle(
                color = Theme.textPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.8).sp,
            ),
        )
        BasicText(
            text = stringResource(R.string.age_gate_body),
            style = TextStyle(
                color = Theme.textSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        )
        Row(
            modifier = Modifier.padding(top = Theme.gap),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ChampagneButton(text = stringResource(R.string.age_gate_enter), onClick = onEnter)
            QuietTextButton(text = stringResource(R.string.age_gate_quit), onClick = onQuit)
        }
    }
}
