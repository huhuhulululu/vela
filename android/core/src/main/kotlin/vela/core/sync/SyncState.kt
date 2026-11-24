package vela.core.sync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import vela.core.base.io.PreferenceQuarantine

enum class SyncPhase { CREATING, ACTIVE, DISABLING, CLEARING }

sealed class SyncHalt {
    data object Forbidden : SyncHalt()
    data class Gone(val deletedAt: Long) : SyncHalt()
    data object Missing : SyncHalt()
    data object EpochChanged : SyncHalt()
    data object Rollback : SyncHalt()
    data object PubMismatch : SyncHalt()
    data object DecryptFailed : SyncHalt()
}

data class SyncInflight(val base: Long, val next: Long, val envelopeHash: String, val sentGen: Long)

data class SyncState(
    var phase: SyncPhase,
    var origin: String,
    var blobID: String,
    var clock: SyncClock,
    var doc: SyncDocument = SyncDocument.empty(),
    var epoch: String? = null,
    var maxSentL: Long = 0,
    var gen: Long = 0,
    var pushedGen: Long = 0,
    var lastSeenVer: Long = 0,
    var lastSeenHash: String? = null,
    var inflight: SyncInflight? = null,
    var migrated: Boolean = false,
    var oversize: Boolean = false,
    var halt: SyncHalt? = null,
    var decryptFailures: Int = 0,
    var lastSuccessAt: Long? = null,
    var lastFailure: String? = null,
) {
    fun deepCopy(): SyncState = SyncStateJson.decode(SyncStateJson.encode(this))

    var replica: SyncReplica
        get() = SyncReplica(doc, clock)
        set(value) {
            doc = value.doc
            clock = value.clock
        }
}

sealed class SyncStateRead {
    data object None : SyncStateRead()
    data class Present(val state: SyncState) : SyncStateRead()
    data object Corrupted : SyncStateRead()
}

interface SyncStateStore {
    fun load(): SyncStateRead
    fun save(state: SyncState): Boolean
    fun delete(): Boolean
}

object SyncStateJson {
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(state: SyncState): String {
        val sb = StringBuilder()
        sb.append('{')
        fun field(name: String, value: String, comma: Boolean = true) {
            if (comma) sb.append(',')
            sb.append('"').append(name).append("\":").append(value)
        }
        field("phase", quote(state.phase.name.lowercase()), comma = false)
        field("origin", quote(state.origin))
        field("blobID", quote(state.blobID))
        field("epoch", state.epoch?.let { quote(it) } ?: "null")
        field("doc", quote(String(state.doc.encoded(), Charsets.UTF_8)))
        field("clock", """{"l":${state.clock.l},"c":${state.clock.c},"dev":${state.clock.dev}}""")
        field("maxSentL", state.maxSentL.toString())
        field("gen", state.gen.toString())
        field("pushedGen", state.pushedGen.toString())
        field("lastSeenVer", state.lastSeenVer.toString())
        field("lastSeenHash", state.lastSeenHash?.let { quote(it) } ?: "null")
        field("inflight", state.inflight?.let { inflightJson(it) } ?: "null")
        field("migrated", state.migrated.toString())
        field("oversize", state.oversize.toString())
        field("halt", state.halt?.let { haltJson(it) } ?: "null")
        field("decryptFailures", state.decryptFailures.toString())
        field("lastSuccessAt", state.lastSuccessAt?.toString() ?: "null")
        field("lastFailure", state.lastFailure?.let { quote(it) } ?: "null")
        sb.append('}')
        return sb.toString()
    }

    fun decode(text: String): SyncState {
        val root = json.parseToJsonElement(text) as JsonObject
        val phase = SyncPhase.valueOf(root.str("phase").uppercase())
        val clockObj = root.obj("clock")
        val clock = SyncClock(clockObj.long("l"), clockObj.long("c").toInt(), clockObj.long("dev"))
        val docText = root.str("doc")
        val decoded = SyncDocument.decode(docText.toByteArray(Charsets.UTF_8))
        val doc = (decoded as? SyncDecodeResult.Valid)?.doc ?: error("同步文档不合法")
        return SyncState(
            phase = phase,
            origin = root.str("origin"),
            blobID = root.str("blobID"),
            clock = clock,
            doc = doc,
            epoch = root.strOrNull("epoch"),
            maxSentL = root.long("maxSentL"),
            gen = root.long("gen"),
            pushedGen = root.long("pushedGen"),
            lastSeenVer = root.long("lastSeenVer"),
            lastSeenHash = root.strOrNull("lastSeenHash"),
            inflight = root.objOrNull("inflight")?.let {
                SyncInflight(it.long("base"), it.long("next"), it.str("envelopeHash"), it.long("sentGen"))
            },
            migrated = root.bool("migrated"),
            oversize = root.bool("oversize"),
            halt = root.objOrNull("halt")?.let { haltOf(it) },
            decryptFailures = root.long("decryptFailures").toInt(),
            lastSuccessAt = root.longOrNull("lastSuccessAt"),
            lastFailure = root.strOrNull("lastFailure"),
        )
    }

    private fun inflightJson(i: SyncInflight) =
        """{"base":${i.base},"next":${i.next},"envelopeHash":${quote(i.envelopeHash)},"sentGen":${i.sentGen}}"""

    private fun haltJson(h: SyncHalt): String = when (h) {
        SyncHalt.Forbidden -> """{"kind":"forbidden"}"""
        is SyncHalt.Gone -> """{"kind":"gone","deletedAt":${h.deletedAt}}"""
        SyncHalt.Missing -> """{"kind":"missing"}"""
        SyncHalt.EpochChanged -> """{"kind":"epochChanged"}"""
        SyncHalt.Rollback -> """{"kind":"rollback"}"""
        SyncHalt.PubMismatch -> """{"kind":"pubMismatch"}"""
        SyncHalt.DecryptFailed -> """{"kind":"decryptFailed"}"""
    }

    private fun haltOf(o: JsonObject): SyncHalt = when (o.str("kind")) {
        "forbidden" -> SyncHalt.Forbidden
        "gone" -> SyncHalt.Gone(o.long("deletedAt"))
        "missing" -> SyncHalt.Missing
        "epochChanged" -> SyncHalt.EpochChanged
        "rollback" -> SyncHalt.Rollback
        "pubMismatch" -> SyncHalt.PubMismatch
        "decryptFailed" -> SyncHalt.DecryptFailed
        else -> error("halt")
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun JsonObject.str(k: String) = (get(k) as JsonPrimitive).content
    private fun JsonObject.strOrNull(k: String): String? {
        val v = get(k) ?: return null
        if (v is JsonNull) return null
        return (v as JsonPrimitive).contentOrNull
    }
    private fun JsonObject.long(k: String): Long = (get(k) as JsonPrimitive).longOrNull ?: (get(k) as JsonPrimitive).content.toLong()
    private fun JsonObject.longOrNull(k: String): Long? {
        val v = get(k) ?: return null
        if (v is JsonNull) return null
        return (v as JsonPrimitive).longOrNull
    }
    private fun JsonObject.bool(k: String) = (get(k) as JsonPrimitive).booleanOrNull ?: error(k)
    private fun JsonObject.obj(k: String) = get(k) as JsonObject
    private fun JsonObject.objOrNull(k: String): JsonObject? {
        val v = get(k) ?: return null
        if (v is JsonNull) return null
        return v as? JsonObject
    }
}

class SyncStateFileStore(val path: Path) : SyncStateStore {
    private val lock = Any()
    private var readOnly = false
    var moveItem: (Path, Path) -> Unit = PreferenceQuarantine.fileMover

    override fun load(): SyncStateRead {
        synchronized(lock) {
            return when (
                val result = PreferenceQuarantine.read(path, MAX_FILE_BYTES, move = moveItem) { bytes ->
                    SyncStateJson.decode(String(bytes, Charsets.UTF_8))
                }
            ) {
                PreferenceQuarantine.ReadResult.Missing -> {
                    readOnly = false
                    SyncStateRead.None
                }
                is PreferenceQuarantine.ReadResult.Loaded -> {
                    readOnly = false
                    SyncStateRead.Present(result.value)
                }
                PreferenceQuarantine.ReadResult.Quarantined -> {
                    readOnly = false
                    SyncStateRead.Corrupted
                }
                PreferenceQuarantine.ReadResult.ReadOnly -> {
                    readOnly = true
                    SyncStateRead.Corrupted
                }
            }
        }
    }

    override fun save(state: SyncState): Boolean {
        synchronized(lock) {
            if (readOnly) return false
            return try {
                val parent = path.parent
                if (parent != null) Files.createDirectories(parent)
                val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
                Files.write(tmp, SyncStateJson.encode(state).toByteArray(Charsets.UTF_8))
                try {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
                }
                true
            } catch (_: Exception) {
                try {
                    val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
                    if (Files.exists(tmp)) Files.delete(tmp)
                } catch (_: Exception) {
                }
                false
            }
        }
    }

    override fun delete(): Boolean {
        synchronized(lock) {
            return try {
                Files.deleteIfExists(path)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    companion object {
        val MAX_FILE_BYTES = SyncLimits.plaintextBytes * 2 + 64_000
    }
}
