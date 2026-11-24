package vela.app.sync

import java.security.SecureRandom
import vela.core.sync.PayloadDecode
import vela.core.sync.SyncDerived
import vela.core.sync.SyncEnvelopeCodec
import vela.core.sync.SyncLimits
import vela.core.sync.SyncOpenResult
import vela.core.sync.aadFor
import vela.core.sync.decodePayload
import vela.core.sync.encodePayload
import vela.core.sync.openEnvelope
import vela.core.sync.sealEnvelope
import vela.core.sync.sha256Hex

/**
 * 同步信封编解码器（docs/plans/sync.md 第 8 节）：
 * AES-256-GCM + raw DEFLATE 载荷；每次封装由系统 CSPRNG 生成新的 12 字节 nonce。
 */
class SyncCryptoCodec(
    val keys: SyncDerived,
    val sizeLimit: Int = SyncLimits.plaintextBytes,
    private val random: (Int) -> ByteArray = defaultRandom,
) : SyncEnvelopeCodec {
    override val publicKey: String get() = keys.pubB64

    override fun seal(plaintext: ByteArray, ver: Long): String? {
        if (plaintext.size > sizeLimit) return null
        val nonce = try {
            random(NONCE_BYTES)
        } catch (_: Exception) {
            return null
        }
        if (nonce.size != NONCE_BYTES) return null
        val (flag, body) = encodePayload(plaintext)
        val payload = ByteArray(1 + body.size)
        payload[0] = flag.toByte()
        body.copyInto(payload, 1)
        return try {
            sealEnvelope(keys.encKey, nonce, payload, aadFor(keys.blobID, ver))
        } catch (_: Exception) {
            null
        }
    }

    override fun open(envelope: String, ver: Long): SyncOpenResult {
        if (!envelope.startsWith(PREFIX)) return SyncOpenResult.Unsupported
        if (envelope.toByteArray(Charsets.UTF_8).size > MAX_ENVELOPE_BYTES) return SyncOpenResult.Failed
        val raw = openEnvelope(keys.encKey, envelope, aadFor(keys.blobID, ver)) ?: return SyncOpenResult.Failed
        if (raw.isEmpty()) return SyncOpenResult.Failed
        val decoded = decodePayload(raw[0].toInt() and 0xFF, raw.copyOfRange(1, raw.size), sizeLimit)
        return if (decoded is PayloadDecode.Ok) SyncOpenResult.Plaintext(decoded.bytes) else SyncOpenResult.Failed
    }

    override fun digest(envelope: String): String = sha256Hex(envelope.toByteArray(Charsets.UTF_8))

    companion object {
        const val PREFIX: String = "e1."
        const val MAX_ENVELOPE_BYTES: Int = 800_100
        private const val NONCE_BYTES = 12
        private val secureRandom = SecureRandom()

        val defaultRandom: (Int) -> ByteArray = { count ->
            ByteArray(count).also { secureRandom.nextBytes(it) }
        }
    }
}
