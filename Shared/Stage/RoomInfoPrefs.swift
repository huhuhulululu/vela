import SwiftUI

/// 房间信息块的显示模式，只属于视图层：菜单、键盘与画面共用同一份，并写入设置。
@MainActor
final class RoomInfoPrefs: ObservableObject {
    static let shared = RoomInfoPrefs()

    private let store: SettingsStore
    @Published private(set) var alwaysShow: Bool

    init(store: SettingsStore = SettingsStore()) {
        self.store = store
        alwaysShow = store.infoAlwaysOn
    }

    /// 用户切换后通知同步（开启时写 `settings.infoAlwaysOn` 寄存器）。
    var onUserToggle: ((Bool) -> Void)?

    func toggle() {
        alwaysShow.toggle()
        store.infoAlwaysOn = alwaysShow
        onUserToggle?(alwaysShow)
    }

    /// 同步投影写入：不再回写同步寄存器；同值不触发界面刷新。
    func applySynced(_ value: Bool) {
        if alwaysShow != value { alwaysShow = value }
        if !store.hasExplicitValue(forKey: "infoAlwaysOn") || store.infoAlwaysOn != value { store.infoAlwaysOn = value }
    }

    var menuTitle: String { alwaysShow ? "房间信息随控制栏隐藏" : "始终显示房间信息" }
}
