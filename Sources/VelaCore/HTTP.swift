import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct HTTPResult: Equatable, Sendable {
    public var status: Int
    public var data: Data
    public var headers: [String: String]

    public init(status: Int, data: Data, headers: [String: String] = [:]) {
        self.status = status
        self.data = data
        self.headers = headers
    }
}

public protocol HTTPClient: Sendable {
    func send(_ request: URLRequest) async throws -> HTTPResult
}

public enum UserAgent {
    public static let safari = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.2 Safari/605.1.15"
}

public enum HTTPHeaders {
    public static func applyBrowser(_ request: inout URLRequest, referer: String) {
        request.setValue(UserAgent.safari, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
        request.setValue("XMLHttpRequest", forHTTPHeaderField: "X-Requested-With")
        request.setValue(referer, forHTTPHeaderField: "Referer")
        request.setValue("https://chaturbate.com", forHTTPHeaderField: "Origin")
        request.timeoutInterval = 12
    }
}
