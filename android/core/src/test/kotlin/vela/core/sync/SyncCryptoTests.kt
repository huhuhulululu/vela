package vela.core.sync

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncCryptoTests {
    private val k = ByteArray(32) { it.toByte() }

    @Test
    fun testSignCounterRetriesUntilScalarInRange() {
        val seen = ArrayList<Int>()
        val keys = deriveSyncKeys(k) { counter ->
            seen.add(counter)
            when (counter) {
                0 -> ByteArray(32)
                1 -> order32()
                else -> signBlock(k, counter)
            }
        }
        assertEquals(listOf(0, 1, 2), seen)
        assertEquals(2, keys.signCounter)
        assertTrue(keys.d.contentEquals(signBlock(k, 2)))
        assertEquals(65, keys.pub.size)
    }

    @Test
    fun testAllCountersFailingAbortsWithoutKey() {
        assertFailsWith<IllegalStateException> {
            deriveSyncKeys(k) { ByteArray(32) { 0xFF.toByte() } }
        }
    }

    @Test
    fun testSignatureRoundTripWithRandomK() {
        val keys = deriveSyncKeys(k)
        val message = signMessage("put", keys.blobID, "a".repeat(32), 3, 4, "b".repeat(64))
        val a = signEcdsa(keys.d, message)
        val b = signEcdsa(keys.d, message)
        assertEquals(64, a.size)
        assertFalse(a.contentEquals(b))
        assertTrue(verifyEcdsa(keys.pub, message, a))
        assertTrue(verifyEcdsa(keys.pub, message, b))
        val flipped = message.copyOf()
        flipped[0] = (flipped[0].toInt() xor 1).toByte()
        assertFalse(verifyEcdsa(keys.pub, flipped, a))
    }

    @Test
    fun testCodecSealOpenRoundTripAndAADBindsVersion() {
        val keys = deriveSyncKeys(k)
        val json = "{\"rooms\":{},\"schema\":2,\"settings\":{},\"veto\":{}}".toByteArray(Charsets.UTF_8)
        val a = seal(keys, json, 5)!!
        val b = seal(keys, json, 5)!!
        assertNotEquals(a, b, "每次封装换新 nonce")
        assertTrue(open(keys, a, 5).contentEquals(json))
        assertNull(openOrNull(keys, a, 6))
        assertNull(openEnvelope(keys.encKey, "e2.AAAA.BBBB", aadFor(keys.blobID, 5)))
        assertEquals(sha256Hex(a.toByteArray(Charsets.UTF_8)), sha256Hex(a.toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun testRandomFailureAbortsSeal() {
        val keys = deriveSyncKeys(k)
        val nonce: () -> ByteArray? = { null }
        assertNull(seal(keys, "{}".toByteArray(Charsets.UTF_8), 1, nonce))
    }

    @Test
    fun testPayloadCompressesOnlyWhenShorter() {
        val small = "{}".toByteArray(Charsets.UTF_8)
        val encoded = encodePayload(small)
        assertEquals(0, encoded.first)
        assertTrue(encoded.second.contentEquals(small))
        val big = "{\"rooms\":{}}".repeat(200).toByteArray(Charsets.UTF_8)
        val wire = encodePayload(big)
        assertEquals(1, wire.first)
        assertTrue(wire.second.size < big.size)
        val back = decodePayload(wire.first, wire.second)
        assertTrue(back is PayloadDecode.Ok && back.bytes.contentEquals(big))
        val limited = decodePayload(wire.first, wire.second, big.size - 1)
        assertTrue(limited is PayloadDecode.Bad, "超过 P 中止")
    }

    private fun order32(): ByteArray {
        val raw = SyncLimits.p256Order.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33)
            else -> error("order ${raw.size}")
        }
    }

    private fun signBlock(k: ByteArray, counter: Int): ByteArray {
        val info = ByteArray(18)
        "vela-sync/v2/sign".toByteArray(Charsets.US_ASCII).copyInto(info)
        info[17] = counter.toByte()
        return hkdfSha256(k, ByteArray(32), info)
    }

    private fun seal(keys: SyncDerived, json: ByteArray, ver: Long, nonce: () -> ByteArray? = ::randomNonce): String? {
        val n = nonce() ?: return null
        val (flag, body) = encodePayload(json)
        val payload = ByteArray(1 + body.size)
        payload[0] = flag.toByte()
        body.copyInto(payload, 1)
        return sealEnvelope(keys.encKey, n, payload, aadFor(keys.blobID, ver))
    }

    private fun open(keys: SyncDerived, envelope: String, ver: Long): ByteArray {
        val raw = openOrNull(keys, envelope, ver) ?: error("open")
        return raw
    }

    private fun openOrNull(keys: SyncDerived, envelope: String, ver: Long): ByteArray? {
        val raw = openEnvelope(keys.encKey, envelope, aadFor(keys.blobID, ver)) ?: return null
        if (raw.isEmpty()) return null
        val decoded = decodePayload(raw[0].toInt() and 0xFF, raw.copyOfRange(1, raw.size))
        return (decoded as? PayloadDecode.Ok)?.bytes
    }

    private fun randomNonce(): ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) }
}
