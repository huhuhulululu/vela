package vela.app.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.core.sync.SyncEncoding
import vela.core.sync.SyncKeychainRead

/**
 * 验收 3：密钥存 Android Keystore；三类失败各有明确处理；失败时不得把密钥明文落盘。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncKeyStoreTest {
    private lateinit var prefs: SharedPreferences
    private val keyBytes = ByteArray(32) { it.toByte() }
    private val entry = SyncKeyEntry(
        key = keyBytes,
        dev = 0x0A0B_0C0DL,
        blobID = "ab".repeat(32),
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences(AndroidSyncKeyStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun testRoundTripWrapsKeyAndNeverStoresPlaintextInPreferences() {
        val cipher = FakeWrapCipher()
        val store = AndroidSyncKeyStore(prefs, cipher)

        assertEquals(SyncKeyWrite.OK, store.write(entry))
        assertEquals(SyncKeyLookup.Present(entry), store.readEntry())
        assertEquals(SyncKeychainRead.Present(entry.blobID), store.read())

        val raw = prefs.getString(AndroidSyncKeyStore.KEY_SPACE, null)
        assertTrue("应写入包裹后的密文", !raw.isNullOrEmpty())
        assertTrue("密文带版本前缀", raw!!.startsWith("v1."))
        assertFalse("不得把明文 K 的 base64url 落盘", raw.contains(SyncEncoding.base64url(keyBytes)))
        assertFalse("不得把明文 K 的 hex 落盘", raw.contains(SyncEncoding.hex(keyBytes)))
        assertFalse("不得把明文 blobID 落盘", raw.contains(entry.blobID))
    }

    @Test
    fun testSecondWriteReplacesEntryWithFreshIv() {
        val cipher = FakeWrapCipher()
        val store = AndroidSyncKeyStore(prefs, cipher)

        assertEquals(SyncKeyWrite.OK, store.write(entry))
        val firstRaw = prefs.getString(AndroidSyncKeyStore.KEY_SPACE, null)

        val other = SyncKeyEntry(ByteArray(32) { 9 }, 7L, "cd".repeat(32))
        assertEquals(SyncKeyWrite.OK, store.write(other))
        val secondRaw = prefs.getString(AndroidSyncKeyStore.KEY_SPACE, null)

        assertTrue("每次包裹须换新随机 IV", firstRaw != secondRaw)
        assertEquals(SyncKeyLookup.Present(other), store.readEntry())
    }

    @Test
    fun testFailureCategory1_MissingEntryReadsAsNone() {
        val store = AndroidSyncKeyStore(prefs, FakeWrapCipher())
        assertEquals(SyncKeyLookup.None, store.readEntry())
        assertEquals(SyncKeychainRead.None, store.read())
        assertTrue("删除不存在的条目视为成功", store.delete())
    }

    @Test
    fun testFailureCategory2_TemporarilyUnavailableSkipsWithoutMutatingOrPlaintext() {
        val cipher = FakeWrapCipher()
        val store = AndroidSyncKeyStore(prefs, cipher)
        assertEquals(SyncKeyWrite.OK, store.write(entry))
        val storedBefore = prefs.getString(AndroidSyncKeyStore.KEY_SPACE, null)

        cipher.nextUnwrapOverride = SyncWrapCipher.UnwrapResult.Unavailable
        assertEquals(SyncKeyLookup.Unavailable, store.readEntry())
        assertEquals(SyncKeychainRead.Unavailable, store.read())
        assertEquals("暂时不可用时不得改动或删除已存条目", storedBefore, prefs.getString(AndroidSyncKeyStore.KEY_SPACE, null))

        prefs.edit().clear().commit()
        cipher.nextWrapOverride = SyncWrapCipher.WrapResult.Unavailable
        assertEquals(SyncKeyWrite.UNAVAILABLE, store.write(entry))
        assertFalse("写入暂时不可用时不得明文落盘", prefs.contains(AndroidSyncKeyStore.KEY_SPACE))
    }

    @Test
    fun testFailureCategory3_PermanentlyInvalidatedNeverAutoGeneratesOrWritesPlaintext() {
        val cipher = FakeWrapCipher()
        val store = AndroidSyncKeyStore(prefs, cipher)
        assertEquals(SyncKeyWrite.OK, store.write(entry))
        val wrapCountBefore = cipher.wrapCalls

        // 3a. Keystore 密钥永久失效 / 丢失 / AEAD 解包失败
        cipher.nextUnwrapOverride = SyncWrapCipher.UnwrapResult.Invalidated
        assertEquals(SyncKeyLookup.Invalidated, store.readEntry())
        assertEquals(SyncKeychainRead.Invalidated, store.read())
        assertEquals("永久失效时不得自动重新生成或写入新 K", wrapCountBefore, cipher.wrapCalls)

        // 3b. 包裹格式损坏
        prefs.edit().putString(AndroidSyncKeyStore.KEY_SPACE, "corrupt-not-v1").commit()
        cipher.nextUnwrapOverride = null
        assertEquals(SyncKeyLookup.Invalidated, store.readEntry())
        assertEquals(SyncKeychainRead.Invalidated, store.read())

        // 3c. 解包后的 JSON 非法（dev == 0、key 长度错、非 JSON）
        for (badJson in listOf(
            "not json",
            """{"K":"AAAA","dev":1,"blobID":"x"}""",
            """{"K":"${SyncEncoding.base64url(keyBytes)}","dev":0,"blobID":"${entry.blobID}"}""",
        )) {
            val wrapped = cipher.wrap(badJson.toByteArray(Charsets.UTF_8)) as SyncWrapCipher.WrapResult.Ok
            val wire = "v1.${SyncEncoding.base64url(wrapped.iv)}.${SyncEncoding.base64url(wrapped.ciphertext)}"
            prefs.edit().putString(AndroidSyncKeyStore.KEY_SPACE, wire).commit()
            assertEquals("非法内容必须判永久失效：$badJson", SyncKeyLookup.Invalidated, store.readEntry())
        }
    }

    @Test
    fun testWrapFailureNeverWritesPlaintextToDisk() {
        val cipher = FakeWrapCipher()
        val store = AndroidSyncKeyStore(prefs, cipher)
        cipher.nextWrapOverride = SyncWrapCipher.WrapResult.Failed
        assertEquals(SyncKeyWrite.FAILED, store.write(entry))
        assertFalse("包裹失败时 SharedPreferences 不得写入任何值", prefs.contains(AndroidSyncKeyStore.KEY_SPACE))
        assertTrue("SharedPreferences 保持为空", prefs.all.isEmpty())
    }

    @Test
    fun testKeyStoreAliasAndPrefsNameMatchSpecSection13() {
        assertEquals("vela_sync_wrap", AndroidKeyStoreWrapCipher.KEY_ALIAS)
        assertEquals("vela_sync", AndroidSyncKeyStore.PREFS_NAME)
        assertEquals("space", AndroidSyncKeyStore.KEY_SPACE)
    }

    /** 用真实 AES-256-GCM 模拟 Keystore 包裹密钥，并支持注入三类失败。 */
    private class FakeWrapCipher : SyncWrapCipher {
        private val wrapKey = ByteArray(32) { (0xA0 + it).toByte() }
        private val rng = SecureRandom()
        var wrapCalls = 0
        var nextWrapOverride: SyncWrapCipher.WrapResult? = null
        var nextUnwrapOverride: SyncWrapCipher.UnwrapResult? = null

        override fun wrap(plaintext: ByteArray): SyncWrapCipher.WrapResult {
            wrapCalls += 1
            nextWrapOverride?.let { return it }
            val iv = ByteArray(12).also { rng.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, iv))
            return SyncWrapCipher.WrapResult.Ok(iv, cipher.doFinal(plaintext))
        }

        override fun unwrap(iv: ByteArray, ciphertext: ByteArray): SyncWrapCipher.UnwrapResult {
            nextUnwrapOverride?.let { return it }
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, iv))
                SyncWrapCipher.UnwrapResult.Ok(cipher.doFinal(ciphertext))
            } catch (_: Exception) {
                SyncWrapCipher.UnwrapResult.Invalidated
            }
        }
    }
}
