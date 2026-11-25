package vela.app.pip

/**
 * 解析 `dumpsys audio` 的当前音频焦点栈（不含历史 Events log）。
 * 严格区分 `GAIN_TRANSIENT` 与永久 `GAIN`、`GAIN_TRANSIENT_MAY_DUCK`、`GAIN_TRANSIENT_EXCLUSIVE`，
 * 并按字段检查 `loss:`，避免被 `flags: PAUSES_ON_DUCKABLE_LOSS` 里的 `LOSS` 子串误判。
 */
internal object AudioFocusDump {
    private val gainField = Regex("""\bgain\s*:\s*([A-Z0-9_]+)""", RegexOption.IGNORE_CASE)
    private val lossField = Regex("""\bloss\s*:\s*([A-Z0-9_]+)""", RegexOption.IGNORE_CASE)
    private val fallbackGainTransient = Regex("""\b(?:AUDIOFOCUS_)?GAIN_TRANSIENT\b""")
    private val fallbackLossToken = Regex("""\b(?:AUDIOFOCUS_)?LOSS(?:_TRANSIENT(?:_CAN_DUCK)?)?\b""")

    fun stackLines(dump: String): List<String> = dump.lineSequence()
        .dropWhile {
            !it.contains("Audio Focus stack entries", ignoreCase = true) &&
                !it.contains("Audio Focus", ignoreCase = true)
        }
        .drop(1)
        .takeWhile { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() &&
                !trimmed.startsWith("Audio Focus stack entry count") &&
                !trimmed.startsWith("No external focus policy") &&
                !trimmed.startsWith("Events log") &&
                !trimmed.startsWith("Audio dumping") &&
                !trimmed.startsWith("Audio Policy") &&
                !trimmed.startsWith("Focus requests") &&
                !trimmed.startsWith("Multi Audio Focus")
        }
        .toList()

    fun packageLines(dump: String, packageName: String = "vela.app"): List<String> {
        val packPattern = Regex(
            """(?:pack|package)\s*:\s*${Regex.escape(packageName)}(?:\b|$)""",
            RegexOption.IGNORE_CASE,
        )
        return stackLines(dump).filter { line ->
            packPattern.containsMatchIn(line) ||
                (line.contains(packageName) && line.contains("client", ignoreCase = true))
        }
    }

    fun packageInStack(dump: String, packageName: String = "vela.app"): Boolean =
        packageLines(dump, packageName).isNotEmpty()

    fun hasGainTransient(dump: String, packageName: String = "vela.app"): Boolean =
        packageLines(dump, packageName).any(::lineHoldsGainTransient)

    private fun lineHoldsGainTransient(line: String): Boolean {
        val loss = lossField.find(line)?.groupValues?.get(1)?.lowercase()
        if (loss != null && loss != "none" && loss != "0") {
            return false
        }
        val gain = gainField.find(line)?.groupValues?.get(1)?.uppercase()
        if (gain != null) {
            return gain == "GAIN_TRANSIENT" || gain == "AUDIOFOCUS_GAIN_TRANSIENT" || gain == "2"
        }
        if (loss == null && fallbackLossToken.containsMatchIn(line)) {
            return false
        }
        return fallbackGainTransient.containsMatchIn(line)
    }
}
