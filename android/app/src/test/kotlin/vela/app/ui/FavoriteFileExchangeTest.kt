package vela.app.ui

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import java.io.ByteArrayInputStream
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
import vela.app.readLimited
import vela.app.stage.StageIntent
import vela.core.base.model.Prefs
import vela.core.curation.Curation

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
                assertTrue(activity.stage.curation.isFavorite("zoe"))
                val huge = Uri.parse("content://vela.test/huge.json")
                shadowOf(activity.contentResolver).registerInputStream(
                    huge,
                    ByteArrayInputStream(ByteArray(Prefs.maxFileBytes + 8) { ' '.code.toByte() }),
                )
                activity.importFavoritesFrom(huge)
                assertFalse(activity.stage.curation.isFavorite(" "))
                assertTrue(activity.stage.state.value.toast != null)
            }
        }
    }
}
