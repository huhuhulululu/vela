import Foundation

/// 统一挂起的代次。异步工作开始时记下 `begin()` 的代次，下面四处都要再核对：
/// 发请求前、提交结果前、注册中继源前、中继 `start()` 前。
/// 代次不一致就丢掉这次结果。macOS 不会进入挂起，不调用 `suspend()` 时这些检查是空操作。
///
/// 画中画用 `suspend(allowingLane:)`，对应 `suspendSharedTasks` 与 `allowLaneRequests`：
/// 共享任务（目录、搜索、收藏查询、侧车、缩略图）照常挂起，只放行那一路的请求、中继登记、中继启动和提交。
/// `resume()` 不回退代次，挂起前的 token 仍然无效。
public struct LifecycleEpoch: Equatable, Sendable {
    public struct Token: Equatable, Sendable {
        /// 共享任务，或绑定到某一路。
        public enum Scope: Equatable, Sendable {
            case shared
            case lane(PlaybackLaneRole)
        }

        public let epoch: UInt64
        public let scope: Scope

        /// 不带路的 token 视为共享任务。已有调用方不用改。
        public init(epoch: UInt64) {
            self.init(epoch: epoch, scope: .shared)
        }

        public init(epoch: UInt64, scope: Scope) {
            self.epoch = epoch
            self.scope = scope
        }
    }

    /// 计划里要校验的四个时机。
    /// 未挂起且代次一致就放行。全量挂起一律拒绝。
    /// 只放行某一路时，共享任务和其他路拒绝；这一路四处都放行，提交也放行，否则结果无法落上。
    public enum Gate: Equatable, Sendable, CaseIterable {
        case beforeRequest
        case beforeCommit
        case beforeRelayRegister
        case beforeRelayStart
    }

    public private(set) var suspended: Bool
    public private(set) var epoch: UInt64
    /// 画中画挂起时放行的那一路。未挂起或全量挂起时为 nil。
    public private(set) var allowedLane: PlaybackLaneRole?

    public init(suspended: Bool = false, epoch: UInt64 = 0) {
        self.suspended = suspended
        self.epoch = epoch
        self.allowedLane = nil
    }

    /// 开工一个共享任务。全量挂起，或只放行某一路时，返回 nil。
    public func begin() -> Token? {
        begin(scope: .shared)
    }

    /// 开工某一路。全量挂起返回 nil；只放行一路时，其他路返回 nil。
    public func begin(lane: PlaybackLaneRole) -> Token? {
        begin(scope: .lane(lane))
    }

    /// 代次仍是开工时的值，并且当前还接纳这个作用域，才放行。
    public func allows(_ gate: Gate, token: Token) -> Bool {
        guard token.epoch == epoch else { return false }
        switch gate {
        case .beforeRequest, .beforeCommit, .beforeRelayRegister, .beforeRelayStart:
            return admits(token.scope)
        }
    }

    /// 中继 `start()` / `waitUntilListening()` 的全局开关。
    /// 全量挂起时为假。只放行某一路时为真，具体是不是这一路仍要看 `beforeRelayStart`。
    public var allowStart: Bool {
        !suspended || allowedLane != nil
    }

    /// 进入后台：代次加一，进行中的旧代次全部作废。不放行任何路。
    public mutating func suspend() {
        suspended = true
        allowedLane = nil
        epoch &+= 1
    }

    /// 挂起共享任务，只放行 `lane` 这一路。代次加一，挂起前的 token 作废。
    /// 再调一次就换成新的那一路，上一路的 token 一并作废。
    public mutating func suspend(allowingLane lane: PlaybackLaneRole) {
        suspended = true
        allowedLane = lane
        epoch &+= 1
    }

    /// 回到前台：允许新的工作与中继启动。代次不回退，挂起前记下的 token 仍然无效。
    public mutating func resume() {
        suspended = false
        allowedLane = nil
    }

    private func begin(scope: Token.Scope) -> Token? {
        guard admits(scope) else { return nil }
        return Token(epoch: epoch, scope: scope)
    }

    /// 未挂起时什么都接纳。全量挂起时什么都不接纳。
    /// 只放行一路时，只接纳这一路，共享任务和其他路都不接纳。
    private func admits(_ scope: Token.Scope) -> Bool {
        if !suspended { return true }
        guard let allowedLane else { return false }
        if case .lane(let lane) = scope {
            return lane == allowedLane
        }
        return false
    }
}
