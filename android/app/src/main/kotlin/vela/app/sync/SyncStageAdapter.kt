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

/** 支持传播同步设置持久化结果（`SharedPreferences.Editor.commit()`）并在屏障落盘后刷新内存的本机接口。 */
interface SyncSettingsPersistSink : SyncLocalSink {
    fun persistSettings(settings: Map<String, Boolean>): Boolean {
        applySettings(settings)
        return true
    }

    /** 屏障把收藏、口味、设置全部成功落盘后，在释放锁之前按磁盘内容重读并刷新内存状态。 */
    fun reloadAfterBarrier() = Unit
}

/** 包装 [SyncLocalSink]，在不改 `:core` 签名的前提下传播设置 `commit()` 失败，并在屏障全部落盘后重读刷新内存。 */
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
        // SyncLifecycle.barrier 仅在 saveCuration、saveTaste 与 persistSettings 均成功后调用本步；
        // 在释放提交/恢复锁之前按落盘内容重读刷新内存舞台，与 Apple 端一致。
        (sink as? SyncSettingsPersistSink)?.reloadAfterBarrier()
    }
}

/** 同步协调层与本机舞台状态之间的最小桥接接口，便于 JVM 单测注入内存实现。 */
interface SyncLocalBridge : SyncJoinSink, SyncSettingsPersistSink {
    val curationLoadOutcome: PreferenceLoadOutcome
    val tasteLoadOutcome: PreferenceLoadOutcome
    /** 应用是否已进入画中画（或画中画后台继续路径）：期间不得触发同步网络请求。 */
    val isInPictureInPicture: Boolean get() = false
    /** 宿主 Activity 是否正因配置变化销毁重建：期间 onStop/onDestroy 不应视为退后台取消加入或清同步码。 */
    val isHostChangingConfigurations: Boolean get() = false
    fun localData(): SyncLocalData
    fun applyProjection(doc: SyncDocument)
    fun reloadSyncedSettingsFromStore()
    fun dropLocalOnlyVetoes(keys: List<String>)
    fun showToast(message: String)
}

/**
 * 可重绑定的本机桥接代理：进程级 [SyncHub] 在 Activity 配置变化重建后切换到新 [StageSyncBridge]，
 * 让在途的同步、加入、恢复与清除屏障自动落到新宿主，不滞留旧 Activity/StageViewModel 引用。
 */
internal class RebindableSyncBridge(initial: SyncLocalBridge) : SyncLocalBridge {
    @Volatile
    private var target: SyncLocalBridge = initial

    fun rebind(next: SyncLocalBridge) {
        target = next
    }

    override val curationLoadOutcome: PreferenceLoadOutcome get() = target.curationLoadOutcome
    override val tasteLoadOutcome: PreferenceLoadOutcome get() = target.tasteLoadOutcome
    override val isInPictureInPicture: Boolean get() = target.isInPictureInPicture
    override val isHostChangingConfigurations: Boolean get() = target.isHostChangingConfigurations
    override fun localData(): SyncLocalData = target.localData()
    override fun applyProjection(doc: SyncDocument) = target.applyProjection(doc)
    override fun reloadSyncedSettingsFromStore() = target.reloadSyncedSettingsFromStore()
    override fun dropLocalOnlyVetoes(keys: List<String>) = target.dropLocalOnlyVetoes(keys)
    override fun showToast(message: String) = target.showToast(message)
    override fun persistPending(): Boolean = target.persistPending()
    override fun reloadAfterRollback() = target.reloadAfterRollback()
    override fun persistSettings(settings: Map<String, Boolean>): Boolean = target.persistSettings(settings)
    override fun reloadAfterBarrier() = target.reloadAfterBarrier()
    override fun cancelPendingSaves() = target.cancelPendingSaves()
    override fun currentTaste(): Taste = target.currentTaste()
    override fun saveCuration(curation: Curation): Boolean = target.saveCuration(curation)
    override fun saveTaste(taste: Taste): Boolean = target.saveTaste(taste)
    override fun applySettings(settings: Map<String, Boolean>) = target.applySettings(settings)
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
    private val hostChangingConfigurations: () -> Boolean = { false },
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

    override val isInPictureInPicture: Boolean
        get() = stage.lifecycleEpoch.suspended &&
            (stage.lifecycleEpoch.allowedLane != null || stage.pictureInPicture || stage.pipSolo)

    override val isHostChangingConfigurations: Boolean
        get() = hostChangingConfigurations()

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

    override fun reloadAfterBarrier() {
        val loadedCuration = stage.curationStore.load(stage.now())
        if (loadedCuration != stage.curation) {
            stage.commitCuration(loadedCuration, null)
        }
        val loadedTaste = stage.tasteStore.load(stage.now())
        if (loadedTaste != stage.taste) {
            stage.commitTaste(loadedTaste)
        }
        reloadSyncedSettingsFromStore()
        stage.publish()
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
    val hub = syncHub ?: return
    if (hub.perform(SyncLocalOp.Setting(key, value)) != SyncOpOutcome.Full) return
    val syncedValue = hub.service.state?.doc?.let { SyncProjection(it).settings[key] }
    val defaultValue = when (key) {
        Settings.KEY_PUBLIC_ONLY -> true
        Settings.KEY_INFO_ALWAYS -> false
        else -> !value
    }
    val reverted = syncedValue ?: !value
    when (key) {
        Settings.KEY_PUBLIC_ONLY -> publicOnly = reverted
        Settings.KEY_INFO_ALWAYS -> infoAlwaysOn = reverted
    }
    val persistedValue = syncedValue ?: if (reverted == defaultValue) null else reverted
    SyncPrefs(settings.preferences).setExplicit(key, persistedValue)
    showToast(SyncHub.FULL_MESSAGE)
}
