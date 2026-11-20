package vela.core.base.io

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreferenceQuarantineTests {
    private lateinit var directory: Path
    private val now: Instant = Instant.ofEpochSecond(1_700_000_000)

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("vela-quarantine-")
    }

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    private fun file(name: String, contents: ByteArray): Path {
        val url = directory.resolve(name)
        Files.write(url, contents)
        return url
    }

    private fun backupNames(url: Path): List<String> =
        PreferenceQuarantine.backups(url).map { it.fileName.toString() }

    private val failingMove: Mover = { _, _ ->
        throw java.io.IOException("fileWriteNoPermission")
    }

    @Test
    fun testKeepsAtMostThreeNewestBackups() {
        val url = directory.resolve("feed-taste.json")
        for (epoch in listOf(900, 1_000, 800)) {
            Files.write(directory.resolve("feed-taste.json.bad-$epoch"), "old".toByteArray(Charsets.UTF_8))
        }
        Files.write(url, "x".toByteArray(Charsets.UTF_8))
        assertTrue(PreferenceQuarantine.quarantine(url, now))
        assertEquals(
            listOf(
                "feed-taste.json.bad-900",
                "feed-taste.json.bad-1000",
                "feed-taste.json.bad-1700000000",
            ),
            backupNames(url),
            "删最旧的 800，按时间而不是字典序",
        )
    }

    @Test
    fun testSameSecondGetsSuffixAndOtherFilesUntouched() {
        val url = directory.resolve("feed-taste.json")
        val other = file("feed-curation.json.bad-1", "keep".toByteArray(Charsets.UTF_8))
        repeat(2) {
            Files.write(url, "x".toByteArray(Charsets.UTF_8))
            assertTrue(PreferenceQuarantine.quarantine(url, now))
        }
        assertEquals(
            listOf("feed-taste.json.bad-1700000000", "feed-taste.json.bad-1700000000-1"),
            backupNames(url),
        )
        assertTrue(Files.exists(other), "别的文件的备份不受影响")
    }

    @Test
    fun testQuarantineFailureLeavesFileInPlace() {
        val url = file("feed-taste.json", "x".toByteArray(Charsets.UTF_8))
        assertFalse(PreferenceQuarantine.quarantine(url, now, failingMove))
        assertTrue(Files.exists(url))
        assertTrue(backupNames(url).isEmpty())
    }

    @Test
    fun testUnparseableBackupNameIsDroppedFirst() {
        val url = directory.resolve("feed-taste.json")
        for (name in listOf("feed-taste.json.bad-zzz", "feed-taste.json.bad-5", "feed-taste.json.bad-1000")) {
            Files.write(directory.resolve(name), "old".toByteArray(Charsets.UTF_8))
        }
        Files.write(url, "x".toByteArray(Charsets.UTF_8))
        assertTrue(PreferenceQuarantine.quarantine(url, now))
        assertEquals(
            listOf(
                "feed-taste.json.bad-5",
                "feed-taste.json.bad-1000",
                "feed-taste.json.bad-1700000000",
            ),
            backupNames(url),
        )
    }

    @Test
    fun testFitsLimitRejectsOversizedAndAllowsMissing() {
        val ok = file("ok.bin", ByteArray(maxFileBytes) { 1 })
        val big = file("big.bin", ByteArray(maxFileBytes + 1) { 1 })
        val missing = directory.resolve("nope.bin")
        assertTrue(fitsLimit(ok, maxFileBytes))
        assertFalse(fitsLimit(big, maxFileBytes))
        assertTrue(fitsLimit(missing, maxFileBytes), "读不出大小时不拦截，交给后面的读取失败")
    }

    @Test
    fun testReadQuarantinesUndecodableAndMissingIsNotBackedUp() {
        val bad = file("feed-taste.json", "not json".toByteArray(Charsets.UTF_8))
        val quarantined = PreferenceQuarantine.read(bad, maxFileBytes, now) { error("bad") }
        assertTrue(quarantined is PreferenceQuarantine.ReadResult.Quarantined)
        assertEquals(listOf("feed-taste.json.bad-1700000000"), backupNames(bad))
        assertEquals(
            "not json",
            Files.readString(directory.resolve("feed-taste.json.bad-1700000000")),
        )
        assertFalse(Files.exists(bad))

        val missing = directory.resolve("absent.json")
        val missed = PreferenceQuarantine.read(missing, maxFileBytes, now) { 1 }
        assertTrue(missed is PreferenceQuarantine.ReadResult.Missing)
        assertTrue(backupNames(missing).isEmpty())

        val good = file("ok.json", "7".toByteArray(Charsets.UTF_8))
        val loaded = PreferenceQuarantine.read(good, maxFileBytes, now) { bytes ->
            bytes.toString(Charsets.UTF_8).toInt()
        }
        val loadedValue = (loaded as PreferenceQuarantine.ReadResult.Loaded<Int>).value
        assertEquals(7, loadedValue)
    }

    @Test
    fun testOversizedReadIsQuarantinedWithoutDecoding() {
        val url = file("big.json", ByteArray(maxFileBytes + 1) { 0x20 })
        var called = false
        val result = PreferenceQuarantine.read(url, maxFileBytes, now) {
            called = true
            0
        }
        assertFalse(called)
        assertTrue(result is PreferenceQuarantine.ReadResult.Quarantined)
        assertEquals(listOf("big.json.bad-1700000000"), backupNames(url))
    }

    @Test
    fun testReadOnlyWhenMoveFails() {
        val url = file("feed-taste.json", "broken".toByteArray(Charsets.UTF_8))
        val result = PreferenceQuarantine.read(url, maxFileBytes, now, failingMove) { error("bad") }
        assertTrue(result is PreferenceQuarantine.ReadResult.ReadOnly)
        assertTrue(Files.exists(url))
        assertEquals("broken", Files.readString(url))
        assertTrue(backupNames(url).isEmpty())
    }
}
