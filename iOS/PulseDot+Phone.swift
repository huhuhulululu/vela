import SwiftUI
import UIKit

/// 脉冲圆点的 iOS 图层实现：与 macOS 版外观一致（1.05 秒 ease-in-out，不透明度 0.4 到 1 往复），不接收触摸。
struct PulseDotMark: UIViewRepresentable {
    var pulsing: Bool

    func makeUIView(context: Context) -> PulseDotView {
        let view = PulseDotView()
        view.setPulsing(pulsing)
        return view
    }

    func updateUIView(_ view: PulseDotView, context: Context) {
        view.setPulsing(pulsing)
    }
}

final class PulseDotView: UIView {
    /// 与 `Theme.live` 同一 sRGB。
    private static let live = UIColor(red: 0.96, green: 0.27, blue: 0.24, alpha: 1)
    private let dot = CALayer()
    private var pulsing = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        dot.backgroundColor = Self.live.cgColor
        layer.addSublayer(dot)
    }

    required init?(coder: NSCoder) { fatalError() }

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

    override func layoutSubviews() {
        super.layoutSubviews()
        let side = min(bounds.width, bounds.height)
        guard side > 0 else { return }
        let next = CGRect(x: (bounds.width - side) / 2, y: (bounds.height - side) / 2, width: side, height: side)
        guard dot.frame != next else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        dot.cornerRadius = side / 2
        dot.frame = next
        CATransaction.commit()
    }
}
