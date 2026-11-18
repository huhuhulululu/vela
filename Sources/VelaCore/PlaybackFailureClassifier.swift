import Foundation

/// 把播放失败分成房间重试、多路资源降级，或不可恢复。
///
/// 资源降级会释放非焦点的那一路、且不消耗这间房的重试预算。单窗与焦点路受保护，
/// 永远不会判成资源降级。损坏码流、解码失败、找不到解码器都按房间失败，避免把码流问题误判成资源不足。
public enum PlaybackFailureClassifier {
    public enum Outcome: Equatable, Sendable {
        /// 这间房播失败了，消耗重试预算。
        case roomRetry
        /// 双窗的副路因解码器资源不足失败，不消耗重试预算。
        case resourceDegrade
        /// 重试这间房没有用，也不是双窗解码器不够。
        /// 目前没有这样的码：白名单以外一律按房间重试，避免把未知错误当成不可恢复。
        case fatal
    }

    /// 失败发生时的现场。
    public struct Context: Equatable, Sendable {
        /// 正在双窗。单窗为 false。
        public var isDualWindow: Bool
        /// 失败的是副路。主路为 false。
        public var isSecondary: Bool
        /// 另一路此刻是否在播。调用方带着现场传入；当前不据此改判。
        public var otherIsPlaying: Bool
        /// 这一路是否已经出过画面。调用方带着现场传入；当前不据此改判。
        public var hasPlayed: Bool
        /// 这一路是焦点路（多路时由调用方给）。nil 时按旧口径：主路视为焦点、副路视为非焦点。
        public var isFocus: Bool?

        public init(isDualWindow: Bool, isSecondary: Bool, otherIsPlaying: Bool, hasPlayed: Bool, isFocus: Bool? = nil) {
            self.isDualWindow = isDualWindow
            self.isSecondary = isSecondary
            self.otherIsPlaying = otherIsPlaying
            self.hasPlayed = hasPlayed
            self.isFocus = isFocus
        }

        /// 受保护的焦点路：永远不降级。
        var protected: Bool { isFocus ?? !isSecondary }
    }

    /// 资源降级后的自动重试是有限次指数退避（修订 v2 H3，PR #8 审计）：60、120、240 秒后各一次，之后不再自动重试，
    /// 只在焦点切到该路、减少路数、回前台或用户点重试时再试。
    public static let degradedRetryDelays: [TimeInterval] = [60, 120, 240]

    /// 已自动重试 `attempts` 次之后下一次的等待；用完返回 nil。
    public static func degradedRetryDelay(after attempts: Int) -> TimeInterval? {
        degradedRetryDelays.indices.contains(attempts) ? degradedRetryDelays[attempts] : nil
    }

    /// `AVError.h` 里 `NS_ERROR_ENUM(AVFoundationErrorDomain, AVError)` 的 domain 字符串。
    public static let avFoundationErrorDomain = "AVFoundationErrorDomain"

    /// `AVErrorDecoderTemporarilyUnavailable`。
    /// 数值来源：Apple AVFoundation 公开头文件 `AVError.h`，
    /// `AVErrorDecoderTemporarilyUnavailable = -11839`。
    public static let decoderTemporarilyUnavailableCode = -11839

    /// 资源降级白名单。修订 A1：模拟器测不到硬件解码器并发上限，暂只收上面这一项。
    /// 以后实测要增减码，只改这个数组。
    ///
    /// 同文件中的这些码故意不收，默认按房间失败：
    /// `AVErrorDecodeFailed = -11821`（码流解不开）、
    /// `AVErrorDecoderNotFound = -11833`（找不到解码器）。
    public static let resourceDegradeWhitelist: [(domain: String, code: Int)] = [
        (domain: avFoundationErrorDomain, code: decoderTemporarilyUnavailableCode)
    ]

    /// 错误链任一环命中白名单，且现场是多路里的非焦点路，才降级。其余全是房间重试。
    /// 单窗即使被标成副路也不降级；焦点路受保护（修订 v2 H3）。白名单码出现在内层 underlying error 时同样算命中。
    public static func classify(
        chain: [(domain: String, code: Int)],
        context: Context
    ) -> Outcome {
        if isResourceShortage(chain), context.isDualWindow, !context.protected {
            return .resourceDegrade
        }
        return .roomRetry
    }

    /// 错误链任一环命中资源降级白名单。
    public static func isResourceShortage(_ chain: [(domain: String, code: Int)]) -> Bool {
        chain.contains { entry in
            resourceDegradeWhitelist.contains { item in
                item.domain == entry.domain && item.code == entry.code
            }
        }
    }

    /// 焦点路缺解码器时让出非焦点路的顺序：T → S → P（跳过焦点）。
    public static func yieldOrder(_ lanes: [LaneID], focus: LaneID) -> [LaneID] {
        lanes.reversed().filter { $0 != focus }
    }
}
