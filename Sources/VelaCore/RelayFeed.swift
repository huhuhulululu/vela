import Foundation

/// 媒体列表里与新鲜度有关的信息：TARGETDURATION、最后一个完整分片的媒体序号、它的时长，
/// 以及是否带 `EXT-X-DISCONTINUITY`、最后一个完整分片的 URI（用于判断重推流 / 换地址）。
/// 只数完整分片（EXTINF），LL-HLS 的 EXT-X-PART 不算进展。
public struct MediaPlaylistInfo: Equatable, Sendable {
    public var targetDuration: TimeInterval
    public var lastSequence: Int
    public var lastSegmentDuration: TimeInterval?
    public var hasDiscontinuity: Bool
    public var lastSegmentURI: String?

    public init(
        targetDuration: TimeInterval, lastSequence: Int, lastSegmentDuration: TimeInterval? = nil,
        hasDiscontinuity: Bool = false, lastSegmentURI: String? = nil
    ) {
        self.targetDuration = targetDuration
        self.lastSequence = lastSequence
        self.lastSegmentDuration = lastSegmentDuration
        self.hasDiscontinuity = hasDiscontinuity
        self.lastSegmentURI = lastSegmentURI
    }

    /// 没有任何完整分片返回 nil。缺 TARGETDURATION 时按 `RelayPolicy.defaultTargetDuration`。
    public static func parse(_ playlist: String) -> MediaPlaylistInfo? {
        var target: TimeInterval?
        var sequence = 0
        var count = 0
        var lastDuration: TimeInterval?
        var discontinuity = false
        var lastURI: String?
        var awaitingURI = false
        for raw in playlist.split(whereSeparator: \.isNewline) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("#EXT-X-TARGETDURATION:") {
                target = Double(line.dropFirst("#EXT-X-TARGETDURATION:".count))
            } else if line.hasPrefix("#EXT-X-MEDIA-SEQUENCE:") {
                sequence = Int(line.dropFirst("#EXT-X-MEDIA-SEQUENCE:".count)) ?? 0
            } else if line == "#EXT-X-DISCONTINUITY" {
                discontinuity = true
            } else if line.hasPrefix("#EXTINF:") {
                count += 1
                let value = line.dropFirst("#EXTINF:".count).prefix { $0 != "," }
                lastDuration = Double(value)
                awaitingURI = true
            } else if awaitingURI, !line.isEmpty, !line.hasPrefix("#") {
                lastURI = line
                awaitingURI = false
            }
        }
        guard count > 0 else { return nil }
        let safeTarget = (target ?? 0) > 0 ? target! : RelayPolicy.defaultTargetDuration
        return MediaPlaylistInfo(
            targetDuration: safeTarget, lastSequence: sequence + count - 1, lastSegmentDuration: lastDuration,
            hasDiscontinuity: discontinuity, lastSegmentURI: lastURI
        )
    }
}

/// 中继拉上游失败的原因：只含状态码与错误域码，不含地址。
public enum RelayFailure: Error, Equatable, Sendable {
    case http(Int)
    case transport(domain: String, code: Int)
    case badBody
    case noSegments

    public var label: String {
        switch self {
        case .http(let status): return "http-\(status)"
        case .transport(let domain, let code): return "\(domain)/\(code)"
        case .badBody: return "bad-body"
        case .noSegments: return "no-segments"
        }
    }

    public static func classify(_ error: Error) -> RelayFailure {
        if let failure = error as? RelayFailure { return failure }
        let ns = error as NSError
        return .transport(domain: ns.domain, code: ns.code)
    }
}

public enum RelayPolicy {
    /// 列表里没写 TARGETDURATION 时的兜底（本流实测为 2 秒）。
    public static let defaultTargetDuration: TimeInterval = 2
    /// 没有新完整分片的宽限 = 倍数 × TARGETDURATION。低于 AVPlayer 的约 1.5 × T 判定，
    /// 让它在缓冲耗尽前就看到失败；上游取不到时同样按这个宽限回放旧列表。
    public static let graceFactor: Double = 1

    public static func grace(targetDuration: TimeInterval) -> TimeInterval {
        graceFactor * targetDuration
    }

    /// 超过宽限后，请求挂起等分片前进的最长时间 = 倍数 × TARGETDURATION。正常发布抖动（间隔至多约 1.5 × T，
    /// 实测偶尔 2.8 秒 / T=2）在这段时间内追得上；真实停滞等满后才 503。
    public static let waitFactor: Double = 1

    public static func waitBound(targetDuration: TimeInterval) -> TimeInterval {
        waitFactor * targetDuration
    }

    /// 挂起等待期间重试刷新的间隔（下限 0.25 秒）。
    public static func waitPoll(targetDuration: TimeInterval) -> TimeInterval {
        max(0.25, targetDuration / 4)
    }

    /// 后台刷新节奏：上次有进展约隔一个分片时长（不超过 T）再刷，没进展或失败约隔 T/2（下限 0.25 秒）。
    public static func refreshDelay(info: MediaPlaylistInfo?, productive: Bool) -> TimeInterval {
        let target = info?.targetDuration ?? defaultTargetDuration
        if productive { return min(target, info?.lastSegmentDuration ?? target) }
        return max(0.25, target / 2)
    }
}

/// 一个播放源在中继里的列表状态：最新已知内容、最后一个完整分片何时前进、单次在途与取消。
/// 纯值类型，调用方负责加锁；时间由参数传入。
public struct RelayFeed: Sendable {
    public struct Ticket: Equatable, Sendable {
        let epoch: Int
    }

    public enum Outcome: Sendable {
        case success(body: Data, info: MediaPlaylistInfo)
        case failure(RelayFailure)
    }

    public enum Applied: Equatable, Sendable {
        /// 完整分片前进了；`gap` 是距上次前进的时间。
        case advanced(gap: TimeInterval)
        case unchanged
        /// 比已知进度还旧的结果，不覆盖。
        case older
        case failed(RelayFailure)
        /// 取消后才回来的结果，整个丢弃。
        case discarded
    }

    public enum Serve: Equatable, Sendable {
        case empty
        /// 宽限内：`noAdvanceFor` 是完整分片已多久没前进，`replay` 表示这一版内容之前已给过 AVPlayer。
        case body(Data, noAdvanceFor: TimeInterval, replay: Bool)
        /// 超过宽限，不能再给旧内容。
        case expired(noAdvanceFor: TimeInterval)
    }

    public private(set) var info: MediaPlaylistInfo?
    public private(set) var lastFailure: RelayFailure?
    public private(set) var isInFlight = false
    private var body: Data?
    private var lastAdvanceAt: Date?
    private var epoch = 0
    private var productive = true
    private var servedSequence: Int?

    public init() {}

    /// 同一个源只有一个在途请求：已有在途返回 nil。
    public mutating func begin() -> Ticket? {
        guard !isInFlight else { return nil }
        isInFlight = true
        return Ticket(epoch: epoch)
    }

    /// 切房 / 暂停 / 换档 / 停止：作废在途请求，之后回来的结果一律丢弃。
    public mutating func cancel() {
        epoch += 1
        isInFlight = false
    }

    /// `addressChanged`：调用方刚换过这一路子列表地址（续期 / 换源），新列表与旧进度不可比，直接采纳。
    public mutating func finish(_ ticket: Ticket, outcome: Outcome, now: Date, addressChanged: Bool = false) -> Applied {
        guard ticket.epoch == epoch else { return .discarded }
        isInFlight = false
        switch outcome {
        case .failure(let failure):
            lastFailure = failure
            productive = false
            return .failed(failure)
        case .success(let newBody, let new):
            lastFailure = nil
            if let current = info, !addressChanged, !Self.replaces(current, with: new) {
                productive = false
                if new.lastSequence < current.lastSequence { return .older }
                info?.targetDuration = new.targetDuration
                return .unchanged
            }
            return adopt(newBody, new, now: now)
        }
    }

    /// 新列表是否应替换已知列表：序号前进；或序号回绕且带 DISCONTINUITY（重推流）；
    /// 或序号相同但最后一个分片 URI 变了（同序号下轮换了地址）。
    static func replaces(_ current: MediaPlaylistInfo, with new: MediaPlaylistInfo) -> Bool {
        if new.lastSequence > current.lastSequence { return true }
        if new.lastSequence < current.lastSequence { return new.hasDiscontinuity }
        guard let old = current.lastSegmentURI, let fresh = new.lastSegmentURI else { return false }
        return old != fresh
    }

    /// 采纳新正文并重置进展计时。非前进的替换（回绕 / 换地址）同样算作前进，`replay` 判定随之清空。
    private mutating func adopt(_ newBody: Data, _ new: MediaPlaylistInfo, now: Date) -> Applied {
        let gap = lastAdvanceAt.map { now.timeIntervalSince($0) } ?? 0
        if let current = info, new.lastSequence <= current.lastSequence { servedSequence = nil }
        body = newBody
        info = new
        lastAdvanceAt = now
        productive = true
        return .advanced(gap: gap)
    }

    public mutating func serve(now: Date) -> Serve {
        guard let body, let info, let lastAdvanceAt else { return .empty }
        let age = max(0, now.timeIntervalSince(lastAdvanceAt))
        guard age <= RelayPolicy.grace(targetDuration: info.targetDuration) else { return .expired(noAdvanceFor: age) }
        let replay = servedSequence == info.lastSequence
        servedSequence = info.lastSequence
        return .body(body, noAdvanceFor: age, replay: replay)
    }

    public func nextRefreshDelay() -> TimeInterval {
        RelayPolicy.refreshDelay(info: info, productive: productive)
    }
}

/// 回答 AVPlayer 时的有界等待：宽限内直接回内容；超过宽限（或还没有内容）不立刻失败，
/// 挂起并反复刷新，分片一前进就返回新内容，等满 `RelayPolicy.waitBound` 仍无进展才返回过期 / 空。
/// 总时长严格有界：每次刷新只拿到剩余预算（调用方据此限时，见 `bounded`），预算用完不再发起新刷新。
/// 时钟、睡眠、刷新、判定都由调用方注入，便于用虚拟时间测试。
public enum RelayWait {
    public static func resolve(
        targetDuration: TimeInterval,
        decide: () -> RelayFeed.Serve,
        refresh: (TimeInterval) async -> Void,
        now: () -> Date,
        sleep: (TimeInterval) async -> Void
    ) async -> RelayFeed.Serve {
        var serve = decide()
        if case .body = serve { return serve }
        let deadline = now().addingTimeInterval(RelayPolicy.waitBound(targetDuration: targetDuration))
        let poll = RelayPolicy.waitPoll(targetDuration: targetDuration)
        while true {
            var remaining = deadline.timeIntervalSince(now())
            if remaining <= 0 { return serve }
            await refresh(remaining)
            serve = decide()
            if case .body = serve { return serve }
            remaining = deadline.timeIntervalSince(now())
            if remaining <= 0 { return serve }
            await sleep(min(poll, remaining))
            serve = decide()
            if case .body = serve { return serve }
        }
    }

    /// 最多等 `budget` 秒：操作先完成就立即返回，超时则不再等（操作本身不被取消，仍自行收尾，结果照常落进状态）。
    @available(macOS 12, *)
    public static func bounded(_ budget: TimeInterval, _ operation: @escaping @Sendable () async -> Void) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let once = Once(continuation)
            let timer = Task {
                try? await Task.sleep(nanoseconds: UInt64(max(0, budget) * 1_000_000_000))
                once.fire()
            }
            Task {
                await operation()
                timer.cancel()
                once.fire()
            }
        }
    }

    @available(macOS 12, *)
    private final class Once: @unchecked Sendable {
        private let lock = NSLock()
        private var continuation: CheckedContinuation<Void, Never>?
        init(_ continuation: CheckedContinuation<Void, Never>) { self.continuation = continuation }
        func fire() {
            lock.lock()
            let pending = continuation
            continuation = nil
            lock.unlock()
            pending?.resume()
        }
    }
}
