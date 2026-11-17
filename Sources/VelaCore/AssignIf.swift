#if canImport(Combine)
import Combine
#else
import Foundation

/// Linux 的开源 Swift 没有 Combine。这里只补测试和 `objectWillChange.send()` 用到的那一点；
/// macOS 仍用系统 Combine，这段不参与编译。
public final class ObservableObjectPublisher {
    private var subscribers: [Int: () -> Void] = [:]
    private var next = 0

    public init() {}

    public func send() {
        let current = Array(subscribers.values)
        current.forEach { $0() }
    }

    public func sink(receiveValue: @escaping () -> Void) -> AnyCancellable {
        let id = next
        next += 1
        subscribers[id] = receiveValue
        return AnyCancellable { [weak self] in self?.subscribers[id] = nil }
    }
}

public final class AnyCancellable {
    private var cancelBlock: (() -> Void)?

    public init(_ cancel: @escaping () -> Void) { cancelBlock = cancel }

    public func cancel() {
        cancelBlock?()
        cancelBlock = nil
    }

    deinit { cancel() }
}

public protocol ObservableObject: AnyObject {
    var objectWillChange: ObservableObjectPublisher { get }
}
#endif

/// 只在值变化时写入。
///
/// `@Published` 对任何赋值都发 `objectWillChange`，播放稳态里把同一个房间名、失败标志再写一遍，
/// 观察它的整棵 SwiftUI 树都会失效。这里不接收 `inout`：`inout` 在函数返回时总会写回属性，
/// 等于又赋了一次相同的值。调用方只在返回 true 时自己赋值。
public enum AssignIf {
    @discardableResult
    public static func changed<T: Equatable>(from current: T, to newValue: T, write: (T) -> Void) -> Bool {
        guard current != newValue else { return false }
        write(newValue)
        return true
    }
}

/// 画面真正要看的播放状态。播放器在 `timeControlStatus` 每次回调里都会把就绪房间和失败标志写进 `@Published`，
/// 值经常没变。视图观察这一份：和菜单栏开关一样，只有变了才 `objectWillChange`，稳态下不再整页重绘。
/// 不用 `@Published`，因为那个包装对任何赋值都发通知，挡不住同值写入。
@MainActor
public final class PlaybackFace: ObservableObject {
    #if !canImport(Combine)
    public let objectWillChange = ObservableObjectPublisher()
    #endif
    public private(set) var readyUsername: String?
    public private(set) var playingUsername: String?
    public private(set) var failed = false

    public init() {}

    public func setReady(_ username: String?) {
        AssignIf.changed(from: readyUsername, to: username) { newValue in
            objectWillChange.send()
            readyUsername = newValue
        }
    }

    public func setPlaying(_ username: String?) {
        AssignIf.changed(from: playingUsername, to: username) { newValue in
            objectWillChange.send()
            playingUsername = newValue
        }
    }

    public func setFailed(_ value: Bool) {
        AssignIf.changed(from: failed, to: value) { newValue in
            objectWillChange.send()
            failed = newValue
        }
    }
}
