package vela.app.stage

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.viewinterop.AndroidView
import vela.app.R
import vela.app.ui.theme.ChampagneButton
import vela.app.ui.theme.QuietTextButton
import vela.app.ui.theme.Theme
import vela.core.base.net.UserAgent

/**
 * 站点质询页。网页只用来完成确认，画面仍回播放器。
 * [url] 由调用方给：生产是站点根，测试必须是伪上游，这里不自己改地址。
 */
@Composable
fun ChallengeSheet(
    url: String,
    onDone: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.playchain_challenge_title),
    body: String = stringResource(R.string.playchain_challenge_body),
    doneLabel: String = stringResource(R.string.playchain_challenge_done),
    closeLabel: String = stringResource(R.string.playchain_challenge_close),
) {
    Column(modifier.fillMaxSize().background(Theme.ink)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = title,
                    style = TextStyle(color = Theme.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.weight(1f))
                QuietTextButton(
                    text = closeLabel,
                    onClick = onClose,
                    modifier = Modifier.semantics { contentDescription = "challenge-close" },
                )
            }
            Spacer(Modifier.height(10.dp))
            BasicText(
                text = body,
                style = TextStyle(color = Theme.textSecondary, fontSize = 12.sp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            var progress by remember { mutableFloatStateOf(0f) }
            AndroidView(
                modifier = Modifier.fillMaxSize().testTag("challenge-web"),
                factory = { context ->
                    challengeWebView(context, url) { progress = it }
                },
                update = { view ->
                    if (view.url != url && view.tag != url) {
                        view.tag = url
                        view.loadUrl(url)
                    }
                },
            )
            if (progress > 0f && progress < 1f) {
                Box(
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .align(Alignment.TopStart)
                        .height(2.dp)
                        .fillMaxWidth(progress)
                        .background(Theme.champagne),
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            ChampagneButton(
                text = doneLabel,
                onClick = onDone,
                modifier = Modifier.semantics { contentDescription = "challenge-done" },
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun challengeWebView(
    context: android.content.Context,
    url: String,
    onProgress: (Float) -> Unit,
): WebView {
    val view = WebView(context)
    view.setBackgroundColor(AndroidColor.rgb(11, 11, 13))
    view.contentDescription = "challenge-web"
    view.tag = url
    view.settings.javaScriptEnabled = true
    view.settings.domStorageEnabled = true
    view.settings.mediaPlaybackRequiresUserGesture = true
    view.settings.userAgentString = UserAgent.safari
    view.webViewClient = WebViewClient()
    view.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgress(newProgress / 100f)
        }
    }
    view.layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )
    view.loadUrl(url)
    return view
}

/** 质询页用的深色底，避免网页还没到时闪出纯白。 */
internal val challengeBackdrop: Color = Theme.ink
