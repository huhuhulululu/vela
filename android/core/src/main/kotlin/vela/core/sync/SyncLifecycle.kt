package vela.core.sync

import vela.core.curation.Curation
import vela.core.taste.Taste

sealed class SyncKeychainRead {
    data object None : SyncKeychainRead()
    data class Present(val blobID: String) : SyncKeychainRead()
    data object Unavailable : SyncKeychainRead()
    data object Invalidated : SyncKeychainRead()
}

interface SyncKeychain {
    fun read(): SyncKeychainRead
    fun delete(): Boolean
}

interface SyncLocalSink {
    fun cancelPendingSaves()
    fun currentTaste(): Taste
    fun saveCuration(curation: Curation): Boolean
    fun saveTaste(taste: Taste): Boolean
    fun applySettings(settings: Map<String, Boolean>)
}

sealed class SyncStartupAction {
    data object NotEnabled : SyncStartupAction()
    /** 无状态、有条目：提交在写状态之前中断。删除条目，未开启。 */
    data object OrphanEntry : SyncStartupAction()
    data object DiscardCreating : SyncStartupAction()
    data object ResumeCreating : SyncStartupAction()
    data object KeyLost : SyncStartupAction()
    data object Normal : SyncStartupAction()
    data object FinishDisabling : SyncStartupAction()
    data object FinishClearing : SyncStartupAction()
    data object FinishClearingLocally : SyncStartupAction()
    data object Mismatch : SyncStartupAction()
    data object Skip : SyncStartupAction()
    data object StateCorrupted : SyncStartupAction()
}

sealed class SyncFlowResult {
    data object Done : SyncFlowResult()
    data object LocalSaveFailed : SyncFlowResult()
    data object StorageFailed : SyncFlowResult()
    data object RemotePending : SyncFlowResult()
    data object RemoteRejected : SyncFlowResult()
}

object SyncLifecycle {
    /**
     * 第 12.1 节表格。钥匙串暂时不可用时不做判断。
     * 第 23 节：`disabling` 优先于 blobID 不一致。
     */
    fun startupAction(state: SyncStateRead, keychain: SyncKeychainRead): SyncStartupAction {
        if (keychain == SyncKeychainRead.Unavailable) return SyncStartupAction.Skip
        val entry = (keychain as? SyncKeychainRead.Present)?.blobID
        val s = when (state) {
            SyncStateRead.None -> return if (entry == null) SyncStartupAction.NotEnabled else SyncStartupAction.OrphanEntry
            SyncStateRead.Corrupted -> return SyncStartupAction.StateCorrupted
            is SyncStateRead.Present -> state.state
        }
        if (s.phase == SyncPhase.DISABLING) return SyncStartupAction.FinishDisabling
        if (entry != null && entry != s.blobID) return SyncStartupAction.Mismatch
        val has = entry != null
        return when (s.phase) {
            SyncPhase.CREATING -> if (has) SyncStartupAction.ResumeCreating else SyncStartupAction.DiscardCreating
            SyncPhase.ACTIVE -> if (has) SyncStartupAction.Normal else SyncStartupAction.KeyLost
            SyncPhase.CLEARING -> if (has) SyncStartupAction.FinishClearing else SyncStartupAction.FinishClearingLocally
            SyncPhase.DISABLING -> SyncStartupAction.FinishDisabling
        }
    }

    fun barrier(doc: SyncDocument, sink: SyncLocalSink): Boolean {
        sink.cancelPendingSaves()
        val projection = SyncProjection(doc)
        val taste = projection.applying(sink.currentTaste())
        if (!sink.saveCuration(projection.curation)) return false
        if (!sink.saveTaste(taste)) return false
        sink.applySettings(projection.settings)
        return true
    }

    fun disable(state: SyncState, store: SyncStateStore, keychain: SyncKeychain, sink: SyncLocalSink): SyncFlowResult {
        if (!barrier(state.doc, sink)) return SyncFlowResult.LocalSaveFailed
        val next = state.copy(phase = SyncPhase.DISABLING)
        if (!store.save(next)) return SyncFlowResult.LocalSaveFailed
        return finishDisable(store, keychain)
    }

    fun finishDisable(store: SyncStateStore, keychain: SyncKeychain): SyncFlowResult {
        if (!keychain.delete()) return SyncFlowResult.StorageFailed
        if (!store.delete()) return SyncFlowResult.StorageFailed
        return SyncFlowResult.Done
    }

    suspend fun clear(
        state: SyncState,
        store: SyncStateStore,
        keychain: SyncKeychain,
        sink: SyncLocalSink,
        transport: SyncTransport,
    ): SyncFlowResult {
        if (!barrier(state.doc, sink)) return SyncFlowResult.LocalSaveFailed
        val next = state.copy(phase = SyncPhase.CLEARING)
        if (!store.save(next)) return SyncFlowResult.LocalSaveFailed
        return finishClear(next, store, keychain, transport)
    }

    suspend fun finishClear(
        state: SyncState,
        store: SyncStateStore,
        keychain: SyncKeychain,
        transport: SyncTransport,
    ): SyncFlowResult {
        var epoch = state.epoch
        var base = state.lastSeenVer
        // 还没有 epoch：不能证明空间不存在，先 GET。404 或 410 才本机关闭；空间存在就用它的 epoch 与版本签名删除。
        if (epoch == null) {
            when (val response = transport.get()) {
                is SyncServerResponse.NotFound, is SyncServerResponse.Gone -> {
                    val disabling = state.copy(phase = SyncPhase.DISABLING)
                    if (!store.save(disabling)) return SyncFlowResult.StorageFailed
                    return finishDisable(store, keychain)
                }
                is SyncServerResponse.Blob -> {
                    epoch = response.epoch
                    base = response.ver
                }
                is SyncServerResponse.Conflict -> {
                    epoch = response.epoch
                    base = response.ver
                }
                else -> return SyncFlowResult.RemotePending
            }
        }
        for (i in 0..SyncCoordinator.CONFLICT_RETRIES) {
            when (val response = transport.delete(base, epoch)) {
                SyncServerResponse.Deleted, is SyncServerResponse.Gone, is SyncServerResponse.NotFound -> {
                    val disabling = state.copy(phase = SyncPhase.DISABLING)
                    if (!store.save(disabling)) return SyncFlowResult.StorageFailed
                    return finishDisable(store, keychain)
                }
                is SyncServerResponse.Conflict -> base = response.ver
                SyncServerResponse.Forbidden -> return SyncFlowResult.RemoteRejected
                else -> return SyncFlowResult.RemotePending
            }
        }
        return SyncFlowResult.RemotePending
    }

    suspend fun recover(
        store: SyncStateStore,
        keychain: SyncKeychain,
        sink: SyncLocalSink,
        transport: SyncTransport,
    ): Pair<SyncStartupAction, SyncFlowResult?> {
        val read = store.load()
        val action = startupAction(read, keychain.read())
        if (action == SyncStartupAction.OrphanEntry) {
            return action to if (keychain.delete()) SyncFlowResult.Done else SyncFlowResult.StorageFailed
        }
        val state = (read as? SyncStateRead.Present)?.state ?: return action to null
        return when (action) {
            SyncStartupAction.DiscardCreating ->
                action to if (store.delete()) SyncFlowResult.Done else SyncFlowResult.StorageFailed
            SyncStartupAction.FinishDisabling -> if (!barrier(state.doc, sink)) {
                action to SyncFlowResult.LocalSaveFailed
            } else {
                action to finishDisable(store, keychain)
            }
            SyncStartupAction.FinishClearing -> if (!barrier(state.doc, sink)) {
                action to SyncFlowResult.LocalSaveFailed
            } else {
                action to finishClear(state, store, keychain, transport)
            }
            SyncStartupAction.FinishClearingLocally -> if (!barrier(state.doc, sink)) {
                action to SyncFlowResult.LocalSaveFailed
            } else {
                val disabling = state.copy(phase = SyncPhase.DISABLING)
                if (!store.save(disabling)) action to SyncFlowResult.StorageFailed
                else action to finishDisable(store, keychain)
            }
            else -> action to null
        }
    }

    fun completeCreate(state: SyncState, local: SyncLocalData, pt: Long): Pair<SyncState, SyncMigrationReport> {
        val next = state.copy()
        var report = SyncMigrationReport()
        if (!state.migrated) {
            val stamped = SyncMigration.stampCreator(local, state.clock, pt)
            next.replica = stamped.first
            next.oversize = !stamped.third
            next.migrated = true
            next.gen += 1
            report = stamped.second
        }
        next.phase = SyncPhase.ACTIVE
        return next to report
    }
}
