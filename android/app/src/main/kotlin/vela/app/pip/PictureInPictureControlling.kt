package vela.app.pip

/**
 * 生命周期对画中画控制器的最小需求。测试用桩替换系统控制器。
 */
interface PictureInPictureControlling {
    /** 自动进入开着，且系统此刻允许进入画中画。 */
    val canAutoEnter: Boolean

    /** 设置里的「回桌面自动进入画中画」，改动立即落到系统参数上。 */
    var autoEnter: Boolean

    /** 请求退出画中画。 */
    fun stop()

    /**
     * 启动中止：回退全停时调用。系统还没真正进入时 stop 可能是空操作，
     * 这里要把协调器状态清干净，之后能重新进入。
     */
    fun abort() {
        stop()
    }

    /** 用当前焦点路画面比例进入画中画（按钮或 onUserLeaveHint）。 */
    fun enter() {}
}
