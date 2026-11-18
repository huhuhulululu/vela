# iOS 依赖清单（T8 产出，供 T9 按清单拆分）

> 来源：把附录 A 里要共享到 `Shared/` 的文件（第一轮，24 个，含 `StageModel` 全部扩展）与「事实 4、9」涉及的界面文件（第二轮，另加 11 个）临时加入 `VelaPhone` 目标，用 `-sdk iphonesimulator` 实际编译，再对函数体做静态扫描补全。临时加入已撤销，目标当前只编译 VelaCore、`Shared/Relay` 与占位入口。
> 方法限制：Swift 在「找不到模块」「找不到类型」这类声明层错误上就会停下，不再检查函数体，所以单靠编译器看不全。下面「编译器报错」是真实报错，「静态扫描」是对 AppKit 符号、`NS*` 类型与平台专属类型的 grep，两者合起来作为清单。
> 编译顺序的两个事实：
> - 第一轮最先报的是 `import AppKit`（`SiteSidecar`、`StageModel`）与 `import Carbon.HIToolbox`（`StageModel`）：iOS SDK 里没有这两个模块，整个目标直接编译失败。
> - 把这些 import 临时去掉后，第一轮 20 条、第二轮 81 条声明层报错（去重后），全部归入下表。

## 1. 按文件的依赖与处理方式

处理方式代号：**留** = 留 macOS（`App/`）；**适配** = 同名符号按目标分文件（`App/*+Mac.swift` 与 `iOS/*+Phone.swift`，§1.2）；**抽出** = 从 macOS 文件里拆出共享部分；**改接口** = 模型层接口调整；**直移** = 清理后直接 `git mv` 到 `Shared/`。

### 1.1 第一轮：计划共享的文件（24 个）

| 文件 | 依赖的 macOS 专属符号（位置） | 定义在 | 处理方式 |
|---|---|---|---|
| `StageModel.swift` | `import AppKit`、`import Carbon.HIToolbox`（:2-3，**编译器报错**） | 系统 | 去掉两处 import，全部下沉到 `PlatformHooks` |
| | `let bossKey = GlobalHotKey()`、`var activity: SystemActivity?`（:147-148，**编译器报错**，找不到类型）；`bootstrap()` 里的 `kVK_ANSI_V` 与 `bossKey.register`（:321-331）、`SystemActivity { ... }`（:328） | `App/BossKey.swift` | 适配：存储属性移进 `PlatformHooks+Mac`，StageModel 只持有 `let platform = PlatformHooks()` |
| | `NSApplication.didUnhide/didHide/willTerminateNotification` 三个订阅（:242、:252、:261）及三个观察者存储属性 `terminationObserver`、`unhideObserver`、`hideObserver`（:133-135） | AppKit | 适配：订阅与存储属性整体移到 macOS 版 `PlatformHooks`，iOS 版按 §2 写自己的值 |
| | `NSApp.terminate`（:365）、`NSApp.hide/unhide/activate`（:626-629）、`NSApp.isHidden`（:790） | AppKit | 适配：改为 `platform.terminate()`、`hide()`、`unhide()`、`revealMain()`、`appHidden` |
| | `fitFill`、`portraitFit`、`effectiveFill`（:15-17、:218-221、:569-588） | 本文件、`SettingsStore.fitFill`、VelaCore `PortraitFit` | 改接口：`effectiveFill` 改为 `platform.allowsCrop && ...`（计划 T9）；`fitFill` 状态与设置键保留 |
| | `PlayerController` 找不到（:70，**编译器报错**） | `App/PlayerController.swift` | 非真实依赖：模块发射失败的连带报错，PlayerController 本身无报错，见下 |
| `StageModel+Favorites.swift` | `import AppKit`；`NSApp.unhide/activate/windows`、`NSPanel`（:168-170，`revealMain`）；`NSSavePanel`（:197）、`NSOpenPanel`（:216） | AppKit | 适配：`revealMain` 进 `PlatformHooks`；导出导入拆成模型的 `exportFavoritesData()` / `importFavorites(_:)`，文件面板放 `App/StageModel+Mac.swift`，iOS 用 `.fileExporter` / `.fileImporter`；`CurationPanel` 走回调 |
| `StageModel+Catalog.swift`、`+Curation`、`+Dwell`、`+Playback`、`+Quality` | 只有 `import AppKit`，函数体内没有任何 AppKit 符号（静态扫描为 0） | | 直移：删掉多余的 import。`+Playback.swift:370` 用 `RoomThumbnailCache.shared.prefetch`，见 `RoomCanvas` 一行 |
| `StageModel+Taste.swift` | 无 | | 直移 |
| `PlayerController.swift` | 无。只有 `NSKeyValueObservation`（:28、:29、:68）、`NSError`、`NSUnderlyingErrorKey`（:609-612），均属 Foundation | | 直移。T7 之后与 `Shared/Relay` 同时可用，无需改 |
| `SiteSidecar.swift` | `import AppKit`；`NSWindow`（:14、:24，**编译器报错**：持有宿主窗口 `hold(window:)`）；`NSRect`（:180，屏外 WKWebView 的 frame，CGRect 即可）；`NSObject`、`NSNumber`（Foundation，无碍） | 系统 | 改接口：按计划注入 `beforeLoad` 与 `SidecarHost`（宿主改为协议）；`NSRect` 换 `CGRect`；macOS 版 `SidecarHost` 持 `NSWindow`，iOS 版持 `UIWindow` 或不持 |
| `URLSessionHTTPClient.swift` | 无 | | 直移 |
| `SettingsStore.swift`、`PreferenceHealth.swift`、`RoomInfoPrefs.swift`、`LegacyLoginCleanup.swift` | 无 | | 直移（`SettingsStore.fitFill` 保留） |
| `Theme.swift` | 无 | | 直移 |
| `RoomCaption.swift` | `import AppKit`；`PulseDotMark: NSViewRepresentable`、`PulseDotView: NSView`、`NSColor`、`NSRect`、`NSPoint`、`NSCoder`、`hitTest` 重写（:115-147，**编译器报错** 9 条）；调用点 `:110` | 本文件 | 适配：`PulseDotMark` 移出，macOS 与 iOS 各写一份（iOS 版用 `UIViewRepresentable` + `CALayer`）；其余留共享 |
| `ChallengeSheet.swift` | `import AppKit`；`SiteWebView: NSViewRepresentable`（:52）、`makeNSView`、`updateNSView`、`NSView`、`NSRect`（:52-112，**编译器报错** 6 条）；`ChampagneButton`（:26，定义在 `AgeGateView.swift:51`） | 本文件 / `AgeGateView.swift` | 适配：`SiteWebView` 两个目标各写一份（iOS 版 `UIViewRepresentable`），ChallengeSheet 的内容部分共享；`ChampagneButton` 见下「共享按钮样式」 |
| `TastePanel.swift`（:98、:228）、`TasteParts.swift`（:57）、`FavoritesPanel.swift`（:81、:109、:199、:230）、`FavoritesRows.swift`（:64、:77、:126、:162、:192） | 用 `QuietButtonStyle`（共 12 处，另有 `ChromeControls`、`ChromeView` 等内部使用）；`FavoritesRows.swift:81、:132` 用 `.onHover`（SwiftUI，iOS 上无害，指针不存在时不触发） | `App/ChromeControls.swift:4` | 抽出：`QuietButtonStyle` 与 `ChampagneButton` 移到 `Shared/Stage/ButtonStyles.swift`（计划已列）；这四个文件随后直移 |
| `TasteView.swift`、`CurationPanel.swift` | 无 | | 直移 |

### 1.2 第二轮：事实 4、9 涉及的 macOS 界面文件（仅作依赖记录，计划里留在 `App/`）

| 文件 | 依赖 | 处理方式 |
|---|---|---|
| `ClickableRegion.swift` | `NSViewRepresentable`、`NSView`、`NSPoint`（:6-15，**编译器报错** 7 条）；`.clickableRegion()`（:28-30） | 留。共享代码通过 `platformClickRegion()` 取用：macOS 版转发 `clickableRegion()`，iOS 版原样返回视图 |
| `ChromeControls.swift` | `QuietButtonStyle`（:4）调用 `.clickableRegion()`（:7）；`WindowButtons`（:85）用 NSApp 窗口控件 | 抽出 `QuietButtonStyle`；`WindowButtons` 留 |
| `AgeGateView.swift` | `ChampagneButton`（:51）调用 `.clickableRegion()`（:61）；`DragStrip`（`WindowBridge.swift:378`）、`WindowButtons`（`ChromeControls.swift:85`）（计划事实 4：`:9`、`:12`） | 抽出 `ChampagneButton` 与共享内容 `AgeGateContent`；macOS 外壳带 `DragStrip`、`WindowButtons`；iOS 另做手机外壳 |
| `WindowBridge.swift` | `NSEvent`、`NSPoint`、`NSWindow`、`NSView`、`NSViewRepresentable`（共 43 条编译器报错：窗口拖动、指针轮询、`DragStrip`） | 留 |
| `BossKey.swift` | `EventHandlerRef`、`EventHotKeyRef`（Carbon，:5-6，**编译器报错**）；`GlobalHotKey`、`SystemActivity` | 留；被 `PlatformHooks+Mac` 持有 |
| `HeartButton.swift` | `NSViewRepresentable`、`NSView`、`NSEvent`（:45-81，**编译器报错** 9 条） | 留（iOS 另做心形按钮，`PhoneChrome`） |
| `MenuBarFavorites.swift` | `NSImage`（:106）、`NSViewRepresentable`（:132-147，**编译器报错** 6 条） | 留（菜单栏本就不做） |
| `RoomCanvas.swift` | 事实 9：`PlayerLayerView(player:fill:)` 是 `NSViewRepresentable`（:241-280，**编译器报错**）；`:97` 传入 `model.effectiveFill`；`SwapBackdrop`（`:178`，**私有结构体**，含 `NSImage` 状态 `:181/:184`）；`RoomThumbnailCache`（`:8-60`，`NSCache<NSURL, NSImage>`，`NSImage(`，共 11 条 NSImage 报错） | 抽出 + 适配，见 §2 |
| `StageView.swift`、`ChromeView.swift`、`PagerView.swift` | 间接使用上面各项（静态扫描 `StageView` 1 处、`ChromeView` 2 处；未单独报错） | 留，iOS 另做 `PhoneStageView`、`PhoneChrome`、`LanePager` |

## 2. `RoomCanvas.swift` 的拆分建议（事实 9 与计划附录 A 的出入）

附录 A 把 `RoomThumbnailCache` 与 `SwapBackdrop` 列入 `Shared/Stage`，但它们现在都在 `RoomCanvas.swift` 里，并且依赖 `NSImage`：

1. **`RoomThumbnailCache`**：`StageModel+Playback.swift:370` 要用它的 `prefetch`，所以共享层必须有它。建议改接口：缓存里存 `CGImage`（`CGImageSource` 解码已经有，`decodeThumbnail`），或引入 `typealias PlatformImage`（macOS 为 `NSImage`，iOS 为 `UIImage`）并在两个 `+Mac`、`+Phone` 文件里各定义。倾向 `CGImage`，因为展示端 `Image(decorative: cgImage, scale:)` 两个平台通用，不再需要类型别名。
2. **`SwapBackdrop`**：改成按 `CGImage` 取图的共享视图，从 `RoomCanvas.swift` 拆出到 `Shared/Stage/SwapBackdrop.swift`（附录 A 已列）。它在播放层下面，播放层背景透明时留边区自然露出模糊缩略图，符合冻结区块第 5 条。
3. **`PlayerLayerView`**：两个目标各写一份；iOS 版不接收 `fill` 参数，固定 `.resizeAspect`。`RoomCanvas:97` 的 `fill: model.effectiveFill` 留在 macOS 版调用处。
4. `MenuBarFavorites.swift:106、:123` 也用 `RoomThumbnailCache.image(for:)` 取 `NSImage`：改 `CGImage` 后留在 macOS 的这一处用 `NSImage(cgImage:size:)` 包一下。

## 3. 摘要（按处理方式分组）

第一轮 24 个计划共享文件：

| 处理方式 | 个数 | 文件 |
|---|---|---|
| 直移（无依赖） | 10 | `PlayerController`、`URLSessionHTTPClient`、`StageModel+Taste`、`SettingsStore`、`PreferenceHealth`、`RoomInfoPrefs`、`LegacyLoginCleanup`、`Theme`、`TasteView`、`CurationPanel` |
| 直移（只删多余 `import AppKit`） | 5 | `StageModel+Catalog`、`+Curation`、`+Dwell`、`+Playback`、`+Quality` |
| 直移（先抽出 `ButtonStyles.swift`） | 4 | `TastePanel`、`TasteParts`、`FavoritesPanel`、`FavoritesRows` |
| 按目标分文件适配 | 5 | `StageModel`（`PlatformHooks`）、`StageModel+Favorites`（`revealMain` 与文件面板）、`RoomCaption`（`PulseDotMark`）、`ChallengeSheet`（`SiteWebView`）、`SiteSidecar`（`SidecarHost`，同时改接口） |

合计 10 + 5 + 4 + 5 = 24。

| 处理方式 | 个数 | 关键项 |
|---|---|---|
| 抽出共享 | 4 项 | `QuietButtonStyle`、`ChampagneButton`（→ `ButtonStyles.swift`）、`AgeGateContent`、`SwapBackdrop` |
| 改接口 | 3 项 | `RoomThumbnailCache`（`NSImage` → `CGImage`）、`SiteSidecar`（注入 `beforeLoad` 与 `SidecarHost`）、`StageModel.effectiveFill`（`platform.allowsCrop`） |
| 留 macOS | 8 个文件 | `WindowBridge`、`BossKey`、`ClickableRegion`、`HeartButton`、`MenuBarFavorites`、`ChromeView`、`StageView`、`PagerView`，加 `ChromeControls` 中的 `WindowButtons` 与 `RoomCanvas` 的 `PlayerLayerView` |

事实核对：事实 3（`StageModel` 的 AppKit、Carbon、`NSApp`、三个通知、`NSSavePanel`/`NSOpenPanel`）见 §1.1 的 `StageModel` 与 `StageModel+Favorites` 行，与计划一致，另外发现 `NSPanel`（`+Favorites.swift:170`）与 `SiteSidecar` 的 `NSWindow` 宿主；事实 4（`ChampagneButton`、`QuietButtonStyle`、`.clickableRegion()`、`DragStrip`、`WindowButtons`）见 §1.2；事实 9（`PlayerLayerView(fill:)`、`effectiveFill`、`SwapBackdrop`）见 §2；与计划的差异是 `RoomThumbnailCache`、`SwapBackdrop` 现在都私有地住在 `RoomCanvas.swift`，且 `StageModel+Playback` 已经依赖前者。
