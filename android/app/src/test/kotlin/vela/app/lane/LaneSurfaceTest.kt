package vela.app.lane

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.core.hls.DisplayFit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaneSurfaceTest {
    @After
    fun tearDown() {
        LaneLog.clear()
    }

    @Test
    fun testLandscapeInPortraitIsUncroppedAndLetterboxed() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val frame = LaneFrame(context)
        frame.laneLabel = "primary"
        frame.presentation = DisplayFit.Size(1920.0, 1080.0)
        val width = View.MeasureSpec.makeMeasureSpec(270, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        frame.measure(width, height)
        frame.layout(0, 0, 270, 480)

        val line = LaneLog.snapshot().last { it.startsWith("display-check") }
        assertTrue(line.contains("lane=primary"))
        assertTrue(line.contains("gravity=fit"))
        assertTrue(line.contains("presentation=1920.00x1080.00"))
        assertTrue(line.contains("uncropped=true"))
        assertTrue(DisplayFit.isUncropped(frame.appliedVideoRect, DisplayFit.Rect(0.0, 0.0, 270.0, 480.0), DisplayFit.Size(1920.0, 1080.0)))
        assertEquals(270, frame.videoSurface.width)
        assertTrue("height=${frame.videoSurface.height}", frame.videoSurface.height in 140..170)
        assertTrue("top=${frame.videoSurface.top}", frame.videoSurface.top > 100)
        assertTrue(frame.videoSurface.top + frame.videoSurface.height < 480)
    }

    @Test
    fun testUnknownPresentationKeepsARealSurface() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val frame = LaneFrame(context)
        frame.laneLabel = "primary"
        val width = View.MeasureSpec.makeMeasureSpec(270, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
        frame.measure(width, height)
        frame.layout(0, 0, 270, 480)

        assertEquals(270, frame.videoSurface.width)
        assertEquals(480, frame.videoSurface.height)
        assertEquals(View.VISIBLE, frame.videoSurface.visibility)
        assertEquals(0.0, frame.appliedVideoRect.width, 0.001)
        assertEquals(0.0, frame.appliedVideoRect.height, 0.001)
        val line = LaneLog.snapshot().last { it.startsWith("display-check") }
        assertTrue(line.contains("presentation=0.00x0.00"))
        assertTrue(line.contains("uncropped=false"))
    }

    @Test
    fun testStretchedRectLogsCropped() {
        val presentation = DisplayFit.Size(1920.0, 1080.0)
        val bounds = DisplayFit.Rect(0.0, 0.0, 100.0, 100.0)
        val stretched = DisplayFit.Rect(0.0, 0.0, 100.0, 100.0)
        val line = DisplayCheck.line("secondary", presentation, bounds, stretched)
        assertTrue(line.contains("lane=secondary"))
        assertTrue(line.contains("uncropped=false"))
        assertFalse(DisplayFit.isUncropped(stretched, bounds, presentation))
    }

    @Test
    fun testBlurShrinksLongEdgeTo16() {
        val source = Bitmap.createBitmap(32, 8, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(220, 30, 40))
        val blurred = DisplayCheck.blurThumbnail(source)
        assertTrue(blurred.width <= DisplayCheck.BLUR_EDGE)
        assertTrue(blurred.height <= DisplayCheck.BLUR_EDGE)
        assertEquals(16, maxOf(blurred.width, blurred.height))
        val pixel = blurred.getPixel(0, 0)
        assertTrue(Color.red(pixel) > 100)
        if (blurred !== source) blurred.recycle()
        source.recycle()
    }

    @Test
    fun testFallbackGradientIsNotMostlyBlack() {
        val bitmap = DisplayCheck.fallbackBackdrop(40, 80)
        var black = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if ((bitmap.getPixel(x, y) and 0xFFFFFF) == 0) black += 1
            }
        }
        val fraction = black.toDouble() / (bitmap.width * bitmap.height).toDouble()
        assertTrue("black=$fraction", fraction < 0.95)
        assertTrue(bitmap.getPixel(0, 0) != bitmap.getPixel(0, bitmap.height - 1))
        bitmap.recycle()
    }

    @Test
    fun testLetterboxFractionIgnoresThePicture() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        for (y in 2 until 8) {
            for (x in 0 until 10) bitmap.setPixel(x, y, Color.RED)
        }
        val video = DisplayFit.Rect(0.0, 2.0, 10.0, 6.0)
        val fraction = DisplayCheck.letterboxPureBlackFraction(bitmap, 0, 0, 10, 10, video)
        assertEquals(1.0, fraction, 0.001)
        bitmap.recycle()
    }

    @Test
    fun testVideoLayerSourceDoesNotCropOrStretch() {
        val dir = listOf(
            File("src/main/kotlin/vela/app/lane"),
            File("app/src/main/kotlin/vela/app/lane"),
        ).firstOrNull { it.isDirectory } ?: return
        val text = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(text.contains("ContentScale.Crop"))
        assertFalse(text.contains("FillBounds"))
        assertFalse(text.contains("RESIZE_MODE_ZOOM"))
    }
}
