package vela.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import vela.app.MainActivity
import vela.app.R
import vela.app.readLimited
import vela.app.stage.StageIntent
import vela.core.base.model.Prefs
import vela.core.curation.Curation
import vela.core.curation.FavoriteExchange

/**
 * 正式 Activity 里导入导出必须接到系统文件选择，而不是空回调和没人读的内部字节。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoriteFileExchangeTest {
    @Test
    fun testExportOpensCreateDocument() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.stage.ageConfirmed = true
                activity.stage.curation = Curation(favorites = listOf("alice"))
                activity.stage.dispatch(StageIntent.ExportFavorites)
            }
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                val intent = shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent?.action)
                assertTrue(intent?.type == "application/json")
            }
        }
    }

    @Test
    fun testImportUsesOpenDocumentAndCapsTheRead() {
        val capped = readLimited(ByteArrayInputStream(ByteArray(Prefs.maxFileBytes + 50) { 1 }), Prefs.maxFileBytes + 1)
        assertEquals(Prefs.maxFileBytes + 1, capped.size)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.launchFavoriteImport()
                val intent = shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent?.action)
                activity.stage.ageConfirmed = true
                val uri = Uri.parse("content://vela.test/fav.json")
                shadowOf(activity.contentResolver).registerInputStream(
                    uri,
                    ByteArrayInputStream("""["zoe"]""".toByteArray()),
                )
                activity.importFavoritesFrom(uri)
            }
            assertTrue(waitUntil(scenario) { it.stage.curation.isFavorite("zoe") })
            scenario.onActivity { activity ->
                val huge = Uri.parse("content://vela.test/huge.json")
                shadowOf(activity.contentResolver).registerInputStream(
                    huge,
                    ByteArrayInputStream(ByteArray(Prefs.maxFileBytes + 8) { ' '.code.toByte() }),
                )
                activity.importFavoritesFrom(huge)
            }
            assertTrue(
                waitUntil(scenario) {
                    it.stage.state.value.toast?.contains("文件太大") == true &&
                        !it.stage.curation.isFavorite(" ")
                },
            )
        }
    }

    @Test
    fun testExportSurvivesActivityRecreation() {
        val expected = FavoriteExchange.encode(listOf("alice"))
        val uri = Uri.parse("content://vela.test/out.json")
        val written = ByteArrayOutputStream()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.stage.ageConfirmed = true
                activity.stage.curation = Curation(favorites = listOf("alice"))
                activity.stage.dispatch(StageIntent.ExportFavorites)
            }
            ShadowLooper.idleMainLooper()
            lateinit var request: Intent
            scenario.onActivity { activity ->
                request = shadowOf(activity).nextStartedActivityForResult.intent
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.action)
            }
            scenario.recreate()
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                shadowOf(activity.contentResolver).registerOutputStream(uri, written)
                shadowOf(activity).receiveResult(
                    request,
                    Activity.RESULT_OK,
                    Intent().setData(uri),
                )
            }
            assertTrue(waitUntil(scenario) { written.size() > 0 })
            assertArrayEquals(expected, written.toByteArray())
        }
    }

    @Test
    fun testDocumentIoFailuresDoNotCrash() {
        val missing = Uri.parse("content://vela.test/missing.json")
        val full = Uri.parse("content://vela.test/full.json")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(activity.contentResolver).registerInputStream(
                    missing,
                    object : InputStream() {
                        override fun read(): Int = throw IOException("gone")
                    },
                )
                activity.importFavoritesFrom(missing)
            }
            assertTrue(
                waitUntil(scenario) {
                    it.stage.state.value.toast == it.getString(R.string.ui_import_failed)
                },
            )
            scenario.onActivity { activity ->
                activity.stage.ageConfirmed = true
                activity.stage.curation = Curation(favorites = listOf("alice"))
                activity.stage.dispatch(StageIntent.ExportFavorites)
            }
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                val request = shadowOf(activity).nextStartedActivityForResult.intent
                shadowOf(activity.contentResolver).registerOutputStream(
                    full,
                    object : OutputStream() {
                        override fun write(b: Int) = throw IOException("full")
                    },
                )
                shadowOf(activity).receiveResult(
                    request,
                    Activity.RESULT_OK,
                    Intent().setData(full),
                )
            }
            assertTrue(
                waitUntil(scenario) {
                    it.stage.state.value.toast == it.getString(R.string.ui_export_failed)
                },
            )
        }
    }

    private fun waitUntil(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long = 4_000,
        probe: (MainActivity) -> Boolean,
    ): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            var ok = false
            scenario.onActivity { ok = probe(it) }
            if (ok) return true
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        var ok = false
        scenario.onActivity { ok = probe(it) }
        return ok
    }
}
