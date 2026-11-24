package vela.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验收 2 与 7：minSdk 26 门槛、S0 探针三级分级、服务端地址构建期注入与未配置时隐藏入口。
 */
class SyncGateAndConfigTest {
    @Test
    fun testBelowMinSdk26DoesNotLoadModuleOrShowEntry() {
        var loaded = false
        val result = SyncPlatformGate.loadIfSupported(sdkInt = 25) {
            loaded = true
            "sync-module"
        }
        assertNull("minSdk 26 以下不得加载同步模块", result)
        assertFalse("factory 不得被调用", loaded)
        assertFalse(SyncEntryPolicy.showEntry(sdkInt = 25, capability = SyncCapability.FULL, configuredOrigin = "https://sync.example"))
        assertFalse(SyncEntryPolicy.canCreate(sdkInt = 25, capability = SyncCapability.FULL, configuredOrigin = "https://sync.example"))
        assertEquals(SyncCapability.DISABLED, SyncProbe.run(sdkInt = 25).capability)
    }

    @Test
    fun testAtMinSdk26LoadsModuleAndRunsProbe() {
        var loaded = false
        val result = SyncPlatformGate.loadIfSupported(sdkInt = 26) {
            loaded = true
            "sync-module"
        }
        assertEquals("sync-module", result)
        assertTrue(loaded)
        val report = SyncProbe.run(sdkInt = 26)
        assertTrue(report.keyImport)
        assertTrue(report.signAndConvert)
        assertTrue(report.publicKeyMatch)
        assertTrue(report.crossVerify)
        assertTrue(report.pubcheckVerify)
        assertEquals(SyncCapability.FULL, report.capability)
    }

    @Test
    fun testProbeTierClassificationMatchesSection18Table() {
        assertEquals(
            SyncCapability.FULL,
            SyncProbe.classify(
                keyImport = true,
                signAndConvert = true,
                publicKeyMatch = true,
                crossVerify = true,
                pubcheckVerify = true,
            ),
        )
        assertEquals(
            SyncCapability.JOIN_ONLY,
            SyncProbe.classify(
                keyImport = true,
                signAndConvert = true,
                publicKeyMatch = false,
                crossVerify = true,
                pubcheckVerify = true,
            ),
        )
        assertEquals(
            SyncCapability.DISABLED,
            SyncProbe.classify(
                keyImport = false,
                signAndConvert = true,
                publicKeyMatch = true,
                crossVerify = true,
                pubcheckVerify = true,
            ),
        )
        assertEquals(
            SyncCapability.DISABLED,
            SyncProbe.classify(
                keyImport = true,
                signAndConvert = false,
                publicKeyMatch = true,
                crossVerify = true,
                pubcheckVerify = true,
            ),
        )
        assertEquals(
            SyncCapability.DISABLED,
            SyncProbe.classify(
                keyImport = true,
                signAndConvert = true,
                publicKeyMatch = true,
                crossVerify = false,
                pubcheckVerify = true,
            ),
        )
        assertEquals(
            SyncCapability.DISABLED,
            SyncProbe.classify(
                keyImport = true,
                signAndConvert = true,
                publicKeyMatch = true,
                crossVerify = true,
                pubcheckVerify = false,
            ),
        )
    }

    @Test
    fun testEntryVisibilityAndCreateVisibilityByCapabilityAndConfig() {
        val origin = "https://sync.example"
        assertFalse(
            "探针禁用时不显示入口",
            SyncEntryPolicy.showEntry(sdkInt = 34, capability = SyncCapability.DISABLED, configuredOrigin = origin),
        )
        assertFalse(
            "探针禁用时不能创建",
            SyncEntryPolicy.canCreate(sdkInt = 34, capability = SyncCapability.DISABLED, configuredOrigin = origin),
        )

        assertTrue(
            "只能加入时显示入口",
            SyncEntryPolicy.showEntry(sdkInt = 34, capability = SyncCapability.JOIN_ONLY, configuredOrigin = origin),
        )
        assertFalse(
            "只能加入时隐藏创建入口",
            SyncEntryPolicy.canCreate(sdkInt = 34, capability = SyncCapability.JOIN_ONLY, configuredOrigin = origin),
        )
        assertTrue(
            "只能加入时保留加入入口",
            SyncEntryPolicy.canJoin(sdkInt = 34, capability = SyncCapability.JOIN_ONLY, configuredOrigin = origin),
        )

        assertTrue(SyncEntryPolicy.showEntry(sdkInt = 34, capability = SyncCapability.FULL, configuredOrigin = origin))
        assertTrue(SyncEntryPolicy.canCreate(sdkInt = 34, capability = SyncCapability.FULL, configuredOrigin = origin))
        assertTrue(SyncEntryPolicy.canJoin(sdkInt = 34, capability = SyncCapability.FULL, configuredOrigin = origin))

        assertFalse(
            "服务端地址未配置时不显示同步入口",
            SyncEntryPolicy.showEntry(sdkInt = 34, capability = SyncCapability.FULL, configuredOrigin = null),
        )
        assertFalse(
            "服务端地址未配置时不显示同步入口（只能加入）",
            SyncEntryPolicy.showEntry(sdkInt = 34, capability = SyncCapability.JOIN_ONLY, configuredOrigin = null),
        )
    }

    @Test
    fun testSyncConfigHasNoHardcodedOriginAndValidatesCanonicalOrigin() {
        assertNull("默认未配置构建期地址时必须返回 null", SyncConfig.configuredOrigin())
        assertEquals("https://sync.example", SyncConfig.parseConfiguredOrigin("  https://sync.example  "))
        assertEquals("https://sync.example:8443", SyncConfig.parseConfiguredOrigin("https://sync.example:8443"))
        assertNull(SyncConfig.parseConfiguredOrigin(""))
        assertNull(SyncConfig.parseConfiguredOrigin("   "))
        assertNull(SyncConfig.parseConfiguredOrigin("http://sync.example"))
        assertNull(SyncConfig.parseConfiguredOrigin("https://127.0.0.1"))
        assertNull(SyncConfig.parseConfiguredOrigin("https://sync.example/"))
        assertNull(SyncConfig.parseConfiguredOrigin("https://sync.example:443"))
    }
}
