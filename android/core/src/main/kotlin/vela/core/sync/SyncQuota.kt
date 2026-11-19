package vela.core.sync

/**
 * 本机专用（同步域外）「不想看」的 50 个额度（第 6.6 节、第 23 节第 12 条）。
 * 纯函数：每次同步处于开启都按当前本机口味重算，不靠一次性记账。
 */
object SyncQuota {
    fun localOnlyExcess(vetoedKeys: Set<String>): List<String> {
        val localOnly = vetoedKeys.filter { SyncKeys.syncVetoKey(it) == null }
        return SyncDocument.sortedKeys(localOnly).drop(SyncLimits.localOnlyVetoes)
    }
}
