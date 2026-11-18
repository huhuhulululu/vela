package vela.core.lifecycle

import vela.core.base.lane.PlaybackLaneRole

/**
 * 统一挂起的代次。异步工作开始时记下 [begin] 的代次，四处再核对：
 * 发请求前、提交结果前、注册中继源前、中继启动前。代次不一致就丢掉这次结果。
 * 不调用 [suspend] 时这些检查是空操作。
 *
 * 画中画用 [suspend]，只放行那一路的请求、中继登记、中继启动和提交。
 * [resume] 不回退代次，挂起前的 token 仍然无效。
 *
 * Swift 是值类型。这里是类，方法就地修改；调用方持有同一实例。
 */
class LifecycleEpoch(
    suspended: Boolean = false,
    epoch: ULong = 0uL,
) {
    class Token(
        val epoch: ULong,
        val scope: Scope = Scope.Shared,
    ) {
        /** 共享任务，或绑定到某一路。 */
        sealed class Scope {
            data object Shared : Scope()
            data class Lane(val role: PlaybackLaneRole) : Scope()
        }

        override fun equals(other: Any?): Boolean =
            other is Token && other.epoch == epoch && other.scope == scope

        override fun hashCode(): Int = 31 * epoch.hashCode() + scope.hashCode()
    }

    /**
     * 计划里要校验的四个时机。
     * 未挂起且代次一致就放行。全量挂起一律拒绝。
     * 只放行某一路时，共享任务和其他路拒绝；这一路四处都放行，提交也放行。
     */
    enum class Gate {
        BEFORE_REQUEST,
        BEFORE_COMMIT,
        BEFORE_RELAY_REGISTER,
        BEFORE_RELAY_START,
    }

    var suspended: Boolean = suspended
        private set

    /** 与 Swift `UInt64` 一样回绕。 */
    var epoch: ULong = epoch
        private set

    /** 画中画挂起时放行的那一路。未挂起或全量挂起时为 null。 */
    var allowedLane: PlaybackLaneRole? = null
        private set

    /** 开工一个共享任务。全量挂起，或只放行某一路时，返回 null。 */
    fun begin(): Token? = begin(Token.Scope.Shared)

    /** 开工某一路。全量挂起返回 null；只放行一路时，其他路返回 null。 */
    fun begin(lane: PlaybackLaneRole): Token? = begin(Token.Scope.Lane(lane))

    /** 代次仍是开工时的值，并且当前还接纳这个作用域，才放行。 */
    fun allows(gate: Gate, token: Token): Boolean {
        if (token.epoch != epoch) return false
        return when (gate) {
            Gate.BEFORE_REQUEST,
            Gate.BEFORE_COMMIT,
            Gate.BEFORE_RELAY_REGISTER,
            Gate.BEFORE_RELAY_START,
            -> admits(token.scope)
        }
    }

    /**
     * 中继启动的全局开关。
     * 全量挂起时为假。只放行某一路时为真，具体是不是这一路仍要看 [Gate.BEFORE_RELAY_START]。
     */
    val allowStart: Boolean
        get() = !suspended || allowedLane != null

    /** 进入后台：代次加一，进行中的旧代次全部作废。不放行任何路。 */
    fun suspend() {
        suspended = true
        allowedLane = null
        epoch += 1uL
    }

    /**
     * 挂起共享任务，只放行 [allowingLane] 这一路。代次加一，挂起前的 token 作废。
     * 再调一次就换成新的那一路，上一路的 token 一并作废。
     */
    fun suspend(allowingLane: PlaybackLaneRole) {
        suspended = true
        allowedLane = allowingLane
        epoch += 1uL
    }

    /** 回到前台。代次不回退，挂起前记下的 token 仍然无效。 */
    fun resume() {
        suspended = false
        allowedLane = null
    }

    private fun begin(scope: Token.Scope): Token? {
        if (!admits(scope)) return null
        return Token(epoch = epoch, scope = scope)
    }

    /**
     * 未挂起时什么都接纳。全量挂起时什么都不接纳。
     * 只放行一路时，只接纳这一路，共享任务和其他路都不接纳。
     */
    private fun admits(scope: Token.Scope): Boolean {
        if (!suspended) return true
        val lane = allowedLane ?: return false
        return scope is Token.Scope.Lane && scope.role == lane
    }

    override fun equals(other: Any?): Boolean =
        other is LifecycleEpoch &&
            other.suspended == suspended &&
            other.epoch == epoch &&
            other.allowedLane == allowedLane

    override fun hashCode(): Int {
        var result = suspended.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + (allowedLane?.hashCode() ?: 0)
        return result
    }
}
