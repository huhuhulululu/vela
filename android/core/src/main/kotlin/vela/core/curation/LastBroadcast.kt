package vela.core.curation

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import vela.core.base.model.timeIntervalSince1970
import vela.core.base.model.trimSwiftWhitespace
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPHeaders
import vela.core.base.net.HTTPRequest
import vela.core.base.net.SiteEndpoint
import vela.core.base.net.isPathAllowed
import vela.core.base.net.percentEncode
import vela.core.base.site.CatalogFailure
import vela.core.base.site.looksLikeChallenge

/** 一位主播的上次开播时间。`at` 为 null 表示接口没给出（查过，但不知道）。 */
data class LastBroadcast(val at: Instant?)

/**
 * `biocontext`：按用户名取主播资料，只用 `last_broadcast`（缺失时退到 `time_since_last_broadcast`）。
 * `last_broadcast` 接受 ISO 8601、`yyyy-MM-dd HH:mm:ss`（UTC）或秒级 / 毫秒级 Unix 时间。
 * `time_since_last_broadcast` 只接受秒数。
 */
object BioContext {
    fun url(username: String): String {
        val slug = percentEncode(username, ::isPathAllowed)
        return "https://chaturbate.com/api/biocontext/$slug/"
    }

    fun request(username: String): HTTPRequest {
        val drafted = HTTPRequest(url = url(username), method = "GET")
        return HTTPHeaders.applyBrowser(drafted, referer = "https://chaturbate.com/$username/").withTimeout(8.0)
    }

    /** 200 解析时间；404、401 与 403 带 JSON 说明算没有可用时间；站点质询抛 [CatalogFailure.Challenge]。 */
    fun decode(status: Int, data: ByteArray, now: Instant): LastBroadcast {
        if (looksLikeChallenge(data)) throw CatalogFailure.Challenge
        val element = try {
            Json.parseToJsonElement(data.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
        val obj = element as? JsonObject
        return when (status) {
            200 -> {
                if (obj == null) throw CatalogFailure.Malformed
                LastBroadcast(lastBroadcast(obj, now))
            }
            401, 404 -> LastBroadcast(null)
            403 -> {
                val detail = obj?.get("detail")
                if (detail is JsonPrimitive && detail.isString) LastBroadcast(null) else throw CatalogFailure.Challenge
            }
            else -> throw CatalogFailure.Status(status)
        }
    }

    internal fun lastBroadcast(obj: JsonObject, now: Instant): Instant? {
        parseDate(obj["last_broadcast"])?.let { date ->
            return if (date.isAfter(now)) now else date
        }
        val seconds = jsonNumber(obj["time_since_last_broadcast"])
        if (seconds != null && seconds >= 0.0) {
            return now.minusNanos(secondsToNanos(seconds))
        }
        return null
    }

    internal fun parseDate(value: JsonElement?): Instant? {
        val seconds = jsonNumber(value)
        if (seconds != null && seconds > 0.0) {
            val epoch = if (seconds > 100_000_000_000.0) seconds / 1000.0 else seconds
            return instantOfEpochSeconds(epoch)
        }
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trimSwiftWhitespace()
        if (text.isNullOrEmpty()) return null
        try {
            return Instant.parse(text)
        } catch (_: DateTimeParseException) {
            // 再试没有时区、或空格分隔的写法。
        }
        for (formatter in formatters) {
            try {
                return formatter.parse(text, Instant::from)
            } catch (_: DateTimeParseException) {
                // 下一个格式。
            } catch (_: DateTimeException) {
                // 下一个格式。
            }
        }
        return null
    }

    /** 数字或纯数字字符串。布尔不算。 */
    internal fun jsonNumber(value: JsonElement?): Double? {
        if (value == null || value is JsonNull) return null
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) {
            val text = primitive.content.trim { it == ' ' || it == '\t' }
            return text.toDoubleOrNull()
        }
        if (primitive.booleanOrNull != null) return null
        return primitive.doubleOrNull
    }

    private val formatters: List<DateTimeFormatter> = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
    ).map { pattern ->
        DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(ZoneOffset.UTC)
    }

    private fun instantOfEpochSeconds(seconds: Double): Instant {
        val whole = seconds.toLong()
        var nanos = ((seconds - whole.toDouble()) * 1_000_000_000.0).toLong()
        var sec = whole
        if (nanos < 0) {
            sec -= 1
            nanos += 1_000_000_000L
        } else if (nanos >= 1_000_000_000L) {
            sec += 1
            nanos -= 1_000_000_000L
        }
        return Instant.ofEpochSecond(sec, nanos)
    }

    private fun secondsToNanos(seconds: Double): Long {
        val raw = seconds * 1_000_000_000.0
        if (raw >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
        if (raw <= Long.MIN_VALUE.toDouble()) return Long.MIN_VALUE
        return raw.toLong()
    }
}

/** 「上次开播 3 小时前」一类中文相对时间。 */
object BroadcastAgo {
    fun string(since: Instant, now: Instant): String {
        val delta = now.timeIntervalSince1970() - since.timeIntervalSince1970()
        val seconds = max(0, delta.toInt())
        val minute = 60
        val hour = 3_600
        val day = 86_400
        val phrase = when {
            seconds < minute -> "刚刚"
            seconds < hour -> "${seconds / minute} 分钟前"
            seconds < day -> "${seconds / hour} 小时前"
            seconds < 30 * day -> "${seconds / day} 天前"
            seconds < 365 * day -> "${seconds / (30 * day)} 个月前"
            else -> "${seconds / (365 * day)} 年前"
        }
        return "上次开播 $phrase"
    }
}

/** 上次开播时间的缓存。30 分钟内不重查。不落盘。 */
class LastBroadcastCache private constructor(
    private val stored: Map<String, Entry>,
) {
    constructor() : this(emptyMap())

    private data class Entry(val broadcast: LastBroadcast, val checkedAt: Instant)

    /** 有时间的条目（不论新旧），键为小写用户名。 */
    val dates: Map<String, Instant>
        get() {
            val out = LinkedHashMap<String, Instant>()
            for ((key, entry) in stored) {
                val at = entry.broadcast.at ?: continue
                out[key] = at
            }
            return out
        }

    /** 这些名字里还没有新鲜结果的（保持原顺序，按小写去重）。 */
    fun pending(names: List<String>, now: Instant): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (name in names) {
            val key = Curation.key(name)
            if (!seen.add(key)) continue
            val entry = stored[key]
            if (entry == null || now.timeIntervalSince1970() - entry.checkedAt.timeIntervalSince1970() > ttl) {
                out.add(name)
            }
        }
        return out
    }

    fun storing(values: Map<String, LastBroadcast>, at: Instant): LastBroadcastCache {
        if (values.isEmpty()) return this
        val next = stored.toMutableMap()
        for ((key, value) in values) {
            next[Curation.key(key)] = Entry(value, at)
        }
        return LastBroadcastCache(next)
    }

    fun pruned(keeping: List<String>): LastBroadcastCache {
        val keep = keeping.map { Curation.key(it) }.toHashSet()
        return LastBroadcastCache(stored.filterKeys { it in keep })
    }

    companion object {
        const val ttl: Double = 1_800.0
    }
}

data class LastBroadcastFetchResult(
    val found: Map<String, LastBroadcast>,
    val challenged: Boolean,
)

/** 逐个查上次开播时间，并发上限沿用收藏查询。遇到站点拦截立刻停止发起新请求。 */
class LastBroadcastFetcher(
    private val client: HTTPClient,
    concurrency: Int = FavoriteFetcher.maxConcurrent,
    private val now: () -> Instant = { Instant.now() },
    private val backoff: EndpointBackoffBox? = EndpointBackoffBox.shared,
) {
    private val limit = maxOf(1, minOf(concurrency, FavoriteFetcher.maxConcurrent))

    suspend fun fetch(names: List<String>): LastBroadcastFetchResult = coroutineScope {
        val found = LinkedHashMap<String, LastBroadcast>()
        var challenged = false
        var index = 0
        val running = ArrayList<Deferred<Pair<String, Outcome>>>()

        fun startNext() {
            if (index >= names.size) return
            val name = names[index]
            index += 1
            running.add(async { Curation.key(name) to lookup(name) })
        }

        repeat(minOf(limit, names.size)) { startNext() }
        while (running.isNotEmpty()) {
            val finished = select {
                for (deferred in running) {
                    deferred.onAwait { value -> deferred to value }
                }
            }
            running.remove(finished.first)
            val (key, outcome) = finished.second
            when (outcome) {
                is Outcome.Value -> found[key] = outcome.value
                Outcome.Challenge -> challenged = true
                Outcome.Failed -> Unit
            }
            if (!challenged && coroutineContext.isActive) startNext()
        }
        LastBroadcastFetchResult(found, challenged)
    }

    /** 退避窗口内不发请求。正文不是 JSON 也不是质询页（404、401 除外）登记退避，不写缓存。 */
    private suspend fun lookup(name: String): Outcome {
        val box = backoff
        if (box != null && !box.allows(SiteEndpoint.bioContext)) return Outcome.Failed
        return try {
            val result = client.send(BioContext.request(name))
            if (box?.note(SiteEndpoint.bioContext, result.status, result.data, definitive) == true) {
                Outcome.Failed
            } else {
                Outcome.Value(BioContext.decode(result.status, result.data, now()))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: CatalogFailure.Challenge) {
            Outcome.Challenge
        } catch (_: Exception) {
            Outcome.Failed
        }
    }

    private sealed interface Outcome {
        data class Value(val value: LastBroadcast) : Outcome

        data object Failed : Outcome

        data object Challenge : Outcome
    }

    companion object {
        val definitive: Set<Int> = setOf(401, 404)
    }
}
