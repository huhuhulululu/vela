package vela.app.sync

import java.nio.file.Path
import vela.app.infra.Settings
import vela.app.stage.StageViewModel
import vela.app.stage.applySearch
import vela.app.stage.commitCuration
import vela.app.stage.commitTaste
import vela.core.curation.Curation
import vela.core.sync.PreferenceLoadOutcome
import vela.core.sync.SyncDocument
import vela.core.sync.SyncKeys
import vela.core.sync.SyncLocalData
import vela.core.sync.SyncLocalOp
import vela.core.sync.SyncLocalSink
import vela.core.sync.SyncOpOutcome
import vela.core.sync.SyncProjection
import vela.core.sync.settingSyncedVeto
import vela.core.taste.Taste

/** 支持传播同步设置持久化结果（`SharedPreferences.Editor.commit()`）的本机落盘接口。 */
interface SyncSettingsPersistSink : SyncLocalSink {
    fun persistSettings(settings: Map<String, Boolean>): Boolean {
        applySettings(settings)
        return true
    }
}

/** 包装 [SyncLocalSink]，在不改 `:core` 签名的前提下把同步设置 `commit()` 失败传播给 `SyncLifecycle.barrier`。 */
internal fun verifiedBarrierSink(
    sink: SyncLocalSink,
    settings: Map<String, Boolean>,
): SyncLocalSink = object : SyncLocalSink by sink {
    override fun saveTaste(taste: Taste): Boolean {
        if (!sink.saveTaste(taste)) return false
        return if (sink is SyncSettingsPersistSink) {
            sink.persistSettings(settings)
        } else {
            sink.applySettings(settings)
            true
        }
    }

    override fun applySettings(settings: Map<String, Boolean>) {
        // 同步设置已在 saveTaste 阶段原子提交并校验 commit() 结果；不再重复写盘或吞掉失败。
    }
}

/** 同步协调层与本机舞台状态之间的最小桥接接口，便于 JVM 单测注入内存实现。 */
interface SyncLocalBridge : SyncJoinSink, SyncSettingsPersistSink {
    val curationLoadOutcome: PreferenceLoadOutcome
    val tasteLoadOutcome: PreferenceLoadOutcome
    fun localData(): SyncLocalData
    fun applyProjection(doc: SyncDocument)
    fun reloadSyncedSettingsFromStore()
    fun dropLocalOnlyVetoes(keys: List<String>)
    fun showToast(message: String)
}

/**
 * 舞台适配器：连接 [StageViewModel] 的收藏、口味、去抖保存器与两个同步设置。
 *
 * 注：当前 `:core` 的 `CurationStore` / `TasteStore` 只暴露 `isReadOnly`（尚未区分
 * `QUARANTINED` 与 `MISSING`），因此这里把 `isReadOnly` 映射为 `READ_ONLY`，其余映射为 `LOADED`，
 * 同时允许通过构造参数注入加载结果。
 */
class StageSyncBridge(
    private val stage: StageViewModel,
    private val prefs: SyncPrefs,
    private val curationOutcomeOverride: PreferenceLoadOutcome? = null,
    private val tasteOutcomeOverride: PreferenceLoadOutcome? = null,
) : SyncLocalBridge {
    override val curationLoadOutcome: PreferenceLoadOutcome
        get() = curationOutcomeOverride ?: if (stage.curationStore.isReadOnly) {
            PreferenceLoadOutcome.READ_ONLY
        } else {
            PreferenceLoadOutcome.LOADED
        }

    override val tasteLoadOutcome: PreferenceLoadOutcome
        get() = tasteOutcomeOverride ?: if (stage.tasteStore.isReadOnly) {
            PreferenceLoadOutcome.READ_ONLY
        } else {
            PreferenceLoadOutcome.LOADED
        }

    override fun localData(): SyncLocalData = SyncLocalData(
        curation = stage.curation,
        vetoedKeys = stage.taste.vetoedKeys.toList(),
        explicitSettings = prefs.readSyncSettings(),
    )

    override fun cancelPendingSaves() {
        stage.curationSaver.flush()
        stage.tasteSaver.flush()
    }

    override fun currentTaste(): Taste = stage.taste

    override fun saveCuration(curation: Curation): Boolean = stage.curationStore.save(curation)

    override fun saveTaste(taste: Taste): Boolean = stage.tasteStore.save(taste, stage.now())

    /**
     * 投影或屏障写入两个同步设置：使用单次可校验结果的 `commit()` 落盘，绝不因内存值相等跳过写盘，
     * 且不经由用户意图入口，避免回声触发新的本地同步操作。
     */
    override fun persistSettings(settings: Map<String, Boolean>): Boolean {
        val known = settings.filterKeys { it in SyncPrefs.SYNC_SETTING_KEYS }
        if (known.isEmpty()) return true
        if (!prefs.writeSyncSettings(known)) return false
        known[Settings.KEY_PUBLIC_ONLY]?.let { value ->
            val changed = stage.publicOnly != value
            stage.publicOnly = value
            if (changed) stage.applySearch() else stage.publish()
        }
        known[Settings.KEY_INFO_ALWAYS]?.let { value ->
            stage.infoAlwaysOn = value
            stage.publish()
        }
        return true
    }

    override fun applySettings(settings: Map<String, Boolean>) {
        persistSettings(settings)
    }

    override fun persistPending(): Boolean {
        cancelPendingSaves()
        return stage.curationStore.save(stage.curation) && stage.tasteStore.save(stage.taste, stage.now())
    }

    override fun reloadAfterRollback() {
        stage.commitCuration(stage.curationStore.load(stage.now()), null)
        stage.commitTaste(stage.tasteStore.load(stage.now()))
        reloadSyncedSettingsFromStore()
        stage.publish()
    }

    /**
     * 回滚后按存储重读内存，绝不写回 SharedPreferences（M9）：
     * 换回的「未设置」键不能被写成显式值，否则后续会被误当成用户显式设置上传。
     */
    override fun reloadSyncedSettingsFromStore() {
        val storedPublicOnly = stage.settings.publicOnly
        if (stage.publicOnly != storedPublicOnly) {
            stage.publicOnly = storedPublicOnly
            stage.applySearch()
        }
        val storedInfo = stage.settings.infoAlwaysOn
        if (stage.infoAlwaysOn != storedInfo) {
            stage.infoAlwaysOn = storedInfo
            stage.publish()
        }
    }

    override fun applyProjection(doc: SyncDocument) {
        val projection = SyncProjection(doc)
        if (projection.curation != stage.curation) {
            stage.commitCuration(projection.curation, null)
        }
        val nextTaste = projection.applying(stage.taste)
        if (nextTaste != stage.taste) {
            stage.commitTaste(nextTaste)
        }
        persistSettings(projection.settings)
        stage.publish()
    }

    override fun dropLocalOnlyVetoes(keys: List<String>) {
        if (keys.isEmpty()) return
        var next = stage.taste
        for (key in keys) {
            next = next.settingSyncedVeto(key, false)
        }
        stage.commitTaste(next)
        stage.publish()
    }

    override fun showToast(message: String) {
        stage.showToast(message)
    }

    companion object {
        /** 构造同卷文件快照：与收藏、口味文件放在同一数据目录下。 */
        fun makeFileJournal(
            syncDir: Path,
            stage: StageViewModel,
            prefs: SyncPrefs,
        ): FileJoinJournal = FileJoinJournal(
            dir = syncDir.resolve(SNAPSHOT_DIR_NAME),
            files = listOf(stage.curationStore.url, stage.tasteStore.url),
            settingKeys = SyncPrefs.SYNC_SETTING_KEYS,
            readSettings = { prefs.readSyncSettings() },
            writeSettings = { values -> prefs.writeSyncSettings(values) },
        )

        const val SNAPSHOT_DIR_NAME: String = "sync-join-snapshot"
        const val STATE_FILE_NAME: String = "sync-state.json"
    }
}

/** 舞台写入入口共用的提交锁/中间态阻断与同步寄存器钩子（对齐 Apple 端 `StageModel+Sync.swift`）。 */
internal fun StageViewModel.syncLocalWriteAllowed(): Boolean = syncHub?.localWriteAllowed ?: true

internal fun StageViewModel.rejectWhileSyncJoining(): Boolean {
    if (syncLocalWriteAllowed()) return false
    showToast(syncHub?.localWriteBlockedMessage() ?: SyncHub.COMMITTING_MESSAGE)
    return true
}

internal fun StageViewModel.syncedCuration(op: SyncLocalOp, local: () -> Curation): Curation? {
    if (rejectWhileSyncJoining()) return null
    val hub = syncHub ?: return local()
    return when (val outcome = hub.perform(op)) {
        is SyncOpOutcome.Applied -> SyncProjection(outcome.replica.doc).curation
        SyncOpOutcome.Full -> {
            showToast(SyncHub.FULL_MESSAGE)
            null
        }
        else -> local()
    }
}

internal fun StageViewModel.syncedVeto(key: String): Boolean {
    if (rejectWhileSyncJoining()) return false
    if (taste.vetoedKeys.contains(key)) return true
    val hub = syncHub ?: return true
    val localOnlyCount = taste.vetoedKeys.count { SyncKeys.syncVetoKey(it) == null }
    return when (hub.perform(SyncLocalOp.Veto(key), localOnlyVetoCount = localOnlyCount)) {
        SyncOpOutcome.VetoQuotaFull, SyncOpOutcome.LocalOnlyQuotaFull -> {
            showToast("不想看最多 450 个（另有 50 个本机专用）")
            false
        }
        SyncOpOutcome.Full -> {
            showToast(SyncHub.FULL_MESSAGE)
            false
        }
        else -> true
    }
}

internal fun StageViewModel.syncedUnveto(key: String): Boolean {
    if (rejectWhileSyncJoining()) return false
    if (!taste.vetoedKeys.contains(key)) return true
    val hub = syncHub ?: return true
    if (hub.perform(SyncLocalOp.Unveto(key)) == SyncOpOutcome.Full) {
        showToast(SyncHub.FULL_MESSAGE)
        return false
    }
    return true
}

internal fun StageViewModel.syncedClearVetoes(): Boolean {
    if (rejectWhileSyncJoining()) return false
    val hub = syncHub ?: return true
    if (hub.perform(SyncLocalOp.ClearVetoes) == SyncOpOutcome.Full) {
        showToast(SyncHub.FULL_MESSAGE)
        return false
    }
    return true
}

internal fun StageViewModel.syncSetting(key: String, value: Boolean) {
    syncHub?.perform(SyncLocalOp.Setting(key, value))
}
