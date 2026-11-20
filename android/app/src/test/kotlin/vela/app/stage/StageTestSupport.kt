package vela.app.stage

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult

/** 测试可拨动的钟。目录与停留都读它，不碰墙钟。 */
class ManualClock(start: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
    var instant: Instant = start

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = instant
}

/**
 * 不连网的目录客户端。偏移大于 0 的续页停在 [continueRest]，让测试先划走再放行。
 * 只回合成 JSON，不访问直播站点。
 */
class FakeCatalogHttp(
    private val pages: Map<Int, ByteArray>,
    private val holdRest: Boolean = false,
) : HTTPClient {
    val urls: MutableList<String> = mutableListOf()
    val continueRest = CompletableDeferred<Unit>()

    override suspend fun send(request: HTTPRequest): HTTPResult {
        urls.add(request.url)
        val offset = OFFSET.find(request.url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (holdRest && offset > 0) continueRest.await()
        val body = pages[offset] ?: error("没有偏移 $offset 的合成页：${request.url}")
        return HTTPResult(status = 200, data = body, headers = emptyMap())
    }

    fun releaseRest() {
        continueRest.complete(Unit)
    }

    companion object {
        private val OFFSET = Regex("""[?&]offset=(\d+)""")
    }
}

internal fun catalogJson(names: List<String>, total: Int = names.size): ByteArray {
    val rooms = names.joinToString(",") { name ->
        val tag = if (name == "ann") "synth" else "other"
        """{"username":"$name","current_show":"public","num_users":4,"tags":["$tag"]}"""
    }
    return """{"rooms":[$rooms],"total_count":$total}""".toByteArray(Charsets.UTF_8)
}

internal fun namedRooms(count: Int, from: Int = 0): List<String> =
    (from until from + count).map { "room%02d".format(it) }

internal class StageHarness(
    val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "stage-test")
    }.asCoroutineDispatcher(),
) {
    fun <T> onStage(block: suspend () -> T): T = runBlocking(dispatcher) { block() }

    fun close() {
        dispatcher.close()
    }
}

internal suspend fun StageViewModel.awaitUntil(
    limit: Int = 1_000,
    predicate: (StageUiState) -> Boolean,
) {
    repeat(limit) {
        if (predicate(state.value)) return
        yield()
    }
    error("舞台没有在时限内到达期望状态，房间数=${state.value.rooms.size}")
}

internal fun deleteTree(directory: Path) {
    val file = directory.toFile()
    if (file.isDirectory) file.deleteRecursively() else file.delete()
}
