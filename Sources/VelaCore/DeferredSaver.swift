import Foundation

/// 把频繁的保存合并成一次后台串行写：`schedule` 只记下最新值，延迟后在专用队列里写一次；
/// `flush` 同步写出未落盘的最新值（退出时用）。所有写都在同一个串行队列上，不会并发。
public final class DeferredSaver<Value: Sendable>: @unchecked Sendable {
    private let queue = DispatchQueue(label: "vela.deferred-saver")
    private let lock = NSLock()
    private var pending: Value?
    private var timerArmed = false
    private let delay: TimeInterval
    private let write: @Sendable (Value) -> Void

    public init(delay: TimeInterval = 2, write: @escaping @Sendable (Value) -> Void) {
        self.delay = delay
        self.write = write
    }

    public func schedule(_ value: Value) {
        lock.lock()
        pending = value
        let arm = !timerArmed
        timerArmed = true
        lock.unlock()
        guard arm else { return }
        queue.asyncAfter(deadline: .now() + delay) { [self] in drain() }
    }

    /// 同步写出尚未落盘的最新值；没有待写内容时什么都不做。
    public func flush() {
        queue.sync { drain() }
    }

    private func drain() {
        lock.lock()
        let value = pending
        pending = nil
        timerArmed = false
        lock.unlock()
        if let value { write(value) }
    }
}
