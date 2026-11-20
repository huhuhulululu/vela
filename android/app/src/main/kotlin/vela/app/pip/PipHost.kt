package vela.app.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.view.View
import androidx.core.content.ContextCompat
import vela.app.R

/**
 * Activity 上的画中画系统调用：参数、进入小窗、静音 [RemoteAction]。
 */
class PipHost(
    private val activity: Activity,
    private val onToggleMute: () -> Unit,
) {
  private var muteReceiver: BroadcastReceiver? = null

    val supported: Boolean
        get() = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    var autoEnter: Boolean = true
        set(value) {
            field = value
            refreshAutoEnter()
        }

    var inPictureInPicture: Boolean = false
        private set

    fun onModeChanged(inPip: Boolean) {
        inPictureInPicture = inPip
        if (!inPip) {
            refreshAutoEnter()
        }
    }

    fun updateParams(
        aspect: Rational,
        sourceRect: Rect?,
        muted: Boolean,
        actionsEnabled: Boolean,
    ) {
        if (!supported) return
        val builder = PictureInPictureParams.Builder().setAspectRatio(aspect)
        if (sourceRect != null && sourceRect.width() > 0 && sourceRect.height() > 0) {
            builder.setSourceRectHint(sourceRect)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter && !inPictureInPicture)
        }
        if (actionsEnabled) {
            builder.setActions(listOf(muteAction(muted)))
        }
        activity.setPictureInPictureParams(builder.build())
    }

    fun enter(aspect: Rational, sourceRect: Rect?, muted: Boolean): Boolean {
        if (!supported || inPictureInPicture) return false
        val builder = PictureInPictureParams.Builder().setAspectRatio(aspect)
        if (sourceRect != null && sourceRect.width() > 0 && sourceRect.height() > 0) {
            builder.setSourceRectHint(sourceRect)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(false)
        }
        builder.setActions(listOf(muteAction(muted)))
        return activity.enterPictureInPictureMode(builder.build())
    }

    fun onUserLeaveHint(focusPlaying: Boolean): Boolean {
        if (!supported || !autoEnter || !focusPlaying || inPictureInPicture) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return false
        }
        return activity.enterPictureInPictureMode(
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build(),
        )
    }

    fun registerMuteReceiver() {
        if (muteReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_PIP_MUTE) onToggleMute()
            }
        }
        muteReceiver = receiver
        val filter = IntentFilter(ACTION_PIP_MUTE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                activity,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(receiver, filter)
        }
    }

    fun unregisterMuteReceiver() {
        val receiver = muteReceiver ?: return
        runCatching { activity.unregisterReceiver(receiver) }
        muteReceiver = null
    }

    fun sourceRectOf(view: View?): Rect? {
        if (view == null || view.width <= 0 || view.height <= 0) return null
        val out = Rect()
        if (!view.getGlobalVisibleRect(out)) return null
        return out
    }

    private fun refreshAutoEnter() {
        if (!supported || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (inPictureInPicture) return
        activity.setPictureInPictureParams(
            PictureInPictureParams.Builder()
                .setAutoEnterEnabled(autoEnter)
                .build(),
        )
    }

    private fun muteAction(muted: Boolean): RemoteAction {
        val iconId = if (muted) R.drawable.ic_pip_unmute else R.drawable.ic_pip_mute
        val title = activity.getString(
            if (muted) R.string.pip_action_unmute else R.string.pip_action_mute,
        )
        val intent = Intent(ACTION_PIP_MUTE).setPackage(activity.packageName)
        val pending = PendingIntent.getBroadcast(
            activity,
            REQUEST_PIP_MUTE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(
            Icon.createWithResource(activity, iconId),
            title,
            title,
            pending,
        )
    }

    companion object {
        const val ACTION_PIP_MUTE = "vela.app.pip.TOGGLE_MUTE"
        private const val REQUEST_PIP_MUTE = 7101
    }
}