package vela.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vela.app.infra.AtomicFile
import vela.app.infra.HttpClientImpl
import vela.app.infra.Settings
import vela.app.lane.LaneSurface
import vela.app.stage.AppPlaybackSession
import vela.app.stage.LifecycleWiring
import vela.app.stage.StageIntent
import vela.app.stage.StageViewModel
import vela.app.stage.challengeEntryUrl
import vela.app.ui.StageScreen
import vela.core.base.model.Prefs
import vela.core.lifecycle.PhoneScenePhase

/**
 * 单窗入口。前后台交给 [LifecycleWiring]：没有画中画时回桌面按
 * [vela.core.lifecycle.PhoneLifecycle] 全停，回前台按冻结裁决 7 恢复。
 * 画中画（B2 之后的 PiP1）再改这个文件。
 *
 * debug / perf 会多编 src/hooks。若存在 [vela.app.hooks.Hooks.install]，启动时调用。
 * 同一套 hooks 里的 [vela.app.hooks.StageHost] 可以让单路仪器测试只拿空宿主，
 * 不要把完整舞台一起拉起来。release 没有这些类，反射找不到就按真实启动走。
 */
class MainActivity : ComponentActivity() {
    lateinit var stage: StageViewModel
        private set

    lateinit var wiring: LifecycleWiring
        private set

    lateinit var session: AppPlaybackSession
        private set

    private var composeView: ComposeView? = null
    private var composed: Boolean = false
    private var nextExportId: Long = 1L
    private var pendingExport: ByteArray? = null
    private var pendingExportId: Long = 0L
    private var pendingExportUri: String? = null
    private var pendingUriId: Long = 0L

    private val createFavorites = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        onFavoriteExportResult(uri)
    }

    private val openFavorites = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFavoritesFrom(uri)
    }

    internal fun launchFavoriteImport() {
        openFavorites.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    internal fun onFavoriteExportResult(uri: Uri?) {
        if (uri == null) {
            cancelWaitingExport()
            return
        }
        val bytes = pendingExport ?: AtomicFile.read(exportSnapshot(), limit = Prefs.maxFileBytes)
        val id = when {
            pendingExport != null && pendingExportId != 0L -> pendingExportId
            else -> readExportId(exportIdFile()) ?: 0L
        }
        if (bytes == null || id == 0L) {
            cancelWaitingExport()
            notifyTransferFailure(exporting = true)
            return
        }
        // 写入完成前保留快照和 URI。选择器返回后、IO 还没开始时重建，新 Activity 才能接着写。
        pendingExport = bytes
        pendingExportId = id
        rememberExportUri(uri)
        try {
            AtomicFile.write(exportSnapshot(), bytes)
            AtomicFile.write(exportIdFile(), id.toString().toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
        }
        beginExportWrite()
    }

    internal fun importFavoritesFrom(uri: Uri) {
        if (!this::stage.isInitialized) return
        val stage = this.stage
        lifecycleScope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { readLimited(it, Prefs.maxFileBytes + 1) }
                        ?: throw IOException("unavailable")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (bytes == null) {
                notifyTransferFailure(exporting = false)
                return@launch
            }
            stage.dispatch(StageIntent.ImportFavorites(bytes))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            deleteExportFiles()
        } else {
            // 完成时磁盘待办已删。旧 Bundle 里的字节和地址不能把这次导出再做一遍。
            restorePendingExportFromDisk()
        }
        installDebugHooks(applicationContext)
        if (!debugAllowsStageBoot()) return
        val directory = Paths.get(filesDir.absolutePath, "Vela")
        val stage = StageViewModel(
            settings = Settings(this),
            http = HttpClientImpl(),
            directory = directory,
            context = this,
        )
        val session = AppPlaybackSession(
            activity = this,
            stage = stage,
            disposeComposition = { disposeStage() },
            restoreComposition = { showStage() },
        )
        val wiring = LifecycleWiring(stage, session)
        this.stage = stage
        this.session = session
        this.wiring = wiring
        stage.onQuit = { finish() }
        stage.bindLifecycleHooks(
            ensurePlayback = { session.ensureLane() },
            onMuteChanged = { muted -> session.onMuteChanged(muted) },
        )
        lifecycleScope.launch {
            for (bytes in stage.favoriteExports) {
                stashExport(bytes)
                createFavorites.launch("vela-favorites.json")
            }
        }
        showStage()
        val host = FrameLayout(this)
        addContentView(host, ViewGroup.LayoutParams(1, 1))
        session.webHost = host
        if (pendingExportUri != null) {
            if (pendingExport != null) {
                beginExportWrite()
            } else {
                clearPendingExport()
                notifyTransferFailure(exporting = true)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val bytes = pendingExport ?: return
        outState.putBoolean(EXPORT_PENDING, true)
        if (bytes.size <= EXPORT_INLINE_LIMIT) outState.putByteArray(EXPORT_BYTES, bytes)
        if (pendingUriId == pendingExportId) {
            pendingExportUri?.let { outState.putString(EXPORT_URI, it) }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!this::wiring.isInitialized) return
        wiring.moveTo(PhoneScenePhase.INACTIVE)
    }

    override fun onResume() {
        super.onResume()
        if (!this::wiring.isInitialized) return
        wiring.moveTo(PhoneScenePhase.ACTIVE)
    }

    override fun onPause() {
        super.onPause()
        if (!this::wiring.isInitialized) return
        wiring.moveTo(PhoneScenePhase.INACTIVE)
    }

    override fun onStop() {
        super.onStop()
        if (!this::wiring.isInitialized) return
        wiring.moveTo(PhoneScenePhase.BACKGROUND)
    }

    override fun onDestroy() {
        if (this::wiring.isInitialized) {
            wiring.moveTo(PhoneScenePhase.BACKGROUND)
            session.releaseLeftovers()
            stage.close()
        }
        super.onDestroy()
    }

    private fun showStage() {
        val view = composeView ?: ComposeView(this).also { created ->
            created.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            composeView = created
            setContentView(created)
        }
        val stage = this.stage
        val session = this.session
        view.setContent {
            val lane by session.lane.collectAsState()
            StageScreen(
                stage = stage,
                challengeUrl = challengeEntryUrl(),
                onImportFavorites = { launchFavoriteImport() },
            ) { _ ->
                val current = lane
                if (current != null) {
                    LaneSurface(lane = current, thumbnail = null, modifier = Modifier.fillMaxSize())
                }
            }
        }
        composed = true
    }

    private fun disposeStage() {
        if (!composed) return
        composeView?.disposeComposition()
        composed = false
    }

    private fun stashExport(bytes: ByteArray) {
        val id = nextExportId++
        pendingExport = bytes
        pendingExportId = id
        try {
            AtomicFile.write(exportSnapshot(), bytes)
            AtomicFile.write(exportIdFile(), id.toString().toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
        }
    }

    private fun beginExportWrite() {
        val id = pendingExportId
        val uriText = pendingExportUri ?: return
        if (id == 0L || pendingUriId != id) return
        val bytes = pendingExport ?: return
        val uri = Uri.parse(uriText)
        lifecycleScope.launch {
            val ok = runDocument {
                val stream = contentResolver.openOutputStream(uri) ?: throw IOException("unavailable")
                stream.use { it.write(bytes) }
            }
            if (isDestroyed) return@launch
            finishExport(id, ok)
        }
    }

    private fun finishExport(id: Long, ok: Boolean) {
        if (pendingExportId == id) {
            clearPendingExport()
        } else if (pendingUriId == id) {
            pendingExportUri = null
            pendingUriId = 0L
            exportUriFile().delete()
            exportUriIdFile().delete()
        }
        if (!ok) notifyTransferFailure(exporting = true)
    }

    private fun rememberExportUri(uri: Uri) {
        val text = uri.toString()
        val id = pendingExportId
        pendingExportUri = text
        pendingUriId = id
        try {
            AtomicFile.write(exportUriFile(), text.toByteArray(Charsets.UTF_8))
            AtomicFile.write(exportUriIdFile(), id.toString().toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
        }
    }

    private fun cancelWaitingExport() {
        if (pendingUriId != 0L && pendingUriId != pendingExportId) {
            pendingExport = null
            pendingExportId = 0L
            exportSnapshot().delete()
            exportIdFile().delete()
            return
        }
        clearPendingExport()
    }

    private fun clearPendingExport() {
        pendingExport = null
        pendingExportId = 0L
        pendingExportUri = null
        pendingUriId = 0L
        deleteExportFiles()
    }

    private fun restorePendingExportFromDisk() {
        val bytes = AtomicFile.read(exportSnapshot(), limit = Prefs.maxFileBytes) ?: return
        val id = readExportId(exportIdFile()) ?: return
        pendingExport = bytes
        pendingExportId = id
        if (nextExportId <= id) nextExportId = id + 1
        val uriId = readExportId(exportUriIdFile()) ?: return
        val uri = AtomicFile.read(exportUriFile(), limit = 4_096)?.toString(Charsets.UTF_8) ?: return
        if (uri.isBlank() || uriId != id) return
        pendingExportUri = uri
        pendingUriId = uriId
    }

    private fun readExportId(file: File): Long? {
        val raw = AtomicFile.read(file, limit = 64) ?: return null
        return raw.toString(Charsets.UTF_8).trim().toLongOrNull()
    }

    private fun exportSnapshot(): File = AtomicFile.resolve(filesDir, "pending-favorite-export")

    private fun exportUriFile(): File = AtomicFile.resolve(filesDir, "pending-favorite-export-uri")

    private fun exportIdFile(): File = AtomicFile.resolve(filesDir, "pending-favorite-export-id")

    private fun exportUriIdFile(): File = AtomicFile.resolve(filesDir, "pending-favorite-export-uri-id")

    private fun deleteExportFiles() {
        exportSnapshot().delete()
        exportUriFile().delete()
        exportIdFile().delete()
        exportUriIdFile().delete()
    }

    private suspend fun runDocument(block: () -> Unit): Boolean =
        try {
            withContext(Dispatchers.IO) { block() }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private fun notifyTransferFailure(exporting: Boolean) {
        if (!this::stage.isInitialized) return
        val message = getString(if (exporting) R.string.ui_export_failed else R.string.ui_import_failed)
        stage.showNotice(message)
    }
}

internal fun installDebugHooks(context: Context) {
    try {
        val cls = Class.forName("vela.app.hooks.Hooks")
        val install = cls.getDeclaredMethod("install", Context::class.java)
        install.invoke(null, context)
    } catch (_: ClassNotFoundException) {
        // release，或 hooks 源码集还没人放类。
    }
}

/** 导入文件只读到上限加一字节，多出来的交给解码报「文件太大」，不把整个文件拉进内存。 */
internal fun readLimited(input: InputStream, limit: Int): ByteArray {
    if (limit <= 0) return ByteArray(0)
    val buf = ByteArray(limit)
    var off = 0
    while (off < limit) {
        val n = input.read(buf, off, limit - off)
        if (n < 0) break
        off += n
    }
    return if (off == buf.size) buf else buf.copyOf(off)
}

private const val EXPORT_PENDING = "vela.pendingExport"
private const val EXPORT_BYTES = "vela.pendingExportBytes"
private const val EXPORT_URI = "vela.pendingExportUri"
private const val EXPORT_INLINE_LIMIT = 400_000

/** release 没有 StageHost，真实启动一律起舞台。hooks 自己出错也放行，避免把正式路径停掉。 */
internal fun debugAllowsStageBoot(): Boolean {
    return try {
        val cls = Class.forName("vela.app.hooks.StageHost")
        val method = cls.getDeclaredMethod("shouldBootStage")
        method.invoke(null) as? Boolean ?: true
    } catch (_: ClassNotFoundException) {
        true
    } catch (_: NoSuchMethodException) {
        true
    } catch (_: Throwable) {
        true
    }
}
