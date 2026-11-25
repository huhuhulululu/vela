package vela.app.pip

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vela.app.audio.AudioFocusController
import vela.core.audio.AudioIntent
import vela.core.audio.DeactivationReason
import vela.core.base.lane.PlaybackLaneRole

/**
 * A-PARITY-8 单元测试：
 * 1) 产品侧：验证画中画内静音不请求焦点、取消静音精确请求 AUDIOFOCUS_GAIN_TRANSIENT（附带 PAUSES_ON_DUCKABLE_LOSS）、再静音放弃焦点；
 * 2) 解析侧：用多版本真实 dumpsys audio 样例验证 AudioFocusDump 精确区分 GAIN_TRANSIENT、永久 GAIN、MAY_DUCK、EXCLUSIVE、loss!=none、历史日志与其他包。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PipAudioFocusAndDumpTest {
    private lateinit var directory: java.nio.file.Path

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("vela-pip-audio-focus")
    }

    @After
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun testPipUnmuteRequestsExactGainTransientAndMuteAbandons() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val shadowAudio = shadowOf(audioManager)
        shadowAudio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        val mutedStates = ArrayList<Boolean>()
        val controller = AudioFocusController(
            context = context,
            audioManager = audioManager,
            sinks = object : AudioFocusController.Sinks {
                override fun muteLane(role: PlaybackLaneRole) = Unit
                override fun muteAll() = Unit
                override fun unmuteLane(role: PlaybackLaneRole) = Unit
                override fun detachLane(role: PlaybackLaneRole) = Unit
                override fun writeMuted(muted: Boolean) { mutedStates.add(muted) }
                override fun syncRuntimeMuted(muted: Boolean) { mutedStates.add(muted) }
                override fun showNotice(text: String) = Unit
                override fun pausePlayback() = Unit
            },
        )
        controller.start()
        try {
            controller.applyStored(true)
            controller.pictureInPictureActive = true
            assertFalse("静音进画中画不得持有焦点", controller.holdingFocus)
            assertEquals("静音进画中画不得发音频焦点请求", null, shadowAudio.lastAudioFocusRequest)

            // 画中画内取消静音：必须请求精确 AUDIOFOCUS_GAIN_TRANSIENT（不是永久 GAIN，不是 MAY_DUCK）。
            controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
            assertTrue("取消静音后应持有焦点", controller.holdingFocus)
            val recorded = shadowAudio.lastAudioFocusRequest
            assertNotNull("取消静音后应向系统发出 AudioFocusRequest", recorded)
            assertEquals(
                "durationHint 必须为 AUDIOFOCUS_GAIN_TRANSIENT",
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                recorded.durationHint,
            )
            val req = recorded.audioFocusRequest
            assertNotNull("应携带真实 AudioFocusRequest", req)
            assertEquals(
                "必须请求 AUDIOFOCUS_GAIN_TRANSIENT",
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                req.focusGain,
            )
            assertTrue("应设置 willPauseWhenDucked（对应 PAUSES_ON_DUCKABLE_LOSS）", req.willPauseWhenDucked())
            assertEquals(AudioAttributes.USAGE_MEDIA, req.audioAttributes.usage)

            // 再静音：必须放弃同一焦点请求。
            controller.execute(AudioIntent.Deactivate(DeactivationReason.MUTE))
            assertFalse("再静音后必须放弃焦点", controller.holdingFocus)
            assertEquals(req, shadowAudio.lastAbandonedAudioFocusRequest)
        } finally {
            controller.stop()
        }
    }

    @Test
    fun testDumpParserAcceptsApi34RealFormatWithPausesOnDuckableLossFlag() {
        val realApi34Dump = """
            Audio Focus stack entries (last is top of stack):
              source:android.os.BinderProxy@c8382c -- pack: vela.app -- client: android.media.AudioManager@9f58d6dvela.app.audio.AudioFocusController${'$'}${'$'}ExternalSyntheticLambda0@df29da2 -- gain: GAIN_TRANSIENT -- flags: PAUSES_ON_DUCKABLE_LOSS -- loss: none -- notified: true -- limbofalse -- uid: 10141 -- attr: AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MOVIE flags=0x800 tags= bundle=null -- sdk:36

            Events log: focus commands as seen by MediaFocusControl
            01-01 00:00:01 requestAudioFocus() from uid/pid 10141/1234
        """.trimIndent()

        assertTrue(AudioFocusDump.packageInStack(realApi34Dump))
        assertTrue(
            "flags 含 PAUSES_ON_DUCKABLE_LOSS 且 loss=none 时必须识别为精确 GAIN_TRANSIENT",
            AudioFocusDump.hasGainTransient(realApi34Dump),
        )
    }

    @Test
    fun testDumpParserAcceptsPrefixedAndNumericGainTransientFormats() {
        val prefixed = """
            Audio Focus stack entries (last is top of stack):
              source:binder -- package: vela.app -- gain: AUDIOFOCUS_GAIN_TRANSIENT -- flags: PAUSES_ON_DUCKABLE_LOSS -- loss: none
        """.trimIndent()
        val numeric = """
            Audio Focus stack entries (last is top of stack):
              source:binder -- pack: vela.app -- gain: 2 -- loss: 0
        """.trimIndent()

        assertTrue(AudioFocusDump.hasGainTransient(prefixed))
        assertTrue(AudioFocusDump.hasGainTransient(numeric))
    }

    @Test
    fun testDumpParserRejectsPermanentGainMayDuckExclusiveLossHistoryAndOtherPackages() {
        val permanentGain = """
            Audio Focus stack entries (last is top of stack):
              source:binder -- pack: vela.app -- gain: GAIN -- flags: PAUSES_ON_DUCKABLE_LOSS -- loss: none

            Events log: focus commands as seen by MediaFocusControl
              pack: vela.app gain: GAIN_TRANSIENT
        """.trimIndent()
        val permanentNumeric = """
            Audio Focus stack entries (last is top of stack):
              source:binder -- pack: vela.app -- gain: 1 -- loss: none
        """.trimIndent()
        val mayDuck = """
            Audio Focus stack entries (last is top of stack):
              source:binder -- pack: vela.app -- gain: GAIN_TRANSIENT_MAY_DUCK -- flags: none -- loss: none
        """.trimIndent()
        val mayDuckNumeric = """
            Audio Focus stack entries (last is top of stack):
              source:binder -- pack: vela.app -- gain: 3 -- loss: none
        """.trimIndent()
        val exclusive = """
            Audio Focus stack entries (last is top of stack):
              source:binder -- pack: vela.app -- gain: GAIN_TRANSIENT_EXCLUSIVE -- flags: none -- loss: none
        """.trimIndent()
        val lostFocus = """
            Audio Focus stack entries (last is top of stack):
              source:binder -- pack: vela.app -- gain: GAIN_TRANSIENT -- flags: PAUSES_ON_DUCKABLE_LOSS -- loss: LOSS_TRANSIENT
        """.trimIndent()
        val otherAppTransientOwnPermanent = """
            Audio Focus stack entries (last is top of stack):
              source:binder -- pack: com.other.music -- gain: GAIN_TRANSIENT -- loss: none
              source:binder -- pack: vela.app -- gain: GAIN -- loss: none
        """.trimIndent()
        val onlyInHistory = """
            Audio Focus stack entries (last is top of stack):

            Events log: focus commands as seen by MediaFocusControl
              source:binder -- pack: vela.app -- gain: GAIN_TRANSIENT -- loss: none
        """.trimIndent()

        assertFalse("永久 GAIN 必须被拒绝", AudioFocusDump.hasGainTransient(permanentGain))
        assertFalse("数字 1（永久 GAIN）必须被拒绝", AudioFocusDump.hasGainTransient(permanentNumeric))
        assertFalse("GAIN_TRANSIENT_MAY_DUCK 必须被拒绝", AudioFocusDump.hasGainTransient(mayDuck))
        assertFalse("数字 3（MAY_DUCK）必须被拒绝", AudioFocusDump.hasGainTransient(mayDuckNumeric))
        assertFalse("GAIN_TRANSIENT_EXCLUSIVE 必须被拒绝", AudioFocusDump.hasGainTransient(exclusive))
        assertFalse("loss=LOSS_TRANSIENT 必须被拒绝", AudioFocusDump.hasGainTransient(lostFocus))
        assertFalse("其他包持有 GAIN_TRANSIENT 而本包为永久 GAIN 必须被拒绝", AudioFocusDump.hasGainTransient(otherAppTransientOwnPermanent))
        assertFalse("仅在历史日志中出现不得通过", AudioFocusDump.packageInStack(onlyInHistory))
        assertFalse("仅在历史日志中出现不得通过", AudioFocusDump.hasGainTransient(onlyInHistory))
    }
}
