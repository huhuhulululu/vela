#if canImport(CryptoKit)
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
#if SWIFT_PACKAGE
import VelaCore
#endif

/// 只经 URLSession 的同步传输（docs/plans/sync.md 第 4.3、9、10.1 节）：不跟随重定向、不带 cookie、不用 URL 缓存，
/// User-Agent 固定为 `Vela-Sync/2`。不在这里重试：超时与断网交给协调器保留 `inflight`，退避由调度层决定。
final class SyncHTTPTransport: SyncTransport {
    static let userAgent = "Vela-Sync/2"
    /// 单个请求的超时（设计稿只要求「超时」，未写数值；20 秒足够 800 KB 信封走完一次往返）。
    static let requestTimeout: TimeInterval = 20
    static let maxRetryAfter = 86_400

    let blobURL: URL
    let deleteURL: URL
    let keys: SyncKeyMaterial
    private let session: URLSession
    private let extraHeaders: [String: String]

    /// `base` 在生产中就是同步码里的 origin；测试可以指向本机 `http://127.0.0.1:<port>` 并附加 `CF-Connecting-IP`。
    init(base: URL, keys: SyncKeyMaterial, session: URLSession = SyncHTTPTransport.session, extraHeaders: [String: String] = [:]) {
        blobURL = base.appendingPathComponent("v2/blob/\(keys.blobID)")
        deleteURL = blobURL.appendingPathComponent("delete")
        self.keys = keys
        self.session = session
        self.extraHeaders = extraHeaders
    }

    static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.urlCache = nil
        config.urlCredentialStorage = nil
        config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        config.timeoutIntervalForRequest = requestTimeout
        config.timeoutIntervalForResource = requestTimeout * 2
        config.waitsForConnectivity = false
        config.httpMaximumConnectionsPerHost = 1
        return URLSession(configuration: config, delegate: RefuseRedirects(), delegateQueue: nil)
    }()

    /// 3xx 当错误：拒绝跟随，原样交回 3xx 响应，由 `parse` 归为暂时故障。
    final class RefuseRedirects: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
        }
    }

    enum Kind { case get, put, delete }

    func get() async -> SyncServerResponse {
        await send(request(blobURL, method: "GET", body: nil), kind: .get)
    }

    func put(_ request: SyncPutRequest) async -> SyncServerResponse {
        guard let epoch = request.epoch else { return .transient }
        let message = SyncCrypto.writeMessage(op: "put", blobID: keys.blobID, epoch: epoch, base: request.base, envelopeHash: SyncCrypto.sha256Hex(request.envelope))
        guard let sig = try? SyncCrypto.sign(message, d32: keys.d32) else { return .transient }
        var body: [String: Any] = ["base": request.base, "data": request.envelope, "sig": SyncEncoding.base64url(sig)]
        if request.includesPub { body["pub"] = keys.publicKeyText }
        return await send(self.request(blobURL, method: "PUT", body: body), kind: .put)
    }

    func delete(base: UInt64, epoch: String?) async -> SyncServerResponse {
        guard let epoch else { return .transient }
        let message = SyncCrypto.writeMessage(op: "delete", blobID: keys.blobID, epoch: epoch, base: base, envelopeHash: nil)
        guard let sig = try? SyncCrypto.sign(message, d32: keys.d32) else { return .transient }
        return await send(request(deleteURL, method: "POST", body: ["base": base, "sig": SyncEncoding.base64url(sig)]), kind: .delete)
    }

    private func request(_ url: URL, method: String, body: [String: Any]?) -> URLRequest {
        var r = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData, timeoutInterval: Self.requestTimeout)
        r.httpMethod = method
        r.httpShouldHandleCookies = false
        r.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        for (k, v) in extraHeaders { r.setValue(v, forHTTPHeaderField: k) }
        if let body, let data = try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys]) {
            r.httpBody = data
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return r
    }

    /// 响应体上限：与请求体上限相同（第 10.3 节，最大的合法响应是带完整信封的 409）。
    static let maxResponseBytes = 802_048

    private func send(_ request: URLRequest, kind: Kind) async -> SyncServerResponse {
        guard let (bytes, response) = try? await session.bytes(for: request), let http = response as? HTTPURLResponse else { return .transient }
        // 先看声明的长度，超限不读体；再流式读取，累计超限立即中止，按暂时故障处理。
        if http.expectedContentLength > Int64(Self.maxResponseBytes) {
            bytes.task.cancel()
            return .transient
        }
        var data = Data()
        do {
            for try await byte in bytes {
                guard data.count < Self.maxResponseBytes else {
                    bytes.task.cancel()
                    return .transient
                }
                data.append(byte)
            }
        } catch {
            return .transient
        }
        let retryAfter = http.value(forHTTPHeaderField: "Retry-After").flatMap { Int($0) }
        return Self.parse(status: http.statusCode, body: data, retryAfter: retryAfter, kind: kind)
    }

    /// 把状态码与 JSON 体解析成协调器的响应；非 JSON、截断、字段缺失、3xx 与 5xx 一律暂时故障。
    static func parse(status: Int, body: Data, retryAfter: Int?, kind: Kind) -> SyncServerResponse {
        // Retry-After 夹到 1 秒到 1 天：极大值不能让调度层的乘法溢出，也不该让客户端停一辈子。
        if status == 429 { return .rateLimited(retryAfter: min(maxRetryAfter, max(1, retryAfter ?? 60))) }
        if status == 413 { return .payloadTooLarge }
        if status == 507 { return .quotaFull }
        if status == 403 { return .forbidden }
        guard let obj = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else { return .transient }
        func uint(_ k: String) -> UInt64? {
            guard let n = obj[k] as? NSNumber, CFGetTypeID(n) == CFNumberGetTypeID(), n.int64Value >= 0 else { return nil }
            return n.uint64Value
        }
        func text(_ k: String) -> String? { obj[k] as? String }
        switch status {
        case 200:
            switch kind {
            case .get:
                guard let ver = uint("ver"), let pub = text("pub"), let data = text("data"), let epoch = text("epoch"), let now = uint("now") else { return .transient }
                return .blob(ver: ver, pub: pub, data: data, epoch: epoch, now: now)
            case .put:
                guard let ver = uint("ver"), let epoch = text("epoch"), let now = uint("now") else { return .transient }
                return .stored(ver: ver, epoch: epoch, now: now)
            case .delete:
                return .deleted
            }
        case 404:
            guard let epoch = text("epoch"), let now = uint("now") else { return .transient }
            return .notFound(epoch: epoch, now: now)
        case 409:
            guard let ver = uint("ver"), let pub = text("pub"), let data = text("data"), let epoch = text("epoch"), let now = uint("now") else { return .transient }
            return .conflict(ver: ver, pub: pub, data: data, epoch: epoch, now: now)
        case 410:
            return .gone(deletedAt: uint("deletedAt") ?? 0)
        default:
            return .transient
        }
    }
}
#endif
