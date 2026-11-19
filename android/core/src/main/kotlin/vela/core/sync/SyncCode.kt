package vela.core.sync

/** 同步码：`vela2:` + k43 + `.` + c6 + `@` + origin。校验和由调用方注入。 */
data class SyncCode(val key: ByteArray, val origin: String) {
    val host: String
        get() {
            val rest = origin.removePrefix("https://")
            val colon = rest.indexOf(':')
            return if (colon < 0) rest else rest.substring(0, colon)
        }

    override fun equals(other: Any?): Boolean {
        val o = other as? SyncCode ?: return false
        return key.contentEquals(o.key) && origin == o.origin
    }

    override fun hashCode(): Int = key.contentHashCode() * 31 + origin.hashCode()

    companion object {
        const val PREFIX = "vela2:"

        fun parse(text: String, sha256: (ByteArray) -> ByteArray): SyncCode? {
            val b = text.toByteArray(Charsets.UTF_8)
            val p = PREFIX.toByteArray(Charsets.US_ASCII)
            if (b.size <= p.size + 43 + 1 + 6 + 1 || !b.copyOfRange(0, p.size).contentEquals(p)) return null
            val kStart = p.size
            val dot = kStart + 43
            val cStart = dot + 1
            val at = cStart + 6
            if (b[dot] != '.'.code.toByte() || b[at] != '@'.code.toByte()) return null
            val k43 = String(b, kStart, 43, Charsets.UTF_8)
            val c6 = String(b, cStart, 6, Charsets.UTF_8)
            val origin = String(b, at + 1, b.size - at - 1, Charsets.UTF_8)
            val key = SyncEncoding.decodeBase64url(k43) ?: return null
            if (key.size != 32) return null
            if (!isCanonicalOrigin(origin)) return null
            if (c6 != checksum(k43, origin, sha256)) return null
            return SyncCode(key, origin)
        }

        fun format(key: ByteArray, origin: String, sha256: (ByteArray) -> ByteArray): String? {
            if (key.size != 32 || !isCanonicalOrigin(origin)) return null
            val k43 = SyncEncoding.base64url(key)
            return PREFIX + k43 + "." + checksum(k43, origin, sha256) + "@" + origin
        }

        fun checksum(k43: String, origin: String, sha256: (ByteArray) -> ByteArray): String {
            val input = (PREFIX + k43 + "@" + origin).toByteArray(Charsets.US_ASCII)
            return SyncEncoding.hex(sha256(input).copyOfRange(0, 3))
        }

        fun isCanonicalOrigin(origin: String): Boolean {
            val scheme = "https://"
            if (!origin.startsWith(scheme)) return false
            var hostPart = origin.substring(scheme.length)
            val colon = hostPart.indexOf(':')
            if (colon >= 0) {
                val portText = hostPart.substring(colon + 1)
                val port = SyncEncoding.parseDecimal(portText) ?: return false
                if (port !in 1..65535 || port == 443L) return false
                hostPart = hostPart.substring(0, colon)
            }
            return isDnsName(hostPart.toByteArray(Charsets.UTF_8))
        }

        private fun isDnsName(host: ByteArray): Boolean {
            if (host.isEmpty() || host.size > 253) return false
            if (host.any { it == ':'.code.toByte() }) return false
            val labels = ArrayList<ByteArray>()
            var start = 0
            for (i in host.indices) {
                if (host[i] == '.'.code.toByte()) {
                    labels.add(host.copyOfRange(start, i))
                    start = i + 1
                }
            }
            labels.add(host.copyOfRange(start, host.size))
            if (labels.size < 2) return false
            for (label in labels) {
                if (label.size !in 1..63) return false
                if (label.first() == '-'.code.toByte() || label.last() == '-'.code.toByte()) return false
                for (c in label) {
                    val v = c.toInt() and 0xFF
                    val ok = v in 97..122 || v in 48..57 || v == '-'.code
                    if (!ok) return false
                }
            }
            // 能被解析成 IP 的写法（第 4.3 节、第 23 节第 10 条）：任何标签不得全为数字，
            // 也不得是 0x 加十六进制；顶级标签首字符必须是字母。
            for (label in labels) {
                if (label.all { (it.toInt() and 0xFF) in 48..57 }) return false
                if (isHexLabel(label)) return false
            }
            val first = labels.last().first().toInt() and 0xFF
            return first in 97..122
        }

        private fun isHexLabel(label: ByteArray): Boolean {
            if (label.size < 2) return false
            if (label[0] != '0'.code.toByte() || label[1] != 'x'.code.toByte()) return false
            for (i in 2 until label.size) {
                val v = label[i].toInt() and 0xFF
                if (v !in 48..57 && v !in 97..102) return false
            }
            return true
        }

        fun fingerprint(blobID: String): String {
            val head = blobID.take(8).uppercase()
            return head.take(4) + "-" + head.drop(4)
        }
    }
}
