#if canImport(Combine)
import Combine
#endif

/// 菜单栏图标是否显示：「播放」菜单开关与 `MenuBarExtra(isInserted:)` 共用的一份状态，改动写进设置。
///
/// `MenuBarExtra` 每次更新场景都会经 KVO 把状态栏项当前的可见性写回 `isInserted` 绑定，值没变也写。
/// `@Published` 对任何写入都发 `objectWillChange`，于是「写回 → 场景失效 → 再次更新 → 再写回」在视图更新期间无限循环
/// （主线程占满、`Publishing changes from within view updates` 每帧一对）。所以相同值的写入必须是空操作，
/// 只有真正变化才通知与保存。
@MainActor
public final class MenuBarVisibility: ObservableObject {
    #if !canImport(Combine)
    public let objectWillChange = ObservableObjectPublisher()
    #endif
    private let save: (Bool) -> Void
    private var stored: Bool

    public init(initial: Bool, save: @escaping (Bool) -> Void) {
        stored = initial
        self.save = save
    }

    public var isShown: Bool {
        get { stored }
        set {
            guard newValue != stored else { return }
            objectWillChange.send()
            stored = newValue
            save(newValue)
        }
    }
}
