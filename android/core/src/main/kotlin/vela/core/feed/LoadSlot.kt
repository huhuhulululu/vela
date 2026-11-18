package vela.core.feed

/**
 * 翻页请求的占位：按列表代次区分「同一代次已在加载」与「旧代次的残留请求」。
 * Swift 是值类型；这里是类，方法就地改同一实例。
 */
class LoadSlot {
    private var inflight: Int? = null

    /** 同一代次已有请求在飞返回 false；否则占位并返回 true。 */
    fun acquire(generation: Int): Boolean {
        if (inflight == generation) return false
        inflight = generation
        return true
    }

    /** 只释放自己占的位：旧代次收尾时不会清掉新代次的占位。 */
    fun release(generation: Int) {
        if (inflight == generation) inflight = null
    }
}

/** 换房时手动暂停的去留。只有用户自己的划动、按键、选择才清除。 */
object MoveRules {
    fun pausedAfterMove(manuallyPaused: Boolean, userInitiated: Boolean): Boolean =
        if (userInitiated) false else manuallyPaused
}

/**
 * 目录翻页游标。[committed] 只在成功加载时更新。
 * 重新加载先清零 [offset]，失败时用 [restart] 返回的快照 [restore]。
 */
class PagingState(offset: Int = 0) {
    var offset: Int = offset

    var committed: Int = offset
        private set

    fun commit(newOffset: Int) {
        offset = newOffset
        committed = newOffset
    }

    /** 游标清零，返回已加载快照的状态供失败时恢复。已提交的游标留在当前实例上。 */
    fun restart(): PagingState {
        val previous = PagingState(committed)
        offset = 0
        return previous
    }

    fun restore(previous: PagingState) {
        offset = previous.offset
        committed = previous.committed
    }

    override fun equals(other: Any?): Boolean =
        other is PagingState && offset == other.offset && committed == other.committed

    override fun hashCode(): Int = 31 * offset + committed
}

/** 播放器因没有可播房间而空闲。翻页完成后一旦出现可见房间就起播。 */
class IdleStart {
    var idle: Boolean = false
        private set

    fun markIdle() {
        idle = true
    }

    fun clear() {
        idle = false
    }

    /** 空闲且已有可播房间时返回 true 并消耗标记。 */
    fun takeStart(hasCurrent: Boolean): Boolean {
        if (!idle || !hasCurrent) return false
        idle = false
        return true
    }
}

/**
 * 冷启动闸门。bootstrap 的副作用只做一次；目录请求在年龄已确认时只发一次。
 * 未确认年龄时不领取，确认前保持不加载。
 */
class StartupGate {
    var bootstrapped: Boolean = false
        private set

    var catalogRequested: Boolean = false
        private set

    /** 第一次进入 bootstrap 返回 true，之后返回 false。 */
    fun enterBootstrap(): Boolean {
        if (bootstrapped) return false
        bootstrapped = true
        return true
    }

    /** 已确认年龄且还没发过目录请求时返回 true。未确认时不领取。 */
    fun claimCatalog(ageConfirmed: Boolean): Boolean {
        if (!ageConfirmed || catalogRequested) return false
        catalogRequested = true
        return true
    }
}
