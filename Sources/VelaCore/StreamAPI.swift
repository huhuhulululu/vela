import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum StreamFailure: Equatable, Sendable {
    case geo
    case privateShow
    case group
    case away
    case offline
    case hidden
    case password
    case unavailable
}

public struct StreamTicket: Equatable, Sendable {
    public var success: Bool
    public var url: URL?
    public var status: ShowState
    public var message: String

    public init(success: Bool, url: URL?, status: ShowState, message: String = "") {
        self.success = success
        self.url = url
        self.status = status
        self.message = message
    }

    public var isPlayable: Bool {
        success && url != nil && status.isPlayable
    }

    public var failure: StreamFailure? {
        if isPlayable { return nil }
        if status == .publicShow && url == nil { return .geo }
        switch status {
        case .publicShow: return .unavailable
        case .privateShow: return .privateShow
        case .group: return .group
        case .away: return .away
        case .offline, .unknown: return .offline
        case .hidden: return .hidden
        case .password: return .password
        }
    }
}

public enum StreamDecodeError: Error, Equatable {
    /// 正文命中站点质询页（`looksLikeChallenge`），需要用户在确认页通关。
    case challenge
    /// 403 / 503 但正文不是质询页（限流、关播、地区封锁等）：按「暂时无法播放」处理，可自动跳过。
    case unavailable(status: Int)
    case malformed

    /// 是否需要弹确认页；只有 `.challenge` 为 true。
    public var isChallenge: Bool { self == .challenge }

    /// 普通不可用对应的失败原因（用于选文案）；质询与格式错误返回 nil。
    public var failure: StreamFailure? {
        if case .unavailable = self { return .unavailable }
        return nil
    }
}

public enum StreamDecoder {
    public static func decode(status: Int, data: Data) throws -> StreamTicket {
        if looksLikeChallenge(data) {
            throw StreamDecodeError.challenge
        }
        if status == 403 || status == 503 {
            throw StreamDecodeError.unavailable(status: status)
        }
        guard (200..<300).contains(status) else {
            throw StreamDecodeError.malformed
        }
        let payload: StreamPayload
        do {
            payload = try JSONDecoder().decode(StreamPayload.self, from: data)
        } catch {
            throw StreamDecodeError.malformed
        }
        let url = payload.url.flatMap { value -> URL? in
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            guard trimmed.hasPrefix("https://") else { return nil }
            return URL(string: trimmed)
        }
        return StreamTicket(
            success: payload.success.ok,
            url: url,
            status: ShowState(apiValue: payload.roomStatus),
            message: payload.hiddenMessage ?? ""
        )
    }
}

struct StreamPayload: Decodable {
    var success: LooseBool
    var url: String?
    var roomStatus: String?
    var hiddenMessage: String?

    enum CodingKeys: String, CodingKey {
        case success, url
        case roomStatus = "room_status"
        case hiddenMessage = "hidden_message"
    }
}

struct LooseBool: Decodable {
    var ok: Bool

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let bool = try? container.decode(Bool.self) {
            ok = bool
            return
        }
        if let int = try? container.decode(Int.self) {
            ok = int == 1
            return
        }
        if let string = try? container.decode(String.self) {
            ok = string == "1" || string.lowercased() == "true"
            return
        }
        ok = false
    }
}

extension StreamDecoder {
    /// 直连取流地址：退避窗口内不发请求，抛 `EndpointBackedOff`（与真实 403 / 503 的 `.unavailable` 分开）；
    /// 正文不是 JSON 也不是质询页时登记退避，按「暂时不可用」抛 `.unavailable`。网络错误原样抛出。
    @available(macOS 10.15, *)
    public static func fetch(
        username: String, client: any HTTPClient, backoff: EndpointBackoffBox? = .shared
    ) async throws -> StreamTicket {
        if let backoff, !backoff.allows(.streamURL) { throw EndpointBackedOff(.streamURL) }
        let result = try await client.send(StreamRequest.make(username: username))
        if backoff?.note(.streamURL, status: result.status, data: result.data) == true {
            throw StreamDecodeError.unavailable(status: result.status)
        }
        return try decode(status: result.status, data: result.data)
    }
}

public enum StreamRequest {

    public static func make(username: String) -> URLRequest {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "chaturbate.com"
        components.path = "/get_edge_hls_url_ajax/"
        var request = URLRequest(url: components.url!)
        request.httpMethod = "POST"
        HTTPHeaders.applyBrowser(&request, referer: "https://chaturbate.com/\(username)/")
        request.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        let slug = username.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? username
        request.httpBody = "room_slug=\(slug)".data(using: .utf8)
        return request
    }
}
