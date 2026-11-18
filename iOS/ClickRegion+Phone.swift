import SwiftUI

extension View {
    /// iOS 没有拖窗判定，不需要登记可点区域：原样返回。
    func platformClickRegion() -> some View { self }
}
