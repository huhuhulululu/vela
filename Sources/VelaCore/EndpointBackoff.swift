import Foundation

/// 会做退避的站点接口：按接口记录，互不影响。
public enum SiteEndpoint: String, CaseIterable, Sendable {
    /// `POST /get_edge_hls_url_ajax/`
    case streamURL
    /// `GET /api/ts/roomlist/room-list/`
    case catalog
    /// `GET /api/chatvideocontext/<用户名>/`
    case chatContext
    /// `GET /api/biocontext/<用户名>/`
    case bioContext
    /// `GET https://www.cam4.com/directoryCams`
    case cam4Directory
}

/// 响应正文的形态判断。
public enum ResponseShape {
    /// 正文看起来是 JSON：去掉前导空白（含 UTF-8 BOM）后以 `{` 或 `[` 开头。只看开头，不整体解析
    /// （是否合格由各自的解码判断，截断的 JSON 照旧算格式错误）。质询页、限流页、空正文都不满足。
    public static func looksLikeJSON(_ data: Data) -> Bool {
        var rest = data[...]
        while let byte = rest.first, isWhitespace(byte) { rest = rest.dropFirst() }
        if rest.starts(with: bom) { rest = rest.dropFirst(bom.count) }
        guard let first = rest.first(where: { !isWhitespace($0) }) else { return false }
        return first == UInt8(ascii: "{") || first == UInt8(ascii: "[")
    }

    /// 应当退避：正文既不是 JSON 也不是质询页（限流页、空正文、网关错误页等）。
    /// `definitive` 里的状态码本身就是确定答案（例如 404 不存在、401 需密码），正文是什么都不退避。
    public static func shouldBackOff(status: Int, data: Data, definitive: Set<Int> = []) -> Bool {
        guard !definitive.contains(status) else { return false }
        return !looksLikeChallenge(data) && !looksLikeJSON(data)
    }

    /// 开头完整的 UTF-8 BOM（单个 BOM 字节不是空白）。
    private static let bom: [UInt8] = [0xEF, 0xBB, 0xBF]

    private static func isWhitespace(_ byte: UInt8) -> Bool {
        byte == 0x20 || byte == 0x09 || byte == 0x0A || byte == 0x0D
    }
}

extension EndpointBackoff {
    /// 退避中、没有发请求：留在当前房间，窗口结束后自动再解析（不当作这一间不可播，不跳房）。
    public static let waitingNote = "站点暂时限流，稍后自动重试"
    /// 同上，但自动重试已被暂停等取消：提示手动重试。
    public static let heldNote = "站点暂时限流，可稍后手动重试"
    /// 退避中的提示：门控允许播放才会自动重试，否则只给手动重试文案。
    public static func pendingNote(playbackAllowed: Bool) -> String { playbackAllowed ? waitingNote : heldNote }

    /// 目录取不回来时，因退避窗口内没发请求而显示的原因。
    public static let catalogNote = "站点暂时限流，请稍后再试"
}

/// 按接口的退避窗口：某接口返回非 JSON（且不是质询页）后，`window` 秒内不再向它发请求。纯值类型，时间由参数传入。
public struct EndpointBackoff: Equatable, Sendable {
    public static let window: TimeInterval = 30

    private var until: [SiteEndpoint: Date] = [:]

    public init() {}

    public func allows(_ endpoint: SiteEndpoint, now: Date) -> Bool {
        guard let until = until[endpoint] else { return true }
        return now >= until
    }

    /// 登记一次非 JSON 响应：从 `now` 起 `window` 秒内不再请求该接口。
    public mutating func record(_ endpoint: SiteEndpoint, now: Date) {
        until[endpoint] = now.addingTimeInterval(Self.window)
    }

    public mutating func reset() { until = [:] }
}

/// 处于退避窗口内、没有发出请求。
public struct EndpointBackedOff: Error, Equatable, Sendable {
    public let endpoint: SiteEndpoint

    public init(_ endpoint: SiteEndpoint) { self.endpoint = endpoint }
}

/// 进程内共用的退避记录（各请求方并发读写，用锁保护）。App 用 `shared`；测试各建一份。
public final class EndpointBackoffBox: @unchecked Sendable {
    public static let shared = EndpointBackoffBox()

    private let lock = NSLock()
    private var state = EndpointBackoff()
    private let clock: @Sendable () -> Date

    public init(now: @escaping @Sendable () -> Date = { Date() }) {
        self.clock = now
    }

    public func allows(_ endpoint: SiteEndpoint) -> Bool {
        let now = clock()
        lock.lock()
        defer { lock.unlock() }
        return state.allows(endpoint, now: now)
    }

    public func record(_ endpoint: SiteEndpoint) {
        let now = clock()
        lock.lock()
        defer { lock.unlock() }
        state.record(endpoint, now: now)
    }

    /// 检查响应：应当退避就登记并返回 true。
    @discardableResult
    public func note(_ endpoint: SiteEndpoint, status: Int, data: Data, definitive: Set<Int> = []) -> Bool {
        guard ResponseShape.shouldBackOff(status: status, data: data, definitive: definitive) else { return false }
        record(endpoint)
        return true
    }

    public func reset() {
        lock.lock()
        defer { lock.unlock() }
        state.reset()
    }
}
