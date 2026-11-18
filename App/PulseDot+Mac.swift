import AppKit
import SwiftUI

/// 脉冲圆点的 macOS 图层实现。iOS 版同名类型在 `iOS/PulseDot+Phone.swift`。
struct PulseDotMark: NSViewRepresentable {
    var pulsing: Bool

    func makeNSView(context: Context) -> PulseDotView {
        let view = PulseDotView()
        view.setPulsing(pulsing)
        return view
    }

    func updateNSView(_ view: PulseDotView, context: Context) {
        // 父视图刷新也会进来。脉冲开关没变就不要碰图层。
        view.setPulsing(pulsing)
    }
}

final class PulseDotView: NSView {
    /// 与 `Theme.live` 同一 sRGB。
    private static let live = NSColor(srgbRed: 0.96, green: 0.27, blue: 0.24, alpha: 1)
    private let dot = CALayer()
    private var pulsing = false

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        layerContentsRedrawPolicy = .never
        layer?.backgroundColor = nil
        dot.backgroundColor = Self.live.cgColor
        layer?.addSublayer(dot)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    func setPulsing(_ on: Bool) {
        guard on != pulsing else { return }
        pulsing = on
        dot.removeAnimation(forKey: "pulse")
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        if on {
            dot.opacity = 0.4
            let animation = CABasicAnimation(keyPath: "opacity")
            animation.fromValue = 0.4
            animation.toValue = 1
            animation.duration = 1.05
            animation.autoreverses = true
            animation.repeatCount = .infinity
            animation.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            dot.add(animation, forKey: "pulse")
        } else {
            dot.opacity = 1
        }
        CATransaction.commit()
    }

    override func layout() {
        super.layout()
        let side = min(bounds.width, bounds.height)
        guard side > 0 else { return }
        let next = CGRect(
            x: (bounds.width - side) / 2,
            y: (bounds.height - side) / 2,
            width: side,
            height: side
        )
        guard dot.frame != next else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        dot.cornerRadius = side / 2
        dot.frame = next
        CATransaction.commit()
    }
}
