import AppKit
import SwiftUI

struct WindowBridge: NSViewRepresentable {
    @ObservedObject var model: StageModel

    func makeNSView(context: Context) -> BridgeView {
        let view = BridgeView()
        view.model = model
        return view
    }

    func updateNSView(_ view: BridgeView, context: Context) {
        view.model = model
        view.apply()
    }
}

final class BridgeView: NSView {
    weak var model: StageModel?
    private var monitors: [Any] = []
    private var poll: Timer?
    private var outsideWork: DispatchWorkItem?
    private var wheel = WheelInterpreter()
    private var lastTarget: CGFloat = -1
    private var lastPoint = NSPoint.zero
    private var hasLastPoint = false
    private var didSeedPointer = false
    private var occlusionObserver: NSObjectProtocol?
    private var resizeObserver: NSObjectProtocol?
    private var configured = false
    private var windowVisible = true
    private var lastMoveNote = Date.distantPast
    /// 按下后还没确定是点击还是拖动：移动超过阈值才开始拖窗，单击照常交给 SwiftUI 按钮。
    private var pendingDrag: PendingDrag?
    private struct PendingDrag {
        let event: NSEvent
        let origin: NSPoint
    }
    /// 按下后移动多少点才算拖窗（小于它仍是点击）。
    private static let dragThreshold: CGFloat = 3.5

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        guard window != nil, !configured else { return }
        configured = true
        configureWindow()
        installMonitors()
        updatePolling()
    }

    deinit {
        monitors.forEach { NSEvent.removeMonitor($0) }
        poll?.invalidate()
        [occlusionObserver, resizeObserver].compactMap { $0 }.forEach(NotificationCenter.default.removeObserver)
    }

    func apply() {
        guard let window, let model else { return }
        model.sidecar.hold(window: window)
        let scale = Double(window.backingScaleFactor)
        if model.screenScale != scale { model.screenScale = scale }
        let presence = model.resolvedPresence()
        // SwiftUI 每次刷新都会进 updateNSView。层级、集合行为和穿透写同值也会让窗口标脏。
        let level: NSWindow.Level = model.pinned ? .floating : .normal
        if window.level != level { window.level = level }
        let behavior: NSWindow.CollectionBehavior = model.pinned
            ? [.canJoinAllSpaces, .fullScreenAuxiliary]
            : [.managed, .fullScreenAuxiliary]
        if window.collectionBehavior != behavior { window.collectionBehavior = behavior }
        let ignores = presence.ignoresMouseEvents
        if window.ignoresMouseEvents != ignores { window.ignoresMouseEvents = ignores }
        let target = CGFloat(presence.opacity)
        if abs(lastTarget - target) > 0.01 {
            lastTarget = target
            // 减弱动态效果：窗口淡入淡出直接到位，不做渐变动画。
            if NSWorkspace.shared.accessibilityDisplayShouldReduceMotion {
                window.alphaValue = target
            } else {
                NSAnimationContext.runAnimationGroup { context in
                    context.duration = 0.34
                    context.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
                    window.animator().alphaValue = target
                }
            }
        }
        model.refreshPointerPolling = { [weak self] in self?.updatePolling() }
        updatePolling()
    }

    /// 穿透时收不到鼠标事件，所以「离开后淡出」开着时必须轮询指针位置；窗口不可见时没有必要。
    /// 淡出关闭时窗口始终收得到鼠标事件，改用 mouseMoved 事件，不再轮询。
    /// 休眠、老板键、App 隐藏时也不轮询。手动暂停且窗口可见时仍轮询，指针移入控制栏才能淡入。
    private func updatePolling() {
        guard let model else { return }
        let conditional = model.resolvedPresence().conditionalOpacity
        window?.acceptsMouseMovedEvents = !conditional
        let shouldPoll = PointerPolling.needed(
            conditionalOpacity: conditional,
            windowVisible: windowVisible,
            hasWindow: window != nil,
            bossHidden: model.bossHidden,
            systemInactive: model.systemInactive,
            appHidden: NSApp.isHidden
        )
        if shouldPoll {
            guard poll == nil else { return }
            let timer = Timer(timeInterval: 0.4, repeats: true) { [weak self] _ in
                self?.evaluatePointer()
            }
            timer.tolerance = 0.2
            RunLoop.main.add(timer, forMode: .common)
            poll = timer
            // 恢复轮询时立刻对一次位置，避免停轮询期间指针进出造成状态陈旧。
            DispatchQueue.main.async { [weak self] in self?.evaluatePointer() }
        } else if let timer = poll {
            timer.invalidate()
            poll = nil
            outsideWork?.cancel()
            outsideWork = nil
        }
    }

    private func configureWindow() {
        guard let window else { return }
        window.title = "Vela"
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.styleMask.insert(.fullSizeContentView)
        window.isMovableByWindowBackground = false
        window.isOpaque = false
        window.backgroundColor = .clear
        // 窗口底色必须始终透明：自定义圆角（22pt）与系统窗口圆角不同，不透明的黑底会在两条弧线之间露出黑色楔形。
        window.hasShadow = true
        window.contentAspectRatio = NSSize(width: 16, height: 9)
        window.minSize = NSSize(width: 640, height: 360)
        if window.frame.width < window.frame.height * 1.2 {
            window.setContentSize(NSSize(width: 1120, height: 630))
            window.center()
        }
        // 系统红绿灯全部隐藏，关闭与最小化由画面内的顶栏提供；⌘W、⌘M 仍走菜单。
        [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton].forEach {
            window.standardWindowButton($0)?.isHidden = true
        }
        removeTitlebarSafeArea()
        window.contentView?.wantsLayer = true
        window.contentView?.layer?.cornerRadius = 22
        window.contentView?.layer?.masksToBounds = true
        window.contentView?.layer?.backgroundColor = NSColor.clear.cgColor
        window.invalidateShadow()
        resizeObserver = NotificationCenter.default.addObserver(
            forName: NSWindow.didResizeNotification,
            object: window,
            queue: .main
        ) { [weak self] _ in
            self?.removeTitlebarSafeArea()
            self?.window?.invalidateShadow()
        }
        occlusionObserver = NotificationCenter.default.addObserver(
            forName: NSWindow.didChangeOcclusionStateNotification,
            object: window,
            queue: .main
        ) { [weak self] note in
            guard let window = note.object as? NSWindow else { return }
            let visible = window.occlusionState.contains(.visible)
            self?.windowVisible = visible
            self?.updatePolling()
            MainActor.assumeIsolated { self?.model?.setWindowVisible(visible) }
        }
    }

    /// 隐藏标题栏后系统仍给内容视图留 32pt 顶部安全区，SwiftUI 宿主整体被推下去，
    /// 窗口顶端露出一条黑底、底部被裁掉。用负的附加安全区抵消，让画面真正铺到最顶端。
    private func removeTitlebarSafeArea() {
        guard let content = window?.contentView else { return }
        let base = content.safeAreaInsets.top - content.additionalSafeAreaInsets.top
        let wanted = NSEdgeInsets(top: -base, left: 0, bottom: 0, right: 0)
        guard content.additionalSafeAreaInsets.top != wanted.top else { return }
        content.additionalSafeAreaInsets = wanted
    }

    /// 淡出关闭时没有轮询，指针离开窗口要靠跟踪区域及时通知，顶栏才能收起。
    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(
            rect: .zero,
            options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect],
            owner: self,
            userInfo: nil
        ))
    }

    override func mouseExited(with event: NSEvent) {
        guard let model, !model.resolvedPresence().conditionalOpacity else { return }
        model.notePointer(inside: false, moved: false)
    }

    private func installMonitors() {
        if let moves = NSEvent.addLocalMonitorForEvents(matching: .mouseMoved, handler: { [weak self] event in
            self?.handleMove(event)
            return event
        }) {
            monitors.append(moves)
        }
        if let scroll = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel, handler: { [weak self] event in
            guard let self, self.handleScroll(event) else { return event }
            return nil
        }) {
            monitors.append(scroll)
        }
        if let keys = NSEvent.addLocalMonitorForEvents(matching: .keyDown, handler: { [weak self] event in
            guard let self, self.handleKey(event) else { return event }
            return nil
        }) {
            monitors.append(keys)
        }
        let drag: [(NSEvent.EventTypeMask, (NSEvent) -> Void)] = [
            (.leftMouseDown, { [weak self] in self?.notePress($0) }),
            (.leftMouseDragged, { [weak self] in self?.noteDrag($0) }),
            (.leftMouseUp, { [weak self] _ in self?.pendingDrag = nil }),
        ]
        for (mask, handler) in drag {
            if let monitor = NSEvent.addLocalMonitorForEvents(matching: mask, handler: { event in
                handler(event)
                return event
            }) {
                monitors.append(monitor)
            }
        }
    }

    /// 画面主体按住左键拖动移动窗口；控件、自定义样式按钮与顶栏按钮不走这里（`DragSurface` 仍负责顶栏空白）。
    /// 按下时只记下起点，事件照常送达（自定义样式的按钮命中的是 SwiftUI 宿主视图，不能在按下时就拖窗）。
    private func notePress(_ event: NSEvent) {
        pendingDrag = nil
        guard event.buttonNumber == 0, let window, event.window === window, let model,
              model.ageConfirmed, !model.isInteracting else { return }
        let point = event.locationInWindow
        guard shouldDragWindow(at: point, in: window) else { return }
        pendingDrag = PendingDrag(event: event, origin: point)
    }

    /// 移动超过阈值才开始拖窗；按钮上的点击没有位移，不会走到这里。
    private func noteDrag(_ event: NSEvent) {
        guard let pending = pendingDrag, let window, event.window === window else { return }
        let point = event.locationInWindow
        guard hypot(point.x - pending.origin.x, point.y - pending.origin.y) > Self.dragThreshold else { return }
        pendingDrag = nil
        window.performDrag(with: pending.event)
    }

    /// 命中判定：控件、拖动条不走这里；SwiftUI 自定义样式的按钮命中的是宿主视图，
    /// 靠按钮样式登记的 `ClickableRegionView` 区域识别（凡是可点的地方按下都不拖窗）。
    private func shouldDragWindow(at point: NSPoint, in window: NSWindow) -> Bool {
        guard let content = window.contentView, let hit = content.hitTest(point) else { return false }
        var kind = DragHit.background
        var view: NSView? = hit
        walk: while let current = view {
            switch current {
            case is NSControl, is NSTextView, is PressView:
                kind = .control
                break walk
            case is DragSurface:
                kind = .dragSurface
                break walk
            case is PlayerNSView:
                kind = .video
                break walk
            default:
                view = current.superview
            }
        }
        return WindowDragPolicy.canStartDrag(
            hit: kind, point: point, clickable: ClickableRegionView.regions(in: content),
            contentHeight: content.bounds.height, barHeight: CGFloat(Theme.barHeight)
        )
    }

    private func handleMove(_ event: NSEvent) {
        guard let window, event.window === window, let model,
              !model.resolvedPresence().conditionalOpacity else { return }
        let now = Date()
        guard now.timeIntervalSince(lastMoveNote) > 0.25 else { return }
        lastMoveNote = now
        model.notePointer(inside: true, moved: true)
    }

    private func evaluatePointer() {
        guard let window, let model else { return }
        let location = NSEvent.mouseLocation
        let inside = window.frame.contains(location)
        let moved = hasLastPoint && hypot(location.x - lastPoint.x, location.y - lastPoint.y) > 1.5
        lastPoint = location
        hasLastPoint = true
        if !didSeedPointer {
            didSeedPointer = true
            model.notePointer(inside: inside, moved: false)
            return
        }
        if inside {
            outsideWork?.cancel()
            outsideWork = nil
            model.notePointer(inside: true, moved: moved)
        } else if model.presence.pointerInside && outsideWork == nil {
            let work = DispatchWorkItem { [weak self] in
                self?.outsideWork = nil
                self?.model?.notePointer(inside: false, moved: false)
            }
            outsideWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.42, execute: work)
        }
    }

    private func handleScroll(_ event: NSEvent) -> Bool {
        guard let window, let model, model.ageConfirmed, !model.isInteracting else { return false }
        guard window.frame.contains(NSEvent.mouseLocation) else { return false }
        if window.firstResponder is NSTextView { return false }
        let sample = WheelSample(
            deltaY: Double(event.scrollingDeltaY),
            began: event.phase.contains(.began),
            ended: event.phase.contains(.ended) || event.phase.contains(.cancelled),
            momentum: !event.momentumPhase.isEmpty,
            precise: event.hasPreciseScrollingDeltas,
            phaseNone: event.phase.isEmpty && event.momentumPhase.isEmpty
        )
        guard let delta = wheel.consume(sample, now: Date()) else { return false }
        model.step(delta)
        return true
    }

    private func handleKey(_ event: NSEvent) -> Bool {
        guard let window, let model, window.isKeyWindow, model.ageConfirmed else { return false }
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        // 文本编辑中（NSTextView 与文本框的 field editor 都是 NSText）：字符归输入框，只认 Esc 退出编辑。
        let editing = window.firstResponder is NSText
        if editing {
            if event.keyCode == 53 {
                window.makeFirstResponder(nil)
                return true
            }
            return false
        }
        if flags.contains(.command) {
            if event.keyCode == 126 { model.shiftVolume(0.05); return true }
            if event.keyCode == 125 { model.shiftVolume(-0.05); return true }
            if flags.contains(.shift) && event.keyCode == 35 { model.togglePinned(); return true }
            if flags.contains(.shift) && event.keyCode == 17 { model.toggleConditional(); return true }
            return false
        }
        if event.keyCode == 53 {
            model.dismissSurfaces()
            return true
        }
        // 无修饰键快捷键与「播放」菜单的等效键共用 `ShortcutGate`：有弹层或在输入时一律不触发。
        guard ShortcutGate.allowsPlainKeys(ageConfirmed: model.ageConfirmed, interacting: model.isInteracting, editingText: editing) else {
            return false
        }
        switch event.keyCode {
        case 125: model.step(1); return true
        case 126: model.step(-1); return true
        case 49: model.togglePause(model.target()); return true
        default: break
        }
        switch event.charactersIgnoringModifiers?.lowercased() {
        case "j": model.step(1); return true
        case "k": model.step(-1); return true
        case "m": model.toggleMute(); return true
        case "f": model.toggleFit(); return true
        case "i": RoomInfoPrefs.shared.toggle(); return true
        default: return false
        }
    }
}

struct DragStrip: NSViewRepresentable {
    func makeNSView(context: Context) -> DragSurface { DragSurface() }
    func updateNSView(_ nsView: DragSurface, context: Context) {}
}

final class DragSurface: NSView {
    override var mouseDownCanMoveWindow: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func mouseDown(with event: NSEvent) {
        window?.performDrag(with: event)
    }
}
