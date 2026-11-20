package vela.core.base.model

/** `Preferences` 的常量。旧结构体和迁移不移植。 */
object Prefs {
    const val halfLife: Double = 3.0 * 86_400
    const val maxWeights: Int = 500
    const val maxSeen: Int = 1_000
    const val seenRetention: Double = 24.0 * 3_600
    const val likeAfter: Double = 20.0
    const val skipBelow: Double = 3.0
    const val relatedAfter: Double = 30.0

    /** Swift 里是模块内可见。每间房参与口味过滤的标签数。 */
    const val maxTagsPerRoom: Int = 8

    /** 单个标签 / 用户名的最长字符数。 */
    const val maxNameLength: Int = 64

    /** 偏好文件读取上限：超过就当作空偏好，不解析。 */
    const val maxFileBytes: Int = 1_000_000
}

/**
 * `Taste.tagKey` / `genderKey` 的叶子实现。C6a 的 `Taste` 转调这里。
 * `tagKey` 先截断到 [Prefs.maxNameLength] 再小写，与 `Taste.tagKey` 一致。
 */
object TasteKeys {
    fun tagKey(tag: String): String = "t:" + tag.take(Prefs.maxNameLength).lowerRoot()

    fun genderKey(gender: Gender): String = "g:" + gender.wire
}

/** `Curation.key`。C7 的 `Curation` 转调这里。 */
object CurationKeys {
    fun key(name: String): String = name.trimSwiftWhitespace().lowerRoot()
}
