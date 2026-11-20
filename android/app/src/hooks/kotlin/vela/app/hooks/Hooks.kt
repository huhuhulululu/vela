package vela.app.hooks

import android.content.Context
import vela.app.infra.Probe

/**
 * debug / perf 启动时由 MainActivity 反射调用。必须是静态方法，release 没有这个类。
 */
object Hooks {
    @JvmStatic
    fun install(context: Context) {
        context.applicationContext
        Probe.install(PerfProbe)
        Endpoints.installFromBuildConfig()
    }
}
