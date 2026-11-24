package vela.app.sync

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import vela.app.ui.TextAction
import vela.app.ui.theme.Theme
import vela.core.sync.SyncScheduler

/**
 * 复制同步码时附带敏感标记（docs/plans/sync.md 第 4.3 节）：
 * Android 13+ 系统据此遮盖剪贴板浮层内容，避免明文同步码暴露在预览或历史里。
 */
object SyncClipboard {
    const val EXTRA_IS_SENSITIVE_KEY: String = "android.content.extra.IS_SENSITIVE"

    fun copySensitive(context: Context, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText("sync-code", text)
        val key = if (Build.VERSION.SDK_INT >= 33) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            EXTRA_IS_SENSITIVE_KEY
        }
        clip.description.extras = PersistableBundle().apply { putBoolean(key, true) }
        manager.setPrimaryClip(clip)
    }
}

private enum class SyncPanelMode { HOME, ENABLING, JOINING }

/**
 * 设置页底部的「跨设备同步」段落（对齐 iOS `PhoneSyncSection` 与 `SyncPanel.swift`）：
 * - 仅当 `ui.visible` 为真时渲染；minSdk < 26、探针 DISABLED 或未配置服务端地址时完全隐藏。
 * - 实验开关默认关闭，关闭时不渲染同步操作按钮、不触发同步。
 * - 探针分级为 `JOIN_ONLY` 时隐藏「在这台设备上开启同步」与 404「仍用此码创建」。
 */
@Composable
fun SyncSettingsSection(
    hub: SyncHub,
    modifier: Modifier = Modifier,
    onRequestReveal: ((onGranted: () -> Unit) -> Unit)? = null,
) {
    val ui by hub.uiState.collectAsState()
    if (!ui.visible) return

    Column(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.12f)),
        )
        BasicText(
            text = "跨设备同步",
            style = TextStyle(color = Theme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        )
        TextAction(
            text = if (ui.experimentEnabled) "实验：跨设备同步（已开启）" else "实验：跨设备同步（已关闭）",
            onClick = { hub.setExperiment(!ui.experimentEnabled) },
            modifier = Modifier.testTag("sync-experiment-toggle"),
            color = if (ui.experimentEnabled) Theme.champagne else Theme.textSecondary,
        )
        if (!ui.experimentEnabled) {
            SyncNote("实验功能，默认关闭。关闭时不显示同步入口、不联网、不读取系统密钥库。")
        } else {
            SyncPanelBody(hub = hub, ui = ui, onRequestReveal = onRequestReveal)
        }
        ui.notice?.let { notice ->
            TextAction(
                text = notice,
                onClick = { hub.dismissNotice() },
                modifier = Modifier.testTag("sync-notice"),
                color = Theme.live,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncPanelBody(
    hub: SyncHub,
    ui: SyncUiState,
    onRequestReveal: ((onGranted: () -> Unit) -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(SyncPanelMode.HOME) }
    var codeInput by remember { mutableStateOf("") }
    var enableLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var enableError by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf<String?>(null) }
    var showPrivacy by remember { mutableStateOf(false) }

    DisposableEffect(hub) {
        hub.trigger(SyncScheduler.Trigger.PANEL)
        onDispose {
            revealed = null
            hub.cancelJoin()
        }
    }

    val saved = ui.savedCode
    if (saved != null) {
        SyncCodeCard(
            title = "保存你的同步码",
            code = saved,
            fingerprint = ui.fingerprint,
            note = "同步码等于全部权限，丢了无法找回。在其他设备上选「我已有同步码」并粘贴它。",
            doneLabel = "我已保存",
            onCopy = { SyncClipboard.copySensitive(context, saved) },
            onDone = { hub.dismissSavedCode() },
        )
        return
    }

    val revealedCode = revealed
    if (revealedCode != null) {
        SyncCodeCard(
            title = "查看同步码",
            code = revealedCode,
            fingerprint = ui.fingerprint,
            note = "不要截图分享，不要发给别人。",
            doneLabel = "关闭",
            onCopy = { SyncClipboard.copySensitive(context, revealedCode) },
            onDone = { revealed = null },
        )
        return
    }

    when (val phase = ui.phase) {
        SyncUiPhase.Loading -> SyncNote("正在读取同步状态…")
        SyncUiPhase.Unsupported -> SyncNote("当前设备不支持同步。")
        SyncUiPhase.Unavailable -> {
            SyncNote("暂时无法读取同步密钥或连不上服务器，回到前台时会自动再试。")
            TextAction("重试", { hub.syncNow() }, Modifier.testTag("sync-retry"))
        }
        is SyncUiPhase.Attention -> {
            if (mode == SyncPanelMode.JOINING) {
                SyncJoinFlow(
                    hub = hub,
                    ui = ui,
                    codeInput = codeInput,
                    onCodeInputChange = { codeInput = it },
                    onLeave = {
                        codeInput = ""
                        hub.cancelJoin()
                        mode = SyncPanelMode.HOME
                    },
                )
            } else {
                SyncNote(phase.message, Theme.live)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextAction("关闭同步", { hub.disable() }, Modifier.testTag("sync-disable"), Theme.live)
                    TextAction("输入同步码", { mode = SyncPanelMode.JOINING }, Modifier.testTag("sync-join-entry"))
                }
            }
        }
        SyncUiPhase.Off -> {
            if (ui.joinStage is SyncUiJoinStage.Preview || mode == SyncPanelMode.JOINING) {
                SyncJoinFlow(
                    hub = hub,
                    ui = ui,
                    codeInput = codeInput,
                    onCodeInputChange = { codeInput = it },
                    onLeave = {
                        codeInput = ""
                        hub.cancelJoin()
                        mode = SyncPanelMode.HOME
                    },
                )
            } else if (mode == SyncPanelMode.ENABLING) {
                val err = enableError
                if (err != null) {
                    SyncNote(err, Theme.live)
                    TextAction("返回", { mode = SyncPanelMode.HOME }, color = Theme.textSecondary)
                } else {
                    SyncNote("开启同步", Theme.textPrimary)
                    SyncStatusText.privacyPoints.forEach { SyncNote("· $it") }
                    enableLines.forEach { SyncNote(it, Theme.champagne) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextAction(
                            text = if (ui.busy) "正在开启…" else "同意并开启",
                            onClick = {
                                if (!ui.busy) {
                                    scope.launch {
                                        hub.enable()
                                        mode = SyncPanelMode.HOME
                                    }
                                }
                            },
                            modifier = Modifier.testTag("sync-enable-confirm"),
                        )
                        TextAction("取消", { mode = SyncPanelMode.HOME }, color = Theme.textSecondary)
                    }
                }
            } else {
                SyncNote("在多台设备之间同步收藏、屏蔽与「不想看」。内容在本机加密后才上传。")
                SyncNote("其他设备已开启过就选「我已有同步码」。")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.canCreate) {
                        TextAction(
                            text = "在这台设备上开启同步",
                            onClick = {
                                hub.enablePreview()
                                    .onSuccess { report ->
                                        enableLines = SyncStatusText.reportLines(report)
                                        enableError = null
                                    }
                                    .onFailure { err ->
                                        enableLines = emptyList()
                                        enableError = err.message ?: "无法开启同步"
                                    }
                                mode = SyncPanelMode.ENABLING
                            },
                            modifier = Modifier.testTag("sync-create-entry"),
                        )
                    }
                    TextAction(
                        text = "我已有同步码",
                        onClick = { mode = SyncPanelMode.JOINING },
                        modifier = Modifier.testTag("sync-join-entry"),
                    )
                }
                PrivacyBlock(showPrivacy) { showPrivacy = !showPrivacy }
            }
        }
        SyncUiPhase.Active -> {
            val fpText = ui.fingerprint?.let { "  ·  指纹 $it" } ?: ""
            SyncNote("同步已开启$fpText", Theme.textPrimary)
            val lastText = ui.lastSuccessAt?.let { ts ->
                "上次同步 " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE)
                    .format(Date(ts))
            } ?: "尚未同步成功"
            SyncNote(lastText + if (ui.hasPending) "，有待同步的改动" else "")
            ui.failure?.let { SyncNote(it, Theme.live) }
            if (confirmClear) {
                SyncNote("将删除云端数据，所有设备停止同步；各设备本地数据保留。", Theme.live)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextAction(
                        text = if (ui.busy) "正在清除…" else "确定清除",
                        onClick = {
                            if (!ui.busy) {
                                scope.launch {
                                    hub.clearCloud()
                                    confirmClear = false
                                }
                            }
                        },
                        modifier = Modifier.testTag("sync-clear-confirm"),
                        color = Theme.live,
                    )
                    TextAction("取消", { confirmClear = false }, color = Theme.textSecondary)
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextAction("立即同步", { hub.syncNow() }, Modifier.testTag("sync-now"))
                    TextAction(
                        text = "查看同步码",
                        onClick = {
                            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                            if (km?.isDeviceSecure != true) {
                                hub.showNotice("这台设备没有设置锁屏密码，无法查看同步码。")
                            } else if (onRequestReveal == null) {
                                hub.showNotice("当前无法验证设备身份，无法查看同步码。")
                            } else {
                                onRequestReveal { revealed = hub.revealCode() }
                            }
                        },
                        modifier = Modifier.testTag("sync-reveal-code"),
                    )
                    TextAction("关闭同步", { hub.disable() }, Modifier.testTag("sync-disable"), Theme.textSecondary)
                    TextAction("清除云端…", { confirmClear = true }, Modifier.testTag("sync-clear-prompt"), Theme.live)
                }
            }
            PrivacyBlock(showPrivacy) { showPrivacy = !showPrivacy }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncJoinFlow(
    hub: SyncHub,
    ui: SyncUiState,
    codeInput: String,
    onCodeInputChange: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    SyncNote("加入已有的同步", Theme.textPrimary)
    when (val stage = ui.joinStage) {
        is SyncUiJoinStage.Preview -> {
            val ticket = stage.ticket
            SyncNote("服务器：${ticket.host}    指纹：${ticket.fingerprint}")
            SyncStatusText.joinLines(ticket.preview, ticket.host).forEach { SyncNote(it) }
            SyncStatusText.reportLines(ticket.report).forEach { SyncNote(it, Theme.champagne) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction(
                    text = if (ui.busy) "正在加入…" else "确认加入",
                    onClick = {
                        if (!ui.busy) {
                            scope.launch {
                                hub.confirmJoin()
                                onCodeInputChange("")
                            }
                        }
                    },
                    modifier = Modifier.testTag("sync-join-confirm"),
                )
                TextAction("取消", onLeave, Modifier.testTag("sync-join-cancel"), Theme.textSecondary)
            }
        }
        is SyncUiJoinStage.NotFound -> {
            SyncNote("这个同步码没有对应的已有空间。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.canCreate) {
                    TextAction(
                        text = "仍用此码创建",
                        onClick = {
                            if (!ui.busy) {
                                scope.launch {
                                    hub.createFromJoin()
                                    onCodeInputChange("")
                                }
                            }
                        },
                        modifier = Modifier.testTag("sync-join-create-anyway"),
                    )
                }
                TextAction("取消", onLeave, Modifier.testTag("sync-join-cancel"), Theme.textSecondary)
            }
        }
        else -> {
            BasicTextField(
                value = codeInput,
                onValueChange = onCodeInputChange,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = TextStyle(color = Theme.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .testTag("sync-join-input"),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (codeInput.isEmpty()) {
                            BasicText("粘贴同步码", style = TextStyle(color = Theme.textSecondary, fontSize = 12.sp))
                        }
                        inner()
                    }
                },
            )
            if (stage is SyncUiJoinStage.Failed) {
                SyncNote(stage.message, Theme.live)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction(
                    text = if (ui.busy) "正在检查…" else "下一步",
                    onClick = {
                        if (!ui.busy && codeInput.isNotBlank()) {
                            val raw = codeInput
                            scope.launch { hub.prepareJoin(raw) }
                        }
                    },
                    modifier = Modifier.testTag("sync-join-next"),
                )
                TextAction("取消", onLeave, Modifier.testTag("sync-join-cancel"), Theme.textSecondary)
            }
        }
    }
}

@Composable
private fun SyncCodeCard(
    title: String,
    code: String,
    fingerprint: String?,
    note: String,
    doneLabel: String,
    onCopy: () -> Unit,
    onDone: () -> Unit,
) {
    var copied by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SyncNote(title + (fingerprint?.let { "  ·  指纹 $it" } ?: ""), Theme.textPrimary)
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            BasicText(
                text = code,
                style = TextStyle(color = Theme.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            )
        }
        SyncNote(note)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextAction(
                text = if (copied) "已复制" else "复制",
                onClick = {
                    onCopy()
                    copied = true
                },
                modifier = Modifier.testTag("sync-copy-code"),
            )
            TextAction(
                text = doneLabel,
                onClick = onDone,
                modifier = Modifier.testTag("sync-dismiss-saved"),
                color = Theme.textSecondary,
            )
        }
    }
}

@Composable
private fun PrivacyBlock(expanded: Boolean, onToggle: () -> Unit) {
    TextAction(
        text = if (expanded) "收起隐私说明" else "隐私说明",
        onClick = onToggle,
        modifier = Modifier.testTag("sync-privacy-toggle"),
        color = Theme.textSecondary,
    )
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SyncStatusText.privacyPoints.forEach { SyncNote("· $it") }
        }
    }
}

@Composable
private fun SyncNote(text: String, color: Color = Theme.textSecondary) {
    BasicText(text = text, style = TextStyle(color = color, fontSize = 11.sp))
}
