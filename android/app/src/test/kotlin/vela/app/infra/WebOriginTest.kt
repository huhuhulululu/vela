package vela.app.infra

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebOriginTest {
    @Test
    fun testAllowsSiteHostSubdomainsAndChallengeCdnOnly() {
        val root = "https://chaturbate.com"
        assertTrue(WebOriginPolicy.allows("https://chaturbate.com/auth/login/", root))
        assertTrue(WebOriginPolicy.allows("https://static.chaturbate.com/a.js", root))
        assertTrue(WebOriginPolicy.allows("https://challenges.cloudflare.com/cdn-cgi/challenge", root))
        assertTrue(WebOriginPolicy.allows("https://static.cloudflareinsights.com/beacon.min.js", root))
        assertFalse(WebOriginPolicy.allows("https://evil.example/pixel.gif", root))
        assertFalse(WebOriginPolicy.allows("https://cdn.example/master.m3u8", root))
        assertFalse(WebOriginPolicy.allows("file:///data/local/tmp/x", root))
        val local = "http://127.0.0.1:9"
        assertTrue(WebOriginPolicy.allows("http://127.0.0.1:9/room", local))
        assertFalse(WebOriginPolicy.allows("http://127.0.0.1.evil.example/", local))
        assertFalse(WebOriginPolicy.allows("http://10.0.2.2:9/", local))
    }
}
