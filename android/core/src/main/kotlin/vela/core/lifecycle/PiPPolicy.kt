package vela.core.lifecycle

import vela.core.base.lane.PlaybackLaneRole

/**
 * 画中画纯函数。输入是平台、场景和开关，输出每一路能不能播、中继留哪一路、音频意图、要不要关掉画中画。
 * 不引用界面或播放器框架。遮挡、最小化、老板键、锁屏都用布尔传入。
 */
object PiPPolicy {
    enum class Platform {
        MAC,
        PHONE,
    }

    /** 画中画控制器状态。[STARTING] 视同已经进入，[STOPPING] 视同正在结束。 */
    enum class State {
        NONE,
        STARTING,
        ACTIVE,
        STOPPING,
    }

    data class Input(
        val platform: Platform,
        val scene: PhoneScenePhase,
        val pictureInPicture: State,
        /** 设置里的「回桌面自动进入」。产品默认开，这里照实传入。 */
        val autoEnterEnabled: Boolean,
        val muted: Boolean,
        val focus: PlaybackLaneRole,
        val lanes: List<PlaybackLaneRole>,
        /** 焦点路是不是正在播放。只有正在播放才会在回桌面时自动进入。 */
        val focusPlaying: Boolean,
        val bossKey: Boolean,
        /** 锁屏和系统休眠效果相同，合成一个输入。 */
        val lockScreenOrSystemSleep: Boolean,
        /** 遮挡和最小化效果相同，拆开是为了两条场景能各自传入。决策不读这两项。 */
        val windowMinimized: Boolean,
        val windowOccluded: Boolean,
    )

    data class LaneDecision(
        val role: PlaybackLaneRole,
        val playbackAllowed: Boolean,
    )

    /** 现在会话该活跃还是停用。停用时通知对方恢复。 */
    enum class SessionActivation {
        KEEP_ACTIVE,
        DEACTIVATE_NOTIFYING_OTHERS,
    }

    data class Decision(
        val lanes: List<LaneDecision>,
        val keepsRelayListening: Boolean,
        /** 非 null 时源表只留这一路。null 表示本策略不要求收成一路。 */
        val retainedRelaySource: PlaybackLaneRole?,
        val audio: PipAudioOutput,
        /**
         * 若此刻再静音，画中画（或即将自动进入）仍要播，就保持会话，只切换成混音。
         * 即 decide 里的 continueFocus：比「控制器状态已经是 active」更宽。
         */
        val keepsSessionActiveOnMute: Boolean,
        val sessionActivation: SessionActivation,
        val shouldClosePictureInPicture: Boolean,
    ) {
        fun playbackAllowed(role: PlaybackLaneRole): Boolean =
            lanes.firstOrNull { it.role == role }?.playbackAllowed ?: false
    }

    fun decide(input: Input): Decision {
        val privacyHide = input.platform == Platform.MAC && (input.bossKey || input.lockScreenOrSystemSleep)
        val engaged = input.pictureInPicture == State.ACTIVE || input.pictureInPicture == State.STARTING
        // 自动进入只发生在手机回桌面、开关开着、还没进画中画、焦点路正在播。mac 的隐私隐藏优先。
        val autoEnter = input.platform == Platform.PHONE &&
            input.scene == PhoneScenePhase.BACKGROUND &&
            input.autoEnterEnabled &&
            input.pictureInPicture == State.NONE &&
            input.focusPlaying &&
            !privacyHide
        val continueFocus = !privacyHide && (engaged || autoEnter)

        val roles = ArrayList(input.lanes)
        if (input.focus !in roles) roles.add(input.focus)
        val laneDecisions = roles.map { role ->
            LaneDecision(
                role = role,
                playbackAllowed = allows(role, input, privacyHide, engaged, continueFocus),
            )
        }

        val keepsRelayListening: Boolean
        val retainedRelaySource: PlaybackLaneRole?
        if (privacyHide || (input.scene == PhoneScenePhase.BACKGROUND && !continueFocus)) {
            keepsRelayListening = false
            retainedRelaySource = null
        } else if (continueFocus || engaged) {
            keepsRelayListening = true
            retainedRelaySource = input.focus
        } else {
            // 前台、也没在画中画：不要求拆监听，也不收成一路。
            keepsRelayListening = true
            retainedRelaySource = null
        }

        val sessionActive = continueFocus || (!input.muted && laneDecisions.any { it.playbackAllowed })
        val sessionActivation = if (sessionActive) {
            SessionActivation.KEEP_ACTIVE
        } else {
            SessionActivation.DEACTIVATE_NOTIFYING_OTHERS
        }

        return Decision(
            lanes = laneDecisions,
            keepsRelayListening = keepsRelayListening,
            retainedRelaySource = retainedRelaySource,
            audio = PipAudioOutput.of(muted = input.muted, sessionActive = sessionActive),
            keepsSessionActiveOnMute = continueFocus,
            sessionActivation = sessionActivation,
            shouldClosePictureInPicture = input.platform == Platform.MAC &&
                privacyHide &&
                input.pictureInPicture != State.NONE,
        )
    }

    /**
     * 老板键、锁屏、系统休眠只在 mac 上停播并关画中画。遮挡和最小化不参与这个判断。
     * 进后台时只有继续的那一路能播。已经在画中画时，另一路也不播。
     */
    private fun allows(
        role: PlaybackLaneRole,
        input: Input,
        privacyHide: Boolean,
        engaged: Boolean,
        continueFocus: Boolean,
    ): Boolean {
        if (privacyHide) return false
        if (input.scene == PhoneScenePhase.BACKGROUND) return continueFocus && role == input.focus
        if (engaged) return role == input.focus
        return true
    }
}
