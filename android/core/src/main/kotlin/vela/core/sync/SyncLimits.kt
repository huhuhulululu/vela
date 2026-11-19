package vela.core.sync

/** 同步协议里写死的上限（docs/plans/sync.md 第 5.2、6.6、6.7 节）。 */
object SyncLimits {
    /** 明文 JSON 字节上限 `P`，按压缩前计量。 */
    const val plaintextBytes: Int = 600_000
    const val favorites: Int = 300
    const val blocked: Int = 300
    const val roomTombstones: Int = 1_000
    const val activeVetoes: Int = 450
    const val vetoTombstones: Int = 500
    /** 同步开启时本机专用（同步域外）「不想看」的额度。 */
    const val localOnlyVetoes: Int = 50
    /** 导入的固定窗口大小。 */
    const val importWindow: Int = 300
    /** 本机墙钟与服务端 `now` 的最大允许偏差，也是重新盖章的阈值（毫秒）。 */
    const val clockSkewMillis: Long = 3_600_000L
    /** 当前 schema 版本。 */
    const val schema: Int = 2
    /** P-256 的阶 n。 */
    val p256Order: java.math.BigInteger =
        java.math.BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
}
