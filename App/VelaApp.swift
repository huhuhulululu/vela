import AppKit
import SwiftUI

@main
struct VelaApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = StageModel()
    @StateObject private var menuBar = MenuBarVisibility.shared

    var body: some Scene {
        WindowGroup {
            StageRoot(model: model)
                .syncCommitGuard(model.sync)
                .frame(minWidth: 640, minHeight: 360)
        }
        .windowStyle(.hiddenTitleBar)
        .defaultSize(width: 1120, height: 630)
        .windowResizability(.contentMinSize)
        .commands {
            VelaCommands(model: model, menuBar: menuBar)
        }
        // 「设置…」（⌘,）：目前只有跨设备同步一页。
        Settings {
            SyncExperimentPage(hub: model.sync, width: 420)
                .padding(20)
                .preferredColorScheme(.dark)
        }
        MenuBarExtra(isInserted: $menuBar.isShown) {
            MenuBarFavorites(model: model)
        } label: {
            Image("MenuBarIcon")
        }
        .menuBarExtraStyle(.window)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.appearance = NSAppearance(named: .darkAqua)
        NSWindow.allowsAutomaticWindowTabbing = false
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}

struct VelaCommands: Commands {
    @ObservedObject var model: StageModel
    @ObservedObject var menuBar: MenuBarVisibility
    @ObservedObject private var info = RoomInfoPrefs.shared

    var body: some Commands {
        CommandGroup(replacing: .newItem) {}
        CommandMenu("播放") {
            // 无修饰键的等效键：有弹层、在输入时禁用，字符归输入框（规则同键盘监视器，见 `ShortcutGate`）。
            Group {
                Button("下一位") { model.step(1) }
                    .keyboardShortcut(.downArrow, modifiers: [])
                Button("上一位") { model.step(-1) }
                    .keyboardShortcut(.upArrow, modifiers: [])
                Button(model.manuallyPaused ? "继续" : "暂停") { model.togglePause(model.target()) }
                    .keyboardShortcut(" ", modifiers: [])
                Button(model.muted ? "取消静音" : "静音") { model.toggleMute() }
                    .keyboardShortcut("m", modifiers: [])
                Button(model.effectiveFill ? "完整画面" : "铺满画面") { model.toggleFit() }
                    .keyboardShortcut("f", modifiers: [])
                Button(info.menuTitle) { info.toggle() }
                    .keyboardShortcut("i", modifiers: [])
            }
            .disabled(!model.plainKeysEnabled)
            Button("重试当前房间") { model.retryFromMenu() }
                .disabled(!model.ageConfirmed)
            Divider()
            Button(model.currentIsFavorite ? "取消收藏当前房间" : "收藏当前房间") { model.toggleFavorite(model.target()) }
                .keyboardShortcut("d", modifiers: .command)
                .disabled(model.feed.current == nil)
            Button("屏蔽当前房间") { model.blockCurrent(model.target()) }
                .keyboardShortcut("b", modifiers: [.command, .shift])
                .disabled(model.feed.current == nil)
            Button("对当前房间不感兴趣") { model.markNotInterested(model.target()) }
                .keyboardShortcut("n", modifiers: [.command, .shift])
                .disabled(model.feed.current == nil)
            Button("收藏夹…") { model.showCuration = true }
                .keyboardShortcut("f", modifiers: [.command, .shift])
            Button("我的口味…") { model.showTaste = true }
            Toggle("在菜单栏显示", isOn: $menuBar.isShown)
            Divider()
            Button(model.bossHidden ? "显示窗口" : "隐藏窗口并停止解码") { model.toggleBoss() }
                .keyboardShortcut("v", modifiers: [.control, .option, .command])
            Button(model.pinned ? "取消置顶" : "置顶") { model.togglePinned() }
                .keyboardShortcut("p", modifiers: [.command, .shift])
            Button(model.presence.conditionalOpacity ? "关闭离开后淡出" : "开启离开后淡出") { model.toggleConditional() }
                .keyboardShortcut("t", modifiers: [.command, .shift])
        }
    }
}
