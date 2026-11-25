package vela.core.base.model

import java.net.URI
import java.net.URISyntaxException
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import vela.core.sites.RoomKey
import vela.core.sites.SiteID

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
    val site: SiteID,
    /** 目录直接带来的播放主列表（Cam4 `hlsPreviewUrl`）。cb 为 null。 */
    val stream: URI?,
    /** 缩略图过期时刻。cb 为 null。 */
    val thumbnailExpires: Instant?,
    /** 大写 ISO 3166-1 alpha-2。cb 为空。 */
    val blockedRegions: Set<String>,
    private val normalized: Unit,
) {
    /** 名字不合法时也有值：内部不校验。`c4` 的用户名部分统一小写。 */
    val key: RoomKey

    /** 小写 ref，集合与比较用。 */
    val identity: String

    /** 内存简写：`cb` 房间等于用户名，其他站点是 `c4:<名>`。 */
    val ref: String
        get() = key.ref

    /** 内存简写。`cb` 房间等于 [username]，其他站点带前缀。 */
    val id: String
        get() = ref

    /** 目录快照里这间房能不能直接算在线可播：公开且（cb 目录无 stream，或非 cb 目录已带流地址）。 */
    val isCatalogPlayable: Boolean
        get() = show.isPlayable && (site == SiteID.chaturbate || stream != null)

    init {
        val resolved = makeKey(site, username)
        key = resolved.first
        identity = resolved.second
    }

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
        site: SiteID = SiteID.chaturbate,
        stream: URI? = null,
        thumbnailExpires: Instant? = null,
        blockedRegions: Set<String> = emptySet(),
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
        site,
        stream,
        thumbnailExpires,
        blockedRegions.toSet(),
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
            val key = RoomKey(SiteID.chaturbate, username) ?: return null
            return direct(key)
        }

        fun direct(key: RoomKey): Room {
            val thumb = if (key.site == SiteID.chaturbate) {
                URI.create("https://thumb.live.mmcdn.com/riw/${key.name}.jpg")
            } else {
                null
            }
            return Room(
                username = key.name,
                displayName = key.name,
                subject = "",
                show = ShowState.publicShow,
                thumbnailURL = thumb,
                site = key.site,
            )
        }

        private fun makeKey(site: SiteID, username: String): Pair<RoomKey, String> {
            val key = RoomKey(site, username)
                ?: RoomKey.unchecked(site, if (site == SiteID.cam4) username.lowerRoot() else username)
            return key to key.normalized
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
        if (seen.add(room.identity)) kept.add(room)
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
