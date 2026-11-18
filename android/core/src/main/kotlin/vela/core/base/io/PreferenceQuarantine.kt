package vela.core.base.io

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import vela.core.base.model.Prefs

/**
 * 偏好文件读取上限（字节），与 L0a [Prefs.maxFileBytes] 同一常量。
 * 超过就当作读失败，不解析。
 */
const val maxFileBytes: Int = Prefs.maxFileBytes

/**
 * 文件不存在或读不出大小时返回 true（不存在时由后面的读取失败退回空值）。
 * 大小在上限内才返回 true。
 */
fun fitsLimit(path: Path, limit: Int): Boolean {
    val size = try {
        Files.size(path)
    } catch (_: Exception) {
        return true
    }
    return size <= limit.toLong()
}

/** 改名操作；测试可注入失败。 */
typealias Mover = (from: Path, to: Path) -> Unit

/**
 * 偏好文件读失败（解码失败、读不出、超过大小上限）时的现场保留：把原文件改名为
 * `<文件名>.bad-<秒级时间戳>`，内存里继续用空值。改名成功后原路径已空，之后保存不会覆盖现场；
 * 改名失败时调用方应进入只读，不让空数据覆盖原文件。每个文件最多保留 [PreferenceQuarantine.maxBackups] 份，删最旧的。
 * 不抛出：所有文件操作失败都折成返回值。调用方负责加锁。
 */
object PreferenceQuarantine {
    const val maxBackups: Int = 3

    val fileMover: Mover = { from, to -> Files.move(from, to) }

    sealed class ReadResult<out T> {
        /** 文件不存在：可正常保存。 */
        data object Missing : ReadResult<Nothing>()

        /** 读取并解码成功。 */
        data class Loaded<T>(val value: T) : ReadResult<T>()

        /** 读失败，已改名备份：内存用空值，可正常保存。 */
        data object Quarantined : ReadResult<Nothing>()

        /** 读失败且改名失败：内存用空值，但不得覆盖原路径。 */
        data object ReadOnly : ReadResult<Nothing>()
    }

    /**
     * 读取并解码 [path]；失败时尝试备份。[limit] 为文件大小上限（字节）。
     * [decode] 抛出任何异常都算解码失败。
     */
    fun <T> read(
        path: Path,
        limit: Int,
        now: Instant = Instant.now(),
        move: Mover = fileMover,
        decode: (ByteArray) -> T,
    ): ReadResult<T> {
        if (!Files.exists(path)) return ReadResult.Missing
        if (fitsLimit(path, limit)) {
            try {
                val data = Files.readAllBytes(path)
                return ReadResult.Loaded(decode(data))
            } catch (_: Exception) {
                // 读或解码失败，走隔离。
            }
        }
        return if (quarantine(path, now, move)) ReadResult.Quarantined else ReadResult.ReadOnly
    }

    /** 把坏文件改名为带时间戳的 `.bad` 并清理多余备份；改名成功返回 true。 */
    fun quarantine(url: Path, now: Instant = Instant.now(), move: Mover = fileMover): Boolean {
        val target = backupPath(url, now)
        try {
            move(url, target)
        } catch (_: Exception) {
            return false
        }
        prune(url)
        return true
    }

    /** 现有备份（按时间从旧到新）。 */
    fun backups(of: Path): List<Path> {
        val directory = of.parent ?: return emptyList()
        val prefix = "${of.fileName}.bad-"
        val names = try {
            Files.list(directory).use { stream ->
                stream.map { it.fileName.toString() }.filter { it.startsWith(prefix) }.toList()
            }
        } catch (_: IOException) {
            return emptyList()
        } catch (_: SecurityException) {
            return emptyList()
        }
        return names
            .map { name -> name to sortKey(name.removePrefix(prefix)) }
            .sortedWith { left, right ->
                val byKey = compareSortKey(left.second, right.second)
                if (byKey != 0) byKey else left.first.compareTo(right.first)
            }
            .map { directory.resolve(it.first) }
    }

    /** `<文件名>.bad-<秒>`；同一秒已有备份时追加 `-1`、`-2`…… 秒数向零截断，对齐 Swift `Int(timeInterval)`。 */
    internal fun backupPath(url: Path, now: Instant): Path {
        val directory = url.parent ?: Path.of(".")
        val base = "${url.fileName}.bad-${epochSecondsTowardZero(now)}"
        var candidate = directory.resolve(base)
        var suffix = 1
        while (Files.exists(candidate)) {
            candidate = directory.resolve("$base-$suffix")
            suffix += 1
        }
        return candidate
    }

    /** 只保留最新的 [maxBackups] 份；删除失败忽略。 */
    internal fun prune(url: Path) {
        val all = backups(url)
        if (all.size <= maxBackups) return
        for (stale in all.take(all.size - maxBackups)) {
            try {
                Files.delete(stale)
            } catch (_: Exception) {
                // 删除失败忽略。
            }
        }
    }

    /** `"<秒>"` 或 `"<秒>-<序号>"` → [秒, 序号]；认不出的排最前（最先被删）。 */
    private fun sortKey(tail: String): List<Long> {
        val parts = tail.split('-').filter { it.isNotEmpty() }.map { it.toLongOrNull() ?: -1L }
        return if (parts.size == 1) listOf(parts[0], 0L) else parts
    }

    private fun compareSortKey(left: List<Long>, right: List<Long>): Int {
        val shared = minOf(left.size, right.size)
        for (index in 0 until shared) {
            val compared = left[index].compareTo(right[index])
            if (compared != 0) return compared
        }
        return left.size.compareTo(right.size)
    }

    private fun epochSecondsTowardZero(now: Instant): Long {
        val seconds = now.epochSecond
        return if (seconds < 0 && now.nano > 0) seconds + 1 else seconds
    }
}
