package vela.core.cross

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vela.core.base.io.Mover
import vela.core.base.io.PreferenceQuarantine
import vela.core.base.model.Prefs
import vela.core.curation.Curation
import vela.core.curation.CurationStore
import vela.core.ledger.WatchLedger
import vela.core.ledger.WatchLedgerStore
import vela.core.taste.Taste
import vela.core.taste.TasteStore

/** L0b 标成 deferred:C9 的三个存储隔离测试。旧偏好迁移不移植，坏文件不会去读 legacy。 */
class DeferredQuarantineTests {
    private lateinit var directory: Path
    private val now: Instant = Instant.ofEpochSecond(1_700_000_000)

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("vela-quarantine-cross-")
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

    private val failingMove: Mover = { _, _ -> throw java.io.IOException("fileWriteNoPermission") }

    @Test
    fun testCorruptTasteIsBackedUpThenSavable() {
        val url = file("feed-taste.json", utf8("not json"))
        val store = TasteStore(url)
        assertEquals(Taste(), store.load(now))
        assertFalse(store.isReadOnly)
        assertEquals(listOf("feed-taste.json.bad-1700000000"), backupNames(url))
        val backup = directory.resolve("feed-taste.json.bad-1700000000")
        assertTrue(Files.readAllBytes(backup).contentEquals(utf8("not json")), "现场原样保留")
        assertFalse(Files.exists(url))
        assertTrue(store.save(Taste(), now))
    }

    @Test
    fun testCorruptTasteDoesNotFallBackToLegacy() {
        val url = file("feed-taste.json", utf8("{"))
        val legacy = file("legacy.json", utf8("{}"))
        assertEquals(Taste(), TasteStore(url).load(now))
        assertEquals(1, backupNames(url).size)
        assertTrue(Files.readAllBytes(legacy).contentEquals(utf8("{}")), "不读旧偏好文件")
    }

    @Test
    fun testOversizedCurationIsBackedUp() {
        val url = file("feed-curation.json", ByteArray(Prefs.maxFileBytes + 1) { 0x20 })
        val store = CurationStore(url)
        assertEquals(Curation(), store.load(now))
        assertEquals(listOf("feed-curation.json.bad-1700000000"), backupNames(url))
        assertTrue(store.save(Curation().favoriting("a")))
        assertEquals(listOf("a"), store.load(now).favorites)
    }

    @Test
    fun testCorruptLedgerIsBackedUp() {
        val url = file("watch-ledger.json", utf8("[]"))
        val store = WatchLedgerStore(url)
        assertEquals(WatchLedger(), store.load(now))
        assertEquals(1, backupNames(url).size)
        assertTrue(store.save(WatchLedger()))
    }

    @Test
    fun testRenameFailureMakesStoresReadOnly() {
        val bad = utf8("broken")
        val taste = TasteStore(file("feed-taste.json", bad))
        val curation = CurationStore(file("feed-curation.json", bad))
        val ledger = WatchLedgerStore(file("watch-ledger.json", bad))
        taste.moveItem = failingMove
        curation.moveItem = failingMove
        ledger.moveItem = failingMove
        assertEquals(Taste(), taste.load(now))
        assertEquals(Curation(), curation.load(now))
        assertEquals(WatchLedger(), ledger.load(now))
        assertTrue(taste.isReadOnly && curation.isReadOnly && ledger.isReadOnly)
        assertFalse(taste.save(Taste(), now))
        assertFalse(curation.save(Curation().blocking("x")))
        assertFalse(ledger.save(WatchLedger()))
        for (name in listOf("feed-taste.json", "feed-curation.json", "watch-ledger.json")) {
            assertTrue(Files.readAllBytes(directory.resolve(name)).contentEquals(bad), "$name 未被覆盖")
        }
    }

    @Test
    fun testReadOnlyClearsAfterGoodLoadOrErase() {
        val url = file("watch-ledger.json", utf8("broken"))
        val ledger = WatchLedgerStore(url)
        ledger.moveItem = failingMove
        ledger.load(now)
        assertTrue(ledger.isReadOnly)
        assertTrue(ledger.erase(), "用户主动清除仍可执行")
        assertFalse(ledger.isReadOnly)
        assertTrue(ledger.save(WatchLedger()))

        val curationURL = file("feed-curation.json", utf8("broken"))
        val curation = CurationStore(curationURL)
        curation.moveItem = failingMove
        curation.load(now)
        Files.write(curationURL, utf8("""{"favorites":["ok"],"blocked":[]}"""))
        assertEquals(listOf("ok"), curation.load(now).favorites)
        assertFalse(curation.isReadOnly)
    }

    @Test
    fun testMissingFileIsNotBackedUpAndSavable() {
        val url = directory.resolve("feed-curation.json")
        val store = CurationStore(url)
        assertEquals(Curation(), store.load(now))
        assertFalse(store.isReadOnly)
        assertTrue(backupNames(url).isEmpty())
        assertTrue(store.save(Curation()))
    }
}
