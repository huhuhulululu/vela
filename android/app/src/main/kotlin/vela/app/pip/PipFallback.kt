package vela.app.pip

import vela.core.lifecycle.PhoneScenePhase
import vela.core.lifecycle.PiPPolicy

/**
 * 回桌面后 3 秒内没进入画中画（只有 [PiPPolicy.State.ACTIVE] 算进入）则全停。
 */
object PipFallback {
    const val TIMEOUT_MS: Long = 3_000L

    fun shouldStop(phase: PhoneScenePhase, state: PiPPolicy.State): Boolean =
        phase == PhoneScenePhase.BACKGROUND && state != PiPPolicy.State.ACTIVE
}