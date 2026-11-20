package vela.app.infra

/**
 * 性能探针。main 里的默认实现什么都不记；debug / perf 的 hooks 在启动时换成记录器。
 * release 编不进 hooks，所以一直停在 [ProbeNop]。
 */
interface Probe {
    fun onRenderedFirstFrame(lane: String, wallTimeMs: Long) {}

    companion object {
        @Volatile
        var current: Probe = ProbeNop
            private set

        fun install(probe: Probe) {
            current = probe
        }
    }
}

/** 默认空实现。调用方可以照常打点，release 里不会留下记录。 */
object ProbeNop : Probe
