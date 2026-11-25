package vela.core.sites

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import vela.core.base.model.Room
import vela.core.base.model.lowerRoot
import vela.core.base.model.trimSwiftWhitespace
import vela.core.sync.SyncKeys

/**
 * 站点短 id。`cb` 是 Chaturbate，`c4` 是 Cam4。
 * 格式只要求 `^[a-z][a-z0-9]{1,7}$`，不要求已注册。
 */
@Serializable
enum class SiteID(val wire: String) {
    chaturbate("cb"),
    cam4("c4"),
    ;

    val displayName: String
        get() = when (this) {
            chaturbate -> "Chaturbate"
            cam4 -> "Cam4"
        }

    /**
     * `cb` 沿用 [Room.isValidUsername]（字母数字下划线，2...30，保留大小写）。
     * `c4` 先折成小写再校验：`^[a-z0-9_]{2,30}$`。不放行 `-` 与 `.`。
     */
    fun isValidName(name: String): Boolean = when (this) {
        chaturbate -> Room.isValidUsername(name)
        cam4 -> {
            val bytes = name.toByteArray(Charsets.UTF_8)
            bytes.size in 2..30 && bytes.all { isASCIILower(it) || isASCIIDigit(it) || it == '_'.code.toByte() }
        }
    }

    companion object {
        const val idPattern: String = "^[a-z][a-z0-9]{1,7}$"

        fun isValidID(raw: String): Boolean {
            val bytes = raw.toByteArray(Charsets.UTF_8)
            if (bytes.size !in 2..8 || !isASCIILower(bytes[0])) return false
            for (i in 1 until bytes.size) {
                if (!isASCIILower(bytes[i]) && !isASCIIDigit(bytes[i])) return false
            }
            return true
        }

        private fun isASCIILower(c: Byte): Boolean = c in 'a'.code.toByte()..'z'.code.toByte()
        private fun isASCIIDigit(c: Byte): Boolean = c in '0'.code.toByte()..'9'.code.toByte()

        fun fromWire(raw: String): SiteID? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * 房间身份。磁盘与同步用 [raw]（永远带站点前缀）；内存用 [ref]（`cb` 省略前缀）。
 * 相等与哈希按站点 + 小写用户名，所以 `cb:Alice` 与 `cb:alice` 是同一间。
 */
@Serializable(with = RoomKeySerializer::class)
class RoomKey private constructor(
    val site: SiteID,
    /** `cb` 去首尾空白后保留大小写。`c4` 统一小写。 */
    val name: String,
) {
    /** 永远带前缀：`"cb:Alice"`、`"c4:sample_one"`。 */
    val raw: String
        get() = "${site.wire}:$name"

    /** 内存简写：cb 省略前缀，其他站点带前缀。 */
    val ref: String
        get() = if (site == SiteID.chaturbate) name else raw

    /** [ref] 的小写，集合与比较用。 */
    val normalized: String
        get() = ref.lowerRoot()

    override fun equals(other: Any?): Boolean =
        other is RoomKey && site == other.site && name.lowerRoot() == other.name.lowerRoot()

    override fun hashCode(): Int = 31 * site.hashCode() + name.lowerRoot().hashCode()

    override fun toString(): String = raw

    companion object {
        /** 名字不合法返回 null。`c4` 会先把名字折成小写。 */
        operator fun invoke(site: SiteID, name: String): RoomKey? {
            val trimmed = name.trimSwiftWhitespace()
            val normalized = if (site == SiteID.cam4) SyncKeys.asciiLower(trimmed) else trimmed
            if (!site.isValidName(normalized)) return null
            return RoomKey(site, normalized)
        }

        /**
         * `"cb:Alice"`、`"c4:bob"`，或裸名（视为 cb）。
         * 站点段不合格式、未知站点、名字不合法返回 null。
         */
        fun parsing(raw: String): RoomKey? {
            val trimmed = raw.trimSwiftWhitespace()
            val colon = trimmed.indexOf(':')
            if (colon < 0) return invoke(SiteID.chaturbate, trimmed)
            val siteRaw = trimmed.substring(0, colon)
            val name = trimmed.substring(colon + 1)
            if (!SiteID.isValidID(siteRaw)) return null
            val site = SiteID.fromWire(siteRaw) ?: return null
            return invoke(site, name)
        }

        /** 名字不合法时也要能落到房间上（[Room.key] 非可选）。不校验。 */
        internal fun unchecked(site: SiteID, name: String): RoomKey = RoomKey(site, name)

        /**
         * 裸名、`cb:x`、`c4:x`（站点段大小写不敏感）都归一到小写 ref。
         * 认不出的形式退回 trim + lowercased，与旧 [vela.core.curation.Curation.key] 相同。
         */
        fun normalizeRef(any: String): String {
            val trimmed = any.trimSwiftWhitespace()
            if (!trimmed.contains(':')) return trimmed.lowerRoot()
            val key = parsing(trimmed) ?: parsing(SyncKeys.asciiLower(trimmed))
            return key?.normalized ?: trimmed.lowerRoot()
        }

        /** 内存 ref 属于哪个站：带已知站点前缀的按前缀，其余一律是 cb。 */
        fun siteOfRef(ref: String): SiteID {
            val trimmed = ref.trimSwiftWhitespace()
            val colon = trimmed.indexOf(':')
            if (colon < 0) return SiteID.chaturbate
            return SiteID.fromWire(SyncKeys.asciiLower(trimmed.substring(0, colon))) ?: SiteID.chaturbate
        }
    }
}

object RoomKeySerializer : KSerializer<RoomKey> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("vela.core.sites.RoomKey", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: RoomKey) {
        encoder.encodeString(value.raw)
    }

    override fun deserialize(decoder: Decoder): RoomKey {
        val raw = decoder.decodeString()
        return RoomKey.parsing(raw) ?: throw SerializationException("无法解析房间键")
    }
}

/**
 * 磁盘 raw 与内存 ref 的转换。短名字（测试与旧文件里的一位用户名）不是合法 [RoomKey]，
 * 仍然按 cb 加前缀 / 去前缀，这样旧文件往返后内存里的键不变。
 */
internal object SiteKeys {
    fun disk(stored: String): String {
        val trimmed = stored.trimSwiftWhitespace()
        val key = RoomKey.parsing(trimmed) ?: RoomKey.parsing(SyncKeys.asciiLower(trimmed))
        if (key != null) return key.raw
        val colon = trimmed.indexOf(':')
        if (colon >= 0) {
            val siteRaw = trimmed.substring(0, colon)
            if (SiteID.isValidID(SyncKeys.asciiLower(siteRaw))) return trimmed
        }
        if (trimmed.isEmpty()) return trimmed
        return "cb:$trimmed"
    }

    /** cb 去掉前缀（保留名字原样），其他已知站点用 ref；认不出则原样返回。 */
    fun memory(stored: String): String {
        val trimmed = stored.trimSwiftWhitespace()
        val key = RoomKey.parsing(trimmed) ?: RoomKey.parsing(SyncKeys.asciiLower(trimmed))
        if (key != null) return key.ref
        val colon = trimmed.indexOf(':')
        if (colon < 0) return trimmed
        val siteRaw = SyncKeys.asciiLower(trimmed.substring(0, colon))
        val name = trimmed.substring(colon + 1)
        if (siteRaw == SiteID.chaturbate.wire) return name
        if (siteRaw == SiteID.cam4.wire) return "c4:${SyncKeys.asciiLower(name)}"
        return trimmed
    }
}

/** 当前看哪个站点。缺省与认不出的值都是 Chaturbate。 */
enum class SiteMode {
    chaturbate,
    cam4,
    mixed,
    ;

    val sites: List<SiteID>
        get() = when (this) {
            chaturbate -> listOf(SiteID.chaturbate)
            cam4 -> listOf(SiteID.cam4)
            mixed -> listOf(SiteID.chaturbate, SiteID.cam4)
        }

    /** 过滤出属于当前站点模式的收藏名单（保持原顺序）。 */
    fun filterFavorites(names: List<String>): List<String> {
        val allowed = sites.toSet()
        return names.filter { RoomKey.siteOfRef(it) in allowed }
    }

    /** 搜索框回车直达：带站点前缀按前缀；裸名在 Cam4 模式按 c4，其余按 cb。名字不合法返回 null。 */
    fun directRoom(text: String): Room? {
        val trimmed = text.trimSwiftWhitespace()
        if (':' in trimmed) return RoomKey.parsing(trimmed)?.let(Room::direct)
        val site = if (this == cam4) SiteID.cam4 else SiteID.chaturbate
        return RoomKey(site, trimmed)?.let(Room::direct)
    }

    companion object {
        fun restore(raw: String?): SiteMode {
            if (raw.isNullOrEmpty()) return chaturbate
            return entries.firstOrNull { it.name == raw } ?: chaturbate
        }
    }
}
