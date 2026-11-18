import Foundation

/// 中继里的一条媒体轨。视频与音轨分开刷新，对应播放列表里的两路。
public enum RelayTrack: Equatable, Sendable, CaseIterable {
    case video
    case audio
}

/// 多源中继的登记表。每个源自己的刷新与静音标志互不影响。
///
/// 双窗最多两路，所以表里最多 `maxSources` 个源。再登记一个新 id 时淘汰最先登记的那个，
/// 把被淘汰的 id 交回去，让调用方停掉它的循环。重复登记同一个 id 只更新静音标志，不改先后，也不淘汰别人。
public struct RelaySourceTable: Equatable, Sendable {
    public static let maxSources = 2

    private struct Source: Equatable, Sendable {
        var refreshing: Bool
        var audioSuppressed: Bool
    }

    /// 下标 0 是最旧的源。
    private var order: [String] = []
    private var sources: [String: Source] = [:]

    public init() {}

    /// 登记一个源。返回被淘汰的 id；没有淘汰时返回 nil。新源默认不在刷新。
    public mutating func register(id: String, audioSuppressed: Bool) -> String? {
        if var existing = sources[id] {
            existing.audioSuppressed = audioSuppressed
            sources[id] = existing
            return nil
        }
        var evicted: String?
        if order.count >= Self.maxSources, let oldest = order.first {
            order.removeFirst()
            sources.removeValue(forKey: oldest)
            evicted = oldest
        }
        order.append(id)
        sources[id] = Source(refreshing: false, audioSuppressed: audioSuppressed)
        return evicted
    }

    public mutating func setRefreshing(_ refreshing: Bool, id: String) {
        guard var source = sources[id] else { return }
        source.refreshing = refreshing
        sources[id] = source
    }

    public mutating func setAudioSuppressed(_ suppressed: Bool, id: String) {
        guard var source = sources[id] else { return }
        source.audioSuppressed = suppressed
        sources[id] = source
    }

    public mutating func remove(id: String) {
        sources.removeValue(forKey: id)
        order.removeAll { $0 == id }
    }

    public mutating func removeAll() {
        sources.removeAll()
        order.removeAll()
    }

    public func contains(id: String) -> Bool {
        sources[id] != nil
    }

    public func isRefreshing(id: String) -> Bool {
        sources[id]?.refreshing ?? false
    }

    public func isAudioSuppressed(id: String) -> Bool {
        sources[id]?.audioSuppressed ?? false
    }

    /// 这一路的后台循环该不该跑。源已经不在表里时两路都是假。
    ///
    /// 视频只看这路是否在刷新。音轨交给 `MuteAudioPolicy`：有独立音轨、这路正在刷新、
    /// 且没有因为静音停掉音频拉取时才跑。没有独立音轨的源不要来问音轨。
    public func shouldRunLoop(id: String, track: RelayTrack) -> Bool {
        guard let source = sources[id] else { return false }
        switch track {
        case .video:
            return source.refreshing
        case .audio:
            return MuteAudioPolicy.shouldRefreshAudioPlaylist(
                hasSeparateAudio: true,
                relayRefreshing: source.refreshing,
                suppressAudioFetch: source.audioSuppressed
            )
        }
    }
}
