package vela.app.sync

import android.content.Context
import android.os.Build
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vela.app.stage.StageViewModel
import vela.core.sync.AndroidIcuSyncUnicode
import vela.core.sync.SyncChars
import vela.core.sync.SyncClock
import vela.core.sync.SyncCode
import vela.core.sync.SyncCycleResult
import vela.core.sync.SyncDocument
import vela.core.sync.SyncFlowResult
import vela.core.sync.SyncHalt
import vela.core.sync.SyncLocalOp
import vela.core.sync.SyncMigration
import vela.core.sync.SyncMigrationReport
import vela.core.sync.SyncOpOutcome
import vela.core.sync.SyncQuota
import vela.core.sync.SyncScheduler
import vela.core.sync.SyncStateFileStore

sealed class SyncUiPhase {
    data object Loading : SyncUiPhase()
    data object Off : SyncUiPhase()
    data object Unsupported : SyncUiPhase()
    data object Unavailable : SyncUiPhase()
    data object Active : SyncUiPhase()
    data class Attention(val message: String) : SyncUiPhase()
}

sealed class SyncUiJoinStage {
    data object Idle : SyncUiJoinStage()
    data object Checking : SyncUiJoinStage()
    data class Preview(val ticket: SyncJoinTicket) : SyncUiJoinStage()
    data class NotFound(val code: SyncCode) : SyncUiJoinStage()
    data class Failed(val message: String) : SyncUiJoinStage()
}

data class SyncUiState(
    val visible: Boolean = false,
    val canCreate: Boolean = false,
    val canJoin: Boolean = false,
    val capability: SyncCapability = SyncCapability.DISABLED,
    val experimentEnabled: Boolean = false,
    val phase: SyncUiPhase = SyncUiPhase.Loading,
    val fingerprint: String? = null,
    val lastSuccessAt: Long? = null,
    val hasPending: Boolean = false,
    val failure: String? = null,
    val busy: Boolean = false,
    val committing: Boolean = false,
    val joinStage: SyncUiJoinStage = SyncUiJoinStage.Idle,
    val savedCode: String? = null,
    val revealedCode: String? = null,
    val notice: String? = null,
)

/**
 * 跨设备同步在界面层的状态与接线（docs/plans/sync.md 第 11–13、16、18、23 节）：
 * - 实验开关默认关闭：关闭时不显示功能、不读密钥库、不发任何同步请求（验收 1）。
 * - minSdk < 26、探针 DISABLED 或未配置服务端地址时不显示入口；JOIN_ONLY 隐藏创建与 404 创建入口（验收 2、7）。
 * - 无常驻轮询，仅在离散触发点执行同步周期。
 * - 开启、加入、404 创建、清除云端与查看同步码结果全部托管在 SyncHub 自身状态与长生命周期 scope，
 *   离开面板、旋转屏幕或界面重建不会中途取消长事务。
 */
class SyncHub(
    val service: SyncService,
    private val prefs: SyncPrefs,
    private val bridge: SyncLocalBridge,
    private val scope: CoroutineScope,
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val capability: SyncCapability = SyncCapability.FULL,
    val configuredOrigin: String? = null,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
) {
    val visible: Boolean = SyncEntryPolicy.showEntry(sdkInt, capability, configuredOrigin)
    val canCreate: Boolean = SyncEntryPolicy.canCreate(sdkInt, capability, configuredOrigin)
    val canJoin: Boolean = SyncEntryPolicy.canJoin(sdkInt, capability, configuredOrigin)

    private val mutable = MutableStateFlow(
        SyncUiState(
            visible = visible,
            canCreate = canCreate,
            canJoin = canJoin,
            capability = capability,
            experimentEnabled = false,
            phase = if (visible) SyncUiPhase.Loading else SyncUiPhase.Unsupported,
        ),
    )
    val uiState: StateFlow<SyncUiState> = mutable.asStateFlow()

    private var scheduler = SyncScheduler()
    private var wired = false
    private var starting = false
    private var startId: String? = null
    private var startJob: Job? = null
    private var editJob: Job? = null
    private var cycleJob: Job? = null
    private var opJob: Job? = null
    private var opToken: Any? = null
    private var joinJob: Job? = null
    private var joinToken: Any? = null
    private var rerun = false
    private var joinGeneration = 0

    val isOn: Boolean get() = uiState.value.phase == SyncUiPhase.Active
    val localWriteAllowed: Boolean
        get() {
            if (!service.localWriteAllowed) return false
            if (wired && uiState.value.experimentEnabled && starting) return false
            return true
        }

    fun localWriteBlockedMessage(): String = when {
        service.isCommittingJoin || service.joinRepairPending -> COMMITTING_MESSAGE
        service.isCreating -> "正在开启同步…"
        service.isClearing -> "正在清除云端同步…"
        service.isDisabling -> "正在关闭同步…"
        service.isRecovering || (wired && uiState.value.experimentEnabled && starting) -> "正在恢复同步状态…"
        else -> "同步正在收尾，请先完成清除或关闭同步。"
    }

    private val nowU: ULong get() = wallClock().coerceAtLeast(0L).toULong()

    fun attach() {
        if (!visible) return
        val enabled = prefs.experimentSync
        mutable.update { it.copy(experimentEnabled = enabled) }
        if (enabled) wire()
    }

    /** 设置里的「实验：跨设备同步」开关（第 23 节第 14、17 条，H9）。 */
    fun setExperiment(on: Boolean) {
        if (!visible) return
        val cur = uiState.value
        if (on == cur.experimentEnabled) return
        if (!on) {
            if (cur.phase == SyncUiPhase.Active || service.isActive || service.hasStoredState) {
                showNotice("请先关闭同步，再关闭实验开关。")
                return
            }
            if (cur.phase == SyncUiPhase.Loading || starting || cur.busy || cur.committing || service.joinRepairPending) {
                showNotice("同步正在处理中，请等它结束（或先关闭同步）再关闭实验开关。")
                return
            }
        }
        if (!prefs.setExperimentSync(on)) {
            showNotice("本机保存失败，请重试。")
            return
        }
        mutable.update { it.copy(experimentEnabled = on, notice = null) }
        if (on) wire() else unwire()
    }

    private fun wire() {
        if (wired) return
        wired = true
        service.onDocumentChanged = { doc -> documentChanged(doc) }
        launchStart()
    }

    private fun unwire() {
        if (!wired) return
        wired = false
        cancelJoin()
        startJob?.cancel()
        startJob = null
        startId = null
        starting = false
        editJob?.cancel()
        editJob = null
        cycleJob?.cancel()
        cycleJob = null
        service.onDocumentChanged = null
        mutable.update { it.copy(phase = SyncUiPhase.Loading, savedCode = null, revealedCode = null) }
    }

    private fun launchStart() {
        if (starting) return
        starting = true
        val id = UUID.randomUUID().toString()
        startId = id
        startJob = scope.launch {
            try {
                runStart()
            } finally {
                if (startId == id) starting = false
            }
        }
    }

    private fun abandonedAfterAwait(): Boolean {
        if (!wired) {
            if (service.isActive) service.suspend()
            return true
        }
        return false
    }

    private suspend fun runStart() {
        if (!wired) return
        val local = bridge.localData()
        val result = service.start({ local }, bridge)
        if (abandonedAfterAwait()) return
        applyStartResult(result)
    }

    private fun applyStartResult(result: SyncStartResult) {
        when (result) {
            SyncStartResult.NotEnabled -> {
                mutable.update { it.copy(phase = SyncUiPhase.Off) }
                if (service.rolledBackAtStartup) {
                    showNotice("上次加入同步没有完成，已恢复本机数据。")
                }
            }
            is SyncStartResult.NeedsRepair -> mutable.update { it.copy(phase = SyncUiPhase.Attention(result.message)) }
            SyncStartResult.Unsupported -> mutable.update { it.copy(phase = SyncUiPhase.Unsupported) }
            SyncStartResult.Unavailable -> mutable.update { it.copy(phase = SyncUiPhase.Unavailable) }
            SyncStartResult.Active -> {
                activated()
                trigger(SyncScheduler.Trigger.LAUNCH)
            }
            is SyncStartResult.Attention -> {
                val text = SyncStatusText.startup(result.action) ?: "同步需要处理，请关闭后重新开启。"
                mutable.update { it.copy(phase = SyncUiPhase.Attention(text)) }
            }
            is SyncStartResult.Failed -> {
                mutable.update { it.copy(phase = SyncUiPhase.Attention(SyncStatusText.flow(result.result))) }
            }
            is SyncStartResult.Stopped -> mutable.update { it.copy(phase = SyncUiPhase.Attention(result.message)) }
        }
    }

    private fun activated() {
        mutable.update { it.copy(phase = SyncUiPhase.Active) }
        val excess = SyncQuota.localOnlyExcess(bridge.currentTaste().vetoedKeys)
        if (excess.isNotEmpty()) bridge.dropLocalOnlyVetoes(excess)
        service.state?.doc?.let { documentChanged(it) }
        refreshStatus()
    }

    private fun documentChanged(doc: SyncDocument) {
        if (!service.isActive) return
        bridge.applyProjection(doc)
        refreshStatus()
    }

    private fun refreshStatus() {
        val state = service.state
        if (state == null) {
            mutable.update { it.copy(fingerprint = null, hasPending = false) }
            return
        }
        mutable.update { cur ->
            cur.copy(
                fingerprint = SyncCode.fingerprint(state.blobID),
                hasPending = state.gen > state.pushedGen,
                lastSuccessAt = state.lastSuccessAt,
                failure = state.halt?.let { SyncStatusText.halt(it) } ?: cur.failure,
            )
        }
    }

    // --- 触发（第 11.3 节） ---

    fun trigger(trigger: SyncScheduler.Trigger) {
        if (!visible || !wired || !uiState.value.experimentEnabled) return
        if (SyncScheduler.retriesStartup(uiState.value.phase == SyncUiPhase.Unavailable, trigger) && !starting) {
            launchStart()
            return
        }
        if (!isOn || !service.isActive || !scheduler.allows(trigger, nowU)) return
        if (cycleJob?.isActive == true) {
            rerun = true
            return
        }
        cycleJob = scope.launch {
            val result = service.runCycle()
            finishCycle(result)
        }
    }

    private fun finishCycle(result: SyncCycleResult?) {
        cycleJob = null
        if (result == null) return
        scheduler.record(result, nowU)
        mutable.update { it.copy(failure = SyncStatusText.cycle(result)) }
        refreshStatus()
        if (rerun) {
            rerun = false
            scheduleEdit()
        }
    }

    private fun scheduleEdit() {
        editJob?.cancel()
        val delayMs = scheduler.editDelay(nowU).toLong().coerceAtLeast(0L)
        editJob = scope.launch {
            delay(delayMs)
            editJob = null
            trigger(SyncScheduler.Trigger.LOCAL_EDIT)
        }
    }

    fun syncNow() = trigger(SyncScheduler.Trigger.MANUAL)

    fun onForeground() = trigger(SyncScheduler.Trigger.FOREGROUND)

    fun onBackground() {
        dismissRevealedCode()
        cancelJoin()
        if (!visible || !wired || !isOn) return
        val s = service.state ?: return
        val unpushed = scheduler.allowsBackground(s.gen.toULong(), s.pushedGen.toULong(), nowU)
        if (!unpushed || cycleJob?.isActive == true) return
        editJob?.cancel()
        editJob = null
        cycleJob = scope.launch {
            val result = service.runCycle()
            finishCycle(result)
        }
    }

    // --- 本地操作 ---

    fun perform(op: SyncLocalOp, localOnlyVetoCount: Int = 0): SyncOpOutcome? {
        if (!isOn) return null
        val result = service.perform(op, localOnlyVetoCount) ?: return null
        if (result.outcome is SyncOpOutcome.Applied) {
            if (!result.saved) mutable.update { it.copy(failure = "同步状态保存失败，下次操作时重试。") }
            scheduleEdit()
        }
        refreshStatus()
        return result.outcome
    }

    private fun launchOperation(block: suspend () -> Unit): Job? {
        if (uiState.value.busy || opJob?.isActive == true || joinJob?.isActive == true) return null
        val token = Any()
        opToken = token
        mutable.update { it.copy(busy = true) }
        val job = scope.launch {
            block()
        }
        if (opToken === token && job.isActive) {
            opJob = job
        }
        job.invokeOnCompletion {
            if (opToken === token) {
                opToken = null
                opJob = null
                mutable.update { it.copy(busy = false) }
            }
        }
        return job
    }

    // --- 创建（第 12.3 节） ---

    fun enablePreview(): Result<SyncMigrationReport> {
        if (!canCreate) return Result.failure(IllegalStateException("当前设备仅支持加入已有同步空间。"))
        if (!SyncMigration.canEnable(bridge.curationLoadOutcome, bridge.tasteLoadOutcome)) {
            return Result.failure(IllegalStateException("收藏或口味文件读取异常，先处理后再开启同步。"))
        }
        val stamped = SyncMigration.stampCreator(bridge.localData(), SyncClock(dev = 1L), wallClock())
        return Result.success(stamped.second)
    }

    /**
     * 开启同步：在开始任何网络请求前先连续完成本机创建、激活与同步码发布，
     * 再把首轮周期交由 SyncHub 自身长生命周期 scope 执行，避免首轮等待期间丢编辑或中途取消卡死。
     */
    fun startEnable(): Job? {
        val origin = configuredOrigin ?: return null
        if (!canCreate || uiState.value.busy || opJob?.isActive == true || joinJob?.isActive == true) return null
        if (enablePreview().isFailure) return null
        val created = when (val local = service.createLocal(origin, bridge.localData())) {
            is SyncLocalCreateResult.Activated -> local
            is SyncLocalCreateResult.Failed -> {
                if (service.hasPendingRecovery) {
                    mutable.update { it.copy(phase = SyncUiPhase.Attention(local.message)) }
                }
                showNotice(local.message)
                return null
            }
        }
        mutable.update { it.copy(savedCode = created.code) }
        activated()
        return launchOperation {
            val first = service.runCreateFirstCycle()
            if (abandonedAfterAwait()) return@launchOperation
            finishCreateFirstCycle(first)
        }
    }

    suspend fun enable() {
        startEnable()?.join()
    }

    private fun finishCreateFirstCycle(first: SyncCycleResult) {
        if (first is SyncCycleResult.Halted && first.halt is SyncHalt.Gone) {
            editJob?.cancel()
            editJob = null
            scheduler = SyncScheduler()
            mutable.update {
                it.copy(
                    phase = if (service.hasPendingRecovery) {
                        SyncUiPhase.Attention("本机保存失败，请稍后关闭同步重试。")
                    } else {
                        SyncUiPhase.Off
                    },
                    failure = null,
                    fingerprint = null,
                    hasPending = false,
                    lastSuccessAt = null,
                    savedCode = null,
                    revealedCode = null,
                )
            }
            showNotice("生成的空间标识已被使用，请重试")
            return
        }
        scheduler.record(first, nowU)
        mutable.update { it.copy(failure = SyncStatusText.cycle(first)) }
        refreshStatus()
    }

    fun dismissSavedCode() = mutable.update { it.copy(savedCode = null) }

    fun revealCode(): String? = service.currentCode()

    /** 通过设备身份验证后展示完整同步码，状态保存在 SyncHub 避免验证途中界面重建丢失。 */
    fun revealAfterAuth() {
        val code = revealCode() ?: return
        mutable.update { it.copy(revealedCode = code) }
    }

    fun dismissRevealedCode() {
        if (uiState.value.revealedCode != null) {
            mutable.update { it.copy(revealedCode = null) }
        }
    }

    // --- 加入（第 12.4 节） ---

    /** 在 SyncHub 自身长生命周期 scope 启动加入检查，只由用户主动取消、进后台或摘线中断。 */
    fun startPrepareJoin(text: String): Job? {
        if (!canJoin || uiState.value.busy || opJob?.isActive == true || joinJob?.isActive == true) return null
        val token = Any()
        joinToken = token
        mutable.update { it.copy(busy = true) }
        val job = scope.launch {
            if (uiState.value.phase != SyncUiPhase.Off) {
                val closed = service.disable(bridge)
                if (closed != SyncFlowResult.Done) {
                    mutable.update { it.copy(joinStage = SyncUiJoinStage.Failed(SyncStatusText.flow(closed))) }
                    return@launch
                }
                mutable.update { it.copy(phase = SyncUiPhase.Off) }
            }
            if (!SyncMigration.canEnable(bridge.curationLoadOutcome, bridge.tasteLoadOutcome)) {
                mutable.update { it.copy(joinStage = SyncUiJoinStage.Failed("收藏或口味文件读取异常，先处理后再加入。")) }
                return@launch
            }
            mutable.update { it.copy(joinStage = SyncUiJoinStage.Checking) }
            joinGeneration += 1
            val generation = joinGeneration
            val prepared = service.prepareJoin(text.trim(), bridge.localData())
            if (abandonedAfterAwait() || generation != joinGeneration) {
                if (prepared is SyncJoinPrepared.Preview) prepared.ticket.cancel()
                if (generation == joinGeneration) mutable.update { it.copy(joinStage = SyncUiJoinStage.Idle) }
                return@launch
            }
            val nextStage = when (prepared) {
                SyncJoinPrepared.InvalidCode -> SyncUiJoinStage.Failed("同步码无效")
                is SyncJoinPrepared.NotFound -> SyncUiJoinStage.NotFound(prepared.code)
                SyncJoinPrepared.Gone -> SyncUiJoinStage.Failed("这个同步码对应的空间已被清除")
                is SyncJoinPrepared.Preview -> SyncUiJoinStage.Preview(prepared.ticket)
                is SyncJoinPrepared.Failed -> SyncUiJoinStage.Failed(prepared.message)
            }
            mutable.update { it.copy(joinStage = nextStage) }
        }
        if (joinToken === token && job.isActive) {
            joinJob = job
        }
        job.invokeOnCompletion {
            if (joinToken === token) {
                joinToken = null
                joinJob = null
                mutable.update { it.copy(busy = false) }
            }
        }
        return job
    }

    suspend fun prepareJoin(text: String) {
        startPrepareJoin(text)?.join()
    }

    /** 在 SyncHub 自身长生命周期 scope 提交加入，离开面板/旋转不打断，主动取消走取消语义。 */
    fun startConfirmJoin(): Job? {
        val stage = uiState.value.joinStage as? SyncUiJoinStage.Preview ?: return null
        if (uiState.value.busy || opJob?.isActive == true || joinJob?.isActive == true) return null
        val ticket = stage.ticket
        val generation = joinGeneration
        val token = Any()
        joinToken = token
        mutable.update { it.copy(busy = true, committing = true) }
        val job = scope.launch {
            try {
                val result = service.commitJoin(
                    ticket,
                    takeLocal = { bridge.localData() },
                    sink = bridge,
                    holdLockAfterJoin = true,
                )
                if (abandonedAfterAwait()) {
                    mutable.update { it.copy(joinStage = SyncUiJoinStage.Idle) }
                    return@launch
                }
                when (result) {
                    SyncJoinCommit.Joined -> {
                        mutable.update { it.copy(joinStage = SyncUiJoinStage.Idle) }
                        activated()
                        if (service.env.journal.exists) {
                            showNotice("已加入同步；临时文件没能删除，下次关闭或启动时再清理。")
                        }
                    }
                    SyncJoinCommit.Cancelled -> {
                        mutable.update { it.copy(joinStage = SyncUiJoinStage.Idle) }
                        bridge.reloadSyncedSettingsFromStore()
                    }
                    is SyncJoinCommit.Reconfirm -> {
                        if (generation != joinGeneration || ticket.isCancelled) {
                            result.ticket.cancel()
                            mutable.update { it.copy(joinStage = SyncUiJoinStage.Idle) }
                            return@launch
                        }
                        mutable.update { it.copy(joinStage = SyncUiJoinStage.Preview(result.ticket)) }
                        showNotice("云端或本机数据有变化，请再确认一次。")
                    }
                    is SyncJoinCommit.Failed -> {
                        mutable.update { it.copy(joinStage = SyncUiJoinStage.Idle) }
                        bridge.reloadSyncedSettingsFromStore()
                        showNotice(result.message)
                    }
                    is SyncJoinCommit.NeedsRepair -> {
                        mutable.update {
                            it.copy(
                                joinStage = SyncUiJoinStage.Idle,
                                phase = SyncUiPhase.Attention(result.message),
                            )
                        }
                    }
                }
            } finally {
                service.releaseJoinCommit()
            }
        }
        if (joinToken === token && job.isActive) {
            joinJob = job
        }
        job.invokeOnCompletion {
            service.releaseJoinCommit()
            if (joinToken === token) {
                joinToken = null
                joinJob = null
                mutable.update { it.copy(busy = false, committing = false) }
            }
        }
        return job
    }

    suspend fun confirmJoin() {
        startConfirmJoin()?.join()
    }

    /** 404 时「仍用此码创建」；探针分级为 JOIN_ONLY 时禁止创建，先连续完成本机激活再跑首轮周期。 */
    fun startCreateFromJoin(): Job? {
        if (!canCreate || uiState.value.busy || opJob?.isActive == true || joinJob?.isActive == true) return null
        val notFound = uiState.value.joinStage as? SyncUiJoinStage.NotFound ?: return null
        val code = notFound.code
        when (val local = service.createLocal(code.key, code.origin, bridge.localData())) {
            is SyncLocalCreateResult.Activated -> Unit
            is SyncLocalCreateResult.Failed -> {
                mutable.update {
                    it.copy(
                        joinStage = SyncUiJoinStage.Idle,
                        phase = if (service.hasPendingRecovery) SyncUiPhase.Attention(local.message) else it.phase,
                    )
                }
                showNotice(local.message)
                return null
            }
        }
        mutable.update { it.copy(joinStage = SyncUiJoinStage.Idle) }
        activated()
        return launchOperation {
            val first = service.runCreateFirstCycle()
            if (abandonedAfterAwait()) return@launchOperation
            finishCreateFirstCycle(first)
        }
    }

    suspend fun createFromJoin() {
        startCreateFromJoin()?.join()
    }

    fun cancelJoin() {
        val current = uiState.value.joinStage
        val cancelledTicket = if (current is SyncUiJoinStage.Preview) current.ticket.cancel() else true
        if (!cancelledTicket) return
        joinGeneration += 1
        val active = joinJob
        joinJob = null
        active?.cancel()
        mutable.update { it.copy(joinStage = SyncUiJoinStage.Idle) }
    }

    // --- 关闭与清除（第 12.6 节） ---

    fun disable() {
        if (uiState.value.busy) return
        val repairing = service.joinRepairPending
        val result = service.disable(bridge)
        finishFlow(result)
        if (repairing && result == SyncFlowResult.Done) {
            showNotice("上次加入同步没有完成，已恢复本机数据。")
        }
    }

    /** 在 SyncHub 自身长生命周期 scope 执行清除云端，不随设置面板销毁或旋转中断。 */
    fun startClearCloud(): Job? = launchOperation {
        editJob?.cancel()
        val result = service.clear(bridge)
        if (abandonedAfterAwait()) return@launchOperation
        finishFlow(result)
    }

    suspend fun clearCloud() {
        startClearCloud()?.join()
    }

    private fun finishFlow(result: SyncFlowResult) {
        if (result == SyncFlowResult.Done) {
            editJob?.cancel()
            editJob = null
            scheduler = SyncScheduler()
            mutable.update {
                it.copy(
                    phase = SyncUiPhase.Off,
                    failure = null,
                    fingerprint = null,
                    hasPending = false,
                    lastSuccessAt = null,
                    savedCode = null,
                    revealedCode = null,
                )
            }
        } else {
            val text = SyncStatusText.flow(result)
            showNotice(text)
            if (!service.isActive && uiState.value.phase == SyncUiPhase.Active) {
                mutable.update { it.copy(phase = SyncUiPhase.Attention(text), revealedCode = null) }
            }
        }
    }

    fun dismissNotice() = mutable.update { it.copy(notice = null) }

    fun showNotice(text: String) = mutable.update { it.copy(notice = text) }

    companion object {
        const val FULL_MESSAGE: String = "同步数据已满，请删掉一些收藏、屏蔽或不想看"
        const val COMMITTING_MESSAGE: String = "正在加入同步…"

        /**
         * 入口工厂：严格执行 minSdk 26 门槛（低于 26 直接返回 null，不加载同步模块、不跑探针）。
         */
        fun createIfSupported(
            context: Context,
            stage: StageViewModel,
            syncDir: Path,
            scope: CoroutineScope,
            sdkInt: Int = Build.VERSION.SDK_INT,
            configuredOriginOverride: String? = null,
            probe: () -> SyncProbeReport = { SyncProbe.run(sdkInt) },
        ): SyncHub? = SyncPlatformGate.loadIfSupported(sdkInt) {
            val origin = SyncConfig.configuredOrigin(configuredOriginOverride, context) ?: return@loadIfSupported null
            installAndroidUnicodeIfAvailable()
            val capability = probe().capability
            if (!SyncEntryPolicy.showEntry(sdkInt, capability, origin)) return@loadIfSupported null
            val prefs = SyncPrefs(stage.settings.preferences)
            val bridge = StageSyncBridge(stage, prefs)
            val service = SyncService(
                SyncService.Environment(
                    stateStore = SyncMarkedStateStore(SyncStateFileStore(syncDir.resolve(StageSyncBridge.STATE_FILE_NAME))),
                    keyStore = AndroidSyncKeyStore(context),
                    makeTransport = { remoteOrigin, keys -> SyncHttpTransport(remoteOrigin, keys) },
                    journal = StageSyncBridge.makeFileJournal(syncDir, stage, prefs),
                ),
            )
            SyncHub(
                service = service,
                prefs = prefs,
                bridge = bridge,
                scope = scope,
                sdkInt = sdkInt,
                capability = capability,
                configuredOrigin = origin,
            )
        }

        internal fun installAndroidUnicodeIfAvailable() {
            if (!AndroidIcuSyncUnicode.available()) return
            try {
                val icu = AndroidIcuSyncUnicode()
                if (icu.ageAtMost13('a'.code)) {
                    SyncChars.unicode = icu
                }
            } catch (_: Throwable) {
            }
        }
    }
}
