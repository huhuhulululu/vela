package vela.app.ui

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import vela.app.R
import vela.app.ui.theme.ChampagneButton
import vela.app.ui.theme.Theme

/**
 * 站点质询页。没有地址时不创建 WebView，测试就不会去访问真站。
 * 调用方把地址传进来（本地伪服务或稍后由 A7 接上）；确认和关闭都只发意图。
 */
@Composable
fun ChallengeSheet(
    onDone: () -> Unit,
    onClose: () -> Unit,
    pageUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    SheetFrame(dismissOnScrim = false, onDismiss = onClose, modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = stringResource(R.string.ui_challenge_title),
                    style = TextStyle(color = Theme.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                TextAction(
                    text = stringResource(R.string.ui_close),
                    onClick = onClose,
                    modifier = Modifier.testTag("challenge-close"),
                    color = Theme.textSecondary,
                )
            }
            BasicText(
                text = stringResource(R.string.ui_challenge_body),
                style = TextStyle(color = Theme.textSecondary, fontSize = 13.sp),
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (pageUrl != null) {
                    ChallengeWeb(url = pageUrl, modifier = Modifier.fillMaxSize().testTag("challenge-web"))
                }
            }
            Box(Modifier.padding(top = 12.dp).align(Alignment.CenterHorizontally)) {
                ChampagneButton(
                    text = stringResource(R.string.ui_challenge_done),
                    onClick = onDone,
                    modifier = Modifier.testTag("challenge-done"),
                )
            }
        }
    }
}

@Composable
private fun ChallengeWeb(url: String, modifier: Modifier = Modifier) {
    var progress by remember { mutableFloatStateOf(0f) }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    configureChallengeWeb(this, url) { progress = it }
                    tag = url
                    loadUrl(url)
                }
            },
            update = { view ->
                if (view.tag != url) {
                    view.tag = url
                    view.loadUrl(url)
                }
            },
            onRelease = { view -> view.destroy() },
        )
        if (progress > 0f && progress < 1f) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(Theme.champagne),
            )
        }
    }
}
