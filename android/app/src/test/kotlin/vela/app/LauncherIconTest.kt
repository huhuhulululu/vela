package vela.app

import android.graphics.BitmapFactory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 启动图标必须是桌面版那套 A2 帆，不能留着系统默认图标。
 * 清单指向 mipmap，自适应前景的中心像素是帆的暖色，角落仍透明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherIconTest {
    @Test
    fun testManifestPointsAtSailLauncher() {
        val main = mainSource()
        val manifest = File(main, "AndroidManifest.xml")
        assertTrue(manifest.isFile)
        val text = manifest.readText()
        assertTrue(text.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(text.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))

        val adaptive = File(main, "res/mipmap-anydpi-v26/ic_launcher.xml")
        assertTrue("自适应图标 xml 要在", adaptive.isFile)
        val adaptiveText = adaptive.readText()
        assertTrue(adaptiveText.contains("@mipmap/ic_launcher_foreground"))
        assertTrue(adaptiveText.contains("@color/ic_launcher_background"))

        val foreground = File(main, "res/mipmap-xxxhdpi/ic_launcher_foreground.png")
        assertTrue("xxxhdpi 前景要在", foreground.isFile)
        val bitmap = BitmapFactory.decodeFile(foreground.absolutePath)
        assertNotNull("前景要能解成位图", bitmap)
        assertEquals(432, bitmap!!.width)
        assertEquals(432, bitmap.height)
        val cx = (bitmap.width * 502) / 1024
        val cy = (bitmap.height * 473) / 1024
        val sail = bitmap.getPixel(cx, cy)
        val red = (sail shr 16) and 0xff
        val blue = sail and 0xff
        assertTrue("帆面中心应是暖色，实际 ${Integer.toHexString(sail)}", red > 140 && red > blue + 30)
        val corner = bitmap.getPixel(2, 2)
        val alpha = (corner ushr 24) and 0xff
        assertEquals("圆角外应透明，好让自适应背景露出来", 0, alpha)
    }

    private fun mainSource(): File {
        val dir = listOf(File("src/main"), File("app/src/main")).firstOrNull {
            File(it, "AndroidManifest.xml").isFile
        }
        assertNotNull("找不到清单目录，测试不能假装通过", dir)
        return dir!!
    }
}
