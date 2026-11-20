package vela.app.infra

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 直接连代理端口看不到 WebView 会不会把回环从代理里摘掉。这里只锁配置本身。 */
class WebProxyConfigTest {
    @Test
    fun testLoopbackAndLinkLocalAreNotImplicitlyBypassed() {
        val config = originProxyConfig(9)
        assertTrue(
            "回环和链路本地仍会绕过代理：${config.bypassRules}",
            config.bypassRules.contains("<-loopback>"),
        )
        assertFalse(config.isReverseBypassEnabled)
        assertTrue(config.proxyRules.any { it.url.contains("127.0.0.1:9") })
    }
}
