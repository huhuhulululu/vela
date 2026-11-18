import AppKit
import SwiftUI
import WebKit

/// 质询网页的 macOS 宿主。iOS 版同名类型在 `iOS/SiteWebView+Phone.swift`。
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
