package vela.app.lane

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import vela.core.hls.DisplayFit

/**
 * 按 [DisplayFit.fitted] 把 [SurfaceView] 放进窗格，不裁切、不拉伸视频层。
 * 留边是模糊缩略图；没有缩略图时用深色渐变，避免大块纯黑。
 */
class LaneFrame(context: Context) : FrameLayout(context) {
    val videoSurface: SurfaceView = SurfaceView(context)
    private val backdrop = ImageView(context)
    private val gradient = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(DisplayCheck.FALLBACK_TOP, DisplayCheck.FALLBACK_BOTTOM),
    )

    var laneLabel: String = "primary"
    var presentation: DisplayFit.Size = DisplayFit.Size(0.0, 0.0)
        set(value) {
            field = value
            requestLayout()
        }

    var appliedVideoRect: DisplayFit.Rect = DisplayFit.Rect.zero
        private set

    var onVideoRect: ((DisplayFit.Rect) -> Unit)? = null

    private var surfaceCallback: ((Surface?) -> Unit)? = null
    private var currentSurface: Surface? = null
    private var thumbnail: Bitmap? = null
    private var blurred: Bitmap? = null
    private var lastLine: String? = null

    /**
     * 播放器要等真正的 Surface 才交出首帧、才回报画面尺寸。
     * 回调可能晚于 surfaceCreated，挂上时把当前 Surface 补交一次。
     */
    var onSurface: ((Surface?) -> Unit)?
        get() = surfaceCallback
        set(value) {
            surfaceCallback = value
            val surface = currentSurface
            if (value != null && surface != null) value.invoke(surface)
        }

    init {
        setBackgroundColor(DisplayCheck.FALLBACK_TOP)
        backdrop.scaleType = ImageView.ScaleType.FIT_XY
        backdrop.setImageDrawable(gradient)
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(videoSurface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        videoSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                deliverSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                deliverSurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                deliverSurface(null)
            }
        })
    }

    private fun deliverSurface(surface: Surface?) {
        currentSurface = surface
        surfaceCallback?.invoke(surface)
    }

    fun setThumbnail(bitmap: Bitmap?) {
        if (bitmap === thumbnail) return
        thumbnail = bitmap
        val previous = blurred
        if (bitmap == null) {
            blurred = null
            backdrop.setImageDrawable(gradient)
        } else {
            val next = DisplayCheck.blurThumbnail(bitmap)
            blurred = if (next === bitmap) null else next
            backdrop.setImageBitmap(next)
        }
        if (previous != null && previous !== blurred && previous !== bitmap) previous.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val exactW = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val exactH = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        backdrop.measure(exactW, exactH)
        videoSurface.measure(exactW, exactH)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        backdrop.layout(0, 0, width, height)
        val bounds = DisplayFit.Rect(0.0, 0.0, width.toDouble(), height.toDouble())
        val video = DisplayFit.fitted(presentation, bounds)
        appliedVideoRect = video
        if (video.width >= 2.0 && video.height >= 2.0) {
            val videoLeft = video.x.toInt()
            val videoTop = video.y.toInt()
            val videoRight = (video.x + video.width).toInt()
            val videoBottom = (video.y + video.height).toInt()
            videoSurface.layout(videoLeft, videoTop, videoRight, videoBottom)
            videoSurface.visibility = VISIBLE
        } else if (width >= 2 && height >= 2) {
            // 尺寸还没来。先铺满窗格，否则 Surface 不会创建，
            // 软件解码也不交出首帧，画面尺寸一直是 0。
            videoSurface.layout(0, 0, width, height)
            videoSurface.visibility = VISIBLE
        } else {
            videoSurface.layout(0, 0, 0, 0)
            videoSurface.visibility = INVISIBLE
        }
        onVideoRect?.invoke(video)
        val line = DisplayCheck.line(laneLabel, presentation, bounds, video)
        if (line != lastLine) {
            lastLine = line
            LaneLog.i(line)
        }
    }
}

@Composable
fun LaneSurface(
    lane: PlaybackLane,
    thumbnail: Bitmap?,
    modifier: Modifier = Modifier,
    laneLabel: String = lane.laneLabel,
) {
    var presentation by remember { mutableStateOf(lane.currentVideoSize()) }
    DisposableEffect(lane) {
        val listener: (Int, Int) -> Unit = { width, height ->
            presentation = DisplayFit.Size(width.toDouble(), height.toDouble())
        }
        lane.addVideoSizeListener(listener)
        val existing = lane.currentVideoSize()
        if (existing.width > 0.0 && existing.height > 0.0) presentation = existing
        onDispose {
            lane.removeVideoSizeListener(listener)
            lane.attachSurface(null)
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { context -> LaneFrame(context) },
        update = { frame ->
            frame.laneLabel = laneLabel
            frame.presentation = presentation
            frame.setThumbnail(thumbnail)
            frame.onSurface = { surface -> lane.attachSurface(surface) }
            frame.onVideoRect = { rect -> lane.noteVideoRect(rect) }
        },
    )
}
