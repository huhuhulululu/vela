import Foundation

public struct LadderVariant: Equatable, Sendable {
    public var bandwidth: Int
    public var width: Int
    public var height: Int

    public init(bandwidth: Int, width: Int, height: Int) {
        self.bandwidth = bandwidth
        self.width = width
        self.height = height
    }
}

public enum PlaylistLadder {
    public static func variants(in playlist: String) -> [LadderVariant] {
        var found: [LadderVariant] = []
        for line in playlist.split(whereSeparator: \.isNewline) {
            let text = String(line)
            guard text.contains("EXT-X-STREAM-INF"), let bandwidth = integer(named: "BANDWIDTH", in: text) else { continue }
            let size = resolution(in: text)
            found.append(LadderVariant(bandwidth: bandwidth, width: size?.0 ?? 0, height: size?.1 ?? 0))
        }
        return found.sorted { $0.bandwidth < $1.bandwidth }
    }

    /// Highest media playlist at or under `maxBandwidth`. Chaturbate's master is low-latency HLS;
    /// a single media playlist is easier for AVPlayer to start.
    public static func mediaURL(in playlist: String, master: URL, maxBandwidth: Double) -> URL? {
        let lines = playlist.split(whereSeparator: \.isNewline).map { String($0).trimmingCharacters(in: .whitespaces) }
        var bestBandwidth = Int.min
        var bestURI: String?
        var lowestBandwidth = Int.max
        var lowestURI: String?
        var index = 0
        while index < lines.count {
            let line = lines[index]
            if line.contains("EXT-X-STREAM-INF"), let bandwidth = integer(named: "BANDWIDTH", in: line) {
                let uri = lines.dropFirst(index + 1).first { !$0.isEmpty && !$0.hasPrefix("#") }
                if let uri {
                    if bandwidth < lowestBandwidth {
                        lowestBandwidth = bandwidth
                        lowestURI = uri
                    }
                    if Double(bandwidth) <= maxBandwidth + 20_000, bandwidth > bestBandwidth {
                        bestBandwidth = bandwidth
                        bestURI = uri
                    }
                }
            }
            index += 1
        }
        guard let uri = bestURI ?? lowestURI else { return nil }
        return resolve(uri, base: master)
    }

    /// Full-segment playlist. Chaturbate's media playlists are low-latency HLS; AVPlayer stutters
    /// when it chases partial segments at the live edge. Relative URLs are made absolute so the
    /// rewritten playlist can be served from anywhere.
    public static func steadyPlaylist(_ playlist: String, base: URL) -> String? {
        let lines = playlist.split(whereSeparator: \.isNewline).map {
            String($0).trimmingCharacters(in: .whitespaces)
        }
        guard !lines.contains(where: { $0.contains("EXT-X-STREAM-INF") }) else { return nil }
        var output: [String] = []
        var sawSegment = false
        for line in lines where !line.isEmpty {
            if isLowLatencyTag(line) { continue }
            if line.hasPrefix("#") {
                let rewritten = line.hasPrefix("#EXT-X-VERSION") ? "#EXT-X-VERSION:7" : rewriteTag(line, base: base)
                output.append(rewritten)
                continue
            }
            sawSegment = true
            output.append(resolve(line, base: base)?.absoluteString ?? line)
        }
        guard sawSegment else { return nil }
        if !output.contains(where: { $0.hasPrefix("#EXT-X-VERSION") }) {
            let insertAt = output.first == "#EXTM3U" ? 1 : 0
            output.insert("#EXT-X-VERSION:7", at: insertAt)
        }
        return output.joined(separator: "\n") + "\n"
    }

    private static func isLowLatencyTag(_ line: String) -> Bool {
        line.hasPrefix("#EXT-X-SERVER-CONTROL")
            || line.hasPrefix("#EXT-X-PART")
            || line.hasPrefix("#EXT-X-PRELOAD-HINT")
            || line.hasPrefix("#EXT-X-SKIP")
            || line.hasPrefix("#EXT-X-RENDITION-REPORT")
    }

    private static func rewriteTag(_ line: String, base: URL) -> String {
        guard let range = line.range(of: "URI=\"") else { return line }
        let start = range.upperBound
        guard let end = line[start...].firstIndex(of: "\"") else { return line }
        let raw = String(line[start..<end])
        guard let resolved = resolve(raw, base: base) else { return line }
        return line.replacingCharacters(in: start..<end, with: resolved.absoluteString)
    }

    private static func resolve(_ reference: String, base: URL) -> URL? {
        if let absolute = URL(string: reference), absolute.scheme != nil {
            return absolute
        }
        return URL(string: reference, relativeTo: base)?.absoluteURL
    }

    public static func peakBitrate(pixelHeight: Double, variants: [LadderVariant], resting: Bool) -> Double {
        let cap = PowerPolicy.peakBitrate(pixelHeight: pixelHeight, resting: resting)
        let eligible = variants.filter { Double($0.bandwidth) <= cap + 20_000 }
        if let match = eligible.last {
            return Double(match.bandwidth)
        }
        if let lowest = variants.first {
            return min(cap, Double(lowest.bandwidth))
        }
        return cap
    }

    private static func integer(named name: String, in line: String) -> Int? {
        guard let range = line.range(of: "\(name)=") else { return nil }
        let tail = line[range.upperBound...]
        let digits = tail.prefix { $0.isNumber }
        return Int(digits)
    }

    private static func resolution(in line: String) -> (Int, Int)? {
        guard let range = line.range(of: "RESOLUTION=") else { return nil }
        let tail = line[range.upperBound...]
        let token = tail.prefix { $0 != "," && !$0.isWhitespace }
        let parts = token.split(separator: "x")
        guard parts.count == 2, let width = Int(parts[0]), let height = Int(parts[1]) else { return nil }
        return (width, height)
    }
}

public enum PowerPolicy {
    public static func peakBitrate(pixelHeight: Double, resting: Bool) -> Double {
        let full: Double
        switch pixelHeight {
        case ..<420: full = 896_000
        case ..<700: full = 1_696_000
        case ..<1100: full = 3_096_000
        default: full = 5_128_000
        }
        if resting { return min(full, 896_000) }
        return full
    }
}
