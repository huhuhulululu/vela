package vela.app.pip

/** 画中画控制器回报给生命周期的事件。 */
sealed class PiPEvent {
    data object WillStart : PiPEvent()
    data object DidStart : PiPEvent()
    data class Failed(val message: String) : PiPEvent()
    data object WillStop : PiPEvent()
    data object DidStop : PiPEvent()
    data object RestoreRequested : PiPEvent()
}
