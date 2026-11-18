import SwiftUI

/// 一路的垂直拖动分页：只渲染上一页、当前页、下一页。
/// 拖过阈值或甩得够快才翻页；翻页由调用方交给 `StageModel.step(_:on:)`（单窗为主路）。
/// 每页以房间名为标识：翻页后下一页的视图原样变成当前页，模糊缩略图垫底不重载，不会闪黑。
struct LanePager<Page: View>: View {
    var rooms: [Room]
    var index: Int
    /// 翻一页：+1 下一间，-1 上一间。返回是否真的换了房间。
    var step: (Int) -> Bool
    var tap: () -> Void
    @ViewBuilder var page: (Room, Bool) -> Page

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var drag: CGFloat = 0
    @State private var settling = false

    var body: some View {
        GeometryReader { geo in
            let height = geo.size.height
            let slots = PageSwipe.slots(count: rooms.count, index: index)
            ZStack {
                ForEach(slots, id: \.index) { slot in
                    let room = rooms[slot.index]
                    page(room, slot.offset == 0)
                        .frame(width: geo.size.width, height: height)
                        .offset(y: CGFloat(slot.offset) * height + drag)
                        .id(room.username)
                }
            }
            .frame(width: geo.size.width, height: height)
            .clipped()
            .contentShape(Rectangle())
            .onTapGesture(perform: tap)
            .gesture(dragGesture(height: height, slots: slots))
        }
    }

    private func dragGesture(height: CGFloat, slots: [PageSwipe.Slot]) -> some Gesture {
        let hasPrevious = slots.contains { $0.offset < 0 }
        let hasNext = slots.contains { $0.offset > 0 }
        return DragGesture(minimumDistance: 12)
            .onChanged { value in
                guard !settling, abs(value.translation.height) > abs(value.translation.width) else { return }
                drag = PageSwipe.offset(translation: value.translation.height, hasPrevious: hasPrevious, hasNext: hasNext)
            }
            .onEnded { value in
                guard !settling else { return }
                let delta = PageSwipe.decision(
                    translation: value.translation.height,
                    predictedEnd: value.predictedEndTranslation.height,
                    height: height, hasPrevious: hasPrevious, hasNext: hasNext
                )
                settle(delta, height: height)
            }
    }

    private func settle(_ delta: Int, height: CGFloat) {
        guard delta != 0 else {
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) { drag = 0 }
            return
        }
        guard !reduceMotion else {
            commit(delta)
            return
        }
        settling = true
        withAnimation(.easeOut(duration: 0.22)) {
            drag = -CGFloat(delta) * height
        } completion: {
            commit(delta)
        }
    }

    /// 换房与归零在同一个无动画事务里：新当前页正好落在原来邻页的位置上。
    private func commit(_ delta: Int) {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            _ = step(delta)
            drag = 0
        }
        settling = false
    }
}

/// 翻页判定（纯函数，VelaPhoneTests 覆盖）。
enum PageSwipe {
    /// 拖过窗格高度的这个比例就翻页。
    static let distanceRatio: CGFloat = 0.2
    /// 甩动翻页：至少拖了这么多点，且预计终点超过窗格高度的一半。
    static let flingMinimum: CGFloat = 24
    static let flingRatio: CGFloat = 0.5
    /// 没有邻页的方向只跟手三分之一，松手回弹。
    static let edgeDamping: CGFloat = 1.0 / 3.0

    struct Slot: Equatable {
        /// 房间在列表里的下标。
        var index: Int
        /// -1 上一页，0 当前页，+1 下一页。
        var offset: Int
    }

    /// 要渲染的页：当前页与存在的上下邻页，最多三页。
    static func slots(count: Int, index: Int) -> [Slot] {
        guard count > 0 else { return [] }
        let current = min(max(index, 0), count - 1)
        return (-1...1).compactMap { offset in
            let position = current + offset
            guard position >= 0, position < count else { return nil }
            return Slot(index: position, offset: offset)
        }
    }

    /// 松手后的去向：+1 下一间（向上拖）、-1 上一间（向下拖）、0 回弹。
    static func decision(
        translation: CGFloat, predictedEnd: CGFloat, height: CGFloat, hasPrevious: Bool, hasNext: Bool
    ) -> Int {
        guard height > 0, translation != 0 else { return 0 }
        let far = abs(translation) >= height * distanceRatio
        let fling = abs(translation) >= flingMinimum
            && abs(predictedEnd) >= height * flingRatio
            && (predictedEnd < 0) == (translation < 0)
        guard far || fling else { return 0 }
        if translation < 0 { return hasNext ? 1 : 0 }
        return hasPrevious ? -1 : 0
    }

    /// 拖动中的偏移：有邻页的方向跟手，没有的方向加阻尼。
    static func offset(translation: CGFloat, hasPrevious: Bool, hasNext: Bool) -> CGFloat {
        let blocked = translation > 0 ? !hasPrevious : !hasNext
        return blocked ? translation * edgeDamping : translation
    }
}
