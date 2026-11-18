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

    func toggle() {
        alwaysShow.toggle()
        store.infoAlwaysOn = alwaysShow
    }

    var menuTitle: String { alwaysShow ? "房间信息随控制栏隐藏" : "始终显示房间信息" }
}
