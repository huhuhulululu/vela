import SwiftUI

struct SectionTitle: View {
    var title: String
    var note: String?

    init(_ title: String, note: String? = nil) {
        self.title = title
        self.note = note
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(title)
                .font(.system(size: 12, weight: .semibold))
            if let note {
                Text(note)
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .lineLimit(1)
    }
}

/// 一行：名字、强弱条（后验均值）、命中次数，右侧是一组文字按钮。
struct TasteRowView: View {
    var name: String
    var mean: Double
    var detail: String
    var actions: [(title: String, color: Color, run: () -> Void)]

    var body: some View {
        HStack(spacing: 8) {
            Text(name)
                .font(.system(size: 12, weight: .medium))
                .lineLimit(1)
                .frame(width: 88, alignment: .leading)
            StrengthBar(value: mean)
                .frame(width: 44, height: 5)
            Text(detail)
                .font(.system(size: 11).monospacedDigit())
                .foregroundStyle(Theme.textSecondary)
                .lineLimit(1)
                .frame(width: 58, alignment: .leading)
            Spacer(minLength: 0)
            ForEach(Array(actions.enumerated()), id: \.offset) { _, action in
                Button(action: action.run) {
                    Text(action.title)
                        .font(.system(size: 11, weight: .medium))
                        .lineLimit(1)
                        .fixedSize()
                        .foregroundStyle(action.color)
                        .frame(minHeight: 24)
                        .contentShape(Rectangle())
                }
                .buttonStyle(QuietButtonStyle())
            }
        }
        .frame(minHeight: 26)
    }
}

struct StrengthBar: View {
    var value: Double

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.12))
                Capsule()
                    .fill(Theme.champagne)
                    .frame(width: geo.size.width * min(max(value, 0.04), 1))
            }
        }
        .accessibilityLabel("强弱 \(Int((value * 100).rounded()))%")
    }
}

/// 最近 7 天命中率与每日条形。样本不足时只说「数据还不够」。
struct HitRateCard: View {
    var summary: TasteSummary
    private static let weekdays = ["日", "一", "二", "三", "四", "五", "六"]
    private let barHeight: CGFloat = 34

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                SectionTitle("猜你喜欢的命中率", note: "最近 7 天")
                Spacer(minLength: 8)
                Text(summary.hitRate.map { "\(Int(($0 * 100).rounded()))%" } ?? "数据还不够")
                    .font(.system(size: summary.hitRate == nil ? 12 : 18, weight: .semibold).monospacedDigit())
                    .foregroundStyle(summary.hitRate == nil ? Theme.textSecondary : Theme.champagne)
                    .lineLimit(1)
            }
            HStack(alignment: .bottom, spacing: 6) {
                ForEach(Array(summary.week.enumerated()), id: \.offset) { index, day in
                    column(day, today: index == summary.week.count - 1)
                }
            }
        }
        .padding(10)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func column(_ day: DayBar, today: Bool) -> some View {
        let peak = max(1, summary.week.map(\.total).max() ?? 1)
        let total = barHeight * CGFloat(day.total) / CGFloat(peak)
        let hit = barHeight * CGFloat(day.hit) / CGFloat(peak)
        return VStack(spacing: 3) {
            ZStack(alignment: .bottom) {
                Color.clear.frame(height: barHeight)
                RoundedRectangle(cornerRadius: 2).fill(.white.opacity(0.16)).frame(height: max(total, 2))
                RoundedRectangle(cornerRadius: 2).fill(Theme.champagne).frame(height: hit)
            }
            Text(today ? "今" : Self.weekdays[max(0, min(6, day.weekday - 1))])
                .font(.system(size: 10))
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("命中 \(day.hit) 次，落空 \(day.miss) 次")
    }
}
