import Foundation
#if SWIFT_PACKAGE
import VelaCore
#endif

/// 本机专用（同步域外）「不想看」的 50 个额度（docs/plans/sync.md 第 6.6 节）。
enum SyncQuota {
    /// 超出额度、要在本机取消的键：同步域外的键按 UTF-8 字节升序保留前 50 个，其余返回。
    /// 纯函数，按当前本机数据算：每次同步开启（含启动时已开启）都重算一次，中断后重启也会落实。
    static func localOnlyExcess(vetoedKeys: Set<String>) -> [String] {
        let localOnly = vetoedKeys.filter { SyncKeys.syncVetoKey(localKey: $0) == nil }
            .sorted { Array($0.utf8).lexicographicallyPrecedes(Array($1.utf8)) }
        return Array(localOnly.dropFirst(SyncLimits.localOnlyVetoes))
    }
}
