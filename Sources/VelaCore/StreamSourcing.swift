import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// 一次解析用的是哪个来源（只记来源，不记地址）。
public enum StreamOrigin: String, Equatable, Sendable {
    /// `chatvideocontext` 的 `hls_source`。
    case chatContext = "chatvideocontext"
    /// `get_edge_hls_url_ajax` 直连。
    case edge = "ajax"
    /// 侧车网页里的 `get_edge_hls_url_ajax`。
    case sidecar
}

/// 解析结果与来源。
public struct SourcedTicket: Equatable, Sendable {
    public var ticket: StreamTicket
    public var origin: StreamOrigin

    public init(ticket: StreamTicket, origin: StreamOrigin) {
        self.ticket = ticket
        self.origin = origin
    }

    /// 不可播放时的原因（可播放为 nil）。
    public var failure: StreamFailure? { ticket.failure }
}

/// 起播视角下 `chatvideocontext` 的结果。
public enum ContextProbe: Equatable, Sendable {
    /// 200 + JSON：房间状态与 `hls_source`（字段缺失、为空或不是 https 时为 nil）。
    case room(show: ShowState, source: URL?)
    /// 正文是质询页。
    case challenge
    /// 正文不是 JSON 也不是质询页（已登记退避）。
    case notJSON
    /// 退避窗口内，没有发请求。
    case backedOff
    /// 网络失败、非 200、JSON 不合格。
    case failed
}

extension ChatContext {
    /// 起播用的解码：只取 `room_status` 与 `hls_source`，不抛错。
    public static func probe(status: Int, data: Data) -> ContextProbe {
        if looksLikeChallenge(data) { return .challenge }
        if ResponseShape.shouldBackOff(status: status, data: data, definitive: definitiveStatuses) { return .notJSON }
        guard status == 200, let payload = try? JSONDecoder().decode(StreamPayload.self, from: data) else { return .failed }
        let source = payload.hlsSource.flatMap { value -> URL? in
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            guard trimmed.hasPrefix("https://") else { return nil }
            return URL(string: trimmed)
        }
        return .room(show: ShowState(apiValue: payload.roomStatus), source: source)
    }

    /// 发请求并解码：退避窗口内不发请求；非 JSON 登记退避。取消原样抛出（不算网络故障），其余网络错误算 `.failed`。
    @available(macOS 10.15, *)
    public static func probe(
        username: String, client: any HTTPClient, backoff: EndpointBackoffBox? = .shared
    ) async throws -> ContextProbe {
        if let backoff, !backoff.allows(.chatContext) { return .backedOff }
        let result: HTTPResult
        do {
            result = try await client.send(request(username: username))
        } catch {
            if RenewalPolicy.isCancellation(error) || Task.isCancelled { throw error }
            return .failed
        }
        let outcome = probe(status: result.status, data: result.data)
        if outcome == .notJSON { backoff?.record(.chatContext) }
        return outcome
    }

    private struct StreamPayload: Decodable {
        var roomStatus: String?
        var hlsSource: String?

        enum CodingKeys: String, CodingKey {
            case roomStatus = "room_status"
            case hlsSource = "hls_source"
        }
    }
}

/// 起播来源的选择：先看 `chatvideocontext`，能定论就不再请求 `get_edge_hls_url_ajax`。纯函数。
public enum StreamSourcing {
    public enum Step: Equatable, Sendable {
        /// 直接采用（可播放，或状态已能定论为不可播放）。
        case use(StreamTicket)
        /// 退回 `get_edge_hls_url_ajax`（及侧车备路）。
        case fallback
    }

    /// 公开且有 `hls_source`：直接用；非公开的已知状态（离开、私密、离线等）：直接给出原因，不再多发请求；
    /// 公开但 `hls_source` 为空、状态未知、请求失败、被质询、非 JSON、退避中：退回 ajax。
    public static func first(_ probe: ContextProbe) -> Step {
        guard case .room(let show, let source) = probe else { return .fallback }
        switch show {
        case .publicShow:
            guard let source else { return .fallback }
            return .use(StreamTicket(success: true, url: source, status: .publicShow))
        case .unknown:
            return .fallback
        default:
            return .use(StreamTicket(success: true, url: nil, status: show))
        }
    }

    /// 退回 ajax 后的定论：`chatvideocontext` 已说公开却没有地址，ajax 也给不出可播地址
    /// （公开无地址，或状态说不清）时，归为地区受限；其余以 ajax 的结果为准。
    public static func settle(probe: ContextProbe, fallback: StreamTicket) -> StreamTicket {
        guard case .room(.publicShow, .none) = probe, !fallback.isPlayable else { return fallback }
        guard fallback.status == .publicShow || fallback.status == .unknown else { return fallback }
        return StreamTicket(success: fallback.success, url: nil, status: .publicShow, message: fallback.message)
    }
}
