package vela.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/**
 * 脚手架入口。界面从后续任务包长出来；本文件只按串行重叠表改（00、A7、B2、PiP1）。
 *
 * debug / perf 会多编 src/hooks。若存在 [vela.app.hooks.Hooks.install]，启动时调用。
 * release 没有这个类，反射找不到就跳过。A1 的约定：
 *
 *     object Hooks {
 *         @JvmStatic fun install(context: Context) { ... }
 *     }
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installDebugHooks(applicationContext)
        setContent {
            Box(modifier = Modifier.fillMaxSize())
        }
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
