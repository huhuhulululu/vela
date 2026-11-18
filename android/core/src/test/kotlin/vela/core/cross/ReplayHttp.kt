package vela.core.cross

import java.util.ArrayDeque
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult

/** 按顺序回放响应。并发发送时串行取下一条，对齐 Swift 测试里的锁。 */
internal class ReplayClient(queue: List<HTTPResult>) : HTTPClient {
    private val queue = ArrayDeque(queue)
    private val lock = Any()
    var count: Int = 0
        private set

    override suspend fun send(request: HTTPRequest): HTTPResult = synchronized(lock) {
        count += 1
        if (queue.isEmpty()) HTTPResult(status = 200, data = "{}".toByteArray()) else queue.removeFirst()
    }
}

internal fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)
