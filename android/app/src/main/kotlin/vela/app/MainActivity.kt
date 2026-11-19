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
import java.io.InputStream
import java.nio.file.Paths
import kotlinx.coroutines.launch
import vela.app.infra.HttpClientImpl
import vela.app.infra.Settings
import vela.app.lane.LaneSurface
import vela.app.stage.AppPlaybackSession
import vela.app.stage.LifecycleWiring
import vela.app.stage.StageIntent
import vela.app.stage.StageViewModel
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
    private var pendingExport: ByteArray? = null

    private val createFavorites = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri == null || bytes == null) return@registerForActivityResult
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }

    private val openFavorites = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFavoritesFrom(uri)
    }

    internal fun launchFavoriteImport() {
        openFavorites.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    internal fun importFavoritesFrom(uri: Uri) {
        if (!this::stage.isInitialized) return
        val bytes = contentResolver.openInputStream(uri)?.use { readLimited(it, Prefs.maxFileBytes + 1) } ?: return
        stage.dispatch(StageIntent.ImportFavorites(bytes))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        stage.bindLifecycleHooks(
            ensurePlayback = { session.ensureLane() },
            onMuteChanged = { muted -> session.onMuteChanged(muted) },
        )
        lifecycleScope.launch {
            for (bytes in stage.favoriteExports) {
                pendingExport = bytes
                createFavorites.launch("vela-favorites.json")
            }
        }
        showStage()
        val host = FrameLayout(this)
        addContentView(host, ViewGroup.LayoutParams(1, 1))
        session.webHost = host
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
            StageScreen(stage = stage, onImportFavorites = { launchFavoriteImport() }) { _ ->
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
