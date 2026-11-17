import Foundation

/// 统一挂起的代次。异步工作开始时记下 `begin()` 的代次，下面四处都要再核对：
/// 发请求前、提交结果前、注册中继源前、中继 `start()` 前（`allowStart`）。
/// 代次不一致就丢掉这次结果。macOS 不会进入挂起，不调用 `suspend()` 时这些检查是空操作。
public struct LifecycleEpoch: Equatable, Sendable {
    public struct Token: Equatable, Sendable {
        public let epoch: UInt64

        public init(epoch: UInt64) {
            self.epoch = epoch
        }
    }

    /// 计划里要校验的四个时机。四处规则相同：已挂起，或代次与开工时不一致，就拒绝。
    public enum Gate: Equatable, Sendable, CaseIterable {
        case beforeRequest
        case beforeCommit
        case beforeRelayRegister
        case beforeRelayStart
    }

    public private(set) var suspended: Bool
    public private(set) var epoch: UInt64

    public init(suspended: Bool = false, epoch: UInt64 = 0) {
        self.suspended = suspended
        self.epoch = epoch
    }

    /// 开工。挂起期间返回 nil，调用方不得再发请求或注册中继。
    public func begin() -> Token? {
        guard !suspended else { return nil }
        return Token(epoch: epoch)
    }

    /// 代次仍是开工时的值，且当前没挂起，才放行。提交前被拒绝就丢弃结果。
    public func allows(_ gate: Gate, token: Token) -> Bool {
        switch gate {
        case .beforeRequest, .beforeCommit, .beforeRelayRegister, .beforeRelayStart:
            return !suspended && token.epoch == epoch
        }
    }

    /// 中继 `start()` / `waitUntilListening()` 的开关。挂起期间为假。
    public var allowStart: Bool { !suspended }

    /// 进入后台：代次加一，进行中的旧代次全部作废。
    public mutating func suspend() {
        suspended = true
        epoch &+= 1
    }

    /// 回到前台：允许新的工作与中继启动。代次不回退，挂起前记下的 token 仍然无效。
    public mutating func resume() {
        suspended = false
    }
}
