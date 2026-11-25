package vela.app.lane

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import vela.core.hls.PhoneThermalState

/**
 * 把系统热状态读成 [PhoneThermalState]。API 29 以下恒为 nominal（计划第 2 节）。
 * 映射按 ADR-A3：MODERATE → serious，SEVERE 及以上 → critical；LIGHT → fair，NONE → nominal。
 * 不在这里降档，降档交给 [vela.core.hls.LaneQuality]。
 */
class ThermalMonitor(
    private val onState: (PhoneThermalState) -> Unit,
    private val readStatus: () -> Int = { THERMAL_NONE },
) {
    private var listening: PowerManager? = null

    /** 监听对象只在 API 29 上创建，字段用 [Any] 免得低版本引用新接口。 */
    private var listener: Any? = null

    /** 读一次当前状态并回调。29 以下只报 nominal。 */
    fun sample() {
        if (Build.VERSION.SDK_INT < 29) {
            onState(PhoneThermalState.NOMINAL)
            return
        }
        onState(map(readStatus()))
    }

    /**
     * 订阅系统热状态。重复调用无效果。没有 [PowerManager] 时只采样一次 nominal。
     * 监听在主线程回调，和舞台的主线程协程一致。
     */
    fun start(context: Context) {
        if (Build.VERSION.SDK_INT < 29) {
            onState(PhoneThermalState.NOMINAL)
            return
        }
        startListening(context)
    }

    fun stop() {
        val manager = listening
        listening = null
        if (Build.VERSION.SDK_INT < 29 || manager == null) {
            listener = null
            return
        }
        stopListening(manager)
    }

    @RequiresApi(29)
    private fun startListening(context: Context) {
        if (listener != null) return
        val manager = context.getSystemService(PowerManager::class.java)
        if (manager == null) {
            onState(PhoneThermalState.NOMINAL)
            return
        }
        val status = try {
            manager.currentThermalStatus
        } catch (error: RuntimeException) {
            Log.w(TAG, "thermal status unavailable")
            onState(PhoneThermalState.NOMINAL)
            return
        }
        onState(map(status))
        val created = PowerManager.OnThermalStatusChangedListener { next ->
            onState(map(next))
        }
        try {
            manager.addThermalStatusListener(mainExecutor(), created)
        } catch (error: RuntimeException) {
            // 模拟器或测试桩可能拒绝注册。已经采过一次，舞台仍可用。
            Log.w(TAG, "thermal listener unavailable")
            return
        }
        listening = manager
        listener = created
    }

    @RequiresApi(29)
    private fun stopListening(manager: PowerManager) {
        val current = listener as? PowerManager.OnThermalStatusChangedListener
        listener = null
        if (current != null) manager.removeThermalStatusListener(current)
    }

    companion object {
        const val THERMAL_NONE: Int = 0
        const val THERMAL_LIGHT: Int = 1
        const val THERMAL_MODERATE: Int = 2
        const val THERMAL_SEVERE: Int = 3
        const val THERMAL_CRITICAL: Int = 4
        const val THERMAL_EMERGENCY: Int = 5
        const val THERMAL_SHUTDOWN: Int = 6

        /**
         * 系统档位 → 舞台档位。没见过的更高档位按 critical：宁可降档。
         * 数值与 API 29 `PowerManager.THERMAL_STATUS_*` 一致。
         */
        fun map(status: Int): PhoneThermalState = when (status) {
            THERMAL_NONE -> PhoneThermalState.NOMINAL
            THERMAL_LIGHT -> PhoneThermalState.FAIR
            THERMAL_MODERATE -> PhoneThermalState.SERIOUS
            THERMAL_SEVERE, THERMAL_CRITICAL, THERMAL_EMERGENCY, THERMAL_SHUTDOWN ->
                PhoneThermalState.CRITICAL
            else -> if (status > THERMAL_MODERATE) PhoneThermalState.CRITICAL else PhoneThermalState.NOMINAL
        }

        private const val TAG: String = "vela.thermal"

        private fun mainExecutor(): Executor {
            val handler = Handler(Looper.getMainLooper())
            return Executor { command -> handler.post(command) }
        }
    }
}
