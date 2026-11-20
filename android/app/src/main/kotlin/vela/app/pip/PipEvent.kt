package vela.app.pip

/** 系统画中画回调转成事件，语义对齐 iOS [PiPEvent]。 */
sealed interface PipEvent {
    data object WillStart : PipEvent
    data object DidStart : PipEvent
    data class Failed(val message: String) : PipEvent
    data object WillStop : PipEvent
    data object DidStop : PipEvent
    data object RestoreRequested : PipEvent
}