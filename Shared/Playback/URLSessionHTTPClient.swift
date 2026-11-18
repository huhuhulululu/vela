import Foundation

final class URLSessionHTTPClient: HTTPClient, @unchecked Sendable {
    private let session: URLSession

    init(session: URLSession? = nil) {
        if let session {
            self.session = session
        } else {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.httpShouldSetCookies = false
            configuration.httpCookieAcceptPolicy = .never
            configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
            // 不等网络恢复：断网时立刻失败，12 秒请求超时才真正生效，界面才能给出「网络不通」与重试。
            configuration.waitsForConnectivity = false
            configuration.timeoutIntervalForRequest = 12
            self.session = URLSession(configuration: configuration)
        }
    }

    func send(_ request: URLRequest) async throws -> HTTPResult {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        return HTTPResult(status: http.statusCode, data: data)
    }
}
