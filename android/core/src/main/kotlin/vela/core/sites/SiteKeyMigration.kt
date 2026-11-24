package vela.core.sites

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import vela.core.base.model.Prefs
import vela.core.curation.Curation
import vela.core.ledger.WatchLedger
import vela.core.ledger.ledgerJson
import vela.core.taste.Taste
import vela.core.taste.tasteJson

/**
 * 把收藏、口味、观看账本三个文件的用户名键一次性写成 raw（`cb:alice`）。
 * 幂等；先备份 `<名>.pre-sites`（已有不覆盖）。
 * 单个文件只有在备份与写回都成功时才带上 `keyVersion`；任何一步失败都不改原文件，因此不会留下半截版本。
 * 三份文件在同一次 [run] 里处理。
 */
object SiteKeyMigration {
    const val version: Int = 2

    data class Report(
        val migrated: List<Path> = emptyList(),
        val skipped: List<Path> = emptyList(),
        val failed: List<Path> = emptyList(),
    )

    /**
     * 依次处理 curation、taste、watch-ledger。
     * [write] / [copy] 仅测试注入失败用。
     */
    fun run(
        curation: Path,
        taste: Path,
        ledger: Path,
        write: ((ByteArray, Path) -> Unit)? = null,
        copy: ((Path, Path) -> Unit)? = null,
    ): Report {
        val writer = write ?: ::writeAtomic
        val copier = copy ?: { from, to ->
            Files.copy(from, to)
            Unit
        }
        val migrated = ArrayList<Path>(3)
        val skipped = ArrayList<Path>(3)
        val failed = ArrayList<Path>(3)
        val files = listOf(curation to Kind.curation, taste to Kind.taste, ledger to Kind.ledger)
        for ((url, kind) in files) {
            when (migrate(url, kind, writer, copier)) {
                Outcome.migrated -> migrated.add(url)
                Outcome.skipped -> skipped.add(url)
                Outcome.failed -> failed.add(url)
            }
        }
        return Report(migrated, skipped, failed)
    }

    /** 用 `.pre-sites` 备份原子写回原文件；读或写失败都不删现有文件，记入 [Report.failed]。 */
    fun rollback(
        curation: Path,
        taste: Path,
        ledger: Path,
        write: ((ByteArray, Path) -> Unit)? = null,
    ): Report {
        val writer = write ?: ::writeAtomic
        val migrated = ArrayList<Path>(3)
        val failed = ArrayList<Path>(3)
        for (url in listOf(curation, taste, ledger)) {
            val backup = backupPath(url)
            if (!Files.exists(backup)) {
                failed.add(url)
                continue
            }
            try {
                val data = Files.readAllBytes(backup)
                writer(data, url)
                migrated.add(url)
            } catch (_: Exception) {
                failed.add(url)
            }
        }
        return Report(migrated = migrated, failed = failed)
    }

    fun backupPath(file: Path): Path {
        val parent = file.parent ?: Path.of(".")
        return parent.resolve(file.fileName.toString() + ".pre-sites")
    }

    /** 「清除全部偏好数据」时一并删掉三份备份。文件不在也算成功。 */
    fun removeBackups(curation: Path, taste: Path, ledger: Path): Boolean {
        var ok = true
        for (url in listOf(curation, taste, ledger)) {
            val backup = backupPath(url)
            if (!Files.exists(backup)) continue
            try {
                Files.delete(backup)
            } catch (_: Exception) {
                ok = false
            }
        }
        return ok
    }

    private enum class Kind { curation, taste, ledger }

    private enum class Outcome { migrated, skipped, failed }

    private fun migrate(
        url: Path,
        kind: Kind,
        write: (ByteArray, Path) -> Unit,
        copy: (Path, Path) -> Unit,
    ): Outcome {
        if (!Files.exists(url)) return Outcome.skipped
        val size = try {
            Files.size(url)
        } catch (_: IOException) {
            return Outcome.skipped
        }
        if (size > Prefs.maxFileBytes) return Outcome.skipped
        val data = try {
            Files.readAllBytes(url)
        } catch (_: IOException) {
            return Outcome.skipped
        }
        if (data.size > Prefs.maxFileBytes) return Outcome.skipped
        val object_ = try {
            json.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return Outcome.skipped
        val version = intValue(object_["keyVersion"])
        if (version != null && version >= this.version) return Outcome.skipped
        if (!hasPayload(object_, kind)) return Outcome.skipped
        val encoded = encode(data, kind) ?: return Outcome.skipped
        val backup = backupPath(url)
        if (!Files.exists(backup)) {
            try {
                copy(url, backup)
            } catch (_: Exception) {
                return Outcome.failed
            }
        }
        return try {
            write(encoded, url)
            Outcome.migrated
        } catch (_: Exception) {
            Outcome.failed
        }
    }

    private fun hasPayload(object_: JsonObject, kind: Kind): Boolean {
        val keys = when (kind) {
            Kind.curation -> setOf("favorites", "blocked")
            Kind.taste -> setOf("beliefs", "seen", "daily", "paused")
            Kind.ledger -> setOf("entries")
        }
        return object_.keys.any { it in keys }
    }

    private fun encode(data: ByteArray, kind: Kind): ByteArray? {
        val text = data.toString(Charsets.UTF_8)
        return try {
            when (kind) {
                Kind.curation -> {
                    val value = curationJson.decodeFromString(Curation.serializer(), text)
                    curationJson.encodeToString(Curation.serializer(), value).toByteArray(Charsets.UTF_8)
                }
                Kind.taste -> {
                    val value = tasteJson.decodeFromString(Taste.serializer(), text)
                    tasteJson.encodeToString(Taste.serializer(), value).toByteArray(Charsets.UTF_8)
                }
                Kind.ledger -> {
                    val value = ledgerJson.decodeFromString(WatchLedger.serializer(), text)
                    ledgerJson.encodeToString(WatchLedger.serializer(), value).toByteArray(Charsets.UTF_8)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun intValue(raw: kotlinx.serialization.json.JsonElement?): Int? {
        val primitive = raw as? JsonPrimitive ?: return null
        if (!primitive.isString) {
            primitive.longOrNull?.let { value ->
                if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return value.toInt()
            }
            primitive.doubleOrNull?.let { value ->
                if (value.isFinite()) return value.toInt()
            }
        }
        return null
    }

    private fun writeAtomic(bytes: ByteArray, url: Path) {
        val parent = url.parent ?: throw IOException("missing parent")
        Files.createDirectories(parent)
        val tmp = Files.createTempFile(parent, ".${url.fileName}.", ".tmp")
        try {
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, url, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, url, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            try {
                Files.deleteIfExists(tmp)
            } catch (_: Exception) {
                // 临时文件清不掉不影响失败返回。
            }
            throw error
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val curationJson = Json { ignoreUnknownKeys = true }
}
