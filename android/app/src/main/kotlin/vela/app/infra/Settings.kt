package vela.app.infra

import android.content.Context
import android.content.SharedPreferences
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Region

/**
 * 本机设置，对应 Swift 的 `SettingsStore`（suite `com.vela.stage`）。
 * 不移植窗口置顶、透明度、老板键、菜单栏这些桌面项。
 * 双窗开关和画中画自动进入记在这里，后面的包不再改这个文件。
 */
class Settings(val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    var ageConfirmed: Boolean
        get() = preferences.getBoolean(KEY_AGE, false)
        set(value) = putBoolean(KEY_AGE, value)

    /** 键不存在（首次启动）视为静音；用户存过的值原样保留。 */
    var muted: Boolean
        get() = if (preferences.contains(KEY_MUTED)) preferences.getBoolean(KEY_MUTED, true) else true
        set(value) = putBoolean(KEY_MUTED, value)

    var volume: Double
        get() = preferences.getString(KEY_VOLUME, null)?.toDoubleOrNull() ?: DEFAULT_VOLUME
        set(value) = putString(KEY_VOLUME, value.toString())

    var source: FeedSource
        get() = FeedSource.restore(preferences.getString(KEY_SOURCE, null)) ?: FeedSource.guess
        set(value) = putString(KEY_SOURCE, value.name)

    var publicOnly: Boolean
        get() = preferences.getBoolean(KEY_PUBLIC_ONLY, true)
        set(value) = putBoolean(KEY_PUBLIC_ONLY, value)

    var infoAlwaysOn: Boolean
        get() = preferences.getBoolean(KEY_INFO_ALWAYS, false)
        set(value) = putBoolean(KEY_INFO_ALWAYS, value)

    var hintSeen: Boolean
        get() = preferences.getBoolean(KEY_HINT, false)
        set(value) = putBoolean(KEY_HINT, value)

    var tag: String
        get() = preferences.getString(KEY_TAG, null) ?: ""
        set(value) = putString(KEY_TAG, value)

    var gender: Gender?
        get() = preferences.getString(KEY_GENDER, null)?.let { raw ->
            Gender.entries.firstOrNull { it.wire == raw }
        }
        set(value) = putOrRemove(KEY_GENDER, value?.wire)

    var region: Region?
        get() = preferences.getString(KEY_REGION, null)?.let(::regionFromStored)
        set(value) = putOrRemove(KEY_REGION, value?.stored())

    /** 竖屏双窗。缺省单窗。 */
    var dualWindow: Boolean
        get() = preferences.getBoolean(KEY_DUAL, false)
        set(value) = putBoolean(KEY_DUAL, value)

    /** 回桌面自动进入画中画。缺省开。 */
    var autoEnterPip: Boolean
        get() = preferences.getBoolean(KEY_PIP, true)
        set(value) = putBoolean(KEY_PIP, value)

    private fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).commit()
    }

    private fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).commit()
    }

    private fun putOrRemove(key: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.commit()
    }

    companion object {
        const val PREFS_NAME = "com.vela.stage"
        const val KEY_MUTED = "muted"
        const val KEY_AGE = "ageConfirmed"
        const val KEY_VOLUME = "volume"
        const val KEY_SOURCE = "source"
        const val KEY_PUBLIC_ONLY = "publicOnly"
        const val KEY_INFO_ALWAYS = "infoAlwaysOn"
        const val KEY_HINT = "hintSeen"
        const val KEY_TAG = "tag"
        const val KEY_GENDER = "gender"
        const val KEY_REGION = "region"
        const val KEY_DUAL = "dualWindow"
        const val KEY_PIP = "autoEnterPip"
        const val DEFAULT_VOLUME = 0.85
    }
}

private fun Region.stored(): String = when (this) {
    Region.asia -> "asia"
    Region.europeRussia -> "europe_russia"
    Region.northAmerica -> "northamerica"
    Region.southAmerica -> "southamerica"
    Region.other -> "other"
}

private fun regionFromStored(raw: String): Region? = when (raw) {
    "asia" -> Region.asia
    "europe_russia" -> Region.europeRussia
    "northamerica" -> Region.northAmerica
    "southamerica" -> Region.southAmerica
    "other" -> Region.other
    else -> null
}
