import MetricKit
import SwiftUI
import UIKit
import os

/// 真机诊断的本机存储：`Application Support/Vela/diagnostics.json`（目录已排除 iCloud 备份，见 `PhoneHousekeeping`）。
/// 只存最近 7 天的摘要与截断后的原始 JSON；不联网、不上传、不同步。界面只在打开诊断时读一次。
enum DiagnosticsStore {
    static let log = Logger(subsystem: "com.vela.stage", category: "diagnostics")

    static func defaultURL() -> URL {
        PhoneHousekeeping.dataDirectory().appendingPathComponent("diagnostics.json")
    }

    /// 读不到或坏文件都当没有记录（坏文件下次写入时被覆盖）。
    static func load(from url: URL = defaultURL()) -> [DiagnosticDay] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        do {
            return try DiagnosticDigest.decode(data)
        } catch {
            log.error("diagnostics file unreadable")
            return []
        }
    }

    /// 把新到的 payload（`jsonRepresentation()`）并入文件，返回合并后的记录。调用方负责串行。
    @discardableResult
    static func record(metrics: [Data], diagnostics: [Data], at url: URL = defaultURL()) -> [DiagnosticDay] {
        var days = load(from: url)
        for data in metrics {
            if let day = DiagnosticDigest.metricDay(from: data) { days = DiagnosticDigest.merge(days, metric: day) }
        }
        for data in diagnostics {
            if let day = DiagnosticDigest.diagnosticDay(from: data) { days = DiagnosticDigest.merge(days, diagnostic: day) }
        }
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try DiagnosticDigest.encode(days).write(to: url, options: .atomic)
            log.info("diagnostics recorded metrics=\(metrics.count) diagnostics=\(diagnostics.count) days=\(days.count)")
        } catch {
            log.error("diagnostics write failed")
        }
        return days
    }

    /// 分享用的全文：摘要在前，原始 JSON 附在后面（用户在系统分享表里自己决定发不发）。
    static func shareText(_ days: [DiagnosticDay]) -> String {
        var text = "Vela 诊断（本机最近 \(days.count) 天）\n" + DiagnosticDigest.text(days)
        for day in days {
            if let raw = day.rawMetric { text += "\n\n[\(day.day) 指标]\n" + raw }
            if let raw = day.rawDiagnostic { text += "\n\n[\(day.day) 诊断]\n" + raw }
        }
        return text
    }
}

/// MetricKit 订阅：系统大约每天投递一次指标与诊断（回调由系统在后台线程发起），这里只串行落盘。
/// 不新增定时器与常驻任务；没有 payload 时什么都不做。
final class DiagnosticsCollector: NSObject, MXMetricManagerSubscriber {
    static let shared = DiagnosticsCollector()
    private let queue = DispatchQueue(label: "com.vela.stage.diagnostics", qos: .utility)
    private var started = false

    /// App 启动时调用一次；重复调用不重复订阅。
    func start() {
        guard !started else { return }
        started = true
        MXMetricManager.shared.add(self)
    }

    func didReceive(_ payloads: [MXMetricPayload]) {
        let data = payloads.map { $0.jsonRepresentation() }
        queue.async { DiagnosticsStore.record(metrics: data, diagnostics: []) }
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        let data = payloads.map { $0.jsonRepresentation() }
        queue.async { DiagnosticsStore.record(metrics: [], diagnostics: data) }
    }
}

/// 口味（设置）面板底部的「诊断」入口：默认收起，展开时才读文件；可复制为文本或经系统分享表分享。
struct PhoneDiagnosticsSection: View {
    @State private var expanded: Bool
    @State private var days: [DiagnosticDay] = []
    @State private var copied = false
    /// 分享全文只在展开读文件时拼一次，不在每次刷新界面时重算。
    @State private var share = ""
    private let url: URL

    init(expanded: Bool = false, url: URL = DiagnosticsStore.defaultURL()) {
        _expanded = State(initialValue: expanded)
        self.url = url
    }

    var body: some View {
        DisclosureGroup(isExpanded: $expanded) {
            content
        } label: {
            Text("诊断")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
        }
        .tint(Theme.textSecondary)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .onChange(of: expanded) { _, open in if open { reload() } }
        .onAppear { if expanded { reload() } }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("系统每天投递一次本机的耗电、发热与卡顿数据，只保存在这台设备上最近 7 天，不联网、不上传。")
                .font(.system(size: 12))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if days.isEmpty {
                        Text(DiagnosticDigest.text([]))
                    } else {
                        ForEach(days, id: \.day) { Text(DiagnosticDigest.line($0)) }
                    }
                }
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Theme.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
            }
            .frame(maxHeight: 220)
            HStack(spacing: 12) {
                Button(copied ? "已复制" : "复制为文本") {
                    UIPasteboard.general.string = DiagnosticDigest.text(days)
                    copied = true
                }
                .buttonStyle(ChampagneButton())
                ShareLink(item: share) { Text("分享") }
                    .buttonStyle(ChampagneButton())
                    .disabled(days.isEmpty)
            }
        }
        .padding(.top, 6)
    }

    private func reload() {
        days = DiagnosticsStore.load(from: url)
        share = DiagnosticsStore.shareText(days)
        copied = false
        DiagnosticsStore.log.info("diagnostics opened days=\(days.count)")
    }
}
