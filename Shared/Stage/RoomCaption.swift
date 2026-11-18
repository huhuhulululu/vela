import QuartzCore
import SwiftUI

/// 房间信息块：用户名、LIVE、人数、标题、元信息、标签。纯展示，不接收点击。
struct RoomCaption: View {
    var room: Room
    /// 当前页、正在播、并且信息块可见。为假时圆点静止，并用 `.id` 拆掉正在跑的动画。
    var pulse: Bool
    /// 「猜你喜欢」里的推荐理由，单行；其他来源为 nil。
    var reason: String?

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.gap) {
            HStack(spacing: Theme.gap) {
                PulseDot(animated: pulse)
                    .id(pulse)
                Text("LIVE")
                    .font(.system(size: 10, weight: .semibold))
                    .tracking(1.6)
                Text(ViewerCount.string(room.viewers))
                    .font(.system(size: 12, weight: .medium).monospacedDigit())
                if room.isHD {
                    Text("HD")
                        .font(.system(size: 10, weight: .bold))
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(.white.opacity(0.14), in: Capsule())
                }
            }
            .lineLimit(1)
            .foregroundStyle(Theme.textPrimary)
            Text(room.displayName)
                .font(.system(size: 28, weight: .semibold))
                .tracking(-0.6)
                .foregroundStyle(.white)
                .lineLimit(1)
            if !room.subject.isEmpty {
                Text(room.subject)
                    .font(.system(size: 14))
                    .foregroundStyle(.white.opacity(0.85))
                    .lineLimit(2)
            }
            Text(meta)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(Theme.textSecondary)
                .lineLimit(1)
            if let reason {
                HStack(spacing: 5) {
                    Image(systemName: "sparkles")
                        .font(.system(size: 10, weight: .semibold))
                    Text(reason)
                        .font(.system(size: 12, weight: .medium))
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                .foregroundStyle(Theme.champagne)
                .accessibilityElement(children: .combine)
            }
            if !room.tags.isEmpty {
                // 窄窗口放不下就少显示几个标签，标签本身不折行。
                ViewThatFits(in: .horizontal) {
                    tagRow(4)
                    tagRow(3)
                    tagRow(2)
                    tagRow(1, truncating: true)
                }
            }
        }
        .padding(.horizontal, 22)
        .padding(.bottom, 26)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func tagRow(_ count: Int, truncating: Bool = false) -> some View {
        HStack(spacing: Theme.tightGap) {
            ForEach(Array(room.tags.prefix(count)), id: \.self) { tag in
                Text(tag)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.white.opacity(0.88))
                    .lineLimit(1)
                    .padding(.horizontal, Theme.gap)
                    .padding(.vertical, 4)
                    .background(.white.opacity(0.12), in: Capsule())
                    .fixedSize(horizontal: !truncating, vertical: true)
            }
        }
    }

    private var meta: String {
        var parts: [String] = []
        if let gender = room.gender { parts.append(Copy.gender(gender)) }
        if let age = room.age { parts.append("\(age)") }
        if let clock = BroadcastClock.string(seconds: room.secondsOnline) { parts.append(clock) }
        if !room.location.isEmpty { parts.append(room.location) }
        return parts.joined(separator: "  ·  ")
    }
}

/// 只有当前页做脉冲动画，邻页是静态圆点。
/// 动画走图层而不是 SwiftUI：`repeatForever` 会让整个宿主视图每帧 layout。
/// 外观与原来一致：1.05 秒 ease-in-out，不透明度在 0.4 和 1 之间往复；减弱动态效果时是静止实心圆。
struct PulseDot: View {
    var animated: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var pulsing: Bool { animated && !reduceMotion }

    var body: some View {
        PulseDotMark(pulsing: pulsing)
            .frame(width: 7, height: 7)
    }
}
