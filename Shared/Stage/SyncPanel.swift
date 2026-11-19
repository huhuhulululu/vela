import LocalAuthentication
import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
import UniformTypeIdentifiers
#endif

/// 同步设置面板（docs/plans/sync.md 第 4.3、12、16 节）：开启、加入、状态、关闭、清除云端。
/// 完整同步码只出现在三个专用界面：创建后的「保存你的同步码」、需验证身份的「查看同步码」、「加入」的输入框。
/// 其他地方只显示短指纹。
struct SyncPanel: View {
    @ObservedObject var hub: SyncHub
    /// macOS 设置窗口给固定宽度；iOS 口味面板里随屏宽。
    var width: CGFloat?

    @State private var mode: Mode = .home
    @State private var codeInput = ""
    @State private var report: SyncMigrationReport?
    @State private var reportError: String?
    @State private var confirmClear = false
    @State private var revealed: String?
    @State private var showPrivacy = false

    enum Mode { case home, enabling, joining }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let code = hub.savedCode {
                SyncCodeCard(title: "保存你的同步码", code: code, fingerprint: hub.fingerprint,
                             note: "同步码等于全部权限，丢了无法找回。在其他设备上选「我已有同步码」并粘贴它。") {
                    hub.dismissSavedCode()
                }
            } else if let code = revealed {
                SyncCodeCard(title: "查看同步码", code: code, fingerprint: hub.fingerprint, note: "不要截图分享，不要发给别人。") {
                    revealed = nil
                }
            } else {
                content
            }
            if let notice = hub.notice {
                noteRow(notice, color: Theme.live)
                    .onTapGesture { hub.dismissNotice() }
            }
        }
        .frame(width: width, alignment: .leading)
        .frame(maxWidth: width == nil ? 420 : nil, alignment: .leading)
        .onAppear { hub.trigger(.panel) }
        .onDisappear { revealed = nil }
    }

    @ViewBuilder
    private var content: some View {
        switch hub.phase {
        case .loading:
            noteRow("正在读取同步状态…")
        case .unsupported:
            noteRow("这个版本的 App 不支持同步（缺少钥匙串授权）。")
        case .unavailable:
            noteRow("设备解锁后才能读取同步密钥，稍后再试。")
        case .attention(let text):
            noteRow(text, color: Theme.live)
            HStack(spacing: 16) {
                textButton("关闭同步", Theme.live) { hub.disable() }
                textButton("输入同步码", Theme.champagne) { mode = .joining }
            }
            if mode == .joining { joinSection }
        case .off:
            offSection
        case .active:
            activeSection
        }
    }

    // MARK: 未开启

    @ViewBuilder
    private var offSection: some View {
        if case .preview = hub.joinStage {
            joinSection
        } else {
            switch mode {
            case .home:
                Text("在多台设备之间同步收藏、屏蔽与「不想看」。内容在本机加密后才上传。")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 16) {
                    if hub.canCreate {
                        textButton("在这台设备上开启同步", Theme.champagne) { prepareEnable() }
                    }
                    textButton("我已有同步码", Theme.champagne) { mode = .joining }
                }
                noteRow("其他设备已开启过就选「我已有同步码」。")
                privacyDisclosure
            case .enabling:
                enableSection
            case .joining:
                joinSection
            }
        }
    }

    private func prepareEnable() {
        switch hub.enablePreview() {
        case .success(let r):
            report = r
            reportError = nil
        case .failure(.message(let text)):
            report = nil
            reportError = text
        }
        mode = .enabling
    }

    @ViewBuilder
    private var enableSection: some View {
        SectionTitle("开启同步")
        if let reportError {
            noteRow(reportError, color: Theme.live)
            textButton("返回", Theme.textSecondary) { mode = .home }
        } else {
            privacyList
            if let report {
                ForEach(Self.reportLines(report), id: \.self) { noteRow($0, color: Theme.live) }
            }
            HStack(spacing: 16) {
                textButton(hub.busy ? "正在开启…" : "同意并开启", Theme.champagne) {
                    Task {
                        await hub.enable()
                        mode = .home
                    }
                }
                .disabled(hub.busy)
                textButton("取消", Theme.textSecondary) { mode = .home }
            }
        }
    }

    static func reportLines(_ r: SyncMigrationReport) -> [String] {
        var lines: [String] = []
        if !r.invalidNames.isEmpty { lines.append("\(r.invalidNames.count) 个格式不合规的旧名字不会同步，将从收藏中移除") }
        if !r.droppedSyncVetoes.isEmpty { lines.append("「不想看」超过 450 个，\(r.droppedSyncVetoes.count) 个将被取消") }
        if !r.droppedLocalOnlyVetoes.isEmpty { lines.append("本机专用的「不想看」超过 50 个，\(r.droppedLocalOnlyVetoes.count) 个将被取消") }
        return lines
    }

    // MARK: 加入

    @ViewBuilder
    private var joinSection: some View {
        SectionTitle("加入已有的同步")
        switch hub.joinStage {
        case .preview(let ticket):
            VStack(alignment: .leading, spacing: 6) {
                Text("服务器：\(ticket.host)    指纹：\(ticket.fingerprint)")
                    .font(.system(size: 12, weight: .medium))
                    .textSelection(.enabled)
                ForEach(SyncStatusText.joinLines(ticket.preview, host: ticket.host), id: \.self) { noteRow($0) }
            }
            HStack(spacing: 16) {
                textButton(hub.busy ? "正在加入…" : "确认加入", Theme.champagne) {
                    Task {
                        await hub.confirmJoin()
                        codeInput = ""
                        mode = .home
                    }
                }
                .disabled(hub.busy)
                textButton("取消", Theme.textSecondary) {
                    hub.cancelJoin()
                    mode = .home
                }
            }
        case .notFound:
            noteRow("这个同步码没有对应的已有空间。")
            HStack(spacing: 16) {
                textButton("仍用此码创建", Theme.champagne) {
                    Task {
                        await hub.createFromJoin()
                        codeInput = ""
                        mode = .home
                    }
                }
                textButton("取消", Theme.textSecondary) {
                    hub.cancelJoin()
                    mode = .home
                }
            }
        default:
            // 完整同步码只允许出现在这个输入框里。
            SecureField("粘贴同步码", text: $codeInput)
                .textFieldStyle(.roundedBorder)
                .font(.system(size: 12, design: .monospaced))
                .autocorrectionDisabled()
            if case .failed(let text) = hub.joinStage { noteRow(text, color: Theme.live) }
            privacyDisclosure
            HStack(spacing: 16) {
                textButton(hub.busy ? "正在检查…" : "下一步", Theme.champagne) {
                    let text = codeInput
                    Task { await hub.prepareJoin(text) }
                }
                .disabled(hub.busy || codeInput.isEmpty)
                textButton("取消", Theme.textSecondary) {
                    codeInput = ""
                    hub.cancelJoin()
                    mode = .home
                }
            }
        }
    }

    // MARK: 已开启

    @ViewBuilder
    private var activeSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("同步已开启" + (hub.fingerprint.map { "  ·  指纹 \($0)" } ?? ""))
                .font(.system(size: 12, weight: .semibold))
            Text(statusLine)
                .font(.system(size: 11))
                .foregroundStyle(Theme.textSecondary)
            if let failure = hub.failure { noteRow(failure, color: Theme.live) }
        }
        HStack(spacing: 16) {
            textButton("立即同步", Theme.champagne) { hub.syncNow() }
            textButton("查看同步码", Theme.champagne) { Task { await reveal() } }
        }
        if confirmClear {
            noteRow("将删除云端数据，所有设备停止同步；各设备本地数据保留。", color: Theme.live)
            HStack(spacing: 16) {
                textButton(hub.busy ? "正在清除…" : "确定清除", Theme.live) {
                    Task {
                        await hub.clearCloud()
                        confirmClear = false
                    }
                }
                .disabled(hub.busy)
                textButton("取消", Theme.textSecondary) { confirmClear = false }
            }
        } else {
            HStack(spacing: 16) {
                textButton("关闭同步", Theme.textSecondary) { hub.disable() }
                textButton("清除云端…", Theme.live) { confirmClear = true }
            }
        }
        privacyDisclosure
    }

    private var statusLine: String {
        let last = hub.lastSuccessAt.map { "上次同步 " + $0.formatted(date: .abbreviated, time: .shortened) } ?? "尚未同步成功"
        return last + (hub.hasPending ? "，有待同步的改动" : "")
    }

    private func reveal() async {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            hub.showNotice("这台设备没有设置密码，无法查看同步码。")
            return
        }
        let ok = (try? await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "查看同步码")) ?? false
        if ok { revealed = hub.revealCode() }
    }

    // MARK: 隐私说明（第 16 节）

    private var privacyDisclosure: some View {
        DisclosureGroup(isExpanded: $showPrivacy) { privacyList } label: {
            Text("隐私说明").font(.system(size: 12, weight: .medium))
        }
    }

    private var privacyList: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(SyncStatusText.privacyPoints, id: \.self) { noteRow("· " + $0) }
        }
    }

    // MARK: 小部件

    private func noteRow(_ text: String, color: Color = Theme.textSecondary) -> some View {
        Text(text)
            .font(.system(size: 11))
            .foregroundStyle(color)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func textButton(_ title: String, _ color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 12, weight: .medium))
                .lineLimit(1)
                .fixedSize()
                .foregroundStyle(color)
                .frame(minHeight: Theme.tapHeight)
                .contentShape(Rectangle())
        }
        .buttonStyle(QuietButtonStyle())
    }
}

/// 「保存你的同步码」与「查看同步码」两个专用页共用：显示整段码、短指纹，复制时标记敏感。
struct SyncCodeCard: View {
    var title: String
    var code: String
    var fingerprint: String?
    var note: String
    var done: () -> Void
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle(title, note: fingerprint.map { "指纹 \($0)" })
            Text(code)
                .font(.system(size: 12, design: .monospaced))
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .padding(8)
                .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 8))
            Text(note)
                .font(.system(size: 11))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 16) {
                Button(copied ? "已复制（120 秒后失效）" : "复制") {
                    SyncPasteboard.copySensitive(code)
                    copied = true
                }
                .buttonStyle(QuietButtonStyle())
                .foregroundStyle(Theme.champagne)
                Button("我已保存", action: done)
                    .buttonStyle(QuietButtonStyle())
                    .foregroundStyle(Theme.textSecondary)
            }
            .font(.system(size: 12, weight: .medium))
        }
    }
}

/// 复制同步码时标记敏感（第 4.3 节）：iOS 限本机且 120 秒过期；macOS 同时写 `org.nspasteboard.ConcealedType`。
enum SyncPasteboard {
    static func copySensitive(_ text: String) {
        #if os(macOS)
        let board = NSPasteboard.general
        board.clearContents()
        board.declareTypes([.string, NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")], owner: nil)
        board.setString(text, forType: .string)
        board.setString("", forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
        #else
        UIPasteboard.general.setItems([[UTType.plainText.identifier: text]],
                                      options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
        #endif
    }
}
