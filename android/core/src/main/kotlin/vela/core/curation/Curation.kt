package vela.core.curation

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import vela.core.base.io.Mover
import vela.core.base.io.PreferenceQuarantine
import vela.core.base.model.Prefs
import vela.core.base.model.trimSwiftWhitespace
import vela.core.sites.RoomKey
import vela.core.sites.SiteKeyMigration
import vela.core.sites.SiteKeys

/**
 * 本机收藏与屏蔽。按 ref（不分大小写）记录，各有数量上限，超出丢最旧的。
 * 同一个 ref 只会在其中一边。更新都返回新值。
 * 内存里 cb 保留输入时的大小写（无前缀），其他站点存 ref；编码一律写 raw。
 */
@Serializable(with = CurationSerializer::class)
class Curation(
    favorites: List<String> = emptyList(),
    blocked: List<String> = emptyList(),
) {
    val favorites: List<String>
    val blocked: List<String>

    init {
        val keptBlocked = clean(blocked)
        val blockedKeys = keptBlocked.map { key(it) }.toHashSet()
        this.blocked = keptBlocked
        this.favorites = clean(favorites).filter { key(it) !in blockedKeys }
    }

    val favoriteSet: Set<String>
        get() = favorites.map { key(it) }.toHashSet()

    val blockedSet: Set<String>
        get() = blocked.map { key(it) }.toHashSet()

    fun isFavorite(name: String): Boolean = favoriteSet.contains(key(name))

    fun isBlocked(name: String): Boolean = blockedSet.contains(key(name))

    fun favoriting(name: String): Curation {
        if (key(name).isEmpty()) return this
        return Curation(favorites = listOf(name) + favorites, blocked = removing(name, blocked))
    }

    /** 撤销移除时：这个名字此刻还能放回收藏（没被屏蔽、也没被重新收藏）。 */
    fun canRestoreFavorite(name: String): Boolean =
        key(name).isNotEmpty() && !isBlocked(name) && !isFavorite(name)

    /** 只放回收藏，不碰屏蔽名单。不能放回时原样返回，不覆盖之后的操作。 */
    fun restoringFavorite(name: String): Curation =
        if (canRestoreFavorite(name)) Curation(favorites = listOf(name) + favorites, blocked = blocked) else this

    /** 已有收藏保持先后，新名字接在后面。去重、遵守上限，屏蔽名单里的不收。 */
    fun importing(names: List<String>): Import {
        val known = favoriteSet
        val next = Curation(favorites = favorites + names, blocked = blocked)
        val added = next.favorites.count { key(it) !in known }
        return Import(next, added)
    }

    fun unfavoriting(name: String): Curation = Curation(favorites = removing(name, favorites), blocked = blocked)

    fun blocking(name: String): Curation {
        if (key(name).isEmpty()) return this
        return Curation(favorites = removing(name, favorites), blocked = listOf(name) + blocked)
    }

    fun unblocking(name: String): Curation = Curation(favorites = favorites, blocked = removing(name, blocked))

    private fun removing(name: String, list: List<String>): List<String> {
        val target = key(name)
        return list.filter { key(it) != target }
    }

    override fun equals(other: Any?): Boolean =
        other is Curation && favorites == other.favorites && blocked == other.blocked

    override fun hashCode(): Int = 31 * favorites.hashCode() + blocked.hashCode()

    override fun toString(): String = "Curation(favorites=$favorites, blocked=$blocked)"

    data class Import(val curation: Curation, val added: Int)

    companion object {
        const val maxEntries: Int = 300

        /** 裸名、`cb:x`、`c4:x` 都归一到小写 ref。参数名沿用，存的是 ref。 */
        fun key(name: String): String = RoomKey.normalizeRef(name)

        /** 去重（保留先出现的）、去空，超出上限只留最新的（列表前部）。 */
        private fun clean(names: List<String>): List<String> {
            val seen = HashSet<String>()
            val out = ArrayList<String>(minOf(names.size, maxEntries))
            for (name in names) {
                val trimmed = name.trimSwiftWhitespace().take(Prefs.maxNameLength)
                if (trimmed.isEmpty() || !seen.add(key(trimmed))) continue
                out.add(SiteKeys.memory(trimmed))
                if (out.size == maxEntries) break
            }
            return out
        }
    }
}

object CurationSerializer : KSerializer<Curation> {
    override val descriptor: SerialDescriptor = Out.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Curation) {
        encoder.encodeSerializableValue(
            Out.serializer(),
            Out(
                favorites = value.favorites.map(SiteKeys::disk),
                blocked = value.blocked.map(SiteKeys::disk),
                keyVersion = SiteKeyMigration.version,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): Curation {
        val raw = decoder.decodeSerializableValue(Incoming.serializer())
        return Curation(
            favorites = raw.favorites.map(SiteKeys::memory),
            blocked = raw.blocked.map(SiteKeys::memory),
        )
    }

    @Serializable
    private data class Incoming(
        val favorites: List<String> = emptyList(),
        val blocked: List<String> = emptyList(),
        val keyVersion: Int = 0,
    )

    /** 没有缺省值，编码时即使 `encodeDefaults = false` 也会写出 `keyVersion`。 */
    @Serializable
    private data class Out(
        val favorites: List<String>,
        val blocked: List<String>,
        val keyVersion: Int,
    )
}

/**
 * 把收藏与屏蔽存成一个小 JSON。读写失败不抛：读退回空，写返回 false。
 * 读失败时坏文件改名为 `.bad-<时间戳>`；备份失败则只读，保存跳过。
 * 目录由调用方注入（Swift 的 `defaultURL()` 不移植）。文件名是 [fileName]。
 */
class CurationStore(val url: Path) {
    private val lock = ReentrantLock()
    private var readOnly = false

    /** 坏文件改名用的操作，测试注入失败。 */
    var moveItem: Mover = PreferenceQuarantine.fileMover

    fun load(now: Instant = Instant.now()): Curation = lock.withLock {
        val result = PreferenceQuarantine.read(
            path = url,
            limit = Prefs.maxFileBytes,
            now = now,
            move = moveItem,
            decode = ::decode,
        )
        readOnly = false
        when (result) {
            is PreferenceQuarantine.ReadResult.Loaded ->
                Curation(favorites = result.value.favorites, blocked = result.value.blocked)
            PreferenceQuarantine.ReadResult.Missing,
            PreferenceQuarantine.ReadResult.Quarantined,
            -> Curation()
            PreferenceQuarantine.ReadResult.ReadOnly -> {
                readOnly = true
                Curation()
            }
        }
    }

    /** 上次读取失败且坏文件没能改名：保存会被跳过，不让空数据覆盖原文件。 */
    val isReadOnly: Boolean
        get() = lock.withLock { readOnly }

    fun save(curation: Curation): Boolean = lock.withLock {
        if (readOnly) return false
        try {
            val bytes = json.encodeToString(Curation.serializer(), curation).toByteArray(Charsets.UTF_8)
            writeAtomic(bytes)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun writeAtomic(bytes: ByteArray) {
        val parent = url.parent ?: throw IOException("missing parent")
        Files.createDirectories(parent)
        val tmp = Files.createTempFile(parent, ".${url.fileName}.", ".tmp")
        try {
            Files.write(tmp, bytes)
            try {
                Files.move(
                    tmp,
                    url,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
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

    companion object {
        const val fileName: String = "feed-curation.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** 注入目录下的收藏文件，对应 Swift `…/feed-curation.json`。 */
        fun fileIn(directory: Path): Path = directory.resolve(fileName)

        private fun decode(bytes: ByteArray): Curation =
            json.decodeFromString(Curation.serializer(), bytes.toString(Charsets.UTF_8))
    }
}
