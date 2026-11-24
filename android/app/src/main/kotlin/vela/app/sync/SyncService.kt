package vela.app.sync

import java.util.concurrent.Semaphore
import kotlinx.coroutines.sync.Mutex
import vela.core.sync.SyncClock
import vela.core.sync.SyncCode
import vela.core.sync.SyncCoordinator
import vela.core.sync.SyncCycleResult
import vela.core.sync.SyncDerived
import vela.core.sync.SyncDocument
import vela.core.sync.SyncFlowResult
import vela.core.sync.SyncHalt
import vela.core.sync.SyncKeychainRead
import vela.core.sync.SyncLifecycle
import vela.core.sync.SyncLimits
import vela.core.sync.SyncLocalData
import vela.core.sync.SyncLocalOp
import vela.core.sync.SyncLocalSink
import vela.core.sync.SyncMigrationReport
import vela.core.sync.SyncPhase
import vela.core.sync.SyncPutRequest
import vela.core.sync.SyncServerResponse
import vela.core.sync.SyncStartupAction
import vela.core.sync.SyncState
import vela.core.sync.SyncStateRead
import vela.core.sync.SyncStateStore
import vela.core.sync.SyncTransport
import vela.core.sync.deriveSyncKeys
import vela.core.sync.sha256

sealed class SyncStartResult {
    data object NotEnabled : SyncStartResult()
    data object Active : SyncStartResult()

    /** 系统密钥库暂时不可用：本次跳过，稍后再试。 */
    data object Unavailable : SyncStartResult()

    /** 当前设备不支持同步（如 minSdk < 26 或探针禁用）。 */
    data object Unsupported : SyncStartResult()

    /** 需要用户处理的启动状态（第 12.1 节）。 */
    data class Attention(val action: SyncStartupAction) : SyncStartResult()
    data class Failed(val result: SyncFlowResult) : SyncStartResult()

    /** 上次加入提交没收尾、且换回快照失败：需要修复（不静默，不激活）。 */
    data class NeedsRepair(val message: String) : SyncStartResult()

    /** 停止同步、需要用户「关闭再重开」或重新输入同步码的情况（第 11.4 节）。 */
    data class Stopped(val message: String) : SyncStartResult()
}

sealed class SyncCreateResult {
    /** 已开启；`code` 只交给「保存你的同步码」页。首个周期的结果另给（可能是暂时故障，之后自动补推）。 */
    data class Created(
        val code: String,
        val fingerprint: String,
        val report: SyncMigrationReport,
        val firstCycle: SyncCycleResult,
    ) : SyncCreateResult()

    data class Failed(val message: String) : SyncCreateResult()
}

/**
 * 同步服务：把 S4 的状态机、屏障与 Android 加密、Keystore、传输接在一起（docs/plans/sync.md 第 11、12 节）。
 * 线程与锁序模型：
 * - 周期通过 `flight`（协程互斥）保证单飞，并与本地操作通过 `gate` 互斥；
 * - `GatedTransport` 在等网络时让出 `gate`，回来后重新拿回 `gate` 再合并；
 * - 协调器触发的文档变化绝不在持有 `gate` 时向外派发，而是先记入叶子锁 `pendingDocLock`，
 *   在释放 `gate` 后经 `docEventLock` 单调有序派发，避免回调内读 `state` 重入 `gate` 死锁。
 */
class SyncService(
    env: Environment,
) {
    data class Environment(
        val stateStore: SyncStateStore,
        val keyStore: SyncKeyStore,
        /** 由同步码里的 origin 与派生材料造传输；测试可指向 127.0.0.1 假服务端或脚本传输。 */
        val makeTransport: (origin: String, keys: SyncDerived) -> SyncTransport,
        /** 加入提交的本机文件快照（第 12.4 节）。 */
        val journal: SyncJoinJournal = NoJournal,
        val wallClock: () -> Long = { System.currentTimeMillis() },
        val random: (Int) -> ByteArray = SyncCryptoCodec.defaultRandom,
        val sizeLimit: Int = SyncLimits.plaintextBytes,
    )

    var env: Environment = env
        private set

    /** 本次启动换回过未完成加入的快照（界面据此重读本机数据并提示）。 */
    var rolledBackAtStartup: Boolean = false
        internal set

    fun configureJournal(journal: SyncJoinJournal) {
        env = env.copy(journal = journal)
    }

    @Volatile
    var coordinator: SyncCoordinator? = null
        private set

    @Volatile
    var keys: SyncDerived? = null
        private set

    @Volatile
    private var origin: String? = null

    private val gate = Semaphore(1)

    /** 周期单飞：一个周期从 GET 到最后一次落盘结束前，第二个周期异步挂起排队。 */
    private val flight = Mutex()

    /** 叶子锁：仅在 gate 内外记录最新待派发文档，绝不调用外部回调、绝不获取 gate。 */
    private val pendingDocLock = Any()
    private var pendingDocSeq: Long = 0L
    private var pendingDoc: SyncDocument? = null

    /** 仅在 gate 外获取：保证文档投影通知与后续状态读取严格串行、单调递增，不乱序覆盖。 */
    private val docEventLock = Any()
    private var deliveredDocSeq: Long = 0L

    internal val commitLock = Any()
    internal var committing: Boolean = false

    /** 文档变化（本地操作或合并）：总在释放 gate 后派发，回调内可安全读取 [state]。 */
    var onDocumentChanged: ((SyncDocument) -> Unit)? = null

    val isActive: Boolean get() = coordinator != null

    /** 当前状态的快照（界面显示用）。 */
    val state: SyncState? get() = locked { coordinator?.state?.deepCopy() }

    /** 完整同步码：只给「保存」与「查看同步码」两个专用页。 */
    fun currentCode(): String? {
        val currentOrigin = origin ?: return null
        val present = env.keyStore.readEntry() as? SyncKeyLookup.Present ?: return null
        return SyncCode.format(present.entry.key, currentOrigin, ::sha256)
    }

    private inline fun <T> locked(body: () -> T): T {
        gate.acquireUninterruptibly()
        return try {
            body()
        } finally {
            gate.release()
        }
    }

    internal fun recordPendingDoc(doc: SyncDocument) {
        synchronized(pendingDocLock) {
            pendingDocSeq += 1L
            pendingDoc = doc
        }
    }

    private fun clearPendingDoc() {
        synchronized(pendingDocLock) {
            pendingDoc = null
        }
    }

    internal fun drainDocNotification() {
        synchronized(docEventLock) {
            while (true) {
                val next = synchronized(pendingDocLock) {
                    val doc = pendingDoc
                    if (doc == null || pendingDocSeq <= deliveredDocSeq) {
                        null
                    } else {
                        pendingDocSeq to doc
                    }
                } ?: return
                deliveredDocSeq = next.first
                onDocumentChanged?.invoke(next.second)
            }
        }
    }

    // --- 启动（第 12.1 节） ---

    suspend fun start(local: () -> SyncLocalData, sink: SyncLocalSink): SyncStartResult {
        rolledBackAtStartup = false
        val lookup = env.keyStore.readEntry()
        val read = env.stateStore.load()
        // 状态文件坏掉：只进入「状态损坏」，不再读第二次，更不删密钥库里的 K（H4）。
        if (read == SyncStateRead.Corrupted) return SyncStartResult.Attention(SyncStartupAction.StateCorrupted)

        // 处理上次加入提交残留的快照（第 12.4 节、第 23 节第 14 条）。
        when (env.journal.status) {
            SyncJoinJournalStatus.NONE -> Unit
            SyncJoinJournalStatus.COMMITTED, SyncJoinJournalStatus.INCOMPLETE -> {
                env.journal.discard()
            }
            SyncJoinJournalStatus.PENDING -> {
                if (read is SyncStateRead.Present) {
                    retireSnapshot()
                } else {
                    if (lookup == SyncKeyLookup.Unavailable) return SyncStartResult.Unavailable
                    if (!rollbackJoin(sink)) return SyncStartResult.NeedsRepair(SyncStatusText.joinRepair)
                    rolledBackAtStartup = true
                    return SyncStartResult.NotEnabled
                }
            }
        }

        val keychainRead = when (lookup) {
            SyncKeyLookup.None -> SyncKeychainRead.None
            is SyncKeyLookup.Present -> SyncKeychainRead.Present(lookup.entry.blobID)
            SyncKeyLookup.Unavailable -> SyncKeychainRead.Unavailable
            SyncKeyLookup.Invalidated -> SyncKeychainRead.Invalidated
        }
        val action = SyncLifecycle.startupAction(read, keychainRead)
        val entryKeys = (lookup as? SyncKeyLookup.Present)?.let {
            try {
                deriveSyncKeys(it.entry.key)
            } catch (_: Exception) {
                null
            }
        }

        when (action) {
            SyncStartupAction.NotEnabled -> return SyncStartResult.NotEnabled
            SyncStartupAction.Skip -> return SyncStartResult.Unavailable
            SyncStartupAction.Normal, SyncStartupAction.ResumeCreating -> {
                val present = read as? SyncStateRead.Present
                if (present == null || entryKeys == null || entryKeys.blobID != present.state.blobID) {
                    return SyncStartResult.Attention(SyncStartupAction.Mismatch)
                }
                var currentState = present.state
                if (action == SyncStartupAction.ResumeCreating) {
                    currentState = SyncLifecycle.completeCreate(currentState, local(), env.wallClock()).first
                    if (!env.stateStore.save(currentState)) return SyncStartResult.Failed(SyncFlowResult.StorageFailed)
                }
                activate(currentState, entryKeys)
                return SyncStartResult.Active
            }
            else -> Unit
        }

        val stateOrigin = (read as? SyncStateRead.Present)?.state?.origin
        val transport = if (entryKeys != null && stateOrigin != null) {
            env.makeTransport(stateOrigin, entryKeys)
        } else {
            OfflineTransport
        }
        val (recoveredAction, recoveredResult) = SyncLifecycle.recover(env.stateStore, env.keyStore, sink, transport)
        if (recoveredResult != null && recoveredResult != SyncFlowResult.Done) {
            return SyncStartResult.Failed(recoveredResult)
        }
        return when (recoveredAction) {
            SyncStartupAction.OrphanEntry,
            SyncStartupAction.DiscardCreating,
            SyncStartupAction.FinishDisabling,
            SyncStartupAction.FinishClearing,
            SyncStartupAction.FinishClearingLocally,
            -> SyncStartResult.NotEnabled
            else -> SyncStartResult.Attention(recoveredAction)
        }
    }

    internal fun activate(state: SyncState, keys: SyncDerived) {
        val codec = SyncCryptoCodec(keys, env.sizeLimit, env.random)
        val transport = GatedTransport(
            inner = env.makeTransport(state.origin, keys),
            gate = gate,
            onGateReleased = { drainDocNotification() },
        )
        val c = SyncCoordinator(
            state = state,
            store = env.stateStore,
            transport = transport,
            codec = codec,
            wallClock = env.wallClock,
            sizeLimit = env.sizeLimit,
        )
        c.onDocumentChanged = { doc -> recordPendingDoc(doc) }
        this.keys = keys
        this.origin = state.origin
        this.coordinator = c
    }

    /** 只停协调器（不动磁盘上的状态与密钥库）：界面层摘线后迟到的流程结果用它收掉。 */
    fun suspend() {
        detach()
    }

    private fun deactivate() {
        locked {
            coordinator?.cancel()
            coordinator = null
            keys = null
            origin = null
            clearPendingDoc()
        }
    }

    // --- 同步周期与本地操作 ---

    suspend fun runCycle(): SyncCycleResult? {
        val result = run {
            flight.lock()
            try {
                val c = coordinator ?: return@run null
                gate.acquireUninterruptibly()
                try {
                    // 拿到 gate 后再确认协调器未被摘下，避免关闭/清除后又写回状态。
                    if (coordinator !== c) return@run null
                    c.runCycle()
                } finally {
                    gate.release()
                }
            } finally {
                flight.unlock()
            }
        }
        drainDocNotification()
        return result
    }

    fun perform(op: SyncLocalOp, localOnlyVetoCount: Int = 0): SyncCoordinator.LocalResult? {
        val result = locked {
            val c = coordinator ?: return@locked null
            c.perform(op, localOnlyVetoCount)
        }
        drainDocNotification()
        return result
    }

    // --- 创建（第 12.3 节） ---

    suspend fun create(origin: String, local: SyncLocalData): SyncCreateResult {
        if (!SyncCode.isCanonicalOrigin(origin)) return SyncCreateResult.Failed("服务器地址无效")
        val k = try {
            env.random(32)
        } catch (_: Exception) {
            return SyncCreateResult.Failed("系统随机源不可用")
        }
        if (k.size != 32) return SyncCreateResult.Failed("系统随机源不可用")
        return create(k, origin, local)
    }

    /** 用给定的 `K` 创建（新生成的，或加入时 404 后「仍用此码创建」）。 */
    suspend fun create(key: ByteArray, origin: String, local: SyncLocalData): SyncCreateResult {
        if (!SyncCode.isCanonicalOrigin(origin)) return SyncCreateResult.Failed("服务器地址无效")
        when (env.journal.status) {
            SyncJoinJournalStatus.NONE -> Unit
            SyncJoinJournalStatus.PENDING -> return SyncCreateResult.Failed(SyncStatusText.joinRepairFirst)
            SyncJoinJournalStatus.COMMITTED, SyncJoinJournalStatus.INCOMPLETE -> {
                if (!env.journal.discard()) return SyncCreateResult.Failed("本机还有未清理的同步数据，请稍后再试")
            }
        }
        if (env.stateStore.load() != SyncStateRead.None) {
            return SyncCreateResult.Failed("本机还有未清理的同步状态，请先关闭同步")
        }
        val dev = randomDevice() ?: return SyncCreateResult.Failed("系统随机源不可用")
        val derived = try {
            deriveSyncKeys(key)
        } catch (_: Exception) {
            return SyncCreateResult.Failed("系统密钥派生不可用")
        }
        val code = SyncCode.format(key, origin, ::sha256) ?: return SyncCreateResult.Failed("同步码格式无效")
        var state = SyncState(
            phase = SyncPhase.CREATING,
            origin = origin,
            blobID = derived.blobID,
            clock = SyncClock(dev = dev),
        )
        if (!env.stateStore.save(state)) return SyncCreateResult.Failed("本机保存失败")
        when (env.keyStore.write(SyncKeyEntry(key, dev, derived.blobID))) {
            SyncKeyWrite.OK -> Unit
            SyncKeyWrite.UNAVAILABLE -> {
                env.stateStore.delete()
                return SyncCreateResult.Failed("系统密钥库暂时不可用，请稍后再试")
            }
            SyncKeyWrite.FAILED -> {
                env.stateStore.delete()
                return SyncCreateResult.Failed("无法写入系统密钥库")
            }
        }
        val (completedState, report) = SyncLifecycle.completeCreate(state, local, env.wallClock())
        state = completedState
        if (!env.stateStore.save(state)) return SyncCreateResult.Failed("本机保存失败")
        activate(state, derived)
        val first = runCycle() ?: SyncCycleResult.Transient
        if (first is SyncCycleResult.Halted && first.halt is SyncHalt.Gone) {
            deactivate()
            env.keyStore.delete()
            env.stateStore.delete()
            return SyncCreateResult.Failed("生成的空间标识已被使用，请重试")
        }
        return SyncCreateResult.Created(code, derived.fingerprint, report, first)
    }

    internal fun randomDevice(): Long? {
        for (i in 0 until 8) {
            val b = try {
                env.random(4)
            } catch (_: Exception) {
                return null
            }
            if (b.size != 4) return null
            val dev = ((b[0].toLong() and 0xFF) shl 24) or
                ((b[1].toLong() and 0xFF) shl 16) or
                ((b[2].toLong() and 0xFF) shl 8) or
                (b[3].toLong() and 0xFF)
            if (dev != 0L) return dev
        }
        return null
    }

    // --- 关闭与清除（第 12.6 节） ---

    private fun detach(): Pair<SyncState, SyncDerived>? = locked {
        val c = coordinator
        val k = keys
        if (c == null || k == null) return@locked null
        c.cancel()
        val snapshot = c.state.deepCopy() to k
        coordinator = null
        keys = null
        origin = null
        clearPendingDoc()
        snapshot
    }

    fun disable(sink: SyncLocalSink): SyncFlowResult {
        if (!settleJoinSnapshot(sink)) return SyncFlowResult.StorageFailed
        val live = detach()
        val current = live?.first ?: (env.stateStore.load() as? SyncStateRead.Present)?.state
        if (current == null) {
            // 只有密钥库条目（「同步状态丢失」或「状态损坏」时的关闭）：先删状态存储（清掉损坏标记），最后删条目（H4b）。
            if (!env.stateStore.delete()) return SyncFlowResult.StorageFailed
            return if (env.keyStore.delete()) SyncFlowResult.Done else SyncFlowResult.StorageFailed
        }
        val result = SyncLifecycle.disable(current, env.stateStore, env.keyStore, sink)
        if (result == SyncFlowResult.LocalSaveFailed && live != null) {
            activate(live.first, live.second)
        }
        return result
    }

    /** 清除云端。网络暂时失败时状态停在 `CLEARING`，下次启动按第 12.1 节表格继续；屏障失败则恢复原样。 */
    suspend fun clear(sink: SyncLocalSink): SyncFlowResult {
        if (!settleJoinSnapshot(sink)) return SyncFlowResult.StorageFailed
        val live = detach() ?: return SyncFlowResult.StorageFailed
        val result = SyncLifecycle.clear(
            live.first,
            env.stateStore,
            env.keyStore,
            sink,
            env.makeTransport(live.first.origin, live.second),
        )
        if (result == SyncFlowResult.LocalSaveFailed) {
            activate(live.first, live.second)
        }
        return result
    }
}

/** 周期在等网络时让出 `gate`（并派发此前排队文档），回来先拿回 `gate` 再交还协调器。 */
internal class GatedTransport(
    private val inner: SyncTransport,
    private val gate: Semaphore,
    private val onGateReleased: () -> Unit = {},
) : SyncTransport {
    override suspend fun get(): SyncServerResponse = around { inner.get() }
    override suspend fun put(request: SyncPutRequest): SyncServerResponse = around { inner.put(request) }
    override suspend fun delete(base: Long, epoch: String?): SyncServerResponse = around { inner.delete(base, epoch) }

    private suspend inline fun around(body: () -> SyncServerResponse): SyncServerResponse {
        gate.release()
        return try {
            onGateReleased()
            body()
        } finally {
            gate.acquireUninterruptibly()
        }
    }
}

/** 没有密钥条目时恢复流程用的占位传输：不发任何请求。 */
internal object OfflineTransport : SyncTransport {
    override suspend fun get(): SyncServerResponse = SyncServerResponse.Transient
    override suspend fun put(request: SyncPutRequest): SyncServerResponse = SyncServerResponse.Transient
    override suspend fun delete(base: Long, epoch: String?): SyncServerResponse = SyncServerResponse.Transient
}
