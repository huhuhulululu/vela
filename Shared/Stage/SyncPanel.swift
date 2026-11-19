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
        .onDisappear {
            revealed = nil
            // 离开加入页作废正在进行的加入（确认前什么都不写；提交途中撤销已写的本机部分）。
            hub.cancelJoin()
        }
    }

    private var content: AnyView {
        switch hub.phase {
        case .loading: return lines(["正在读取同步状态…"])
        case .unsupported: return lines(["这个版本的 App 不支持同步（缺少钥匙串授权）。"])
        case .unavailable:
            return section(nil, ["暂时无法读取同步密钥或连不上服务器，回到前台时会自动再试。"], actions: [
                Action(title: "重试", color: Theme.champagne) { hub.syncNow() },
            ])
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

/// 设置里的「实验功能」：默认关；打开后才显示同步入口（第 1 节）。
struct SyncExperimentPage: View {
    @ObservedObject var hub: SyncHub
    var width: CGFloat?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Toggle("实验：跨设备同步", isOn: Binding(get: { hub.experimentEnabled }, set: { hub.setExperiment($0) }))
                .font(.system(size: 12, weight: .medium))
            if hub.experimentEnabled {
                SyncPanel(hub: hub, width: width)
            } else {
                Text("实验功能，默认关闭。关闭时不显示同步入口、不联网、不读钥匙串。")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(width: width, alignment: .leading)
    }
}

/// 加入提交中：整个舞台置灰并提示，本机写操作在模型层同样被拒（第 12.4 节）。
struct SyncCommitGuard: ViewModifier {
    @ObservedObject var hub: SyncHub

    func body(content: Content) -> some View {
        content
            .disabled(hub.committing)
            .overlay(alignment: .top) {
                if hub.committing {
                    Text(SyncHub.committingMessage)
                        .font(.system(size: 12, weight: .medium))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Theme.hintFill, in: Capsule())
                        .padding(.top, 12)
                }
            }
    }
}

extension View {
    func syncCommitGuard(_ hub: SyncHub) -> some View { modifier(SyncCommitGuard(hub: hub)) }
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

/// 复制同步码时标记敏感（第 4.3 节）：iOS 限本机且 120 秒过期（系统执行）；macOS 用同一个 `NSPasteboardItem`
/// 同时写明文与 `org.nspasteboard.ConcealedType`（剪贴板管理器不会先看到没有敏感标记的版本），
/// 120 秒后、App 退出时、下次启动时，只要剪贴板仍是这次写入（changeCount 未变）就清空。一次性延时，不是常驻定时器。
enum SyncPasteboard {
    static let expirySeconds: TimeInterval = 120
    #if os(macOS)
    /// 本次复制的记录：剪贴板 changeCount 与每次复制新生成的令牌。
    private static let stampKey = "syncPasteboardChangeCount"
    private static let tokenKey = "syncPasteboardToken"
    private static let concealed = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")
    #endif

    static func copySensitive(_ text: String) {
        #if os(macOS)
        let board = NSPasteboard.general
        board.clearContents()
        let item = NSPasteboardItem()
        item.setString(text, forType: .string)
        item.setString("", forType: concealed)
        board.writeObjects([item])
        let token = UUID().uuidString
        UserDefaults.standard.set(board.changeCount, forKey: stampKey)
        UserDefaults.standard.set(token, forKey: tokenKey)
        DispatchQueue.main.asyncAfter(deadline: .now() + SyncPasteboard.expirySeconds) { expire(onlyIf: token) }
        #else
        UIPasteboard.general.setItems([[UTType.plainText.identifier: text]],
                                      options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(SyncPasteboard.expirySeconds)])
        #endif
    }

    /// 到期：只在记录的令牌仍是这次复制的、剪贴板也没被替换时清空；之后又复制过就什么都不做。
    static func expire(onlyIf token: String) {
        #if os(macOS)
        guard UserDefaults.standard.string(forKey: tokenKey) == token else { return }
        expireNow()
        #endif
    }

    /// 剪贴板仍是本 App 最近一次写入的同步码就清空（退出、启动、到期时调用）。
    static func expireNow() {
        #if os(macOS)
        guard let stamp = UserDefaults.standard.object(forKey: stampKey) as? Int else { return }
        let board = NSPasteboard.general
        if board.changeCount == stamp { board.clearContents() }
        UserDefaults.standard.removeObject(forKey: stampKey)
        UserDefaults.standard.removeObject(forKey: tokenKey)
        #endif
    }

    /// 启动时：上次进程留下、没来得及清的同步码（崩溃、被杀）。changeCount 在一次登录会话内跨进程有效。
    static func expireLeftover() { expireNow() }
}
