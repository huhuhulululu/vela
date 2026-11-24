package vela.core.sites

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import vela.core.base.io.PreferenceQuarantine
import vela.core.base.model.Prefs
import vela.core.curation.Curation
import vela.core.ledger.WatchLedger
import vela.core.ledger.ledgerJson
import vela.core.taste.Taste
import vela.core.taste.tasteJson

/** 三文件迁移：与 Swift `SiteKeyMigrationTests` 同输入同期望。 */
class SiteKeyMigrationTests {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testMissingFilesAreSkipped() {
        val directory = tempDir()
        try {
            val report = SiteKeyMigration.run(
                curation = directory.resolve("feed-curation.json"),
                taste = directory.resolve("feed-taste.json"),
                ledger = directory.resolve("watch-ledger.json"),
            )
            assertTrue(report.migrated.isEmpty())
            assertEquals(3, report.skipped.size)
            assertTrue(report.failed.isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testOldFileMigratesOnceAndSecondRunDoesNotTouchBytes() {
        val directory = tempDir()
        try {
            val curation = directory.resolve("feed-curation.json")
            val taste = directory.resolve("feed-taste.json")
            val ledger = directory.resolve("watch-ledger.json")
            Files.writeString(curation, """{"favorites":["Alice","c4:Bob"],"blocked":["dave"]}""")
            Files.writeString(taste, """{"seen":{"alice":50},"paused":false}""")
            Files.writeString(
                ledger,
                """{"entries":{"alice":{"totalSeconds":12,"visitCount":1,"lastVisitStart":1,"suggested":false,"lastUsed":12}}}""",
            )
            val original = Files.readAllBytes(curation)
            val first = SiteKeyMigration.run(curation, taste, ledger)
            assertEquals(
                setOf("feed-curation.json", "feed-taste.json", "watch-ledger.json"),
                first.migrated.map { it.fileName.toString() }.toSet(),
            )
            val migrated = Files.readAllBytes(curation)
            val object_ = json.parseToJsonElement(migrated.decodeToString()).jsonObject
            assertEquals(2, object_["keyVersion"]?.jsonPrimitive?.int)
            val loaded = json.decodeFromString(Curation.serializer(), migrated.decodeToString())
            assertTrue(loaded.isFavorite("alice"))
            assertTrue(loaded.isFavorite("c4:bob"))
            assertTrue(loaded.isBlocked("dave"))
            assertEquals(
                Instant.ofEpochSecond(50),
                tasteJson.decodeFromString(Taste.serializer(), Files.readString(taste)).lastSeen("cb:alice"),
            )
            assertEquals(
                12.0,
                ledgerJson.decodeFromString(WatchLedger.serializer(), Files.readString(ledger)).entries["alice"]?.totalSeconds,
            )
            val bytes = Files.readAllBytes(curation)
            val second = SiteKeyMigration.run(curation, taste, ledger)
            assertTrue(second.migrated.isEmpty())
            assertEquals(3, second.skipped.size)
            assertTrue(bytes.contentEquals(Files.readAllBytes(curation)))
            val backup = SiteKeyMigration.backupPath(curation)
            assertTrue(original.contentEquals(Files.readAllBytes(backup)))
            Files.writeString(curation, "changed")
            val third = SiteKeyMigration.run(curation, taste, ledger)
            assertTrue(third.skipped.contains(curation))
            assertTrue(original.contentEquals(Files.readAllBytes(backup)))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testBackupIsCreatedOnce() {
        val directory = tempDir()
        try {
            val curation = directory.resolve("feed-curation.json")
            Files.writeString(curation, """{"favorites":["alice"],"blocked":[]}""")
            SiteKeyMigration.run(curation, directory.resolve("missing-taste.json"), directory.resolve("missing-ledger.json"))
            val backup = SiteKeyMigration.backupPath(curation)
            val firstBackup = Files.readAllBytes(backup)
            SiteKeyMigration.run(curation, directory.resolve("missing-taste.json"), directory.resolve("missing-ledger.json"))
            assertTrue(firstBackup.contentEquals(Files.readAllBytes(backup)))
            val backups = Files.list(directory).use { stream ->
                stream.map { it.fileName.toString() }.filter { it.contains("pre-sites") }.toList()
            }
            assertEquals(1, backups.size)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testCopyFailureLeavesTheOriginalAlone() {
        val directory = tempDir()
        try {
            val curation = directory.resolve("feed-curation.json")
            val original = """{"favorites":["alice"],"blocked":[]}""".encodeToByteArray()
            Files.write(curation, original)
            val report = SiteKeyMigration.run(
                curation = curation,
                taste = directory.resolve("no-taste.json"),
                ledger = directory.resolve("no-ledger.json"),
                copy = { _, _ -> throw IOException("fileWriteNoPermission") },
            )
            assertEquals(listOf(curation), report.failed)
            assertTrue(original.contentEquals(Files.readAllBytes(curation)))
            assertFalse(Files.exists(SiteKeyMigration.backupPath(curation)))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testWriteFailureKeepsOriginalAndBackup() {
        val directory = tempDir()
        try {
            val curation = directory.resolve("feed-curation.json")
            val original = """{"favorites":["alice"],"blocked":[]}""".encodeToByteArray()
            Files.write(curation, original)
            val report = SiteKeyMigration.run(
                curation = curation,
                taste = directory.resolve("no-taste.json"),
                ledger = directory.resolve("no-ledger.json"),
                write = { _, _ -> throw IOException("fileWriteNoPermission") },
            )
            assertEquals(listOf(curation), report.failed)
            assertTrue(original.contentEquals(Files.readAllBytes(curation)))
            assertTrue(original.contentEquals(Files.readAllBytes(SiteKeyMigration.backupPath(curation))))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testBrokenAndOversizeFilesAreLeftForQuarantine() {
        val directory = tempDir()
        try {
            val broken = directory.resolve("feed-curation.json")
            Files.writeString(broken, "broken")
            val report = SiteKeyMigration.run(broken, directory.resolve("no-taste.json"), directory.resolve("no-ledger.json"))
            assertEquals(broken, report.skipped.first())
            assertEquals("broken", Files.readString(broken))
            assertFalse(Files.exists(SiteKeyMigration.backupPath(broken)))
            val quarantined = PreferenceQuarantine.read(
                path = broken,
                limit = Prefs.maxFileBytes,
                now = Instant.ofEpochSecond(10),
                decode = { bytes -> json.decodeFromString(Curation.serializer(), bytes.decodeToString()) },
            )
            assertTrue(quarantined is PreferenceQuarantine.ReadResult.Quarantined)
            assertFalse(Files.exists(broken))

            val huge = directory.resolve("feed-taste.json")
            Files.write(huge, ByteArray(Prefs.maxFileBytes + 1) { 0x20 })
            val skipped = SiteKeyMigration.run(
                directory.resolve("absent.json"),
                huge,
                directory.resolve("absent-ledger.json"),
            )
            assertTrue(skipped.skipped.contains(huge))
            assertEquals(Prefs.maxFileBytes + 1, Files.size(huge).toInt())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testRollbackRestoresOriginalBytes() {
        val directory = tempDir()
        try {
            val curation = directory.resolve("feed-curation.json")
            val taste = directory.resolve("feed-taste.json")
            val ledger = directory.resolve("watch-ledger.json")
            val original = """{"favorites":["Alice"],"blocked":[]}""".encodeToByteArray()
            Files.write(curation, original)
            Files.writeString(taste, """{"seen":{"alice":1}}""")
            Files.writeString(ledger, """{"entries":{}}""")
            SiteKeyMigration.run(curation, taste, ledger)
            assertFalse(original.contentEquals(Files.readAllBytes(curation)))
            val rolled = SiteKeyMigration.rollback(curation, taste, ledger)
            assertEquals(3, rolled.migrated.size)
            assertTrue(original.contentEquals(Files.readAllBytes(curation)))
            assertTrue(SiteKeyMigration.removeBackups(curation, taste, ledger))
            assertFalse(Files.exists(SiteKeyMigration.backupPath(curation)))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testMixedOldAndNewKeys() {
        val directory = tempDir()
        try {
            val curation = directory.resolve("feed-curation.json")
            Files.writeString(
                curation,
                """{"favorites":["Alice","cb:Carol","c4:Bob","c4:bob"],"blocked":["cb:dave"]}""",
            )
            val report = SiteKeyMigration.run(curation, directory.resolve("no-taste.json"), directory.resolve("no-ledger.json"))
            assertEquals(listOf(curation), report.migrated)
            val loaded = json.decodeFromString(Curation.serializer(), Files.readString(curation))
            assertTrue(loaded.isFavorite("alice"))
            assertTrue(loaded.isFavorite("carol"))
            assertTrue(loaded.isFavorite("c4:bob"))
            assertEquals(1, loaded.favorites.count { RoomKey.normalizeRef(it) == "c4:bob" })
            assertTrue(loaded.isBlocked("dave"))
            assertFalse(loaded.isBlocked("c4:dave"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun tempDir(): Path = Files.createTempDirectory("vela-site-keys-")
}
