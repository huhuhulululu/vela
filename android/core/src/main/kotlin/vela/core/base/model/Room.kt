package vela.core.base.model

import java.net.URI
import java.net.URISyntaxException
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

internal fun displayableAge(age: Int?): Int? = if (age != null && age in 18..80) age else null

private fun resolveDisplayName(username: String, displayName: String?): String {
    val trimmed = displayName?.trimSwiftWhitespace().orEmpty()
    return if (trimmed.isEmpty()) username else trimmed
}

/**
 * 主构造器多一个 [Unit] 哨兵，这样同文件里的 `Room(...)` 只会命中对外的次级构造器
 * （次级构造器做展示名空白处理和年龄夹取）。`copy` 会带上哨兵，但默认沿用原值。
 */
@ConsistentCopyVisibility
data class Room private constructor(
    val username: String,
    val displayName: String,
    val subject: String,
    val viewers: Int,
    val followers: Int,
    val gender: Gender?,
    val tags: List<String>,
    val isHD: Boolean,
    val show: ShowState,
    val location: String,
    val thumbnailURL: URI?,
    val age: Int?,
    val secondsOnline: Int?,
    private val normalized: Unit,
) {
    val id: String
        get() = username

    /** 只改演出状态。目录筛除用它改写列表里的公开房间。 */
    fun withShow(state: ShowState): Room = copy(show = state)

    constructor(
        username: String,
        displayName: String? = null,
        subject: String = "",
        viewers: Int = 0,
        followers: Int = 0,
        gender: Gender? = null,
        tags: List<String> = emptyList(),
        isHD: Boolean = false,
        show: ShowState = ShowState.unknown,
        location: String = "",
        thumbnailURL: URI? = null,
        age: Int? = null,
        secondsOnline: Int? = null,
    ) : this(
        username,
        resolveDisplayName(username, displayName),
        subject,
        viewers,
        followers,
        gender,
        tags.toList(),
        isHD,
        show,
        location,
        thumbnailURL,
        displayableAge(age),
        secondsOnline,
        Unit,
    )

    companion object {
        /** 用户名格式：2 到 30 位，只含字母、数字、下划线。 */
        fun isValidUsername(name: String): Boolean {
            if (name.codePointCount(0, name.length) !in 2..30) return false
            var i = 0
            while (i < name.length) {
                val cp = name.codePointAt(i)
                val ch = cp.toChar()
                val ok = cp <= 0x7F && (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_')
                if (!ok) return false
                i += Character.charCount(cp)
            }
            return true
        }

        fun direct(username: String): Room? {
            val name = username.trimSwiftWhitespace()
            if (!isValidUsername(name)) return null
            return Room(
                username = name,
                displayName = name,
                subject = "",
                show = ShowState.publicShow,
                thumbnailURL = URI.create("https://thumb.live.mmcdn.com/riw/$name.jpg"),
            )
        }
    }
}

object ViewerCount {
    fun string(count: Int): String {
        val n = maxOf(count, 0)
        if (n >= 10_000) {
            val value = n.toDouble() / 10_000.0
            val nearest = round(value)
            if (abs(nearest - value) < 0.05 || value >= 10.0) {
                return "${nearest.toLong()}万"
            }
            val format = DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.US)).apply {
                roundingMode = java.math.RoundingMode.HALF_UP
            }
            return format.format(value) + "万"
        }
        return n.toString()
    }
}

object BroadcastClock {
    fun string(seconds: Int?): String? {
        if (seconds == null || seconds < 60) return null
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "已开播 $hours 小时 $minutes 分" else "已开播 $minutes 分钟"
    }
}

fun dedupeRooms(rooms: List<Room>): List<Room> {
    val seen = HashSet<String>()
    val kept = ArrayList<Room>(rooms.size)
    for (room in rooms) {
        if (seen.add(room.username.lowerRoot())) kept.add(room)
    }
    return kept
}

fun secureImageURL(raw: String?): URI? {
    if (raw.isNullOrEmpty()) return null
    val fixed = raw.replace("\\/", "/")
    val parsed = try {
        URI(fixed)
    } catch (_: URISyntaxException) {
        return null
    }
    val scheme = parsed.scheme?.lowerRoot() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (scheme == "https") return parsed
    return try {
        URI("https", parsed.userInfo, parsed.host, parsed.port, parsed.path, parsed.query, parsed.fragment)
    } catch (_: URISyntaxException) {
        null
    }
}
