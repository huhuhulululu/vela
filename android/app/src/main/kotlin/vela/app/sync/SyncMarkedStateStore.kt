package vela.app.sync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import vela.core.sync.SyncState
import vela.core.sync.SyncStateFileStore
import vela.core.sync.SyncStateJson
import vela.core.sync.SyncStateRead
import vela.core.sync.SyncStateStore

/**
 * 持久化「状态损坏」标记的同步状态存储包装（docs/plans/sync.md 第 23 节第 14、15 条，H4、H7）：
 * 底层读到坏文件会改名隔离，下次再读会变成 `None` 导致密钥被误判为孤儿条目删除。
 * 因此状态文件存在时，读之前先落盘 `sync-state.json.corrupt`，读成功再删；
 * 标记写不成时不改名、走只读解码，完好文件照常读出；坏文件则留在原处、下次仍是损坏。
 * 标记在而状态文件不在时，保存一律拒绝，直到显式 `delete()` 清除。
 */
class SyncMarkedStateStore(
    val inner: SyncStateFileStore,
) : SyncStateStore {
    /** 写损坏标记；测试可注入失败或观察写入时机。 */
    var writeMarker: (Path) -> Boolean = { marker ->
        try {
            marker.parent?.let { Files.createDirectories(it) }
            Files.write(marker, ByteArray(0))
            setPrivatePermissions(marker)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** `sync-state.json.corrupt`（与隔离备份 `sync-state.json.bad-*` 不同名）。 */
    val corruptMarker: Path = inner.path.resolveSibling(inner.path.fileName.toString() + ".corrupt")

    private val markedCorrupt: Boolean get() = Files.exists(corruptMarker)
    private val stateFileExists: Boolean get() = Files.exists(inner.path)

    override fun load(): SyncStateRead {
        if (!stateFileExists) return if (markedCorrupt) SyncStateRead.Corrupted else SyncStateRead.None
        if (!markedCorrupt && !writeMarker(corruptMarker)) return readOnlyDecode()
        val read = inner.load()
        if (read != SyncStateRead.Corrupted) {
            try {
                Files.deleteIfExists(corruptMarker)
            } catch (_: Exception) {
            }
        }
        return read
    }

    /** 不经底层（不改名隔离）直接解码状态文件，大小上限与底层一致。 */
    private fun readOnlyDecode(): SyncStateRead = try {
        val size = Files.size(inner.path)
        if (size > SyncStateFileStore.MAX_FILE_BYTES) {
            SyncStateRead.Corrupted
        } else {
            val bytes = Files.readAllBytes(inner.path)
            SyncStateRead.Present(SyncStateJson.decode(String(bytes, Charsets.UTF_8)))
        }
    } catch (_: Exception) {
        SyncStateRead.Corrupted
    }

    override fun save(state: SyncState): Boolean {
        if (markedCorrupt && !stateFileExists) return false
        val saved = inner.save(state)
        if (saved) setPrivatePermissions(inner.path)
        return saved
    }

    override fun delete(): Boolean {
        if (!inner.delete()) return false
        if (!markedCorrupt) return true
        return try {
            Files.deleteIfExists(corruptMarker)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun setPrivatePermissions(path: Path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        } catch (_: Exception) {
        }
    }
}
