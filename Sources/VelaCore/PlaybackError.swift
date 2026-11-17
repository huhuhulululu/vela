import Foundation

/// 播放失败的归类与中文文案。系统给的 localizedDescription 是英文，只进日志，不进界面。
public enum PlaybackErrorKind: Equatable, Sendable {
    case offline
    case timeout
    case unavailable
    case other
}

public enum PlaybackErrorCopy {
    private static let offlineCodes: Set<Int> = [-1009, -1005, -1004, -1003, -1006, -1018, -1020]
    private static let timeoutCodes: Set<Int> = [-1001]
    /// AVFoundation / CoreMedia：无法打开、服务器停止、HTTP 4xx/5xx、播放列表失效。
    private static let unavailableCodes: Set<Int> = [
        -11828, -11850, -11800, -11819, -12660, -12661, -12662, -12667, -12938, -12939, -12888, -12885, -12883
    ]

    /// `chain` 从外到内依次是错误本身与各层 underlying error 的 (domain, code)。
    public static func kind(chain: [(domain: String, code: Int)]) -> PlaybackErrorKind {
        for entry in chain where entry.domain == NSURLErrorDomain {
            if timeoutCodes.contains(entry.code) { return .timeout }
            if offlineCodes.contains(entry.code) { return .offline }
        }
        for entry in chain where unavailableCodes.contains(entry.code) { return .unavailable }
        return .other
    }

    public static func message(for kind: PlaybackErrorKind) -> String {
        switch kind {
        case .offline: return "网络连接中断了，请检查网络后重试"
        case .timeout: return "连接超时，这路画面暂时没有响应"
        case .unavailable: return "这路画面暂时无法播放，可能已下播"
        case .other: return "播放失败，请重试或换一个房间"
        }
    }

    /// 起播迟迟没有开始：有明确的网络类原因时用对应文案，否则说「迟迟没有开始」。
    public static func startTimeoutMessage(chain: [(domain: String, code: Int)]) -> String {
        let kind = kind(chain: chain)
        switch kind {
        case .offline, .timeout: return message(for: kind)
        case .unavailable, .other: return "这路画面迟迟没有开始，请重试或换一个房间"
        }
    }

    public static func failureMessage(chain: [(domain: String, code: Int)]) -> String {
        message(for: kind(chain: chain))
    }
}
