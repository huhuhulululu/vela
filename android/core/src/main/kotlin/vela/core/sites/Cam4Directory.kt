package vela.core.sites

import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import vela.core.base.model.FlexInt
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.model.lowerRoot
import vela.core.base.model.toIntExactOrNull
import vela.core.base.model.trimSwiftWhitespace
import vela.core.base.net.ResponseShape
import vela.core.base.site.CatalogFailure
import vela.core.base.site.CatalogPage

/**
 * Cam4 访客目录。不登录、不带推广参数。缩略图用 `snapshotImageLink`，不用 `defaultImageLink`。
 */
object Cam4Directory {
    const val maxPageSize: Int = 90

    /** 截图没有 `validto` 时的固定有效期，与目录索引的新鲜窗口一致。 */
    const val snapshotTTL: Long = 10 * 60

    /**
     * `page` 从 1 开始，`resultsPerPage` 夹到 1...90。
     * 传入 [offset] / [limit] 时按 `page = offset / k + 1` 计算（Kotlin 不能按参数名重载）。
     */
    fun url(page: Int = 1, resultsPerPage: Int = 1, offset: Int? = null, limit: Int? = null): URI {
        val size: Int
        val index: Int
        if (offset != null || limit != null) {
            size = (limit ?: resultsPerPage).coerceIn(1, maxPageSize)
            index = maxOf(offset ?: 0, 0) / size + 1
        } else {
            size = resultsPerPage.coerceIn(1, maxPageSize)
            index = maxOf(page, 1)
        }
        val query = "directoryJson=true&online=true&url=true&page=$index&resultsPerPage=$size"
        return URI("https", "www.cam4.com", "/directoryCams", query, null)
    }

    internal data class DecodedPage(val page: CatalogPage, val rawCount: Int)

    fun decode(
        data: ByteArray,
        requestLimit: Int,
        requestOffset: Int,
        now: Instant,
        viewerRegions: Set<String>,
    ): CatalogPage = decodeWithRawCount(data, requestLimit, requestOffset, now, viewerRegions).page

    internal fun decodeWithRawCount(
        data: ByteArray,
        requestLimit: Int,
        requestOffset: Int,
        now: Instant,
        viewerRegions: Set<String>,
    ): DecodedPage {
        if (!ResponseShape.looksLikeJSON(data)) throw CatalogFailure.Malformed
        val parsed = parse(data)
        val base = maxOf(requestOffset, 0)
        val limit = requestLimit.coerceIn(1, maxPageSize)
        val rooms = parsed.users.mapNotNull { it.room(now, viewerRegions) }
        val step = minOf(parsed.users.size, limit)
        val nextPage = ((base / limit) + 1) * limit
        val total: Int
        val nextOffset: Int
        val serverTotal = parsed.total
        if (serverTotal != null) {
            total = serverTotal
            nextOffset = when {
                parsed.users.isEmpty() -> base
                nextPage < serverTotal -> nextPage
                else -> base + step
            }
        } else if (parsed.users.size < limit) {
            nextOffset = base + step
            total = nextOffset
        } else {
            nextOffset = nextPage
            total = nextOffset + 1
        }
        return DecodedPage(CatalogPage(rooms = rooms, total = total, nextOffset = nextOffset), parsed.users.size)
    }

    private data class Parsed(val users: List<Cam4User>, val total: Int?)

    private fun parse(data: ByteArray): Parsed {
        val element = try {
            json.parseToJsonElement(data.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw CatalogFailure.Malformed
        }
        return when (element) {
            is JsonArray -> Parsed(users = usersOf(element), total = null)
            is JsonObject -> {
                val listElement = element["users"] ?: element["rooms"]
                val totalElement = element["totalCount"] ?: element["userCount"]
                if (listElement == null && totalElement == null) throw CatalogFailure.Malformed
                val users = when (listElement) {
                    null -> emptyList()
                    is JsonArray -> usersOf(listElement)
                    else -> throw CatalogFailure.Malformed
                }
                Parsed(users, intValue(totalElement))
            }
            else -> throw CatalogFailure.Malformed
        }
    }

    private fun usersOf(array: JsonArray): List<Cam4User> =
        array.map { item ->
            if (item !is JsonObject) throw CatalogFailure.Malformed
            try {
                json.decodeFromJsonElement(Cam4User.serializer(), item)
            } catch (_: Exception) {
                throw CatalogFailure.Malformed
            }
        }

    private fun intValue(element: JsonElement?): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString) return null
        primitive.longOrNull?.let { value ->
            if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return value.toInt()
        }
        return primitive.doubleOrNull?.takeIf { it.isFinite() }?.toInt()
    }

    private val json = Json { ignoreUnknownKeys = true }
}

/** 逗号分隔字符串、字符串数组或数字数组。解析结果是大写 alpha-2。 */
object Cam4Regions {
    fun parse(parts: List<String>?): Set<String> {
        if (parts == null) return emptySet()
        val out = LinkedHashSet<String>()
        for (part in parts) {
            for (piece in part.split(',')) {
                val alpha = canonical(piece) ?: continue
                out.add(alpha)
            }
        }
        return out
    }

    fun parse(raw: JsonElement?): Set<String> = parse(partsOf(raw))

    /**
     * 观看地区为空不过滤。两边都先归一成 alpha-2 再看交集
     * （设备侧是 alpha-2，房间侧可能还是数字码）。
     */
    fun isBlocked(blocked: Set<String>, viewer: Set<String>): Boolean {
        if (viewer.isEmpty() || blocked.isEmpty()) return false
        val left = blocked.mapNotNull(::canonical).toSet()
        val right = viewer.mapNotNull(::canonical).toSet()
        return left.intersect(right).isNotEmpty()
    }

    internal fun canonical(raw: String): String? {
        val trimmed = raw.trimSwiftWhitespace()
        if (trimmed.isEmpty()) return null
        if (trimmed.length == 2 && trimmed.all { it.isLetter() }) {
            val upper = trimmed.uppercase(Locale.ROOT)
            return if (ISO3166.alpha2ToNumeric[upper] == null) null else upper
        }
        return ISO3166.alpha2(trimmed)
    }

    private fun partsOf(raw: JsonElement?): List<String> {
        if (raw == null || raw is JsonNull) return emptyList()
        return when (raw) {
            is JsonArray -> raw.mapNotNull(::primitiveText)
            is JsonPrimitive -> primitiveText(raw)?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
    }

    private fun primitiveText(element: JsonElement): String? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (primitive.isString) return primitive.content
        primitive.longOrNull?.let { return it.toString() }
        primitive.doubleOrNull?.let { if (it.isFinite()) return it.toLong().toString() }
        return null
    }
}

@Serializable
internal data class Cam4User(
    val username: String? = null,
    val gender: String? = null,
    val age: FlexInt? = null,
    val countryCode: String? = null,
    val privateRoom: Boolean? = null,
    val hlsPreviewUrl: String? = null,
    val snapshotImageLink: String? = null,
    val profileImageLink: String? = null,
    val defaultImageLink: String? = null,
    val blockedRegions: JsonElement? = null,
    val viewers: FlexInt? = null,
    val showTags: List<String>? = null,
    val broadcastType: String? = null,
    val languages: List<String>? = null,
) {
    fun room(now: Instant, viewerRegions: Set<String>): Room? {
        val rawName = username?.trimSwiftWhitespace() ?: return null
        val key = RoomKey(SiteID.cam4, rawName) ?: return null
        val blocked = Cam4Regions.parse(blockedRegions)
        val geo = Cam4Regions.isBlocked(blocked, viewerRegions)
        val isPrivate = privateRoom == true
        val stream = if (geo || isPrivate) null else Cam4Links.https(hlsPreviewUrl)
        val snapshot = Cam4Links.https(snapshotImageLink)
        val show = if (isPrivate && !geo) ShowState.privateShow else ShowState.publicShow
        val expires = Cam4Links.expiry(profileImageLink, snapshot != null, now)
        return Room(
            username = key.name,
            displayName = key.name,
            viewers = viewers?.value.toIntExactOrNull() ?: 0,
            gender = Cam4Links.gender(gender),
            tags = showTags.orEmpty(),
            show = show,
            location = Cam4Links.location(countryCode),
            thumbnailURL = snapshot,
            age = age?.value.toIntExactOrNull(),
            site = SiteID.cam4,
            stream = stream,
            thumbnailExpires = expires,
            blockedRegions = blocked,
        )
    }
}

internal object Cam4Links {
    fun https(raw: String?): URI? {
        if (raw.isNullOrEmpty()) return null
        val fixed = raw.replace("\\/", "/")
        val parsed = try {
            URI(fixed)
        } catch (_: URISyntaxException) {
            return null
        }
        if (parsed.scheme != "https" || parsed.host.isNullOrEmpty()) return null
        return parsed
    }

    fun gender(raw: String?): Gender? = when (raw?.trimSwiftWhitespace()?.lowerRoot()) {
        "female" -> Gender.female
        "male" -> Gender.male
        "couple" -> Gender.couple
        "shemale", "trans", "transgender" -> Gender.trans
        else -> null
    }

    fun location(raw: String?): String {
        val trimmed = raw?.trimSwiftWhitespace().orEmpty()
        if (trimmed.length != 2 || trimmed.any { !it.isLetter() }) return ""
        return trimmed.uppercase(Locale.ROOT)
    }

    /** `profileImageLink` 的 `validto` 是秒。没有则用截图的固定 TTL。毫秒（>1e11）不认。 */
    fun expiry(profile: String?, hasSnapshot: Boolean, now: Instant): Instant? {
        validto(profile)?.let { return it }
        return if (hasSnapshot) now.plusSeconds(Cam4Directory.snapshotTTL) else null
    }

    fun validto(raw: String?): Instant? {
        if (raw.isNullOrEmpty()) return null
        val fixed = raw.replace("\\/", "/")
        val parsed = try {
            URI(fixed)
        } catch (_: URISyntaxException) {
            return null
        }
        val query = parsed.rawQuery ?: return null
        val value = query.split('&').firstNotNullOfOrNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@firstNotNullOfOrNull null
            val name = part.substring(0, eq)
            if (!name.equals("validto", ignoreCase = true)) return@firstNotNullOfOrNull null
            decodeQuery(part.substring(eq + 1))
        } ?: return null
        val seconds = value.toDoubleOrNull() ?: return null
        if (!seconds.isFinite() || seconds < 1_000_000_000.0 || seconds >= 100_000_000_000.0) return null
        return Instant.ofEpochSecond(seconds.toLong())
    }

    private fun decodeQuery(raw: String): String = try {
        java.net.URLDecoder.decode(raw, Charsets.UTF_8)
    } catch (_: Exception) {
        raw
    }
}
