package vela.app.sync

import android.content.Context
import android.content.SharedPreferences
import vela.app.infra.Settings

/**
 * 同步相关的本机偏好读写（复用 `com.vela.stage`，不改动 `Settings.kt`）：
 * - 实验开关 `experimentSync` 默认关闭（验收 1）。
 * - 显式值读取/移除接口，用于区分「未设置」与「显式设过」（第 14.1 节、M9）。
 */
class SyncPrefs(
    val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE),
    )

    var experimentSync: Boolean
        get() = preferences.getBoolean(KEY_EXPERIMENT_SYNC, false)
        set(value) {
            preferences.edit().putBoolean(KEY_EXPERIMENT_SYNC, value).commit()
        }

    fun hasExplicitValue(key: String): Boolean = preferences.contains(key)

    fun explicitBool(key: String): Boolean? =
        if (preferences.contains(key)) preferences.getBoolean(key, false) else null

    fun setExplicit(key: String, value: Boolean?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key) else editor.putBoolean(key, value)
        editor.commit()
    }

    fun readSyncSettings(): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>(2)
        for (key in SYNC_SETTING_KEYS) {
            explicitBool(key)?.let { out[key] = it }
        }
        return out
    }

    fun writeSyncSettings(values: Map<String, Boolean?>) {
        val editor = preferences.edit()
        for ((k, v) in values) {
            if (v == null) editor.remove(k) else editor.putBoolean(k, v)
        }
        editor.commit()
    }

    companion object {
        const val KEY_EXPERIMENT_SYNC: String = "experimentSync"
        val SYNC_SETTING_KEYS: List<String> = listOf(
            Settings.KEY_PUBLIC_ONLY,
            Settings.KEY_INFO_ALWAYS,
        )
    }
}
