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
    data object StateLost : SyncStartupAction()
    data object DiscardCreating : SyncStartupAction()
    data object ResumeCreating : SyncStartupAction()
    data object DiscardJoining : SyncStartupAction()
    data object ResumeJoining : SyncStartupAction()
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
            SyncStateRead.None -> return if (entry == null) SyncStartupAction.NotEnabled else SyncStartupAction.StateLost
            SyncStateRead.Corrupted -> return SyncStartupAction.StateCorrupted
            is SyncStateRead.Present -> state.state
        }
        if (s.phase == SyncPhase.DISABLING) return SyncStartupAction.FinishDisabling
        if (entry != null && entry != s.blobID) return SyncStartupAction.Mismatch
        val has = entry != null
        return when (s.phase) {
            SyncPhase.CREATING -> if (has) SyncStartupAction.ResumeCreating else SyncStartupAction.DiscardCreating
            SyncPhase.JOINING -> if (has) SyncStartupAction.ResumeJoining else SyncStartupAction.DiscardJoining
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
        var base = state.lastSeenVer
        for (i in 0..SyncCoordinator.CONFLICT_RETRIES) {
            when (val response = transport.delete(base, state.epoch)) {
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
        val state = (read as? SyncStateRead.Present)?.state ?: return action to null
        return when (action) {
            SyncStartupAction.DiscardCreating, SyncStartupAction.DiscardJoining ->
                action to if (store.delete()) SyncFlowResult.Done else SyncFlowResult.StorageFailed
            SyncStartupAction.ResumeJoining -> if (state.joinPlan == null) {
                action to finishDisable(store, keychain)
            } else {
                action to null
            }
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

    sealed class JoinRecovery {
        data object Proceed : JoinRecovery()
        data class Reconfirm(val preview: SyncJoinPreview) : JoinRecovery()
        data object Abort : JoinRecovery()
    }

    fun joinRecovery(plan: SyncJoinPlan?, remoteVer: Long, remoteHash: String, recomputed: SyncJoinPreview): JoinRecovery {
        if (plan == null) return JoinRecovery.Abort
        if (plan.ver == remoteVer && plan.envelopeHash == remoteHash) return JoinRecovery.Proceed
        return if (plan.preview.sameCounts(recomputed)) JoinRecovery.Proceed else JoinRecovery.Reconfirm(recomputed)
    }

    fun completeJoin(state: SyncState, local: SyncLocalData, remote: SyncDocument, pt: Long): SyncState {
        val next = state.copy()
        if (!state.migrated) {
            val stamped = SyncMigration.stampJoin(local, remote, state.clock, pt)
            next.replica = stamped.replica
            next.oversize = !stamped.fits
            next.migrated = true
            next.gen += 1
        }
        next.phase = SyncPhase.ACTIVE
        next.joinPlan = null
        return next
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
