/// 同步协议里写死的上限（docs/plans/sync.md 第 5.2、6.6、6.7 节）。
public enum SyncLimits {
    /// 明文 JSON 字节上限 `P`，按压缩前计量。
    public static let plaintextBytes = 600_000
    public static let favorites = 300
    public static let blocked = 300
    public static let roomTombstones = 1_000
    public static let activeVetoes = 450
    public static let vetoTombstones = 500
    /// 同步开启时本机专用（同步域外）「不想看」的额度。
    public static let localOnlyVetoes = 50
    /// 导入的固定窗口大小。
    public static let importWindow = 300
    /// 本机墙钟与服务端 `now` 的最大允许偏差，也是重新盖章的阈值（毫秒）。
    public static let clockSkewMillis: UInt64 = 3_600_000
    /// 当前 schema 版本。
    public static let schema = 2
}
