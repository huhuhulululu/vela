package vela.app.sync

import java.nio.file.Path
import vela.app.infra.Settings
import vela.app.stage.StageViewModel
import vela.app.stage.applySearch
import vela.app.stage.commitCuration
import vela.app.stage.commitTaste
import vela.app.stage.setPublicOnly
import vela.core.curation.Curation
import vela.core.sync.PreferenceLoadOutcome
import vela.core.sync.SyncDocument
import vela.core.sync.SyncLocalData
import vela.core.sync.SyncProjection
import vela.core.sync.settingSyncedVeto
import vela.core.taste.Taste

/** 同步协调层与本机舞台状态之间的最小桥接接口，便于 JVM 单测注入内存实现。 */
interface SyncLocalBridge : SyncJoinSink {
    val curationLoadOutcome: PreferenceLoadOutcome
    val tasteLoadOutcome: PreferenceLoadOutcome
    fun localData(): SyncLocalData
    fun applyProjection(doc: SyncDocument)
    fun reloadSyncedSettingsFromStore()
    fun dropLocalOnlyVetoes(keys: List<String>)
    fun showToast(message: String)
}

/**
 * 舞台适配器：在不改动 `vela.app.stage` 包文件的前提下，通过同模块 `internal` 接口
 * 连接 [StageViewModel] 的收藏、口味、去抖保存器与两个同步设置。
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

    override fun applySettings(settings: Map<String, Boolean>) {
        settings[Settings.KEY_PUBLIC_ONLY]?.let { value ->
            stage.setPublicOnly(value)
        }
        settings[Settings.KEY_INFO_ALWAYS]?.let { value ->
            if (stage.infoAlwaysOn != value || prefs.explicitBool(Settings.KEY_INFO_ALWAYS) != value) {
                stage.infoAlwaysOn = value
                stage.settings.infoAlwaysOn = value
                stage.publish()
            }
        }
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
        applySettings(projection.settings)
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
