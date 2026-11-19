package vela.core.sync

sealed class SyncCycleResult {
    data object UpToDate : SyncCycleResult()
    data object Pushed : SyncCycleResult()
    data object Created : SyncCycleResult()
    data class Halted(val halt: SyncHalt) : SyncCycleResult()
    data object ReadOnly : SyncCycleResult()
    data object InvalidRemote : SyncCycleResult()
    data object DecryptFailed : SyncCycleResult()
    data object ClockSkew : SyncCycleResult()
    data object Oversize : SyncCycleResult()
    data object Transient : SyncCycleResult()
    data class RateLimited(val retryAfter: Int) : SyncCycleResult()
    data object QuotaFull : SyncCycleResult()
    data object PayloadTooLarge : SyncCycleResult()
    data object ConflictLimit : SyncCycleResult()
    data object Cancelled : SyncCycleResult()
    data object StorageFailed : SyncCycleResult()
}

/**
 * 每个空间一个的串行协调器（第 11.2 节）。调用方保证同一时刻只有一个周期在跑。
 * 合并用的是请求返回时内存里的当前状态。
 */
class SyncCoordinator(
    var state: SyncState,
    val store: SyncStateStore,
    val transport: SyncTransport,
    val codec: SyncEnvelopeCodec,
    val wallClock: () -> Long,
    val sizeLimit: Int = SyncLimits.plaintextBytes,
) {
    var onDocumentChanged: ((SyncDocument) -> Unit)? = null
    private var token = 0

    fun cancel() {
        token += 1
    }

    data class LocalResult(val outcome: SyncOpOutcome, val saved: Boolean)

    fun perform(op: SyncLocalOp, localOnlyVetoCount: Int = 0): LocalResult {
        val outcome = SyncOps.apply(op, state.replica, wallClock(), localOnlyVetoCount, sizeLimit)
        val applied = outcome as? SyncOpOutcome.Applied ?: return LocalResult(outcome, true)
        state.replica = applied.replica
        state.oversize = false
        state.gen += 1
        val saved = store.save(state)
        onDocumentChanged?.invoke(state.doc)
        return LocalResult(outcome, saved)
    }

    suspend fun runCycle(): SyncCycleResult {
        state.halt?.let { return SyncCycleResult.Halted(it) }
        val myToken = token
        var response = transport.get()
        if (token != myToken) return SyncCycleResult.Cancelled
        var conflicts = 0
        var countedDecryptFailure = false
        while (true) {
            val remote: Triple<Long, SyncDocument, Long>
            when (val r = response) {
                is SyncServerResponse.Blob -> when (val accepted = accept(r.ver, r.pub, r.data, r.epoch, countedDecryptFailure)) {
                    is Accepted.Ok -> {
                        countedDecryptFailure = accepted.counted
                        remote = Triple(r.ver, accepted.doc, r.now)
                    }
                    is Accepted.Bad -> return accepted.result
                }
                is SyncServerResponse.Conflict -> when (val accepted = accept(r.ver, r.pub, r.data, r.epoch, countedDecryptFailure)) {
                    is Accepted.Ok -> {
                        countedDecryptFailure = accepted.counted
                        remote = Triple(r.ver, accepted.doc, r.now)
                    }
                    is Accepted.Bad -> return accepted.result
                }
                is SyncServerResponse.NotFound -> {
                    if (state.lastSeenVer > 0 || state.inflight != null) return halt(SyncHalt.Missing)
                    if (state.epoch != null && state.epoch != r.epoch) return halt(SyncHalt.EpochChanged)
                    state.epoch = r.epoch
                    remote = Triple(0L, SyncDocument.empty(), r.now)
                }
                else -> return terminal(response)
            }
            mergeRemote(remote.second)
            if (!store.save(state)) return SyncCycleResult.StorageFailed
            if (remote.first > 0 && state.doc == remote.second && state.gen == state.pushedGen) return success(SyncCycleResult.UpToDate)
            if (state.oversize) return SyncCycleResult.Oversize
            val wall = wallClock()
            val skew = if (wall > remote.third) wall - remote.third else remote.third - wall
            if (skew > SyncLimits.clockSkewMillis) return SyncCycleResult.ClockSkew
            val replica = state.replica
            if (SyncRestamp.presend(replica, remote.third, state.maxSentL, wall) is SyncRestamp.Result.Restamped) {
                state.replica = replica
                onDocumentChanged?.invoke(state.doc)
            }
            val envelope = codec.seal(state.doc.encoded(), remote.first + 1) ?: return SyncCycleResult.Transient
            val hash = codec.digest(envelope)
            val request = SyncPutRequest(remote.first, remote.first + 1, envelope, state.epoch)
            state.inflight = SyncInflight(request.base, request.next, hash, state.gen)
            state.maxSentL = maxOf(state.maxSentL, state.doc.allRegisters.maxOfOrNull { it.hlc.l } ?: 0L)
            if (!store.save(state)) return SyncCycleResult.StorageFailed
            response = transport.put(request)
            if (token != myToken) return SyncCycleResult.Cancelled
            when (val put = response) {
                is SyncServerResponse.Stored -> {
                    if (state.epoch != null && state.epoch != put.epoch) return halt(SyncHalt.EpochChanged)
                    // 200 回的版本必须恰为请求的 next；不符则保留 inflight，下一周期再核对。
                    if (put.ver != request.next) return SyncCycleResult.Transient
                    state.lastSeenVer = put.ver
                    state.lastSeenHash = hash
                    state.pushedGen = maxOf(state.pushedGen, state.inflight?.sentGen ?: 0L)
                    state.inflight = null
                    if (!store.save(state)) return SyncCycleResult.StorageFailed
                    return success(if (request.base == 0L) SyncCycleResult.Created else SyncCycleResult.Pushed)
                }
                is SyncServerResponse.Conflict -> {
                    conflicts += 1
                    if (conflicts > CONFLICT_RETRIES) return SyncCycleResult.ConflictLimit
                    continue
                }
                is SyncServerResponse.NotFound -> return halt(SyncHalt.Missing)
                else -> return terminal(response)
            }
        }
    }

    private sealed class Accepted {
        class Ok(val doc: SyncDocument, val counted: Boolean) : Accepted()
        class Bad(val result: SyncCycleResult) : Accepted()
    }

    private fun accept(ver: Long, pub: String, data: String, epoch: String, countedDecryptFailure: Boolean): Accepted {
        var counted = countedDecryptFailure
        if (state.epoch != null && state.epoch != epoch) return Accepted.Bad(halt(SyncHalt.EpochChanged))
        val hash = codec.digest(data)
        if (ver < state.lastSeenVer || (ver == state.lastSeenVer && state.lastSeenHash != null && state.lastSeenHash != hash)) {
            return Accepted.Bad(halt(SyncHalt.Rollback))
        }
        if (pub != codec.publicKey) return Accepted.Bad(halt(SyncHalt.PubMismatch))
        val plaintext = when (val opened = codec.open(data, ver)) {
            is SyncOpenResult.Plaintext -> opened.bytes
            SyncOpenResult.Unsupported -> return Accepted.Bad(SyncCycleResult.ReadOnly)
            SyncOpenResult.Failed -> {
                if (!counted) {
                    counted = true
                    state.decryptFailures += 1
                }
                if (state.decryptFailures >= 2) return Accepted.Bad(halt(SyncHalt.DecryptFailed))
                store.save(state)
                return Accepted.Bad(SyncCycleResult.DecryptFailed)
            }
        }
        val doc = when (val decoded = SyncDocument.decode(plaintext)) {
            is SyncDecodeResult.Valid -> decoded.doc
            SyncDecodeResult.NewerSchema -> return Accepted.Bad(SyncCycleResult.ReadOnly)
            is SyncDecodeResult.Invalid -> {
                // 只记类别（冒号前），不把房间键、标签等文档内容写进同步状态。
                val category = decoded.reason.substringBefore(':')
                state.lastFailure = "invalid:$category"
                store.save(state)
                return Accepted.Bad(SyncCycleResult.InvalidRemote)
            }
        }
        state.decryptFailures = 0
        state.epoch = epoch
        state.lastSeenVer = ver
        state.lastSeenHash = hash
        val inflight = state.inflight
        if (inflight != null) {
            if (ver == inflight.next && hash == inflight.envelopeHash) {
                state.pushedGen = maxOf(state.pushedGen, inflight.sentGen)
            }
            state.inflight = null
        }
        if (!store.save(state)) return Accepted.Bad(SyncCycleResult.StorageFailed)
        return Accepted.Ok(doc, counted)
    }

    private fun mergeRemote(remote: SyncDocument) {
        val fitted = SyncMerge.fitSize(SyncMerge.union(state.doc, remote), sizeLimit)
        val changed = fitted.doc != state.doc
        state.doc = fitted.doc
        state.oversize = !fitted.fits
        state.clock.receive(remote.maxHlc)
        if (changed) onDocumentChanged?.invoke(state.doc)
    }

    private fun success(result: SyncCycleResult): SyncCycleResult {
        state.lastSuccessAt = wallClock()
        state.lastFailure = null
        store.save(state)
        return result
    }

    private fun halt(reason: SyncHalt): SyncCycleResult {
        state.halt = reason
        store.save(state)
        return SyncCycleResult.Halted(reason)
    }

    private fun terminal(response: SyncServerResponse): SyncCycleResult = when (response) {
        is SyncServerResponse.Gone -> halt(SyncHalt.Gone(response.deletedAt))
        SyncServerResponse.Forbidden -> halt(SyncHalt.Forbidden)
        is SyncServerResponse.RateLimited -> SyncCycleResult.RateLimited(response.retryAfter)
        SyncServerResponse.QuotaFull -> SyncCycleResult.QuotaFull
        SyncServerResponse.PayloadTooLarge -> SyncCycleResult.PayloadTooLarge
        else -> SyncCycleResult.Transient
    }

    companion object {
        const val CONFLICT_RETRIES = 3
    }
}
