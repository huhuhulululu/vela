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
    val notice: String? = null,
)

/**
 * 跨设备同步在界面层的状态与接线（docs/plans/sync.md 第 11–13、16、18、23 节）：
 * - 实验开关默认关闭：关闭时不显示功能、不读密钥库、不发任何同步请求（验收 1）。
 * - minSdk < 26、探针 DISABLED 或未配置服务端地址时不显示入口；JOIN_ONLY 隐藏创建与 404 创建入口（验收 2、7）。
 * - 无常驻轮询，仅在离散触发点执行同步周期。
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
    private var joinJob: Job? = null
    private var rerun = false
    private var joinGeneration = 0

    val isOn: Boolean get() = uiState.value.phase == SyncUiPhase.Active
    val localWriteAllowed: Boolean get() = service.localWriteAllowed

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
        mutable.update { it.copy(phase = SyncUiPhase.Loading) }
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

    // --- 创建（第 12.3 节） ---

    fun enablePreview(): Result<SyncMigrationReport> {
        if (!canCreate) return Result.failure(IllegalStateException("当前设备仅支持加入已有同步空间。"))
        if (!SyncMigration.canEnable(bridge.curationLoadOutcome, bridge.tasteLoadOutcome)) {
            return Result.failure(IllegalStateException("收藏或口味文件读取异常，先处理后再开启同步。"))
        }
        val stamped = SyncMigration.stampCreator(bridge.localData(), SyncClock(dev = 1L), wallClock())
        return Result.success(stamped.second)
    }

    suspend fun enable() {
        val origin = configuredOrigin ?: return
        if (!canCreate || uiState.value.busy) return
        if (enablePreview().isFailure) return
        mutable.update { it.copy(busy = true) }
        try {
            val result = service.create(origin, bridge.localData())
            if (abandonedAfterAwait()) return
            when (result) {
                is SyncCreateResult.Created -> {
                    mutable.update { it.copy(savedCode = result.code) }
                    activated()
                    scheduler.record(result.firstCycle, nowU)
                    mutable.update { it.copy(failure = SyncStatusText.cycle(result.firstCycle)) }
                }
                is SyncCreateResult.Failed -> showNotice(result.message)
            }
        } finally {
            mutable.update { it.copy(busy = false) }
        }
    }

    fun dismissSavedCode() = mutable.update { it.copy(savedCode = null) }

    fun revealCode(): String? = service.currentCode()

    // --- 加入（第 12.4 节） ---

    suspend fun prepareJoin(text: String) {
        if (!canJoin || uiState.value.busy) return
        val myJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        joinJob = myJob
        mutable.update { it.copy(busy = true) }
        try {
            if (uiState.value.phase != SyncUiPhase.Off) {
                val closed = service.disable(bridge)
                if (closed != SyncFlowResult.Done) {
                    mutable.update { it.copy(joinStage = SyncUiJoinStage.Failed(SyncStatusText.flow(closed))) }
                    return
                }
                mutable.update { it.copy(phase = SyncUiPhase.Off) }
            }
            if (!SyncMigration.canEnable(bridge.curationLoadOutcome, bridge.tasteLoadOutcome)) {
                mutable.update { it.copy(joinStage = SyncUiJoinStage.Failed("收藏或口味文件读取异常，先处理后再加入。")) }
                return
            }
            mutable.update { it.copy(joinStage = SyncUiJoinStage.Checking) }
            joinGeneration += 1
            val generation = joinGeneration
            val prepared = service.prepareJoin(text.trim(), bridge.localData())
            if (abandonedAfterAwait() || generation != joinGeneration) {
                if (prepared is SyncJoinPrepared.Preview) prepared.ticket.cancel()
                if (generation == joinGeneration) mutable.update { it.copy(joinStage = SyncUiJoinStage.Idle) }
                return
            }
            val nextStage = when (prepared) {
                SyncJoinPrepared.InvalidCode -> SyncUiJoinStage.Failed("同步码无效")
                is SyncJoinPrepared.NotFound -> SyncUiJoinStage.NotFound(prepared.code)
                SyncJoinPrepared.Gone -> SyncUiJoinStage.Failed("这个同步码对应的空间已被清除")
                is SyncJoinPrepared.Preview -> SyncUiJoinStage.Preview(prepared.ticket)
                is SyncJoinPrepared.Failed -> SyncUiJoinStage.Failed(prepared.message)
            }
            mutable.update { it.copy(joinStage = nextStage) }
        } finally {
            if (joinJob === myJob) joinJob = null
            mutable.update { it.copy(busy = false) }
        }
    }

    suspend fun confirmJoin() {
        val stage = uiState.value.joinStage as? SyncUiJoinStage.Preview ?: return
        if (uiState.value.busy) return
        val ticket = stage.ticket
        val generation = joinGeneration
        val myJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        joinJob = myJob
        mutable.update { it.copy(busy = true, committing = true) }
        try {
            val result = service.commitJoin(
                ticket,
                takeLocal = { bridge.localData() },
                sink = bridge,
                holdLockAfterJoin = true,
            )
            if (abandonedAfterAwait()) {
                mutable.update { it.copy(joinStage = SyncUiJoinStage.Idle) }
                return
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
                        return
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
            if (joinJob === myJob) joinJob = null
            service.releaseJoinCommit()
            mutable.update { it.copy(busy = false, committing = false) }
        }
    }

    /** 404 时「仍用此码创建」；探针分级为 JOIN_ONLY 时禁止创建。 */
    suspend fun createFromJoin() {
        if (!canCreate || uiState.value.busy) return
        val notFound = uiState.value.joinStage as? SyncUiJoinStage.NotFound ?: return
        mutable.update { it.copy(busy = true) }
        try {
            val result = service.createWithCode(notFound.code, bridge.localData())
            if (abandonedAfterAwait()) return
            mutable.update { it.copy(joinStage = SyncUiJoinStage.Idle) }
            when (result) {
                is SyncCreateResult.Created -> {
                    activated()
                    scheduler.record(result.firstCycle, nowU)
                    mutable.update { it.copy(failure = SyncStatusText.cycle(result.firstCycle)) }
                }
                is SyncCreateResult.Failed -> showNotice(result.message)
            }
        } finally {
            mutable.update { it.copy(busy = false) }
        }
    }

    fun cancelJoin() {
        val current = uiState.value.joinStage
        val cancelledTicket = if (current is SyncUiJoinStage.Preview) current.ticket.cancel() else true
        if (!cancelledTicket) return
        joinGeneration += 1
        joinJob?.cancel()
        joinJob = null
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

    suspend fun clearCloud() {
        if (uiState.value.busy) return
        mutable.update { it.copy(busy = true) }
        try {
            editJob?.cancel()
            val result = service.clear(bridge)
            if (abandonedAfterAwait()) return
            finishFlow(result)
        } finally {
            mutable.update { it.copy(busy = false) }
        }
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
                )
            }
        } else {
            val text = SyncStatusText.flow(result)
            showNotice(text)
            if (!service.isActive && uiState.value.phase == SyncUiPhase.Active) {
                mutable.update { it.copy(phase = SyncUiPhase.Attention(text)) }
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
