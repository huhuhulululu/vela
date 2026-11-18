import AppKit
import SwiftUI

struct ChromeView: View {
    @ObservedObject var model: StageModel

    var body: some View {
        bar
    }

    /// 实心控制栏：深色底加极轻的材质，文字与图标不依赖画面内容。
    private var surface: some View {
        ZStack {
            Rectangle().fill(.ultraThinMaterial)
            Rectangle().fill(Theme.barFill)
        }
        .overlay(alignment: .bottom) {
            Rectangle().fill(.white.opacity(0.10)).frame(height: 0.5)
        }
    }

    private var dragSurface: some View { DragStrip() }

    private var bar: some View {
        VStack(spacing: 0) {
            HStack(spacing: Theme.tightGap) {
                WindowButtons()
                SourceSwitch(selected: model.source) { model.select($0) }
                    .padding(.leading, Theme.tightGap)
                // 控件之间的空隙本身就是拖动面（Color.clear 吃掉命中，背景拖动面收不到按下）。
                dragSurface.frame(maxWidth: .infinity)
                icon("pin", active: model.pinned, help: model.pinned ? "取消置顶" : "置顶", label: "置顶") { model.togglePinned() }
                icon(model.presence.conditionalOpacity ? "circle.lefthalf.filled" : "circle.fill",
                     active: model.presence.conditionalOpacity, help: "离开后淡出设置", label: "淡出设置") {
                    model.showOpacity = true
                }
                .popover(isPresented: $model.showOpacity, arrowEdge: .top) {
                    OpacityPanel(model: model)
                }
                icon("line.3.horizontal.decrease", active: model.showFilters, help: "搜索与筛选", label: "搜索与筛选") {
                    model.showFilters = true
                }
                .popover(isPresented: $model.showFilters, arrowEdge: .top) {
                    FilterPanel(model: model)
                }
                HeartButton(isFavorite: model.currentIsFavorite, canToggle: model.feed.current != nil,
                            toggle: { model.toggleFavorite(model.target()) }, openList: { model.showCuration = true })
                .popover(isPresented: $model.showCuration, arrowEdge: .top) {
                    CurationPanel(
                        model: model,
                        exportFile: { model.exportFavorites() },
                        importFile: { model.importFavorites() }
                    )
                }
                icon("sparkles", active: model.showTaste || model.taste.paused, help: "我的口味", label: "我的口味") {
                    model.showTaste = true
                }
                .popover(isPresented: $model.showTaste, arrowEdge: .top) {
                    TasteView(model: model)
                }
                icon(model.muted ? "speaker.slash" : "speaker.wave.2", active: model.muted, help: "音量", label: "音量") {
                    model.showVolume = true
                }
                .popover(isPresented: $model.showVolume, arrowEdge: .top) {
                    VolumePanel(model: model)
                }
            }
            .padding(.horizontal, Theme.inset)
            .frame(height: Theme.barHeight)
            // 空白处可拖动窗口；按钮、来源切换在它上层，不触发拖动。
            .background(dragSurface)
            .background(surface)
        }
    }

    private func icon(_ name: String, active: Bool, help: String, label: String, action: @escaping () -> Void) -> some View {
        ChromeIconButton(symbol: name, active: active, help: help, label: label, action: action)
    }
}

private struct OpacityPanel: View {
    @ObservedObject var model: StageModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("离开后淡出")
                .font(.system(size: 13, weight: .semibold))
            Text("指针离开后窗口变淡，并让出点击。移回来就恢复，方便压在工作窗口上。")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Toggle("启用", isOn: Binding(
                get: { model.presence.conditionalOpacity },
                set: { _ in model.toggleConditional() }
            ))
            slider("离开时", value: model.presence.restingOpacity, range: 0.18...0.85) { model.setRestingOpacity($0) }
            slider("观看时", value: model.presence.engagedOpacity, range: 0.72...1) { model.setEngagedOpacity($0) }
            Toggle("离开后让出点击", isOn: Binding(
                get: { model.presence.clickThrough },
                set: { model.setClickThrough($0) }
            ))
            Toggle("离开后降低画质", isOn: Binding(
                get: { model.restSavesPower },
                set: { model.setRestSavesPower($0) }
            ))
            Text("指针离开约 10 秒后当前房间换到低画质，回来后换回；新开的房间在离开状态下也先用低画质。窗口被遮挡或最小化时会自动暂停；开启淡出后，只有指针在窗口里才阻止屏幕休眠。")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(width: 280)
    }

    private func slider(_ title: String, value: Double, range: ClosedRange<Double>, set: @escaping (Double) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title)
                Spacer()
                Text("\(Int((value * 100).rounded()))%")
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
            .font(.system(size: 12, weight: .medium))
            Slider(value: Binding(get: { value }, set: set), in: range)
                .tint(Theme.champagne)
        }
    }
}

private struct VolumePanel: View {
    @ObservedObject var model: StageModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("音量")
                Spacer()
                Text(model.muted ? "静音" : "\(Int((model.volume * 100).rounded()))%")
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
            .font(.system(size: 12, weight: .medium))
            Slider(value: Binding(get: { model.volume }, set: { model.setVolume($0) }), in: 0...1)
                .tint(Theme.champagne)
            Button(model.muted ? "取消静音" : "静音") { model.toggleMute() }
                .buttonStyle(.plain)
                .font(.system(size: 12))
                .foregroundStyle(Theme.champagne)
        }
        .padding(16)
        .frame(width: 220)
    }
}
