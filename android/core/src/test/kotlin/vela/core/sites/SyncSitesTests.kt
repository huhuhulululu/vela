package vela.core.sites

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import vela.core.curation.CurationStore
import vela.core.sync.SyncClock
import vela.core.sync.SyncLocalData
import vela.core.sync.SyncMigration

/**
 * 同步与站点键交互（对齐 Swift `SyncSitesTests` 中安卓侧已具备等价行为的用例）。
 * 其余 4 条多站点同步用例因安卓 `SyncKeys`/`SyncProjection` 尚未支持 `c4:` 暂未补写。
 */
class SyncSitesTests {
    private val dev: Long = 0xa1b2c3d4L

    @Test
    fun testSiteKeyMigrationDoesNotChangeWhatSyncSees() {
        val dir = Files.createTempDirectory("sync-sites-")
        try {
            val curation = dir.resolve("feed-curation.json")
            Files.writeString(curation, """{"favorites":["Alice","bob"],"blocked":["spam_1"]}""")
            val before = CurationStore(curation).load()
            val report = SiteKeyMigration.run(
                curation = curation,
                taste = dir.resolve("feed-taste.json"),
                ledger = dir.resolve("watch-ledger.json"),
            )
            assertEquals(listOf(curation), report.migrated)
            val after = CurationStore(curation).load()
            assertEquals(before, after)
            val clock = SyncClock(dev = dev)
            val a = SyncMigration.stampCreator(SyncLocalData(curation = before), clock, pt = 1_000L).first.doc
            val b = SyncMigration.stampCreator(SyncLocalData(curation = after), clock, pt = 1_000L).first.doc
            assertContentEquals(a.encoded(), b.encoded())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
