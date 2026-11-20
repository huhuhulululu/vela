package vela.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import org.robolectric.Robolectric
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
    fun testExportWriteSurvivesRecreationAfterResult() {
        val expected = FavoriteExchange.encode(listOf("alice"))
        val uri = Uri.parse("content://vela.test/out-recreate.json")
        val written = ByteArrayOutputStream()
        val release = CountDownLatch(1)
        val running = CountDownLatch(64)
        val executor = Dispatchers.IO.asExecutor()
        repeat(96) {
            executor.execute {
                running.countDown()
                release.await()
            }
        }
        try {
            assertTrue("IO 没有饱和", running.await(5, TimeUnit.SECONDS))
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
                    shadowOf(activity).receiveResult(
                        request,
                        Activity.RESULT_OK,
                        Intent().setData(uri),
                    )
                }
                ShadowLooper.idleMainLooper()
                scenario.recreate()
                ShadowLooper.idleMainLooper()
                scenario.onActivity { activity ->
                    shadowOf(activity.contentResolver).registerOutputStream(uri, written)
                }
                release.countDown()
                assertTrue(waitUntil(scenario) { written.size() > 0 })
                assertArrayEquals(expected, written.toByteArray())
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun testOlderExportCompletionDoesNotClearTheNewerPendingExport() {
        val alice = FavoriteExchange.encode(listOf("alice"))
        val bob = FavoriteExchange.encode(listOf("bob"))
        val uriA = Uri.parse("content://vela.test/export-a.json")
        val uriB = Uri.parse("content://vela.test/export-b.json")
        val gate = HoldingStream()
        val writtenB = ByteArrayOutputStream()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.stage.ageConfirmed = true
                activity.stage.curation = Curation(favorites = listOf("alice"))
                activity.stage.dispatch(StageIntent.ExportFavorites)
            }
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                val request = shadowOf(activity).nextStartedActivityForResult.intent
                shadowOf(activity.contentResolver).registerOutputStream(uriA, gate)
                shadowOf(activity).receiveResult(
                    request,
                    Activity.RESULT_OK,
                    Intent().setData(uriA),
                )
            }
            assertTrue("第一次导出没有开始写", gate.started.await(5, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                activity.stage.curation = Curation(favorites = listOf("bob"))
                activity.stage.dispatch(StageIntent.ExportFavorites)
            }
            ShadowLooper.idleMainLooper()
            lateinit var requestB: Intent
            scenario.onActivity { activity ->
                requestB = shadowOf(activity).nextStartedActivityForResult.intent
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, requestB.action)
                shadowOf(activity.contentResolver).registerOutputStream(uriB, writtenB)
            }
            gate.release.countDown()
            assertTrue("第一次导出没有写完", gate.finished.await(5, TimeUnit.SECONDS))
            assertTrue(waitUntil(scenario) {
                File(it.filesDir, "Vela/pending-favorite-export").isFile
            })
            scenario.onActivity { activity ->
                shadowOf(activity).receiveResult(
                    requestB,
                    Activity.RESULT_OK,
                    Intent().setData(uriB),
                )
            }
            assertTrue(waitUntil(scenario) { writtenB.size() > 0 })
            assertArrayEquals(bob, writtenB.toByteArray())
            assertArrayEquals(alice, gate.bytes())
            scenario.onActivity { activity ->
                assertFalse(activity.stage.state.value.toast == activity.getString(R.string.ui_export_failed))
            }
        }
    }

    @Test
    fun testClearedDiskExportIsNotReplayedFromAStaleBundle() {
        val uri = Uri.parse("content://vela.test/stale-export.json")
        val gate = HoldingStream()
        val replay = ByteArrayOutputStream()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.stage.ageConfirmed = true
        activity.stage.curation = Curation(favorites = listOf("alice"))
        activity.stage.dispatch(StageIntent.ExportFavorites)
        ShadowLooper.idleMainLooper()
        val request = shadowOf(activity).nextStartedActivityForResult.intent
        shadowOf(activity.contentResolver).registerOutputStream(uri, gate)
        shadowOf(activity).receiveResult(
            request,
            Activity.RESULT_OK,
            Intent().setData(uri),
        )
        assertTrue("导出没有开始写", gate.started.await(5, TimeUnit.SECONDS))
        controller.pause().stop()
        val bundle = Bundle()
        controller.saveInstanceState(bundle)
        assertTrue("保存状态里没有未完成的导出", bundle.getBoolean("vela.pendingExport"))
        gate.release.countDown()
        assertTrue("导出没有写完", gate.finished.await(5, TimeUnit.SECONDS))
        assertTrue(diskCleared(activity))
        controller.destroy()

        val release = CountDownLatch(1)
        val running = CountDownLatch(64)
        val executor = Dispatchers.IO.asExecutor()
        repeat(96) {
            executor.execute {
                running.countDown()
                release.await()
            }
        }
        try {
            assertTrue("IO 没有饱和", running.await(5, TimeUnit.SECONDS))
            val next = Robolectric.buildActivity(MainActivity::class.java)
            next.create(bundle)
            val again = next.get()
            shadowOf(again.contentResolver).registerOutputStream(uri, replay)
            next.start().resume()
            release.countDown()
            val deadline = System.currentTimeMillis() + 1_000
            while (System.currentTimeMillis() < deadline) {
                ShadowLooper.idleMainLooper()
                if (replay.size() > 0) break
                Thread.sleep(20)
            }
            assertEquals(0, replay.size())
            assertFalse(again.stage.state.value.toast == again.getString(R.string.ui_export_failed))
            next.destroy()
        } finally {
            release.countDown()
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

    private fun diskCleared(activity: MainActivity): Boolean {
        val deadline = System.currentTimeMillis() + 4_000
        val snapshot = File(activity.filesDir, "Vela/pending-favorite-export")
        val uri = File(activity.filesDir, "Vela/pending-favorite-export-uri")
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (!snapshot.exists() && !uri.exists()) return true
            Thread.sleep(20)
        }
        ShadowLooper.idleMainLooper()
        return !snapshot.exists() && !uri.exists()
    }

    private class HoldingStream : OutputStream() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        private val buf = ByteArrayOutputStream()

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()))
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            buf.write(b, off, len)
            started.countDown()
            release.await(8, TimeUnit.SECONDS)
            finished.countDown()
        }

        fun bytes(): ByteArray = buf.toByteArray()
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
