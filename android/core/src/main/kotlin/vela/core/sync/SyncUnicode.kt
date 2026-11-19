package vela.core.sync

import java.io.File
import java.text.Normalizer

/**
 * 标签规范化里依赖 Unicode 数据的部分（第 6.3 节）。
 * 真机可用 [AndroidIcuSyncUnicode]（反射调用 android.icu，避免 core 这个 JVM 模块直接依赖 Android SDK）；
 * JVM 测试注入 [Unicode13]，数据文件与 S0 生成器内嵌的 Unicode 13.0 表相同。
 */
interface SyncUnicode {
    /** 码点在 Unicode 13.0 已分配且年龄不晚于 13.0。未分配返回 false。 */
    fun ageAtMost13(cp: Int): Boolean
    fun isWhiteSpace(cp: Int): Boolean
    /** 不考虑上下文的完整小写映射。没有特殊映射时返回单码点本身。 */
    fun lowercase(cp: Int): IntArray
    fun nfc(s: String): String
}

/**
 * 设备上的 android.icu。类不存在时 [available] 为 false，调用方应改用 [Unicode13]。
 * 年龄用 `UCharacter.getAge`，空白用 `UProperty.WHITE_SPACE`，小写按码点调用 `String.lowercase(ROOT)`，
 * NFC 用 `java.text.Normalizer`。
 */
class AndroidIcuSyncUnicode : SyncUnicode {
    private val uCharacter = Class.forName("android.icu.lang.UCharacter")
    private val whiteSpace: Int = Class.forName("android.icu.lang.UProperty").getField("WHITE_SPACE").getInt(null)
    private val getAge = uCharacter.getMethod("getAge", Int::class.javaPrimitiveType)
    private val hasBinary = uCharacter.getMethod(
        "hasBinaryProperty",
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    )

    override fun ageAtMost13(cp: Int): Boolean {
        val age = getAge.invoke(null, cp) ?: return false
        val major = age.javaClass.getMethod("getMajor").invoke(age) as Int
        val minor = age.javaClass.getMethod("getMinor").invoke(age) as Int
        return major < 13 || (major == 13 && minor == 0)
    }

    override fun isWhiteSpace(cp: Int): Boolean = hasBinary.invoke(null, cp, whiteSpace) as Boolean

    override fun lowercase(cp: Int): IntArray {
        val mapped = String(Character.toChars(cp)).lowercase(java.util.Locale.ROOT)
        return mapped.codePoints().toArray()
    }

    override fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    companion object {
        fun available(): Boolean = try {
            Class.forName("android.icu.lang.UCharacter")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}

/**
 * Unicode 13.0 BMP 表，算法与 `scripts/sync/vectors/unicode.go` 一致。
 * 小写用无条件完整映射（SpecialCasing），不用 Final_Sigma 等上下文规则。
 */
class Unicode13 private constructor(
    private val ageOk: BooleanArray,
    private val white: BooleanArray,
    private val ccc: ByteArray,
    private val lowerMap: Map<Int, IntArray>,
    private val decomp: Map<Int, IntArray>,
    private val comp: Map<Long, Int>,
) : SyncUnicode {
    override fun ageAtMost13(cp: Int): Boolean = cp in 0..0xFFFF && ageOk[cp]
    override fun isWhiteSpace(cp: Int): Boolean = cp in 0..0xFFFF && white[cp]

    override fun lowercase(cp: Int): IntArray = lowerMap[cp] ?: intArrayOf(cp)

    override fun nfc(s: String): String {
        val cps = s.codePoints().toArray()
        val nfd = ArrayList<Int>(cps.size)
        for (cp in cps) decompOne(cp, nfd)
        var i = 1
        while (i < nfd.size) {
            var j = i
            while (j > 0) {
                val cj = cccOf(nfd[j])
                val cj1 = cccOf(nfd[j - 1])
                if (cj == 0 || cj1 == 0 || cj1 <= cj) break
                val tmp = nfd[j]
                nfd[j] = nfd[j - 1]
                nfd[j - 1] = tmp
                j--
            }
            i++
        }
        if (nfd.isEmpty()) return ""
        val out = ArrayList<Int>(nfd.size)
        out.add(nfd[0])
        var lastCcc = cccOf(nfd[0])
        var starter = if (lastCcc == 0) 0 else -1
        for (idx in 1 until nfd.size) {
            val r = nfd[idx]
            val cc = cccOf(r)
            var composed = false
            if (starter >= 0 && (lastCcc < cc || lastCcc == 0)) {
                val cp = tryCompose(out[starter], r)
                if (cp != null) {
                    out[starter] = cp
                    composed = true
                }
            }
            if (!composed) {
                out.add(r)
                if (cc == 0) starter = out.size - 1
                lastCcc = cc
            }
        }
        val sb = StringBuilder()
        for (cp in out) sb.appendCodePoint(cp)
        return sb.toString()
    }

    private fun cccOf(cp: Int): Int = if (cp in 0..0xFFFF) ccc[cp].toInt() and 0xFF else 0

    private fun decompOne(cp: Int, into: MutableList<Int>) {
        if (isHangulS(cp)) {
            var s = cp - HANGUL_S
            val t = s % HANGUL_T
            s /= HANGUL_T
            into.add(HANGUL_L + s / HANGUL_V)
            into.add(HANGUL_V_BASE + s % HANGUL_V)
            if (t != 0) into.add(HANGUL_T_BASE + t)
            return
        }
        val d = decomp[cp]
        if (d != null) {
            for (p in d) decompOne(p, into)
            return
        }
        into.add(cp)
    }

    private fun tryCompose(a: Int, b: Int): Int? {
        composeHangul(a, b)?.let { return it }
        return comp[a.toLong() shl 21 or b.toLong()]
    }

    private fun composeHangul(a: Int, b: Int): Int? {
        if (a in HANGUL_L until HANGUL_L + HANGUL_L_COUNT && b in HANGUL_V_BASE until HANGUL_V_BASE + HANGUL_V) {
            val li = a - HANGUL_L
            val vi = b - HANGUL_V_BASE
            return HANGUL_S + (li * HANGUL_V + vi) * HANGUL_T
        }
        if (isHangulS(a) && b > HANGUL_T_BASE && b < HANGUL_T_BASE + HANGUL_T) {
            if ((a - HANGUL_S) % HANGUL_T == 0) return a + (b - HANGUL_T_BASE)
        }
        return null
    }

    companion object {
        private const val HANGUL_S = 0xAC00
        private const val HANGUL_L = 0x1100
        private const val HANGUL_V_BASE = 0x1161
        private const val HANGUL_T_BASE = 0x11A7
        private const val HANGUL_L_COUNT = 19
        private const val HANGUL_V = 21
        private const val HANGUL_T = 28
        private const val HANGUL_S_COUNT = HANGUL_L_COUNT * HANGUL_V * HANGUL_T

        private fun isHangulS(cp: Int) = cp in HANGUL_S until HANGUL_S + HANGUL_S_COUNT

        fun load(dir: File): Unicode13 {
            val excl = HashSet<Int>()
            scan(File(dir, "DerivedNormalizationProps.txt")) { fields ->
                if (fields.size < 2 || fields[1].trim() != "Full_Composition_Exclusion") return@scan
                for (cp in expand(fields[0])) if (cp <= 0xFFFF) excl.add(cp)
            }
            val white = BooleanArray(65536)
            scan(File(dir, "PropList.txt")) { fields ->
                if (fields.size < 2 || fields[1].trim() != "White_Space") return@scan
                for (cp in expand(fields[0])) if (cp <= 0xFFFF) white[cp] = true
            }
            val ageOk = BooleanArray(65536)
            scan(File(dir, "DerivedAge.txt")) { fields ->
                if (fields.size < 2 || !ageAtMost13(fields[1].trim())) return@scan
                for (cp in expand(fields[0])) if (cp <= 0xFFFF) ageOk[cp] = true
            }
            val ccc = ByteArray(65536)
            val decomp = HashMap<Int, IntArray>()
            val simple = HashMap<Int, Int>()
            scan(File(dir, "UnicodeData.txt")) { fields ->
                if (fields.size < 6) return@scan
                val cp = fields[0].trim().toIntOrNull(16) ?: return@scan
                if (cp > 0xFFFF) return@scan
                val cc = fields[3].trim().toIntOrNull() ?: 0
                if (cc > 0) ccc[cp] = cc.toByte()
                val dec = fields[5].trim()
                if (dec.isNotEmpty() && !dec.startsWith("<")) {
                    val seq = parseHexSeq(dec)
                    if (seq.isNotEmpty()) decomp[cp] = seq
                }
                if (fields.size > 13) {
                    val low = fields[13].trim()
                    if (low.isNotEmpty()) low.toIntOrNull(16)?.let { simple[cp] = it }
                }
            }
            val lowerMap = HashMap<Int, IntArray>()
            scan(File(dir, "SpecialCasing.txt")) { fields ->
                if (fields.size < 4) return@scan
                val cond = if (fields.size >= 5) fields[4].trim() else ""
                if (cond.isNotEmpty()) return@scan
                val cp = fields[0].trim().toIntOrNull(16) ?: return@scan
                if (cp > 0xFFFF) return@scan
                val seq = parseHexSeq(fields[1])
                if (seq.isNotEmpty()) lowerMap[cp] = seq
            }
            for ((cp, low) in simple) {
                if (cp in lowerMap) continue
                if (low != cp) lowerMap[cp] = intArrayOf(low)
            }
            val comp = HashMap<Long, Int>()
            for ((cp, seq) in decomp) {
                if (cp in excl || seq.size != 2) continue
                if (cccOfRaw(ccc, seq[0]) != 0) continue
                comp[seq[0].toLong() shl 21 or seq[1].toLong()] = cp
            }
            return Unicode13(ageOk, white, ccc, lowerMap, decomp, comp)
        }

        private fun cccOfRaw(ccc: ByteArray, cp: Int): Int = if (cp in 0..0xFFFF) ccc[cp].toInt() and 0xFF else 0

        private fun ageAtMost13(v: String): Boolean {
            val parts = v.split('.')
            val maj = parts[0].toIntOrNull() ?: return false
            val min = if (parts.size > 1) parts[1].toIntOrNull() ?: return false else 0
            return maj < 13 || (maj == 13 && min == 0)
        }

        private fun parseHexSeq(s: String): IntArray {
            val parts = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return IntArray(0)
            val out = IntArray(parts.size)
            for (i in parts.indices) {
                out[i] = parts[i].toIntOrNull(16) ?: return IntArray(0)
            }
            return out
        }

        private fun expand(raw: String): List<Int> {
            val s = raw.trim()
            val dots = s.indexOf("..")
            if (dots >= 0) {
                val a = s.substring(0, dots).toIntOrNull(16) ?: return emptyList()
                val b = s.substring(dots + 2).toIntOrNull(16) ?: return emptyList()
                if (b < a || b - a > 1 shl 20) return emptyList()
                return (a..b).toList()
            }
            val v = s.toIntOrNull(16) ?: return emptyList()
            return listOf(v)
        }

        private fun scan(file: File, fn: (List<String>) -> Unit) {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (rawLine in lines) {
                    val hash = rawLine.indexOf('#')
                    val line = (if (hash >= 0) rawLine.substring(0, hash) else rawLine).trim()
                    if (line.isEmpty()) continue
                    fn(line.split(';'))
                }
            }
        }
    }
}

/** 进程内当前使用的表。JVM 上第一次读取时从仓库的 Unicode 13 数据装入；设备接线可换成 ICU。 */
object SyncChars {
    private val fallback: SyncUnicode = object : SyncUnicode {
        override fun ageAtMost13(cp: Int) = false
        override fun isWhiteSpace(cp: Int) = cp == 0x20 || cp == 0x09 || cp == 0x0A || cp == 0x0D
        override fun lowercase(cp: Int) = intArrayOf(cp)
        override fun nfc(s: String) = s
    }

    @Volatile
    private var installed: SyncUnicode? = null

    var unicode: SyncUnicode
        get() {
            installed?.let { return it }
            synchronized(this) {
                installed?.let { return it }
                val root = findRepoRoot()
                val loaded = if (root != null) Unicode13.load(File(root, "scripts/sync/vectors/data")) else fallback
                installed = loaded
                return loaded
            }
        }
        set(value) {
            installed = value
        }

    fun installUnicode13(repoRoot: File) {
        unicode = Unicode13.load(File(repoRoot, "scripts/sync/vectors/data"))
    }

    private fun findRepoRoot(): File? {
        val prop = System.getProperty("vela.sync.repo")
        if (!prop.isNullOrEmpty()) return File(prop)
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "scripts/sync/vectors/data/DerivedAge.txt").isFile) return dir
            dir = dir.parentFile ?: return null
        }
        return null
    }
}
