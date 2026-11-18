package vela.app.spike

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

internal data class SpikeHttpResponse(val code: Int, val body: ByteArray)

/**
 * 直接走 Socket，避开 Robolectric 对 URLStreamHandler 的拦截。
 * 伪上游是 JDK HttpServer，这里只发 HTTP/1.1 并读完 Content-Length。
 */
internal object SpikeHttp {
    fun get(url: String, readTimeoutMs: Int = 8_000): SpikeHttpResponse {
        val uri = URI(url)
        val port = if (uri.port == -1) 80 else uri.port
        val path = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            if (!uri.rawQuery.isNullOrEmpty()) {
                append('?')
                append(uri.rawQuery)
            }
        }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, port), 3_000)
            socket.soTimeout = readTimeoutMs
            val request = "GET $path HTTP/1.1\r\nHost: ${uri.host}:$port\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            return readResponse(socket)
        }
    }

    private fun readResponse(socket: Socket): SpikeHttpResponse {
        val input = socket.getInputStream()
        val header = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var splitAt = -1
        while (splitAt < 0) {
            val n = input.read(buf)
            if (n < 0) break
            header.write(buf, 0, n)
            splitAt = indexOfHeaderEnd(header.toByteArray())
            if (header.size() > 256 * 1024) error("响应头过大")
        }
        val raw = header.toByteArray()
        if (splitAt < 0) error("上游没有返回完整响应头")
        val head = String(raw, 0, splitAt, Charsets.ISO_8859_1)
        val preBody = raw.copyOfRange(splitAt + 4, raw.size)
        val statusLine = head.lineSequence().first()
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: error("无法解析状态行 $statusLine")
        val length = head.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.toIntOrNull()
            ?: preBody.size
        val body = ByteArrayOutputStream()
        body.write(preBody)
        var left = length - preBody.size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size, left))
            if (n < 0) break
            body.write(buf, 0, n)
            left -= n
        }
        return SpikeHttpResponse(code, body.toByteArray())
    }

    private fun indexOfHeaderEnd(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == '\r'.code.toByte() &&
                bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() &&
                bytes[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }
}
