package vela.app.sync

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import vela.core.sync.SyncEncoding
import vela.core.sync.SyncKeychain
import vela.core.sync.SyncKeychainRead

/** 密钥条目 `{K, dev, blobID}`（docs/plans/sync.md 第 12.1 节）：一次写入，原子生效。 */
data class SyncKeyEntry(
    val key: ByteArray,
    val dev: Long,
    val blobID: String,
) {
    fun encoded(): ByteArray {
        val k = SyncEncoding.base64url(key)
        return """{"K":"$k","dev":$dev,"blobID":"$blobID"}""".toByteArray(Charsets.UTF_8)
    }

    override fun equals(other: Any?): Boolean {
        val o = other as? SyncKeyEntry ?: return false
        return key.contentEquals(o.key) && dev == o.dev && blobID == o.blobID
    }

    override fun hashCode(): Int {
        var r = key.contentHashCode()
        r = 31 * r + dev.hashCode()
        r = 31 * r + blobID.hashCode()
        return r
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        fun decode(bytes: ByteArray): SyncKeyEntry? {
            val obj = try {
                json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            val kText = (obj["K"] as? JsonPrimitive)?.contentOrNull ?: return null
            val dev = (obj["dev"] as? JsonPrimitive)?.longOrNull ?: return null
            val blobID = (obj["blobID"] as? JsonPrimitive)?.contentOrNull ?: return null
            val key = SyncEncoding.decodeBase64url(kText) ?: return null
            if (key.size != 32) return null
            if (dev !in 1L..0xFFFF_FFFFL) return null
            if (blobID.length != 64 || SyncEncoding.decodeHex(blobID) == null) return null
            return SyncKeyEntry(key, dev, blobID)
        }
    }
}

/** 读取结果三类（docs/plans/sync.md 第 13 节）。 */
sealed class SyncKeyLookup {
    data object None : SyncKeyLookup()
    data class Present(val entry: SyncKeyEntry) : SyncKeyLookup()

    /** 暂时不可用（首次解锁前、Keystore 暂时性错误）：本次跳过同步，稍后再试。 */
    data object Unavailable : SyncKeyLookup()

    /** 永久失效（Keystore 密钥丢失、KeyPermanentlyInvalidatedException、解包失败）：提示重新输入同步码。 */
    data object Invalidated : SyncKeyLookup()
}

enum class SyncKeyWrite { OK, UNAVAILABLE, FAILED }

/** 完整密钥存储接口：S4 的 [SyncKeychain] 加上读写条目本身。 */
interface SyncKeyStore : SyncKeychain {
    fun readEntry(): SyncKeyLookup
    fun write(entry: SyncKeyEntry): SyncKeyWrite
}

/** AndroidKeyStore AES-256-GCM 包裹密钥的最小隔离层，供单测注入三类故障。 */
interface SyncWrapCipher {
    sealed class WrapResult {
        class Ok(val iv: ByteArray, val ciphertext: ByteArray) : WrapResult()
        data object Unavailable : WrapResult()
        data object Failed : WrapResult()
    }

    sealed class UnwrapResult {
        class Ok(val plaintext: ByteArray) : UnwrapResult()
        data object Unavailable : UnwrapResult()
        data object Invalidated : UnwrapResult()
    }

    fun wrap(plaintext: ByteArray): WrapResult
    fun unwrap(iv: ByteArray, ciphertext: ByteArray): UnwrapResult
}

/**
 * 真实 AndroidKeyStore 包裹实现（docs/plans/sync.md 第 13 节）：
 * 别名 `vela_sync_wrap`，AES-256-GCM，`setUserAuthenticationRequired(false)`，每次包裹由 Cipher 生成随机 IV。
 */
class AndroidKeyStoreWrapCipher(
    private val alias: String = KEY_ALIAS,
) : SyncWrapCipher {
    override fun wrap(plaintext: ByteArray): SyncWrapCipher.WrapResult {
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            if (iv == null || iv.size != GCM_IV_BYTES) return SyncWrapCipher.WrapResult.Failed
            val ct = cipher.doFinal(plaintext)
            SyncWrapCipher.WrapResult.Ok(iv, ct)
        } catch (e: Throwable) {
            if (isTemporaryFailure(e)) SyncWrapCipher.WrapResult.Unavailable else SyncWrapCipher.WrapResult.Failed
        }
    }

    override fun unwrap(iv: ByteArray, ciphertext: ByteArray): SyncWrapCipher.UnwrapResult {
        if (iv.size != GCM_IV_BYTES || ciphertext.size < GCM_TAG_BYTES) {
            return SyncWrapCipher.UnwrapResult.Invalidated
        }
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = ks.getKey(alias, null) as? SecretKey
                ?: return SyncWrapCipher.UnwrapResult.Invalidated
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            SyncWrapCipher.UnwrapResult.Ok(cipher.doFinal(ciphertext))
        } catch (e: Throwable) {
            when {
                isPermanentFailure(e) -> SyncWrapCipher.UnwrapResult.Invalidated
                isTemporaryFailure(e) -> SyncWrapCipher.UnwrapResult.Unavailable
                else -> SyncWrapCipher.UnwrapResult.Invalidated
            }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = try {
            ks.getKey(alias, null) as? SecretKey
        } catch (_: KeyPermanentlyInvalidatedException) {
            ks.deleteEntry(alias)
            null
        } catch (_: UnrecoverableKeyException) {
            ks.deleteEntry(alias)
            null
        }
        if (existing != null) return existing
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        const val KEY_ALIAS: String = "vela_sync_wrap"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val GCM_TAG_BITS = 128

        internal fun isPermanentFailure(error: Throwable): Boolean =
            error is KeyPermanentlyInvalidatedException ||
                error is AEADBadTagException ||
                error is BadPaddingException ||
                error is UnrecoverableKeyException

        internal fun isTemporaryFailure(error: Throwable): Boolean =
            error is UserNotAuthenticatedException ||
                error is ProviderException ||
                error is java.security.KeyStoreException ||
                error is IllegalStateException
    }
}

/**
 * 同步密钥存储：包裹后的密文存专用 SharedPreferences 文件 `vela_sync`（单键 `space`，一次 `commit()`）。
 * 任何 Keystore 失败都绝不把密钥明文落盘，也绝不自动生成新 K。
 */
class AndroidSyncKeyStore(
    private val preferences: SharedPreferences,
    private val cipher: SyncWrapCipher = AndroidKeyStoreWrapCipher(),
) : SyncKeyStore {
    constructor(context: Context, cipher: SyncWrapCipher = AndroidKeyStoreWrapCipher()) : this(
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        cipher = cipher,
    )

    override fun readEntry(): SyncKeyLookup {
        val raw = preferences.getString(KEY_SPACE, null) ?: return SyncKeyLookup.None
        val parts = raw.split('.')
        if (parts.size != 3 || parts[0] != WIRE_PREFIX) return SyncKeyLookup.Invalidated
        val iv = SyncEncoding.decodeBase64url(parts[1]) ?: return SyncKeyLookup.Invalidated
        val ct = SyncEncoding.decodeBase64url(parts[2]) ?: return SyncKeyLookup.Invalidated
        return when (val opened = cipher.unwrap(iv, ct)) {
            is SyncWrapCipher.UnwrapResult.Ok -> {
                val entry = SyncKeyEntry.decode(opened.plaintext)
                if (entry != null) SyncKeyLookup.Present(entry) else SyncKeyLookup.Invalidated
            }
            SyncWrapCipher.UnwrapResult.Unavailable -> SyncKeyLookup.Unavailable
            SyncWrapCipher.UnwrapResult.Invalidated -> SyncKeyLookup.Invalidated
        }
    }

    override fun write(entry: SyncKeyEntry): SyncKeyWrite {
        val plain = entry.encoded()
        return when (val wrapped = cipher.wrap(plain)) {
            is SyncWrapCipher.WrapResult.Ok -> {
                val wire = "$WIRE_PREFIX.${SyncEncoding.base64url(wrapped.iv)}.${SyncEncoding.base64url(wrapped.ciphertext)}"
                if (preferences.edit().putString(KEY_SPACE, wire).commit()) {
                    SyncKeyWrite.OK
                } else {
                    SyncKeyWrite.FAILED
                }
            }
            SyncWrapCipher.WrapResult.Unavailable -> SyncKeyWrite.UNAVAILABLE
            SyncWrapCipher.WrapResult.Failed -> SyncKeyWrite.FAILED
        }
    }

    override fun read(): SyncKeychainRead = when (val lookup = readEntry()) {
        SyncKeyLookup.None -> SyncKeychainRead.None
        is SyncKeyLookup.Present -> SyncKeychainRead.Present(lookup.entry.blobID)
        SyncKeyLookup.Unavailable -> SyncKeychainRead.Unavailable
        SyncKeyLookup.Invalidated -> SyncKeychainRead.Invalidated
    }

    override fun delete(): Boolean = preferences.edit().remove(KEY_SPACE).commit()

    companion object {
        const val PREFS_NAME: String = "vela_sync"
        const val KEY_SPACE: String = "space"
        private const val WIRE_PREFIX = "v1"
    }
}
