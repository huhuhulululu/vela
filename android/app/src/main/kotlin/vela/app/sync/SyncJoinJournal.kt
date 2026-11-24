package vela.app.sync

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 快照的状态（docs/plans/sync.md 第 12.4 节、第 23 节第 14 条）。 */
enum class SyncJoinJournalStatus {
    NONE,

    /** 完整快照，提交还没定局：失败或启动时没有同步状态就换回。 */
    PENDING,

    /** 「已开启」已写，提交已定局：只能删除，不能换回。 */
    COMMITTED,

    /** 发布目录没有完整性标记：快照是写本机的第一步，没写过别的，直接删除，不换回。 */
    INCOMPLETE,
}

/**
 * 加入提交的本机文件快照（docs/plans/sync.md 第 12.4 节、第 23 节第 13、14 条）。
 * 写本机之前整份复制收藏、口味文件与两个同步设置；任一步失败、或启动时发现未定局的快照而没有同步状态，
 * 就用它换回提交前的本机数据。
 */
interface SyncJoinJournal {
    val status: SyncJoinJournalStatus
    val exists: Boolean get() = status != SyncJoinJournalStatus.NONE

    /** 在临时目录完整构建后原子发布；任何失败都不留目录。 */
    fun snapshot(): Boolean

    /** 「已开启」写入之后：持久标记已提交，此后只能删除。 */
    fun markCommitted(): Boolean

    /** 换回快照里的文件与设置；可重复执行，不删除快照（收尾完成后由调用方 `discard`）。只接受未定局的完整快照。 */
    fun restore(): Boolean

    /** 删除快照（连同残留的临时目录）。 */
    fun discard(): Boolean
}

/** 不做快照（只用于不涉及本机文件的测试与工具）。 */
object NoJournal : SyncJoinJournal {
    override val status: SyncJoinJournalStatus get() = SyncJoinJournalStatus.NONE
    override fun snapshot(): Boolean = true
    override fun markCommitted(): Boolean = true
    override fun restore(): Boolean = true
    override fun discard(): Boolean = true
}

/**
 * 文件实现：快照目录与原文件同卷（同一目录下），文件 0600，换回用同卷原子移动。
 * 构建在同目录的 `<dir>.tmp-<随机>` 里进行，写完全部副本与完整性标记后整目录原子重命名为 `dir` 发布，
 * 因此 `dir` 要么不存在、要么带完整性标记；已提交另写一个标记文件。
 */
class FileJoinJournal(
    val dir: Path,
    val files: List<Path>,
    val settingKeys: List<String>,
    val readSettings: () -> Map<String, Boolean>,
    val writeSettings: (Map<String, Boolean?>) -> Boolean,
) : SyncJoinJournal {
    /** 写一个小文件；测试可注入失败。 */
    var writeFile: (Path, ByteArray) -> Boolean = { path, data ->
        try {
            Files.write(path, data)
            setMode(path, "rw-------")
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun copyPath(f: Path, inDir: Path): Path = inDir.resolve(f.fileName)
    private fun absentPath(f: Path, inDir: Path): Path = inDir.resolve("absent-" + f.fileName.toString())
    private fun settingsPath(inDir: Path): Path = inDir.resolve("settings.json")
    private fun has(name: String): Boolean = Files.exists(dir.resolve(name))

    private fun temporaries(): List<Path> {
        val parent = dir.parent ?: return emptyList()
        if (!Files.exists(parent)) return emptyList()
        val prefix = dir.fileName.toString() + ".tmp-"
        return try {
            Files.list(parent).use { stream ->
                stream.filter { it.fileName.toString().startsWith(prefix) }.toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override val status: SyncJoinJournalStatus
        get() {
            if (!Files.exists(dir)) return SyncJoinJournalStatus.NONE
            if (has(COMMITTED_MARKER)) return SyncJoinJournalStatus.COMMITTED
            return if (has(COMPLETE_MARKER)) SyncJoinJournalStatus.PENDING else SyncJoinJournalStatus.INCOMPLETE
        }

    override fun snapshot(): Boolean {
        if (!discard()) return false
        val parent = dir.parent ?: return false
        val tmp = parent.resolve("${dir.fileName}.tmp-${UUID.randomUUID()}")
        if (!build(tmp) || !atomicMove(tmp, dir, replaceExisting = false)) {
            deleteTree(tmp)
            return false
        }
        return true
    }

    private fun build(tmp: Path): Boolean {
        return try {
            Files.createDirectories(tmp)
            setMode(tmp, "rwx------")
            for (f in files) {
                if (Files.exists(f)) {
                    val dest = copyPath(f, tmp)
                    Files.copy(f, dest, StandardCopyOption.REPLACE_EXISTING)
                    setMode(dest, "rw-------")
                } else {
                    if (!writeFile(absentPath(f, tmp), ByteArray(0))) return false
                }
            }
            val data = encodeSettings(readSettings())
            if (!writeFile(settingsPath(tmp), data)) return false
            writeFile(tmp.resolve(COMPLETE_MARKER), ByteArray(0))
        } catch (_: Exception) {
            false
        }
    }

    override fun markCommitted(): Boolean = when (status) {
        SyncJoinJournalStatus.COMMITTED -> true
        SyncJoinJournalStatus.PENDING -> writeFile(dir.resolve(COMMITTED_MARKER), ByteArray(0))
        SyncJoinJournalStatus.NONE, SyncJoinJournalStatus.INCOMPLETE -> false
    }

    override fun restore(): Boolean {
        if (status != SyncJoinJournalStatus.PENDING) return false
        for (f in files) {
            val copy = copyPath(f, dir)
            val absent = absentPath(f, dir)
            if (Files.exists(copy)) {
                if (!atomicMove(copy, f, replaceExisting = true)) return false
            } else if (Files.exists(absent)) {
                try {
                    Files.deleteIfExists(f)
                    Files.deleteIfExists(absent)
                } catch (_: Exception) {
                    return false
                }
            }
        }
        val saved = try {
            decodeSettings(Files.readAllBytes(settingsPath(dir)))
        } catch (_: Exception) {
            null
        } ?: return false
        val values = LinkedHashMap<String, Boolean?>(settingKeys.size)
        for (key in settingKeys) {
            values[key] = saved[key]
        }
        return writeSettings(values)
    }

    override fun discard(): Boolean {
        var ok = true
        val targets = buildList {
            add(dir)
            addAll(temporaries())
        }
        for (d in targets) {
            if (Files.exists(d) && !deleteTree(d)) ok = false
        }
        return ok
    }

    private fun atomicMove(src: Path, dst: Path, replaceExisting: Boolean): Boolean = try {
        if (replaceExisting) {
            try {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
            }
        } else {
            try {
                Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(src, dst)
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun deleteTree(root: Path): Boolean {
        if (!Files.exists(root)) return true
        return try {
            val items = Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).toList() }
            for (p in items) {
                Files.deleteIfExists(p)
            }
            !Files.exists(root)
        } catch (_: Exception) {
            false
        }
    }

    private fun setMode(path: Path, mode: String) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        } catch (_: Exception) {
        }
    }

    companion object {
        const val COMPLETE_MARKER: String = "complete"
        const val COMMITTED_MARKER: String = "committed"
        private val json = Json { ignoreUnknownKeys = false }

        private fun encodeSettings(map: Map<String, Boolean>): ByteArray {
            val sb = StringBuilder("{")
            var first = true
            for ((k, v) in map) {
                if (!first) sb.append(',')
                first = false
                sb.append('"').append(escapeJson(k)).append("\":").append(if (v) "true" else "false")
            }
            sb.append('}')
            return sb.toString().toByteArray(Charsets.UTF_8)
        }

        private fun decodeSettings(bytes: ByteArray): Map<String, Boolean>? {
            val obj = json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject ?: return null
            val out = LinkedHashMap<String, Boolean>(obj.size)
            for ((k, v) in obj) {
                val b = (v as? JsonPrimitive)?.booleanOrNull ?: return null
                out[k] = b
            }
            return out
        }

        private fun escapeJson(s: String): String = buildString(s.length) {
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    else -> append(c)
                }
            }
        }
    }
}
