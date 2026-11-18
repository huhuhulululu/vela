import AVFoundation
import SwiftUI
import UIKit

/// iOS 播放层：固定 `.resizeAspect`，画面保持原始比例不裁切（裁决 5）；没有 `fill` 参数。
/// 图层与视图背景透明，留边区露出下面的 `SwapBackdrop`。
struct PlayerLayerView: UIViewRepresentable {
    var player: AVPlayer

    func makeUIView(context: Context) -> PlayerUIView {
        let view = PlayerUIView()
        view.playerLayer.player = player
        return view
    }

    func updateUIView(_ view: PlayerUIView, context: Context) {
        // 父视图刷新也会进来。播放器实例没变就不要写 layer。
        if view.playerLayer.player !== player {
            view.playerLayer.player = player
        }
    }
}

final class PlayerUIView: UIView {
    override static var layerClass: AnyClass { AVPlayerLayer.self }

    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        playerLayer.backgroundColor = UIColor.clear.cgColor
        playerLayer.isOpaque = false
        playerLayer.videoGravity = .resizeAspect
    }

    required init?(coder: NSCoder) { fatalError() }
}
