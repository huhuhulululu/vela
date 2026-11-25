package vela.core.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncCodeTests {
    private val key = ByteArray(32) { (it * 7 and 0xFF).toByte() }

    private fun code(origin: String = "https://sync.example.org") =
        SyncCode.format(key, origin, ::sha256)!!

    @Test
    fun testSHA256KnownAnswer() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".toByteArray()))
    }

    @Test
    fun testRoundTrip() {
        val text = code()
        assertTrue(text.startsWith("vela2:"))
        val parsed = SyncCode.parse(text, ::sha256)
        assertTrue(parsed?.key?.contentEquals(key) == true)
        assertEquals("https://sync.example.org", parsed?.origin)
        assertEquals("sync.example.org", parsed?.host)
        assertNotNull(SyncCode.parse(code("https://a.b:8443"), ::sha256))
    }

    @Test
    fun testRejectsTamperingAndNonCanonicalForms() {
        val text = code()
        val flipped = text.toCharArray()
        flipped[8] = if (flipped[8] == 'A') 'B' else 'A'
        val bad = listOf(
            String(flipped),
            text.replace("sync.example.org", "sync.example.com"),
            text.uppercase(),
            text + "/",
            " $text",
            text.replace("vela2:", "vela3:"),
            text.dropLast(1),
        )
        for (b in bad) assertNull(SyncCode.parse(b, ::sha256), b)
    }

    @Test
    fun testOriginRules() {
        for (ok in listOf("https://a.b", "https://sync.example.org", "https://x-y.example.org:1", "https://a.b:65535", "https://xn--fsq.example")) {
            assertTrue(SyncCode.isCanonicalOrigin(ok), ok)
        }
        val bad = listOf(
            "http://a.b", "https://localhost", "https://A.b", "https://a.b/", "https://a.b/x", "https://a.b?q", "https://a.b#f",
            "https://u@a.b", "https://1.2.3.4", "https://[::1]", "https://a.b:443", "https://a.b:0", "https://a.b:65536",
            "https://a.b:080", "https://-a.b", "https://a-.b", "https://a..b", "https://ä.b", "https://a.b:",
            "https://" + "a".repeat(64) + ".b",
        )
        for (b in bad) assertFalse(SyncCode.isCanonicalOrigin(b), b)
    }

    /** 第 4.3 节、第 23 节第 10 条：数字或 0x 十六进制标签可被解析成 IP，顶级标签首字符必须是字母。 */
    @Test
    fun testOriginRejectsNumericLookingLabels() {
        val bad = listOf(
            "https://0x7f.0x1",
            "https://0177.0.0.0x1",
            "https://sync.0x1",
            "https://10.example",
            "https://a.1b",
            "https://sync.123",
        )
        for (b in bad) assertFalse(SyncCode.isCanonicalOrigin(b), b)
        val ok = listOf(
            "https://sync.example",
            "https://a1.b2c",
            "https://sync.xn--fiqs8s",
            "https://0xbox.example",
            "https://s3.example",
        )
        for (b in ok) assertTrue(SyncCode.isCanonicalOrigin(b), b)
    }

    @Test
    fun testFingerprint() {
        assertEquals("3F9A-0C21", SyncCode.fingerprint("3f9a0c21" + "0".repeat(56)))
    }

    @Test
    fun testStateFileRoundTripAndQuarantine() {
        val dir = Files.createTempDirectory("sync-state")
        val url = dir.resolve("sync-state.json")
        val store = SyncStateFileStore(url)
        assertEquals(SyncStateRead.None, store.load())
        val s = SyncState(SyncPhase.ACTIVE, "https://sync.example.org", "a".repeat(64), SyncClock(5, 1, 9))
        s.doc.rooms["cb:alice"] = SyncRoom(f = SyncFixture.reg(5, "+"))
        s.inflight = SyncInflight(1, 2, "ab", 3)
        s.halt = SyncHalt.Gone(42)
        assertTrue(store.save(s))
        assertEquals(SyncStateRead.Present(s), SyncStateFileStore(url).load())
        Files.writeString(url, "{\"phase\":\"active\"}")
        assertEquals(SyncStateRead.Corrupted, SyncStateFileStore(url).load())
        assertFalse(Files.exists(url), "坏文件改名备份")
        assertTrue(store.delete())
    }

    @Test
    fun testStateFileRejectsInvalidEmbeddedDocument() {
        val dir = Files.createTempDirectory("sync-state")
        val url = dir.resolve("sync-state.json")
        val store = SyncStateFileStore(url)
        assertTrue(store.save(SyncState(SyncPhase.ACTIVE, "https://a.b", "x", SyncClock(0, 0, 1))))
        val text = Files.readString(url).replace("\\\"schema\\\":2", "\\\"schema\\\":1")
        Files.writeString(url, text)
        val bad = SyncStateFileStore(url)
        bad.moveItem = { _, _ -> throw java.io.IOException("no") }
        assertEquals(SyncStateRead.Corrupted, bad.load())
        assertFalse(bad.save(SyncState(SyncPhase.ACTIVE, "https://a.b", "x", SyncClock(0, 0, 1))), "改名也失败时只读")
    }

    @Test
    fun testStateFileDeleteResetsReadOnlyFlag() {
        val dir = Files.createTempDirectory("sync-state-")
        try {
            val url = dir.resolve("sync-state.json")
            Files.writeString(url, "{corrupt")
            val store = SyncStateFileStore(url)
            store.moveItem = { _, _ -> throw java.io.IOException("no permission") }
            val fresh = SyncState(SyncPhase.CREATING, "https://sync.example.org", "a".repeat(64), SyncClock(0, 0, 2))

            assertEquals(SyncStateRead.Corrupted, store.load())
            assertFalse(store.save(fresh), "改名失败进入只读")

            val realRemove = store.removeItem
            store.removeItem = { throw java.io.IOException("no permission") }
            assertFalse(store.delete())
            store.removeItem = realRemove
            assertFalse(store.save(fresh), "删除未成功，只读标记不得复位")

            assertTrue(store.delete())
            assertFalse(Files.exists(url))
            assertTrue(store.save(fresh), "清除成功后复位只读标记，不重启即可重新开启")
            assertEquals(SyncStateRead.Present(fresh), store.load())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
