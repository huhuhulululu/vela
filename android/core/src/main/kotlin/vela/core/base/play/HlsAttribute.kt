package vela.core.base.play

/** HLS 属性，值原样保留（含引号）。 */
data class HLSAttribute(
    var key: String,
    var value: String,
) {
    val unquoted: String
        get() = if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value.substring(1, value.length - 1)
        } else {
            value
        }

    companion object {
        /**
         * 解析 `KEY=VALUE,KEY="a,b"`，引号里的逗号不分割。
         * 不处理反斜杠转义，和 Swift 一样。
         */
        fun parse(text: CharSequence): List<HLSAttribute> {
            val result = mutableListOf<HLSAttribute>()
            val token = StringBuilder()
            var quoted = false
            fun flush() {
                val raw = token.toString()
                val eq = raw.indexOf('=')
                if (eq >= 0) {
                    result.add(HLSAttribute(raw.substring(0, eq), raw.substring(eq + 1)))
                }
                token.setLength(0)
            }
            for (character in text) {
                if (character == '"') quoted = !quoted
                if (character == ',' && !quoted) flush() else token.append(character)
            }
            flush()
            return result
        }

        fun value(key: String, attributes: List<HLSAttribute>): String? =
            attributes.firstOrNull { it.key == key }?.unquoted
    }
}
