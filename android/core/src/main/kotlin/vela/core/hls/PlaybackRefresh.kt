package vela.core.hls

/**
 * 已请求外面重新解析、新项到来前不接着播旧项。
 * 每条结束路径都要清掉它，否则旧项会被永远挡住。
 */
class RefreshTracker {
    var pending: Boolean = false
        private set

    fun begin() {
        pending = true
    }

    /** 新播放项起播或播放器停止。 */
    fun clear() {
        pending = false
    }

    /** 仍在待刷新时清掉并返回文案；已经结束则返回 null，不重复报。 */
    fun fail(reason: String?): String? {
        if (!pending) return null
        pending = false
        return reason ?: "画面没能刷新"
    }

    companion object {
        /** 代次多走了不止一步，这次结果不归自己。 */
        fun ownsOutcome(taskGeneration: Int, currentGeneration: Int): Boolean =
            currentGeneration <= taskGeneration + 1
    }
}

/** 恢复时只清播放器自己带来的失败文案，连续跳过、解析失败不动。 */
object PlaybackNoteRule {
    fun clearOnRecovery(note: String?, playerNote: String?): Boolean {
        if (note == null || playerNote == null) return false
        return note == playerNote
    }
}

/** 翻页完成后是否还要补拉：列表仍无可见房间，且这次列表代次没有变。 */
object LoadFollowUp {
    fun needsFill(tokenUnchanged: Boolean, visibleRooms: Int): Boolean =
        tokenUnchanged && visibleRooms == 0
}
