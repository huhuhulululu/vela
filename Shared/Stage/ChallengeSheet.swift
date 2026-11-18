import SwiftUI
import WebKit

/// 站点要求确认访问时弹出的网页：完成确认后回到播放器，画面不在网页里播放。
/// 这里是两个平台共用的内容；尺寸与配色由各自的外壳决定（macOS 见 `App/ChallengeSheet+Mac.swift`）。
struct ChallengeContent: View {
    @ObservedObject var model: StageModel
    @State private var progress = 0.0

    var body: some View {
        VStack(spacing: 0) {
            header
            ZStack(alignment: .top) {
                SiteWebView(url: URL(string: "https://chaturbate.com/")!, progress: $progress)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                Capsule()
                    .fill(Theme.champagne)
                    .frame(width: max(24, 460 * progress), height: 2)
                    .opacity(progress > 0 && progress < 1 ? 1 : 0)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12)
                    .padding(.top, 8)
            }
            // 网页区域吃掉剩余高度并自己滚动，底部按钮始终可见。
            Button("已完成，继续播放") { model.retryAfterChallenge() }
                .buttonStyle(ChampagneButton())
                .padding(.vertical, 10)
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("确认访问")
                    .font(.system(size: 16, weight: .semibold))
                Spacer()
                Button("关闭") { model.showChallenge = false }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
            }
            Text(Self.hint)
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
    }
}

@MainActor
enum WebKitWarmup {
    private static var task: Task<Void, Never>?

    static func prepare() async {
        if let task {
            await task.value
            return
        }
        let task = Task { @MainActor in
            let configuration = WKWebViewConfiguration()
            configuration.websiteDataStore = .default()
            _ = WKWebView(frame: CGRect(x: 0, y: 0, width: 2, height: 2), configuration: configuration)
        }
        self.task = task
        await task.value
    }
}

extension ChallengeContent {
    /// 页眉说明。两端通用：macOS 是独立窗口、iOS 是 sheet，都只说「这里」。
    static let hint = "站点要求确认这次访问。完成后回到播放器即可，画面不会在这里播放。"
}
