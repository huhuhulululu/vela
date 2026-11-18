import Foundation
import WebKit

enum SidecarError: Error {
    case unavailable
    case script
    case timedOut
    case challenge
}

@MainActor
final class SiteSidecar: NSObject, WKNavigationDelegate {
    /// 离屏网页视图的宿主（macOS 挂到主窗口内容视图，iOS 挂到场景窗口），两个平台各有一份 `SidecarHost`。
    let host: SidecarHost
    /// 每次加载页面前先等它：默认等旧登录会话清理完，不带旧 sessionid 发请求；iOS 可再接生命周期挂起检查。
    private let beforeLoad: @MainActor () async -> Void
    private var webView: WKWebView?
    private var pendingLoad: CheckedContinuation<Void, Error>?
    private var loaded = false
    private var loading: Task<Void, Error>?
    private var suspended = false
    private var busy = 0
    /// 用完后空闲达到 `SidecarIdlePolicy.seconds` 就销毁离屏网页视图。30 秒内再使用仍是这一份；超过则按需重建。
    private var idleTask: Task<Void, Never>?

    init(
        host: SidecarHost = SidecarHost(),
        beforeLoad: @escaping @MainActor () async -> Void = { await LegacyLoginCleanup.waitUntilDone() }
    ) {
        self.host = host
        self.beforeLoad = beforeLoad
        super.init()
    }

    /// 宿主刚就绪（窗口挂上）：没停着且网页视图还没挂上就挂过去。
    func hostDidAttach() {
        guard !suspended, let webView, webView.superview == nil else { return }
        host.attach(webView)
    }

    func suspend() {
        suspended = true
        webView?.stopLoading()
        webView?.removeFromSuperview()
    }

    func resumeIfNeeded() {
        suspended = false
        guard let webView, webView.superview == nil else { return }
        host.attach(webView)
    }

    func resolve(username: String) async throws -> StreamTicket {
        let result = try await evaluate(
            """
            const response = await fetch("https://chaturbate.com/get_edge_hls_url_ajax/", {
              method: "POST",
              credentials: "include",
              headers: {
                "X-Requested-With": "XMLHttpRequest",
                "Accept": "application/json",
                "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"
              },
              body: "room_slug=" + encodeURIComponent(username)
            });
            return { status: response.status, body: await response.text() };
            """,
            arguments: ["username": username]
        )
        let (status, data) = try payload(result)
        do {
            return try StreamDecoder.decode(status: status, data: data)
        } catch StreamDecodeError.challenge {
            throw SidecarError.challenge
        } catch let error as StreamDecodeError where error.failure != nil {
            // 403 / 503 但不是质询页：普通不可用，原样交给调用方按「暂时无法播放」处理。
            throw error
        } catch {
            throw SidecarError.script
        }
    }

    /// 在侧车页面里发一个 GET（带该页面的 cookie，用户通过站点确认后的通关状态在这里生效）。
    func get(_ url: URL) async throws -> HTTPResult {
        let result = try await evaluate(
            """
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), 10000);
            try {
              const response = await fetch(url, {
                credentials: "include",
                signal: controller.signal,
                headers: { "X-Requested-With": "XMLHttpRequest", "Accept": "application/json" }
              });
              return { status: response.status, body: await response.text() };
            } finally { clearTimeout(timer); }
            """,
            arguments: ["url": url.absoluteString]
        )
        let (status, data) = try payload(result)
        return HTTPResult(status: status, data: data)
    }

    private func evaluate(_ body: String, arguments: [String: Any]) async throws -> Any? {
        busy += 1
        idleTask?.cancel()
        defer {
            busy -= 1
            scheduleIdleTeardown()
        }
        try await ensureLoaded()
        guard let webView else { throw SidecarError.unavailable }
        return try await webView.callAsyncJavaScript(body, arguments: arguments, in: nil, contentWorld: .page)
    }

    private func scheduleIdleTeardown(since: Date = Date()) {
        idleTask?.cancel()
        idleTask = nil
        guard busy == 0, webView != nil else { return }
        let idleFor = Date().timeIntervalSince(since)
        if SidecarIdlePolicy.shouldTearDown(idleFor: idleFor) {
            tearDown()
            return
        }
        let delay = SidecarIdlePolicy.seconds - idleFor
        guard delay.isFinite, delay > 0 else { return }
        let nanos = delay * 1_000_000_000
        guard nanos >= 1, nanos <= Double(UInt64.max) else {
            tearDown()
            return
        }
        idleTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(nanos))
            guard let self, !Task.isCancelled else { return }
            self.scheduleIdleTeardown(since: since)
        }
    }

    private func tearDown() {
        // 只拆离屏网页视图：移出层级并丢掉引用。默认 WKWebsiteDataStore 的通关 cookie 留着，重建后仍然带上。
        webView?.stopLoading()
        webView?.navigationDelegate = nil
        webView?.removeFromSuperview()
        webView = nil
        loaded = false
        loading = nil
    }

    private func ensureLoaded() async throws {
        await beforeLoad()
        if loaded { return }
        if let loading {
            try await loading.value
            return
        }
        let view = ensureWebView()
        let task = Task { @MainActor in
            try await self.load(view)
        }
        loading = task
        do {
            try await task.value
        } catch {
            loading = nil
            throw error
        }
    }

    private func load(_ view: WKWebView) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            pendingLoad = continuation
            var request = URLRequest(url: URL(string: "https://chaturbate.com/auth/login/?next=/")!)
            request.timeoutInterval = 20
            view.load(request)
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 18_000_000_000)
                guard let pending = self.pendingLoad else { return }
                self.pendingLoad = nil
                pending.resume(throwing: SidecarError.timedOut)
            }
        }
    }

    private func ensureWebView() -> WKWebView {
        if let webView { return webView }
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        // Keep a real layout size. A 2×2, fully transparent view often never finishes the page,
        // so the stream request never leaves WebKit.
        let view = WKWebView(frame: CGRect(x: 0, y: -4200, width: 800, height: 600), configuration: configuration)
        view.navigationDelegate = self
        view.customUserAgent = UserAgent.safari
        // 新建的视图马上要加载。停着（确认页还没把侧车挂回来）也先放进窗口外，否则页面经常加载不完。
        host.attach(view)
        webView = view
        return view
    }

    private func payload(_ result: Any?) throws -> (Int, Data) {
        guard let dict = result as? [String: Any] else { throw SidecarError.script }
        let status = (dict["status"] as? Int) ?? (dict["status"] as? NSNumber)?.intValue ?? 0
        guard let body = dict["body"] as? String, let data = body.data(using: .utf8) else {
            throw SidecarError.script
        }
        return (status, data)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        loaded = true
        pendingLoad?.resume()
        pendingLoad = nil
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        pendingLoad?.resume(throwing: error)
        pendingLoad = nil
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        pendingLoad?.resume(throwing: error)
        pendingLoad = nil
    }
}

/// 经侧车发请求的 HTTP 客户端：收藏查询被站点拦截后用它，复用浏览器里的通关状态。
struct SidecarHTTPClient: HTTPClient {
    let sidecar: SiteSidecar

    func send(_ request: URLRequest) async throws -> HTTPResult {
        guard let url = request.url else { throw SidecarError.script }
        return try await sidecar.get(url)
    }
}
