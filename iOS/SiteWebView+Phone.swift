import SwiftUI
import UIKit
import WebKit

/// 质询网页的 iOS 宿主：与 macOS 版同一套配置（默认数据存储、媒体需手势、Safari UA），进度回报给进度条。
struct SiteWebView: UIViewRepresentable {
    var url: URL
    @Binding var progress: Double

    func makeCoordinator() -> Coordinator {
        Coordinator(onProgress: { value in progress = value })
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.customUserAgent = UserAgent.safari
        context.coordinator.observe(webView)
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    final class Coordinator: NSObject {
        var onProgress: (Double) -> Void
        private var observation: NSKeyValueObservation?

        init(onProgress: @escaping (Double) -> Void) {
            self.onProgress = onProgress
        }

        func observe(_ webView: WKWebView) {
            observation = webView.observe(\.estimatedProgress) { [weak self] view, _ in
                let value = view.estimatedProgress
                DispatchQueue.main.async { self?.onProgress(value) }
            }
        }
    }
}
