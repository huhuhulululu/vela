package vela.core.ledger

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import vela.core.base.io.Mover
import vela.core.base.io.PreferenceQuarantine
import vela.core.base.model.Prefs
import vela.core.base.model.timeIntervalSince1970

/**
 * 观看账本 JSON 存储。读写失败不抛出：读退回空，写返回 false。
 * 读失败时坏文件改名为 `.bad-<时间戳>` 备份（[PreferenceQuarantine]）；备份失败则只读，保存跳过。
 *
 * `defaultURL` 改成注入目录：`PreferenceStore` 不移植，文件名仍是 `watch-ledger.json`。
 */
class WatchLedgerStore(val url: Path) {
    private val lock = ReentrantLock()
    private var readOnly = false

    /** 坏文件改名用的操作（测试注入失败用）。 */
    var moveItem: Mover = PreferenceQuarantine.fileMover

    fun load(now: Instant = Instant.now()): WatchLedger {
        lock.lock()
        try {
            val result = PreferenceQuarantine.read(
                path = url,
                limit = Prefs.maxFileBytes,
                now = now,
                move = moveItem,
            ) { bytes ->
                ledgerJson.decodeFromString(WatchLedger.serializer(), bytes.toString(Charsets.UTF_8))
            }
            readOnly = false
            return when (result) {
                is PreferenceQuarantine.ReadResult.Loaded -> result.value.capped(now.timeIntervalSince1970())
                PreferenceQuarantine.ReadResult.Missing,
                PreferenceQuarantine.ReadResult.Quarantined,
                -> WatchLedger()
                PreferenceQuarantine.ReadResult.ReadOnly -> {
                    readOnly = true
                    WatchLedger()
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /** 上次读取失败且坏文件没能改名备份：此时保存会被跳过（返回 false），不让空数据覆盖原文件。 */
    val isReadOnly: Boolean
        get() {
            lock.lock()
            try {
                return readOnly
            } finally {
                lock.unlock()
            }
        }

    fun save(ledger: WatchLedger): Boolean {
        lock.lock()
        try {
            if (readOnly) return false
            val capped = ledger.capped(Instant.now().timeIntervalSince1970())
            return try {
                val bytes = ledgerJson.encodeToString(WatchLedger.serializer(), capped)
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

    fun erase(): Boolean {
        lock.lock()
        try {
            return try {
                if (Files.exists(url)) Files.delete(url)
                readOnly = false
                true
            } catch (_: Exception) {
                false
            }
        } finally {
            lock.unlock()
        }
    }

    companion object {
        fun defaultURL(directory: Path): Path = directory.resolve("watch-ledger.json")
    }
}

/** 先写同目录临时文件再替换，对齐 `Data.write(options: .atomic)`。 */
private fun writeAtomic(path: Path, bytes: ByteArray) {
    val parent = path.parent
    if (parent != null) Files.createDirectories(parent)
    val dir = parent ?: Path.of(".")
    val prefix = ".${path.fileName ?: "ledger"}."
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
