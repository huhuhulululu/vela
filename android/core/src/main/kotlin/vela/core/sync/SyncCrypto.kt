package vela.core.sync

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256（RFC 5869），L = 32，只要一个块。salt 必须显式传入。 */
fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    val prk = mac.doFinal(ikm)
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    mac.update(info)
    mac.update(0x01)
    return mac.doFinal()
}

data class SyncDerived(
    val blobID: String,
    val encKey: ByteArray,
    val signCounter: Int,
    val d: ByteArray,
    val pub: ByteArray,
) {
    val pubB64: String get() = SyncEncoding.base64url(pub)
    val fingerprint: String get() = SyncCode.fingerprint(blobID)
}

/**
 * 派生 blobID、encKey 与签名标量 d。
 * [signBlock] 非空时按计数器注入 HKDF 输出（覆盖重试路径）；否则走真实 HKDF。
 */
fun deriveSyncKeys(k: ByteArray, signBlock: ((Int) -> ByteArray)? = null): SyncDerived {
    require(k.size == 32)
    val salt = ByteArray(32)
    val id = hkdfSha256(k, salt, "vela-sync/v2/id".toByteArray(Charsets.US_ASCII))
    val enc = hkdfSha256(k, salt, "vela-sync/v2/enc".toByteArray(Charsets.US_ASCII))
    val n = SyncLimits.p256Order
    var d32: ByteArray? = null
    var used = -1
    for (c in 0..255) {
        val t = if (signBlock != null) {
            signBlock(c)
        } else {
            val info = ByteArray(18)
            "vela-sync/v2/sign".toByteArray(Charsets.US_ASCII).copyInto(info)
            info[17] = c.toByte()
            hkdfSha256(k, salt, info)
        }
        val d = BigInteger(1, t)
        if (d.signum() > 0 && d < n) {
            d32 = t.copyOf()
            used = c
            break
        }
    }
    if (d32 == null) error("sign scalar exhausted")
    val pub = p256PublicKey(d32)
    return SyncDerived(SyncEncoding.hex(id), enc, used, d32, pub)
}

fun pubcheckMessage(blobID: String): ByteArray = ("vela-sync/v2/pubcheck\n" + blobID).toByteArray(Charsets.US_ASCII)

fun signMessage(op: String, blobID: String, epoch: String, base: Long, next: Long, line6: String): ByteArray {
    val text = listOf(
        "vela-sync/v2/$op",
        blobID,
        epoch,
        SyncEncoding.formatDecimal(base),
        SyncEncoding.formatDecimal(next),
        line6,
    ).joinToString("\n")
    return text.toByteArray(Charsets.US_ASCII)
}

fun aadFor(blobID: String, ver: Long): ByteArray =
    ("vela-sync/e1|$blobID|${SyncEncoding.formatDecimal(ver)}").toByteArray(Charsets.US_ASCII)

fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

fun sha256Hex(data: ByteArray): String = SyncEncoding.hex(sha256(data))

sealed class PayloadDecode {
    class Ok(val bytes: ByteArray) : PayloadDecode()
    class Bad(val reason: String) : PayloadDecode()
}

fun encodePayload(json: ByteArray): Pair<Int, ByteArray> {
    val comp = deflateRaw(json)
    return if (comp.size < json.size) 0x01 to comp else 0x00 to json.copyOf()
}

fun decodePayload(flag: Int, body: ByteArray, limit: Int = SyncLimits.plaintextBytes): PayloadDecode = when (flag) {
    0x00 -> if (body.size > limit) PayloadDecode.Bad("over_p") else PayloadDecode.Ok(body)
    0x01 -> inflateLimited(body, limit)
    else -> PayloadDecode.Bad("bad_flag")
}

fun deflateRaw(src: ByteArray): ByteArray {
    val def = Deflater(6, true)
    def.setInput(src)
    def.finish()
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (!def.finished()) {
        val n = def.deflate(buf)
        if (n > 0) out.write(buf, 0, n)
    }
    def.end()
    return out.toByteArray()
}

/** raw DEFLATE。安卓 `Inflater(true)` 在输入末尾补 1 个 0 字节，结束时 remaining 必须恰为 1。 */
fun inflateLimited(body: ByteArray, limit: Int): PayloadDecode {
    val input = ByteArray(body.size + 1)
    body.copyInto(input)
    val inf = Inflater(true)
    inf.setInput(input)
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    try {
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n > 0) {
                if (out.size() + n > limit) {
                    inf.end()
                    return PayloadDecode.Bad("over_p")
                }
                out.write(buf, 0, n)
            } else if (inf.finished()) {
                break
            } else if (inf.needsInput() || inf.needsDictionary()) {
                inf.end()
                return PayloadDecode.Bad("truncated")
            }
        }
    } catch (_: DataFormatException) {
        inf.end()
        return PayloadDecode.Bad("truncated")
    }
    val finished = inf.finished()
    val remaining = inf.remaining
    inf.end()
    if (!finished) return PayloadDecode.Bad("truncated")
    if (remaining != 1) return PayloadDecode.Bad("trailing")
    return PayloadDecode.Ok(out.toByteArray())
}

fun sealEnvelope(encKey: ByteArray, nonce: ByteArray, payload: ByteArray, aad: ByteArray): String {
    val ct = aesGcm(Cipher.ENCRYPT_MODE, encKey, nonce, aad, payload)
    return "e1." + SyncEncoding.base64url(nonce) + "." + SyncEncoding.base64url(ct)
}

fun openEnvelope(encKey: ByteArray, envelope: String, aad: ByteArray): ByteArray? {
    if (!envelope.startsWith("e1.")) return null
    val rest = envelope.substring(3)
    val dot = rest.indexOf('.')
    if (dot < 0) return null
    val nonce = SyncEncoding.decodeBase64url(rest.substring(0, dot)) ?: return null
    val ct = SyncEncoding.decodeBase64url(rest.substring(dot + 1)) ?: return null
    if (nonce.size != 12 || ct.size < 16) return null
    return try {
        aesGcm(Cipher.DECRYPT_MODE, encKey, nonce, aad, ct)
    } catch (_: Exception) {
        null
    }
}

private fun aesGcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    cipher.updateAAD(aad)
    return cipher.doFinal(input)
}

private val ecSpec: ECParameterSpec by lazy {
    val params = AlgorithmParameters.getInstance("EC")
    params.init(ECGenParameterSpec("secp256r1"))
    params.getParameterSpec(ECParameterSpec::class.java)
}

/** 平台 `SHA256withECDSA` 签名，输出 64 字节 r‖s。调用方不得预先哈希。 */
fun signEcdsa(d32: ByteArray, message: ByteArray): ByteArray {
    val priv = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, d32), ecSpec))
    val sig = Signature.getInstance("SHA256withECDSA")
    sig.initSign(priv)
    sig.update(message)
    return derToRs(sig.sign()) ?: error("der")
}

/**
 * 第 9 节：验签前检查长度、r/s 范围、公钥形状与是否在曲线上，再交给平台验签。
 * 不强制 low-S。
 */
fun verifyEcdsa(pub: ByteArray, message: ByteArray, sig: ByteArray): Boolean {
    if (sig.size != 64 || pub.size != 65 || pub[0] != 0x04.toByte()) return false
    val n = SyncLimits.p256Order
    val r = BigInteger(1, sig.copyOfRange(0, 32))
    val s = BigInteger(1, sig.copyOfRange(32, 64))
    if (r.signum() <= 0 || s.signum() <= 0 || r >= n || s >= n) return false
    val x = BigInteger(1, pub.copyOfRange(1, 33))
    val y = BigInteger(1, pub.copyOfRange(33, 65))
    if (!p256OnCurve(x, y)) return false
    val der = rsToDer(sig) ?: return false
    return try {
        val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), ecSpec))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(message)
        verifier.verify(der)
    } catch (_: Exception) {
        false
    }
}

fun rsToDer(sig: ByteArray): ByteArray? {
    if (sig.size != 64) return null
    val r = derInt(sig.copyOfRange(0, 32))
    val s = derInt(sig.copyOfRange(32, 64))
    val body = r + s
    if (body.size > 127) return null
    return byteArrayOf(0x30, body.size.toByte()) + body
}

fun derToRs(der: ByteArray): ByteArray? {
    if (der.size < 8 || der[0] != 0x30.toByte()) return null
    var pos = 1
    val seqLen: Int
    val lenByte = der[pos].toInt() and 0xFF
    if (lenByte and 0x80 == 0) {
        seqLen = lenByte
        pos += 1
    } else {
        val n = lenByte and 0x7F
        if (n == 0 || n > 2 || pos + n >= der.size) return null
        seqLen = 0
        // recompute below
        var v = 0
        for (i in 0 until n) v = v shl 8 or (der[pos + 1 + i].toInt() and 0xFF)
        pos += 1 + n
        if (pos + v != der.size && false) return null
        return parseSeq(der, pos, v)
    }
    if (pos + seqLen != der.size) return null
    return parseSeq(der, pos, seqLen)
}

private fun parseSeq(der: ByteArray, start: Int, len: Int): ByteArray? {
    fun takeInt(at: Int): Pair<ByteArray, Int>? {
        if (at >= der.size || der[at] != 0x02.toByte()) return null
        val l = der[at + 1].toInt() and 0xFF
        if (l >= 0x80 || at + 2 + l > der.size) return null
        return der.copyOfRange(at + 2, at + 2 + l) to (at + 2 + l)
    }
    val (rRaw, afterR) = takeInt(start) ?: return null
    val (sRaw, afterS) = takeInt(afterR) ?: return null
    if (afterS != start + len) return null
    val out = ByteArray(64)
    if (!putFixed(rRaw, out, 0)) return null
    if (!putFixed(sRaw, out, 32)) return null
    return out
}

private fun putFixed(raw: ByteArray, dest: ByteArray, offset: Int): Boolean {
    var v = raw
    if (v.isNotEmpty() && v[0] == 0.toByte()) v = v.copyOfRange(1, v.size)
    if (v.size > 32) return false
    v.copyInto(dest, offset + (32 - v.size))
    return true
}

private fun derInt(v: ByteArray): ByteArray {
    var i = 0
    while (i < v.size - 1 && v[i] == 0.toByte()) i++
    val neg = v[i].toInt() and 0x80 != 0
    val len = v.size - i + if (neg) 1 else 0
    val out = ByteArray(2 + len)
    out[0] = 0x02
    out[1] = len.toByte()
    var o = 2
    if (neg) {
        out[o] = 0
        o++
    }
    v.copyInto(out, o, i, v.size)
    return out
}

// --- P-256 标量乘，与 S0 探针同一算法（Jacobian、按位 Montgomery 阶梯） ---

private val P256_P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
private val P256_A = P256_P.subtract(BigInteger.valueOf(3))
private val P256_B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
private val P256_GX = BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16)
private val P256_GY = BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)

private class Jac(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
    fun inf() = z.signum() == 0
}

private fun mod(v: BigInteger) = v.mod(P256_P)

private fun infinity() = Jac(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO)

private fun generator() = Jac(P256_GX, P256_GY, BigInteger.ONE)

private fun dbl(p: Jac): Jac {
    if (p.inf()) return infinity()
    val delta = mod(p.z.multiply(p.z))
    val gamma = mod(p.y.multiply(p.y))
    val beta = mod(p.x.multiply(gamma))
    val alpha = mod(p.x.subtract(delta).multiply(p.x.add(delta)).multiply(BigInteger.valueOf(3)))
    val x3 = mod(alpha.multiply(alpha).subtract(beta.shiftLeft(3)))
    val z3 = mod(p.y.add(p.z).multiply(p.y.add(p.z)).subtract(gamma).subtract(delta))
    val y3 = mod(alpha.multiply(beta.shiftLeft(2).subtract(x3)).subtract(gamma.multiply(gamma).shiftLeft(3)))
    return Jac(x3, y3, z3)
}

private fun add(p: Jac, q: Jac): Jac {
    if (p.inf()) return q
    if (q.inf()) return p
    val z1z1 = mod(p.z.multiply(p.z))
    val z2z2 = mod(q.z.multiply(q.z))
    val u1 = mod(p.x.multiply(z2z2))
    val u2 = mod(q.x.multiply(z1z1))
    val s1 = mod(p.y.multiply(q.z).multiply(z2z2))
    val s2 = mod(q.y.multiply(p.z).multiply(z1z1))
    val h = mod(u2.subtract(u1))
    val r = mod(s2.subtract(s1))
    if (h.signum() == 0) return if (r.signum() == 0) dbl(p) else infinity()
    val hh = mod(h.multiply(h))
    val hhh = mod(h.multiply(hh))
    val v = mod(u1.multiply(hh))
    val x3 = mod(r.multiply(r).subtract(hhh).subtract(v.shiftLeft(1)))
    val y3 = mod(r.multiply(v.subtract(x3)).subtract(s1.multiply(hhh)))
    val z3 = mod(p.z.multiply(q.z).multiply(h))
    return Jac(x3, y3, z3)
}

private fun bit(d: ByteArray, i: Int): Int {
    val b = d[31 - i / 8].toInt() and 0xFF
    return (b shr (i % 8)) and 1
}

private fun ladder(d: ByteArray): Jac {
    require(d.size == 32)
    var r0 = infinity()
    var r1 = generator()
    for (i in 255 downTo 0) {
        val bit = bit(d, i)
        val a = if (bit == 0) r0 else r1
        val b = if (bit == 0) r1 else r0
        val doubled = dbl(a)
        val added = add(a, b)
        r0 = if (bit == 0) doubled else added
        r1 = if (bit == 0) added else doubled
    }
    return r0
}

private fun p256OnCurve(x: BigInteger, y: BigInteger): Boolean {
    if (x.signum() < 0 || y.signum() < 0 || x >= P256_P || y >= P256_P) return false
    val left = mod(y.multiply(y))
    val right = mod(x.multiply(x).multiply(x).add(P256_A.multiply(x)).add(P256_B))
    return left == right
}

private fun to32(v: BigInteger): ByteArray {
    val raw = v.toByteArray()
    if (raw.size == 33 && raw[0] == 0.toByte()) return raw.copyOfRange(1, 33)
    if (raw.size > 32) error("coord")
    val out = ByteArray(32)
    raw.copyInto(out, 32 - raw.size)
    return out
}

/** 未压缩 X9.63：0x04 ‖ X ‖ Y。不在曲线上或无穷远点则失败。 */
fun p256PublicKey(d32: ByteArray): ByteArray {
    val p = ladder(d32)
    if (p.inf()) error("infinity")
    val zinv = p.z.modInverse(P256_P)
    val zinv2 = mod(zinv.multiply(zinv))
    val x = mod(p.x.multiply(zinv2))
    val y = mod(p.y.multiply(zinv2).multiply(zinv))
    if (!p256OnCurve(x, y)) error("not on curve")
    val out = ByteArray(65)
    out[0] = 0x04
    to32(x).copyInto(out, 1)
    to32(y).copyInto(out, 33)
    return out
}
