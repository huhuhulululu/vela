package vela.core.taste

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.json.Json
import vela.core.base.io.Mover
import vela.core.base.io.PreferenceQuarantine
import vela.core.base.model.Prefs

/**
 * 与 Swift `JSONEncoder` / `JSONDecoder` 对齐：缺省值也写出，未知字段忽略。
 * `Taste` 的自定义解码把缺字段当成空画像；这里用构造器默认值表达同一件事。
 */
internal val tasteJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * 口味画像 JSON 文件。读写失败不抛出：读退回空画像，写返回 false。
 * 读失败时坏文件改名为 `.bad-<时间戳>`（[PreferenceQuarantine]）；备份失败则只读，保存跳过。
 * 不从旧版偏好文件迁移。目录由调用方传入，文件名仍是 `feed-taste.json`。
 */
class TasteStore(val url: Path) {
    private val lock = ReentrantLock()
    private var readOnly = false

    /** 坏文件改名用的操作（测试注入失败用）。 */
    var moveItem: Mover = PreferenceQuarantine.fileMover

    fun load(now: Instant = Instant.now()): Taste {
        lock.lock()
        try {
            val result = PreferenceQuarantine.read(
                path = url,
                limit = Prefs.maxFileBytes,
                now = now,
                move = moveItem,
            ) { bytes ->
                tasteJson.decodeFromString(Taste.serializer(), bytes.decodeToString())
            }
            readOnly = false
            return when (result) {
                is PreferenceQuarantine.ReadResult.Loaded -> result.value.capped(now)
                PreferenceQuarantine.ReadResult.Missing,
                PreferenceQuarantine.ReadResult.Quarantined,
                -> Taste()
                PreferenceQuarantine.ReadResult.ReadOnly -> {
                    readOnly = true
                    Taste()
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /** 上次读取失败且坏文件没能改名备份：此时保存会被跳过，不让空数据覆盖原文件。 */
    val isReadOnly: Boolean
        get() {
            lock.lock()
            try {
                return readOnly
            } finally {
                lock.unlock()
            }
        }

    fun save(taste: Taste, now: Instant = Instant.now()): Boolean {
        lock.lock()
        try {
            if (readOnly) return false
            return try {
                val bytes = tasteJson.encodeToString(Taste.serializer(), taste.capped(now))
                    .toByteArray(Charsets.UTF_8)
                writeAtomic(url, bytes)
                true
            } catch (_: Exception) {
                false
            }
        } finally {
            lock.unlock()
        }
    }

    companion object {
        fun defaultURL(directory: Path): Path = directory.resolve("feed-taste.json")
    }
}

/** 先写同目录临时文件再替换，对齐 `Data.write(options: .atomic)`。 */
private fun writeAtomic(path: Path, bytes: ByteArray) {
    val parent = path.parent
    if (parent != null) Files.createDirectories(parent)
    val dir = parent ?: Path.of(".")
    val prefix = ".${path.fileName ?: "taste"}."
    val tmp = Files.createTempFile(dir, prefix, ".tmp")
    try {
        Files.write(tmp, bytes)
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (error: Exception) {
        try {
            Files.deleteIfExists(tmp)
        } catch (_: Exception) {
            // 临时文件清不掉也不改返回值。
        }
        throw error
    }
}
