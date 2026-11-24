package vela.app.sync

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import vela.core.sync.AndroidIcuSyncUnicode
import vela.core.sync.SyncEncoding
import vela.core.sync.SyncKeys
import vela.core.sync.deriveSyncKeys

/**
 * 真机 / 模拟器仪器测试：验证依赖真实 Android 系统能力的部分（AndroidKeyStore 硬件/系统包裹、
 * 真机 P-256 探针、真机 android.icu Unicode 13、剪贴板敏感标记）。
 */
@RunWith(AndroidJUnit4::class)
class SyncDeviceInstrumentTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(AndroidSyncKeyStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(AndroidSyncKeyStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testRealAndroidKeyStoreWrapUnwrapNeverStoresPlaintext() {
        if (Build.VERSION.SDK_INT < SyncPlatformGate.MIN_SDK) return
        val keyBytes = ByteArray(32) { (it * 7 + 3).toByte() }
        val derived = deriveSyncKeys(keyBytes)
        val entry = SyncKeyEntry(keyBytes, 987654L, derived.blobID)
        val store = AndroidSyncKeyStore(context, AndroidKeyStoreWrapCipher("vela_sync_wrap_inst_test"))

        assertEquals(SyncKeyWrite.OK, store.write(entry))
        val rawStored = context.getSharedPreferences(AndroidSyncKeyStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(AndroidSyncKeyStore.KEY_SPACE, null)
        assertNotNull(rawStored)
        assertTrue(rawStored!!.startsWith("v1."))
        assertFalse("SharedPreferences 不得含明文 K", rawStored.contains(SyncEncoding.base64url(keyBytes)))
        assertFalse("SharedPreferences 不得含明文 blobID", rawStored.contains(derived.blobID))

        val loaded = store.readEntry()
        assertEquals(SyncKeyLookup.Present(entry), loaded)
        assertTrue(store.delete())
        assertEquals(SyncKeyLookup.None, store.readEntry())
    }

    @Test
    fun testRealDeviceP256Probe() {
        if (Build.VERSION.SDK_INT < SyncPlatformGate.MIN_SDK) return
        val report = SyncProbe.run(Build.VERSION.SDK_INT)
        assertTrue("真机 P-256 私钥导入应通过", report.keyImport)
        assertTrue("真机 P-256 签名与 DER 转换应通过", report.signAndConvert)
        assertTrue("真机跨端验签应通过", report.crossVerify)
        assertTrue("真机 pubcheck 核对消息应通过", report.pubcheckVerify)
        assertTrue(report.capability == SyncCapability.FULL || report.capability == SyncCapability.JOIN_ONLY)
    }

    @Test
    fun testRealAndroidIcuSyncUnicode() {
        if (Build.VERSION.SDK_INT < SyncPlatformGate.MIN_SDK) return
        assertTrue(AndroidIcuSyncUnicode.available())
        val icu = AndroidIcuSyncUnicode()
        assertTrue(icu.ageAtMost13('a'.code))
        assertTrue(icu.isWhiteSpace(' '.code))
        assertEquals("t:jazz", SyncKeys.syncTagKey("  Jazz  ", icu))
    }

    @Test
    fun testRealSensitiveClipboardFlag() {
        SyncClipboard.copySensitive(context, "vela2:instrument-check")
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip
        assertNotNull(clip)
        assertTrue(clip!!.description.extras?.getBoolean(SyncClipboard.EXTRA_IS_SENSITIVE_KEY, false) == true)
    }
}
