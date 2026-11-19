package vela.core.sync

import vela.core.curation.Curation
import vela.core.taste.Taste

object SyncFixture {
    fun reg(l: Long, payload: String, c: Int = 0, dev: Long = 1): SyncRegister =
        SyncRegister.of(Hlc(l, c, dev), payload)

    fun doc(text: String): SyncDocument {
        val decoded = SyncDocument.decode(text.toByteArray(Charsets.UTF_8))
        return (decoded as? SyncDecodeResult.Valid)?.doc ?: error("bad fixture: $text")
    }

    fun text(doc: SyncDocument): String = String(doc.encoded(), Charsets.UTF_8)
}

class PlainCodec : SyncEnvelopeCodec {
    override var publicKey: String = "PUB"
    var failOpen = false

    override fun seal(plaintext: ByteArray, ver: Long): String? =
        "p1." + SyncEncoding.base64url(plaintext) + "." + ver.toString()

    override fun open(envelope: String, ver: Long): SyncOpenResult {
        val parts = envelope.split('.')
        if (parts.size != 3 || parts[0] != "p1") return SyncOpenResult.Unsupported
        if (failOpen || parts[2] != ver.toString()) return SyncOpenResult.Failed
        val bytes = SyncEncoding.decodeBase64url(parts[1]) ?: return SyncOpenResult.Failed
        return SyncOpenResult.Plaintext(bytes)
    }

    override fun digest(envelope: String): String = sha256Hex(envelope.toByteArray(Charsets.UTF_8))

    companion object {
        fun envelope(doc: SyncDocument, ver: Long): String = PlainCodec().seal(doc.encoded(), ver)!!
    }
}

class MemoryStateStore(state: SyncState? = null) : SyncStateStore {
    var stored: SyncState? = state?.deepCopy()
    var corrupted = false
    var failSaves = false
    var failDelete = false
    var saves = 0

    override fun load(): SyncStateRead = when {
        corrupted -> SyncStateRead.Corrupted
        stored != null -> SyncStateRead.Present(stored!!)
        else -> SyncStateRead.None
    }

    override fun save(state: SyncState): Boolean {
        if (failSaves) return false
        saves += 1
        stored = state.deepCopy()
        return true
    }

    override fun delete(): Boolean {
        if (failDelete) return false
        stored = null
        return true
    }
}

class MemoryKeychain(var entry: SyncKeychainRead = SyncKeychainRead.None) : SyncKeychain {
    var failDelete = false
    override fun read(): SyncKeychainRead = entry
    override fun delete(): Boolean {
        if (failDelete) return false
        entry = SyncKeychainRead.None
        return true
    }
}

class MemorySink : SyncLocalSink {
    var curation: Curation? = null
    var taste: Taste = Taste()
    var settings: Map<String, Boolean> = emptyMap()
    var failCuration = false
    var failTaste = false
    var cancelled = 0

    override fun cancelPendingSaves() {
        cancelled += 1
    }

    override fun currentTaste(): Taste = taste

    override fun saveCuration(curation: Curation): Boolean {
        if (failCuration) return false
        this.curation = curation
        return true
    }

    override fun saveTaste(taste: Taste): Boolean {
        if (failTaste) return false
        this.taste = taste
        return true
    }

    override fun applySettings(settings: Map<String, Boolean>) {
        this.settings = this.settings + settings
    }
}

class MemoryServer {
    var ver: Long = 0
    var pub: String? = null
    var data: String? = null
    var epoch: String = "e0000000000000000000000000000001"
    var now: Long = 1_800_000_000_000
    var deletedAt: Long? = null
    val puts = ArrayList<SyncPutRequest>()
    var gets = 0

    fun get(): SyncServerResponse {
        gets += 1
        deletedAt?.let { return SyncServerResponse.Gone(it) }
        val p = pub
        val d = data
        if (p == null || d == null) return SyncServerResponse.NotFound(epoch, now)
        return SyncServerResponse.Blob(ver, p, d, epoch, now)
    }

    fun put(r: SyncPutRequest, requester: String): SyncServerResponse {
        puts.add(r)
        deletedAt?.let { return SyncServerResponse.Gone(it) }
        if (r.epoch != null && r.epoch != epoch) return SyncServerResponse.Forbidden
        if (r.base == 0L) {
            if (data != null) return SyncServerResponse.Conflict(ver, pub!!, data!!, epoch, now)
            pub = requester
        } else {
            if (data == null) return SyncServerResponse.NotFound(epoch, now)
            if (r.base != ver) return SyncServerResponse.Conflict(ver, pub!!, data!!, epoch, now)
        }
        ver = r.next
        data = r.envelope
        return SyncServerResponse.Stored(ver, epoch, now)
    }

    fun delete(base: Long): SyncServerResponse {
        deletedAt?.let { return SyncServerResponse.Gone(it) }
        if (data == null) return SyncServerResponse.NotFound(epoch, now)
        if (base != ver) return SyncServerResponse.Conflict(ver, pub!!, data!!, epoch, now)
        deletedAt = now
        data = null
        return SyncServerResponse.Deleted
    }

    val document: SyncDocument?
        get() {
            val d = data ?: return null
            val opened = PlainCodec().open(d, ver) as? SyncOpenResult.Plaintext ?: return null
            return (SyncDocument.decode(opened.bytes) as? SyncDecodeResult.Valid)?.doc
        }
}

class MemoryTransport(val server: MemoryServer) : SyncTransport {
    var pub = "PUB"
    var beforeGetReturns: (suspend () -> Unit)? = null
    var beforePutReturns: (suspend () -> Unit)? = null
    var beforePutSends: (suspend () -> Unit)? = null
    var putOverride: SyncServerResponse? = null
    var dropNextPutResponse = false
    val scriptedGets = ArrayDeque<SyncServerResponse>()
    val deletes = ArrayList<Long>()
    var failDeletes = 0

    override suspend fun get(): SyncServerResponse {
        val r = if (scriptedGets.isEmpty()) server.get() else scriptedGets.removeFirst()
        beforeGetReturns?.invoke()
        return r
    }

    override suspend fun put(request: SyncPutRequest): SyncServerResponse {
        beforePutSends?.invoke()
        putOverride?.let { return it }
        val r = server.put(request, pub)
        beforePutReturns?.invoke()
        if (dropNextPutResponse) {
            dropNextPutResponse = false
            return SyncServerResponse.Transient
        }
        return r
    }

    override suspend fun delete(base: Long, epoch: String?): SyncServerResponse {
        deletes.add(base)
        if (failDeletes > 0) {
            failDeletes -= 1
            return SyncServerResponse.Transient
        }
        return server.delete(base)
    }
}
