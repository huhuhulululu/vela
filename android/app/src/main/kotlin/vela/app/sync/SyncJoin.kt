package vela.app.sync

import vela.core.sync.SyncClock
import vela.core.sync.SyncCode
import vela.core.sync.SyncCycleResult
import vela.core.sync.SyncDecodeResult
import vela.core.sync.SyncDerived
import vela.core.sync.SyncDocument
import vela.core.sync.SyncHalt
import vela.core.sync.SyncJoinPreview
import vela.core.sync.SyncLifecycle
import vela.core.sync.SyncLimits
import vela.core.sync.SyncLocalData
import vela.core.sync.SyncLocalSink
import vela.core.sync.SyncMigration
import vela.core.sync.SyncMigrationReport
import vela.core.sync.SyncOpenResult
import vela.core.sync.SyncPhase
import vela.core.sync.SyncPutRequest
import vela.core.sync.SyncReplica
import vela.core.sync.SyncServerResponse
import vela.core.sync.SyncState
import vela.core.sync.SyncStateRead
import vela.core.sync.decode
import vela.core.sync.deriveSyncKeys
import vela.core.sync.sha256

/**
 * 加入时用户要确认的一切（docs/plans/sync.md 第 12.4 节）。持有 `K` 与算好的合并结果，只在内存里；
 * 确认之前什么都不落盘，取消就是丢掉它。
 */
class SyncJoinTicket(
    val code: SyncCode,
    val keys: SyncDerived,
    val dev: Long,
    val remoteVer: Long,
    /** 已验证信封的哈希：409 重试时与新响应比较，版本与信封水位不得回退。 */
    val remoteHash: String,
    val remoteEpoch: String,
    val remoteNow: Long,
    val remote: SyncDocument,
    /** `merge(本机盖章结果, 远端)` 并做过体积处理的结果，连同盖章后的时钟。 */
    val replica: SyncReplica,
    val fits: Boolean,
    val preview: SyncJoinPreview,
    /** 迁移说明（不合规名字、超额将被取消的「不想看」），确认页列出。 */
    val report: SyncMigrationReport,
) {
    private val lock = Any()
    private var cancelledFlag = false
    private var finalizedFlag = false

    val host: String get() = code.host
    val fingerprint: String get() = keys.fingerprint

    /**
     * 用户取消、离开加入页、App 退出或被挂起：作废这张票。
     * 与提交的最后一步（写「已开启」）由同一把锁裁决：返回 false 表示提交已定局，取消不再生效。
     */
    fun cancel(): Boolean = synchronized(lock) {
        if (finalizedFlag) return false
        cancelledFlag = true
        true
    }

    val isCancelled: Boolean get() = synchronized(lock) { cancelledFlag }

    /** 提交写「已开启」之前调用：没被取消就定局，之后的取消不再生效。 */
    fun finalize(): Boolean = synchronized(lock) {
        if (cancelledFlag) return false
        finalizedFlag = true
        true
    }
}

sealed class SyncJoinPrepared {
    data object InvalidCode : SyncJoinPrepared()

    /** 404：这个同步码没有对应的已有空间；可「仍用此码创建」。 */
    data class NotFound(val code: SyncCode) : SyncJoinPrepared()

    /** 410：空间已被清除，不加入。 */
    data object Gone : SyncJoinPrepared()
    data class Preview(val ticket: SyncJoinTicket) : SyncJoinPrepared()

    /** 核对、解封、校验或网络失败：什么都没写。 */
    data class Failed(val message: String) : SyncJoinPrepared()
}

sealed class SyncJoinCommit {
    /** 已加入：云端已写入合并结果，本机状态、密钥库与投影都已落盘，同步开启。 */
    data object Joined : SyncJoinCommit()

    /** 取消或退出：未加入，本机原样。 */
    data object Cancelled : SyncJoinCommit()

    /** 提交时云端变了（409）且计数不同：回到确认页，什么都没写。 */
    data class Reconfirm(val ticket: SyncJoinTicket) : SyncJoinCommit()

    /** 未加入，本机原样；附界面文案。 */
    data class Failed(val message: String) : SyncJoinCommit()

    /** 回滚没有完成：快照留着，下次启动继续收尾；界面提示需要修复。 */
    data class NeedsRepair(val message: String) : SyncJoinCommit()
}

/**
 * 加入提交用的本机存储（docs/plans/sync.md 第 12.4 节、第 23 节第 14 条）：
 * 提交前把保存队列里待写的数据落盘并确认，回滚换回文件之后按文件重读内存。
 */
interface SyncJoinSink : SyncLocalSink {
    /** 写出保存队列里待写的值，再把内存里的当前收藏与口味直接写盘；都成功才返回 true。 */
    fun persistPending(): Boolean

    /** 快照换回之后：按磁盘重读收藏、口味与两个设置，内存、磁盘、保存队列三者一致。 */
    fun reloadAfterRollback()
}

private const val JOIN_CONFLICT_RETRIES = 3

private sealed class TicketResult {
    class Ok(val ticket: SyncJoinTicket) : TicketResult()
    class Err(val message: String) : TicketResult()
}

suspend fun SyncService.prepareJoin(text: String, local: SyncLocalData): SyncJoinPrepared {
    val code = SyncCode.parse(text, ::sha256) ?: return SyncJoinPrepared.InvalidCode
    val derived = try {
        deriveSyncKeys(code.key)
    } catch (_: Exception) {
        return SyncJoinPrepared.InvalidCode
    }
    val dev = randomDevice() ?: return SyncJoinPrepared.Failed("系统随机源不可用")
    val transport = env.makeTransport(code.origin, derived)
    return when (val response = transport.get()) {
        is SyncServerResponse.NotFound -> SyncJoinPrepared.NotFound(code)
        is SyncServerResponse.Gone -> SyncJoinPrepared.Gone
        is SyncServerResponse.Blob -> {
            when (
                val built = buildJoinTicket(
                    code = code,
                    keys = derived,
                    dev = dev,
                    ver = response.ver,
                    pub = response.pub,
                    data = response.data,
                    epoch = response.epoch,
                    now = response.now,
                    local = local,
                )
            ) {
                is TicketResult.Ok -> SyncJoinPrepared.Preview(built.ticket)
                is TicketResult.Err -> SyncJoinPrepared.Failed(built.message)
            }
        }
        else -> SyncJoinPrepared.Failed("网络不可用，稍后再试")
    }
}

private fun SyncService.buildJoinTicket(
    code: SyncCode,
    keys: SyncDerived,
    dev: Long,
    ver: Long,
    pub: String,
    data: String,
    epoch: String,
    now: Long,
    local: SyncLocalData,
): TicketResult {
    if (pub != keys.pubB64) return TicketResult.Err(SyncStatusText.halt(SyncHalt.PubMismatch))
    val codec = SyncCryptoCodec(keys, env.sizeLimit, env.random)
    val bytes = when (val opened = codec.open(data, ver)) {
        SyncOpenResult.Unsupported -> return TicketResult.Err("其他设备的 App 更新，请更新本机 App")
        SyncOpenResult.Failed -> return TicketResult.Err("无法解密云端数据")
        is SyncOpenResult.Plaintext -> opened.bytes
    }
    return when (val decoded = SyncDocument.decode(bytes)) {
        is SyncDecodeResult.Valid -> {
            val stamped = SyncMigration.stampJoin(local, decoded.doc, SyncClock(dev = dev), env.wallClock())
            TicketResult.Ok(
                SyncJoinTicket(
                    code = code,
                    keys = keys,
                    dev = dev,
                    remoteVer = ver,
                    remoteHash = codec.digest(data),
                    remoteEpoch = epoch,
                    remoteNow = now,
                    remote = decoded.doc,
                    replica = stamped.replica,
                    fits = stamped.fits,
                    preview = stamped.preview,
                    report = stamped.report,
                ),
            )
        }
        SyncDecodeResult.NewerSchema -> TicketResult.Err("其他设备的 App 更新，请更新本机 App")
        is SyncDecodeResult.Invalid -> TicketResult.Err("云端数据不合法")
    }
}

/**
 * 用户确认后提交（docs/plans/sync.md 第 12.4 节、第 23 节第 13 条）：
 * 1. 先上提交锁，期间本机写操作一律拒绝，且上锁后才读取最新本机数据；
 * 2. 云端 PUT 成功后，本机按「待写落盘 → 写前快照 → 密钥库 → 投影落盘屏障 → 状态（最后写）」顺序写入；
 * 3. 任一步失败或被取消则用快照原子换回、按文件重读内存并删密钥与状态；
 * 4. 回滚本身失败进入「需要修复」，保留快照并持续阻断本机写入。
 */
suspend fun SyncService.commitJoin(
    original: SyncJoinTicket,
    takeLocal: () -> SyncLocalData,
    sink: SyncJoinSink,
    holdLockAfterJoin: Boolean = false,
): SyncJoinCommit {
    if (original.isCancelled) return SyncJoinCommit.Cancelled
    if (isActive) return SyncJoinCommit.Failed("这台设备已开启同步，请先关闭")
    if (env.stateStore.load() != SyncStateRead.None || env.journal.exists) {
        return SyncJoinCommit.Failed("本机还有未清理的同步状态，请先关闭同步")
    }
    if (!beginCommit()) return SyncJoinCommit.Failed("正在加入同步…")
    val result = try {
        commitLocked(original, takeLocal, sink)
    } catch (t: Throwable) {
        endCommit()
        throw t
    }
    if (!(holdLockAfterJoin && result == SyncJoinCommit.Joined)) {
        endCommit()
    }
    return result
}

private suspend fun SyncService.commitLocked(
    original: SyncJoinTicket,
    takeLocal: () -> SyncLocalData,
    sink: SyncJoinSink,
): SyncJoinCommit {
    val local = takeLocal()
    var t = ticketFromRemote(original, local)
    if (t.preview != original.preview) {
        return if (original.isCancelled) SyncJoinCommit.Cancelled else SyncJoinCommit.Reconfirm(t)
    }
    if (!t.fits) {
        return SyncJoinCommit.Failed(SyncStatusText.cycle(SyncCycleResult.Oversize) ?: "同步数据已满")
    }
    val wall = env.wallClock()
    val skew = if (wall > t.remoteNow) wall - t.remoteNow else t.remoteNow - wall
    if (skew > SyncLimits.clockSkewMillis) {
        return SyncJoinCommit.Failed(SyncStatusText.cycle(SyncCycleResult.ClockSkew) ?: "本机时间不准")
    }
    val codec = SyncCryptoCodec(t.keys, env.sizeLimit, env.random)
    val transport = env.makeTransport(t.code.origin, t.keys)
    for (attempt in 0..JOIN_CONFLICT_RETRIES) {
        val next = t.remoteVer + 1
        val envelope = codec.seal(t.replica.doc.encoded(), next)
            ?: return SyncJoinCommit.Failed("系统随机源不可用，未加入")
        val response = transport.put(SyncPutRequest(t.remoteVer, next, envelope, t.remoteEpoch))
        if (original.isCancelled) return SyncJoinCommit.Cancelled
        when (response) {
            is SyncServerResponse.Stored -> {
                if (response.ver != next || response.epoch != t.remoteEpoch) {
                    return SyncJoinCommit.Failed("服务器响应异常，未加入")
                }
                return commitLocally(original, t, next, codec.digest(envelope), sink)
            }
            is SyncServerResponse.Conflict -> {
                if (response.epoch != t.remoteEpoch) {
                    return SyncJoinCommit.Failed(SyncStatusText.halt(SyncHalt.EpochChanged))
                }
                if (response.ver < t.remoteVer || (response.ver == t.remoteVer && codec.digest(response.data) != t.remoteHash)) {
                    return SyncJoinCommit.Failed(SyncStatusText.halt(SyncHalt.Rollback))
                }
                when (
                    val fresh = buildJoinTicket(
                        code = t.code,
                        keys = t.keys,
                        dev = t.dev,
                        ver = response.ver,
                        pub = response.pub,
                        data = response.data,
                        epoch = response.epoch,
                        now = response.now,
                        local = local,
                    )
                ) {
                    is TicketResult.Ok -> {
                        if (fresh.ticket.preview != t.preview) {
                            return if (original.isCancelled) SyncJoinCommit.Cancelled else SyncJoinCommit.Reconfirm(fresh.ticket)
                        }
                        t = fresh.ticket
                    }
                    is TicketResult.Err -> return SyncJoinCommit.Failed(fresh.message)
                }
            }
            is SyncServerResponse.Gone -> return SyncJoinCommit.Failed("这个同步码对应的空间已被清除，未加入")
            SyncServerResponse.Forbidden -> return SyncJoinCommit.Failed("云端拒绝了这台设备的写入，未加入")
            is SyncServerResponse.NotFound -> return SyncJoinCommit.Failed("云端同步空间不见了，未加入")
            is SyncServerResponse.RateLimited,
            SyncServerResponse.QuotaFull,
            SyncServerResponse.PayloadTooLarge,
            -> return SyncJoinCommit.Failed("服务器暂时拒绝写入，未加入，稍后再试")
            else -> return SyncJoinCommit.Failed("网络不可用，未加入，稍后再试")
        }
    }
    return SyncJoinCommit.Failed("云端一直在变，未加入，稍后再试")
}

private fun SyncService.ticketFromRemote(t: SyncJoinTicket, local: SyncLocalData): SyncJoinTicket {
    val stamped = SyncMigration.stampJoin(local, t.remote, SyncClock(dev = t.dev), env.wallClock())
    return SyncJoinTicket(
        code = t.code,
        keys = t.keys,
        dev = t.dev,
        remoteVer = t.remoteVer,
        remoteHash = t.remoteHash,
        remoteEpoch = t.remoteEpoch,
        remoteNow = t.remoteNow,
        remote = t.remote,
        replica = stamped.replica,
        fits = stamped.fits,
        preview = stamped.preview,
        report = stamped.report,
    )
}

private fun SyncService.commitLocally(
    original: SyncJoinTicket,
    t: SyncJoinTicket,
    ver: Long,
    envelopeHash: String,
    sink: SyncJoinSink,
): SyncJoinCommit {
    val failed = SyncJoinCommit.Failed("本机保存失败，未加入；本机数据保持原样")
    fun undo(outcome: SyncJoinCommit): SyncJoinCommit =
        if (rollbackJoin(sink)) outcome else SyncJoinCommit.NeedsRepair(SyncStatusText.joinRepair)

    if (original.isCancelled) return SyncJoinCommit.Cancelled
    // H1: 先把去抖保存队列里待写的最近编辑落盘并确认，快照才是提交这一刻的本机数据。
    if (!sink.persistPending()) return failed
    if (!env.journal.snapshot()) return failed
    if (env.keyStore.write(SyncKeyEntry(t.code.key, t.dev, t.keys.blobID)) != SyncKeyWrite.OK) {
        return undo(failed)
    }
    if (original.isCancelled) return undo(SyncJoinCommit.Cancelled)
    val state = SyncState(
        phase = SyncPhase.ACTIVE,
        origin = t.code.origin,
        blobID = t.keys.blobID,
        clock = t.replica.clock,
        doc = t.replica.doc,
        epoch = t.remoteEpoch,
        maxSentL = t.replica.doc.allRegisters.maxOfOrNull { it.hlc.l } ?: 0L,
        lastSeenVer = ver,
        lastSeenHash = envelopeHash,
        migrated = true,
    )
    if (!SyncLifecycle.barrier(state.doc, sink)) return undo(failed)
    // 定局：写「已开启」之前与取消由同一把锁裁决。
    if (!original.finalize()) return undo(SyncJoinCommit.Cancelled)
    // 「已开启」最后写。
    if (!env.stateStore.save(state)) return undo(failed)
    retireSnapshot()
    activate(state, t.keys)
    recordPendingDoc(state.doc)
    drainDocNotification()
    return SyncJoinCommit.Joined
}

/** 撤销未完成的加入：换回快照、删同步状态与密钥条目、按文件重读内存，最后删快照。全部成功才返回 true。 */
fun SyncService.rollbackJoin(sink: SyncLocalSink? = null): Boolean = when (env.journal.status) {
    SyncJoinJournalStatus.NONE -> true
    SyncJoinJournalStatus.COMMITTED, SyncJoinJournalStatus.INCOMPLETE -> env.journal.discard()
    SyncJoinJournalStatus.PENDING -> {
        if (!env.journal.restore() || !env.stateStore.delete() || !env.keyStore.delete()) {
            false
        } else {
            (sink as? SyncJoinSink)?.reloadAfterRollback()
            env.journal.discard()
        }
    }
}

/** 已定局的快照：标记已提交，再删除。删除成功才返回 true。 */
fun SyncService.retireSnapshot(): Boolean {
    env.journal.markCommitted()
    return env.journal.discard()
}

/** 关闭、清除之前处理残留快照（H2、H3）。 */
fun SyncService.settleJoinSnapshot(sink: SyncLocalSink?): Boolean = when (env.journal.status) {
    SyncJoinJournalStatus.NONE -> true
    SyncJoinJournalStatus.COMMITTED, SyncJoinJournalStatus.INCOMPLETE -> env.journal.discard()
    SyncJoinJournalStatus.PENDING -> when {
        isActive -> retireSnapshot()
        env.stateStore.load() == SyncStateRead.None -> rollbackJoin(sink)
        else -> retireSnapshot()
    }
}

/** 上次加入没收尾、回滚还没完成（需要修复）：未定局的快照在、同步没开启。 */
val SyncService.joinRepairPending: Boolean
    get() = !isActive && env.journal.status == SyncJoinJournalStatus.PENDING

val SyncService.isCommittingJoin: Boolean
    get() = synchronized(commitLock) { committing }

/** 本机收藏、屏蔽、口味与同步设置的写操作是否允许（提交中与待修复期间一律拒绝，不排队）。 */
val SyncService.localWriteAllowed: Boolean
    get() = !isCommittingJoin && !joinRepairPending

private fun SyncService.beginCommit(): Boolean = synchronized(commitLock) {
    if (committing) return false
    committing = true
    true
}

private fun SyncService.endCommit() = synchronized(commitLock) {
    committing = false
}

/** 磁盘上有同步状态（已开启、暂停、损坏等，不是「无」）：界面据此拒绝关闭实验开关（第 23 节第 17 条，H9）。 */
val SyncService.hasStoredState: Boolean
    get() = env.stateStore.load() != SyncStateRead.None

/** 释放提交锁（调用方要求保持时，在主线程完成激活之后调用；重复调用无害）。 */
fun SyncService.releaseJoinCommit() {
    endCommit()
}

/** 404 时「仍用此码创建」：等同于用这个 `K` 开启同步（第 12.3 节）。 */
suspend fun SyncService.createWithCode(code: SyncCode, local: SyncLocalData): SyncCreateResult =
    create(code.key, code.origin, local)
