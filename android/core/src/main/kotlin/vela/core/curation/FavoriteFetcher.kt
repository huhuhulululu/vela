package vela.core.curation

import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.HTTPClient
import vela.core.base.net.SiteEndpoint
import vela.core.base.site.CatalogFailure
import vela.core.base.site.ChatContext
import vela.core.base.site.FavoriteStatus

data class FavoriteFetchResult(
    val statuses: Map<String, FavoriteStatus>,
    val challenged: Boolean,
)

/** 逐个查询收藏房间，并发有上限。遇到站点拦截立刻停止发起新请求。 */
class FavoriteFetcher(
    private val client: HTTPClient,
    concurrency: Int = maxConcurrent,
    private val now: () -> Instant = { Instant.now() },
    private val backoff: EndpointBackoffBox? = EndpointBackoffBox.shared,
) {
    private val limit = maxOf(1, minOf(concurrency, maxConcurrent))

    suspend fun fetch(names: List<String>): FavoriteFetchResult = coroutineScope {
        val statuses = LinkedHashMap<String, FavoriteStatus>()
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
                is Outcome.Status -> statuses[key] = outcome.status
                Outcome.Challenge -> challenged = true
                Outcome.Failed -> Unit
            }
            if (!challenged && coroutineContext.isActive) startNext()
        }
        FavoriteFetchResult(statuses, challenged)
    }

    /** 退避窗口内不发请求。正文不是 JSON 也不是质询页（404、401 除外）时登记退避，状态未知。 */
    private suspend fun lookup(name: String): Outcome {
        val box = backoff
        if (box != null && !box.allows(SiteEndpoint.chatContext)) return Outcome.Failed
        return try {
            val result = client.send(ChatContext.request(name))
            if (box?.note(
                    SiteEndpoint.chatContext,
                    result.status,
                    result.data,
                    ChatContext.definitiveStatuses,
                ) == true
            ) {
                Outcome.Failed
            } else {
                Outcome.Status(ChatContext.decode(result.status, result.data, now()))
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
        data class Status(val status: FavoriteStatus) : Outcome

        data object Failed : Outcome

        data object Challenge : Outcome
    }

    companion object {
        const val maxConcurrent: Int = 4
    }
}
