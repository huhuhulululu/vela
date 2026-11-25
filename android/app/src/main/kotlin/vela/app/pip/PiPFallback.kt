package vela.app.pip

import vela.core.lifecycle.PhoneScenePhase
import vela.core.lifecycle.PiPPolicy

/**
 * 回桌面后的回退判定（与 iOS `PiPFallback` 对齐）：
 * 按「将自动进入」放行焦点路之后，到时仍不在后台画中画里就全停释放。
 * 只有 [PiPPolicy.State.ACTIVE] 算进入：一直卡在启动中也按未进入处理。
 */
object PiPFallback {
    const val TIMEOUT_SECONDS: Double = 3.0

    fun shouldStop(scene: PhoneScenePhase, state: PiPPolicy.State): Boolean {
        if (scene != PhoneScenePhase.BACKGROUND) return false
        return state != PiPPolicy.State.ACTIVE
    }
}
