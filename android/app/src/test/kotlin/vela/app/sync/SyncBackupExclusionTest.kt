package vela.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import vela.app.R

/**
 * 验收 4：备份排除生效。同步状态与密钥相关文件不进云备份、设备间迁移与 API 26–30 全量备份。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncBackupExclusionTest {
    @Test
    fun testManifestDeclaresBackupExclusionAndNoExtraPermissions() {
        val main = mainSource()
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue("allowBackup 必须为 false", manifest.contains("android:allowBackup=\"false\""))
        assertTrue(
            "清单必须引用 dataExtractionRules",
            manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""),
        )
        assertTrue(
            "清单必须引用 fullBackupContent（覆盖 API 26–30）",
            manifest.contains("android:fullBackupContent=\"@xml/full_backup_content\""),
        )
        val perms = Regex("""uses-permission\s+android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.WAKE_LOCK",
            ),
            perms,
        )
    }

    @Test
    fun testDataExtractionRulesExcludeSyncKeyAndStateInCloudAndTransfer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rules = parseDataExtraction(context.resources.getXml(R.xml.data_extraction_rules))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val entries = rules[section].orEmpty()
            assertTrue("$section 必须排除整个 sharedpref 或 vela_sync.xml", excludes(entries, "sharedpref", "vela_sync.xml"))
            assertTrue("$section 必须显式排除 vela_sync.xml", entries.contains("sharedpref" to "vela_sync.xml"))
            assertTrue("$section 必须显式排除 Vela/sync-state.json", entries.contains("file" to "Vela/sync-state.json"))
            assertTrue("$section 必须显式排除 Vela/sync-state.json.corrupt", entries.contains("file" to "Vela/sync-state.json.corrupt"))
            assertTrue("$section 必须显式排除 Vela/sync-join-snapshot", entries.contains("file" to "Vela/sync-join-snapshot"))
            assertTrue("$section 仍排除整域 file", excludesWholeDomain(entries, "file"))
            assertTrue("$section 仍排除整域 sharedpref", excludesWholeDomain(entries, "sharedpref"))
        }
    }

    @Test
    fun testFullBackupContentExcludesSyncKeyAndState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entries = parseFullBackup(context.resources.getXml(R.xml.full_backup_content))
        assertTrue("fullBackupContent 必须显式排除 vela_sync.xml", entries.contains("sharedpref" to "vela_sync.xml"))
        assertTrue("fullBackupContent 必须显式排除 Vela/sync-state.json", entries.contains("file" to "Vela/sync-state.json"))
        assertTrue("fullBackupContent 必须显式排除 Vela/sync-state.json.corrupt", entries.contains("file" to "Vela/sync-state.json.corrupt"))
        assertTrue("fullBackupContent 必须显式排除 Vela/sync-join-snapshot", entries.contains("file" to "Vela/sync-join-snapshot"))
        assertTrue("fullBackupContent 仍排除整域 file", excludesWholeDomain(entries, "file"))
        assertTrue("fullBackupContent 仍排除整域 sharedpref", excludesWholeDomain(entries, "sharedpref"))
    }

    private fun excludes(entries: Set<Pair<String, String>>, domain: String, path: String): Boolean =
        entries.contains(domain to path) || excludesWholeDomain(entries, domain)

    private fun excludesWholeDomain(entries: Set<Pair<String, String>>, domain: String): Boolean =
        entries.contains(domain to "") || entries.contains(domain to ".")

    private fun parseDataExtraction(parser: XmlPullParser): Map<String, Set<Pair<String, String>>> {
        val out = LinkedHashMap<String, MutableSet<Pair<String, String>>>()
        var section: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "cloud-backup", "device-transfer" -> {
                        section = parser.name
                        out.getOrPut(section) { LinkedHashSet() }
                    }
                    "exclude" -> if (section != null) {
                        val domain = parser.getAttributeValue(null, "domain").orEmpty()
                        val path = parser.getAttributeValue(null, "path").orEmpty()
                        out.getValue(section).add(domain to path)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == section) section = null
            }
            event = parser.next()
        }
        return out
    }

    private fun parseFullBackup(parser: XmlPullParser): Set<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "exclude") {
                val domain = parser.getAttributeValue(null, "domain").orEmpty()
                val path = parser.getAttributeValue(null, "path").orEmpty()
                out.add(domain to path)
            }
            event = parser.next()
        }
        return out
    }

    private fun mainSource(): File {
        val dir = listOf(File("src/main"), File("app/src/main")).firstOrNull {
            File(it, "AndroidManifest.xml").isFile
        }
        assertNotNull("找不到清单目录", dir)
        return dir!!
    }
}
