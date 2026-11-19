package vela.core.hls

import java.time.Instant
import java.util.Locale
import vela.core.base.play.StreamRendition

/** 播放相关的纯判定：门控、暂停后怎么恢复、看门狗、停留计时。时长都是 `Double` 秒。 */
object PlaybackPolicy {
    const val startupTimeout: Double = 16.0
    const val liveRejoinAfter: Double = 15.0
    const val reresolveAfter: Double = 30.0
    const val releasePausedItemAfter: Double = reresolveAfter
    const val stuckAfter: Double = 10.0
    const val audioCheckDelay: Double = 2.0
    const val audioRecheckDelay: Double = 3.0
    const val bufferAdvanceEpsilon: Double = 0.1

    enum class ResumeAction {
        RESUME,
        REJOIN,
        RERESOLVE,
    }

    enum class AudioCheckStep {
        OK,
        RECHECK,
        FAIL,
    }

    enum class StuckAction {
        IGNORE,
        REARM,
        RECOVER,
        GIVE_UP,
    }

    fun allowed(
        windowVisible: Boolean,
        manuallyPaused: Boolean,
        bossHidden: Boolean,
        systemInactive: Boolean,
    ): Boolean = windowVisible && !manuallyPaused && !bossHidden && !systemInactive

    fun resumeAction(pausedFor: Double): ResumeAction {
        if (pausedFor <= liveRejoinAfter) return ResumeAction.RESUME
        return if (pausedFor < reresolveAfter) ResumeAction.REJOIN else ResumeAction.RERESOLVE
    }

    fun windowInvisible(
        windowVisible: Boolean,
        bossHidden: Boolean,
        systemInactive: Boolean,
        appHidden: Boolean,
    ): Boolean = !windowVisible || bossHidden || systemInactive || appHidden

    /** `hiddenFor` 为 null 表示窗口可见，永不释放。刚好等于阈值也释放。 */
    fun shouldReleasePausedItem(pausedFor: Double, hiddenFor: Double?): Boolean {
        val hidden = hiddenFor ?: return false
        return minOf(pausedFor, hidden) >= releasePausedItemAfter
    }

    fun startupStalled(
        sameItem: Boolean,
        allowed: Boolean,
        itemPlayed: Boolean,
        isPlaying: Boolean,
        refreshing: Boolean = false,
    ): Boolean = sameItem && allowed && !itemPlayed && !isPlaying && !refreshing

    fun audioCheckStep(enabledTracks: Int, isRecheck: Boolean): AudioCheckStep {
        if (enabledTracks > 0) return AudioCheckStep.OK
        return if (isRecheck) AudioCheckStep.FAIL else AudioCheckStep.RECHECK
    }

    fun stuckAction(
        sameItem: Boolean,
        allowed: Boolean,
        itemPlayed: Boolean,
        isPlaying: Boolean,
        refreshing: Boolean,
        retryAvailable: Boolean,
        bufferAdvancing: Boolean = false,
        renewing: Boolean = false,
    ): StuckAction {
        if (!(sameItem && allowed && itemPlayed && !isPlaying && !refreshing)) return StuckAction.IGNORE
        if (bufferAdvancing || renewing) return StuckAction.REARM
        return if (retryAvailable) StuckAction.RECOVER else StuckAction.GIVE_UP
    }

    /** 上次未知不算增长。末端至少前进 [bufferAdvanceEpsilon] 秒才算。 */
    fun bufferAdvanced(previousEnd: Double?, currentEnd: Double?): Boolean {
        if (previousEnd == null || currentEnd == null) return false
        return currentEnd - previousEnd >= bufferAdvanceEpsilon
    }

    fun relayShouldRefresh(allowed: Boolean, failed: Boolean): Boolean = allowed && !failed

    /** 房间名按根语言小写比较，避免默认语言把 `I` 变成 `ı`。 */
    fun dwellActive(allowed: Boolean, playing: String?, current: String?): Boolean {
        if (!allowed || playing == null || current == null) return false
        return playing.lowercase(Locale.ROOT) == current.lowercase(Locale.ROOT)
    }
}

/** 侧车空闲多久销毁。故意不跟重新解析窗口绑在一起，短于 403 / 质询冷却。 */
object SidecarIdlePolicy {
    const val seconds: Double = 30.0

    fun shouldTearDown(idleFor: Double): Boolean = idleFor >= seconds
}

/**
 * 预取缓存：已选好档位的子列表地址。主列表不存。
 * 有效期 20 秒，短于实测的最低闲置寿命。档位上限不同不能复用。
 */
class PreparedCache {
    private data class Entry(val rendition: StreamRendition, val cap: Double, val at: Instant)

    private val entries = mutableMapOf<String, Entry>()

    fun set(rendition: StreamRendition, cap: Double, username: String, now: Instant) {
        val expired = entries.filter { (_, entry) -> now.secondsSince(entry.at) >= maxAge }.keys
        expired.forEach { entries.remove(it) }
        entries[username] = Entry(rendition, cap, now)
    }

    fun fresh(username: String, cap: Double, now: Instant): StreamRendition? {
        val entry = entries[username] ?: return null
        if (entry.cap != cap) return null
        if (now.secondsSince(entry.at) >= maxAge) return null
        return entry.rendition
    }

    fun remove(username: String) {
        entries.remove(username)
    }

    companion object {
        const val maxAge: Double = 20.0
    }
}

/**
 * 双窗副路资源降级后的独立门控。普通闸门重算不会重新放行，
 * 只有显式重试、切房、回前台才清除。单窗不会降级。
 */
class DegradedHold {
    enum class Release {
        RETRY,
        ROOM_SWITCH,
        FOREGROUND_RESUME,
    }

    var held: Boolean = false
        private set

    fun degrade() {
        held = true
    }

    /** [reason] 必须由调用处写明。三个入口的效果相同。 */
    fun release(reason: Release) {
        if (reason == Release.RETRY || reason == Release.ROOM_SWITCH || reason == Release.FOREGROUND_RESUME) {
            held = false
        }
    }

    fun gate(allowed: Boolean): Boolean = allowed && !held
}
