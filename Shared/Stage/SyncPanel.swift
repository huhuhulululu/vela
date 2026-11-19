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
/// 其他地方只显示短指纹。为控制包体积，各段落以文字行与按钮行的数据驱动拼成，类型擦除到 `AnyView`。
struct SyncPanel: View {
    @ObservedObject var hub: SyncHub
    /// macOS 设置窗口给固定宽度；iOS 口味面板里随屏宽。
    var width: CGFloat?

    @State private var mode: Mode = .home
    @State private var codeInput = ""
    @State private var enableLines: [String] = []
    @State private var enableError: String?
    @State private var confirmClear = false
    @State private var revealed: String?
    @State private var showPrivacy = false

    enum Mode { case home, enabling, joining }

    struct Action {
        let title: String
        let color: Color
        var enabled = true
        let run: () -> Void
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let code = hub.savedCode {
                SyncCodeCard(title: "保存你的同步码", code: code, fingerprint: hub.fingerprint,
                             note: "同步码等于全部权限，丢了无法找回。在其他设备上选「我已有同步码」并粘贴它。") { hub.dismissSavedCode() }
            } else if let code = revealed {
                SyncCodeCard(title: "查看同步码", code: code, fingerprint: hub.fingerprint, note: "不要截图分享，不要发给别人。") { revealed = nil }
            } else {
                content
            }
            if let notice = hub.notice {
                Self.note(notice, Theme.live).onTapGesture { hub.dismissNotice() }
            }
        }
        .frame(width: width, alignment: .leading)
        .frame(maxWidth: width == nil ? 420 : nil, alignment: .leading)
        .onAppear { hub.trigger(.panel) }
        .onDisappear { revealed = nil }
    }

    private var content: AnyView {
        switch hub.phase {
        case .loading: return lines(["正在读取同步状态…"])
        case .unsupported: return lines(["这个版本的 App 不支持同步（缺少钥匙串授权）。"])
        case .unavailable: return lines(["设备解锁后才能读取同步密钥，稍后再试。"])
        case .attention(let text):
            if mode == .joining { return join }
            return section(nil, [text], color: Theme.live, actions: [
                Action(title: "关闭同步", color: Theme.live) { hub.disable() },
                Action(title: "输入同步码", color: Theme.champagne) { mode = .joining },
            ])
        case .off:
            if case .preview = hub.joinStage { return join }
            switch mode {
            case .enabling: return enable
            case .joining: return join
            case .home:
                var actions: [Action] = []
                if hub.canCreate { actions.append(Action(title: "在这台设备上开启同步", color: Theme.champagne) { prepareEnable() }) }
                actions.append(Action(title: "我已有同步码", color: Theme.champagne) { mode = .joining })
                return section(nil, ["在多台设备之间同步收藏、屏蔽与「不想看」。内容在本机加密后才上传。", "其他设备已开启过就选「我已有同步码」。"],
                               actions: actions, privacy: true)
            }
        case .active: return active
        }
    }

    // MARK: 开启

    private func prepareEnable() {
        switch hub.enablePreview() {
        case .success(let r):
            enableLines = Self.reportLines(r)
            enableError = nil
        case .failure(.message(let text)):
            enableLines = []
            enableError = text
        }
        mode = .enabling
    }

    private var enable: AnyView {
        if let enableError {
            return section("开启同步", [enableError], color: Theme.live, actions: [Action(title: "返回", color: Theme.textSecondary) { mode = .home }])
        }
        return section("开启同步", SyncStatusText.privacyPoints.map { "· " + $0 } + enableLines, actions: [
            Action(title: hub.busy ? "正在开启…" : "同意并开启", color: Theme.champagne, enabled: !hub.busy) {
                Task {
                    await hub.enable()
                    mode = .home
                }
            },
            Action(title: "取消", color: Theme.textSecondary) { mode = .home },
        ])
    }

    static func reportLines(_ r: SyncMigrationReport) -> [String] {
        var lines: [String] = []
        if !r.invalidNames.isEmpty { lines.append("\(r.invalidNames.count) 个格式不合规的旧名字不会同步，将从收藏中移除") }
        if !r.droppedSyncVetoes.isEmpty { lines.append("「不想看」超过 450 个，\(r.droppedSyncVetoes.count) 个将被取消") }
        if !r.droppedLocalOnlyVetoes.isEmpty { lines.append("本机专用的「不想看」超过 50 个，\(r.droppedLocalOnlyVetoes.count) 个将被取消") }
        return lines
    }

    // MARK: 加入

    private func leaveJoin() {
        codeInput = ""
        hub.cancelJoin()
        mode = .home
    }

    private func finishJoin(_ work: @escaping () async -> Void) -> () -> Void {
        {
            Task {
                await work()
                codeInput = ""
                mode = .home
            }
        }
    }

    private var join: AnyView {
        switch hub.joinStage {
        case .preview(let ticket):
            return section("加入已有的同步", ["服务器：\(ticket.host)    指纹：\(ticket.fingerprint)"] + SyncStatusText.joinLines(ticket.preview, host: ticket.host) + Self.reportLines(ticket.report), actions: [
                Action(title: hub.busy ? "正在加入…" : "确认加入", color: Theme.champagne, enabled: !hub.busy, run: finishJoin { await hub.confirmJoin() }),
                Action(title: "取消", color: Theme.textSecondary) { leaveJoin() },
            ])
        case .notFound:
            return section("加入已有的同步", ["这个同步码没有对应的已有空间。"], actions: [
                Action(title: "仍用此码创建", color: Theme.champagne, enabled: !hub.busy, run: finishJoin { await hub.createFromJoin() }),
                Action(title: "取消", color: Theme.textSecondary) { leaveJoin() },
            ])
        default:
            var failure: [String] = []
            if case .failed(let text) = hub.joinStage { failure = [text] }
            // 完整同步码只允许出现在这个输入框里。
            return AnyView(VStack(alignment: .leading, spacing: 8) {
                SectionTitle("加入已有的同步")
                SecureField("粘贴同步码", text: $codeInput)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 12, design: .monospaced))
                    .autocorrectionDisabled()
                section(nil, failure, color: Theme.live, actions: [
                    Action(title: hub.busy ? "正在检查…" : "下一步", color: Theme.champagne, enabled: !hub.busy && !codeInput.isEmpty) {
                        let text = codeInput
                        Task { await hub.prepareJoin(text) }
                    },
                    Action(title: "取消", color: Theme.textSecondary) { leaveJoin() },
                ], privacy: true)
            })
        }
    }

    // MARK: 已开启

    private var active: AnyView {
        let last = hub.lastSuccessAt.map { "上次同步 " + $0.formatted(date: .abbreviated, time: .shortened) } ?? "尚未同步成功"
        var rows = ["同步已开启" + (hub.fingerprint.map { "  ·  指纹 \($0)" } ?? ""), last + (hub.hasPending ? "，有待同步的改动" : "")]
        if let failure = hub.failure { rows.append(failure) }
        var actions = [
            Action(title: "立即同步", color: Theme.champagne) { hub.syncNow() },
            Action(title: "查看同步码", color: Theme.champagne) { Task { await reveal() } },
        ]
        if confirmClear {
            rows.append("将删除云端数据，所有设备停止同步；各设备本地数据保留。")
            actions = [
                Action(title: hub.busy ? "正在清除…" : "确定清除", color: Theme.live, enabled: !hub.busy) {
                    Task {
                        await hub.clearCloud()
                        confirmClear = false
                    }
                },
                Action(title: "取消", color: Theme.textSecondary) { confirmClear = false },
            ]
        } else {
            actions += [
                Action(title: "关闭同步", color: Theme.textSecondary) { hub.disable() },
                Action(title: "清除云端…", color: Theme.live) { confirmClear = true },
            ]
        }
        return section(nil, rows, actions: actions, privacy: true)
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

    // MARK: 积木

    private func lines(_ rows: [String]) -> AnyView { section(nil, rows, actions: []) }

    /// 一个段落：可选标题、若干文字行、一行按钮、可选的隐私说明（第 16 节，可展开）。
    private func section(_ title: String?, _ rows: [String], color: Color = Theme.textSecondary, actions: [Action], privacy: Bool = false) -> AnyView {
        AnyView(VStack(alignment: .leading, spacing: 8) {
            if let title { SectionTitle(title) }
            ForEach(rows, id: \.self) { Self.note($0, color) }
            if !actions.isEmpty {
                HStack(spacing: 16) {
                    ForEach(actions.indices, id: \.self) { i in
                        Button(action: actions[i].run) {
                            Text(actions[i].title)
                                .font(.system(size: 12, weight: .medium))
                                .lineLimit(1)
                                .fixedSize()
                                .foregroundStyle(actions[i].color)
                                .frame(minHeight: Theme.tapHeight)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(QuietButtonStyle())
                        .disabled(!actions[i].enabled)
                    }
                }
            }
            if privacy {
                DisclosureGroup(isExpanded: $showPrivacy) {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(SyncStatusText.privacyPoints, id: \.self) { Self.note("· " + $0, Theme.textSecondary) }
                    }
                } label: {
                    Text("隐私说明").font(.system(size: 12, weight: .medium))
                }
            }
        })
    }

    static func note(_ text: String, _ color: Color = Theme.textSecondary) -> Text {
        Text(text).font(.system(size: 11)).foregroundColor(color)
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
                // 不开系统文本选择：复制只能走下面带敏感标记的按钮（第 4.3 节）。
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
