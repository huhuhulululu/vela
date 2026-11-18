package vela.app.hooks

import vela.app.infra.Probe

/** 记录首帧墙钟时刻。只编进 debug / perf。 */
object PerfProbe : Probe {
    data class FirstFrame(val lane: String, val wallTimeMs: Long)

    private val lock = Any()
    private val frames = ArrayList<FirstFrame>()

    override fun onRenderedFirstFrame(lane: String, wallTimeMs: Long) {
        synchronized(lock) {
            frames.add(FirstFrame(lane, wallTimeMs))
        }
    }

    fun firstFrames(): List<FirstFrame> = synchronized(lock) { frames.toList() }

    fun reset() {
        synchronized(lock) { frames.clear() }
    }
}
