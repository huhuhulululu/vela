package vela.core.base.play

/**
 * `RelayPolicy.defaultTargetDuration` 的独立落点。
 * 回退包 R1 的 `RelayPolicy` 引用这里，不要在 L0b 之外改这个文件。
 */
object RelayDefaults {
    /** 列表里没写 TARGETDURATION 时的兜底（本流实测为 2 秒）。 */
    const val defaultTargetDuration: Double = 2.0
}
