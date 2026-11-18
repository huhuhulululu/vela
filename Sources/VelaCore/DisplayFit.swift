import Foundation

/// 按原始比例把画面放进窗格（不裁切），并校验实际显示矩形有没有被裁切。
public enum DisplayFit {
    /// 宽高比允许的相对误差。正好落在这个比例上算未裁切。
    public static let aspectTolerance: CGFloat = 0.01

    /// 把 `presentation` 按比例居中放进 `bounds`。限制的那条边与窗格对齐，另一条边留白。
    public static func fitted(presentation: CGSize, bounds: CGRect) -> CGRect {
        guard presentation.width > 0, presentation.height > 0,
              bounds.width > 0, bounds.height > 0,
              finite(presentation.width), finite(presentation.height),
              finite(bounds.width), finite(bounds.height) else {
            return .zero
        }
        let widthScale = bounds.width / presentation.width
        let heightScale = bounds.height / presentation.height
        let fittedWidth: CGFloat
        let fittedHeight: CGFloat
        if widthScale <= heightScale {
            fittedWidth = bounds.width
            fittedHeight = presentation.height * widthScale
        } else {
            fittedHeight = bounds.height
            fittedWidth = presentation.width * heightScale
        }
        let x = bounds.minX + (bounds.width - fittedWidth) / 2
        let y = bounds.minY + (bounds.height - fittedHeight) / 2
        return CGRect(x: x, y: y, width: fittedWidth, height: fittedHeight)
    }

    /// `videoRect` 完全落在 `bounds` 内，且宽高比与 `presentation` 相差不超过 1%。
    public static func isUncropped(videoRect: CGRect, bounds: CGRect, presentation: CGSize) -> Bool {
        guard videoRect.width > 0, videoRect.height > 0,
              presentation.width > 0, presentation.height > 0,
              finite(videoRect.width), finite(videoRect.height),
              finite(presentation.width), finite(presentation.height) else {
            return false
        }
        guard contains(bounds, videoRect) else { return false }
        let presented = presentation.width / presentation.height
        guard presented > 0, finite(presented) else { return false }
        let actual = videoRect.width / videoRect.height
        guard finite(actual) else { return false }
        let delta = abs(actual - presented) / presented
        // 正好 1% 时浮点误差可能略大于 0.01，仍算在容差内。
        return delta <= aspectTolerance || abs(delta - aspectTolerance) < 0.000_000_001
    }

    private static func contains(_ bounds: CGRect, _ rect: CGRect) -> Bool {
        let slack: CGFloat = 0.01
        return rect.minX >= bounds.minX - slack
            && rect.minY >= bounds.minY - slack
            && rect.maxX <= bounds.maxX + slack
            && rect.maxY <= bounds.maxY + slack
    }

    private static func finite(_ value: CGFloat) -> Bool {
        value.isFinite
    }
}
