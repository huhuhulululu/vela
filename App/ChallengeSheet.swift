import AppKit
import SwiftUI
import WebKit

/// 站点要求确认访问时弹出的网页：完成确认后回到播放器，画面不在网页里播放。
struct ChallengeSheet: View {
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
        .frame(width: 480, height: CGFloat(SheetLayout.challengeHeight(windowHeight: model.pointHeight)))
        .preferredColorScheme(.dark)
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
            Text("站点要求确认这次访问。完成后回到播放器即可，画面不会在这个窗口里播放。")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
    }
}

struct SiteWebView: NSViewRepresentable {
    var url: URL
    @Binding var progress: Double

    func makeCoordinator() -> Coordinator {
        Coordinator(onProgress: { value in progress = value })
    }

    func makeNSView(context: Context) -> NSView {
        let host = NSView(frame: .zero)
        context.coordinator.install(in: host, url: url)
        return host
    }

    func updateNSView(_ nsView: NSView, context: Context) {}

    final class Coordinator: NSObject {
        var onProgress: (Double) -> Void
        private var observation: NSKeyValueObservation?

        init(onProgress: @escaping (Double) -> Void) {
            self.onProgress = onProgress
        }

        func install(in host: NSView, url: URL, attempt: Int = 0) {
            DispatchQueue.main.async { [weak self, weak host] in
                guard let self, let host, host.subviews.isEmpty else { return }
                guard host.window != nil || attempt >= 8 else {
                    self.install(in: host, url: url, attempt: attempt + 1)
                    return
                }
                let configuration = WKWebViewConfiguration()
                configuration.websiteDataStore = .default()
                configuration.mediaTypesRequiringUserActionForPlayback = .all
                let webView = WKWebView(frame: host.bounds, configuration: configuration)
                webView.autoresizingMask = [.width, .height]
                webView.customUserAgent = UserAgent.safari
                self.observation = webView.observe(\.estimatedProgress) { [weak self] view, _ in
                    let value = view.estimatedProgress
                    DispatchQueue.main.async { self?.onProgress(value) }
                }
                host.addSubview(webView)
                webView.load(URLRequest(url: url))
            }
        }
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
            _ = WKWebView(frame: NSRect(x: 0, y: 0, width: 2, height: 2), configuration: configuration)
        }
        self.task = task
        await task.value
    }
}
